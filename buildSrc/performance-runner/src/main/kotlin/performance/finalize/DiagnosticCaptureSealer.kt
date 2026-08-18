/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant
import performance.capture.PrivacyFilter
import performance.compare.CaptureBundleVerifier
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.*
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
internal enum class DiagnosticSealFailure {
  OPERATION_PATH_INVALID,
  BUNDLE_PATH_INVALID,
  LAYOUT_INVALID,
  PROVISIONAL_INVALID,
  CONTENT_INVALID,
  SCHEMA_INVALID,
  MATERIALIZATION_FAILED,
  VERIFICATION_FAILED,
}
internal enum class DiagnosticSealPoint { AFTER_MANIFEST_DURABLE, BEFORE_VERIFIER, BEFORE_MOVE }
internal fun interface DiagnosticSealCheckpoint { fun reached(point: DiagnosticSealPoint) }
internal sealed interface DiagnosticSealOutcome {
  class Sealed internal constructor(mint: Any, val projection: CaptureBundleVerifier.Projection) : DiagnosticSealOutcome {
    init { require(mint === SEALED_MINT) { "diagnostic seal must be verifier-backed" } }
    val root: Path get() = projection.root; val captureSha256: Sha256 get() = projection.captureSha256
    val manifestSha256: Sha256 get() = projection.bundleSha256
    companion object {
      @JvmSynthetic internal fun verified(projection: CaptureBundleVerifier.Projection) = Sealed(SEALED_MINT, projection)
    }
  }
  class Rejected internal constructor(val reason: DiagnosticSealFailure) : DiagnosticSealOutcome
}
private val SEALED_MINT = Any()
internal object DiagnosticCaptureSealer {
  @JvmSynthetic
  fun seal(
    provisional: ProvisionalCaptureDocument,
    operationRoot: Path,
    bundleRoot: Path,
    qualification: QualificationEvidence,
  ): DiagnosticSealOutcome = seal(provisional, operationRoot, bundleRoot, qualification, NOOP_CHECKPOINT)
  @JvmSynthetic
  fun seal(
    provisional: ProvisionalCaptureDocument,
    operationRoot: Path,
    bundleRoot: Path,
    qualification: QualificationEvidence,
    checkpoint: DiagnosticSealCheckpoint,
  ): DiagnosticSealOutcome {
    if (!safeExistingDirectory(operationRoot))
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.OPERATION_PATH_INVALID)
    if (!safeAbsentPath(bundleRoot) || pathsOverlap(operationRoot, bundleRoot))
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.BUNDLE_PATH_INVALID)
    if (!runCatching { exactOperationLayout(operationRoot) }.getOrDefault(false))
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.LAYOUT_INVALID)
    if (!validProvisional(provisional, qualification))
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.PROVISIONAL_INVALID)
    val inputs =
      runCatching {
          val jmh = readBounded(operationRoot.resolve(JMH_RESULT_JSON), MAX_JMH_BYTES)
          val stdout = readBoundedLog(operationRoot.resolve(STDOUT_LOG))
          val stderr = readBoundedLog(operationRoot.resolve(STDERR_LOG))
          ValidatedInputs(jmh, stdout, stderr)
        }
        .getOrElse {
          return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.CONTENT_INVALID)
        }
    val document =
      CaptureDocument(
        schemaVersion = "capture-v1",
        benchmarkProtocolVersion = provisional.benchmarkProtocolVersion,
        identity = provisional.identity,
        outcome =
          CaptureOutcome(
            status = EvidenceStatus.VALID,
            strength = EvidenceStrength.DIAGNOSTIC,
            claimEligibilityReasons = listOf(FinalOutcomeReason.BOUNDED_DIAGNOSTIC),
            startedAtUtc = provisional.outcome.startedAtUtc,
            completedAtUtc = provisional.outcome.completedAtUtc,
            processExit = 0,
          ),
        provenance = provisional.provenance,
        protocol = provisional.protocol,
        artifacts = provisional.artifacts,
        toolchain = provisional.toolchain,
        runtime = provisional.runtime,
        qualification = qualification,
        logging = provisional.logging,
        profile = provisional.profile,
        cells = provisional.cells,
      )
    val captureBytes = CanonicalJson.encode(render(document))
    if (EvidenceSchemaValidator().validate(SchemaKind.CAPTURE, captureBytes).isNotEmpty()) {
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.SCHEMA_INVALID)
    }
    val files =
      linkedMapOf(
        CAPTURE_JSON to captureBytes,
        JMH_RESULT_JSON to inputs.jmh,
        STDOUT_LOG to inputs.stdout,
        STDERR_LOG to inputs.stderr,
      )
    val manifest = checksumManifest(files)
    val staging =
      runCatching { Files.createTempDirectory(bundleRoot.parent, ".${bundleRoot.fileName}.staging-") }
        .getOrElse {
          return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.MATERIALIZATION_FAILED)
        }
    if (runCatching {
        files.forEach { (name, bytes) -> writeFsynced(staging.resolve(name), bytes) }
        writeFsynced(staging.resolve(CHECKSUMS), manifest)
        fsync(staging)
        checkpoint.reached(DiagnosticSealPoint.AFTER_MANIFEST_DURABLE)
      }.isFailure) {
      cleanupOwnedTree(staging)
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.MATERIALIZATION_FAILED)
    }
    if (runCatching {
        checkpoint.reached(DiagnosticSealPoint.BEFORE_VERIFIER)
        require(!Files.exists(bundleRoot, NOFOLLOW_LINKS))
        CaptureBundleVerifier.verify(staging).verifiedProjection()
      }.isFailure) {
      cleanupOwnedTree(staging)
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.VERIFICATION_FAILED)
    }
    if (runCatching {
        checkpoint.reached(DiagnosticSealPoint.BEFORE_MOVE)
        require(safeAbsentPath(bundleRoot))
        Files.move(staging, bundleRoot, ATOMIC_MOVE)
      }.isFailure) {
      cleanupOwnedTree(staging)
      return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.MATERIALIZATION_FAILED)
    }
    val finalProjection =
      runCatching {
          val projection = CaptureBundleVerifier.verify(bundleRoot).verifiedProjection()
          fsync(bundleRoot.parent)
          projection
        }
        .getOrElse {
          cleanupOwnedTree(bundleRoot)
          runCatching { fsync(bundleRoot.parent) }
          return DiagnosticSealOutcome.Rejected(DiagnosticSealFailure.VERIFICATION_FAILED)
        }
    return DiagnosticSealOutcome.Sealed.verified(finalProjection)
  }
  private fun validProvisional(
    document: ProvisionalCaptureDocument,
    qualification: QualificationEvidence,
  ): Boolean {
    val outcome = document.outcome
    val timestamps =
      runCatching { Instant.parse(outcome.startedAtUtc) to Instant.parse(outcome.completedAtUtc) }
        .getOrNull() ?: return false
    val bounded = qualification as? QualificationEvidence.ControlledMacBoundedDiagnostic ?: return false
    val profile = document.profile
    return document.schemaVersion == "capture-provisional-v1" &&
      document.benchmarkProtocolVersion == "performance-v1" &&
      outcome.status == EvidenceStatus.VALID &&
      outcome.strength == ProvisionalEvidenceStrength.DIAGNOSTIC &&
      outcome.reasons == listOf(ProvisionalOutcomeReason.BOUNDED_DIAGNOSTIC) &&
      outcome.processExit == 0 &&
      !timestamps.second.isBefore(timestamps.first) &&
      document.rawProfilerInputSha256 == null &&
      profile.family in setOf("cold", "warm") &&
      profile.profiler == "none" &&
      profile.forks in setOf(10, 20, 40) &&
      profile.measurementIterations == (if (profile.family == "warm") 10 else 1) &&
      profile.warmupIterations == (if (profile.family == "warm") 5 else 0) &&
      document.cells.isNotEmpty() &&
      document.cells.all { cell ->
        cell.sampleDimensions.forks == profile.forks &&
          cell.sampleDimensions.measurementIterations == profile.measurementIterations &&
          cell.sampleDimensions.samplesPerFork == profile.measurementIterations
      } &&
      listOf(
          document.provenance.treatment,
          document.provenance.immutableHarness,
          document.provenance.distributionFreezer,
          document.provenance.captureRunner,
        )
        .all(GitProvenance::treeClean) &&
      bounded.policyHash == document.protocol.qualificationPolicySha256 &&
      bounded.campaignFieldsInapplicableReason == "standaloneBoundedDiagnostic" &&
      validArtifacts(document.artifacts)
  }
  private fun validArtifacts(artifacts: CaptureArtifacts): Boolean {
    val classpath = artifacts.orderedClasspath
    val runnerClasspath = artifacts.orderedRunnerClasspath
    return classpath.count { it.path == artifacts.production.path && it.sha256 == artifacts.production.sha256 } == 1 &&
      classpath.count { it.path == artifacts.benchmark.path && it.sha256 == artifacts.benchmark.sha256 } == 1 &&
      runnerClasspath.count {
        it.path == artifacts.executingRunner.path && it.sha256 == artifacts.executingRunner.sha256
      } == 1 &&
      (classpath + runnerClasspath).all { validRelativePath(it.path) } &&
      (classpath.map(ArtifactIdentity::path).distinct().size == classpath.size) &&
      (runnerClasspath.map(ArtifactIdentity::path).distinct().size == runnerClasspath.size) &&
      artifacts.dependencies.map(DependencyIdentity::coordinate).distinct().size ==
        artifacts.dependencies.size
  }
  private fun exactOperationLayout(root: Path): Boolean {
    val entries = Files.list(root).use { it.toList() }
    return entries.map { it.fileName.toString() }.toSet() == OPERATION_FILES &&
      entries.all { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
  }
  private fun safeExistingDirectory(path: Path): Boolean =
    path.isAbsolute &&
      path == path.toAbsolutePath().normalize() &&
      !hasSymbolicLinkComponent(path) &&
      Files.isDirectory(path, NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(path)
  private fun safeAbsentPath(path: Path): Boolean =
    path.isAbsolute &&
      path == path.toAbsolutePath().normalize() &&
      path.fileName != null &&
      SAFE_NAME.matches(path.fileName.toString()) &&
      !Files.exists(path, NOFOLLOW_LINKS) &&
      path.parent?.let(::safeExistingDirectory) == true
  private fun pathsOverlap(left: Path, right: Path): Boolean = left.startsWith(right) || right.startsWith(left)
  private fun hasSymbolicLinkComponent(path: Path): Boolean {
    var current = path.root ?: return true
    return path.any { component ->
      current = current.resolve(component)
      Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)
    }
  }
  private fun validRelativePath(value: String): Boolean {
    val path = runCatching { Path.of(value) }.getOrNull() ?: return false
    return value.isNotBlank() && !path.isAbsolute && path.normalize() == path &&
      path.none { it.toString() == ".." } && '\\' !in value
  }
  private fun readBounded(path: Path, limit: Long): ByteArray {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    require(Files.size(path) in 0..limit)
    return Files.readAllBytes(path)
  }
  private fun readBoundedLog(path: Path): ByteArray {
    val bytes = readBounded(path, MAX_LOG_BYTES)
    val text =
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    require(PrivacyFilter().sanitize(text) == text)
    return bytes
  }
  private fun checksumManifest(files: Map<String, ByteArray>): ByteArray =
    files.entries
      .sortedWith { left, right -> compareUnsignedUtf8(left.key, right.key) }
      .joinToString(separator = "\n", postfix = "\n") { (name, bytes) ->
        "${Sha256.digest(bytes).hex}  $name"
      }
      .encodeToByteArray()
  private fun compareUnsignedUtf8(left: String, right: String): Int {
    val first = left.encodeToByteArray()
    val second = right.encodeToByteArray()
    val difference = (0 until minOf(first.size, second.size)).firstOrNull { first[it] != second[it] }
    return difference?.let { index ->
      (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
    } ?: (first.size - second.size)
  }
  private fun writeFsynced(path: Path, bytes: ByteArray) {
    val options: Array<OpenOption> = arrayOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    FileChannel.open(path, *options).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }
  private fun fsync(path: Path) {
    FileChannel.open(path, READ).use { it.force(true) }
  }
  private fun cleanupOwnedTree(root: Path) {
    BUNDLE_FILES.forEach { name -> runCatching { Files.deleteIfExists(root.resolve(name)) } }
    runCatching { Files.deleteIfExists(root) }
  }
  private fun CaptureBundleVerifier.Verification.verifiedProjection(): CaptureBundleVerifier.Projection {
    require(failures.isEmpty())
    return requireNotNull(projection)
  }
  private fun render(document: CaptureDocument): ObjectNode =
    objectNode {
      set("artifacts", render(document.artifacts))
      put("benchmarkProtocolVersion", document.benchmarkProtocolVersion)
      set("cells", arrayNode(document.cells, ::render))
      set("identity", objectNode {
        put("captureId", document.identity.captureId)
        put("performanceSessionId", document.identity.performanceSessionId)
        put("processRunId", document.identity.processRunId)
        put("sessionSequence", document.identity.sessionSequence)
      })
      set("logging", render(document.logging))
      set("outcome", objectNode {
        set("claimEligibilityReasons", stringArrayNode(listOf("boundedDiagnostic")))
        put("completedAtUtc", document.outcome.completedAtUtc)
        put("processExit", document.outcome.processExit)
        put("startedAtUtc", document.outcome.startedAtUtc)
        put("status", "valid")
        put("strength", "diagnostic")
      })
      set("profile", render(document.profile))
      set("protocol", render(document.protocol))
      set("provenance", render(document.provenance))
      set("qualification", render(document.qualification))
      set("runtime", render(document.runtime))
      put("schemaVersion", document.schemaVersion)
      set("toolchain", render(document.toolchain))
    }
  private fun render(value: CaptureArtifacts): ObjectNode = objectNode {
    set("benchmark", render(value.benchmark))
    set("dependencies", arrayNode(value.dependencies, ::render))
    set("distribution", render(value.distribution))
    set("executingRunner", render(value.executingRunner))
    set("orderedClasspath", arrayNode(value.orderedClasspath, ::render))
    set("orderedRunnerClasspath", arrayNode(value.orderedRunnerClasspath, ::render))
    set("production", render(value.production))
    put("rawJmhInputSha256", value.rawJmhInputSha256.hex)
  }
  private fun render(value: ArtifactIdentity): ObjectNode =
    objectNode { put("path", value.path); put("sha256", value.sha256.hex) }
  private fun render(value: DependencyIdentity): ObjectNode =
    objectNode { put("coordinate", value.coordinate); put("sha256", value.sha256.hex) }
  private fun render(value: CaptureCell): ObjectNode = objectNode {
    put("batchSize", value.batchSize)
    put("benchmark", value.benchmark)
    set("derivedForkSummaries", arrayNode(value.derivedForkSummaries, ::render))
    set("jmhResultRow", render(value.jmhResultRow))
    put("mode", value.mode)
    set("parameters", objectNode { value.parameters.forEach(::put) })
    set("primaryMetric", render(value.primaryMetric))
    set("sampleDimensions", render(value.sampleDimensions))
    put("threads", value.threads)
    put("unit", value.unit)
  }
  private fun render(value: ForkSummary): ObjectNode =
    objectNode { put("fork", value.fork); put("sampleCount", value.sampleCount); put("score", value.score) }
  private fun render(value: JmhResultRowRef): ObjectNode =
    objectNode { put("jsonPointer", value.jsonPointer); put("sha256", value.sha256.hex) }
  private fun render(value: PrimaryMetricIdentity): ObjectNode =
    objectNode { put("direction", value.direction); put("name", value.name) }
  private fun render(value: SampleDimensions): ObjectNode = objectNode {
    put("forks", value.forks); put("measurementIterations", value.measurementIterations)
    put("samplesPerFork", value.samplesPerFork)
  }
  private fun render(value: LoggingProfileIdentity): ObjectNode =
    objectNode { put("configurationSha256", value.configurationSha256.hex); put("profile", value.profile) }
  private fun render(value: CaptureProfileIdentity): ObjectNode = objectNode {
    put("family", value.family)
    put("forks", value.forks)
    put("identity", value.identity)
    put("measurementIterations", value.measurementIterations)
    put("profiler", value.profiler)
    put("variantSha256", value.variantSha256.hex)
    put("warmupIterations", value.warmupIterations)
  }
  private fun render(value: ProtocolIdentity): ObjectNode = objectNode {
    put("benchmarkProtocolSha256", value.benchmarkProtocolSha256.hex)
    put("benchmarkSourceSha256", value.benchmarkSourceSha256.hex)
    put("comparatorSha256", value.comparatorSha256.hex)
    put("hostAdapterSha256", value.hostAdapterSha256.hex)
    put("qualificationPolicySha256", value.qualificationPolicySha256.hex)
    put("rendererSha256", value.rendererSha256.hex)
    put("schemaSha256", value.schemaSha256.hex)
    put("workloadTreeSha256", value.workloadTreeSha256.hex)
  }
  private fun render(value: ProvenanceRoles): ObjectNode = objectNode {
    set("captureRunner", render(value.captureRunner))
    set("distributionFreezer", render(value.distributionFreezer))
    set("immutableHarness", render(value.immutableHarness))
    set("treatment", render(value.treatment))
  }
  private fun render(value: GitProvenance): ObjectNode =
    objectNode { put("gitSha", value.gitSha); put("treeClean", value.treeClean) }
  private fun render(value: QualificationEvidence): ObjectNode =
    (value as QualificationEvidence.ControlledMacBoundedDiagnostic).let { bounded ->
      objectNode {
        put("campaignFieldsInapplicableReason", bounded.campaignFieldsInapplicableReason)
        put("kind", "controlledMacBoundedDiagnostic")
        put("policyHash", bounded.policyHash.hex)
        set("postflight", render(bounded.postflight))
        set("preflight", render(bounded.preflight))
        set("restoration", render(bounded.restoration))
        set("watcher", render(bounded.watcher))
      }
    }
  private fun render(value: HostDocumentRef): ObjectNode =
    objectNode { put("path", value.path); put("sha256", value.sha256.hex) }
  private fun render(value: RuntimeIdentity): ObjectNode = objectNode {
    set("environment", objectNode { value.environment.forEach(::put) })
    put("hostId", value.hostId)
    set("jdk", render(value.jdk))
    set("limits", render(value.limits))
    set("linux", render(value.linux))
    set("network", render(value.network))
    set("oci", render(value.oci))
    set("security", render(value.security))
    set("storage", render(value.storage))
    set("substrate", render(value.substrate))
  }
  private fun render(value: JdkIdentity): ObjectNode = objectNode {
    put("binarySha256", value.binarySha256.hex)
    set("jvmArguments", stringArrayNode(value.jvmArguments))
    put("vendor", value.vendor)
    put("version", value.version)
  }
  private fun render(value: RuntimeLimits): ObjectNode = objectNode {
    put("cpuSet", value.cpuSet); put("memoryBytes", value.memoryBytes)
    put("memorySwapBytes", value.memorySwapBytes); put("pidLimit", value.pidLimit)
  }
  private fun render(value: LinuxIdentity): ObjectNode =
    objectNode { put("architecture", value.architecture); put("kernel", value.kernel); put("os", value.os) }
  private fun render(value: NetworkIdentity): ObjectNode =
    objectNode { put("mode", value.mode); put("pullPolicy", value.pullPolicy) }
  private fun render(value: OciIdentity): ObjectNode = objectNode {
    put("configDigest", value.configDigest); put("imageReference", value.imageReference)
    put("platformManifestDigest", value.platformManifestDigest)
  }
  private fun render(value: SecurityIdentity): ObjectNode = objectNode {
    set("capabilities", stringArrayNode(value.capabilities))
    put("noNewPrivileges", value.noNewPrivileges)
    put("readOnlyRoot", value.readOnlyRoot)
    put("user", value.user)
  }
  private fun render(value: StorageIdentity): ObjectNode = objectNode {
    put("distributionSource", value.distributionSource); set("writableMounts", stringArrayNode(value.writableMounts))
  }
  private fun render(value: SubstrateIdentity): ObjectNode =
    when (value) {
      is SubstrateIdentity.ControlledMac -> objectNode {
        put("dockerDesktopVersion", value.dockerDesktopVersion)
        put("dockerEngineVersion", value.dockerEngineVersion)
        put("hardwareModelClass", value.hardwareModelClass)
        put("kind", "controlledMac")
        put("macosBuild", value.macosBuild)
        put("macosVersion", value.macosVersion)
        set("vmResources", render(value.vmResources))
      }
      is SubstrateIdentity.GithubHosted -> objectNode {
        set("advertisedResources", render(value.advertisedResources))
        put("dockerEngineVersion", value.dockerEngineVersion)
        put("kernel", value.kernel)
        put("kind", "githubHosted")
        put("runnerImageVersion", value.runnerImageVersion)
        put("runnerLabel", value.runnerLabel)
      }
    }
  private fun render(value: AdvertisedResources): ObjectNode =
    objectNode { put("cpus", value.cpus); put("memoryBytes", value.memoryBytes) }
  private fun render(value: ToolchainIdentity): ObjectNode = objectNode {
    put("gradleVersion", value.gradleVersion)
    put("jmhCoreVersion", value.jmhCoreVersion)
    put("jmhPluginVersion", value.jmhPluginVersion)
    put("kotlinCompilerVersion", value.kotlinCompilerVersion)
    put("sanitizerVersion", value.sanitizerVersion)
    put("schemaVersion", value.schemaVersion)
  }
  private fun objectNode(block: ObjectNode.() -> Unit): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply(block)
  private fun <T> arrayNode(values: List<T>, render: (T) -> JsonNode): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply { values.forEach { add(render(it)) } }
  private fun stringArrayNode(values: List<String>): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply { values.forEach(::add) }
  private class ValidatedInputs(val jmh: ByteArray, val stdout: ByteArray, val stderr: ByteArray)
  private const val CAPTURE_JSON = "capture.json"
  private const val JMH_RESULT_JSON = "jmh-result.json"
  private const val STDOUT_LOG = "stdout.log"
  private const val STDERR_LOG = "stderr.log"
  private const val CHECKSUMS = "checksums.sha256"
  private const val MAX_LOG_BYTES = 4L * 1024 * 1024
  private const val MAX_JMH_BYTES = 64L * 1024 * 1024
  private val OPERATION_FILES = setOf(JMH_RESULT_JSON, STDOUT_LOG, STDERR_LOG)
  private val BUNDLE_FILES = listOf(CAPTURE_JSON, JMH_RESULT_JSON, STDOUT_LOG, STDERR_LOG, CHECKSUMS)
  private val NOOP_CHECKPOINT = DiagnosticSealCheckpoint {}
  private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
}

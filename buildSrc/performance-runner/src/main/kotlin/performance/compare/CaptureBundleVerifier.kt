/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.jar.JarFile
import performance.distribution.DistributionClasspathEntry
import performance.distribution.DistributionLayout
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidationRequest
import performance.distribution.DistributionValidator
import performance.distribution.JavaRuntimeIdentity
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

internal data class ArtifactProjection(val path: String, val sha256: Sha256)

internal data class DependencyProjection(val coordinate: String, val sha256: Sha256)

internal data class GitProjection(val gitSha: String, val treeClean: Boolean)

internal data class IdentityProjection(
  val captureId: String,
  val processRunId: String,
  val performanceSessionId: String,
  val sessionSequence: Int,
)

internal data class ProvenanceProjection(
  val treatment: GitProjection,
  val immutableHarness: GitProjection,
  val distributionFreezer: GitProjection,
  val captureRunner: GitProjection,
)

internal data class DistributionProvenanceProjection(
  val treatment: GitProjection,
  val immutableHarness: GitProjection,
  val distributionFreezer: GitProjection,
)

internal data class ProtocolProjection(
  val benchmarkSourceSha256: Sha256,
  val benchmarkProtocolSha256: Sha256,
  val qualificationPolicySha256: Sha256,
  val workloadTreeSha256: Sha256,
  val hostAdapterSha256: Sha256,
  val schemaSha256: Sha256,
  val rendererSha256: Sha256,
  val comparatorSha256: Sha256,
)

internal data class ArtifactSetProjection(
  val production: ArtifactProjection,
  val benchmark: ArtifactProjection,
  val distribution: ArtifactProjection,
  val orderedClasspath: List<ArtifactProjection>,
  val executingRunner: ArtifactProjection,
  val orderedRunnerClasspath: List<ArtifactProjection>,
  val dependencies: List<DependencyProjection>,
)

internal data class ToolchainProjection(
  val gradleVersion: String,
  val jmhPluginVersion: String,
  val jmhCoreVersion: String,
  val kotlinCompilerVersion: String,
  val schemaVersion: String,
  val sanitizerVersion: String,
) {
  fun matches(distribution: DistributionProjection): Boolean =
    gradleVersion == distribution.toolIdentities["gradle"] &&
      jmhPluginVersion == distribution.toolIdentities["jmhGradlePlugin"] &&
      jmhCoreVersion == distribution.toolIdentities["jmhCore"] &&
      kotlinCompilerVersion == distribution.toolIdentities["kotlinCompiler"] &&
      schemaVersion == "evidence-schema-v1" &&
      sanitizerVersion == "privacy-v1"
}

internal data class RuntimeProjection(
  val substrateKind: String,
  val frozen: FrozenRuntimeProjection,
  val hostIdentity: Sha256,
)

internal data class FrozenRuntimeProjection(
  val jdkBinarySha256: Sha256,
  val jdkVendor: String,
  val jdkVersion: String,
  val jvmArguments: List<String>,
  val environment: Map<String, String>,
  val cpuSet: String,
  val memoryBytes: Long,
  val memorySwapBytes: Long,
  val pidLimit: Int,
  val imageReference: String,
  val platformManifestDigest: String,
  val configDigest: String,
  val architecture: String,
  val networkMode: String,
  val pullPolicy: String,
  val capabilities: List<String>,
  val noNewPrivileges: Boolean,
  val readOnlyRoot: Boolean,
  val user: String,
  val distributionSource: String,
  val writableMounts: List<String>,
)

internal data class RuntimeDeclarationProjection(
  val jdkBinarySha256: Sha256,
  val jdkVendor: String,
  val jdkVersion: String,
  val environment: Map<String, String>,
  val cpuSet: String,
  val memoryBytes: Long,
  val memorySwapBytes: Long,
  val pidLimit: Int,
  val imageReference: String,
  val platformManifestDigest: String,
  val configDigest: String,
  val architecture: String,
  val user: String,
  val readOnlyRoot: Boolean,
  val noNewPrivileges: Boolean,
)

internal data class LoggingProjection(
  val profile: String,
  val configurationSha256: Sha256,
)

internal fun RuntimeProjection.matches(
  distribution: DistributionProjection,
  profile: ProfileProjection,
): Boolean {
  val declared = distribution.runtimeDeclarations[substrateKind] ?: return false
  val declaredProfile =
    distribution.profileVariants[profile.family]?.get(profile.identity) ?: return false
  return frozen.jdkBinarySha256 == declared.jdkBinarySha256 &&
    frozen.jdkVendor == declared.jdkVendor &&
    frozen.jdkVersion == declared.jdkVersion &&
    frozen.jvmArguments == declaredProfile.jvmArguments &&
    frozen.environment == declared.environment &&
    frozen.cpuSet == declared.cpuSet &&
    frozen.memoryBytes == declared.memoryBytes &&
    frozen.memorySwapBytes == declared.memorySwapBytes &&
    frozen.pidLimit == declared.pidLimit &&
    frozen.imageReference == declared.imageReference &&
    frozen.platformManifestDigest == declared.platformManifestDigest &&
    frozen.configDigest == declared.configDigest &&
    frozen.architecture == declared.architecture &&
    frozen.networkMode == "none" &&
    frozen.pullPolicy == "never" &&
    frozen.capabilities.isEmpty() &&
    frozen.noNewPrivileges == declared.noNewPrivileges &&
    frozen.readOnlyRoot == declared.readOnlyRoot &&
    frozen.user == declared.user &&
    frozen.distributionSource == "containerVolume" &&
    frozen.writableMounts == listOf("tmp", "operation-output")
}

internal data class ProfileProjection(
  val family: String,
  val identity: String,
  val variantSha256: Sha256,
  val forks: Int,
  val warmupIterations: Int,
  val measurementIterations: Int,
  val profiler: String,
  val jvmArguments: List<String>,
) {
  fun matches(distribution: DistributionProjection): Boolean =
    distribution.profileVariants[family]?.get(identity) == this
}

internal data class DistributionProjection(
  val root: Path,
  val verifiedRunnerClasspath: List<Path>,
  val provenance: DistributionProvenanceProjection,
  val protocolSha256: Sha256,
  val runnerSha256: Sha256,
  val adapterSha256: Sha256,
  val expectedCellsSha256: Sha256,
  val captureSchemaSha256: Sha256,
  val comparisonSchemaSha256: Sha256,
  val bootstrapVectorSha256: Sha256,
  val comparatorSha256: Sha256,
  val rendererSha256: Sha256,
  val qualificationPolicies: Map<String, Sha256>,
  val benchmarkSourceSha256: Sha256,
  val workloadTreeSha256: Sha256,
  val loggingConfigurationSha256: Sha256,
  val benchmarkClasspath: List<ArtifactProjection>,
  val benchmarkCoordinates: Map<String, String>,
  val runnerClasspath: List<ArtifactProjection>,
  val embeddedDependencies: List<DependencyProjection>,
  val expectedCells: Map<String, List<Pair<String, Map<String, String>>>>,
  val profileVariants: Map<String, Map<String, ProfileProjection>>,
  val toolIdentities: Map<String, String>,
  val runtimeDeclarations: Map<String, RuntimeDeclarationProjection>,
  val distributionManifestSha256: Sha256,
)

internal fun ArtifactSetProjection.matchesBaselineDistribution(
  distribution: DistributionProjection,
): Boolean {
  val benchmark = distribution.benchmarkClasspath.singleOrNull {
    it.path == DistributionLayout.BENCHMARK_JAR
  }
  val production = distribution.benchmarkClasspath.singleOrNull {
    it.path == DistributionLayout.PRODUCTION_JAR
  }
  return benchmark != null &&
    production != null &&
    this.benchmark == benchmark &&
    this.production == production &&
    this.distribution ==
      ArtifactProjection(DistributionLayout.CHECKSUM_MANIFEST, distribution.distributionManifestSha256) &&
    orderedClasspath == distribution.benchmarkClasspath &&
    executingRunner == distribution.runnerClasspath.firstOrNull() &&
    orderedRunnerClasspath == distribution.runnerClasspath &&
      dependencies == distributionDependencies(distribution)
}

internal fun ArtifactSetProjection.matchesCandidateProjection(
  distribution: DistributionProjection,
): Boolean {
  val expectedBenchmark = distribution.benchmarkClasspath.singleOrNull {
    it.path == DistributionLayout.BENCHMARK_JAR
  }
  val expectedProduction = distribution.benchmarkClasspath.singleOrNull {
    it.path == DistributionLayout.PRODUCTION_JAR
  }
  if (expectedBenchmark == null || expectedProduction == null) return false
  val expectedClasspath =
    distribution.benchmarkClasspath.map { artifact ->
      if (artifact.path == expectedProduction.path) production else artifact
    }
  return benchmark == expectedBenchmark &&
    production.path == expectedProduction.path &&
    orderedClasspath == expectedClasspath &&
    executingRunner == distribution.runnerClasspath.firstOrNull() &&
    orderedRunnerClasspath == distribution.runnerClasspath &&
      dependencies == distributionDependencies(distribution)
}

private fun distributionDependencies(distribution: DistributionProjection): List<DependencyProjection> {
  val excluded = setOf(DistributionLayout.BENCHMARK_JAR, DistributionLayout.PRODUCTION_JAR)
  return distribution.benchmarkClasspath.filterNot { it.path in excluded }.map { artifact ->
    DependencyProjection(
      coordinate = checkNotNull(distribution.benchmarkCoordinates[artifact.path]),
      sha256 = artifact.sha256,
    )
  } + distribution.embeddedDependencies
}

internal fun CaptureBundleVerifier.Projection.cellsMatchExpected(
  distribution: DistributionProjection,
): Boolean =
  cells.map { it.benchmark to it.parameters } == distribution.expectedCells[profile.family]

internal object CaptureBundleVerifier {
  class Projection internal constructor(
    mint: Any,
    val root: Path,
    val captureSha256: Sha256,
    val bundleSha256: Sha256,
    val schemaVersion: String,
    val benchmarkProtocolVersion: String,
    val identity: IdentityProjection,
    val outcomeStatus: String,
    val outcomeStrength: String,
    val processExit: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val provenance: ProvenanceProjection,
    val protocol: ProtocolProjection,
    val artifacts: ArtifactSetProjection,
    val toolchain: ToolchainProjection,
    val runtime: RuntimeProjection,
    val qualificationKind: String,
    val qualificationPolicySha256: Sha256,
    val logging: LoggingProjection,
    val profile: ProfileProjection,
    val cells: List<CellIdentity>,
    val samples: Map<CellIdentity, List<ForkSamples>>,
    val profilerSummaryPresent: Boolean,
  ) {
    init {
      require(mint === PROJECTION_MINT) { "capture projection must be verifier-minted" }
    }
  }

  class Verification internal constructor(
    val projection: Projection?,
    val failures: List<CompatibilityFailure>,
  )

  fun verify(rootInput: Path): Verification {
    val root = rootInput.toAbsolutePath().normalize()
    val layoutFailures = verifyLayout(root)
    val snapshot = snapshot(root)
    val checksum = verifyChecksums(snapshot)
    val captureBytes = snapshot[CAPTURE_JSON]
    val document =
      captureBytes?.let { bytes ->
        runCatching { CanonicalJson.parseStrict(bytes) as? ObjectNode }.getOrNull()
      }
    val schemaFailures =
      if (
        captureBytes == null ||
          document == null ||
          !CanonicalJson.encode(document).contentEquals(captureBytes) ||
          EvidenceSchemaValidator().validate(SchemaKind.CAPTURE, captureBytes).isNotEmpty()
      ) {
        listOf(CompatibilityFailure.BUNDLE_SCHEMA_INVALID)
      } else {
        emptyList()
      }
    val jmh =
      if (document == null) {
        JmhVerification(null, listOf(CompatibilityFailure.BUNDLE_SCHEMA_INVALID))
      } else {
        runCatching { verifyJmh(document, snapshot[JMH_RESULT_JSON]) }
          .getOrElse {
            JmhVerification(null, listOf(CompatibilityFailure.BUNDLE_SCHEMA_INVALID))
          }
      }
    val failures =
      (layoutFailures + checksum.failures + schemaFailures + jmh.failures)
        .distinct()
        .sortedBy(Enum<*>::name)
    val projection =
      if (
        failures.isNotEmpty() ||
          document == null ||
          captureBytes == null ||
          checksum.manifestBytes == null ||
          jmh.samples == null
      ) {
        null
      } else {
        runCatching {
            projection(
              root = root,
              document = document,
              captureSha256 = Sha256.digest(captureBytes),
              bundleSha256 = Sha256.digest(checksum.manifestBytes),
              samples = jmh.samples,
              profilerSummaryPresent = PROFILER_SUMMARY in snapshot,
            )
          }
          .getOrNull()
      }
    return Verification(projection, failures)
  }

  private fun projection(
    root: Path,
    document: ObjectNode,
    captureSha256: Sha256,
    bundleSha256: Sha256,
    samples: Map<CellIdentity, List<ForkSamples>>,
    profilerSummaryPresent: Boolean,
  ): Projection {
    val identity = document.objectNode("identity")
    val outcome = document.objectNode("outcome")
    val provenance = document.objectNode("provenance")
    val protocol = document.objectNode("protocol")
    val artifacts = document.objectNode("artifacts")
    val toolchain = document.objectNode("toolchain")
    val qualification = document.objectNode("qualification")
    val profile = document.objectNode("profile")
    return Projection(
      mint = PROJECTION_MINT,
      root = root,
      captureSha256 = captureSha256,
      bundleSha256 = bundleSha256,
      schemaVersion = document.text("schemaVersion"),
      benchmarkProtocolVersion = document.text("benchmarkProtocolVersion"),
      identity =
        IdentityProjection(
          captureId = identity.text("captureId"),
          processRunId = identity.text("processRunId"),
          performanceSessionId = identity.text("performanceSessionId"),
          sessionSequence = identity.get("sessionSequence").asInt(),
        ),
      outcomeStatus = outcome.text("status"),
      outcomeStrength = outcome.text("strength"),
      processExit = outcome.get("processExit").asInt(),
      startedAt = Instant.parse(outcome.text("startedAtUtc")),
      completedAt = Instant.parse(outcome.text("completedAtUtc")),
      provenance =
        ProvenanceProjection(
          treatment = provenance.git("treatment"),
          immutableHarness = provenance.git("immutableHarness"),
          distributionFreezer = provenance.git("distributionFreezer"),
          captureRunner = provenance.git("captureRunner"),
        ),
      protocol =
        ProtocolProjection(
          benchmarkSourceSha256 = protocol.sha("benchmarkSourceSha256"),
          benchmarkProtocolSha256 = protocol.sha("benchmarkProtocolSha256"),
          qualificationPolicySha256 = protocol.sha("qualificationPolicySha256"),
          workloadTreeSha256 = protocol.sha("workloadTreeSha256"),
          hostAdapterSha256 = protocol.sha("hostAdapterSha256"),
          schemaSha256 = protocol.sha("schemaSha256"),
          rendererSha256 = protocol.sha("rendererSha256"),
          comparatorSha256 = protocol.sha("comparatorSha256"),
        ),
      artifacts =
        ArtifactSetProjection(
          production = artifacts.artifact("production"),
          benchmark = artifacts.artifact("benchmark"),
          distribution = artifacts.artifact("distribution"),
          orderedClasspath = artifacts.artifacts("orderedClasspath"),
          executingRunner = artifacts.artifact("executingRunner"),
          orderedRunnerClasspath = artifacts.artifacts("orderedRunnerClasspath"),
          dependencies = artifacts.dependencies("dependencies"),
        ),
      toolchain =
        ToolchainProjection(
          gradleVersion = toolchain.text("gradleVersion"),
          jmhPluginVersion = toolchain.text("jmhPluginVersion"),
          jmhCoreVersion = toolchain.text("jmhCoreVersion"),
          kotlinCompilerVersion = toolchain.text("kotlinCompilerVersion"),
          schemaVersion = toolchain.text("schemaVersion"),
          sanitizerVersion = toolchain.text("sanitizerVersion"),
        ),
      runtime = runtimeProjection(document.objectNode("runtime")),
      qualificationKind = qualification.text("kind"),
      qualificationPolicySha256 = qualification.sha("policyHash"),
      logging =
        document.objectNode("logging").let { logging ->
          LoggingProjection(
            profile = logging.text("profile"),
            configurationSha256 = logging.sha("configurationSha256"),
          )
        },
      profile =
        ProfileProjection(
          family = profile.text("family"),
          identity = profile.text("identity"),
          variantSha256 = profile.sha("variantSha256"),
          forks = profile.get("forks").asInt(),
          warmupIterations = profile.get("warmupIterations").asInt(),
          measurementIterations = profile.get("measurementIterations").asInt(),
          profiler = profile.text("profiler"),
          jvmArguments =
            document
              .objectNode("runtime")
              .objectNode("jdk")
              .arrayNode("jvmArguments")
              .values()
              .asSequence()
              .map(JsonNode::asString)
              .toList()
              .let(::immutableList),
        ),
      cells = immutableList(samples.keys),
      samples = immutableSamples(samples),
      profilerSummaryPresent = profilerSummaryPresent,
    )
  }

  private fun verifyLayout(root: Path): List<CompatibilityFailure> {
    if (
      hasSymbolicLinkComponent(root) ||
        !Files.isDirectory(root, NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(root)
    ) {
      return listOf(CompatibilityFailure.BUNDLE_UNSEALED)
    }
    val entries = runCatching { Files.list(root).use { it.toList() } }.getOrElse {
      return listOf(CompatibilityFailure.BUNDLE_UNSEALED)
    }
    if (
      entries.any { path ->
        Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)
      }
    ) {
      return listOf(CompatibilityFailure.BUNDLE_UNSEALED)
    }
    val names = entries.map { it.fileName.toString() }.toSet()
    return if (
      !names.containsAll(REQUIRED_FILES) ||
        names.any { it !in REQUIRED_FILES && it !in OPTIONAL_FILES }
    ) {
      listOf(CompatibilityFailure.BUNDLE_UNSEALED)
    } else {
      emptyList()
    }
  }

  private fun verifyChecksums(snapshot: Map<String, ByteArray>): ChecksumVerification {
    val bytes = snapshot[CHECKSUMS]
      ?: return ChecksumVerification(null, listOf(CompatibilityFailure.BUNDLE_CHECKSUM_INVALID))
    val text = runCatching { bytes.decodeToString() }.getOrNull()
      ?: return ChecksumVerification(bytes, listOf(CompatibilityFailure.BUNDLE_CHECKSUM_INVALID))
    if (!text.endsWith('\n')) {
      return ChecksumVerification(bytes, listOf(CompatibilityFailure.BUNDLE_CHECKSUM_INVALID))
    }
    val entries =
      text.dropLast(1).split('\n').mapNotNull { line ->
        CHECKSUM_LINE.matchEntire(line)?.destructured?.let { (sha, relative) -> sha to relative }
      }
    val expectedNames = snapshot.keys.filter { it != CHECKSUMS }.sorted()
    val names = entries.map(Pair<String, String>::second)
    val structurallyValid =
      entries.size == text.dropLast(1).split('\n').size &&
        names == expectedNames &&
        names.distinct().size == names.size &&
        names.none { it == CHECKSUMS || '/' in it || '\\' in it || it == "." || it == ".." }
    val hashesValid =
      structurallyValid && entries.all { (expected, relative) ->
        snapshot[relative]?.let(Sha256::digest)?.hex == expected
      }
    return ChecksumVerification(
      bytes,
      if (hashesValid) emptyList() else listOf(CompatibilityFailure.BUNDLE_CHECKSUM_INVALID),
    )
  }

  private fun verifyJmh(document: ObjectNode, bytes: ByteArray?): JmhVerification {
    if (bytes == null) {
      return JmhVerification(null, listOf(CompatibilityFailure.BUNDLE_SCHEMA_INVALID))
    }
    val rows = runCatching { CanonicalJson.parseStrict(bytes) as? ArrayNode }.getOrNull()
    if (rows == null || !CanonicalJson.encode(rows).contentEquals(bytes)) {
      return JmhVerification(null, listOf(CompatibilityFailure.BUNDLE_SCHEMA_INVALID))
    }
    val failures = linkedSetOf<CompatibilityFailure>()
    val cellNodes = document.arrayNode("cells")
    if (rows.size() != cellNodes.size() || rows.isEmpty) {
      failures += CompatibilityFailure.CELL_SET_MISMATCH
    }
    val samples = linkedMapOf<CellIdentity, List<ForkSamples>>()
    cellNodes.values().asSequence().forEachIndexed { index, value ->
      val cell = value as ObjectNode
      val row = rows.get(index) as? ObjectNode
      if (row == null) {
        failures += CompatibilityFailure.CELL_SET_MISMATCH
        return@forEachIndexed
      }
      val identity = cellIdentity(document, cell)
      val cellSamples = verifyRow(index, document, cell, row, failures)
      if (samples.put(identity, cellSamples) != null) {
        failures += CompatibilityFailure.CELL_SET_MISMATCH
      }
    }
    return JmhVerification(samples.toMap(), failures.toList().sortedBy(Enum<*>::name))
  }

  private fun verifyRow(
    index: Int,
    document: ObjectNode,
    cell: ObjectNode,
    row: ObjectNode,
    failures: MutableSet<CompatibilityFailure>,
  ): List<ForkSamples> {
    if (row.properties().map { it.key }.toSet() != JMH_ROW_FIELDS) {
      failures += CompatibilityFailure.CELL_IDENTITY_MISMATCH
    }
    val profile = document.objectNode("profile")
    val dimensions = cell.objectNode("sampleDimensions")
    val parameters = cell.stringMap("parameters")
    val rowParameters = (row.get("params") as? ObjectNode)?.strictStringMap()
    val primary = row.get("primaryMetric") as? ObjectNode
    val secondary = row.get("secondaryMetrics") as? ObjectNode
    val rowHash = Sha256.digest(CanonicalJson.encode(row))
    if (
      cell.objectNode("jmhResultRow").text("jsonPointer") != "/$index" ||
        cell.objectNode("jmhResultRow").sha("sha256") != rowHash ||
        row.textOrNull("benchmark") != cell.text("benchmark") ||
        row.textOrNull("mode") != cell.text("mode") ||
        row.integer("threads") != cell.get("threads").asInt() ||
        rowParameters != parameters ||
        primary?.properties()?.map { it.key }?.toSet() != PRIMARY_METRIC_FIELDS ||
        primary?.textOrNull("scoreUnit") != cell.text("unit") ||
        secondary == null ||
        secondary.properties().isNotEmpty()
    ) {
      failures += CompatibilityFailure.CELL_IDENTITY_MISMATCH
    }
    val forks = primary?.get("rawData") as? ArrayNode
    val dimensionsMismatch =
      row.integer("forks") != dimensions.get("forks").asInt() ||
        row.integer("warmupIterations") != profile.get("warmupIterations").asInt() ||
        row.integer("measurementIterations") != dimensions.get("measurementIterations").asInt() ||
        row.integer("warmupBatchSize") != cell.get("batchSize").asInt() ||
        row.integer("measurementBatchSize") != cell.get("batchSize").asInt() ||
        dimensions.get("forks").asInt() != profile.get("forks").asInt() ||
        dimensions.get("measurementIterations").asInt() != profile.get("measurementIterations").asInt() ||
        dimensions.get("samplesPerFork").asInt() != profile.get("measurementIterations").asInt() ||
        forks == null ||
        forks.size() != dimensions.get("forks").asInt()
    if (dimensionsMismatch) failures += CompatibilityFailure.SAMPLE_DIMENSION_MISMATCH
    if (
      !isExactStructuralCanary(document, profile, dimensions) &&
        dimensions.get("forks").asInt() < MINIMUM_FORKS
    ) {
      failures += CompatibilityFailure.UNDERSAMPLED_CELL
    }
    if (forks == null) return emptyList()
    val summaries = cell.arrayNode("derivedForkSummaries")
    if (summaries.size() != forks.size()) failures += CompatibilityFailure.DERIVED_SUMMARY_MISMATCH
    return forks.values().asSequence().mapIndexed { forkIndex, value ->
      val observations = value as? ArrayNode
      if (observations == null || observations.size() != dimensions.get("samplesPerFork").asInt()) {
        failures += CompatibilityFailure.SAMPLE_DIMENSION_MISMATCH
        return@mapIndexed ForkSamples(emptyList())
      }
      val decimals = observations.values().asSequence().mapNotNull { observation ->
        observation.takeIf(JsonNode::isNumber)?.let { node ->
          runCatching { node.decimalValue() }.getOrNull()
        }
      }.toList()
      if (
        decimals.size != observations.size() ||
          decimals.any { decimal ->
            val value = decimal.toDouble()
            decimal.signum() <= 0 || !value.isFinite() || value <= 0.0
          }
      ) {
        failures += CompatibilityFailure.INVALID_PRIMARY_SAMPLE
      }
      if (decimals.isNotEmpty() && forkIndex < summaries.size()) {
        val mean =
          decimals.reduce(BigDecimal::add).divide(BigDecimal(decimals.size), MathContext.DECIMAL128)
        val summary = summaries.get(forkIndex).asObject()
        if (
          summary.get("fork").asInt() != forkIndex + 1 ||
            summary.get("sampleCount").asInt() != decimals.size ||
            summary.get("score").decimalValue().compareTo(mean) != 0
        ) {
          failures += CompatibilityFailure.DERIVED_SUMMARY_MISMATCH
        }
      }
      ForkSamples(immutableList(decimals.map(BigDecimal::toDouble)))
    }.toList()
  }

  private fun isExactStructuralCanary(
    document: ObjectNode,
    profile: ObjectNode,
    dimensions: ObjectNode,
  ): Boolean {
    val outcome = document.objectNode("outcome")
    return outcome.text("strength") == "canary" &&
      outcome.stringList("claimEligibilityReasons") == listOf("structuralCanary") &&
      profile.text("family") == "canary" &&
      profile.get("forks").asInt() == 1 &&
      profile.get("warmupIterations").asInt() == 0 &&
      profile.get("measurementIterations").asInt() == 1 &&
      profile.text("profiler") == "none" &&
      dimensions.get("forks").asInt() == 1 &&
      dimensions.get("measurementIterations").asInt() == 1 &&
      dimensions.get("samplesPerFork").asInt() == 1
  }

  private fun cellIdentity(document: ObjectNode, cell: ObjectNode): CellIdentity =
    CellIdentity(
      benchmark = cell.text("benchmark"),
      profile = document.objectNode("profile").text("family"),
      parameters = cell.stringMap("parameters"),
      mode = cell.text("mode"),
      unit = cell.text("unit"),
      threads = cell.get("threads").asInt(),
      batchSize = cell.get("batchSize").asInt(),
      primaryMetric = cell.objectNode("primaryMetric").text("name"),
      direction =
        when (val direction = cell.objectNode("primaryMetric").text("direction")) {
          "lowerIsBetter" -> "lower-is-better"
          else -> direction
        },
    )

  private data class ChecksumVerification(
    val manifestBytes: ByteArray?,
    val failures: List<CompatibilityFailure>,
  )

  private data class JmhVerification(
    val samples: Map<CellIdentity, List<ForkSamples>>?,
    val failures: List<CompatibilityFailure>,
  )

  private const val CAPTURE_JSON = "capture.json"
  private const val JMH_RESULT_JSON = "jmh-result.json"
  private const val CHECKSUMS = "checksums.sha256"
  private const val PROFILER_SUMMARY = "profiler-summary.json"
  private const val MINIMUM_FORKS = 10
  private val REQUIRED_FILES = setOf(CAPTURE_JSON, JMH_RESULT_JSON, "stdout.log", "stderr.log", CHECKSUMS)
  private val OPTIONAL_FILES = setOf(PROFILER_SUMMARY)
  private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([A-Za-z0-9._-]+)")
  private val JMH_ROW_FIELDS =
    setOf(
      "benchmark",
      "forks",
      "measurementBatchSize",
      "measurementIterations",
      "mode",
      "params",
      "primaryMetric",
      "secondaryMetrics",
      "threads",
      "warmupBatchSize",
      "warmupIterations",
    )
  private val PRIMARY_METRIC_FIELDS = setOf("rawData", "scoreUnit")
  private val PROJECTION_MINT = Any()
}

internal object ComparisonInputVerifier {
  sealed interface Result {
    class Compatible internal constructor(
      val baseline: CaptureBundleVerifier.Projection,
      val candidate: CaptureBundleVerifier.Projection,
      val calibration: CalibrationBundleVerifier.Projection?,
      val execution: ComparisonExecutionIdentity,
      val distribution: DistributionProjection,
    ) : Result

    class Incompatible internal constructor(
      val execution: ComparisonExecutionIdentity,
      val reasons: List<CompatibilityFailure>,
    ) : Result

    data object InputFailure : Result
  }

  @JvmSynthetic
  internal fun verify(
    request: ComparisonRequest,
    executingRunnerMatches: (DistributionProjection) -> Boolean,
  ): Result {
    val distribution = verifyDistribution(request.runnerDistribution) ?: return Result.InputFailure
    val fallbackPolicy =
      distribution.qualificationPolicies.toSortedMap().values.firstOrNull()
        ?: return Result.InputFailure
    val fallbackExecution = distribution.execution(fallbackPolicy)
    if (!runCatching { executingRunnerMatches(distribution) }.getOrDefault(false)) {
      return Result.Incompatible(
        fallbackExecution,
        listOf(CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH),
      )
    }
    val baseline = CaptureBundleVerifier.verify(request.baseline)
    val candidate = CaptureBundleVerifier.verify(request.candidate)
    val calibration =
      if (request.kind == ComparisonKind.CANDIDATE && request.calibration != null) {
        CalibrationBundleVerifier.verify(request.calibration)
      } else {
        null
      }
    val selectedKind =
      baseline.projection?.qualificationKind ?: candidate.projection?.qualificationKind
    val selectedPolicy =
      selectedKind?.let(distribution.qualificationPolicies::get)
        ?: fallbackPolicy
    val execution = distribution.execution(selectedPolicy)
    val failures = mutableListOf<CompatibilityFailure>()
    failures += baseline.failures
    failures += candidate.failures
    if (samePhysicalPath(request.baseline, request.candidate)) {
      failures += CompatibilityFailure.SAME_CAPTURE
    }
    when {
      baseline.projection != null && candidate.projection != null -> {
        failures +=
          CaptureCompatibility.validate(
            request,
            baseline.projection,
            candidate.projection,
            execution,
            distribution,
          )
        if (request.kind == ComparisonKind.CANDIDATE) {
          when {
            request.calibration == null ->
              failures += CompatibilityFailure.CALIBRATION_EVIDENCE_MISSING
            calibration == null ->
              failures += CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID
            else -> {
              failures += calibration.failures
              calibration.projection?.let { proof ->
                failures +=
                  CalibrationBundleVerifier.validate(
                    proof,
                    baseline.projection,
                    candidate.projection,
                    execution,
                  )
              }
              if (
                samePhysicalPath(request.calibration, request.baseline) ||
                  samePhysicalPath(request.calibration, request.candidate)
              ) {
                failures += CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID
              }
            }
          }
        }
      }

      failures.isEmpty() -> failures += CompatibilityFailure.BUNDLE_SCHEMA_INVALID
    }
    val ordered = failures.distinct().sortedBy(Enum<*>::name)
    return if (ordered.isNotEmpty()) {
      Result.Incompatible(execution, ordered)
    } else {
      Result.Compatible(
        checkNotNull(baseline.projection),
        checkNotNull(candidate.projection),
        calibration?.projection,
        execution,
        distribution,
      )
    }
  }

  private fun verifyDistribution(root: Path): DistributionProjection? {
    val executable =
      runCatching {
          Path.of(checkNotNull(ProcessHandle.current().info().command().orElse(null)))
            .toAbsolutePath()
            .normalize()
        }
        .getOrNull() ?: return null
    val validation =
      DistributionValidator()
        .validate(
          DistributionValidationRequest(
            root = root,
            selectedJava =
              JavaRuntimeIdentity(
                executable = executable,
                featureVersion = Runtime.version().feature(),
                sha256 = Sha256.digest(executable),
              ),
          ),
        )
    val verified = (validation as? DistributionValidation.Valid)?.distribution ?: return null
    return runCatching { distributionProjection(verified) }.getOrNull()
  }

  internal fun distributionProjection(verified: VerifiedDistribution): DistributionProjection {
    val root = verified.root
    val metadata = verified.metadata
    val protocol = metadata.protocol
    val sourceByPath = protocol.sourceClosure.associateBy { it.path }
    fun source(simpleName: String): Sha256 =
      checkNotNull(sourceByPath.entries.singleOrNull { it.key.endsWith("/$simpleName") }?.value?.sha256)
    fun binding(path: String): Sha256 =
      checkNotNull(protocol.bindings().singleOrNull { it.path == path }?.sha256)
    val expectedCells = expectedCells(root.resolve(protocol.expectedCells.path))
    val profiles = profileVariants(root, protocol.profiles)
    return DistributionProjection(
      root = root.toAbsolutePath().normalize(),
      verifiedRunnerClasspath = immutableList(verified.runnerClasspath),
      provenance =
        DistributionProvenanceProjection(
          treatment = GitProjection(metadata.provenance.treatment.gitSha, metadata.provenance.treatment.treeClean),
          immutableHarness =
            GitProjection(
              metadata.provenance.immutableHarness.gitSha,
              metadata.provenance.immutableHarness.treeClean,
            ),
          distributionFreezer =
            GitProjection(
              metadata.provenance.distributionFreezer.gitSha,
              metadata.provenance.distributionFreezer.treeClean,
            ),
        ),
      protocolSha256 = protocol.protocolSha256,
      runnerSha256 = protocol.runner.sha256,
      adapterSha256 = protocol.adapter.sha256,
      expectedCellsSha256 = protocol.expectedCells.sha256,
      captureSchemaSha256 = binding("protocol/schemas/capture-v1.schema.json"),
      comparisonSchemaSha256 = binding("protocol/schemas/comparison-v1.schema.json"),
      bootstrapVectorSha256 = binding("protocol/test-vectors/bootstrap-v1.json"),
      comparatorSha256 = comparatorImplementationAggregate(protocol.sourceClosure),
      rendererSha256 = source("ComparisonRenderer.kt"),
      qualificationPolicies =
        immutableMap(
          buildMap {
            protocol.qualificationPolicies.forEach { binding ->
              when (binding.path) {
                "protocol/qualification/m4max-docker.json" -> {
                  put("controlledMacCampaign", binding.sha256)
                  put("controlledMacBoundedDiagnostic", binding.sha256)
                }
                "protocol/qualification/github-hosted.json" ->
                  put("githubHosted", binding.sha256)
                else -> error("unknown qualification policy path: ${binding.path}")
              }
            }
          }
        ),
      benchmarkSourceSha256 = benchmarkSourceAggregate(protocol.sourceClosure),
      workloadTreeSha256 = workloadTreeSha256(root),
      loggingConfigurationSha256 =
        checkNotNull(
          sourceByPath["source/src/jmh/resources/performance/log4j2-performance.xml"]?.sha256
        ),
      benchmarkClasspath =
        immutableList(metadata.classpath.benchmarkClasspath.map(DistributionClasspathEntry::artifact)),
      benchmarkCoordinates =
        immutableMap(
          metadata.classpath.benchmarkClasspath.associate { entry -> entry.path to entry.coordinate }
        ),
      runnerClasspath =
        immutableList(metadata.classpath.runnerClasspath.map(DistributionClasspathEntry::artifact)),
      embeddedDependencies =
        immutableList(
          metadata.classpath.embeddedDependencies.map { dependency ->
            DependencyProjection(dependency.coordinate, dependency.sha256)
          }
        ),
      expectedCells = immutableMap(expectedCells.mapValues { (_, cells) -> immutableList(cells) }),
      profileVariants =
        immutableMap(profiles.mapValues { (_, variants) -> immutableMap(variants) }),
      toolIdentities = immutableMap(protocol.toolIdentities),
      runtimeDeclarations = runtimeDeclarations(root, protocol.runtimeDeclarations),
      distributionManifestSha256 = Sha256.digest(root.resolve(DistributionLayout.CHECKSUM_MANIFEST)),
    )
  }

  private fun benchmarkSourceAggregate(
    sourceClosure: List<performance.distribution.DistributionArtifactBinding>,
  ): Sha256 {
    val entries = sourceClosure.filter { binding -> binding.path.startsWith(BENCHMARK_SOURCE_PREFIX) }
    require(entries.isNotEmpty())
    val bytes =
      entries.sortedBy { binding -> binding.path }.joinToString(separator = "\n", postfix = "\n") {
        binding ->
        "source\t${binding.sha256.hex}\t${binding.path}"
      }.encodeToByteArray()
    return Sha256.digest(bytes)
  }

  private fun comparatorImplementationAggregate(
    sourceClosure: List<performance.distribution.DistributionArtifactBinding>,
  ): Sha256 {
    val entries =
      COMPARATOR_IMPLEMENTATION_FILES.map { fileName ->
        checkNotNull(sourceClosure.singleOrNull { binding -> binding.path.endsWith("/$fileName") })
      }
    val bytes =
      entries.sortedBy { binding -> binding.path }.joinToString(separator = "\n", postfix = "\n") {
        binding ->
        "source\t${binding.sha256.hex}\t${binding.path}"
      }.encodeToByteArray()
    return Sha256.digest(bytes)
  }

  private fun workloadTreeSha256(root: Path): Sha256 =
    JarFile(root.resolve(DistributionLayout.BENCHMARK_JAR).toFile()).use { jar ->
      val entries = jar.entries().asSequence().filter { entry -> entry.name == WORKLOAD_TREE_ENTRY }.toList()
      require(entries.size == 1 && !entries.single().isDirectory)
      Sha256.digest(jar.getInputStream(entries.single()).use { input -> input.readAllBytes() })
    }

  private fun expectedCells(path: Path): Map<String, List<Pair<String, Map<String, String>>>> {
    val root = CanonicalJson.parseStrict(Files.readAllBytes(path)).asObject()
    return root.objectNode("families").properties().associate { (family, value) ->
      family to
        value.asArray().values().asSequence().map { cellValue ->
          val cell = cellValue.asObject()
          cell.text("benchmark") to checkNotNull(cell.strictStringMap("parameters"))
        }.toList()
    }
  }

  private fun profileVariants(
    root: Path,
    bindings: List<performance.distribution.DistributionArtifactBinding>,
  ): Map<String, Map<String, ProfileProjection>> =
    bindings.associate { binding ->
      val document = CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(binding.path))).asObject()
      val family = document.text("family")
      family to
        document.arrayNode("variants").values().asSequence().associate { value ->
          val variant = value.asObject()
          val profile =
            ProfileProjection(
              family = family,
              identity = variant.text("identity"),
              variantSha256 = Sha256.digest(CanonicalJson.encode(variant)),
              forks = variant.get("forks").asInt(),
              warmupIterations = variant.get("warmupIterations").asInt(),
              measurementIterations = variant.get("measurementIterations").asInt(),
              profiler = variant.text("profiler"),
              jvmArguments =
                document.arrayNode("jvmArguments").values().asSequence().map(JsonNode::asString).toList()
                  .let(::immutableList),
            )
          profile.identity to profile
        }
    }

  private fun runtimeDeclarations(
    root: Path,
    bindings: List<performance.distribution.DistributionArtifactBinding>,
  ): Map<String, RuntimeDeclarationProjection> {
    val documents =
      bindings.map { binding ->
        CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(binding.path))).asObject()
      }
    val runtimes =
      documents.filter { document -> document.text("profileKind") == "runtime" }.associateBy {
        document ->
        document.text("profileId")
      }
    val projections =
      documents.filter { document -> document.text("profileKind") == "substrate" }.associate {
        substrate ->
        val runtime = checkNotNull(runtimes[substrate.text("runtimeProfileId")])
        val image = runtime.objectNode("image")
        val java = runtime.objectNode("java")
        val identity = substrate.objectNode("identity")
        val limits = substrate.objectNode("limits")
        val security = substrate.objectNode("security")
        val environment = checkNotNull(substrate.strictStringMap("environment"))
        val securityOptions = security.stringList("securityOpt")
        when (substrate.text("profileId")) {
          "m4max-docker-linux-arm64-v1" -> "controlledMac"
          "github-hosted-arm64-v1" -> "githubHosted"
          else -> error("unknown substrate profile")
        } to
          RuntimeDeclarationProjection(
            jdkBinarySha256 = java.sha("sha256"),
            jdkVendor =
              java.textOrNull("vendor")
                ?: if (runtime.text("profileId").startsWith("temurin-")) {
                  "Eclipse Adoptium"
                } else {
                  error("runtime vendor is not frozen")
                },
            jdkVersion = java.text("release"),
            environment = immutableMap(environment),
            cpuSet = limits.text("cpusetCpus"),
            memoryBytes = limits.get("memoryBytes").asLong(),
            memorySwapBytes = limits.get("memorySwapBytes").asLong(),
            pidLimit = limits.get("pidsLimit").asInt(),
            imageReference = image.text("reference"),
            platformManifestDigest = image.text("manifestDigest"),
            configDigest = image.text("ociConfigDigest"),
            architecture =
              image.textOrNull("architecture")
                ?: image.objectNode("platform").text("architecture"),
            user = "${identity.get("uid").asInt()}:${identity.get("gid").asInt()}",
            readOnlyRoot = security.get("readOnlyRoot").asBoolean(),
            noNewPrivileges = "no-new-privileges" in securityOptions,
          )
      }
    require(projections.isNotEmpty())
    return immutableMap(projections)
  }

  private fun samePhysicalPath(left: Path, right: Path): Boolean =
    runCatching { Files.isSameFile(left, right) }.getOrDefault(false)

  private const val BENCHMARK_SOURCE_PREFIX = "source/src/jmh/"
  private const val WORKLOAD_TREE_ENTRY = "META-INF/revoman/performance/revup-v3-tree.json"
  private val COMPARATOR_IMPLEMENTATION_FILES =
    setOf(
      "BootstrapV1.kt",
      "CalibrationBundleVerifier.kt",
      "CaptureBundleVerifier.kt",
      "CaptureComparator.kt",
      "CaptureCompatibility.kt",
      "CellIdentity.kt",
    )
}

private fun DistributionProjection.execution(policy: Sha256): ComparisonExecutionIdentity =
  ComparisonExecutionIdentity(
    runnerSha256 = runnerSha256,
    protocolSha256 = protocolSha256,
    adapterSha256 = adapterSha256,
    expectedCellsSha256 = expectedCellsSha256,
    captureSchemaSha256 = captureSchemaSha256,
    comparisonSchemaSha256 = comparisonSchemaSha256,
    bootstrapVectorSha256 = bootstrapVectorSha256,
    comparatorSha256 = comparatorSha256,
    rendererSha256 = rendererSha256,
    qualificationPolicySha256 = policy,
  )

private fun DistributionClasspathEntry.artifact(): ArtifactProjection = ArtifactProjection(path, sha256)

private fun readRegular(path: Path): ByteArray? =
  runCatching {
      path
        .takeIf { candidate ->
          Files.isRegularFile(candidate, NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)
        }
        ?.let(Files::readAllBytes)
    }
    .getOrNull()

private fun snapshot(root: Path): Map<String, ByteArray> =
  runCatching {
      Files.list(root).use { paths ->
        immutableMap(
          paths
            .filter { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) }
            .toList()
            .associate { path -> path.fileName.toString() to Files.readAllBytes(path) }
        )
      }
    }
    .getOrDefault(emptyMap())

private fun hasSymbolicLinkComponent(path: Path): Boolean {
  var current = path.root ?: return true
  path.forEach { component ->
    current = current.resolve(component)
    if (Files.isSymbolicLink(current)) return true
  }
  return false
}

private fun <T> immutableList(values: Collection<T>): List<T> =
  Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
  Collections.unmodifiableMap(LinkedHashMap(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
  Collections.unmodifiableSet(LinkedHashSet(values))

private fun immutableSamples(
  values: Map<CellIdentity, List<ForkSamples>>,
): Map<CellIdentity, List<ForkSamples>> =
  immutableMap(
    values.mapValues { (_, forks) ->
      immutableList(
        forks.map { fork -> ForkSamples(immutableList(fork.measurements)) },
      )
    }
  )

private fun runtimeProjection(runtime: ObjectNode): RuntimeProjection {
  val host = runtime.deepCopy()
  listOf(
      "environment",
      "jdk",
      "limits",
      "network",
      "oci",
      "security",
      "storage",
    )
    .forEach(host::remove)
  host.objectNode("linux").remove("architecture")
  val jdk = runtime.objectNode("jdk")
  val limits = runtime.objectNode("limits")
  val oci = runtime.objectNode("oci")
  val network = runtime.objectNode("network")
  val security = runtime.objectNode("security")
  val storage = runtime.objectNode("storage")
  return RuntimeProjection(
    substrateKind = runtime.objectNode("substrate").text("kind"),
    frozen =
      FrozenRuntimeProjection(
        jdkBinarySha256 = jdk.sha("binarySha256"),
        jdkVendor = jdk.text("vendor"),
        jdkVersion = jdk.text("version"),
        jvmArguments = jdk.stringList("jvmArguments"),
        environment = checkNotNull(runtime.strictStringMap("environment")),
        cpuSet = limits.text("cpuSet"),
        memoryBytes = limits.get("memoryBytes").asLong(),
        memorySwapBytes = limits.get("memorySwapBytes").asLong(),
        pidLimit = limits.get("pidLimit").asInt(),
        imageReference = oci.text("imageReference"),
        platformManifestDigest = oci.text("platformManifestDigest"),
        configDigest = oci.text("configDigest"),
        architecture = runtime.objectNode("linux").text("architecture"),
        networkMode = network.text("mode"),
        pullPolicy = network.text("pullPolicy"),
        capabilities = security.stringList("capabilities"),
        noNewPrivileges = security.get("noNewPrivileges").asBoolean(),
        readOnlyRoot = security.get("readOnlyRoot").asBoolean(),
        user = security.text("user"),
        distributionSource = storage.text("distributionSource"),
        writableMounts = storage.stringList("writableMounts"),
      ),
    hostIdentity = Sha256.digest(CanonicalJson.encode(host)),
  )
}

private fun ObjectNode.text(name: String): String = get(name).asString()

private fun ObjectNode.textOrNull(name: String): String? =
  get(name)?.takeIf(JsonNode::isTextual)?.asString()

private fun ObjectNode.integer(name: String): Int? =
  get(name)?.takeIf(JsonNode::isIntegralNumber)?.asInt()

private fun ObjectNode.sha(name: String): Sha256 = Sha256.parse(text(name))

private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()

private fun ObjectNode.arrayNode(name: String): ArrayNode = get(name).asArray()

private fun ObjectNode.stringMap(name: String): Map<String, String> = objectNode(name).stringMap()

private fun ObjectNode.stringMap(): Map<String, String> =
  immutableMap(properties().associate { (name, value) -> name to value.asString() })

private fun ObjectNode.strictStringMap(name: String): Map<String, String>? =
  (get(name) as? ObjectNode)?.strictStringMap()

private fun ObjectNode.strictStringMap(): Map<String, String>? {
  val entries = properties().toList()
  if (entries.any { (_, value) -> !value.isTextual }) return null
  return immutableMap(entries.associate { (name, value) -> name to value.asString() })
}

private fun ObjectNode.stringList(name: String): List<String> =
  immutableList(
    arrayNode(name).values().asSequence().map { value ->
      require(value.isTextual)
      value.asString()
    }.toList()
  )

private fun ObjectNode.git(name: String): GitProjection =
  objectNode(name).let { value -> GitProjection(value.text("gitSha"), value.get("treeClean").asBoolean()) }

private fun ObjectNode.artifact(name: String): ArtifactProjection =
  objectNode(name).let { value -> ArtifactProjection(value.text("path"), value.sha("sha256")) }

private fun ObjectNode.artifacts(name: String): List<ArtifactProjection> =
  immutableList(
    arrayNode(name).values().asSequence().map { value ->
      value.asObject().let { artifact -> ArtifactProjection(artifact.text("path"), artifact.sha("sha256")) }
    }.toList()
  )

private fun ObjectNode.dependencies(name: String): List<DependencyProjection> =
  immutableList(
    arrayNode(name).values().asSequence().map { value ->
      value.asObject().let { dependency ->
        DependencyProjection(dependency.text("coordinate"), dependency.sha("sha256"))
      }
    }.toList()
  )

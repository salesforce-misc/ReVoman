/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import performance.distribution.DistributionClasspathEntry
import performance.distribution.DistributionGitIdentity
import performance.distribution.DistributionLayout
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.ArtifactIdentity
import performance.model.CaptureArtifacts
import performance.model.CaptureIdentity
import performance.model.CaptureProfileIdentity
import performance.model.DependencyIdentity
import performance.model.EvidenceStatus
import performance.model.GitProvenance
import performance.model.ProvisionalCaptureDocument
import performance.model.ProvisionalCaptureOutcome
import performance.model.ProvisionalEvidenceStrength
import performance.model.ProvisionalOutcomeReason
import performance.model.SubstrateIdentity
import performance.process.ProcessExecutor
import performance.process.ProcessInvocation
import performance.runner.RunnerExit

internal data class CaptureRequest(
  val distribution: VerifiedDistribution,
  val profile: CaptureProfile,
  val identity: CaptureIdentity,
  val provisionalRoot: Path,
)

internal enum class CaptureFailure(internal val preflight: Boolean = false) {
  PROFILE_MISMATCH(true),
  IDENTITY_MISMATCH(true),
  PROTOCOL_MISMATCH(true),
  DISTRIBUTION_MISMATCH(true),
  OUTPUT_PATH_INVALID(true),
  CHILD_PROCESS_FAILED,
  RESULT_MISSING,
  RESULT_EMPTY,
  RESULT_HEADER_ONLY,
  RESULT_MALFORMED,
  RESULT_HAS_ZERO_ROWS,
  RESULT_ROW_MALFORMED,
  DUPLICATE_RESULT_ROW,
  MISSING_RESULT_ROW,
  EXTRA_RESULT_ROW,
  RESULT_GEOMETRY_MISMATCH,
  NONPOSITIVE_PRIMARY_OBSERVATION,
  NONFINITE_PRIMARY_OBSERVATION,
  LOG4J_FALLBACK,
  LOG4J_PROVIDER_FAILED,
  GRAAL_PACKAGING_FAILED,
  SCENARIO_INVARIANT_FAILED,
  TEARDOWN_FAILED,
  PROFILER_DATA_INVALID,
  LOG_OUTPUT_INVALID,
  CAPTURE_IO_FAILED,
}

internal sealed interface CaptureOutcome {
  data class Provisional(val document: ProvisionalCaptureDocument) : CaptureOutcome

  data class Invalid(val reasons: List<CaptureFailure>) : CaptureOutcome {
    val exit: RunnerExit =
      if (reasons.isNotEmpty() && reasons.all(CaptureFailure::preflight)) {
        RunnerExit.INPUT_OR_PREFLIGHT_INVALID
      } else {
        RunnerExit.MEASUREMENT_INVALID
      }
  }
}

/** Launches one exact JMH capture and emits unpublished evidence only. */
internal data class JfrCaptureHooks(
  val afterBindingBeforeHash: (Path) -> Unit = {},
)

internal class CaptureRunner private constructor(
  private val processExecutor: ProcessExecutor,
  private val clock: Clock,
  private val canonicalizer: JmhResultCanonicalizer,
  private val privacyFilter: PrivacyFilter,
  private val jfrHooks: JfrCaptureHooks,
) {
  constructor(
    processExecutor: ProcessExecutor,
    clock: Clock = Clock.systemUTC(),
    canonicalizer: JmhResultCanonicalizer = JmhResultCanonicalizer(),
    privacyFilter: PrivacyFilter = PrivacyFilter(),
  ) : this(processExecutor, clock, canonicalizer, privacyFilter, JfrCaptureHooks())

  internal constructor(
    processExecutor: ProcessExecutor,
    jfrHooks: JfrCaptureHooks,
  ) : this(
    processExecutor,
    Clock.systemUTC(),
    JmhResultCanonicalizer(),
    PrivacyFilter(),
    jfrHooks,
  )

  fun capture(request: CaptureRequest): CaptureOutcome {
    preflight(request).takeIf(List<CaptureFailure>::isNotEmpty)?.let {
      return CaptureOutcome.Invalid(it)
    }
    val startedAt = clock.instant().toString()
    val paths =
      runCatching { createOperationPaths(request.provisionalRoot, request.profile) }
        .getOrElse {
          return CaptureOutcome.Invalid(listOf(CaptureFailure.OUTPUT_PATH_INVALID))
        }
    val spec = processSpec(request, paths)
    val processResult = runCatching { processExecutor.execute(spec) }.getOrElse {
      val reasons =
        buildList {
          add(CaptureFailure.CHILD_PROCESS_FAILED)
          if (!sanitizeLogs(paths)) add(CaptureFailure.LOG_OUTPUT_INVALID)
        }
      return CaptureOutcome.Invalid(reasons)
    }
    val signalFailures = detectSignals(paths)
    if (!sanitizeLogs(paths)) {
      return CaptureOutcome.Invalid(listOf(CaptureFailure.LOG_OUTPUT_INVALID))
    }
    if (processResult.exitCode != 0) {
      return CaptureOutcome.Invalid(
        (listOf(CaptureFailure.CHILD_PROCESS_FAILED) + signalFailures).distinct(),
      )
    }
    if (signalFailures.isNotEmpty()) return CaptureOutcome.Invalid(signalFailures)
    val rawBytes =
      when (val result = readResult(paths.rawResultPath)) {
        is ResultRead.Invalid -> return CaptureOutcome.Invalid(listOf(result.reason))
        is ResultRead.Valid -> result.bytes
      }
    val canonical =
      when (val result = canonicalizer.canonicalize(rawBytes, request.profile.geometry, request.profile.expectedCells)) {
        is JmhCanonicalization.Invalid -> return CaptureOutcome.Invalid(result.reasons)
        is JmhCanonicalization.Valid -> result
      }
    if (
      runCatching {
          writeFsynced(paths.canonicalResultPath, canonical.canonicalBytes)
          Files.delete(paths.rawResultPath)
          fsync(paths.root)
        }
        .isFailure
    ) {
      return CaptureOutcome.Invalid(listOf(CaptureFailure.CAPTURE_IO_FAILED))
    }

    val rawProfilerHash =
      when (request.profile.profiler) {
        DiagnosticProfiler.NONE -> null
        DiagnosticProfiler.GC -> {
          val summary =
            ProfilerSummary.fromGc(
              GcProfilerInput(
                captureId = request.identity.captureId,
                rawInputSha256 = canonical.rawInputSha256,
                variantSha256 = request.profile.variantSha256,
                durationNanos = elapsedNanos(startedAt),
                secondaryMetrics = canonical.secondaryMetricScores,
              ),
            )
          if (summary !is ProfilerSummaryBuild.Valid) {
            return CaptureOutcome.Invalid(listOf(CaptureFailure.PROFILER_DATA_INVALID))
          }
          if (
            runCatching {
                writeFsynced(paths.root.resolve("profiler-summary.json"), summary.canonicalBytes)
                fsync(paths.root)
              }
              .isFailure
          ) {
            return CaptureOutcome.Invalid(listOf(CaptureFailure.CAPTURE_IO_FAILED))
          }
          null
        }
        DiagnosticProfiler.JFR -> {
          validateJfrAggregate(paths, request.profile.forks)
            ?: return CaptureOutcome.Invalid(listOf(CaptureFailure.PROFILER_DATA_INVALID))
        }
      }
    val completedAt = clock.instant().toString()
    return CaptureOutcome.Provisional(
      ProvisionalCaptureDocument(
        schemaVersion = "capture-provisional-v1",
        benchmarkProtocolVersion = "performance-v1",
        identity = request.identity,
        outcome =
          ProvisionalCaptureOutcome(
            status = EvidenceStatus.VALID,
            strength = request.profile.provisionalStrength(),
            reasons = listOf(request.profile.provisionalReason()),
            startedAtUtc = startedAt,
            completedAtUtc = completedAt,
            processExit = processResult.exitCode,
          ),
        provenance = request.profile.evidence.provenance,
        protocol = request.profile.evidence.protocol,
        artifacts = artifacts(request.distribution, canonical.rawInputSha256),
        toolchain = request.profile.evidence.toolchain,
        runtime = request.profile.evidence.runtime,
        logging = request.profile.evidence.logging,
        profile =
          CaptureProfileIdentity(
            family = request.profile.family.id,
            identity = request.profile.identity,
            variantSha256 = request.profile.variantSha256,
            forks = request.profile.forks,
            warmupIterations = request.profile.warmupIterations,
            measurementIterations = request.profile.measurementIterations,
            profiler = request.profile.profiler.id,
          ),
        cells = canonical.cells,
        rawProfilerInputSha256 = rawProfilerHash,
      ),
    )
  }

  private fun preflight(request: CaptureRequest): List<CaptureFailure> {
    if (!request.profile.isStructurallyValid()) return listOf(CaptureFailure.PROFILE_MISMATCH)
    if (!validIdentity(request.identity)) return listOf(CaptureFailure.IDENTITY_MISMATCH)
    if (
      request.profile.expectedProtocolSha256 != request.distribution.metadata.protocol.protocolSha256 ||
        request.profile.evidence.protocol.benchmarkProtocolSha256 != request.distribution.metadata.protocol.protocolSha256
    ) {
      return listOf(CaptureFailure.PROTOCOL_MISMATCH)
    }
    if (!distributionMatches(request.distribution, request.profile)) {
      return listOf(CaptureFailure.DISTRIBUTION_MISMATCH)
    }
    if (!validOutputPath(request.provisionalRoot)) return listOf(CaptureFailure.OUTPUT_PATH_INVALID)
    return emptyList()
  }

  private fun validIdentity(identity: CaptureIdentity): Boolean =
    identity.sessionSequence > 0 &&
      listOf(identity.captureId, identity.processRunId, identity.performanceSessionId).all(SAFE_ID::matches)

  private fun distributionMatches(
    distribution: VerifiedDistribution,
    profile: CaptureProfile,
  ): Boolean {
    val classpath = distribution.metadata.classpath
    val provenance = distribution.metadata.provenance
    return classpath.javaRuntime.featureVersion >= 21 &&
      classpath.javaRuntime.executable == profile.selectedJavaExecutable &&
      classpath.javaRuntime.executableSha256 == profile.selectedJavaSha256 &&
      distribution.benchmarkClasspath.isNotEmpty() &&
      distribution.runnerClasspath.isNotEmpty() &&
      distribution.benchmarkClasspath.all(Path::isAbsolute) &&
      distribution.runnerClasspath.all(Path::isAbsolute) &&
      profile.expectedCells.cells.map(ExpectedCell::benchmark).toSet().let { expected ->
        classpath.expectedBenchmarks.containsAll(expected)
      } &&
      profile.evidence.provenance.treatment == provenance.treatment.toModel() &&
      profile.evidence.provenance.immutableHarness == provenance.immutableHarness.toModel() &&
      profile.evidence.provenance.distributionFreezer == provenance.distributionFreezer.toModel()
  }

  private fun validOutputPath(path: Path): Boolean {
    val normalized = path.toAbsolutePath().normalize()
    return path.isAbsolute &&
      path == normalized &&
      SAFE_ID.matches(path.fileName?.toString().orEmpty()) &&
      !Files.exists(path, NOFOLLOW_LINKS) &&
      Files.isDirectory(path.parent, NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(path.parent)
  }

  private fun createOperationPaths(root: Path, profile: CaptureProfile): OperationPaths {
    Files.createDirectory(root)
    val stdout = root.resolve("stdout.log")
    val stderr = root.resolve("stderr.log")
    writeFsynced(stdout, byteArrayOf())
    writeFsynced(stderr, byteArrayOf())
    fsync(root)
    return OperationPaths(
      root = root,
      rawResultPath = root.resolve("jmh-result.raw.json"),
      canonicalResultPath = root.resolve("jmh-result.json"),
      stdoutPath = stdout,
      stderrPath = stderr,
      rawProfilerPath =
        if (profile.profiler == DiagnosticProfiler.JFR) root.resolve(JFR_AGGREGATE_NAME) else null,
    )
  }

  private fun validateJfrAggregate(paths: OperationPaths, expectedForks: Int): Sha256? {
    val rawPath = paths.rawProfilerPath ?: return null
    val markerPath = paths.root.resolve(JFR_MARKER_NAME)
    val lockPath = paths.root.resolve(JFR_LOCK_NAME)
    val boundPath = paths.root.resolve(".jfr-capture-input.bound")
    return try {
      require(Files.isRegularFile(rawPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(rawPath))
      require(Files.isRegularFile(markerPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(markerPath))
      require(Files.isRegularFile(lockPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(lockPath))
      require(Files.size(lockPath) == 0L)
      require(!Files.exists(boundPath, NOFOLLOW_LINKS))
      val jfrObjects =
        Files.walk(paths.root).use { entries ->
          entries
            .filter { it.fileName.toString().endsWith(".jfr") }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toList()
        }
      require(jfrObjects == listOf(rawPath))
      val markerBytes = Files.readAllBytes(markerPath)
      require(markerBytes.size in 1..MAX_JFR_MARKER_BYTES)
      val marker = CanonicalJson.parseStrict(markerBytes) as? tools.jackson.databind.node.ObjectNode
        ?: error("JFR marker root")
      require(CanonicalJson.encode(marker).contentEquals(markerBytes))
      require(marker.properties().map { it.key }.toSet() == JFR_MARKER_FIELDS)
      val schemaVersionNode = marker.get("schemaVersion")
      val completedForksNode = marker.get("completedForks")
      val byteLengthNode = marker.get("byteLength")
      val sha256Node = marker.get("sha256")
      require(schemaVersionNode.isString && schemaVersionNode.asString() == JFR_MARKER_VERSION)
      require(
        completedForksNode.isIntegralNumber &&
          completedForksNode.canConvertToInt() &&
          completedForksNode.asInt() == expectedForks
      )
      require(byteLengthNode.isIntegralNumber && byteLengthNode.canConvertToLong())
      val byteLength = byteLengthNode.asLong()
      require(byteLength in 1..MAX_JFR_BYTES)
      require(Files.size(rawPath) == byteLength)
      require(sha256Node.isString)
      val expectedSha256 = Sha256.parse(sha256Node.asString())
      Files.createLink(boundPath, rawPath)
      require(Files.isSameFile(boundPath, rawPath))
      jfrHooks.afterBindingBeforeHash(boundPath)
      FileChannel.open(boundPath, READ, NOFOLLOW_LINKS).use { it.force(true) }
      val actualSha256 = Sha256.digest(boundPath)
      require(actualSha256 == expectedSha256)
      require(Files.isSameFile(boundPath, rawPath) && Files.size(boundPath) == byteLength)
      Files.delete(markerPath)
      Files.delete(lockPath)
      Files.delete(boundPath)
      fsync(paths.root)
      actualSha256
    } catch (_: Exception) {
      runCatching { Files.deleteIfExists(boundPath) }
      null
    }
  }

  private fun processSpec(request: CaptureRequest, paths: OperationPaths): ProcessInvocation {
    val classpath = request.distribution.benchmarkClasspath
    val profile = request.profile
    val benchmarkExpression =
      profile.expectedCells.cells.map(ExpectedCell::benchmark).distinct().joinToString(
        prefix = "^(?:",
        postfix = ")$",
        separator = "|",
      ) { "\\Q$it\\E" }
    val arguments =
      buildList {
        add("-cp")
        add(classpath.joinToString(File.pathSeparator))
        add("org.openjdk.jmh.Main")
        add(benchmarkExpression)
        addAll(listOf("-foe", "true", "-rf", "json", "-rff", paths.rawResultPath.toString()))
        addAll(listOf("-f", profile.forks.toString()))
        addAll(listOf("-wi", profile.warmupIterations.toString()))
        addAll(listOf("-i", profile.measurementIterations.toString()))
        addAll(listOf("-bs", profile.batchSize.toString()))
        addAll(listOf("-t", profile.threads.toString()))
        addAll(listOf("-bm", profile.mode))
        addAll(listOf("-tu", profile.unit))
        profile.expectedCells.cells
          .flatMap { cell -> cell.parameters.map { (name, value) -> name to value } }
          .groupBy({ it.first }, { it.second })
          .toSortedMap()
          .forEach { (name, values) ->
            addAll(listOf("-p", "$name=${values.distinct().joinToString(",")}"))
          }
        addAll(listOf("-jvm", profile.selectedJavaExecutable.toString()))
        addAll(listOf("-jvmArgs", profile.jvmArguments.joinToString(" ")))
        when (profile.profiler) {
          DiagnosticProfiler.NONE -> Unit
          DiagnosticProfiler.GC,
          DiagnosticProfiler.JFR ->
            addAll(
              listOf(
                "-prof",
                profile.profilerArguments.single().replace("{operationRoot}", paths.root.toString()),
              ),
            )
        }
      }
    return ProcessInvocation(
      executable = profile.selectedJavaExecutable,
      arguments = arguments,
      classpath = classpath,
      workingDirectory = paths.root,
      environment = profile.evidence.runtime.environment,
      stdoutPath = paths.stdoutPath,
      stderrPath = paths.stderrPath,
      resultPath = paths.rawResultPath,
      rawProfilerPath = paths.rawProfilerPath,
    )
  }

  private fun detectSignals(paths: OperationPaths): List<CaptureFailure> {
    val combined =
      listOf(paths.stdoutPath, paths.stderrPath).joinToString("\n") { path ->
        readBoundedLog(path).orEmpty()
      }
    return SIGNALS.mapNotNull { (pattern, failure) -> failure.takeIf { pattern.containsMatchIn(combined) } }
  }

  private fun sanitizeLogs(paths: OperationPaths): Boolean =
    listOf(paths.stdoutPath, paths.stderrPath).all { path ->
      val unsafe = readBoundedLog(path) ?: return@all false
      runCatching {
          writeFsynced(path, privacyFilter.sanitize(unsafe).encodeToByteArray(), replace = true)
        }
        .isSuccess
    }

  private fun readBoundedLog(path: Path): String? =
    if (
      Files.isRegularFile(path, NOFOLLOW_LINKS) &&
        runCatching { Files.size(path) <= MAX_LOG_BYTES }.getOrDefault(false)
    ) {
      runCatching { Files.readString(path) }.getOrNull()
    } else {
      null
    }

  private fun readResult(path: Path): ResultRead {
    if (!Files.exists(path, NOFOLLOW_LINKS)) {
      return ResultRead.Invalid(CaptureFailure.RESULT_MISSING)
    }
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
      return ResultRead.Invalid(CaptureFailure.RESULT_MALFORMED)
    }
    val size =
      runCatching { Files.size(path) }.getOrElse {
        return ResultRead.Invalid(CaptureFailure.RESULT_MALFORMED)
      }
    if (size == 0L) return ResultRead.Invalid(CaptureFailure.RESULT_EMPTY)
    if (size > MAX_JMH_RESULT_BYTES) {
      return ResultRead.Invalid(CaptureFailure.RESULT_MALFORMED)
    }
    return runCatching { ResultRead.Valid(Files.readAllBytes(path)) }
      .getOrElse { ResultRead.Invalid(CaptureFailure.RESULT_MALFORMED) }
  }

  private fun artifacts(distribution: VerifiedDistribution, rawJmhSha256: Sha256): CaptureArtifacts {
    val classpath = distribution.metadata.classpath
    val benchmark =
      classpath.benchmarkClasspath.single { it.path == DistributionLayout.BENCHMARK_JAR }
    val production =
      classpath.benchmarkClasspath.single { it.path == DistributionLayout.PRODUCTION_JAR }
    val runner = classpath.runnerClasspath.first()
    val dependencyEntries = classpath.benchmarkClasspath.filterNot { it == benchmark || it == production }
    val embedded = classpath.embeddedDependencies.map { DependencyIdentity(it.coordinate, it.sha256) }
    return CaptureArtifacts(
      production = production.artifact(),
      benchmark = benchmark.artifact(),
      distribution =
        ArtifactIdentity(
          "metadata/distribution.sha256",
          Sha256.digest(distribution.root.resolve("metadata/distribution.sha256")),
        ),
      orderedClasspath = classpath.benchmarkClasspath.map(DistributionClasspathEntry::artifact),
      executingRunner = runner.artifact(),
      orderedRunnerClasspath = classpath.runnerClasspath.map(DistributionClasspathEntry::artifact),
      dependencies =
        dependencyEntries.map { DependencyIdentity(it.coordinate, it.sha256) } + embedded,
      rawJmhInputSha256 = rawJmhSha256,
    )
  }

  private fun elapsedNanos(startedAt: String): Long =
    java.time.Duration.between(java.time.Instant.parse(startedAt), clock.instant()).toNanos().coerceAtLeast(1)

  private fun writeFsynced(path: Path, bytes: ByteArray, replace: Boolean = false) {
    val options: Array<OpenOption> =
      if (replace) {
        arrayOf(WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)
      } else {
        arrayOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
      }
    FileChannel.open(path, *options).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  private fun fsync(directory: Path) {
    FileChannel.open(directory, READ).use { it.force(true) }
  }

  private fun CaptureProfile.provisionalStrength(): ProvisionalEvidenceStrength =
    if (family == CaptureProfileFamily.CANARY && profiler == DiagnosticProfiler.NONE) {
      ProvisionalEvidenceStrength.CANARY
    } else {
      ProvisionalEvidenceStrength.DIAGNOSTIC
    }

  private fun CaptureProfile.provisionalReason(): ProvisionalOutcomeReason =
    when {
      profiler != DiagnosticProfiler.NONE -> ProvisionalOutcomeReason.PROFILER_DIAGNOSTIC
      family == CaptureProfileFamily.CANARY -> ProvisionalOutcomeReason.STRUCTURAL_CANARY
      evidence.runtime.substrate is SubstrateIdentity.GithubHosted ->
        ProvisionalOutcomeReason.GITHUB_HOSTED
      else -> ProvisionalOutcomeReason.BOUNDED_DIAGNOSTIC
    }

  private data class OperationPaths(
    val root: Path,
    val rawResultPath: Path,
    val canonicalResultPath: Path,
    val stdoutPath: Path,
    val stderrPath: Path,
    val rawProfilerPath: Path?,
  )

  private sealed interface ResultRead {
    class Valid(val bytes: ByteArray) : ResultRead

    data class Invalid(val reason: CaptureFailure) : ResultRead
  }

  private companion object {
    val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    const val MAX_LOG_BYTES = 4L * 1024 * 1024
    const val MAX_JMH_RESULT_BYTES = 64L * 1024 * 1024
    const val MAX_JFR_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_JFR_MARKER_BYTES = 256
    const val JFR_AGGREGATE_NAME = "profile.jfr"
    const val JFR_MARKER_NAME = ".jfr-aggregate.json"
    const val JFR_LOCK_NAME = ".jfr-aggregate.lock"
    const val JFR_MARKER_VERSION = "jfr-fork-aggregate-v1"
    val JFR_MARKER_FIELDS = setOf("byteLength", "completedForks", "schemaVersion", "sha256")
    val SIGNALS =
      listOf(
        Regex("org\\.apache\\.logging\\.log4j\\.simple\\.SimpleLogger", RegexOption.IGNORE_CASE) to
          CaptureFailure.LOG4J_FALLBACK,
        Regex("(?:ERROR\\s+StatusLogger|Unable to locate a logging implementation)", RegexOption.IGNORE_CASE) to
          CaptureFailure.LOG4J_PROVIDER_FAILED,
        Regex("(?:No language and polyglot implementation|Graal.*packag)", RegexOption.IGNORE_CASE) to
          CaptureFailure.GRAAL_PACKAGING_FAILED,
        Regex("REVOMAN_SCENARIO_INVARIANT_FAILED") to CaptureFailure.SCENARIO_INVARIANT_FAILED,
        Regex("REVOMAN_TEARDOWN_FAILED") to CaptureFailure.TEARDOWN_FAILED,
      )
  }
}

private fun DistributionGitIdentity.toModel(): GitProvenance = GitProvenance(gitSha, treeClean)

private fun DistributionClasspathEntry.artifact(): ArtifactIdentity = ArtifactIdentity(path, sha256)

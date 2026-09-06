package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal class ConsumerScorecardRunner(
  private val host: ScorecardHost = SystemScorecardHost(),
  private val processExecutor: ProcessExecutor = SystemProcessExecutor,
  private val reporter: (Path) -> Int = { manifest ->
    BenchmarkReportCli.run(arrayOf("scorecard", "--manifest", manifest.toAbsolutePath().toString()))
  },
  private val publishMove: (Path, Path) -> Unit = ::moveCompleteRun,
  private val runtimeCleanup: (Path) -> Unit = ::deleteScorecardRuntimeWorkspace,
) {
  internal fun run(request: ScorecardRunRequest): Path {
    val paths = bootstrapScorecardAttempt(request.projectRoot, host.clock.instant())
    return executeAttempt(paths, request.copy(projectRoot = paths.projectRoot))
  }

  internal fun preflight(request: ScorecardRunRequest): ScorecardPreflight =
    ScorecardPreflightValidator(host).validate(request)

  @Suppress("TooGenericExceptionCaught")
  private fun executeAttempt(paths: ScorecardAttemptPaths, request: ScorecardRunRequest): Path {
    var phase = "preflight"
    var runtimeWorkspace: ScorecardRuntimeWorkspace? = null
    try {
      require(!paths.acceptedExisted) {
        "Accepted scorecard run already exists for ${paths.runId}"
      }
      validateAttemptPaths(paths)
      println("consumer-scorecard: preflight")
      val attempt = createAttempt(paths, preflight(request))
      val runtime = prepareRuntime(attempt).also { runtimeWorkspace = it.workspace }
      phase = "profiling"
      val profilerFacts = runProfiles(attempt, runtime)
      phase = "final measurement"
      val finalCommand = runFinalMeasurement(attempt, runtime)
      phase = "environment capture"
      val manifest = writeEvidence(attempt, runtime, finalCommand, profilerFacts)
      phase = "scorecard validation"
      validateReports(attempt, manifest)
      phase = "privacy validation"
      val evidenceSnapshot =
        validateEvidencePrivacy(attempt.stagingRun, runtime.privateMachineIdentity)
      phase = "runtime cleanup"
      runtimeCleanup(runtime.workspace.root)
      runtimeWorkspace = null
      phase = "publication"
      publish(attempt, evidenceSnapshot)
      return attempt.acceptedRun
    } catch (failure: Exception) {
      runtimeWorkspace?.let { workspace ->
        runCatching { runtimeCleanup(workspace.root) }
      }
      restoreFailedPublication(paths)
      writeFailureSummary(paths, phase, failure)
      System.err.println("consumer-scorecard: $phase failed: ${failure.message}")
      throw failure
    }
  }

  private fun createAttempt(
    paths: ScorecardAttemptPaths,
    preflight: ScorecardPreflight,
  ): ScorecardAttempt {
    validateAttemptPaths(paths)
    Files.createDirectories(paths.stagingRun.resolve("raw/profiles"))
    Files.createDirectories(paths.stagingRun.resolve("environment"))
    validateAttemptPaths(paths)
    return ScorecardAttempt(preflight, paths)
  }

  private fun prepareRuntime(attempt: ScorecardAttempt): ScorecardRuntime {
    val privateMachineIdentity =
      host.resolvePrivateMachineIdentity(attempt.preflight.projectRoot).also { identity ->
        identity.values
      }
    val affinity = selectScorecardAffinity(host)
    val hygiene =
      inspectProcessHygiene(host, attempt.preflight.projectRoot, affinity.logicalCpus.toSet())
    val profilerLibrary =
      attempt.preflight.javaExecutable.parent.parent.resolve("lib/libasyncProfiler.so").normalize()
    require(host.isRegularFile(profilerLibrary) && host.isReadable(profilerLibrary)) {
      "Selected Java home does not contain readable lib/libasyncProfiler.so"
    }
    val jfrExecutable = attempt.preflight.javaExecutable.parent.resolve("jfr").normalize()
    require(
      host.isRegularFile(jfrExecutable) &&
        host.isReadable(jfrExecutable) &&
        host.isExecutable(jfrExecutable)
    ) {
      "Selected Java home does not contain an executable bin/jfr"
    }
    val workspace = createScorecardRuntimeWorkspace(attempt.preflight.benchmarkJar)
    return ScorecardRuntime(
      privateMachineIdentity,
      affinity,
      hygiene,
      profilerLibrary,
      jfrExecutable,
      workspace,
    )
  }

  private fun runProfiles(
    attempt: ScorecardAttempt,
    runtime: ScorecardRuntime,
  ): List<ProfilerFact> {
    println(
      "consumer-scorecard: profiling ${expectedScorecardRows.size * PROFILE_EVENTS.size} " +
        "method/event pairs"
    )
    return expectedScorecardRows.flatMap { row ->
      val method = row.benchmark.substringAfterLast('.')
      PROFILE_EVENTS.map { profile ->
        runProfile(attempt, runtime, method, profile)
      }
    }
  }

  private fun runFinalMeasurement(
    attempt: ScorecardAttempt,
    runtime: ScorecardRuntime,
  ): List<String> {
    println("consumer-scorecard: final measurement")
    val runtimeResults = runtime.workspace.results
    val resultsPath = attempt.stagingRun.resolve(RESULTS_PATH)
    val command =
      finalCommand(attempt.preflight, runtime.affinity, runtime.workspace, runtimeResults)
    validateAttemptPaths(attempt.paths)
    val result = processExecutor.execute(command, runtime.workspace.root)
    validateAttemptPaths(attempt.paths)
    preserveFailedRuntimeArtifact(result, runtimeResults, resultsPath)
    requireSuccessfulProcess(
      result,
      "Final JMH measurement",
    )
    require(
      Files.isRegularFile(runtimeResults) &&
        Files.isReadable(runtimeResults) &&
        Files.size(runtimeResults) > 0
    ) {
      "Final JMH CSV is missing or empty"
    }
    copyRuntimeArtifact(runtimeResults, resultsPath)
    return command
  }

  private fun writeEvidence(
    attempt: ScorecardAttempt,
    runtime: ScorecardRuntime,
    finalCommand: List<String>,
    profilerFacts: List<ProfilerFact>,
  ): Path {
    val completedAt = host.clock.instant()
    val environment =
      captureScorecardEnvironment(
        host,
        attempt.preflight.projectRoot,
        attempt.runId,
        attempt.startedAt,
        completedAt,
        runtime.affinity,
        attempt.preflight.javaIdentities,
        runtime.hygiene,
      )
    validateAttemptPaths(attempt.paths)
    Files.writeString(
      attempt.stagingRun.resolve(ENVIRONMENT_PATH),
      environment,
    )
    val fingerprint =
      dependencyFingerprint(attempt.preflight.projectRoot, attempt.preflight.benchmarkJar)
    return attempt.stagingRun.resolve("manifest.json").also { manifest ->
      val contents =
        scorecardManifest(
          attempt.preflight,
          attempt.runId,
          attempt.startedAt,
          completedAt,
          runtime.affinity,
          finalCommand,
          fingerprint,
          profilerFacts,
        )
      validateAttemptPaths(attempt.paths)
      Files.writeString(manifest, contents)
    }
  }

  private fun validateReports(attempt: ScorecardAttempt, manifest: Path) {
    println("consumer-scorecard: validating reports")
    validateAttemptPaths(attempt.paths)
    val reporterStatus = reporter(manifest.toAbsolutePath().normalize())
    validateAttemptPaths(attempt.paths)
    require(reporterStatus == 0) {
      "Scorecard validation or rendering failed"
    }
    GENERATED_REPORTS.forEach { relative ->
      val path = attempt.stagingRun.resolve(relative)
      require(Files.isRegularFile(path) && Files.isReadable(path) && Files.size(path) > 0) {
        "Scorecard reporter did not create $relative"
      }
    }
  }

  private fun publish(attempt: ScorecardAttempt, evidenceSnapshot: EvidencePrivacySnapshot) {
    println("consumer-scorecard: publishing ${attempt.runId}")
    validateAttemptPaths(attempt.paths)
    revalidateEvidencePrivacy(attempt.stagingRun, evidenceSnapshot)
    validateAttemptPaths(attempt.paths)
    publishMove(attempt.stagingRun, attempt.acceptedRun)
    validatePublishedPaths(attempt.paths)
    require(Files.isDirectory(attempt.acceptedRun) && Files.notExists(attempt.stagingRun)) {
      "Accepted scorecard publication is incomplete"
    }
    println("consumer-scorecard: accepted ${attempt.runId}")
  }

  private fun runProfile(
    attempt: ScorecardAttempt,
    runtime: ScorecardRuntime,
    method: String,
    profile: ProfileEvent,
  ): ProfilerFact {
    val directory = attempt.stagingRun.resolve("raw/profiles").resolve(method)
    validateAttemptPaths(attempt.paths)
    Files.createDirectories(directory)
    val paths = ProfilePaths.create(directory, runtime.workspace, method, profile.event)
    Files.createDirectories(paths.runtimeSampleDirectory)
    Files.createDirectories(paths.runtimeSemanticDirectory)
    val recordings = executeProfile(processExecutor, attempt, runtime, method, profile, paths)
    val attributionContents =
      renderProfilerAttributionCsv(
        createProfilerAttribution(profile.event, recordings.sample, recordings.semantic)
      )
    Files.writeString(paths.attribution, attributionContents)
    require(Files.isRegularFile(paths.attribution) && Files.size(paths.attribution) > 0) {
      "$method ${profile.event} attribution is missing or empty"
    }
    validateAttemptPaths(attempt.paths)
    val summaryContents =
      profileSummary(processExecutor, attempt, runtime, method, profile, recordings.sample)
    validateAttemptPaths(attempt.paths)
    Files.writeString(paths.summary, summaryContents)
    require(Files.isRegularFile(paths.summary) && Files.size(paths.summary) > 0) {
      "$method ${profile.event} JFR summary is missing or empty"
    }
    return ProfilerFact(
      method,
      profile.event,
      profile.view,
      attempt.stagingRun.relativize(paths.recording).toString(),
      attempt.stagingRun.relativize(paths.semanticRecording).toString(),
      attempt.stagingRun.relativize(paths.summary).toString(),
      attempt.stagingRun.relativize(paths.attribution).toString(),
    )
  }
}

private fun executeProfile(
  processExecutor: ProcessExecutor,
  attempt: ScorecardAttempt,
  runtime: ScorecardRuntime,
  method: String,
  profile: ProfileEvent,
  paths: ProfilePaths,
): RuntimeProfileRecordings {
  val command =
    profileCommand(
      attempt.preflight,
      runtime.affinity,
      runtime.workspace,
      method,
      profile.event,
      runtime.profilerLibrary,
      paths.runtimeDirectory,
    )
  validateAttemptPaths(attempt.paths)
  val result = processExecutor.execute(command, runtime.workspace.root)
  validateAttemptPaths(attempt.paths)
  val sample = findAsyncProfilerRecording(paths.runtimeSampleDirectory, profile.event)
  val semantic = findJfrProfilerRecording(paths.runtimeSemanticDirectory)
  sample?.let { preserveFailedRuntimeArtifact(result, it, paths.recording) }
  semantic?.let { preserveFailedRuntimeArtifact(result, it, paths.semanticRecording) }
  requireSuccessfulProcess(result, "$method ${profile.event} profile")

  val requiredSample =
    requireNotNull(sample) { "$method ${profile.event} profile recording is missing" }
  requireRuntimeRecording(requiredSample, "$method ${profile.event} profile")
  copyRuntimeArtifact(requiredSample, paths.recording)
  val requiredSemantic =
    requireNotNull(semantic) { "$method ${profile.event} semantic recording is missing" }
  requireRuntimeRecording(requiredSemantic, "$method ${profile.event} semantic")
  copyRuntimeArtifact(requiredSemantic, paths.semanticRecording)
  return RuntimeProfileRecordings(requiredSample, requiredSemantic)
}

private fun isJfrViewError(line: String): Boolean =
  line.startsWith("Can't find event type named") || line.startsWith("Missing event found for")

private fun profileSummary(
  processExecutor: ProcessExecutor,
  attempt: ScorecardAttempt,
  runtime: ScorecardRuntime,
  method: String,
  profile: ProfileEvent,
  recording: Path,
): String =
  when (profile.event) {
    "alloc" -> allocationByClassSummary(recording)
    else -> jfrViewSummary(processExecutor, attempt, runtime, method, profile, recording)
  }

private fun jfrViewSummary(
  processExecutor: ProcessExecutor,
  attempt: ScorecardAttempt,
  runtime: ScorecardRuntime,
  method: String,
  profile: ProfileEvent,
  recording: Path,
): String {
  val description = "$method ${profile.event} JFR summary"
  val result =
    processExecutor.execute(
      listOf(runtime.jfrExecutable.toString(), "view", profile.view, recording.toString()),
      runtime.workspace.root,
    )
  validateAttemptPaths(attempt.paths)
  requireSuccessfulProcess(result, description)
  require(result.stdout.isNotBlank()) { "$description is empty" }
  require(result.stdout.lineSequence().none(::isJfrViewError)) {
    "$description contains a JFR view error"
  }
  return result.stdout
}

private data class ScorecardAttempt(
  val preflight: ScorecardPreflight,
  val paths: ScorecardAttemptPaths,
) {
  val startedAt: Instant
    get() = paths.startedAt

  val runId: String
    get() = paths.runId

  val stagingRun: Path
    get() = paths.stagingRun

  val acceptedRun: Path
    get() = paths.acceptedRun
}

private data class ScorecardRuntime(
  val privateMachineIdentity: PrivateMachineIdentity,
  val affinity: CpuAffinity,
  val hygiene: ProcessHygiene,
  val profilerLibrary: Path,
  val jfrExecutable: Path,
  val workspace: ScorecardRuntimeWorkspace,
)

private data class ProfileEvent(
  val event: String,
  val view: String,
)

private data class RuntimeProfileRecordings(val sample: Path, val semantic: Path)

private data class ProfilePaths(
  val recording: Path,
  val semanticRecording: Path,
  val attribution: Path,
  val summary: Path,
  val runtimeDirectory: Path,
) {
  val runtimeSampleDirectory: Path = runtimeDirectory.resolve(ASYNC_PROFILER_DIRECTORY)
  val runtimeSemanticDirectory: Path = runtimeDirectory.resolve(JFR_PROFILER_DIRECTORY)

  companion object {
    fun create(
      outputDirectory: Path,
      workspace: ScorecardRuntimeWorkspace,
      method: String,
      event: String,
    ): ProfilePaths =
      ProfilePaths(
        recording = outputDirectory.resolve("$event.jfr"),
        semanticRecording = outputDirectory.resolve("$event-semantic.jfr"),
        attribution = outputDirectory.resolve("$event-attribution.csv"),
        summary = outputDirectory.resolve("$event.txt"),
        runtimeDirectory = workspace.profileDirectory(method, event),
      )
  }
}

private val GENERATED_REPORTS = listOf("scorecard.csv", "report.md", "performance-scorecard.adoc")
private val PROFILE_EVENTS =
  listOf(
    ProfileEvent("cpu", "hot-methods"),
    ProfileEvent("alloc", "allocation-by-class"),
    ProfileEvent("lock", "contention-by-site"),
    ProfileEvent("wall", "profiler.WallClockSample"),
  )

private fun requireRuntimeRecording(recording: Path, description: String) {
  require(
    Files.isRegularFile(recording) && Files.isReadable(recording) && Files.size(recording) > 0
  ) {
    "$description recording is missing or empty"
  }
}

private fun requireSuccessfulProcess(result: ProcessResult, description: String) {
  require(result.exitCode == 0) {
    "$description failed with exit ${result.exitCode}: ${result.stderr.trim()}"
  }
}

private fun restoreFailedPublication(paths: ScorecardAttemptPaths) {
  if (paths.acceptedExisted) return
  runCatching {
    validateFailedPublicationPaths(paths)
    moveCompleteRun(paths.acceptedRun, paths.stagingRun)
  }
}

private fun writeFailureSummary(
  paths: ScorecardAttemptPaths,
  phase: String,
  failure: Exception,
) {
  if (runCatching { validateStagingPaths(paths) }.isFailure) return
  val summary = buildString {
    appendLine("status: rejected")
    appendLine("runId: ${paths.runId}")
    appendLine("phase: $phase")
    appendLine("failure: ${failure::class.simpleName}")
    appendLine("message: ${failure.message.orEmpty().lineSequence().firstOrNull().orEmpty()}")
  }
  runCatching { Files.writeString(paths.stagingRun.resolve("failure-summary.txt"), summary) }
}

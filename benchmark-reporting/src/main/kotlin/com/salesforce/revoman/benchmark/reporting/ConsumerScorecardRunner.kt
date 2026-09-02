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
    try {
      require(!paths.acceptedExisted) {
        "Accepted scorecard run already exists for ${paths.runId}"
      }
      println("consumer-scorecard: preflight")
      val attempt = createAttempt(paths, preflight(request))
      val runtime = prepareRuntime(attempt)
      phase = "profiling"
      val profilerFacts = runProfiles(attempt, runtime)
      phase = "final measurement"
      val finalCommand = runFinalMeasurement(attempt, runtime.affinity)
      phase = "environment capture"
      val manifest = writeEvidence(attempt, runtime, finalCommand, profilerFacts)
      phase = "scorecard validation"
      validateReports(attempt, manifest)
      phase = "publication"
      publish(attempt)
      return attempt.acceptedRun
    } catch (failure: Exception) {
      restoreFailedPublication(
        paths.stagingRun,
        paths.acceptedRun,
        paths.acceptedExisted,
      )
      writeFailureSummary(paths.stagingRun, paths.runId, phase, failure)
      System.err.println("consumer-scorecard: $phase failed: ${failure.message}")
      throw failure
    }
  }

  private fun createAttempt(
    paths: ScorecardAttemptPaths,
    preflight: ScorecardPreflight,
  ): ScorecardAttempt {
    Files.createDirectories(paths.stagingRun.resolve("raw/profiles"))
    Files.createDirectories(paths.stagingRun.resolve("environment"))
    return ScorecardAttempt(
      preflight,
      paths.startedAt,
      paths.runId,
      paths.stagingRun,
      paths.acceptedRun,
      paths.acceptedExisted,
    )
  }

  private fun prepareRuntime(attempt: ScorecardAttempt): ScorecardRuntime {
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
    return ScorecardRuntime(affinity, hygiene, profilerLibrary, jfrExecutable)
  }

  private fun runProfiles(
    attempt: ScorecardAttempt,
    runtime: ScorecardRuntime,
  ): List<ProfilerFact> {
    println("consumer-scorecard: profiling 21 method/event pairs")
    return expectedScorecardRows.flatMap { row ->
      val method = row.benchmark.substringAfterLast('.')
      PROFILE_EVENTS.map { profile ->
        runProfile(
          attempt.preflight,
          runtime.affinity,
          attempt.stagingRun,
          runtime.profilerLibrary,
          runtime.jfrExecutable,
          method,
          profile,
        )
      }
    }
  }

  private fun runFinalMeasurement(
    attempt: ScorecardAttempt,
    affinity: CpuAffinity,
  ): List<String> {
    println("consumer-scorecard: final measurement")
    val resultsPath = attempt.stagingRun.resolve(RESULTS_PATH)
    val command = finalCommand(attempt.preflight, affinity, resultsPath)
    requireSuccessfulProcess(
      processExecutor.execute(command, attempt.preflight.projectRoot),
      "Final JMH measurement",
    )
    require(
      Files.isRegularFile(resultsPath) &&
        Files.isReadable(resultsPath) &&
        Files.size(resultsPath) > 0
    ) {
      "Final JMH CSV is missing or empty"
    }
    return command
  }

  private fun writeEvidence(
    attempt: ScorecardAttempt,
    runtime: ScorecardRuntime,
    finalCommand: List<String>,
    profilerFacts: List<ProfilerFact>,
  ): Path {
    val completedAt = host.clock.instant()
    Files.writeString(
      attempt.stagingRun.resolve(ENVIRONMENT_PATH),
      captureScorecardEnvironment(
        host,
        attempt.preflight.projectRoot,
        attempt.runId,
        attempt.startedAt,
        completedAt,
        runtime.affinity,
        attempt.preflight.javaIdentities,
        runtime.hygiene,
      ),
    )
    val fingerprint =
      dependencyFingerprint(attempt.preflight.projectRoot, attempt.preflight.benchmarkJar)
    return attempt.stagingRun.resolve("manifest.json").also { manifest ->
      Files.writeString(
        manifest,
        scorecardManifest(
          attempt.preflight,
          attempt.runId,
          attempt.startedAt,
          completedAt,
          runtime.affinity,
          finalCommand,
          fingerprint,
          profilerFacts,
        ),
      )
    }
  }

  private fun validateReports(attempt: ScorecardAttempt, manifest: Path) {
    println("consumer-scorecard: validating reports")
    require(reporter(manifest.toAbsolutePath().normalize()) == 0) {
      "Scorecard validation or rendering failed"
    }
    GENERATED_REPORTS.forEach { relative ->
      val path = attempt.stagingRun.resolve(relative)
      require(Files.isRegularFile(path) && Files.isReadable(path) && Files.size(path) > 0) {
        "Scorecard reporter did not create $relative"
      }
    }
  }

  private fun publish(attempt: ScorecardAttempt) {
    println("consumer-scorecard: publishing ${attempt.runId}")
    validatePublicationPaths(attempt.paths())
    publishMove(attempt.stagingRun, attempt.acceptedRun)
    require(Files.isDirectory(attempt.acceptedRun) && Files.notExists(attempt.stagingRun)) {
      "Accepted scorecard publication is incomplete"
    }
    println("consumer-scorecard: accepted ${attempt.runId}")
  }

  private fun runProfile(
    preflight: ScorecardPreflight,
    affinity: CpuAffinity,
    stagingRun: Path,
    profilerLibrary: Path,
    jfrExecutable: Path,
    method: String,
    profile: ProfileEvent,
  ): ProfilerFact {
    val directory = stagingRun.resolve("raw/profiles").resolve(method)
    Files.createDirectories(directory)
    val recording = directory.resolve("${profile.event}.jfr")
    val summary = directory.resolve("${profile.event}.txt")
    val command =
      profileCommand(preflight, affinity, method, profile.event, profilerLibrary, recording)
    requireSuccessfulProcess(
      processExecutor.execute(command, preflight.projectRoot),
      "$method ${profile.event} profile",
    )
    require(
      Files.isRegularFile(recording) && Files.isReadable(recording) && Files.size(recording) > 0
    ) {
      "$method ${profile.event} profile recording is missing or empty"
    }
    val summaryResult =
      processExecutor.execute(
        listOf(jfrExecutable.toString(), "view", profile.view, recording.toString()),
        preflight.projectRoot,
      )
    requireSuccessfulProcess(summaryResult, "$method ${profile.event} JFR summary")
    require(summaryResult.stdout.isNotBlank()) { "$method ${profile.event} JFR summary is empty" }
    Files.writeString(summary, summaryResult.stdout)
    require(Files.isRegularFile(summary) && Files.size(summary) > 0) {
      "$method ${profile.event} JFR summary is missing or empty"
    }
    return ProfilerFact(
      method,
      profile.event,
      profile.view,
      stagingRun.relativize(recording).toString(),
      stagingRun.relativize(summary).toString(),
    )
  }
}

private data class ScorecardAttempt(
  val preflight: ScorecardPreflight,
  val startedAt: Instant,
  val runId: String,
  val stagingRun: Path,
  val acceptedRun: Path,
  val acceptedExisted: Boolean,
)

private data class ScorecardRuntime(
  val affinity: CpuAffinity,
  val hygiene: ProcessHygiene,
  val profilerLibrary: Path,
  val jfrExecutable: Path,
)

private data class ProfileEvent(
  val event: String,
  val view: String,
)

private val GENERATED_REPORTS = listOf("scorecard.csv", "report.md", "performance-scorecard.adoc")
private val PROFILE_EVENTS =
  listOf(
    ProfileEvent("cpu", "hot-methods"),
    ProfileEvent("alloc", "allocation-by-class"),
    ProfileEvent("lock", "contention-by-site"),
  )

private fun requireSuccessfulProcess(result: ProcessResult, description: String) {
  require(result.exitCode == 0) {
    "$description failed with exit ${result.exitCode}: ${result.stderr.trim()}"
  }
}

private fun restoreFailedPublication(
  stagingRun: Path,
  acceptedRun: Path,
  acceptedExisted: Boolean,
) {
  if (!acceptedExisted && Files.exists(acceptedRun) && Files.notExists(stagingRun)) {
    runCatching { moveCompleteRun(acceptedRun, stagingRun) }
  }
}

private fun writeFailureSummary(
  stagingRun: Path,
  runId: String,
  phase: String,
  failure: Exception,
) {
  if (!Files.isDirectory(stagingRun)) return
  val summary = buildString {
    appendLine("status: rejected")
    appendLine("runId: $runId")
    appendLine("phase: $phase")
    appendLine("failure: ${failure::class.simpleName}")
    appendLine("message: ${failure.message.orEmpty().lineSequence().firstOrNull().orEmpty()}")
  }
  runCatching { Files.writeString(stagingRun.resolve("failure-summary.txt"), summary) }
}

private fun ScorecardAttempt.paths(): ScorecardAttemptPaths =
  ScorecardAttemptPaths(
    preflight.projectRoot,
    startedAt,
    runId,
    stagingRun,
    acceptedRun,
    acceptedExisted,
  )

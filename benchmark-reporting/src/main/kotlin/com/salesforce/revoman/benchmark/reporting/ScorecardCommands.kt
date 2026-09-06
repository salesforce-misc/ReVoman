package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path

internal fun profileCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
  workspace: ScorecardRuntimeWorkspace,
  method: String,
  event: String,
  profilerLibrary: Path,
  outputDirectory: Path,
): List<String> =
  baseJmhCommand(preflight, affinity, workspace.benchmarkJar) +
    listOf(
      exactMethodSelector(method),
      "-bm",
      expectedScorecardProfile.mode,
      "-tu",
      expectedScorecardProfile.unit.substringBefore('/'),
      "-t",
      expectedScorecardProfile.threads.toString(),
      "-f",
      "1",
      "-wi",
      "1",
      "-i",
      "1",
      "-w",
      "250ms",
      "-r",
      "250ms",
      "-prof",
      asyncProfilerConfiguration(
        profilerLibrary,
        outputDirectory.resolve(ASYNC_PROFILER_DIRECTORY),
        event,
      ),
      "-prof",
      jfrProfilerConfiguration(workspace, outputDirectory.resolve(JFR_PROFILER_DIRECTORY)),
      "-jvmArgsAppend",
      jvmProperties(workspace),
    )

private fun asyncProfilerConfiguration(
  profilerLibrary: Path,
  outputDirectory: Path,
  event: String,
): String =
  "async:" +
    (listOf(
        "libPath=$profilerLibrary",
        "output=jfr",
        "event=$event",
      ) + if (event == "wall") listOf("interval=$WALL_SAMPLE_INTERVAL_NS") else emptyList())
      .plus("dir=$outputDirectory")
      .joinToString(";")

private fun jfrProfilerConfiguration(
  workspace: ScorecardRuntimeWorkspace,
  outputDirectory: Path,
): String =
  "jfr:" +
    listOf(
        "dir=$outputDirectory",
        "configName=${workspace.performanceJfrConfiguration}",
        "debugNonSafePoints=false",
      )
      .joinToString(";")

internal const val ASYNC_PROFILER_DIRECTORY = "samples"
internal const val JFR_PROFILER_DIRECTORY = "semantic"
internal const val WALL_SAMPLE_INTERVAL_NS = 10_000_000L

internal fun finalCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
  workspace: ScorecardRuntimeWorkspace,
  resultsPath: Path,
): List<String> =
  baseJmhCommand(preflight, affinity, workspace.benchmarkJar) +
    listOf(
      SCORECARD_SELECTOR,
      "-bm",
      expectedScorecardProfile.mode,
      "-tu",
      expectedScorecardProfile.unit.substringBefore('/'),
      "-t",
      expectedScorecardProfile.threads.toString(),
      "-f",
      expectedScorecardProfile.forks.toString(),
      "-wi",
      expectedScorecardProfile.warmups.toString(),
      "-i",
      expectedScorecardProfile.measurements.toString(),
      "-w",
      "${expectedScorecardProfile.iterationSeconds}s",
      "-r",
      "${expectedScorecardProfile.iterationSeconds}s",
      "-rf",
      "csv",
      "-rff",
      resultsPath.toString(),
      "-jvmArgsAppend",
      jvmProperties(workspace),
    )

private fun baseJmhCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
  benchmarkJar: Path,
): List<String> =
  listOf(
    "taskset",
    "--cpu-list",
    affinity.logicalCpuList,
    preflight.javaExecutable.toString(),
    "-jar",
    benchmarkJar.toString(),
  )

private fun exactMethodSelector(method: String): String =
  "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\.${method}$"

private fun jvmProperties(workspace: ScorecardRuntimeWorkspace): String =
  listOf(
      "-Drevoman.scorecard.expectedJavaFeature=$EXPECTED_JAVA_FEATURE",
      "-Drevoman.banner=off",
      "-Duser.name=revoman-scorecard",
      "-Duser.home=${workspace.userHome}",
      "-Duser.dir=${workspace.root}",
      "-Djava.io.tmpdir=${workspace.temporaryDirectory}",
    )
    .joinToString(" ")

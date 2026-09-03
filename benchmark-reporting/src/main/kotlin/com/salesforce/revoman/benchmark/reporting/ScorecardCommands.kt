package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path

internal fun profileCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
  workspace: ScorecardRuntimeWorkspace,
  method: String,
  event: String,
  profilerLibrary: Path,
  recording: Path,
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
      "-jvmArgsAppend",
      "${jvmProperties(workspace)} " +
        "-agentpath:$profilerLibrary=start,event=$event,file=$recording,loglevel=warn",
    )

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

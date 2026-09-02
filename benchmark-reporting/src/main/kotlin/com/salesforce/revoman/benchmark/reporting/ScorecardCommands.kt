package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path

internal fun profileCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
  method: String,
  event: String,
  profilerLibrary: Path,
  recording: Path,
): List<String> =
  baseJmhCommand(preflight, affinity) +
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
      "$JVM_PROPERTIES -agentpath:$profilerLibrary=start,event=$event,file=$recording,loglevel=warn",
    )

internal fun finalCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
  resultsPath: Path,
): List<String> =
  baseJmhCommand(preflight, affinity) +
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
      JVM_PROPERTIES,
    )

private fun baseJmhCommand(
  preflight: ScorecardPreflight,
  affinity: CpuAffinity,
): List<String> =
  listOf(
    "taskset",
    "--cpu-list",
    affinity.logicalCpuList,
    preflight.javaExecutable.toString(),
    "-jar",
    preflight.benchmarkJar.toString(),
  )

private fun exactMethodSelector(method: String): String =
  "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\.${method}$"

private val JVM_PROPERTIES =
  "-Drevoman.scorecard.expectedJavaFeature=$EXPECTED_JAVA_FEATURE -Drevoman.banner=off"

package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  exitProcess(runConsumerScorecardMain(args))
}

internal fun runConsumerScorecardMain(
  args: Array<String>,
  run: (ScorecardRunRequest) -> Path = ConsumerScorecardRunner()::run,
): Int =
  runCatching {
      run(parseConsumerScorecardRequest(args))
      0
    }
    .getOrElse { failure ->
      System.err.println("consumer-scorecard: ${failure.message}")
      2
    }

private fun parseConsumerScorecardRequest(args: Array<String>): ScorecardRunRequest {
  require(args.size % 2 == 0) { USAGE }
  val arguments = args.toList().chunked(2).map { pair -> pair[0] to pair[1] }
  require(arguments.all { (name, _) -> name in ARGUMENT_NAMES }) { USAGE }
  val values = arguments.filterNot { (name, _) -> name == "--allowed-dirty-path" }
  require(values.groupingBy(Pair<String, String>::first).eachCount().values.all { it == 1 }) {
    "Each scorecard option must be provided exactly once"
  }
  val byName = values.toMap()
  require(REQUIRED_ARGUMENTS.all(byName::containsKey)) { USAGE }
  return ScorecardRunRequest(
    projectRoot = Path.of(byName.getValue("--project-root")),
    benchmarkJar = Path.of(byName.getValue("--benchmark-jar")),
    javaExecutable = Path.of(byName.getValue("--java-executable")),
    javaFeature = byName.getValue("--java-feature").strictInt("--java-feature"),
    gradleDaemonJavaFeature =
      byName.getValue("--gradle-daemon-java-feature").strictInt("--gradle-daemon-java-feature"),
    gradleDaemonRuntimeVersion = byName.getValue("--gradle-daemon-runtime-version"),
    gradleDaemonVendor = byName.getValue("--gradle-daemon-vendor"),
    gradleDaemonVmName = byName.getValue("--gradle-daemon-vm-name"),
    gradleMaxWorkers = byName.getValue("--gradle-max-workers").strictInt("--gradle-max-workers"),
    libraryVersion = byName.getValue("--library-version"),
    runtimeValidation = Path.of(byName.getValue("--runtime-validation")),
    allowedDirtyPaths =
      arguments
        .asSequence()
        .filter { (name, _) -> name == "--allowed-dirty-path" }
        .map { (_, value) -> Path.of(value) }
        .toCollection(linkedSetOf()),
  )
}

private fun String.strictInt(name: String): Int = toIntOrNull() ?: error("$name must be an integer")

private val REQUIRED_ARGUMENTS =
  setOf(
    "--project-root",
    "--benchmark-jar",
    "--java-executable",
    "--java-feature",
    "--gradle-daemon-java-feature",
    "--gradle-daemon-runtime-version",
    "--gradle-daemon-vendor",
    "--gradle-daemon-vm-name",
    "--gradle-max-workers",
    "--library-version",
    "--runtime-validation",
  )
private val ARGUMENT_NAMES = REQUIRED_ARGUMENTS + "--allowed-dirty-path"
private const val USAGE =
  "Usage: --project-root <path> --benchmark-jar <path> --java-executable <path> " +
    "--java-feature <feature> --gradle-daemon-java-feature <feature> " +
    "--gradle-daemon-runtime-version <version> --gradle-daemon-vendor <vendor> " +
    "--gradle-daemon-vm-name <name> " +
    "--gradle-max-workers <count> --library-version <version> " +
    "--runtime-validation <path> [--allowed-dirty-path <path>]..."

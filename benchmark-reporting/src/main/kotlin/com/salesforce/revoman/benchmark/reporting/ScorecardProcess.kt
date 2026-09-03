package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import kotlin.concurrent.thread

internal data class ProcessResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
)

internal fun interface ProcessExecutor {
  fun execute(command: List<String>, workingDirectory: Path): ProcessResult
}

internal data class ScorecardRunRequest(
  val projectRoot: Path,
  val benchmarkJar: Path,
  val javaExecutable: Path,
  val javaFeature: Int,
  val gradleDaemonJavaFeature: Int,
  val gradleDaemonRuntimeVersion: String,
  val gradleDaemonVendor: String,
  val gradleDaemonVmName: String,
  val gradleMaxWorkers: Int,
  val libraryVersion: String,
  val runtimeValidation: Path,
  val allowedDirtyPaths: Set<Path>,
)

internal object SystemProcessExecutor : ProcessExecutor {
  override fun execute(command: List<String>, workingDirectory: Path): ProcessResult =
    executeProcess(command, workingDirectory)
}

internal fun executeProcess(command: List<String>, workingDirectory: Path): ProcessResult {
  require(command.isNotEmpty()) { "Process command must not be empty" }
  val process = ProcessBuilder(command).directory(workingDirectory.toFile()).start()
  var stdout = ByteArray(0)
  var stderr = ByteArray(0)
  val stdoutReader =
    thread(name = "scorecard-process-stdout") { stdout = process.inputStream.readAllBytes() }
  val stderrReader =
    thread(name = "scorecard-process-stderr") { stderr = process.errorStream.readAllBytes() }
  val exitCode = process.waitFor()
  stdoutReader.join()
  stderrReader.join()
  return ProcessResult(
    exitCode,
    stdout.toString(Charsets.UTF_8),
    stderr.toString(Charsets.UTF_8),
  )
}

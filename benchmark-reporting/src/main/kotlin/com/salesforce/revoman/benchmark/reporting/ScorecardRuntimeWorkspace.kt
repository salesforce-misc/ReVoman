package com.salesforce.revoman.benchmark.reporting

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator

internal data class ScorecardRuntimeWorkspace(
  val root: Path,
  val benchmarkJar: Path,
  val userHome: Path,
  val temporaryDirectory: Path,
) {
  val results: Path = root.resolve("results.csv")
  val performanceJfrConfiguration: Path = root.resolve("revoman-performance.jfc")

  fun profileDirectory(method: String, event: String): Path =
    root.resolve("profiles").resolve(method).resolve(event)
}

/** Locates the single JFR file emitted below JMH's per-benchmark async-profiler directory. */
internal fun findAsyncProfilerRecording(profileDirectory: Path, event: String): Path? =
  findSingleRecording(profileDirectory, "jfr-$event.jfr", "JMH async-profiler")

/** Locates the single recording emitted below JMH's per-benchmark JFR profiler directory. */
internal fun findJfrProfilerRecording(profileDirectory: Path): Path? =
  findSingleRecording(profileDirectory, "profile.jfr", "JMH JFR profiler")

private fun findSingleRecording(
  profileDirectory: Path,
  expectedName: String,
  producer: String,
): Path? {
  if (Files.notExists(profileDirectory, NOFOLLOW_LINKS)) return null
  val recordings =
    Files.walk(profileDirectory).use { paths ->
      paths
        .filter { path ->
          path.fileName.toString() == expectedName && !Files.isDirectory(path, NOFOLLOW_LINKS)
        }
        .map { it.toAbsolutePath().normalize() }
        .toList()
    }
  require(recordings.size <= 1) {
    "$producer created multiple $expectedName recordings below $profileDirectory"
  }
  return recordings.singleOrNull()
}

internal fun createScorecardRuntimeWorkspace(
  originalBenchmarkJar: Path
): ScorecardRuntimeWorkspace {
  val root =
    Files.createTempDirectory(Path.of("/tmp"), "revoman-consumer-scorecard-")
      .toAbsolutePath()
      .normalize()
  return try {
    val benchmarkJar = root.resolve("benchmark.jar")
    val userHome = Files.createDirectory(root.resolve("home"))
    val temporaryDirectory = Files.createDirectory(root.resolve("tmp"))
    Files.createDirectory(root.resolve("profiles"))
    copyRuntimeArtifact(originalBenchmarkJar, benchmarkJar)
    ScorecardRuntimeWorkspace(root, benchmarkJar, userHome, temporaryDirectory).also { workspace ->
      Files.writeString(workspace.performanceJfrConfiguration, PERFORMANCE_JFR_CONFIGURATION)
    }
  } catch (failure: IOException) {
    deleteScorecardRuntimeWorkspace(root)
    throw failure
  } catch (failure: IllegalArgumentException) {
    deleteScorecardRuntimeWorkspace(root)
    throw failure
  }
}

private val PERFORMANCE_JFR_CONFIGURATION =
  """
  <?xml version="1.0" encoding="UTF-8"?>
  <configuration version="2.0" label="ReVoman semantic events"
      description="Semantic operation intervals for profiler attribution" provider="ReVoman">
    <event name="com.salesforce.revoman.performance.Operation">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
    </event>
    <event name="com.salesforce.revoman.performance.Kick">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
    </event>
    <event name="com.salesforce.revoman.performance.Step">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
    </event>
  </configuration>
  """
    .trimIndent()
    .plus('\n')

internal fun copyRuntimeArtifact(source: Path, target: Path) {
  require(Files.isRegularFile(source, NOFOLLOW_LINKS)) {
    "Runtime artifact must be a regular file"
  }
  Files.createDirectories(requireNotNull(target.parent))
  Files.copy(source, target)
  require(Files.mismatch(source, target) == -1L) {
    "Runtime artifact copy changed bytes"
  }
}

internal fun preserveFailedRuntimeArtifact(
  result: ProcessResult,
  source: Path,
  rejectedTarget: Path,
) {
  if (result.exitCode != 0 && Files.exists(source, NOFOLLOW_LINKS)) {
    copyRuntimeArtifact(source, rejectedTarget)
  }
}

internal fun deleteScorecardRuntimeWorkspace(root: Path) {
  if (Files.notExists(root)) return
  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

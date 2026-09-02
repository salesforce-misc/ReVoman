package com.salesforce.revoman.benchmark.reporting

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal data class ScorecardRuntimeWorkspace(
  val root: Path,
  val benchmarkJar: Path,
  val userHome: Path,
  val temporaryDirectory: Path,
) {
  val results: Path = root.resolve("results.csv")

  fun recording(method: String, event: String): Path =
    root.resolve("profiles").resolve(method).resolve("$event.jfr")
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
    ScorecardRuntimeWorkspace(root, benchmarkJar, userHome, temporaryDirectory)
  } catch (failure: IOException) {
    deleteScorecardRuntimeWorkspace(root)
    throw failure
  } catch (failure: IllegalArgumentException) {
    deleteScorecardRuntimeWorkspace(root)
    throw failure
  }
}

internal fun copyRuntimeArtifact(source: Path, target: Path) {
  Files.createDirectories(requireNotNull(target.parent))
  Files.copy(source, target)
  require(Files.mismatch(source, target) == -1L) {
    "Runtime artifact copy changed bytes"
  }
}

internal fun deleteScorecardRuntimeWorkspace(root: Path) {
  if (Files.notExists(root)) return
  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

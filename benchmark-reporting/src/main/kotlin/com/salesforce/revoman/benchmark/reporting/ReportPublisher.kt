package com.salesforce.revoman.benchmark.reporting

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.jetbrains.kotlinx.dataframe.DataFrame

@Suppress(
  "TooGenericExceptionCaught"
) // Any publication failure must restore the two-file snapshot.
internal fun publish(
  runDir: Path,
  frame: DataFrame<ComparisonRowSchema>,
  move: (Path, Path) -> Unit = ::atomicMove,
) {
  val parent = requireNotNull(runDir.parent) { "Run directory must have a parent" }
  val targets = listOf(runDir.resolve("comparison.csv"), runDir.resolve("report.md"))
  require(targets.all { !Files.exists(it) || Files.isRegularFile(it) }) {
    "Generated output path is not a regular file"
  }
  val staging = Files.createTempDirectory(parent, ".benchmark-report-")
  val backup = Files.createTempDirectory(parent, ".benchmark-report-backup-")
  val installed = mutableListOf<Path>()
  try {
    val staged = listOf(staging.resolve("comparison.csv"), staging.resolve("report.md"))
    Files.writeString(staged[0], renderCsv(frame))
    Files.writeString(staged[1], renderMarkdown(frame))
    require(staged.all { Files.isRegularFile(it) }) { "Staged outputs are incomplete" }
    targets.forEachIndexed { index, target ->
      if (Files.exists(target)) move(target, backup.resolve(target.fileName))
      move(staged[index], target)
      installed.add(target)
    }
  } catch (failure: Exception) {
    rollback(installed, targets, backup, move)
    throw failure
  } finally {
    deleteTree(staging)
    deleteTree(backup)
  }
}

private fun rollback(
  installed: List<Path>,
  targets: List<Path>,
  backup: Path,
  move: (Path, Path) -> Unit,
) {
  installed.forEach(Files::deleteIfExists)
  targets.forEach { target ->
    val saved = backup.resolve(target.fileName)
    if (Files.exists(saved)) move(saved, target)
  }
}

private fun atomicMove(source: Path, target: Path) {
  try {
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, target, REPLACE_EXISTING)
  }
}

private fun deleteTree(path: Path) {
  if (Files.notExists(path)) return
  Files.walk(path).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

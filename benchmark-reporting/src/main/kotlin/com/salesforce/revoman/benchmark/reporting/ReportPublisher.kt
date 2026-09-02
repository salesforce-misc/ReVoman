package com.salesforce.revoman.benchmark.reporting

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.jetbrains.kotlinx.dataframe.DataFrame

internal fun publishComparison(
  runDir: Path,
  frame: DataFrame<ComparisonRowSchema>,
  move: (Path, Path) -> Unit = ::atomicMove,
) =
  publishFiles(
    runDir,
    linkedMapOf("comparison.csv" to renderCsv(frame), "report.md" to renderMarkdown(frame)),
    move,
  )

internal fun publishScorecard(
  runDir: Path,
  document: ScorecardDocument,
  move: (Path, Path) -> Unit = ::atomicMove,
) =
  publishFiles(
    runDir,
    linkedMapOf(
      "scorecard.csv" to renderScorecardCsv(document.frame),
      "report.md" to renderScorecardMarkdown(document.frame),
      "performance-scorecard.adoc" to
        renderScorecardAsciiDoc(document.studyId, document.runId, document.frame),
    ),
    move,
  )

@Suppress("TooGenericExceptionCaught")
internal fun publishFiles(
  runDir: Path,
  contentsByName: Map<String, String>,
  move: (Path, Path) -> Unit = ::atomicMove,
) {
  require(contentsByName.isNotEmpty()) { "At least one output is required" }
  require(contentsByName.keys.all(::isPlainFileName)) { "Output names must be plain file names" }
  val parent = requireNotNull(runDir.parent) { "Run directory must have a parent" }
  val targets = contentsByName.keys.map(runDir::resolve)
  require(targets.all { !Files.exists(it) || Files.isRegularFile(it) }) {
    "Generated output path is not a regular file"
  }
  val staging = Files.createTempDirectory(parent, ".benchmark-report-")
  val backup = Files.createTempDirectory(parent, ".benchmark-report-backup-")
  val installed = mutableListOf<Path>()
  try {
    val staged = contentsByName.map { (name, contents) ->
      staging.resolve(name).also { Files.writeString(it, contents) }
    }
    require(staged.all { Files.isRegularFile(it) }) { "Staged outputs are incomplete" }
    targets.forEachIndexed { index, target ->
      if (Files.exists(target)) move(target, backup.resolve(target.fileName))
      installed.add(target)
      move(staged[index], target)
    }
  } catch (failure: Exception) {
    rollback(installed, targets, backup, move)
    throw failure
  } finally {
    deleteTree(staging)
    deleteTree(backup)
  }
}

private fun isPlainFileName(name: String): Boolean =
  name.isNotBlank() &&
    Path.of(name).let { !it.isAbsolute && it.nameCount == 1 && it.toString() == name }

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

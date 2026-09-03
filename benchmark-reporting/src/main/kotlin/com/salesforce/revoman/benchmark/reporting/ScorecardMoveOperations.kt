package com.salesforce.revoman.benchmark.reporting

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

internal interface ScorecardMoveOperations {
  fun atomicMove(source: Path, target: Path)

  fun sameFileStore(source: Path, targetParent: Path): Boolean

  fun nonAtomicMove(source: Path, target: Path)
}

private object SystemScorecardMoveOperations : ScorecardMoveOperations {
  override fun atomicMove(source: Path, target: Path) {
    Files.move(source, target, ATOMIC_MOVE)
  }

  override fun sameFileStore(source: Path, targetParent: Path): Boolean =
    Files.getFileStore(source) == Files.getFileStore(targetParent)

  override fun nonAtomicMove(source: Path, target: Path) {
    Files.move(source, target)
  }
}

internal fun moveCompleteRun(
  source: Path,
  target: Path,
  operations: ScorecardMoveOperations = SystemScorecardMoveOperations,
) {
  require(Files.notExists(target, NOFOLLOW_LINKS)) { "Accepted run target already exists" }
  try {
    operations.atomicMove(source, target)
  } catch (_: AtomicMoveNotSupportedException) {
    require(operations.sameFileStore(source, requireNotNull(target.parent))) {
      "Non-atomic publication fallback requires source and target on the same filesystem"
    }
    operations.nonAtomicMove(source, target)
  }
}

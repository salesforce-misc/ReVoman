package com.salesforce.revoman.benchmark.reporting

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class ScorecardAttemptPaths(
  val projectRoot: Path,
  val startedAt: Instant,
  val runId: String,
  val stagingRun: Path,
  val acceptedRun: Path,
  val acceptedExisted: Boolean,
)

internal fun bootstrapScorecardAttempt(
  requestedProjectRoot: Path,
  startedAt: Instant,
): ScorecardAttemptPaths {
  val projectRoot = canonicalProjectRoot(requestedProjectRoot)
  val runId = RUN_ID_FORMATTER.format(startedAt)
  val stagingComponents = listOf(".benchmark-staging", SCORECARD_STUDY_ID)
  val acceptedComponents = listOf("benchmark-results", SCORECARD_STUDY_ID)
  validateReservedPath(projectRoot, stagingComponents + runId)
  validateReservedPath(projectRoot, acceptedComponents + runId)
  val stagingParent = createReservedDirectories(projectRoot, stagingComponents)
  val acceptedParent = createReservedDirectories(projectRoot, acceptedComponents)
  val acceptedRun = acceptedParent.resolve(runId)
  validateReservedPath(projectRoot, acceptedComponents + runId)
  val stagingRun = allocateAttemptDirectory(projectRoot, stagingParent, runId)
  return ScorecardAttemptPaths(
    projectRoot,
    startedAt,
    runId,
    stagingRun,
    acceptedRun,
    Files.exists(acceptedRun, NOFOLLOW_LINKS),
  )
}

internal fun validatePublicationPaths(paths: ScorecardAttemptPaths) {
  requireSafeDirectory(paths.projectRoot, paths.stagingRun)
  requireSafeDirectory(paths.projectRoot, requireNotNull(paths.acceptedRun.parent))
  require(!Files.isSymbolicLink(paths.acceptedRun)) {
    "Reserved publication path must not be a symbolic link"
  }
  require(paths.acceptedRun.normalize().startsWith(paths.projectRoot)) {
    "Reserved publication path escapes the project root"
  }
}

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

private fun canonicalProjectRoot(requested: Path): Path {
  val absolute = requested.toAbsolutePath().normalize()
  require(Files.isDirectory(absolute)) { "Project root must be an existing directory" }
  val canonical = absolute.toRealPath()
  require(Files.isDirectory(canonical, NOFOLLOW_LINKS) && Files.isWritable(canonical)) {
    "Canonical project root must be a writable directory"
  }
  return canonical
}

private fun validateReservedPath(projectRoot: Path, components: List<String>) {
  components.foldIndexed(projectRoot) { index, parent, component ->
    val path = parent.resolve(component)
    if (Files.exists(path, NOFOLLOW_LINKS)) {
      require(!Files.isSymbolicLink(path)) {
        "Reserved scorecard path must not contain a symbolic link: $path"
      }
      require(path.toRealPath().startsWith(projectRoot)) {
        "Reserved scorecard path escapes the canonical project root: $path"
      }
      if (index < components.lastIndex) {
        require(Files.isDirectory(path, NOFOLLOW_LINKS) && Files.isWritable(path)) {
          "Reserved scorecard path component must be a writable directory: $path"
        }
      }
    }
    path
  }
}

private fun createReservedDirectories(projectRoot: Path, components: List<String>): Path =
  components.fold(projectRoot) { parent, component ->
    val path = parent.resolve(component)
    if (Files.notExists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
    requireSafeDirectory(projectRoot, path)
    path
  }

private fun requireSafeDirectory(projectRoot: Path, path: Path) {
  require(!Files.isSymbolicLink(path)) {
    "Reserved scorecard path must not be a symbolic link: $path"
  }
  require(
    Files.isDirectory(path, NOFOLLOW_LINKS) &&
      Files.isWritable(path) &&
      path.toRealPath().startsWith(projectRoot)
  ) {
    "Reserved scorecard path must be a writable directory inside the canonical project root: $path"
  }
}

private fun allocateAttemptDirectory(
  projectRoot: Path,
  parent: Path,
  runId: String,
  diagnosticIndex: Int = 0,
): Path {
  val suffix = if (diagnosticIndex == 0) "" else "-diagnostic-$diagnosticIndex"
  val candidate = parent.resolve("$runId$suffix")
  require(!Files.isSymbolicLink(candidate)) {
    "Reserved staging path must not be a symbolic link: $candidate"
  }
  if (Files.exists(candidate, NOFOLLOW_LINKS)) {
    return allocateAttemptDirectory(projectRoot, parent, runId, diagnosticIndex + 1)
  }
  return try {
    Files.createDirectory(candidate).also { requireSafeDirectory(projectRoot, it) }
  } catch (_: FileAlreadyExistsException) {
    allocateAttemptDirectory(projectRoot, parent, runId, diagnosticIndex + 1)
  }
}

private val RUN_ID_FORMATTER =
  DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

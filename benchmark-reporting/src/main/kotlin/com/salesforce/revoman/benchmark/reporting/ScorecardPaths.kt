package com.salesforce.revoman.benchmark.reporting

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    projectRoot = projectRoot,
    startedAt = startedAt,
    runId = runId,
    stagingRun = stagingRun,
    acceptedRun = acceptedRun,
    acceptedExisted = Files.exists(acceptedRun, NOFOLLOW_LINKS),
    stagingDirectories =
      captureDirectoryIdentities(
        listOf(
          projectRoot,
          projectRoot.resolve(stagingComponents.first()),
          stagingParent,
          stagingRun,
        )
      ),
    publicationDirectories =
      captureDirectoryIdentities(
        listOf(projectRoot, projectRoot.resolve(acceptedComponents.first()), acceptedParent)
      ),
  )
}

internal fun validateAttemptPaths(paths: ScorecardAttemptPaths) {
  validateDirectoryIdentities(paths.projectRoot, paths.stagingDirectories)
  validateDirectoryIdentities(paths.projectRoot, paths.publicationDirectories)
  require(!Files.isSymbolicLink(paths.acceptedRun)) {
    "Reserved publication path must not be a symbolic link"
  }
  require(paths.acceptedRun.normalize().startsWith(paths.projectRoot)) {
    "Reserved publication path escapes the project root"
  }
  if (!paths.acceptedExisted) {
    require(Files.notExists(paths.acceptedRun, NOFOLLOW_LINKS)) {
      "Reserved scorecard path changed after bootstrap: ${paths.acceptedRun}"
    }
  }
}

internal fun validateStagingPaths(paths: ScorecardAttemptPaths) {
  validateDirectoryIdentities(paths.projectRoot, paths.stagingDirectories)
}

internal fun validatePublishedPaths(paths: ScorecardAttemptPaths) {
  validateDirectoryIdentities(paths.projectRoot, paths.stagingDirectories.dropLast(1))
  validateDirectoryIdentities(paths.projectRoot, paths.publicationDirectories)
  require(Files.notExists(paths.stagingRun, NOFOLLOW_LINKS)) {
    "Reserved staging path still exists after publication"
  }
  validateRelocatedDirectory(
    paths.projectRoot,
    paths.stagingDirectories.last(),
    paths.acceptedRun,
  )
}

internal fun validateFailedPublicationPaths(paths: ScorecardAttemptPaths) {
  validateDirectoryIdentities(paths.projectRoot, paths.stagingDirectories.dropLast(1))
  validateDirectoryIdentities(paths.projectRoot, paths.publicationDirectories)
  require(Files.notExists(paths.stagingRun, NOFOLLOW_LINKS)) {
    "Reserved staging path changed during failed publication"
  }
  validateRelocatedDirectory(
    paths.projectRoot,
    paths.stagingDirectories.last(),
    paths.acceptedRun,
  )
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

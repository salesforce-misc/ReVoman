package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

internal data class ScorecardAttemptPaths(
  val projectRoot: Path,
  val startedAt: Instant,
  val runId: String,
  val stagingRun: Path,
  val acceptedRun: Path,
  val acceptedExisted: Boolean,
  val stagingDirectories: List<ScorecardPathIdentity>,
  val publicationDirectories: List<ScorecardPathIdentity>,
)

internal data class ScorecardPathIdentity(
  val path: Path,
  val realPath: Path,
  val fileKey: String,
)

internal fun captureDirectoryIdentities(paths: List<Path>): List<ScorecardPathIdentity> =
  paths.map { path ->
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(attributes.isDirectory && !Files.isSymbolicLink(path)) {
      "Reserved scorecard path must be a directory, not a symbolic link: $path"
    }
    ScorecardPathIdentity(
      path = path,
      realPath = path.toRealPath(NOFOLLOW_LINKS),
      fileKey =
        requireNotNull(attributes.fileKey()) {
            "Reserved scorecard path does not expose stable file identity: $path"
          }
          .toString(),
    )
  }

internal fun validateDirectoryIdentities(
  projectRoot: Path,
  identities: List<ScorecardPathIdentity>,
) {
  identities.forEach { identity ->
    require(!Files.isSymbolicLink(identity.path)) {
      "Reserved scorecard path became a symbolic link: ${identity.path}"
    }
    require(Files.isDirectory(identity.path, NOFOLLOW_LINKS)) {
      "Reserved scorecard path disappeared or stopped being a directory: ${identity.path}"
    }
    val attributes =
      Files.readAttributes(identity.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val currentRealPath = identity.path.toRealPath(NOFOLLOW_LINKS)
    require(
      attributes.isDirectory &&
        Files.isWritable(identity.path) &&
        currentRealPath == identity.realPath &&
        currentRealPath.startsWith(projectRoot) &&
        attributes.fileKey()?.toString() == identity.fileKey
    ) {
      "Reserved scorecard path changed after bootstrap: ${identity.path}"
    }
  }
}

internal fun validateRelocatedDirectory(
  projectRoot: Path,
  expected: ScorecardPathIdentity,
  relocated: Path,
) {
  require(!Files.isSymbolicLink(relocated)) {
    "Reserved scorecard path became a symbolic link: $relocated"
  }
  require(Files.isDirectory(relocated, NOFOLLOW_LINKS)) {
    "Reserved scorecard path disappeared or stopped being a directory: $relocated"
  }
  val attributes = Files.readAttributes(relocated, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  require(
    attributes.isDirectory &&
      relocated.toRealPath(NOFOLLOW_LINKS).startsWith(projectRoot) &&
      attributes.fileKey()?.toString() == expected.fileKey
  ) {
    "Reserved scorecard path changed during publication: $relocated"
  }
}

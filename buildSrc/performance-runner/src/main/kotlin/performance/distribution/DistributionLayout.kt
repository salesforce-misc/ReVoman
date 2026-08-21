/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import performance.hash.Sha256

internal data class DistributionFiles(
  val root: Path,
  val relativePaths: List<String>,
) {
  fun resolve(relativePath: String): Path = root.resolve(relativePath).normalize()
}

internal sealed interface DistributionLayoutInspection {
  data class Valid(val files: DistributionFiles) : DistributionLayoutInspection

  data class Invalid(val problem: DistributionProblem) : DistributionLayoutInspection
}

internal object DistributionLayout {
  const val PRODUCTION_JAR = "app/revoman.jar"
  const val BENCHMARK_JAR = "benchmark/revoman-jmh.jar"
  const val UNIX_LAUNCHER = "bin/performance-runner"
  const val WINDOWS_LAUNCHER = "bin/performance-runner.bat"
  const val RUNNER_JAR = "runner/performance-runner.jar"
  const val CLASSPATH_MANIFEST = "metadata/classpath.json"
  const val PROVENANCE_MANIFEST = "metadata/provenance.json"
  const val PROTOCOL_MANIFEST = "metadata/protocol.json"
  const val CHECKSUM_MANIFEST = "metadata/distribution.sha256"

  fun inspect(requestedRoot: Path): DistributionLayoutInspection {
    val root = requestedRoot.toAbsolutePath().normalize()
    if (
      Files.isSymbolicLink(root) ||
        !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
    ) {
      return DistributionLayoutInspection.Invalid(DistributionProblem.INVALID_LAYOUT)
    }

    val paths =
      runCatching {
          Files.walk(root).use { stream -> stream.toList() }
        }
        .getOrElse {
          return DistributionLayoutInspection.Invalid(DistributionProblem.INVALID_LAYOUT)
        }
    if (paths.any(Files::isSymbolicLink)) {
      return DistributionLayoutInspection.Invalid(DistributionProblem.SYMBOLIC_LINK_NOT_ALLOWED)
    }
    if (
      paths
        .asSequence()
        .filter { it != root }
        .any { path ->
          !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        }
    ) {
      return DistributionLayoutInspection.Invalid(DistributionProblem.INVALID_LAYOUT)
    }

    val relativePaths =
      paths
        .asSequence()
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .map(root::relativize)
        .map(::portablePath)
        .sorted()
        .toList()
    val relativeDirectories =
      paths
        .asSequence()
        .filter { it != root && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        .map(root::relativize)
        .map(::portablePath)
        .toList()
    if (
      relativePaths.any { !isAllowedFile(it) } ||
        relativeDirectories.any { it !in ALLOWED_DIRECTORIES } ||
        REQUIRED_LAUNCHERS.any { it !in relativePaths }
    ) {
      return DistributionLayoutInspection.Invalid(DistributionProblem.INVALID_LAYOUT)
    }
    return DistributionLayoutInspection.Valid(
      DistributionFiles(root = root, relativePaths = immutableList(relativePaths)),
    )
  }

  fun isNormalizedRelativePath(value: String): Boolean {
    if (
      value.isBlank() ||
        value.startsWith('/') ||
        value.startsWith('\\') ||
        value.contains('\\') ||
        value.contains('\u0000') ||
        value.contains("//") ||
        value.contains('*') ||
        value.contains('?') ||
        WINDOWS_ABSOLUTE.matches(value)
    ) {
      return false
    }
    val path = runCatching { Path.of(value) }.getOrNull() ?: return false
    return !path.isAbsolute &&
      path.nameCount > 0 &&
      path.none { segment -> segment.toString() == "." || segment.toString() == ".." } &&
      portablePath(path.normalize()) == value
  }

  private fun isAllowedFile(relativePath: String): Boolean =
    relativePath in EXACT_FILES ||
      BENCHMARK_LIBRARY.matches(relativePath) ||
      RUNNER_LIBRARY.matches(relativePath) ||
      PROTOCOL_SCHEMA.matches(relativePath)

  private val EXACT_FILES =
    setOf(
      PRODUCTION_JAR,
      BENCHMARK_JAR,
      UNIX_LAUNCHER,
      WINDOWS_LAUNCHER,
      RUNNER_JAR,
      CLASSPATH_MANIFEST,
      PROVENANCE_MANIFEST,
      PROTOCOL_MANIFEST,
      CHECKSUM_MANIFEST,
      "protocol/adapter/run",
      "protocol/profiles/canary.json",
      "protocol/profiles/cold.json",
      "protocol/profiles/warm.json",
      "protocol/runtime/linux-arm64.json",
      "protocol/runtime/m4max-docker.json",
      "protocol/runtime/github-hosted.json",
      "protocol/qualification/m4max-docker.json",
      "protocol/qualification/github-hosted.json",
      "protocol/test-vectors/bootstrap-v1.json",
      "protocol/expected-cells.json",
    )
  private val ALLOWED_DIRECTORIES =
    setOf(
      "app",
      "benchmark",
      "bin",
      "lib",
      "metadata",
      "protocol",
      "protocol/adapter",
      "protocol/profiles",
      "protocol/qualification",
      "protocol/runtime",
      "protocol/schemas",
      "protocol/test-vectors",
      "runner",
      "runner/lib",
    )
  private val BENCHMARK_LIBRARY = Regex("lib/[A-Za-z0-9_.-]+\\.jar")
  private val RUNNER_LIBRARY = Regex("runner/lib/[A-Za-z0-9_.-]+\\.jar")
  private val REQUIRED_LAUNCHERS = setOf(UNIX_LAUNCHER, WINDOWS_LAUNCHER)
  private val PROTOCOL_SCHEMA =
    Regex("protocol/schemas/[A-Za-z0-9_.-]+-v[0-9]+\\.schema\\.json")
  private val WINDOWS_ABSOLUTE = Regex("[A-Za-z]:[/\\\\].*")
}

internal sealed interface DistributionChecksumValidation {
  data object Valid : DistributionChecksumValidation

  data class Invalid(val problems: List<DistributionProblem>) : DistributionChecksumValidation
}

internal object DistributionChecksumValidator {
  private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([^\\r\\n]+)")

  fun validate(files: DistributionFiles): DistributionChecksumValidation {
    val manifest = files.resolve(DistributionLayout.CHECKSUM_MANIFEST)
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
      return DistributionChecksumValidation.Invalid(
        listOf(DistributionProblem.CHECKSUM_MANIFEST_MISSING),
      )
    }
    val text =
      runCatching { Files.readString(manifest, StandardCharsets.UTF_8) }
        .getOrElse {
          return DistributionChecksumValidation.Invalid(
            listOf(DistributionProblem.CHECKSUM_MANIFEST_INVALID),
          )
        }
    if (!text.endsWith('\n') || text.contains('\r')) {
      return DistributionChecksumValidation.Invalid(
        listOf(DistributionProblem.CHECKSUM_MANIFEST_INVALID),
      )
    }
    val lines = text.dropLast(1).split('\n')
    if (lines.isEmpty() || lines.any(String::isEmpty)) {
      return DistributionChecksumValidation.Invalid(
        listOf(DistributionProblem.CHECKSUM_MANIFEST_INVALID),
      )
    }
    val parsed =
      lines.map { line ->
        val match = CHECKSUM_LINE.matchEntire(line)
          ?: return DistributionChecksumValidation.Invalid(
            listOf(DistributionProblem.CHECKSUM_MANIFEST_INVALID),
          )
        match.groupValues[2] to Sha256.parse(match.groupValues[1])
      }
    val paths = parsed.map(Pair<String, Sha256>::first)
    if (
      paths != paths.sorted() ||
        paths.distinct().size != paths.size ||
        paths.any { path ->
          path == DistributionLayout.CHECKSUM_MANIFEST ||
            !DistributionLayout.isNormalizedRelativePath(path)
        }
    ) {
      return DistributionChecksumValidation.Invalid(
        listOf(DistributionProblem.CHECKSUM_MANIFEST_INVALID),
      )
    }

    val actualPaths =
      files.relativePaths.filterNot { it == DistributionLayout.CHECKSUM_MANIFEST }
    val problems = mutableListOf<DistributionProblem>()
    if (actualPaths.any { it !in paths }) {
      problems += DistributionProblem.CHECKSUM_ENTRY_MISSING
    }
    if (paths.any { it !in actualPaths }) {
      problems += DistributionProblem.CHECKSUM_ENTRY_TARGET_MISSING
    }
    parsed
      .asSequence()
      .filter { (path) -> path in actualPaths }
      .filter { (path, expected) -> digest(files.resolve(path)) != expected }
      .forEach { problems += DistributionProblem.CHECKSUM_MISMATCH }
    return problems
      .distinct()
      .takeIf(List<DistributionProblem>::isNotEmpty)
      ?.let { DistributionChecksumValidation.Invalid(immutableList(it)) }
      ?: DistributionChecksumValidation.Valid
  }
}

internal fun digest(path: Path): Sha256 {
  val messageDigest = MessageDigest.getInstance("SHA-256")
  Files.newInputStream(path).use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    generateSequence { input.read(buffer).takeIf { it >= 0 } }
      .takeWhile { it != 0 }
      .forEach { count -> messageDigest.update(buffer, 0, count) }
  }
  return Sha256.parse(
    messageDigest.digest().joinToString(separator = "") { byte ->
      "%02x".format(byte.toInt() and 0xff)
    },
  )
}

internal fun portablePath(path: Path): String =
  path.joinToString(separator = "/") { it.toString() }

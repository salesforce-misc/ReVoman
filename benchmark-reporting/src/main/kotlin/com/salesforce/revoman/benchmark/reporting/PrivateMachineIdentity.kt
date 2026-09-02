package com.salesforce.revoman.benchmark.reporting

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class PrivateMachineIdentity(
  val userName: String,
  val userHome: String,
  val hostName: String,
) {
  val values: Set<String>
    get() {
      require(listOf(userName, userHome, hostName).all(String::isNotBlank)) {
        "Private machine identity is unavailable"
      }
      return setOf(userName, userHome, hostName)
    }
}

internal fun validateEvidencePrivacy(root: Path, identity: PrivateMachineIdentity) {
  val forbidden = identity.values.map { it.toByteArray(StandardCharsets.UTF_8) }
  Files.walk(root).use { paths ->
    paths.forEach { path ->
      require(!Files.isSymbolicLink(path)) { "Scorecard evidence must not contain symbolic links" }
      val relativePath = root.relativize(path).toString().toByteArray(StandardCharsets.UTF_8)
      require(forbidden.none { relativePath.contains(it) }) { PRIVATE_IDENTITY_ERROR }
      if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
        val contents = Files.readAllBytes(path)
        require(forbidden.none { contents.contains(it) }) { PRIVATE_IDENTITY_ERROR }
      }
    }
  }
}

private fun ByteArray.contains(candidate: ByteArray): Boolean {
  if (candidate.isEmpty() || candidate.size > size) return false
  return (0..size - candidate.size).any { start ->
    candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
  }
}

private const val PRIVATE_IDENTITY_ERROR = "Scorecard evidence contains a private identity"

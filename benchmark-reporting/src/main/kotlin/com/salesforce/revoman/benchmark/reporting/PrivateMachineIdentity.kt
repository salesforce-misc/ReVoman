package com.salesforce.revoman.benchmark.reporting

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class PrivateMachineIdentity(
  val userName: String,
  val userHome: String,
  val hostName: String,
) {
  val values: Set<String>
    get() {
      require(listOf(userName, userHome, hostName).all(String::isNotBlank)) {
        PRIVATE_IDENTITY_UNAVAILABLE_ERROR
      }
      return setOf(userName, userHome, hostName)
    }
}

internal data class EvidencePrivacySnapshot(val entries: List<EvidenceEntrySnapshot>)

internal data class EvidenceEntrySnapshot(
  val relativePath: Path,
  val fileKey: String,
  val size: Long,
  val lastModifiedTime: FileTime,
  val isDirectory: Boolean,
)

internal fun validateEvidencePrivacy(
  root: Path,
  identity: PrivateMachineIdentity,
): EvidencePrivacySnapshot = privacyFileSystemOperation {
  val forbidden = identity.values.map { it.toByteArray(StandardCharsets.UTF_8) }
  EvidencePrivacySnapshot(captureEvidence(root, forbidden))
}

internal fun revalidateEvidencePrivacy(root: Path, expected: EvidencePrivacySnapshot) {
  privacyFileSystemOperation {
    require(captureEvidence(root, emptyList()) == expected.entries) { EVIDENCE_CHANGED_ERROR }
  }
}

internal fun linuxPrivateMachineIdentity(
  processStatus: String,
  passwd: String,
  kernelHostname: String,
): PrivateMachineIdentity {
  val uid =
    processStatus
      .lineSequence()
      .firstOrNull { line -> line.startsWith("Uid:") }
      ?.substringAfter(':')
      ?.trim()
      ?.split(Regex("\\s+"))
      ?.firstOrNull()
      ?.toLongOrNull()
      ?.takeIf { it >= 0 } ?: unavailablePrivateMachineIdentity()
  val accounts =
    passwd
      .lineSequence()
      .filter(String::isNotBlank)
      .map { line -> line.split(':') }
      .filter { fields -> fields.size >= PASSWD_FIELD_COUNT && fields[2].toLongOrNull() == uid }
      .toList()
  if (accounts.size != 1) unavailablePrivateMachineIdentity()
  val account = accounts.single()
  val values = listOf(account[0], account[5], kernelHostname.trim())
  if (values.any(String::isBlank)) unavailablePrivateMachineIdentity()
  return PrivateMachineIdentity(values[0], values[1], values[2])
}

private fun captureEvidence(root: Path, forbidden: List<ByteArray>): List<EvidenceEntrySnapshot> =
  Files.walk(root).use { paths ->
    paths
      .map { path -> captureEntry(root, path, forbidden) }
      .sorted(compareBy { snapshot -> snapshot.relativePath.toString() })
      .toList()
  }

private fun captureEntry(
  root: Path,
  path: Path,
  forbidden: List<ByteArray>,
): EvidenceEntrySnapshot {
  val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  require(before.isDirectory || before.isRegularFile) { UNSUPPORTED_ENTRY_ERROR }
  val relativePath = root.relativize(path)
  val relativePathBytes = relativePath.toString().toByteArray(StandardCharsets.UTF_8)
  require(
    forbidden.none { candidate -> relativePathBytes.contains(candidate, relativePathBytes.size) }
  ) {
    PRIVATE_IDENTITY_ERROR
  }
  if (before.isRegularFile) {
    require(!fileContains(path, forbidden)) { PRIVATE_IDENTITY_ERROR }
  }
  val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  val beforeSnapshot = entrySnapshot(relativePath, before)
  val afterSnapshot = entrySnapshot(relativePath, after)
  require(beforeSnapshot == afterSnapshot) { EVIDENCE_CHANGED_ERROR }
  return afterSnapshot
}

private fun entrySnapshot(
  relativePath: Path,
  attributes: BasicFileAttributes,
): EvidenceEntrySnapshot =
  EvidenceEntrySnapshot(
    relativePath,
    requireNotNull(attributes.fileKey()) { PRIVACY_IO_ERROR }.toString(),
    attributes.size(),
    attributes.lastModifiedTime(),
    attributes.isDirectory,
  )

private fun fileContains(path: Path, candidates: List<ByteArray>): Boolean {
  if (candidates.isEmpty()) return false
  val overlap = candidates.maxOf(ByteArray::size) - 1
  val buffer = ByteArray(PRIVACY_BUFFER_SIZE + overlap)
  return Files.newInputStream(path, NOFOLLOW_LINKS).buffered().use { input ->
    var carried = 0
    var found = false
    while (!found) {
      val read = input.read(buffer, carried, PRIVACY_BUFFER_SIZE)
      if (read < 0) break
      val available = carried + read
      found = candidates.any { candidate -> buffer.contains(candidate, available) }
      carried = minOf(overlap, available)
      buffer.copyInto(buffer, 0, available - carried, available)
    }
    found
  }
}

private fun ByteArray.contains(candidate: ByteArray, available: Int): Boolean {
  if (candidate.isEmpty() || candidate.size > available) return false
  return (0..available - candidate.size).any { start ->
    candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
  }
}

private inline fun <T> privacyFileSystemOperation(operation: () -> T): T =
  try {
    operation()
  } catch (_: IOException) {
    throw IllegalArgumentException(PRIVACY_IO_ERROR)
  } catch (_: UncheckedIOException) {
    throw IllegalArgumentException(PRIVACY_IO_ERROR)
  } catch (_: SecurityException) {
    throw IllegalArgumentException(PRIVACY_IO_ERROR)
  }

internal fun unavailablePrivateMachineIdentity(): Nothing =
  throw IllegalArgumentException(PRIVATE_IDENTITY_UNAVAILABLE_ERROR)

private const val PASSWD_FIELD_COUNT = 7
private const val PRIVACY_BUFFER_SIZE = 8192
internal const val PRIVATE_IDENTITY_UNAVAILABLE_ERROR = "Private machine identity is unavailable"
private const val PRIVATE_IDENTITY_ERROR = "Scorecard evidence contains a private identity"
private const val UNSUPPORTED_ENTRY_ERROR = "Scorecard evidence contains an unsupported entry"
private const val EVIDENCE_CHANGED_ERROR = "Scorecard evidence changed after privacy validation"
private const val PRIVACY_IO_ERROR = "Scorecard evidence privacy validation failed"

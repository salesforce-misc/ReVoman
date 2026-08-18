/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.publication

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import performance.hash.Sha256

/** Strict recursive checksum authority shared by finalization, publication, and recovery. */
internal object ChecksumManifest {
  private const val MANIFEST = "checksums.sha256"
  private val LINE = Regex("([0-9a-f]{64})\\x20\\x20([^\\r\\n]+)")

  fun create(root: Path): Result<ByteArray> = runCatching {
    requireSafeRoot(root)
    val paths = regularFiles(root).filter { it != MANIFEST }.sortedWith(::compareUnsignedUtf8)
    require(paths.distinct().size == paths.size)
    paths
      .joinToString(separator = "\n", postfix = "\n") { relative ->
        "${Sha256.digest(root.resolve(relative)).hex}  $relative"
      }
      .encodeToByteArray()
  }

  fun write(root: Path) {
    val bytes = create(root).getOrThrow()
    val target = root.resolve(MANIFEST)
    require(!Files.exists(target, NOFOLLOW_LINKS))
    writeFsynced(target, bytes)
    fsync(root)
  }

  fun verify(root: Path): Boolean = runCatching {
    requireSafeRoot(root)
    val manifest = root.resolve(MANIFEST)
    require(Files.isRegularFile(manifest, NOFOLLOW_LINKS) && !Files.isSymbolicLink(manifest))
    val bytes = Files.readAllBytes(manifest)
    val text = bytes.decodeToString()
    require(text.isNotEmpty() && text.endsWith('\n') && '\r' !in text)
    val actual =
      text.dropLast(1).split('\n').map { line ->
        val match = requireNotNull(LINE.matchEntire(line))
        val relative = normalizeRelative(match.groupValues[2])
        require(relative != MANIFEST)
        relative to match.groupValues[1]
      }
    require(actual.map(Pair<String, String>::first).distinct().size == actual.size)
    require(actual.map(Pair<String, String>::first) == actual.map(Pair<String, String>::first).sortedWith(::compareUnsignedUtf8))
    val expectedPaths = regularFiles(root).filter { it != MANIFEST }.sortedWith(::compareUnsignedUtf8)
    require(actual.map(Pair<String, String>::first) == expectedPaths)
    actual.all { (relative, expected) -> Sha256.digest(root.resolve(relative)).hex == expected }
  }.getOrDefault(false)

  private fun requireSafeRoot(root: Path) {
    require(root.isAbsolute && root == root.toAbsolutePath().normalize())
    require(!hasSymbolicLinkComponent(root))
    require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
  }

  private fun regularFiles(root: Path): List<String> =
    Files.walk(root).use { paths ->
      paths
        .map { path ->
          when {
            path == root -> null
            Files.isSymbolicLink(path) -> error("symbolic links are forbidden")
            Files.isDirectory(path, NOFOLLOW_LINKS) -> null
            Files.isRegularFile(path, NOFOLLOW_LINKS) -> portable(root.relativize(path))
            else -> error("special files are forbidden")
          }
        }
        .filter { it != null }
        .map { checkNotNull(it) }
        .toList()
    }

  private fun portable(path: Path): String =
    normalizeRelative((0 until path.nameCount).joinToString("/") { path.getName(it).toString() })

  private fun normalizeRelative(value: String): String {
    require(value.isNotBlank() && '\\' !in value && !value.startsWith('/'))
    val path = Path.of(value)
    require(!path.isAbsolute && path.normalize() == path)
    require(path.none { it.toString() in setOf("", ".", "..") })
    val normalized = (0 until path.nameCount).joinToString("/") { path.getName(it).toString() }
    require(normalized == value)
    return normalized
  }

  private fun hasSymbolicLinkComponent(path: Path): Boolean {
    var current = path.root ?: return true
    return path.any { component ->
      current = current.resolve(component)
      Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)
    }
  }

  private fun compareUnsignedUtf8(left: String, right: String): Int =
    java.util.Arrays.compareUnsigned(left.encodeToByteArray(), right.encodeToByteArray())

  private fun writeFsynced(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  internal fun fsync(directory: Path) {
    FileChannel.open(directory, READ).use { it.force(true) }
  }
}

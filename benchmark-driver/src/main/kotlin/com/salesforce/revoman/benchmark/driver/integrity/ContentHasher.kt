/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.integrity

import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Computes byte-exact identities for benchmark fixtures and ordered artifact sets. */
object ContentHasher {
    private val treePrefix = "revoman-benchmark-tree/v1\u0000".toByteArray(UTF_8)
    private val artifactSetPrefix = "revoman-benchmark-artifact-set/v1\u0000".toByteArray(UTF_8)
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    /** Returns the lowercase SHA-256 of [bytes]. */
    fun sha256(bytes: ByteArray): String = digestHex { update(bytes) }

    /** Returns the lowercase SHA-256 of the raw bytes at [path]. */
    fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    /**
     * Hashes files below [root] using sorted POSIX relative paths, explicit lengths, and raw bytes.
     */
    fun treeSha256(root: Path, files: Iterable<Path>): String {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val records =
            files
                .map { file ->
                    val normalizedFile = file.toAbsolutePath().normalize()
                    require(normalizedFile != normalizedRoot && normalizedFile.startsWith(normalizedRoot)) {
                        "Tree hash file must be below root $normalizedRoot: $file"
                    }
                    require(Files.isRegularFile(normalizedFile)) {
                        "Tree hash input must be a regular file: $file"
                    }
                    val relativePath =
                        normalizedRoot
                            .relativize(normalizedFile)
                            .map(Path::toString)
                            .joinToString("/")
                    TreeRecord(relativePath, Files.readAllBytes(normalizedFile))
                }
                .sortedBy(TreeRecord::relativePath)

        require(records.map(TreeRecord::relativePath).distinct().size == records.size) {
            "Tree hash inputs must not contain duplicate relative paths"
        }

        return treeSha256(records.associate { record -> record.relativePath to record.bytes })
    }

    /** Hashes already captured bytes using the same portable tree contract as filesystem inputs. */
    internal fun treeSha256(files: Map<String, ByteArray>): String {
        require(files.keys.none(String::isBlank)) { "Tree hash paths must not be blank" }
        return digestHex {
            update(treePrefix)
            files.toSortedMap().forEach { (relativePath, bytes) ->
                val relativePathBytes = relativePath.toByteArray(UTF_8)
                updateInt(relativePathBytes.size)
                update(relativePathBytes)
                updateLong(bytes.size.toLong())
                update(bytes)
            }
        }
    }

    /**
     * Hashes ordered logical ID, size, and content-hash records while excluding execution paths.
     */
    fun artifactSetSha256(artifacts: List<HashedArtifact>): String =
        digestHex {
            update(artifactSetPrefix)
            artifacts.forEachIndexed { index, artifact ->
                require(artifact.logicalId.isNotBlank()) {
                    "artifacts[$index].logicalId must not be blank"
                }
                require(artifact.sizeBytes >= 0) {
                    "artifacts[$index].sizeBytes must not be negative"
                }
                require(artifact.sha256.matches(sha256Pattern)) {
                    "artifacts[$index].sha256 must be a lowercase 64-character SHA-256 hash"
                }
                val logicalIdBytes = artifact.logicalId.toByteArray(UTF_8)
                updateInt(logicalIdBytes.size)
                update(logicalIdBytes)
                updateLong(artifact.sizeBytes)
                update(artifact.sha256.toByteArray(UTF_8))
            }
        }

    private fun digestHex(updates: MessageDigest.() -> Unit): String =
        MessageDigest.getInstance("SHA-256").run {
            updates()
            HexFormat.of().formatHex(digest())
        }

    private fun MessageDigest.updateInt(value: Int) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }

    private fun MessageDigest.updateLong(value: Long) {
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private data class TreeRecord(val relativePath: String, val bytes: ByteArray)
}

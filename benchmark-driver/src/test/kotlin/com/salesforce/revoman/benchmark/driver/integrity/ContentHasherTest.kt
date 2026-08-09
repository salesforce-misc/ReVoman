/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.integrity

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContentHasherTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `tree hash is path sorted and order independent`() {
        val first = write("zeta.txt", "last")
        val second = write("nested/alpha.txt", "first")

        val forward = ContentHasher.treeSha256(temporaryDirectory, listOf(first, second))
        val reverse = ContentHasher.treeSha256(temporaryDirectory, listOf(second, first))

        assertThat(reverse).isEqualTo(forward)
    }

    @Test
    fun `tree hash distinguishes CRLF and LF bytes`() {
        val rootWithLf = Files.createDirectory(temporaryDirectory.resolve("lf"))
        val rootWithCrlf = Files.createDirectory(temporaryDirectory.resolve("crlf"))
        val lf = rootWithLf.resolve("fixture.txt").also { Files.write(it, "a\nb\n".toByteArray()) }
        val crlf = rootWithCrlf.resolve("fixture.txt").also { Files.write(it, "a\r\nb\r\n".toByteArray()) }

        val lfHash = ContentHasher.treeSha256(rootWithLf, listOf(lf))
        val crlfHash = ContentHasher.treeSha256(rootWithCrlf, listOf(crlf))

        assertThat(crlfHash).isNotEqualTo(lfHash)
    }

    @Test
    fun `one byte fixture change changes tree hash`() {
        val originalRoot = Files.createDirectory(temporaryDirectory.resolve("original"))
        val changedRoot = Files.createDirectory(temporaryDirectory.resolve("changed"))
        val original = originalRoot.resolve("fixture.bin").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val changed = changedRoot.resolve("fixture.bin").also { Files.copy(original, it) }
        val changedBytes = Files.readAllBytes(changed).also { it[it.lastIndex] = 2 }
        Files.write(changed, changedBytes)

        val originalHash = ContentHasher.treeSha256(originalRoot, listOf(original))
        val changedHash = ContentHasher.treeSha256(changedRoot, listOf(changed))

        assertThat(changedHash).isNotEqualTo(originalHash)
    }

    @Test
    fun `artifact set identity ignores execution path but not logical id or bytes`() {
        val firstCheckout = listOf(artifact("runtime.jar", "/checkout-a/runtime.jar", 8, "a"))
        val secondCheckout = listOf(artifact("runtime.jar", "/checkout-b/runtime.jar", 8, "a"))
        val changedLogicalId = listOf(artifact("renamed.jar", "/checkout-b/runtime.jar", 8, "a"))
        val changedBytes = listOf(artifact("runtime.jar", "/checkout-b/runtime.jar", 8, "b"))

        val firstIdentity = ContentHasher.artifactSetSha256(firstCheckout)

        assertThat(ContentHasher.artifactSetSha256(secondCheckout)).isEqualTo(firstIdentity)
        assertThat(ContentHasher.artifactSetSha256(changedLogicalId)).isNotEqualTo(firstIdentity)
        assertThat(ContentHasher.artifactSetSha256(changedBytes)).isNotEqualTo(firstIdentity)
    }

    @Test
    fun `artifact set identity preserves executable classpath order`() {
        val first = artifact("first.jar", "/checkout/first.jar", 8, "a")
        val second = artifact("second.jar", "/checkout/second.jar", 8, "b")

        val executableOrder = ContentHasher.artifactSetSha256(listOf(first, second))
        val swappedOrder = ContentHasher.artifactSetSha256(listOf(second, first))

        assertThat(swappedOrder).isNotEqualTo(executableOrder)
    }

    private fun write(relativePath: String, contents: String): Path =
        temporaryDirectory.resolve(relativePath).also {
            Files.createDirectories(requireNotNull(it.parent))
            Files.writeString(it, contents)
        }

    private fun artifact(logicalId: String, executionPath: String, sizeBytes: Long, hashDigit: String) =
        HashedArtifact(
            logicalId = logicalId,
            executionPath = executionPath,
            sizeBytes = sizeBytes,
            sha256 = hashDigit.repeat(64),
        )
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.integrity

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TargetManifestLoaderTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `loader schema validates decodes and fully preflights one captured manifest`() {
        val manifestPath = writeManifest("candidate", dirty = false)

        val loaded = TargetManifestLoader.load(manifestPath)

        assertThat(loaded.manifest.targetId).isEqualTo("candidate")
        assertThat(loaded.verified.manifest).isEqualTo(loaded.manifest)
        assertThat(loaded.verified.manifestPath).isEqualTo(manifestPath)
        assertThat(loaded.snapshotBytes).isEqualTo(Files.readAllBytes(manifestPath))
    }

    @Test
    fun `loader rejects unknown schema fields before model decoding`() {
        val path = writeManifest("candidate", dirty = false)
        Files.writeString(path, Files.readString(path).replaceFirst("{", "{\"unknown\":true,"))

        val failure = assertThrows<IllegalArgumentException> { TargetManifestLoader.load(path) }

        assertThat(failure).hasMessageThat().contains("revoman-target-manifest-v1.schema.json")
    }

    @Test
    fun `loader rejects a changed classpath byte`() {
        val path = writeManifest("candidate", dirty = false)
        val manifest = BenchmarkJson.read<TargetManifest>(path)
        Files.writeString(Path.of(manifest.classpath.single().executionPath), "changed")

        val failure = assertThrows<IllegalArgumentException> { TargetManifestLoader.load(path) }

        assertThat(failure).hasMessageThat().contains("mismatch")
    }

    @Test
    fun `clean release requirement rejects dirty manifest but structural load preserves it`() {
        val loaded = TargetManifestLoader.load(writeManifest("candidate", dirty = true))

        assertThat(loaded.manifest.dirty).isTrue()
        val failure = assertThrows<IllegalArgumentException> { loaded.requireClean("verification") }
        assertThat(failure).hasMessageThat().contains("clean target")
    }

    @Test
    fun `preflight from one captured snapshot defers later manifest mutation to postflight`() {
        val manifestPath = writeManifest("candidate", dirty = false)
        val bytes = Files.readAllBytes(manifestPath)
        val manifest = BenchmarkJson.decode<TargetManifest>(bytes, manifestPath.toString())
        BenchmarkJson.write(manifestPath, manifest.copy(targetId = "later-mutation"))

        val verified =
            VerifiedTargetManifest.preflightFromSnapshot(
                manifestPath = manifestPath,
                bytes = bytes,
                expectedManifest = manifest,
            )

        assertThat(verified.manifest).isEqualTo(manifest)
        val failure = assertThrows<IllegalStateException> { verified.postflight() }
        assertThat(failure).hasMessageThat().contains("target manifest SHA-256 changed")
    }

    private fun writeManifest(targetId: String, dirty: Boolean): Path {
        val root = Files.createDirectories(temporaryDirectory.resolve(targetId)).toRealPath()
        val jar = Files.writeString(root.resolve("target.jar"), "target-bytes").toRealPath()
        val manifest =
            TargetManifest(
                targetId = targetId,
                gitCommit = "1".repeat(40),
                gitTree = "2".repeat(40),
                dirty = dirty,
                gradleVersion = "9.7",
                wrapperSha256 = "3".repeat(64),
                jdk =
                    JdkIdentity(
                        distribution = "jdk",
                        vendor = "vendor",
                        fullVersion = "21.0.1",
                        javaHome = "/jdk",
                        jvmFlags = emptyList(),
                    ),
                classpath =
                    listOf(
                        HashedArtifact(
                            logicalId = "target/revoman.jar",
                            executionPath = jar.toString(),
                            sizeBytes = Files.size(jar),
                            sha256 = ContentHasher.sha256(jar),
                        )
                    ),
            )
        return root.resolve("manifest.json").also { BenchmarkJson.write(it, manifest) }.toRealPath()
    }
}

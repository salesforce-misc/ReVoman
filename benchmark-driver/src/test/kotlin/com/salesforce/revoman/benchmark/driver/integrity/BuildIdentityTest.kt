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
import com.salesforce.revoman.benchmark.driver.model.AdapterIdentity
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildIdentityTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `source files are enumerated in relative path order`() {
        val root = temporaryDirectory.resolve("source")
        write(root.resolve("z/file.kt"), "z")
        write(root.resolve("a/file.kt"), "a")
        write(root.resolve("middle.kt"), "m")

        val relativePaths =
            BuildIdentity.sourceFiles(root, listOf(root.resolve("z"), root.resolve("a"), root))
                .map(root::relativize)
                .map(Path::toString)

        assertThat(relativePaths).containsExactly("a/file.kt", "middle.kt", "z/file.kt").inOrder()
    }

    @Test
    fun `adapter and workload byte changes alter source identity`() {
        val root = temporaryDirectory.resolve("source")
        val adapter = root.resolve("adapter/Baseline.kt")
        val workload = root.resolve("workloads/manifest.json")
        write(adapter, "adapter-v1")
        write(workload, "workload-v1")

        val first = sourceManifest(root, dirty = false)
        write(adapter, "adapter-v2")
        val adapterChanged = sourceManifest(root, dirty = false)
        write(workload, "workload-v2")
        val workloadChanged = sourceManifest(root, dirty = false)

        assertThat(adapterChanged.sourceSha256).isNotEqualTo(first.sourceSha256)
        assertThat(workloadChanged.sourceSha256).isNotEqualTo(adapterChanged.sourceSha256)
    }

    @Test
    fun `dirty Git state is recorded without entering content identity`() {
        val root = temporaryDirectory.resolve("source")
        write(root.resolve("adapter/Baseline.kt"), "adapter")
        write(root.resolve("workloads/manifest.json"), "workload")

        val clean = sourceManifest(root, dirty = false)
        val dirty = sourceManifest(root, dirty = true)

        assertThat(clean.dirty).isFalse()
        assertThat(dirty.dirty).isTrue()
        assertThat(dirty.sourceSha256).isEqualTo(clean.sourceSha256)
    }

    @Test
    fun `runtime artifact set hash ignores installation root`() {
        val firstRoot = temporaryDirectory.resolve("install-a")
        val secondRoot = temporaryDirectory.resolve("install-b")
        listOf("lib/driver.jar" to "jar", "conf/log.xml" to "log").forEach { (path, bytes) ->
            write(firstRoot.resolve(path), bytes)
            write(secondRoot.resolve(path), bytes)
        }

        val first = BuildIdentity.runtimeArtifactSetSha256(firstRoot)
        val second = BuildIdentity.runtimeArtifactSetSha256(secondRoot)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `embedded source manifest cannot contain its distribution hash`() {
        val root = temporaryDirectory.resolve("source")
        write(root.resolve("adapter/Baseline.kt"), "adapter")
        write(root.resolve("workloads/manifest.json"), "workload")
        val output = temporaryDirectory.resolve("benchmark-harness-source-v1.json")

        BuildIdentity.writeSourceManifest(output, sourceManifest(root, dirty = false))

        val bytes = Files.readString(output)
        assertThat(bytes).doesNotContain("distributionSha256")
        assertThat(bytes).doesNotContain(output.toString())
    }

    @Test
    fun `build identity hashes source trees without timestamps`() {
        val root = temporaryDirectory.resolve("source")
        val adapter = root.resolve("adapter/Baseline.kt")
        val workload = root.resolve("workloads/manifest.json")
        write(adapter, "adapter")
        write(workload, "workload")
        val first = sourceManifest(root, dirty = false)

        Files.setLastModifiedTime(adapter, FileTime.fromMillis(1))
        Files.setLastModifiedTime(workload, FileTime.fromMillis(9_999_999_999L))
        val second = sourceManifest(root, dirty = false)

        assertThat(second.sourceSha256).isEqualTo(first.sourceSha256)
        assertThat(second.artifacts).isEqualTo(first.artifacts)
    }

    @Test
    fun `shared runtime identity factory creates a path free target snapshot`() {
        val installation = temporaryDirectory.resolve("installation")
        write(installation.resolve("lib/driver.jar"), "driver")
        write(installation.resolve("conf/log.xml"), "logging")
        val source =
            HarnessSourceManifest(
                commit = "commit",
                tree = "tree",
                dirty = false,
                sourceSha256 = "1".repeat(64),
                artifacts =
                    listOf(
                        HashedArtifact("source", "source", 1, "2".repeat(64))
                    ),
                workloadContractSha256 = "3".repeat(64),
                fixtureSetSha256 = "4".repeat(64),
                adapters = listOf(AdapterIdentity("adapter", "5".repeat(64))),
            )
        BenchmarkJson.write(installation.resolve("conf/benchmark-harness-source-v1.json"), source)
        val targetRoot = Files.createDirectories(temporaryDirectory.resolve("target")).toRealPath()
        val jar = Files.writeString(targetRoot.resolve("target.jar"), "target").toRealPath()
        val target =
            TargetManifest(
                targetId = "candidate",
                gitCommit = "candidate-commit",
                gitTree = "candidate-tree",
                dirty = false,
                gradleVersion = "9.7",
                wrapperSha256 = "6".repeat(64),
                jdk =
                    JdkIdentity("jdk", "vendor", "21", "/jdk", emptyList()),
                classpath =
                    listOf(
                        HashedArtifact(
                            "project::jar",
                            jar.toString(),
                            Files.size(jar),
                            ContentHasher.sha256(jar),
                        )
                    ),
            )
        val manifestPath = targetRoot.resolve("manifest.json")
        BenchmarkJson.write(manifestPath, target)
        val verified = VerifiedTargetManifest.preflight(manifestPath.toRealPath(), target)

        val factory = RuntimeIdentityFactory(installation.toRealPath())
        val harness = factory.harnessIdentity()
        val identity = factory.targetIdentity(verified, "adapter", harness)

        assertThat(identity.classpath.single().logicalId).isEqualTo("project::jar")
        assertThat(identity.toString()).doesNotContain(targetRoot.toString())
        assertThat(harness.distributionSha256)
            .isEqualTo(ContentHasher.artifactSetSha256(harness.artifacts))
    }

    private fun sourceManifest(root: Path, dirty: Boolean): HarnessSourceManifest =
        BuildIdentity.createSourceManifest(
            repositoryRoot = root,
            git = GitBuildIdentity(commit = "commit", tree = "tree", dirty = dirty),
            adapterRoots = listOf(root.resolve("adapter")),
            benchmarkContractRoots = listOf(root.resolve("workloads")),
        )

    private fun write(path: Path, value: String) {
        Files.createDirectories(requireNotNull(path.parent))
        Files.writeString(path, value)
    }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.metrics.WARM_LIFECYCLE_ALLOCATION_INCLUDE
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.run.loadWarmLifecycleExpectedDigest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class VerifiedLifecycleWorkloadSnapshotTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `controller setup captures manifest and every declared fixture exactly once`() {
        val source = materializeInstallation().resolve(LIFECYCLE_RELATIVE_ROOT).toRealPath()
        val snapshotParent = Files.createDirectory(temporaryDirectory.resolve("snapshots")).toRealPath()
        val reads = linkedMapOf<Path, Int>()

        val snapshot =
            VerifiedLifecycleWorkloadSnapshot.open(
                sourceRoot = source,
                snapshotParent = snapshotParent,
                readBytes = { path ->
                    reads.compute(path) { _, count -> (count ?: 0) + 1 }
                    Files.readAllBytes(path)
                },
            )

        try {
            assertThat(reads)
                .containsExactly(
                    source.resolve("manifest.json"),
                    1,
                    source.resolve("collection.postman_collection.json"),
                    1,
                    source.resolve("handler.json"),
                    1,
                )
            assertThat(snapshot.manifestSha256).isEqualTo(LIFECYCLE_MANIFEST_SHA256)
            assertThat(snapshot.workloadIdentity.manifest).isEqualTo(snapshot.manifest)
            assertThat(Files.readAllBytes(snapshot.snapshotRoot.resolve("manifest.json")))
                .isEqualTo(Files.readAllBytes(source.resolve("manifest.json")))
        } finally {
            snapshot.close()
        }

        assertThat(Files.list(snapshotParent).use { it.toList() }).isEmpty()
    }

    @Test
    fun `source manifest mutation after fixture start cannot change fork oracle or result identity`() {
        val installation = materializeInstallation()
        val sourceManifest = installation.resolve(LIFECYCLE_RELATIVE_ROOT).resolve("manifest.json")
        val reportedIdentity = AtomicReference<WorkloadManifest>()
        val snapshotPath = AtomicReference<Path>()

        val failure = assertThrows<IllegalStateException> {
            withLifecycleFixture(
                requestedIncludes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE),
                installationRoot = installation,
                hooks =
                    LifecycleFixtureHooks { snapshot ->
                        snapshotPath.set(snapshot.snapshotRoot)
                        writeLaterManifest(sourceManifest, checksum = 999)
                    },
            ) { capturedIdentity ->
                val identity = requireNotNull(capturedIdentity)
                val expected =
                    loadWarmLifecycleExpectedDigest(
                        fixtureRoot = requireNotNull(snapshotPath.get()),
                        expectedManifestSha256 = identity.manifestSha256,
                    )
                assertThat(expected.checksum).isEqualTo(31)
                reportedIdentity.set(resolveJmhWorkloadIdentity(identity).manifest)
            }
        }

        assertThat(failure).hasMessageThat().contains("source lifecycle workload invalid after postflight")
        assertThat(requireNotNull(reportedIdentity.get()).expectedDigest?.checksum).isEqualTo(31)
        assertThat(BenchmarkJson.read<WorkloadManifest>(sourceManifest).expectedDigest?.checksum)
            .isEqualTo(999)
        assertThat(Files.exists(requireNotNull(snapshotPath.get()))).isFalse()
    }

    @Test
    fun `snapshot manifest mutation before fork is rejected by captured hash binding`() {
        val installation = materializeInstallation()
        val snapshotPath = AtomicReference<Path>()

        val failure = assertThrows<IllegalArgumentException> {
            withLifecycleFixture(
                requestedIncludes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE),
                installationRoot = installation,
                hooks =
                    LifecycleFixtureHooks { snapshot ->
                        snapshotPath.set(snapshot.snapshotRoot)
                        writeLaterManifest(snapshot.snapshotRoot.resolve("manifest.json"), checksum = 999)
                    },
            ) { capturedIdentity ->
                loadWarmLifecycleExpectedDigest(
                    fixtureRoot = requireNotNull(snapshotPath.get()),
                    expectedManifestSha256 = requireNotNull(capturedIdentity).manifestSha256,
                )
            }
        }

        assertThat(failure).hasMessageThat().contains("lifecycle manifest SHA-256 mismatch")
        assertThat(failure.suppressed.asList()).hasSize(1)
        assertThat(failure.suppressed.single())
            .hasMessageThat()
            .contains("snapshot lifecycle workload invalid after postflight")
        assertThat(Files.exists(requireNotNull(snapshotPath.get()))).isFalse()
    }

    @Test
    fun `source fixture byte mutation invalidates lifecycle evidence and cleans snapshot`() {
        val installation = materializeInstallation()
        val sourceFixture =
            installation
                .resolve(LIFECYCLE_RELATIVE_ROOT)
                .resolve("collection.postman_collection.json")
        val snapshotPath = AtomicReference<Path>()

        val failure = assertThrows<IllegalStateException> {
            withLifecycleFixture(
                requestedIncludes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE),
                installationRoot = installation,
                hooks =
                    LifecycleFixtureHooks { snapshot ->
                        snapshotPath.set(snapshot.snapshotRoot)
                        Files.writeString(sourceFixture, "changed")
                    },
            ) {}
        }

        assertThat(failure).hasMessageThat().contains("source lifecycle workload invalid after postflight")
        assertThat(failure).hasMessageThat().contains("collection.postman_collection.json SHA-256 changed")
        assertThat(Files.exists(requireNotNull(snapshotPath.get()))).isFalse()
    }

    @Test
    fun `primary failure retains ordered source and snapshot postflight failures before cleanup`() {
        val installation = materializeInstallation()
        val sourceManifest = installation.resolve(LIFECYCLE_RELATIVE_ROOT).resolve("manifest.json")
        val snapshotPath = AtomicReference<Path>()
        val primary = DeliberateLifecycleFailure("fork failed")

        val failure = assertThrows<DeliberateLifecycleFailure> {
            withLifecycleFixture(
                requestedIncludes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE),
                installationRoot = installation,
                hooks =
                    LifecycleFixtureHooks { snapshot ->
                        snapshotPath.set(snapshot.snapshotRoot)
                        writeLaterManifest(sourceManifest, checksum = 111)
                        writeLaterManifest(snapshot.snapshotRoot.resolve("manifest.json"), checksum = 222)
                    },
            ) { throw primary }
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.suppressed.asList()).hasSize(2)
        assertThat(failure.suppressed[0])
            .hasMessageThat()
            .contains("source lifecycle workload invalid after postflight")
        assertThat(failure.suppressed[1])
            .hasMessageThat()
            .contains("snapshot lifecycle workload invalid after postflight")
        assertThat(Files.exists(requireNotNull(snapshotPath.get()))).isFalse()
    }

    @Test
    fun `successful lifecycle controller leaves no private snapshot`() {
        val installation = materializeInstallation()
        val snapshotPath = AtomicReference<Path>()

        withLifecycleFixture(
            requestedIncludes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE),
            installationRoot = installation,
            hooks = LifecycleFixtureHooks { snapshot -> snapshotPath.set(snapshot.snapshotRoot) },
        ) { capturedIdentity ->
            assertThat(requireNotNull(capturedIdentity).manifestSha256)
                .isEqualTo(LIFECYCLE_MANIFEST_SHA256)
        }

        assertThat(Files.exists(requireNotNull(snapshotPath.get()))).isFalse()
    }

    private fun materializeInstallation(): Path {
        val installation = Files.createDirectories(temporaryDirectory.resolve("installation"))
        val destination = Files.createDirectories(installation.resolve(LIFECYCLE_RELATIVE_ROOT))
        listOf("manifest.json", "collection.postman_collection.json", "handler.json").forEach { name ->
            val resource = "/workloads/v1/lifecycle.no-script-one-step.v1/$name"
            requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing resource: $resource" }
                .use { input -> Files.copy(input, destination.resolve(name)) }
        }
        return installation.toRealPath()
    }

    private fun writeLaterManifest(path: Path, checksum: Long) {
        val original = BenchmarkJson.read<WorkloadManifest>(path)
        BenchmarkJson.write(
            path,
            original.copy(expectedDigest = requireNotNull(original.expectedDigest).copy(checksum = checksum)),
        )
    }
}

private class DeliberateLifecycleFailure(message: String) : RuntimeException(message)

private val LIFECYCLE_RELATIVE_ROOT: Path =
    Path.of("workloads/v1/lifecycle.no-script-one-step.v1")
private const val LIFECYCLE_MANIFEST_SHA256: String =
    "288f95f6d9e2904cd019656b83ce915a2e23fb6f6f24391d1c596161ce71c31e"

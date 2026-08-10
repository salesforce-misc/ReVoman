/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManifestValidationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `target manifest fixture passes strict parsing and validation`() {
        val manifest = BenchmarkJson.read<TargetManifest>(targetManifestFixture())

        assertThat(manifest.targetId).isEqualTo("fixture-target")
        assertThat(manifest.classpath.map(HashedArtifact::logicalId))
            .containsExactly("adapter.jar", "target.jar")
            .inOrder()
    }

    @Test
    fun `target manifest rejects duplicate classpath logical IDs`() {
        val manifest = BenchmarkJson.read<TargetManifest>(targetManifestFixture())
        val first = manifest.classpath.first()
        val duplicate = manifest.classpath.last().copy(logicalId = first.logicalId)

        val failure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(
                temporaryDirectory.resolve("duplicate-target-manifest.json"),
                manifest.copy(classpath = listOf(first, duplicate)),
            )
        }

        assertThat(failure).hasMessageThat().contains("classpath logical IDs must be unique")
    }

    @Test
    fun `target manifest rejects a noncanonical artifact hash`() {
        val manifest = BenchmarkJson.read<TargetManifest>(targetManifestFixture())
        val invalid = manifest.classpath.first().copy(sha256 = "A".repeat(64))

        val failure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(
                temporaryDirectory.resolve("uppercase-target-manifest.json"),
                manifest.copy(classpath = listOf(invalid) + manifest.classpath.drop(1)),
            )
        }

        assertThat(failure).hasMessageThat().contains("lowercase 64-character SHA-256")
    }

    @Test
    fun `workload manifest write canonicalizes every per-mode gate list`() {
        val output = temporaryDirectory.resolve("canonical-workload.json")

        BenchmarkJson.write(output, workloadManifestWithUnorderedGates())
        val canonical = BenchmarkJson.read<WorkloadManifest>(output)

        assertThat(canonical.requiredGatesByMode.getValue(RunMode.COLD))
            .containsExactly(GateId.COLD_MEDIAN, GateId.COLD_P95)
            .inOrder()
        assertThat(canonical.requiredGatesByMode.getValue(RunMode.WARM))
            .containsExactly(GateId.WARM_MEDIAN, GateId.WARM_P95)
            .inOrder()
        assertThat(canonical.requiredGatesByMode.getValue(RunMode.RETAINED))
            .containsExactly(GateId.RETAINED_SLOPE, GateId.PER_STEP_ALLOCATION_SPREAD)
            .inOrder()
    }

    @Test
    fun `workload manifest read rejects noncanonical per-mode gate order`() {
        val output = temporaryDirectory.resolve("noncanonical-workload.json")
        BenchmarkJson.write(output, workloadManifestWithUnorderedGates())
        val noncanonical =
            Files.readString(output)
                .replace(
                    "\"COLD_MEDIAN\",\"COLD_P95\"",
                    "\"COLD_P95\",\"COLD_MEDIAN\"",
                )
        Files.writeString(output, noncanonical)

        val failure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.read<WorkloadManifest>(output)
        }

        assertThat(failure).hasMessageThat().contains("requiredGatesByMode.COLD")
        assertThat(failure).hasMessageThat().contains("unique and in enum order")
    }

    private fun targetManifestFixture(): Path =
        Path.of(
            requireNotNull(javaClass.getResource("/protocol/target-manifest-v1.json")) {
                "Missing target manifest fixture"
            }.toURI(),
        )

    private fun workloadManifestWithUnorderedGates(): WorkloadManifest {
        val artifact = BenchmarkJson.read<TargetManifest>(targetManifestFixture()).classpath.first()
        return WorkloadManifest(
            id = "simple-get",
            contractVersion = 1,
            files = listOf(artifact),
            fixtureTreeSha256 = "6".repeat(64),
            operationIds = listOf("request-1"),
            requiredGatesByMode = mapOf(
                RunMode.COLD to listOf(GateId.COLD_P95, GateId.COLD_MEDIAN, GateId.COLD_P95),
                RunMode.WARM to listOf(GateId.WARM_P95, GateId.WARM_MEDIAN),
                RunMode.RETAINED to
                    listOf(GateId.PER_STEP_ALLOCATION_SPREAD, GateId.RETAINED_SLOPE),
            ),
            expectedDigest = ExecutionDigest(checksum = 42, executedSteps = 1, failureCount = 0),
        )
    }
}

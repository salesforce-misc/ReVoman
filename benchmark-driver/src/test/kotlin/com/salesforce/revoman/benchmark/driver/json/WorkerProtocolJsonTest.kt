/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.json

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WorkerProtocolJsonTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `protocol fixture round-trips every target fork command field as canonical bytes`() {
        val command = readCommandFixture()

        assertThat(command.protocolVersion).isEqualTo(1)
        assertThat(command.verification.targetManifest).isEqualTo(targetManifestFixture().toString())
        assertThat(command.verification.targetManifestSha256)
            .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(command.verification.targetClasspathSha256)
            .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertThat(command.verification.artifactStamps).hasSize(2)
        assertThat(command.verification.artifactStamps[0].logicalId).isEqualTo("adapter.jar")
        assertThat(command.verification.artifactStamps[0].executionPath).isEqualTo("/bench/adapter.jar")
        assertThat(command.verification.artifactStamps[0].sizeBytes).isEqualTo(128L)
        assertThat(command.verification.artifactStamps[0].lastModifiedMillis).isEqualTo(1_700_000_000_000L)
        assertThat(command.verification.artifactStamps[0].fileKey).isEqualTo("adapter-key")
        assertThat(command.verification.artifactStamps[1].logicalId).isEqualTo("target.jar")
        assertThat(command.verification.artifactStamps[1].executionPath).isEqualTo("/bench/target.jar")
        assertThat(command.verification.artifactStamps[1].sizeBytes).isEqualTo(4096L)
        assertThat(command.verification.artifactStamps[1].lastModifiedMillis).isEqualTo(1_700_000_000_010L)
        assertThat(command.verification.artifactStamps[1].fileKey).isNull()
        assertThat(command.adapterId).isEqualTo("postman-v1")
        assertThat(command.mode).isEqualTo(RunMode.WARM)
        assertThat(command.metricPass).isEqualTo(MetricPass.LATENCY)
        assertThat(command.workload.id).isEqualTo("simple-get")
        assertThat(command.workload.contractVersion).isEqualTo(1)
        assertThat(command.workload.fixtureRoot).isEqualTo("/bench/fixtures")
        assertThat(command.workload.baseUrl).isEqualTo("http://127.0.0.1:8080")
        assertThat(command.workload.parameters).containsExactly("region", "us-east-1", "tenant", "acme")
        assertThat(command.expectedDigest)
            .isEqualTo(ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0))
        assertThat(command.warmupIterations).isEqualTo(2)
        assertThat(command.measurementIterations).isEqualTo(3)
        assertThat(command.resultFile).isEqualTo("/bench/result.json")

        val firstWrite = temporaryDirectory.resolve("first.json")
        val secondWrite = temporaryDirectory.resolve("second.json")
        BenchmarkJson.write(firstWrite, command)
        BenchmarkJson.write(secondWrite, BenchmarkJson.read<TargetForkCommand>(firstWrite))

        assertThat(Files.readAllBytes(secondWrite)).isEqualTo(Files.readAllBytes(firstWrite))
    }

    @Test
    fun `semantically equal parameter maps produce identical canonical command bytes`() {
        val command = readCommandFixture()
        val first = command.copy(
            workload = command.workload.copy(parameters = linkedMapOf("zeta" to "z", "alpha" to "a")),
        )
        val second = command.copy(
            workload = command.workload.copy(parameters = linkedMapOf("alpha" to "a", "zeta" to "z")),
        )
        val firstPath = temporaryDirectory.resolve("first-command.json")
        val secondPath = temporaryDirectory.resolve("second-command.json")

        BenchmarkJson.write(firstPath, first)
        BenchmarkJson.write(secondPath, second)

        assertThat(Files.readAllBytes(firstPath)).isEqualTo(Files.readAllBytes(secondPath))
    }

    @Test
    fun `protocol rejects duplicate artifact logical IDs`() {
        val command = readCommandFixture()
        val original = command.verification.artifactStamps.first()
        val duplicate = original.copy(executionPath = "/bench/adapter-copy.jar")

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(
                temporaryDirectory.resolve("duplicate-artifact.json"),
                command.copy(
                    verification = command.verification.copy(artifactStamps = listOf(original, duplicate)),
                ),
            )
        }
    }

    @Test
    fun `command serialization never rereads its mutable target manifest path`() {
        val mutableManifest = temporaryDirectory.resolve("mutable-target-manifest.json")
        Files.copy(targetManifestFixture(), mutableManifest)
        val command =
            readCommandFixture().let { original ->
                original.copy(
                    verification =
                        original.verification.copy(targetManifest = mutableManifest.toString())
                )
            }
        val beforeMutation = temporaryDirectory.resolve("before-mutation.json")
        val afterMutation = temporaryDirectory.resolve("after-mutation.json")
        BenchmarkJson.write(beforeMutation, command)
        Files.delete(mutableManifest)

        BenchmarkJson.write(afterMutation, command)

        assertThat(Files.readAllBytes(afterMutation)).isEqualTo(Files.readAllBytes(beforeMutation))
    }

    @Test
    fun `protocol rejects a result whose measurement count differs from its samples`() {
        val result = validResult().copy(measurementIterations = 2)

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(temporaryDirectory.resolve("invalid-count.json"), result)
        }
    }

    @Test
    fun `protocol rejects a negative sample latency`() {
        val result = validResult().let { valid ->
            valid.copy(samples = listOf(valid.samples.single().copy(latencyNanos = -1)))
        }

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(temporaryDirectory.resolve("negative-latency.json"), result)
        }
    }

    @Test
    fun `protocol rejects a negative sample iteration`() {
        val result = validResult().let { valid ->
            valid.copy(samples = listOf(valid.samples.single().copy(iteration = -1)))
        }

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(temporaryDirectory.resolve("negative-iteration.json"), result)
        }
    }

    @Test
    fun `protocol rejects a negative executed step count`() {
        val result = validResult().let { valid ->
            val sample = valid.samples.single()
            valid.copy(samples = listOf(sample.copy(digest = sample.digest.copy(executedSteps = -1))))
        }

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(temporaryDirectory.resolve("negative-executed-steps.json"), result)
        }
    }

    @Test
    fun `protocol rejects a negative failure count`() {
        val result = validResult().let { valid ->
            val sample = valid.samples.single()
            valid.copy(samples = listOf(sample.copy(digest = sample.digest.copy(failureCount = -1))))
        }

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(temporaryDirectory.resolve("negative-failures.json"), result)
        }
    }

    private fun readCommandFixture(): TargetForkCommand {
        val source = resourcePath("/protocol/target-command-v1.json")
        val materialized = temporaryDirectory.resolve("target-command-v1.json")
        val json = Files.readString(source).replace("__TARGET_MANIFEST__", targetManifestFixture().toString())
        Files.writeString(materialized, json)
        return BenchmarkJson.read(materialized)
    }

    private fun targetManifestFixture(): Path = resourcePath("/protocol/target-manifest-v1.json")

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing test resource: $name" }.toURI())

    private fun validResult(): TargetForkResult =
        TargetForkResult(
            processId = 42,
            warmupIterations = 1,
            measurementIterations = 1,
            samples = listOf(
                TargetSample(
                    iteration = 0,
                    latencyNanos = 100,
                    digest = ExecutionDigest(checksum = 123, executedSteps = 2, failureCount = 0),
                ),
            ),
        )
}

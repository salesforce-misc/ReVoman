/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.salesforce.revoman.benchmark.driver.fixture.DeterministicHttpFixture
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.target.baseline.Baseline083f3cd70Adapter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BaselineAdapterIntegrationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `exported baseline target executes the deterministic lifecycle workload`() {
        val materializedFixture = materializeFixture(temporaryDirectory.resolve("single"))
        val manifest =
            BenchmarkJson.read<WorkloadManifest>(materializedFixture.resolve("manifest.json"))
        DeterministicHttpFixture.verifyFixture(manifest, materializedFixture)

        val verified = verifiedTarget()
        DeterministicHttpFixture.open(manifest).use { server ->
            server.resetExecution("single")
            TargetRuntime.open(verified).use { runtime ->
                val request =
                    WorkloadRequest(
                        id = "lifecycle.no-script-one-step.v1",
                        contractVersion = 1,
                        fixtureRoot = materializedFixture.toString(),
                        baseUrl = server.baseUrl,
                    )
                val digest =
                    Baseline083f3cd70Adapter.prepare(runtime, request).use { it.execute() }
                digest.executedSteps shouldBe 1
                digest.failureCount shouldBe 0
            }
            server.requestCount("single") shouldBe 1
        }
        verified.postflight()
    }

    @Test
    fun `one prepared baseline adapter produces a stable digest across ten executions`() {
        val materializedFixture = materializeFixture(temporaryDirectory.resolve("repeated"))
        val manifest =
            BenchmarkJson.read<WorkloadManifest>(materializedFixture.resolve("manifest.json"))
        DeterministicHttpFixture.verifyFixture(manifest, materializedFixture)
        val verified = verifiedTarget()

        DeterministicHttpFixture.open(manifest).use { server ->
            TargetRuntime.open(verified).use { runtime ->
                val request =
                    WorkloadRequest(
                        id = "lifecycle.no-script-one-step.v1",
                        contractVersion = 1,
                        fixtureRoot = materializedFixture.toString(),
                        baseUrl = server.baseUrl,
                    )
                Baseline083f3cd70Adapter.prepare(runtime, request).use { prepared ->
                    val digests =
                        (1..10).map { execution ->
                            val executionId = "execution-$execution"
                            server.resetExecution(executionId)
                            prepared.execute().also {
                                server.requestCount(executionId) shouldBe 1
                            }
                        }

                    digests.shouldContainExactly(
                        List(10) {
                            ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0)
                        }
                    )
                }
            }
        }
        verified.postflight()
    }

    private fun verifiedTarget(): VerifiedTargetManifest {
        System.getProperty("revoman.benchmark.adapter") shouldBe "baseline-83f3cd70"
        val manifestPath =
            requireNotNull(System.getProperty("revoman.benchmark.targetManifest")) {
                "revoman.benchmark.targetManifest is required"
            }
        return VerifiedTargetManifest.preflight(Path.of(manifestPath))
    }

    private fun materializeFixture(destination: Path): Path {
        Files.createDirectories(destination)
        listOf("manifest.json", "collection.postman_collection.json", "handler.json").forEach {
            fileName ->
            val resource = "/workloads/v1/lifecycle.no-script-one-step.v1/$fileName"
            requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing resource: $resource" }
                .use { input -> Files.copy(input, destination.resolve(fileName)) }
        }
        return destination.toRealPath()
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun silenceTargetLogging() {
            System.setProperty("kotlin-logging.logStartupMessage", "false")
            System.setProperty("revoman.banner", "off")
        }
    }
}

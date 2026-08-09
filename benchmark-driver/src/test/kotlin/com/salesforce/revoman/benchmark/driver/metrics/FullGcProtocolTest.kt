/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RetainedCheckpoint
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FullGcProtocolTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `two distinct full GC acknowledgements are required before retained heap sample`() {
        val collections = AtomicLong(7)
        val requests = AtomicInteger()
        val protocol =
            FullGcProtocol(
                FullGcRuntime(
                    collectionCount = collections::get,
                    requestGc = {
                        requests.incrementAndGet()
                        collections.incrementAndGet()
                    },
                    usedHeapBytes = {
                        check(requests.get() == 2) { "heap sampled before two acknowledgements" }
                        45_678L
                    },
                    nanoTime = { 0L },
                    awaitPoll = {},
                ),
                Duration.ofMillis(10),
            )

        val sample = protocol.sample()

        assertThat(requests.get()).isEqualTo(2)
        assertThat(sample.completedGcCycles).isEqualTo(2)
        assertThat(sample.usedHeapBytes).isEqualTo(45_678L)
    }

    @Test
    fun `second missing GC acknowledgement invalidates retained sample`() {
        val collections = AtomicLong(3)
        val requests = AtomicInteger()
        val clock = AtomicLong()
        val protocol =
            FullGcProtocol(
                FullGcRuntime(
                    collectionCount = collections::get,
                    requestGc = {
                        if (requests.incrementAndGet() == 1) collections.incrementAndGet()
                    },
                    usedHeapBytes = { error("heap must not be sampled") },
                    nanoTime = clock::get,
                    awaitPoll = { clock.addAndGet(5) },
                ),
                Duration.ofNanos(10),
            )

        val failure = assertThrows<IllegalStateException> {
            protocol.sample()
        }

        assertThat(failure).hasMessageThat().contains("second")
    }

    @Test
    fun `unsupported GC collection count invalidates retained sample before request`() {
        val requests = AtomicInteger()
        val protocol =
            FullGcProtocol(
                FullGcRuntime(
                    collectionCount = { -1 },
                    requestGc = { requests.incrementAndGet() },
                    usedHeapBytes = { error("heap must not be sampled") },
                    nanoTime = { 0 },
                    awaitPoll = {},
                ),
                Duration.ofMillis(1),
            )

        val failure = assertThrows<IllegalStateException> {
            protocol.sample()
        }

        assertThat(failure).hasMessageThat().contains("unsupported")
        assertThat(requests.get()).isEqualTo(0)
    }

    @Test
    fun `full GC timeout is immutable provider configuration identity`() {
        val runtime =
            FullGcRuntime(
                collectionCount = { 0 },
                requestGc = {},
                usedHeapBytes = { 0 },
                nanoTime = { 0 },
                awaitPoll = {},
            )

        val short = FullGcProtocol(runtime, Duration.ofSeconds(1))
        val long = FullGcProtocol(runtime, Duration.ofSeconds(2))

        assertThat(short.configurationSha256).isNotEqualTo(long.configurationSha256)
        assertThat(short.timeoutPerCycle).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `retained protocol accepts only its exact field and mode matrix`() {
        val retained = retainedCommand()
        BenchmarkJson.write(temporaryDirectory.resolve("retained-command.json"), retained)

        listOf(
            retained.copy(mode = RunMode.COLD),
            retained.copy(metricPass = MetricPass.LATENCY),
            retained.copy(retainedExecutionCount = null),
            retained.copy(retainedExecutionCount = 0),
            retained.copy(warmupIterations = 1),
            retained.copy(measurementIterations = 1),
        ).forEachIndexed { index, invalid ->
            assertThrows<IllegalArgumentException> {
                BenchmarkJson.write(temporaryDirectory.resolve("invalid-command-$index.json"), invalid)
            }
        }

        val checkpoint =
            RetainedCheckpoint(
                executionCount = 1_000,
                usedHeapBytes = 99,
                completedGcCycles = 2,
                weakReferences = listOf(WeakReferenceOutcome("Cs1FakeExecutionToken", 1, 1)),
            )
        BenchmarkJson.write(
            temporaryDirectory.resolve("retained-result.json"),
            TargetForkResult(
                processId = 42,
                warmupIterations = 0,
                measurementIterations = 0,
                samples = emptyList(),
                retainedCheckpoint = checkpoint,
            ),
        )
        assertThrows<IllegalArgumentException> {
            BenchmarkJson.write(
                temporaryDirectory.resolve("ordinary-with-checkpoint.json"),
                TargetForkResult(
                    processId = 42,
                    warmupIterations = 0,
                    measurementIterations = 1,
                    samples = emptyList(),
                    retainedCheckpoint = checkpoint,
                ),
            )
        }
    }

    private fun retainedCommand(): TargetForkCommand =
        TargetForkCommand(
            verification =
                TargetVerificationToken(
                    targetManifest = "/bench/target.json",
                    targetManifestSha256 = "a".repeat(64),
                    targetClasspathSha256 = "b".repeat(64),
                    artifactStamps = emptyList(),
                ),
            adapterId = "baseline-83f3cd70",
            mode = RunMode.RETAINED,
            metricPass = MetricPass.RETAINED,
            workload =
                WorkloadRequest(
                    id = "lifecycle.no-script-one-step.v1",
                    contractVersion = 1,
                    fixtureRoot = "/bench/fixture",
                    baseUrl = "http://127.0.0.1:8080",
                ),
            expectedDigest = ExecutionDigest(31, 1, 0),
            warmupIterations = 0,
            measurementIterations = 0,
            resultFile = "/bench/result.json",
            retainedExecutionCount = 1_000,
        )
}

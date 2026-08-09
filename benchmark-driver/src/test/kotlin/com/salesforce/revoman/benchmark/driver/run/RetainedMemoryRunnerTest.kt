/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RetainedCheckpoint
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import com.salesforce.revoman.benchmark.driver.process.ProcessObservation
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RetainedMemoryRunnerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `retained checkpoints use independent 1k 2k 4k JVMs and preserve evidence coordinates`() {
        val target = runnerTarget(temporaryDirectory)
        val commands = mutableListOf<TargetForkCommand>()
        val runner =
            RetainedMemoryRunner(
                ProcessLauncher { javaCommand ->
                    val command = BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                    commands += command
                    val executionCount = requireNotNull(command.retainedExecutionCount)
                    val processId = 9_000L + commands.size
                    ProcessObservation(
                        exitCode = 0,
                        processId = processId,
                        elapsedNanos = 1,
                        stdoutTail = "",
                        stderrTail = "",
                        result =
                            TargetForkResult(
                                processId = processId,
                                warmupIterations = 0,
                                measurementIterations = 0,
                                samples = emptyList(),
                                retainedCheckpoint =
                                    RetainedCheckpoint(
                                        executionCount = executionCount,
                                        usedHeapBytes = executionCount * 10L,
                                        completedGcCycles = 2,
                                        weakReferences =
                                            listOf(
                                                WeakReferenceOutcome(
                                                    "Cs1FakeExecutionToken",
                                                    1,
                                                    1,
                                                )
                                            ),
                                    ),
                            ),
                    )
                }
            )

        val result =
            runner.run(
                RetainedMemoryPlan(
                    target = target,
                    targetManifestPath = targetManifestPath(target),
                    adapterId = "baseline-83f3cd70",
                    workload = workload(),
                    expectedDigest = EXPECTED_DIGEST,
                    blockId = 11,
                    targetRole = TargetRole.CANDIDATE,
                    fork = 0,
                    replicateGroup = 47,
                    timeout = Duration.ofSeconds(5),
                    loggingConfiguration = loggingConfiguration(target),
                )
            )

        assertThat(commands.map { it.retainedExecutionCount }).containsExactly(1_000, 2_000, 4_000).inOrder()
        assertThat(commands.map { it.mode }.distinct()).containsExactly(RunMode.RETAINED)
        assertThat(commands.map { it.metricPass }.distinct()).containsExactly(MetricPass.RETAINED)
        assertThat(commands.map { it.warmupIterations }.distinct()).containsExactly(0)
        assertThat(commands.map { it.measurementIterations }.distinct()).containsExactly(0)
        assertThat(result.blockId).isEqualTo(11)
        assertThat(result.targetRole).isEqualTo(TargetRole.CANDIDATE)
        assertThat(result.provider).isEqualTo("jdk-memorymxbean-two-acknowledged-full-gc/v1")
        assertThat(result.providerConfigurationSha256).hasLength(64)
        assertThat(result.observations.map { it.iteration }).containsExactly(0, 1, 2).inOrder()
        assertThat(result.observations.map { it.replicateGroup }.distinct()).containsExactly(47)
        assertThat(result.observations.map { it.processId }.distinct()).hasSize(3)
        assertThat(result.observations.map { it.retainedEvidence!!.executionCount })
            .containsExactly(1_000, 2_000, 4_000)
            .inOrder()
        assertThat(result.observations.flatMap { it.retainedEvidence!!.weakReferences }.map { it.type }.distinct())
            .containsExactly("Cs1FakeExecutionToken")
    }
}

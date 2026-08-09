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
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.process.JavaCommand
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WarmRunnerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `warm mode launches one process per fork and excludes warmups from observations`() {
        val target = runnerTarget(temporaryDirectory)
        val commands = mutableListOf<JavaCommand>()
        val workerCommands = mutableListOf<TargetForkCommand>()
        val launcher =
            ProcessLauncher { javaCommand ->
                commands += javaCommand
                val command = BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                workerCommands += command
                processObservation(command, processId = 20_000L + commands.size)
            }
        val plan = warmPlan(target, forks = 2, warmups = 3, measurements = 4, RunIntent.SMOKE)

        val observations = WarmRunner(launcher).run(plan)

        assertThat(commands).hasSize(2)
        assertThat(observations).hasSize(8)
        assertThat(observations.map { it.processId }.distinct()).hasSize(2)
        assertThat(observations.groupBy { it.fork }.keys).containsExactly(0, 1)
        observations.groupBy { it.fork }.values.forEach { forkObservations ->
            assertThat(forkObservations.map { it.iteration }).containsExactly(0, 1, 2, 3).inOrder()
            assertThat(forkObservations.map { it.value }).containsExactly(200.0, 201.0, 202.0, 203.0).inOrder()
        }
        commands.zip(workerCommands).forEach { (_, command) ->
            assertThat(command.mode).isEqualTo(RunMode.WARM)
            assertThat(command.warmupIterations).isEqualTo(3)
            assertThat(command.measurementIterations).isEqualTo(4)
        }
    }

    @Test
    fun `controlled warm block permits one fork because the campaign owns the aggregate minimum`() {
        val target = runnerTarget(temporaryDirectory)
        var launches = 0
        val runner =
            WarmRunner(
                ProcessLauncher { javaCommand ->
                    launches++
                    val command =
                        BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                    processObservation(command, processId = 30_000)
                }
            )

        val observations = runner.run(warmPlan(target, forks = 1, warmups = 0, measurements = 1))

        assertThat(observations).hasSize(1)
        assertThat(launches).isEqualTo(1)
    }

    @Test
    fun `warm fork output and duplicate process IDs invalidate the run`() {
        val outputTarget = runnerTarget(temporaryDirectory.resolve("output"))
        val outputFailure = assertThrows<IllegalStateException> {
            WarmRunner(
                ProcessLauncher { javaCommand ->
                    val command =
                        BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                    processObservation(command, processId = 31, stderr = "target log")
                }
            ).run(warmPlan(outputTarget, forks = 1, warmups = 0, measurements = 1, RunIntent.SMOKE))
        }
        assertThat(outputFailure).hasMessageThat().contains("stderr")

        val duplicateTarget = runnerTarget(temporaryDirectory.resolve("duplicate"))
        val duplicateFailure = assertThrows<IllegalStateException> {
            WarmRunner(
                ProcessLauncher { javaCommand ->
                    val command =
                        BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                    processObservation(command, processId = 41)
                }
            ).run(warmPlan(duplicateTarget, forks = 2, warmups = 0, measurements = 1, RunIntent.SMOKE))
        }
        assertThat(duplicateFailure).hasMessageThat().contains("distinct process")
    }
}

private fun warmPlan(
    target: com.salesforce.revoman.benchmark.driver.model.TargetManifest,
    forks: Int,
    warmups: Int,
    measurements: Int,
    intent: RunIntent = RunIntent.CONTROLLED,
): WarmPlan =
    WarmPlan(
        intent = intent,
        target = target,
        targetManifestPath = targetManifestPath(target),
        adapterId = "baseline-83f3cd70",
        workload = workload(),
        forksPerBlock = forks,
        warmupIterations = warmups,
        measurementIterations = measurements,
        metricPass = MetricPass.LATENCY,
        timeout = Duration.ofSeconds(5),
        loggingConfiguration = loggingConfigurationPath(target),
    )

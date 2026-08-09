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
import java.net.URI
import java.nio.file.Files
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
    fun `warm macro run rejects an absent digest oracle before launch`() {
        val target = runnerTarget(temporaryDirectory)
        var launches = 0
        val runner = WarmRunner(ProcessLauncher { error("unexpected launch ${++launches}") })

        val failure = assertThrows<IllegalArgumentException> {
            runner.run(warmPlan(target, 1, 0, 1, RunIntent.SMOKE).copy(expectedDigest = null))
        }

        assertThat(failure).hasMessageThat().contains("expectedDigest")
        assertThat(launches).isEqualTo(0)
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

    @Test
    fun `warm parent rejects every measured digest field mismatch`() {
        val mismatches =
            listOf(
                "checksum" to EXPECTED_DIGEST.copy(checksum = 32),
                "executedSteps" to EXPECTED_DIGEST.copy(executedSteps = 2),
                "failureCount" to EXPECTED_DIGEST.copy(failureCount = 1),
            )

        mismatches.forEachIndexed { index, (field, digest) ->
            val target = runnerTarget(temporaryDirectory.resolve("warm-digest-$index"))
            val runner =
                WarmRunner(
                    ProcessLauncher { command ->
                        val worker =
                            BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                        processObservation(worker, processId = 700L + index, digest = digest)
                    }
                )

            val failure = assertThrows<IllegalStateException> {
                runner.run(warmPlan(target, 1, 0, 1, RunIntent.SMOKE))
            }

            assertThat(failure).hasMessageThat().contains("digest")
            assertThat(failure).hasMessageThat().contains(field)
        }
    }

    @Test
    fun `quiet logging source mutation between warm forks invalidates all observations`() {
        val target = runnerTarget(temporaryDirectory)
        val source = loggingConfigurationPath(target)
        val snapshotPaths = mutableListOf<Path>()
        val snapshotBytes = mutableListOf<ByteArray>()
        var launches = 0
        val runner =
            WarmRunner(
                ProcessLauncher { javaCommand ->
                    launches++
                    val snapshot =
                        Path.of(
                            URI(
                                javaCommand.jvmArgs
                                    .single { it.startsWith("-Dlog4j2.configurationFile=") }
                                    .substringAfter('=')
                            )
                        )
                    snapshotPaths.add(snapshot)
                    snapshotBytes.add(Files.readAllBytes(snapshot))
                    if (launches == 1) {
                        Files.writeString(
                            source,
                            "<Configuration status=\"OFF\"><Appenders/><Loggers><Root level=\"OFF\"/></Loggers></Configuration>",
                        )
                    }
                    val command =
                        BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                    processObservation(command, processId = 800L + launches)
                }
            )

        val failure = assertThrows<IllegalStateException> {
            runner.run(warmPlan(target, 2, 0, 1, RunIntent.SMOKE))
        }

        assertThat(launches).isEqualTo(2)
        assertThat(snapshotPaths.distinct()).hasSize(1)
        assertThat(snapshotPaths.first()).isNotEqualTo(source)
        assertThat(snapshotBytes[1]).isEqualTo(snapshotBytes[0])
        assertThat(failure).hasMessageThat().contains("Logging configuration")
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
        expectedDigest = EXPECTED_DIGEST,
        forksPerBlock = forks,
        warmupIterations = warmups,
        measurementIterations = measurements,
        metricPass = MetricPass.LATENCY,
        timeout = Duration.ofSeconds(5),
        loggingConfiguration = loggingConfiguration(target),
    )

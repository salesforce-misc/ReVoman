/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.metrics.PeakRssProvider
import com.salesforce.revoman.benchmark.driver.process.JavaCommand
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import com.salesforce.revoman.benchmark.driver.process.ProcessObservation
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ColdRunnerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `standalone smoke cold samples request distinct processes`() {
        val target = runnerTarget(temporaryDirectory)
        val commands = mutableListOf<JavaCommand>()
        val workerCommands = mutableListOf<TargetForkCommand>()
        val loggingSnapshots = mutableListOf<Pair<Path, ByteArray>>()
        val launcher =
            ProcessLauncher { javaCommand ->
                commands += javaCommand
                val command = BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                workerCommands += command
                val loggingSnapshot = loggingSnapshot(javaCommand)
                loggingSnapshots += loggingSnapshot to Files.readAllBytes(loggingSnapshot)
                val processId = 10_000L + commands.size
                processObservation(command, processId, elapsedNanos = processId * 10)
            }
        val plan = coldPlan(target, sampleCount = 50, intent = RunIntent.SMOKE)

        val observations = ColdRunner(launcher).run(plan)

        assertThat(observations).hasSize(50)
        assertThat(observations.map { it.processId }.distinct()).hasSize(50)
        assertThat(observations.map { it.value })
            .containsExactlyElementsIn((1..50).map { (10_000L + it) * 10.0 })
            .inOrder()
        assertThat(commands).hasSize(50)
        commands.zip(workerCommands).forEach { (javaCommand, command) ->
            assertThat(command.mode).isEqualTo(RunMode.COLD)
            assertThat(command.adapterId).isEqualTo("baseline-83f3cd70")
            assertThat(command.workload).isEqualTo(workload())
            assertThat(command.expectedDigest).isEqualTo(EXPECTED_DIGEST)
            assertThat(command.warmupIterations).isEqualTo(0)
            assertThat(command.measurementIterations).isEqualTo(1)
            assertThat(command.verification.targetManifest).isEqualTo(targetManifestPath(target).toString())
            assertThat(javaCommand.jvmArgs).contains("-Drevoman.banner=off")
            assertThat(javaCommand.jvmArgs).contains("-Dkotlin-logging.logStartupMessage=false")
            val snapshotUri = loggingSnapshot(javaCommand).toUri()
            assertThat(javaCommand.jvmArgs).contains("-Dlog4j2.configurationFile=$snapshotUri")
            assertThat(javaCommand.jvmArgs).contains("-Dlog4j2.*.Configuration.file=$snapshotUri")
        }
        assertThat(loggingSnapshots.map { it.first }.distinct()).hasSize(1)
        assertThat(loggingSnapshots.first().first).isNotEqualTo(loggingConfigurationPath(target))
        assertThat(plan.loggingConfiguration.sha256)
            .isEqualTo(ContentHasher.sha256(loggingConfigurationPath(target)))
        loggingSnapshots.forEach { (_, bytes) ->
            assertThat(bytes).isEqualTo(Files.readAllBytes(loggingConfigurationPath(target)))
        }
    }

    @Test
    fun `controlled cold position launches exactly one process and preserves coordinates`() {
        val target = runnerTarget(temporaryDirectory)
        val commands = mutableListOf<JavaCommand>()
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    commands += command
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    processObservation(worker, processId = 77)
                }
            )

        val result =
            runner.runWithEvidence(
                coldPlan(target, sampleCount = 1, intent = RunIntent.CONTROLLED).copy(
                    position = ColdPosition(9, TargetRole.CANDIDATE, 4)
                )
            )

        assertThat(commands).hasSize(1)
        assertThat(result.position).isEqualTo(ColdPosition(9, TargetRole.CANDIDATE, 4))
        assertThat(result.observations.single().fork).isEqualTo(4)
    }

    @Test
    fun `controlled cold plan rejects absent scheduler position before launch`() {
        val target = runnerTarget(temporaryDirectory)
        var launches = 0
        val runner = ColdRunner(ProcessLauncher { error("unexpected launch ${++launches}") })

        val failure = assertThrows<IllegalArgumentException> {
            runner.run(coldPlan(target, sampleCount = 1, intent = RunIntent.CONTROLLED))
        }

        assertThat(failure).hasMessageThat().contains("position")
        assertThat(launches).isEqualTo(0)
    }

    @Test
    fun `nonzero exit invalidates cold run and postflight failure is suppressed`() {
        val target = runnerTarget(temporaryDirectory)
        val artifact = Path.of(target.classpath.single().executionPath)
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    Files.writeString(artifact, "changed-target")
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    processObservation(worker, processId = 72, exitCode = 9)
                }
            )

        val failure = assertThrows<IllegalStateException> {
            runner.run(coldPlan(target, sampleCount = 1, intent = RunIntent.SMOKE))
        }

        assertThat(failure).hasMessageThat().contains("exit code 9")
        assertThat(failure.suppressed).hasLength(1)
        assertThat(failure.suppressed.single())
            .hasMessageThat()
            .contains("Target campaign invalid after postflight")
    }

    @Test
    fun `successful observations are discarded when controller postflight fails`() {
        val target = runnerTarget(temporaryDirectory)
        val artifact = Path.of(target.classpath.single().executionPath)
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    Files.writeString(artifact, "changed-target")
                    processObservation(worker, processId = 73)
                }
            )

        val failure = assertThrows<IllegalStateException> {
            runner.run(coldPlan(target, sampleCount = 1, intent = RunIntent.SMOKE))
        }

        assertThat(failure).hasMessageThat().contains("Target campaign invalid after postflight")
    }

    @Test
    fun `successful observations are discarded after same-classpath manifest replacement`() {
        val target = runnerTarget(temporaryDirectory)
        val manifestPath = targetManifestPath(target)
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    BenchmarkJson.write(
                        manifestPath,
                        target.copy(targetId = "same-classpath-replacement"),
                    )
                    processObservation(worker, processId = 74)
                }
            )

        val failure = assertThrows<IllegalStateException> {
            runner.run(coldPlan(target, sampleCount = 1, intent = RunIntent.SMOKE))
        }

        assertThat(failure).hasMessageThat().contains("target manifest SHA-256 changed")
    }

    @Test
    fun `plan target must exactly match its explicit manifest before any launch`() {
        val target = runnerTarget(temporaryDirectory)
        var launches = 0
        val runner = ColdRunner(ProcessLauncher { error("unexpected launch ${++launches}") })

        val failure = assertThrows<IllegalArgumentException> {
            runner.run(
                coldPlan(target.copy(targetId = "different-target"), 1, RunIntent.SMOKE).copy(
                    targetManifestPath = targetManifestPath(target),
                    loggingConfiguration = loggingConfiguration(target),
                )
            )
        }

        assertThat(failure).hasMessageThat().contains("expected target")
        assertThat(launches).isEqualTo(0)
    }

    @Test
    fun `unexpected child stdout invalidates a cold sample`() {
        val target = runnerTarget(temporaryDirectory)
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    processObservation(worker, processId = 81, stdout = "unexpected output")
                }
            )

        val failure = assertThrows<IllegalStateException> {
            runner.run(coldPlan(target, sampleCount = 1, intent = RunIntent.SMOKE))
        }

        assertThat(failure).hasMessageThat().contains("stdout")
        assertThat(failure).hasMessageThat().contains("unexpected output")
    }

    @Test
    fun `timeout malformed result and empty result failures invalidate cold runs`() {
        listOf("timeout", "malformed result", "empty result").forEach { reason ->
            val caseRoot = temporaryDirectory.resolve(reason.replace(' ', '-'))
            Files.createDirectories(caseRoot)
            val target = runnerTarget(caseRoot)
            val runner = ColdRunner(ProcessLauncher { throw IllegalStateException(reason) })

            val failure = assertThrows<IllegalStateException> {
                runner.run(coldPlan(target, sampleCount = 1, intent = RunIntent.SMOKE))
            }

            assertThat(failure).hasMessageThat().contains(reason)
        }
    }

    @Test
    fun `cold parent rejects every measured digest field mismatch`() {
        val mismatches =
            listOf(
                "checksum" to EXPECTED_DIGEST.copy(checksum = 32),
                "executedSteps" to EXPECTED_DIGEST.copy(executedSteps = 2),
                "failureCount" to EXPECTED_DIGEST.copy(failureCount = 1),
            )

        mismatches.forEachIndexed { index, (field, digest) ->
            val target = runnerTarget(temporaryDirectory.resolve("cold-digest-$index"))
            val runner =
                ColdRunner(
                    ProcessLauncher { command ->
                        val worker =
                            BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                        processObservation(worker, processId = 500L + index, digest = digest)
                    }
                )

            val failure = assertThrows<IllegalStateException> {
                runner.run(coldPlan(target, 1, RunIntent.SMOKE))
            }

            assertThat(failure).hasMessageThat().contains("digest")
            assertThat(failure).hasMessageThat().contains(field)
        }
    }

    @Test
    fun `macro run rejects an absent digest oracle for every intent before launch`() {
        val target = runnerTarget(temporaryDirectory)
        var launches = 0
        val runner = ColdRunner(ProcessLauncher { error("unexpected launch ${++launches}") })

        RunIntent.entries.forEach { intent ->
            val failure = assertThrows<IllegalArgumentException> {
                runner.run(
                    coldPlan(target, 1, intent).copy(
                        expectedDigest = null,
                        position =
                            if (intent == RunIntent.CONTROLLED) {
                                ColdPosition(0, TargetRole.BASELINE, 0)
                            } else {
                                null
                            },
                    )
                )
            }

            assertThat(failure).hasMessageThat().contains("expectedDigest")
        }
        assertThat(launches).isEqualTo(0)
    }

    @Test
    fun `macro plan rejects an oracle that permits execution failures before launch`() {
        val target = runnerTarget(temporaryDirectory)
        var launches = 0
        val runner =
            ColdRunner(
                ProcessLauncher { error("unexpected launch ${++launches}") }
            )

        val failure = assertThrows<IllegalArgumentException> {
            runner.run(
                coldPlan(target, 1, RunIntent.SMOKE).copy(
                    expectedDigest = EXPECTED_DIGEST.copy(failureCount = 1)
                )
            )
        }

        assertThat(failure).hasMessageThat().contains("failureCount")
        assertThat(launches).isEqualTo(0)
    }

    @Test
    fun `recursive fork cleanup preserves the primary launcher failure`() {
        val target = runnerTarget(temporaryDirectory)
        val primary = DeliberateLaunchFailure("launch failed")
        var invocationDirectory: Path? = null
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    val directory = Path.of(command.programArgs.single()).parent
                    invocationDirectory = directory
                    Files.writeString(directory.resolve(".result.json.worker.tmp"), "partial")
                    throw primary
                }
            )

        val failure = assertThrows<DeliberateLaunchFailure> {
            runner.run(coldPlan(target, 1, RunIntent.SMOKE))
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(Files.exists(requireNotNull(invocationDirectory))).isFalse()
    }

    @Test
    fun `peak RSS identity changes with verified logging configuration`() {
        val firstTarget = runnerTarget(temporaryDirectory.resolve("rss-first"))
        val secondTarget = runnerTarget(temporaryDirectory.resolve("rss-second"))
        Files.writeString(
            loggingConfigurationPath(secondTarget),
            "<Configuration status=\"OFF\"><Loggers><Root level=\"ERROR\"/></Loggers></Configuration>",
        )
        val provider = fixedPeakRssProvider()
        var processId = 900L
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    processObservation(worker, ++processId)
                }
            )

        val first =
            runner.runWithEvidence(peakRssPlan(firstTarget, provider, blockId = 1))
        val second =
            runner.runWithEvidence(peakRssPlan(secondTarget, provider, blockId = 2))

        assertThat(first.providerConfigurationSha256)
            .isNotEqualTo(second.providerConfigurationSha256)
    }

    @Test
    fun `peak RSS provider deletion failure is suppressed behind parse failure`() {
        val target = runnerTarget(temporaryDirectory.resolve("rss-delete"))
        val primary = DeliberateLaunchFailure("parse failed")
        var capturedProviderOutput: Path? = null
        val provider =
            object : PeakRssProvider {
                override val id: String = "test-peak-rss/v1"
                override val configurationSha256: String = "c".repeat(64)

                override fun invocationPrefix(providerOutput: Path): List<String> {
                    capturedProviderOutput = providerOutput
                    return emptyList()
                }

                override fun parse(providerOutput: Path): Long {
                    Files.createDirectory(providerOutput)
                    Files.writeString(providerOutput.resolve("retained-child"), "diagnostic")
                    throw primary
                }
            }
        val runner =
            ColdRunner(
                ProcessLauncher { command ->
                    val worker = BenchmarkJson.read<TargetForkCommand>(Path.of(command.programArgs.single()))
                    processObservation(worker, 991)
                }
            )

        val failure = assertThrows<DeliberateLaunchFailure> {
            runner.runWithEvidence(peakRssPlan(target, provider, blockId = 3))
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.suppressed).hasLength(1)
        requireNotNull(capturedProviderOutput).let { output ->
            Files.delete(output.resolve("retained-child"))
            Files.delete(output)
        }
    }
}

private class DeliberateLaunchFailure(message: String) : RuntimeException(message)

internal fun runnerTarget(root: Path): TargetManifest {
    Files.createDirectories(root)
    val artifact = root.resolve("target.jar")
    Files.writeString(artifact, "stable-target")
    val canonicalArtifact = artifact.toRealPath()
    val target =
        TargetManifest(
            targetId = "test-target",
            gitCommit = "test-commit",
            gitTree = "test-tree",
            dirty = false,
            gradleVersion = "test-gradle",
            wrapperSha256 = "0".repeat(64),
            jdk =
                JdkIdentity(
                    distribution = "test-jdk",
                    vendor = "test-vendor",
                    fullVersion = "21",
                    javaHome = System.getProperty("java.home"),
                    jvmFlags = emptyList(),
                ),
            classpath =
                listOf(
                    HashedArtifact(
                        logicalId = "target.jar",
                        executionPath = canonicalArtifact.toString(),
                        sizeBytes = Files.size(canonicalArtifact),
                        sha256 = ContentHasher.sha256(canonicalArtifact),
                    )
                ),
        )
    val manifest = root.resolve("target-manifest.json")
    BenchmarkJson.write(manifest, target)
    val logging = root.resolve("log4j2-benchmark.xml")
    Files.writeString(logging, "<Configuration status=\"OFF\"><Loggers><Root level=\"OFF\"/></Loggers></Configuration>")
    return target
}

internal fun targetManifestPath(target: TargetManifest): Path =
    Path.of(target.classpath.single().executionPath).parent.resolve("target-manifest.json").toRealPath()

internal fun loggingConfigurationPath(target: TargetManifest): Path =
    Path.of(target.classpath.single().executionPath).parent.resolve("log4j2-benchmark.xml").toRealPath()

internal fun loggingConfiguration(target: TargetManifest): VerifiedLoggingConfiguration =
    VerifiedLoggingConfiguration.preflight(loggingConfigurationPath(target))

internal fun coldPlan(
    target: TargetManifest,
    sampleCount: Int,
    intent: RunIntent = RunIntent.SMOKE,
): ColdPlan =
    ColdPlan(
        intent = intent,
        target = target,
        targetManifestPath = targetManifestPath(target),
        adapterId = "baseline-83f3cd70",
        workload = workload(),
        expectedDigest = EXPECTED_DIGEST,
        sampleCount = sampleCount,
        metricPass = MetricPass.LATENCY,
        timeout = Duration.ofSeconds(5),
        loggingConfiguration = loggingConfiguration(target),
    )

internal fun workload(): WorkloadRequest =
    WorkloadRequest(
        id = "lifecycle.no-script-one-step.v1",
        contractVersion = 1,
        fixtureRoot = "/fixture",
        baseUrl = "http://127.0.0.1:12345",
    )

internal fun processObservation(
    command: TargetForkCommand,
    processId: Long,
    elapsedNanos: Long = 1_000,
    exitCode: Int = 0,
    stdout: String = "",
    stderr: String = "",
    digest: ExecutionDigest = command.expectedDigest ?: EXPECTED_DIGEST,
): ProcessObservation =
    ProcessObservation(
        exitCode = exitCode,
        processId = processId,
        elapsedNanos = elapsedNanos,
        stdoutTail = stdout,
        stderrTail = stderr,
        result =
            TargetForkResult(
                processId = processId,
                warmupIterations = command.warmupIterations,
                measurementIterations = command.measurementIterations,
                samples =
                    List(command.measurementIterations) { iteration ->
                        TargetSample(
                            iteration = iteration,
                            latencyNanos = 200L + iteration,
                            digest = digest,
                        )
                    },
            ),
    )

internal val EXPECTED_DIGEST: ExecutionDigest =
    ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0)

private fun ColdRunnerTest.fixedPeakRssProvider(): PeakRssProvider =
    object : PeakRssProvider {
        override val id: String = "test-peak-rss/v1"
        override val configurationSha256: String = "d".repeat(64)
        override fun invocationPrefix(providerOutput: Path): List<String> = emptyList()
        override fun parse(providerOutput: Path): Long = 1_024
    }

private fun ColdRunnerTest.peakRssPlan(
    target: TargetManifest,
    provider: PeakRssProvider,
    blockId: Int,
): ColdPlan =
    coldPlan(target, 1).copy(
        metricPass = MetricPass.PEAK_RSS,
        artifactDirectory =
            Files.createDirectories(temporaryDirectory.resolve("rss-artifacts-$blockId")).toRealPath(),
        peakRssProvider = provider,
        position = ColdPosition(blockId, TargetRole.BASELINE, 0),
    )

private fun loggingSnapshot(command: JavaCommand): Path {
    val value =
        command.jvmArgs.single { it.startsWith("-Dlog4j2.configurationFile=") }.substringAfter('=')
    return Path.of(URI(value))
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import com.salesforce.revoman.benchmark.driver.run.ColdRunner
import com.salesforce.revoman.benchmark.driver.run.ColdPosition
import com.salesforce.revoman.benchmark.driver.run.coldPlan
import com.salesforce.revoman.benchmark.driver.run.processObservation
import com.salesforce.revoman.benchmark.driver.run.runnerTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class JfrAllocationReaderTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `JFR sums in TLAB reservations and outside TLAB allocation sizes`() {
        val fixture = fixtureFiles()
        val reader = reader(
            JfrAllocationEvent(
                name = "jdk.ObjectAllocationInNewTLAB",
                fields = mapOf("tlabSize" to 4_096L, "allocationSize" to 17L),
            ),
            JfrAllocationEvent(
                name = "jdk.ObjectAllocationOutsideTLAB",
                fields = mapOf("allocationSize" to 3_000L, "tlabSize" to 19L),
            ),
            JfrAllocationEvent(name = "jdk.GarbageCollection", fields = emptyMap()),
        )

        val measurement = reader.read(
            recordingFile = fixture.recording,
            configurationFile = fixture.configuration,
            resultConfigurationSha256 = ContentHasher.sha256(fixture.configuration),
        )

        assertThat(measurement.provider).isEqualTo("jdk21-jfr-tlab-reserved-plus-outside/v1")
        assertThat(measurement.recordingConfigurationSha256)
            .isEqualTo(ContentHasher.sha256(fixture.configuration))
        assertThat(measurement.allocatedBytes).isEqualTo(7_096L)
    }

    @Test
    fun `JFR rejects missing allocation events and exact event fields`() {
        val fixture = fixtureFiles()

        val missingEvents = assertThrows<IllegalArgumentException> {
            reader(JfrAllocationEvent("jdk.GarbageCollection", emptyMap())).read(
                fixture.recording,
                fixture.configuration,
                ContentHasher.sha256(fixture.configuration),
            )
        }
        assertThat(missingEvents).hasMessageThat().contains("allocation event")

        val missingField = assertThrows<IllegalArgumentException> {
            reader(
                JfrAllocationEvent(
                    "jdk.ObjectAllocationInNewTLAB",
                    mapOf("allocationSize" to 128L),
                )
            ).read(
                fixture.recording,
                fixture.configuration,
                ContentHasher.sha256(fixture.configuration),
            )
        }
        assertThat(missingField).hasMessageThat().contains("tlabSize")
    }

    @Test
    fun `JFR rejects checked long overflow and configuration metadata mismatch`() {
        val fixture = fixtureFiles()
        val overflow = assertThrows<IllegalArgumentException> {
            reader(
                JfrAllocationEvent(
                    "jdk.ObjectAllocationInNewTLAB",
                    mapOf("tlabSize" to Long.MAX_VALUE),
                ),
                JfrAllocationEvent(
                    "jdk.ObjectAllocationOutsideTLAB",
                    mapOf("allocationSize" to 1L),
                ),
            ).read(
                fixture.recording,
                fixture.configuration,
                ContentHasher.sha256(fixture.configuration),
            )
        }
        assertThat(overflow).hasMessageThat().contains("overflow")

        val mismatch = assertThrows<IllegalArgumentException> {
            reader(
                JfrAllocationEvent(
                    "jdk.ObjectAllocationInNewTLAB",
                    mapOf("tlabSize" to 1L),
                )
            ).read(fixture.recording, fixture.configuration, "0".repeat(64))
        }
        assertThat(mismatch).hasMessageThat().contains("configuration SHA-256")
    }

    @Test
    fun `cold latency and JFR allocation use distinct child processes`() {
        val target = runnerTarget(temporaryDirectory.resolve("target"))
        val configuration = temporaryDirectory.resolve("revoman-allocation-v1.jfc")
        val artifacts = Files.createDirectories(temporaryDirectory.resolve("artifacts")).toRealPath()
        Files.writeString(configuration, "allocation configuration")
        val commands = mutableListOf<TargetForkCommand>()
        val launcher =
            ProcessLauncher { javaCommand ->
                val command = BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                commands += command
                command.jfrRecordingFile?.let { Files.writeString(Path.of(it), "recording") }
                val processId = 5_000L + commands.size
                processObservation(command, processId).let { observation ->
                    observation.copy(
                        result =
                            observation.result.copy(
                                jfrConfigurationSha256 =
                                    command.jfrConfigurationFile?.let { ContentHasher.sha256(Path.of(it)) }
                            )
                    )
                }
            }
        val reader =
            reader(
                JfrAllocationEvent(
                    "jdk.ObjectAllocationInNewTLAB",
                    mapOf("tlabSize" to 128L),
                )
            )
        val runner = ColdRunner(launcher, reader)

        val latency = runner.run(coldPlan(target, 1, RunIntent.SMOKE))
        val allocation =
            runner.runWithEvidence(
                coldPlan(target, 1, RunIntent.SMOKE).copy(
                    metricPass = MetricPass.ALLOCATION,
                    artifactDirectory = artifacts,
                    jfrConfigurationFile = configuration.toRealPath(),
                    position = ColdPosition(3, TargetRole.BASELINE, 0),
                )
            )
        val secondAllocation =
            runner.runWithEvidence(
                coldPlan(target, 1, RunIntent.SMOKE).copy(
                    metricPass = MetricPass.ALLOCATION,
                    artifactDirectory = artifacts,
                    jfrConfigurationFile = configuration.toRealPath(),
                    position = ColdPosition(4, TargetRole.CANDIDATE, 0),
                )
            )

        assertThat(commands).hasSize(3)
        assertThat(commands[0].jfrConfigurationFile).isNull()
        assertThat(commands[0].jfrRecordingFile).isNull()
        assertThat(commands[1].jfrConfigurationFile).isNotEqualTo(configuration.toRealPath().toString())
        assertThat(commands[1].jfrRecordingFile).isNotNull()
        assertThat(latency.single().processId)
            .isNotEqualTo(allocation.observations.single().processId)
        assertThat(allocation.observations.single().value).isEqualTo(128.0)
        assertThat(secondAllocation.providerConfigurationSha256)
            .isEqualTo(allocation.providerConfigurationSha256)
        assertThat(secondAllocation.artifacts.single().logicalId)
            .isEqualTo("cold-allocation-block-4-role-candidate-fork-0.jfr")
        assertThat(Files.list(artifacts).use { paths -> paths.count() }).isEqualTo(2)
    }

    @Test
    fun `cold allocation uses one JFR snapshot and source mutation fails postflight`() {
        val target = runnerTarget(temporaryDirectory.resolve("snapshot-target"))
        val source = temporaryDirectory.resolve("mutable-allocation.jfc")
        val artifacts = Files.createDirectories(temporaryDirectory.resolve("snapshot-artifacts")).toRealPath()
        Files.writeString(source, "stable allocation configuration")
        var snapshot: Path? = null
        val launcher =
            ProcessLauncher { javaCommand ->
                val command = BenchmarkJson.read<TargetForkCommand>(Path.of(javaCommand.programArgs.single()))
                val configuration = Path.of(requireNotNull(command.jfrConfigurationFile))
                snapshot = configuration
                val configurationHash = ContentHasher.sha256(configuration)
                Files.writeString(Path.of(requireNotNull(command.jfrRecordingFile)), "recording")
                Files.writeString(source, "mutated allocation configuration")
                processObservation(command, 6_001).let { observation ->
                    observation.copy(
                        result = observation.result.copy(jfrConfigurationSha256 = configurationHash)
                    )
                }
            }
        val runner =
            ColdRunner(
                launcher,
                reader(
                    JfrAllocationEvent(
                        "jdk.ObjectAllocationInNewTLAB",
                        mapOf("tlabSize" to 64L),
                    )
                ),
            )

        val failure = assertThrows<IllegalStateException> {
            runner.runWithEvidence(
                coldPlan(target, 1).copy(
                    metricPass = MetricPass.ALLOCATION,
                    artifactDirectory = artifacts,
                    jfrConfigurationFile = source.toRealPath(),
                    position = ColdPosition(8, TargetRole.BASELINE, 0),
                )
            )
        }

        assertThat(requireNotNull(snapshot)).isNotEqualTo(source)
        assertThat(failure).hasMessageThat().contains("JFR configuration invalid after postflight")
        assertThat(Files.exists(requireNotNull(snapshot))).isFalse()
    }

    private fun reader(vararg events: JfrAllocationEvent): JfrAllocationReader =
        JfrAllocationReader(
            JfrEventSource { _, consumer -> events.forEach(consumer) }
        )

    private fun fixtureFiles(): FixtureFiles {
        val recording = temporaryDirectory.resolve("allocation.jfr")
        val configuration = temporaryDirectory.resolve("allocation.jfc")
        Files.writeString(recording, "synthetic recording boundary")
        Files.writeString(configuration, "synthetic configuration")
        return FixtureFiles(recording, configuration)
    }

    private data class FixtureFiles(val recording: Path, val configuration: Path)
}

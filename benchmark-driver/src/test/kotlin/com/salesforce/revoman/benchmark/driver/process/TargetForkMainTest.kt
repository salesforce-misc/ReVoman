/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.target.FakeTargetJarBuilder
import com.salesforce.revoman.benchmark.driver.target.MajorDiagnosticsFixture
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TargetForkMainTest {
    @TempDir lateinit var temporaryDirectory: Path

    @AfterEach
    fun clearDiagnosticsProperty() {
        System.clearProperty(DIAGNOSTICS_PROPERTY)
    }

    @Test
    fun `major retained worker enables diagnostics then restores before result publication`() {
        System.setProperty(DIAGNOSTICS_PROPERTY, "previous")
        val effects = RecordingTargetForkEffects()

        runTargetFork(arrayOf(commandPath().toString()), effects)

        assertThat(effects.events)
            .containsExactly(
                "get:previous",
                "set:weak-references-v1",
                "set:previous",
                "publish:previous",
            )
            .inOrder()
        assertThat(effects.published).hasSize(1)
        assertThat(System.getProperty(DIAGNOSTICS_PROPERTY)).isEqualTo("previous")
    }

    @Test
    fun `restore failure prevents publication after a successful worker body`() {
        val restoreFailure = DeliberateTargetForkFailure("restore failed")
        val effects = RecordingTargetForkEffects(clearFailure = restoreFailure)

        val failure =
            assertThrows<DeliberateTargetForkFailure> {
                runTargetFork(arrayOf(commandPath().toString()), effects)
            }

        assertThat(failure).isSameInstanceAs(restoreFailure)
        assertThat(effects.published).isEmpty()
        assertThat(effects.events)
            .containsExactly("get:null", "set:weak-references-v1", "clear")
            .inOrder()
    }

    @Test
    fun `body failure remains primary and directly suppresses restore failure without publishing`() {
        val restoreFailure = DeliberateTargetForkFailure("restore failed")
        val effects = RecordingTargetForkEffects(clearFailure = restoreFailure)

        val failure =
            assertThrows<IllegalStateException> {
                runTargetFork(
                    arrayOf(commandPath(expected = ExecutionDigest(32, 1, 0)).toString()),
                    effects,
                )
            }

        assertThat(failure).hasMessageThat().contains("checksum mismatch")
        assertThat(failure.suppressed.asList()).containsExactly(restoreFailure)
        assertThat(effects.published).isEmpty()
    }

    @Test
    fun `cleanup preserves body primary and directly suppresses every ordered failure`() {
        val body = DeliberateTargetForkFailure("body")
        val prepared = DeliberateTargetForkFailure("prepared")
        val runtime = DeliberateTargetForkFailure("runtime")
        val recording = DeliberateTargetForkFailure("recording")
        val restore = DeliberateTargetForkFailure("restore")
        val events = mutableListOf<String>()

        val failure =
            finishTargetFork(
                prepared = failingCloseable("prepared", prepared, events),
                runtime = failingCloseable("runtime", runtime, events),
                recording = failingCloseable("recording", recording, events),
                restoreProperty = {
                    events += "restore"
                    throw restore
                },
                primary = body,
            )

        assertThat(failure).isSameInstanceAs(body)
        assertThat(requireNotNull(failure).suppressed.asList())
            .containsExactly(prepared, runtime, recording, restore)
            .inOrder()
        assertThat(events).containsExactly("prepared", "runtime", "recording", "restore").inOrder()
    }

    private fun commandPath(
        expected: ExecutionDigest = ExecutionDigest(31, 1, 0)
    ): Path {
        val builder =
            FakeTargetJarBuilder(
                Files.createDirectories(temporaryDirectory.resolve("target-${expected.checksum}"))
            )
        val target = builder.majorJar(MajorDiagnosticsFixture.VALID)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(target))
        val fixture = Files.createDirectories(temporaryDirectory.resolve("fixture-${expected.checksum}"))
        Files.writeString(fixture.resolve("collection.postman_collection.json"), "{}")
        val commandPath = temporaryDirectory.resolve("command-${expected.checksum}.json")
        BenchmarkJson.write(
            commandPath,
            TargetForkCommand(
                verification = verified.verificationToken(),
                adapterId = "major-v1",
                mode = RunMode.RETAINED,
                metricPass = MetricPass.RETAINED,
                workload =
                    WorkloadRequest(
                        id = "lifecycle.no-script-one-step.v1",
                        contractVersion = 1,
                        fixtureRoot = fixture.toRealPath().toString(),
                        baseUrl = "http://127.0.0.1:8080",
                    ),
                expectedDigest = expected,
                warmupIterations = 0,
                measurementIterations = 0,
                resultFile = temporaryDirectory.resolve("result-${expected.checksum}.json").toString(),
                retainedExecutionCount = 1,
            ),
        )
        return commandPath
    }

    private fun failingCloseable(
        name: String,
        failure: Throwable,
        events: MutableList<String>,
    ): AutoCloseable = AutoCloseable {
        events += name
        throw failure
    }

    private class RecordingTargetForkEffects(
        private val clearFailure: Throwable? = null,
    ) : TargetForkEffects {
        val events = mutableListOf<String>()
        val published = mutableListOf<TargetForkResult>()

        override fun getProperty(key: String): String? =
            System.getProperty(key).also { events += "get:${it ?: "null"}" }

        override fun setProperty(key: String, value: String) {
            events += "set:$value"
            System.setProperty(key, value)
        }

        override fun clearProperty(key: String) {
            events += "clear"
            clearFailure?.let { throw it }
            System.clearProperty(key)
        }

        override fun publish(path: Path, result: TargetForkResult) {
            events += "publish:${System.getProperty(DIAGNOSTICS_PROPERTY) ?: "null"}"
            published += result
        }
    }

    private class DeliberateTargetForkFailure(message: String) : RuntimeException(message)

    private companion object {
        const val DIAGNOSTICS_PROPERTY = "revoman.lifecycleDiagnostics"
    }
}

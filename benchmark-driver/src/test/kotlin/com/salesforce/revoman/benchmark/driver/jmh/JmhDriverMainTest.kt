/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.RunnerException
import org.openjdk.jmh.runner.options.Options

class JmhDriverMainTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `runner forces fail on error`() {
        var captured: Options? = null

        runJmh(arrayOf("SmokeBenchmark")) { options ->
            captured = options
            listOf(mockk<RunResult>())
        }

        assertThat(requireNotNull(captured).shouldFailOnError().orElse(false)).isTrue()
        assertThat(requireNotNull(captured).profilers.map { it.klass })
            .contains("com.salesforce.revoman.benchmark.driver.jmh.ForkPidProfiler")
    }

    @Test
    fun `runner forwards captured lifecycle manifest hash to every fork`() {
        var captured: Options? = null
        val prior = System.getProperty(LIFECYCLE_MANIFEST_SHA256_PROPERTY)
        System.setProperty(LIFECYCLE_MANIFEST_SHA256_PROPERTY, "a".repeat(64))
        try {
            runJmh(arrayOf("WarmLifecycleAllocationBenchmark")) { options ->
                captured = options
                listOf(mockk<RunResult>())
            }
        } finally {
            if (prior == null) {
                System.clearProperty(LIFECYCLE_MANIFEST_SHA256_PROPERTY)
            } else {
                System.setProperty(LIFECYCLE_MANIFEST_SHA256_PROPERTY, prior)
            }
        }

        assertThat(requireNotNull(captured).jvmArgsAppend.orElse(emptyList()))
            .contains("-D$LIFECYCLE_MANIFEST_SHA256_PROPERTY=${"a".repeat(64)}")
    }

    @Test
    fun `empty result collection fails`() {
        val failure = assertThrows<IllegalStateException> {
            runJmh(emptyArray()) { emptyList() }
        }

        assertThat(failure).hasMessageThat().isEqualTo("JMH produced no result rows")
    }

    @Test
    fun `runner exception propagates`() {
        val failure = assertThrows<RunnerException> {
            runJmh(emptyArray()) { throw RunnerException("fork failed") }
        }

        assertThat(failure).hasMessageThat().isEqualTo("fork failed")
    }

    @Test
    fun `postflight failure is suppressed behind primary JMH failure`() {
        val primary = DeliberateJmhFailure("runner failed")
        val postflight = IllegalStateException("postflight failed")

        val failure = assertThrows<DeliberateJmhFailure> {
            withJmhPostflight(postflight = { throw postflight }) { throw primary }
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.suppressed.asList()).containsExactly(postflight)
    }

    @Test
    fun `normalized JMH result is schema valid after atomic write`() {
        val source =
            Path.of(
                requireNotNull(javaClass.getResource("/jmh-result/v1/minimal-valid.json")).toURI()
            )
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(source)
        val output = temporaryDirectory.resolve("revoman-benchmark-jmh-v1.json")

        writeJmhResult(output, result)

        BenchmarkJson.validateSchema(output, "/schema/revoman-benchmark-jmh-v1.schema.json")
        assertThat(BenchmarkJson.read<JmhBenchmarkResultV1>(output)).isEqualTo(result)
    }

    @Test
    fun `invalid normalized JMH result cannot replace prior output`() {
        val source =
            Path.of(
                requireNotNull(javaClass.getResource("/jmh-result/v1/minimal-valid.json")).toURI()
            )
        val invalid = BenchmarkJson.read<JmhBenchmarkResultV1>(source).copy(resultId = "")
        val output = temporaryDirectory.resolve("revoman-benchmark-jmh-v1.json")
        Files.writeString(output, "prior")

        assertThrows<IllegalArgumentException> { writeJmhResult(output, invalid) }

        assertThat(Files.readString(output)).isEqualTo("prior")
    }

    @Test
    fun `logging identity hashes the actual effective configuration bytes`() {
        val log4j2 = temporaryDirectory.resolve("log4j2.xml")
        val log4j3 = temporaryDirectory.resolve("log4j3.xml")
        Files.writeString(log4j2, "root=OFF\n")
        Files.writeString(log4j3, "status=OFF\n")

        val configuration =
            jmhLoggingConfiguration(
                log4j2Configuration = log4j2,
                log4j3Configuration = log4j3,
                kotlinLoggingStartupMessage = "false",
                revomanBanner = "off",
            )

        assertThat(configuration.log4j2ConfigurationFileSha256)
            .isEqualTo("fe2e7b63a122b319ab70600859839378afbb1bca5087652a84b6b542af490a48")
        assertThat(configuration.log4j2GlobalConfigurationFileSha256)
            .isEqualTo("f21ec6bacc847eee8bb36f4b89654e6d79c4cf41262a3b2df3f7b68b76262d11")
        assertThat(configuration.kotlinLoggingStartupMessage).isEqualTo("false")
        assertThat(configuration.revomanBanner).isEqualTo("off")
    }
}

private class DeliberateJmhFailure(message: String) : RuntimeException(message)

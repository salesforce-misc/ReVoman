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
}

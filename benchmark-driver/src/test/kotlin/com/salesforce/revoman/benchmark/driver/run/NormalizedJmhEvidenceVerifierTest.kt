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
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class NormalizedJmhEvidenceVerifierTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `normalized JMH verifier binds target harness workload and requested configuration`() {
        val source = resourcePath("/jmh-result/v1/minimal-valid.json")
        val expected = BenchmarkJson.read<JmhBenchmarkResultV1>(source)

        val verified =
            NormalizedJmhEvidenceVerifier.verify(
                path = source,
                expectation = JmhEvidenceExpectation.from(expected),
            )

        assertThat(verified).isEqualTo(expected)
    }

    @Test
    fun `normalized JMH verifier rejects evidence for a different scheduled target`() {
        val source = resourcePath("/jmh-result/v1/minimal-valid.json")
        val expected = BenchmarkJson.read<JmhBenchmarkResultV1>(source)
        val forged =
            expected.copy(
                target = expected.target.copy(id = "different"),
                benchmarks =
                    expected.benchmarks.map { benchmark ->
                        benchmark.copy(
                            metricSeries =
                                benchmark.metricSeries.map { series ->
                                    series.copy(
                                        rawObservations =
                                            series.rawObservations?.map { observation ->
                                                observation.copy(targetId = "different")
                                            }
                                    )
                                }
                        )
                    },
            )
        val path = temporaryDirectory.resolve("forged-target.json")
        BenchmarkJson.write(path, forged)

        val failure = assertThrows<IllegalArgumentException> {
            NormalizedJmhEvidenceVerifier.verify(path.toRealPath(), JmhEvidenceExpectation.from(expected))
        }

        assertThat(failure).hasMessageThat().contains("scheduled target identity")
    }

    @Test
    fun `normalized JMH verifier rejects schema confusion before identity checks`() {
        val source = resourcePath("/results/v1/minimal-valid.json")
        val expected = BenchmarkJson.read<JmhBenchmarkResultV1>(resourcePath("/jmh-result/v1/minimal-valid.json"))

        val failure = assertThrows<IllegalArgumentException> {
            NormalizedJmhEvidenceVerifier.verify(source, JmhEvidenceExpectation.from(expected))
        }

        assertThat(failure).hasMessageThat().contains("revoman-benchmark-jmh-v1.schema.json")
    }

    @Test
    fun `normalized JMH verifier uses one captured file snapshot`() {
        val source = resourcePath("/jmh-result/v1/minimal-valid.json")
        val expected = BenchmarkJson.read<JmhBenchmarkResultV1>(source)
        val path = temporaryDirectory.resolve("result.json")
        Files.copy(source, path)

        val verified =
            NormalizedJmhEvidenceVerifier.verify(path.toRealPath(), JmhEvidenceExpectation.from(expected))

        assertThat(verified.resultId).isEqualTo(expected.resultId)
    }

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing resource: $name" }.toURI())
}

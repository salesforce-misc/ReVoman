/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.squareup.moshi.JsonDataException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class JmhBenchmarkResultSchemaTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `strict single target JMH result round trips canonically`() {
        val source = fixture("minimal-valid.json")
        BenchmarkJson.validateSchema(source, SCHEMA)
        val first = temporaryDirectory.resolve("first.json")
        val second = temporaryDirectory.resolve("second.json")

        BenchmarkJson.write(first, BenchmarkJson.read<JmhBenchmarkResultV1>(source))
        BenchmarkJson.write(second, BenchmarkJson.read<JmhBenchmarkResultV1>(first))

        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(first))
        assertThat(BenchmarkJson.read<JmhBenchmarkResultV1>(first).target.id)
            .isEqualTo("current")
    }

    @Test
    fun `unknown JMH result property is rejected by schema and parser`() {
        val source = Files.readString(fixture("minimal-valid.json"))
        val invalid = temporaryDirectory.resolve("unknown.json")
        Files.writeString(invalid, source.replaceFirst("{", "{\"unexpected\":true,"))

        val schemaFailure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(invalid, SCHEMA)
        }
        val parseFailure = assertThrows<JsonDataException> {
            BenchmarkJson.read<JmhBenchmarkResultV1>(invalid)
        }

        assertThat(schemaFailure).hasMessageThat().contains("unexpected")
        assertThat(parseFailure).hasMessageThat().contains("unexpected")
    }

    @Test
    fun `single target JMH result rejects a forged harness distribution hash`() {
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(fixture("minimal-valid.json"))
        val forged = result.copy(harness = result.harness.copy(distributionSha256 = "0".repeat(64)))

        val failure = assertThrows<IllegalArgumentException> { forged.validate() }

        assertThat(failure).hasMessageThat().contains("ordered artifact snapshot")
    }

    @Test
    fun `raw JMH observations must cover every fork and iteration`() {
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(fixture("minimal-valid.json"))
        val benchmark = result.benchmarks.single()
        val series = benchmark.metricSeries.single()
        val incomplete =
            result.copy(
                benchmarks =
                    listOf(
                        benchmark.copy(
                            metricSeries =
                                listOf(series.copy(rawObservations = series.rawObservations!!.dropLast(1)))
                        )
                    )
            )

        val failure = assertThrows<IllegalArgumentException> { incomplete.validate() }

        assertThat(failure).hasMessageThat().contains("coordinates")
    }

    @Test
    fun `JMH metric series cannot contain raw and histogram evidence together`() {
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(fixture("minimal-valid.json"))
        val benchmark = result.benchmarks.single()
        val series = benchmark.metricSeries.single()
        val both =
            result.copy(
                benchmarks =
                    listOf(
                        benchmark.copy(
                            metricSeries =
                                listOf(
                                    series.copy(
                                        exactHistogram =
                                            ExactHistogram(
                                                targetId = result.target.id,
                                                buckets = listOf(HistogramBucket(value = 10.0, count = 2)),
                                            )
                                    )
                                )
                        )
                    )
            )

        val failure = assertThrows<IllegalArgumentException> { both.validate() }

        assertThat(failure).hasMessageThat().contains("exactly one")
    }

    @Test
    fun `JMH benchmark requires one stable process ID per fork`() {
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(fixture("minimal-valid.json"))
        val benchmark = result.benchmarks.single()
        val series = benchmark.metricSeries.single()
        val inconsistent =
            result.copy(
                benchmarks =
                    listOf(
                        benchmark.copy(
                            metricSeries =
                                listOf(
                                    series.copy(
                                        rawObservations =
                                            series.rawObservations!!.mapIndexed { index, observation ->
                                                if (index == 0) observation
                                                else observation.copy(processId = observation.processId + 1)
                                            }
                                    )
                                )
                        )
                    )
            )

        val failure = assertThrows<IllegalArgumentException> { inconsistent.validate() }

        assertThat(failure).hasMessageThat().contains("stable process ID")
    }

    @Test
    fun `requested fork count must match effective raw JMH configuration`() {
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(fixture("minimal-valid.json"))
        val mismatched =
            result.copy(configuration = result.configuration.copy(requestedForks = 2))

        val failure = assertThrows<IllegalArgumentException> { mismatched.validate() }

        assertThat(failure).hasMessageThat().contains("requestedForks")
    }

    @Test
    fun `JMH canonical write orders unordered provider and observation evidence`() {
        val result = BenchmarkJson.read<JmhBenchmarkResultV1>(fixture("minimal-valid.json"))
        val benchmark = result.benchmarks.single()
        val latency = benchmark.metricSeries.single()
        val allocation =
            latency.copy(
                metric = MetricId.ALLOCATED_BYTES,
                provider = "jmh:gc.alloc.rate.norm",
                providerConfigurationSha256 =
                    "8888888888888888888888888888888888888888888888888888888888888888",
                unit = MetricUnit.BYTES_PER_OPERATION,
                rawObservations =
                    latency.rawObservations!!.reversed().map { observation ->
                        observation.copy(
                            metric = MetricId.ALLOCATED_BYTES,
                            provider = "jmh:gc.alloc.rate.norm",
                            unit = MetricUnit.BYTES_PER_OPERATION,
                            value = 100.0 + observation.iteration,
                        )
                    },
            )
        val unordered =
            result.copy(
                configuration =
                    result.configuration.copy(profilers = listOf("stack", "gc", "stack")),
                benchmarks =
                    listOf(benchmark.copy(metricSeries = listOf(allocation, latency))),
            )
        val output = temporaryDirectory.resolve("canonical.json")

        BenchmarkJson.write(output, unordered)
        val canonical = BenchmarkJson.read<JmhBenchmarkResultV1>(output)

        assertThat(canonical.configuration.profilers).containsExactly("gc", "stack").inOrder()
        assertThat(canonical.configuration.logging)
            .isEqualTo(result.configuration.logging)
        assertThat(canonical.benchmarks.single().jvmArgs)
            .containsExactly("-Xms256m", "-Xmx256m")
            .inOrder()
        assertThat(canonical.benchmarks.single().metricSeries.map(JmhMetricSeries::metric))
            .containsExactly(MetricId.LATENCY, MetricId.ALLOCATED_BYTES)
            .inOrder()
        assertThat(
                canonical.benchmarks.single().metricSeries.last().rawObservations!!.map {
                    it.iteration
                }
            )
            .containsExactly(0, 1)
            .inOrder()
    }

    private fun fixture(name: String): Path =
        Path.of(
            requireNotNull(javaClass.getResource("/jmh-result/v1/$name")) {
                    "Missing JMH result fixture: $name"
                }
                .toURI()
        )

    private companion object {
        const val SCHEMA: String = "/schema/revoman-benchmark-jmh-v1.schema.json"
    }
}

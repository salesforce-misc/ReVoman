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
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JmhResultImporterTest {
    @Test
    fun `empty JSON result is rejected`() {
        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = fixture("empty.json"),
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark"),
                processId = 123,
            )
        }

        assertThat(failure).hasMessageThat().contains("no result rows")
    }

    @Test
    fun `primary metric without raw data is rejected`() {
        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = fixture("missing-raw-data.json"),
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark"),
                processId = 123,
            )
        }

        assertThat(failure).hasMessageThat().contains("primaryMetric.rawData")
    }

    @Test
    fun `raw fork and iteration coordinates plus allocation are preserved`() {
        val imported =
            JmhResultImporter.`import`(
                rawResult = fixture("valid.json"),
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
                processId = 321,
            )

        assertThat(imported.benchmarks)
            .containsExactly("com.salesforce.revoman.benchmark.HarnessSanityBenchmark.scalar")
        assertThat(imported.records.single().forks).isEqualTo(2)
        assertThat(imported.records.single().warmupIterations).isEqualTo(1)
        assertThat(imported.records.single().measurementIterations).isEqualTo(2)
        assertThat(imported.records.single().parameters).isEmpty()
        assertThat(imported.observations).hasSize(8)
        assertThat(
                imported.observations
                    .filter { it.metric == MetricId.LATENCY }
                    .map { Triple(it.fork, it.iteration, it.value) }
            )
            .containsExactly(
                Triple(0, 0, 10.0),
                Triple(0, 1, 11.0),
                Triple(1, 0, 12.0),
                Triple(1, 1, 13.0),
            )
            .inOrder()
        assertThat(
                imported.observations
                    .filter { it.metric == MetricId.ALLOCATED_BYTES }
                    .map { Triple(it.fork, it.iteration, it.value) }
            )
            .containsExactly(
                Triple(0, 0, 100.25),
                Triple(0, 1, 101.5),
                Triple(1, 0, 102.75),
                Triple(1, 1, 103.125),
            )
            .inOrder()
        assertThat(imported.observations.map { it.targetId }.distinct())
            .containsExactly("current")
        assertThat(imported.observations.map { it.processId }.distinct()).containsExactly(321L)
        assertThat(imported.observations.first().unit)
            .isEqualTo(MetricUnit.NANOSECONDS_PER_OPERATION)
        assertThat(imported.observations.last().unit)
            .isEqualTo(MetricUnit.BYTES_PER_OPERATION)
    }

    @Test
    fun `requested include without a returned benchmark is rejected`() {
        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = fixture("valid.json"),
                targetId = "current",
                requestedIncludes = listOf("DefinitelyMissingBenchmark"),
                processId = 123,
            )
        }

        assertThat(failure).hasMessageThat().contains("DefinitelyMissingBenchmark")
    }

    @Test
    fun `imported rows attach to strict single target JMH identities`() {
        val identityTemplate =
            BenchmarkJson.read<JmhBenchmarkResultV1>(
                Path.of(requireNotNull(javaClass.getResource("/jmh-result/v1/minimal-valid.json")).toURI())
            )
        val imported =
            JmhResultImporter.`import`(
                rawResult = fixture("valid.json"),
                targetId = identityTemplate.target.id,
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
                processId = 321,
            )
        val configuration =
            JmhRunConfiguration(
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
                requestedForks = 2,
                profilers = listOf("gc"),
                quick = false,
            )

        val result =
            JmhResultImporter.attachIdentities(
                imported = imported,
                resultId = "attached-result",
                createdAt = "2026-08-09T01:00:00Z",
                harness = identityTemplate.harness,
                environment = identityTemplate.environment,
                target = identityTemplate.target,
                workload = identityTemplate.workload,
                configuration = configuration,
            )

        assertThat(result.validate()).isSameInstanceAs(result)
        assertThat(result.benchmarks.single().metricSeries.map { it.metric })
            .containsExactly(MetricId.LATENCY, MetricId.ALLOCATED_BYTES)
            .inOrder()
    }

    private fun fixture(name: String): Path =
        Path.of(
            requireNotNull(javaClass.getResource("/jmh/$name")) {
                    "Missing JMH fixture: $name"
                }
                .toURI(),
        )
}

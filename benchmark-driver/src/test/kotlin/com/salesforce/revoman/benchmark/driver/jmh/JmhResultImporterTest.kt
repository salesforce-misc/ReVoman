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
import com.salesforce.revoman.benchmark.driver.model.JmhLoggingConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class JmhResultImporterTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `empty JSON result is rejected`() {
        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = fixture("empty.json"),
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark"),
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
        assertThat(
                imported.observations
                    .filter { it.metric == MetricId.LATENCY }
                    .groupBy { it.fork }
                    .mapValues { (_, observations) -> observations.map { it.processId }.distinct() }
            )
            .containsExactlyEntriesIn(mapOf(0 to listOf(4101L), 1 to listOf(4102L)))
        assertThat(imported.observations.map { it.processId }).doesNotContain(321L)
        assertThat(imported.observations.first().unit)
            .isEqualTo(MetricUnit.NANOSECONDS_PER_OPERATION)
        assertThat(imported.observations.last().unit)
            .isEqualTo(MetricUnit.BYTES_PER_OPERATION)
    }

    @Test
    fun `raw effective JMH execution configuration is preserved`() {
        val record =
            JmhResultImporter.`import`(
                    rawResult = fixture("valid.json"),
                    targetId = "current",
                    requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
                )
                .records
                .single()

        assertThat(record.jmhVersion).isEqualTo("1.37")
        assertThat(record.mode).isEqualTo("avgt")
        assertThat(record.threads).isEqualTo(1)
        assertThat(record.jvm).isEqualTo("/jdk/bin/java")
        assertThat(record.jvmArgs).isEmpty()
        assertThat(record.jdkVersion).isEqualTo("21")
        assertThat(record.vmName).isEqualTo("OpenJDK 64-Bit Server VM")
        assertThat(record.vmVersion).isEqualTo("21")
        assertThat(record.warmupTime).isEqualTo("1 s")
        assertThat(record.warmupBatchSize).isEqualTo(1)
        assertThat(record.measurementTime).isEqualTo("1 s")
        assertThat(record.measurementBatchSize).isEqualTo(1)
    }

    @Test
    fun `requested include without a returned benchmark is rejected`() {
        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = fixture("valid.json"),
                targetId = "current",
                requestedIncludes = listOf("DefinitelyMissingBenchmark"),
            )
        }

        assertThat(failure).hasMessageThat().contains("DefinitelyMissingBenchmark")
    }

    @Test
    fun `missing fork PID provenance is rejected`() {
        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = fixture("missing-fork-pid.json"),
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
            )
        }

        assertThat(failure).hasMessageThat().contains("revoman.fork.pid")
    }

    @Test
    fun `fork PID must be stable within each fork`() {
        val malformed = temporaryDirectory.resolve("unstable-fork-pid.json")
        Files.writeString(
            malformed,
            Files.readString(fixture("valid.json"))
                .replace(
                    "[[4101.0, 4101.0], [4102.0, 4102.0]]",
                    "[[4101.0, 4103.0], [4102.0, 4102.0]]",
                ),
        )

        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = malformed,
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
            )
        }

        assertThat(failure).hasMessageThat().contains("stable PID")
    }

    @Test
    fun `different forks must have distinct PIDs`() {
        val malformed = temporaryDirectory.resolve("duplicate-fork-pid.json")
        Files.writeString(
            malformed,
            Files.readString(fixture("valid.json"))
                .replace(
                    "[[4101.0, 4101.0], [4102.0, 4102.0]]",
                    "[[4101.0, 4101.0], [4101.0, 4101.0]]",
                ),
        )

        val failure = assertThrows<IllegalArgumentException> {
            JmhResultImporter.`import`(
                rawResult = malformed,
                targetId = "current",
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
            )
        }

        assertThat(failure).hasMessageThat().contains("distinct PID")
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
            )
        val configuration =
            JmhRunConfiguration(
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
                requestedForks = 2,
                profilers = listOf("gc"),
                internalProfilers =
                    listOf("com.salesforce.revoman.benchmark.driver.jmh.ForkPidProfiler"),
                quick = false,
                logging = loggingConfiguration("a"),
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
        assertThat(result.configuration.internalProfilers)
            .containsExactly("com.salesforce.revoman.benchmark.driver.jmh.ForkPidProfiler")
    }

    @Test
    fun `provider configuration identity changes with execution settings and logging bytes`() {
        val identityTemplate =
            BenchmarkJson.read<JmhBenchmarkResultV1>(
                Path.of(requireNotNull(javaClass.getResource("/jmh-result/v1/minimal-valid.json")).toURI())
            )
        val imported =
            JmhResultImporter.`import`(
                rawResult = fixture("valid.json"),
                targetId = identityTemplate.target.id,
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
            )
        val configuration =
            JmhRunConfiguration(
                requestedIncludes = listOf("HarnessSanityBenchmark.scalar"),
                requestedForks = 2,
                profilers = listOf("gc"),
                internalProfilers =
                    listOf("com.salesforce.revoman.benchmark.driver.jmh.ForkPidProfiler"),
                quick = false,
                logging = loggingConfiguration("a"),
            )

        fun attach(
            source: ImportedJmhResult,
            runConfiguration: JmhRunConfiguration,
        ): JmhBenchmarkResultV1 =
            JmhResultImporter.attachIdentities(
                imported = source,
                resultId = "identity-result",
                createdAt = "2026-08-09T01:00:00Z",
                harness = identityTemplate.harness,
                environment = identityTemplate.environment,
                target = identityTemplate.target,
                workload = identityTemplate.workload,
                configuration = runConfiguration,
            )

        val baseline = attach(imported, configuration)
        val differentMode =
            attach(
                imported.copy(records = imported.records.map { it.copy(mode = "sample") }),
                configuration,
            )
        val differentParameters =
            attach(
                imported.copy(
                    records = imported.records.map { it.copy(parameters = mapOf("steps" to "200")) }
                ),
                configuration,
            )
        val differentLogging =
            attach(imported, configuration.copy(logging = loggingConfiguration("b")))

        val baselineProvider =
            baseline.benchmarks.single().metricSeries.first().providerConfigurationSha256
        assertThat(differentMode.benchmarks.single().metricSeries.first().providerConfigurationSha256)
            .isNotEqualTo(baselineProvider)
        assertThat(
                differentParameters.benchmarks.single().metricSeries.first().providerConfigurationSha256
            )
            .isNotEqualTo(baselineProvider)
        assertThat(differentLogging.benchmarks.single().metricSeries.first().providerConfigurationSha256)
            .isNotEqualTo(baselineProvider)
    }

    private fun loggingConfiguration(hashDigit: String): JmhLoggingConfiguration =
        JmhLoggingConfiguration(
            log4j2ConfigurationFileSha256 = hashDigit.repeat(64),
            log4j2GlobalConfigurationFileSha256 = hashDigit.repeat(64),
            kotlinLoggingStartupMessage = "false",
            revomanBanner = "off",
        )

    private fun fixture(name: String): Path =
        Path.of(
            requireNotNull(javaClass.getResource("/jmh/$name")) {
                    "Missing JMH fixture: $name"
                }
                .toURI(),
        )
}

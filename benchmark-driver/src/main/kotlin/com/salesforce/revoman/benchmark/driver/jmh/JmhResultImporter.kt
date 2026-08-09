/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.model.EnvironmentIdentity
import com.salesforce.revoman.benchmark.driver.model.HarnessIdentity
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkEvidence
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.JmhMetricSeries
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhWorkloadIdentity
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.nio.file.Files
import java.nio.file.Path

/** Raw JMH rows normalized without losing fork or measurement-iteration coordinates. */
data class ImportedJmhResult(
    val records: List<ImportedJmhBenchmark>,
) {
    val benchmarks: List<String> get() = records.map(ImportedJmhBenchmark::benchmark)

    val observations: List<MetricObservation>
        get() = records.flatMap(ImportedJmhBenchmark::observations)
}

/** One strict JMH JSON row before source, target, and workload identities are attached. */
data class ImportedJmhBenchmark(
    val jmhVersion: String,
    val benchmark: String,
    val parameters: Map<String, String>,
    val forks: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val observations: List<MetricObservation>,
)

/** Strictly imports machine-readable JMH JSON into target-independent observations. */
object JmhResultImporter {
    private val dynamicAdapter =
        Moshi.Builder()
            .build()
            .adapter<List<Map<String, Any?>>>(
                Types.newParameterizedType(
                    List::class.java,
                    Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        Any::class.java,
                    ),
                ),
            )

    /** Imports [rawResult] and requires every requested include to match a returned benchmark. */
    fun `import`(
        rawResult: Path,
        targetId: String,
        requestedIncludes: List<String>,
        processId: Long,
    ): ImportedJmhResult {
        require(targetId.isNotBlank()) { "targetId must not be blank" }
        require(processId >= 0) { "processId must not be negative" }
        val rows =
            requireNotNull(dynamicAdapter.fromJson(Files.readString(rawResult))) {
                "JMH result at $rawResult is null"
            }
        require(rows.isNotEmpty()) { "JMH produced no result rows" }
        val benchmarkNames = rows.mapIndexed { index, row -> row.requiredString(index, "benchmark") }
        requestedIncludes.forEach { include ->
            val regex = Regex(include)
            require(benchmarkNames.any(regex::containsMatchIn)) {
                "JMH include '$include' matched no returned benchmark: $benchmarkNames"
            }
        }
        val records =
            rows.mapIndexed { index, row ->
                val benchmark = benchmarkNames[index]
                val jmhVersion = row.requiredString(index, "jmhVersion")
                val forks = row.requiredInt(index, "forks")
                val warmupIterations = row.requiredInt(index, "warmupIterations")
                val measurementIterations = row.requiredInt(index, "measurementIterations")
                require(forks > 0) { "JMH row[$index].forks must be positive" }
                require(warmupIterations >= 0) {
                    "JMH row[$index].warmupIterations must not be negative"
                }
                require(measurementIterations > 0) {
                    "JMH row[$index].measurementIterations must be positive"
                }
                val parameters = row.optionalStringMap(index, "params")
                val primary = row.requiredObject(index, "primaryMetric")
                val latency =
                    primary.rawObservations(
                        rowIndex = index,
                        path = "primaryMetric",
                        targetId = targetId,
                        metric = MetricId.LATENCY,
                        provider = "jmh:$benchmark",
                        unit = MetricUnit.NANOSECONDS_PER_OPERATION,
                        processId = processId,
                        multiplier = primary.nanosecondsMultiplier(index, "primaryMetric"),
                        forks = forks,
                        measurementIterations = measurementIterations,
                    )
                val secondary = row.optionalObject(index, "secondaryMetrics")
                val allocation =
                    secondary
                        ?.get("gc.alloc.rate.norm")
                        ?.let { value ->
                            value.requireObject(index, "secondaryMetrics.gc.alloc.rate.norm")
                                .also { metric ->
                                    require(
                                        metric.requiredString(
                                            index,
                                            "scoreUnit",
                                            "secondaryMetrics.gc.alloc.rate.norm",
                                        ) == "B/op"
                                    ) {
                                        "JMH row[$index].secondaryMetrics.gc.alloc.rate.norm.scoreUnit " +
                                            "must be B/op"
                                    }
                                }
                                .rawObservations(
                                    rowIndex = index,
                                    path = "secondaryMetrics.gc.alloc.rate.norm",
                                    targetId = targetId,
                                    metric = MetricId.ALLOCATED_BYTES,
                                    provider = "jmh:gc.alloc.rate.norm:$benchmark",
                                    unit = MetricUnit.BYTES_PER_OPERATION,
                                    processId = processId,
                                    multiplier = 1.0,
                                    forks = forks,
                                    measurementIterations = measurementIterations,
                                )
                        }
                        .orEmpty()
                ImportedJmhBenchmark(
                    jmhVersion = jmhVersion,
                    benchmark = benchmark,
                    parameters = parameters,
                    forks = forks,
                    warmupIterations = warmupIterations,
                    measurementIterations = measurementIterations,
                    observations = latency + allocation,
                )
            }
        return ImportedJmhResult(records = records)
    }

    /** Attaches immutable identities without converting single-target evidence into a paired run. */
    fun attachIdentities(
        imported: ImportedJmhResult,
        resultId: String,
        createdAt: String,
        harness: HarnessIdentity,
        environment: EnvironmentIdentity,
        target: TargetIdentity,
        workload: JmhWorkloadIdentity,
        configuration: JmhRunConfiguration,
    ): JmhBenchmarkResultV1 =
        JmhBenchmarkResultV1(
                resultId = resultId,
                createdAt = createdAt,
                harness = harness,
                environment = environment,
                target = target,
                workload = workload,
                configuration = configuration,
                benchmarks =
                    imported.records.map { record ->
                        JmhBenchmarkEvidence(
                            name = record.benchmark,
                            parameters = record.parameters,
                            forks = record.forks,
                            warmupIterations = record.warmupIterations,
                            measurementIterations = record.measurementIterations,
                            metricSeries =
                                record.observations
                                    .groupBy { observation ->
                                        Triple(
                                            observation.metric,
                                            observation.provider,
                                            observation.unit,
                                        )
                                    }
                                    .map { (identity, observations) ->
                                        JmhMetricSeries(
                                            metric = identity.first,
                                            provider = identity.second,
                                            providerConfigurationSha256 =
                                                providerConfigurationSha256(
                                                    record.jmhVersion,
                                                    identity.first,
                                                    identity.second,
                                                    identity.third,
                                                ),
                                            unit = identity.third,
                                            rawObservations = observations,
                                            exactHistogram = null,
                                        )
                                    },
                        )
                    },
            )
            .canonicalized()
            .validate()
}

private fun providerConfigurationSha256(
    jmhVersion: String,
    metric: MetricId,
    provider: String,
    unit: MetricUnit,
): String =
    ContentHasher.sha256(
        "revoman-jmh-provider/v1\u0000$jmhVersion\u0000${metric.name}\u0000$provider\u0000${unit.name}"
            .toByteArray()
    )

private fun Map<String, Any?>.rawObservations(
    rowIndex: Int,
    path: String,
    targetId: String,
    metric: MetricId,
    provider: String,
    unit: MetricUnit,
    processId: Long,
    multiplier: Double,
    forks: Int,
    measurementIterations: Int,
): List<MetricObservation> {
    val rawData = this["rawData"] as? List<*>
    requireNotNull(rawData) { "JMH row[$rowIndex].$path.rawData must be an array" }
    require(rawData.isNotEmpty()) { "JMH row[$rowIndex].$path.rawData must not be empty" }
    require(rawData.size == forks) {
        "JMH row[$rowIndex].$path.rawData fork count must match forks: " +
            "expected=$forks, actual=${rawData.size}"
    }
    return rawData.flatMapIndexed { fork, rawFork ->
        val iterations = rawFork as? List<*>
        requireNotNull(iterations) {
            "JMH row[$rowIndex].$path.rawData[$fork] must be an array"
        }
        require(iterations.isNotEmpty()) {
            "JMH row[$rowIndex].$path.rawData[$fork] must not be empty"
        }
        require(iterations.size == measurementIterations) {
            "JMH row[$rowIndex].$path.rawData[$fork] iteration count must match " +
                "measurementIterations: expected=$measurementIterations, actual=${iterations.size}"
        }
        iterations.mapIndexed { iteration, rawValue ->
            val value = (rawValue as? Number)?.toDouble()?.times(multiplier)
            requireNotNull(value) {
                "JMH row[$rowIndex].$path.rawData[$fork][$iteration] must be numeric"
            }
            require(value.isFinite() && value >= 0.0) {
                "JMH row[$rowIndex].$path.rawData[$fork][$iteration] must be finite and non-negative"
            }
            MetricObservation(
                targetId = targetId,
                metric = metric,
                provider = provider,
                unit = unit,
                fork = fork,
                iteration = iteration,
                processId = processId,
                value = value,
            )
        }
    }
}

private fun Map<String, Any?>.nanosecondsMultiplier(rowIndex: Int, path: String): Double =
    when (requiredString(rowIndex, "scoreUnit", path)) {
        "ns/op" -> 1.0
        "us/op" -> 1_000.0
        "ms/op" -> 1_000_000.0
        "s/op" -> 1_000_000_000.0
        else -> throw IllegalArgumentException("JMH row[$rowIndex].$path.scoreUnit is unsupported")
    }

private fun Map<String, Any?>.requiredString(
    rowIndex: Int,
    key: String,
    path: String = "",
): String {
    val prefix = if (path.isEmpty()) "" else "$path."
    return this[key] as? String
        ?: throw IllegalArgumentException("JMH row[$rowIndex].$prefix$key must be a string")
}

private fun Map<String, Any?>.requiredInt(rowIndex: Int, key: String): Int {
    val number = this[key] as? Number
    requireNotNull(number) { "JMH row[$rowIndex].$key must be an integer" }
    val long = number.toLong()
    require(number.toDouble() == long.toDouble() && long in Int.MIN_VALUE..Int.MAX_VALUE) {
        "JMH row[$rowIndex].$key must be an integer"
    }
    return long.toInt()
}

private fun Map<String, Any?>.optionalStringMap(
    rowIndex: Int,
    key: String,
): Map<String, String> {
    if (this[key] == null) return emptyMap()
    val objectValue = requiredObject(rowIndex, key)
    return objectValue.mapValues { (parameter, value) ->
        value as? String
            ?: throw IllegalArgumentException(
                "JMH row[$rowIndex].$key.$parameter must be a string"
            )
    }
}

private fun Map<String, Any?>.requiredObject(rowIndex: Int, key: String): Map<String, Any?> =
    requireNotNull(optionalObject(rowIndex, key)) { "JMH row[$rowIndex].$key must be an object" }

private fun Map<String, Any?>.optionalObject(
    rowIndex: Int,
    key: String,
): Map<String, Any?>? =
    this[key]?.requireObject(rowIndex, key)

private fun Any.requireObject(rowIndex: Int, path: String): Map<String, Any?> {
    val objectValue = this as? Map<*, *>
    requireNotNull(objectValue) { "JMH row[$rowIndex].$path must be an object" }
    return objectValue.entries.associate { (key, value) ->
        require(key is String) { "JMH row[$rowIndex].$path keys must be strings" }
        key to value
    }
}

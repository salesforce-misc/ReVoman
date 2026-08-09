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
    val mode: String,
    val threads: Int,
    val forks: Int,
    val jvm: String,
    val jvmArgs: List<String>,
    val jdkVersion: String,
    val vmName: String,
    val vmVersion: String,
    val warmupIterations: Int,
    val warmupTime: String,
    val warmupBatchSize: Int,
    val measurementIterations: Int,
    val measurementTime: String,
    val measurementBatchSize: Int,
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
    ): ImportedJmhResult {
        require(targetId.isNotBlank()) { "targetId must not be blank" }
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
                val mode = row.requiredString(index, "mode")
                val threads = row.requiredInt(index, "threads")
                val forks = row.requiredInt(index, "forks")
                val jvm = row.requiredString(index, "jvm")
                val jvmArgs = row.requiredStringList(index, "jvmArgs")
                val jdkVersion = row.requiredString(index, "jdkVersion")
                val vmName = row.requiredString(index, "vmName")
                val vmVersion = row.requiredString(index, "vmVersion")
                val warmupIterations = row.requiredInt(index, "warmupIterations")
                val warmupTime = row.requiredString(index, "warmupTime")
                val warmupBatchSize = row.requiredInt(index, "warmupBatchSize")
                val measurementIterations = row.requiredInt(index, "measurementIterations")
                val measurementTime = row.requiredString(index, "measurementTime")
                val measurementBatchSize = row.requiredInt(index, "measurementBatchSize")
                require(threads > 0) { "JMH row[$index].threads must be positive" }
                require(forks > 0) { "JMH row[$index].forks must be positive" }
                require(warmupIterations >= 0) {
                    "JMH row[$index].warmupIterations must not be negative"
                }
                require(warmupBatchSize > 0) {
                    "JMH row[$index].warmupBatchSize must be positive"
                }
                require(measurementIterations > 0) {
                    "JMH row[$index].measurementIterations must be positive"
                }
                require(measurementBatchSize > 0) {
                    "JMH row[$index].measurementBatchSize must be positive"
                }
                val parameters = row.optionalStringMap(index, "params")
                val secondary = row.requiredObject(index, "secondaryMetrics")
                val forkProcessIds =
                    requireNotNull(secondary[FORK_PID_METRIC]) {
                            "JMH row[$index].secondaryMetrics.$FORK_PID_METRIC must be present"
                        }
                        .requireObject(index, "secondaryMetrics.$FORK_PID_METRIC")
                        .also { metric ->
                            require(
                                metric.requiredString(
                                    index,
                                    "scoreUnit",
                                    "secondaryMetrics.$FORK_PID_METRIC",
                                ) == FORK_PID_UNIT
                            ) {
                                "JMH row[$index].secondaryMetrics.$FORK_PID_METRIC.scoreUnit " +
                                    "must be $FORK_PID_UNIT"
                            }
                        }
                        .forkProcessIds(
                            rowIndex = index,
                            forks = forks,
                            measurementIterations = measurementIterations,
                        )
                val primary = row.requiredObject(index, "primaryMetric")
                val latency =
                    primary.rawObservations(
                        rowIndex = index,
                        path = "primaryMetric",
                        targetId = targetId,
                        metric = MetricId.LATENCY,
                        provider = "jmh:$benchmark",
                        unit = MetricUnit.NANOSECONDS_PER_OPERATION,
                        processIds = forkProcessIds,
                        multiplier = primary.nanosecondsMultiplier(index, "primaryMetric"),
                        forks = forks,
                        measurementIterations = measurementIterations,
                    )
                val allocation =
                    secondary
                        .get("gc.alloc.rate.norm")
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
                                    processIds = forkProcessIds,
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
                    mode = mode,
                    threads = threads,
                    forks = forks,
                    jvm = jvm,
                    jvmArgs = jvmArgs,
                    jdkVersion = jdkVersion,
                    vmName = vmName,
                    vmVersion = vmVersion,
                    warmupIterations = warmupIterations,
                    warmupTime = warmupTime,
                    warmupBatchSize = warmupBatchSize,
                    measurementIterations = measurementIterations,
                    measurementTime = measurementTime,
                    measurementBatchSize = measurementBatchSize,
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
                            jmhVersion = record.jmhVersion,
                            mode = record.mode,
                            threads = record.threads,
                            forks = record.forks,
                            jvm = record.jvm,
                            jvmArgs = record.jvmArgs,
                            jdkVersion = record.jdkVersion,
                            vmName = record.vmName,
                            vmVersion = record.vmVersion,
                            warmupIterations = record.warmupIterations,
                            warmupTime = record.warmupTime,
                            warmupBatchSize = record.warmupBatchSize,
                            measurementIterations = record.measurementIterations,
                            measurementTime = record.measurementTime,
                            measurementBatchSize = record.measurementBatchSize,
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
                                                    record,
                                                    configuration.canonicalized(),
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
    record: ImportedJmhBenchmark,
    configuration: JmhRunConfiguration,
    metric: MetricId,
    provider: String,
    unit: MetricUnit,
): String =
    ContentHasher.sha256(
        buildList {
                add("revoman-jmh-provider/v2")
                add(record.jmhVersion)
                add(record.mode)
                add(record.parameters.size.toString())
                record.parameters.toSortedMap().forEach { (name, value) ->
                    add(name)
                    add(value)
                }
                add(record.threads.toString())
                add(record.forks.toString())
                add(record.jvm)
                add(record.jvmArgs.size.toString())
                addAll(record.jvmArgs)
                add(record.jdkVersion)
                add(record.vmName)
                add(record.vmVersion)
                add(record.warmupIterations.toString())
                add(record.warmupTime)
                add(record.warmupBatchSize.toString())
                add(record.measurementIterations.toString())
                add(record.measurementTime)
                add(record.measurementBatchSize.toString())
                add(configuration.requestedIncludes.size.toString())
                addAll(configuration.requestedIncludes)
                add(configuration.requestedForks.toString())
                add(configuration.profilers.size.toString())
                addAll(configuration.profilers)
                add(configuration.internalProfilers.size.toString())
                addAll(configuration.internalProfilers)
                add(configuration.quick.toString())
                add(configuration.logging.log4j2ConfigurationFileSha256)
                add(configuration.logging.log4j2GlobalConfigurationFileSha256)
                add(configuration.logging.kotlinLoggingStartupMessage)
                add(configuration.logging.revomanBanner)
                add(metric.name)
                add(provider)
                add(unit.name)
            }
            .joinToString("\u0000")
            .toByteArray()
    )

private fun Map<String, Any?>.rawObservations(
    rowIndex: Int,
    path: String,
    targetId: String,
    metric: MetricId,
    provider: String,
    unit: MetricUnit,
    processIds: List<Long>,
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
                processId = processIds[fork],
                value = value,
            )
        }
    }
}

private fun Map<String, Any?>.forkProcessIds(
    rowIndex: Int,
    forks: Int,
    measurementIterations: Int,
): List<Long> {
    val path = "secondaryMetrics.$FORK_PID_METRIC"
    val rawData = this["rawData"] as? List<*>
    requireNotNull(rawData) { "JMH row[$rowIndex].$path.rawData must be an array" }
    require(rawData.size == forks) {
        "JMH row[$rowIndex].$path.rawData fork count must match forks: " +
            "expected=$forks, actual=${rawData.size}"
    }
    val processIds =
        rawData.mapIndexed { fork, rawFork ->
            val iterations = rawFork as? List<*>
            requireNotNull(iterations) {
                "JMH row[$rowIndex].$path.rawData[$fork] must be an array"
            }
            require(iterations.size == measurementIterations) {
                "JMH row[$rowIndex].$path.rawData[$fork] iteration count must match " +
                    "measurementIterations: expected=$measurementIterations, actual=${iterations.size}"
            }
            val forkIds =
                iterations.mapIndexed { iteration, rawValue ->
                    val number = rawValue as? Number
                    requireNotNull(number) {
                        "JMH row[$rowIndex].$path.rawData[$fork][$iteration] must be numeric"
                    }
                    val value = number.toDouble()
                    val processId = number.toLong()
                    require(
                        value.isFinite() &&
                            value > 0.0 &&
                            value == processId.toDouble()
                    ) {
                        "JMH row[$rowIndex].$path.rawData[$fork][$iteration] " +
                            "must be a positive integer PID"
                    }
                    processId
                }
            require(forkIds.distinct().size == 1) {
                "JMH row[$rowIndex].$path.rawData[$fork] must contain one stable PID"
            }
            forkIds.first()
        }
    require(processIds.distinct().size == forks) {
        "JMH row[$rowIndex].$path must contain a distinct PID for every fork"
    }
    return processIds
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

private fun Map<String, Any?>.requiredStringList(rowIndex: Int, key: String): List<String> {
    val values = this[key] as? List<*>
    requireNotNull(values) { "JMH row[$rowIndex].$key must be an array" }
    return values.mapIndexed { valueIndex, value ->
        value as? String
            ?: throw IllegalArgumentException(
                "JMH row[$rowIndex].$key[$valueIndex] must be a string"
            )
    }
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

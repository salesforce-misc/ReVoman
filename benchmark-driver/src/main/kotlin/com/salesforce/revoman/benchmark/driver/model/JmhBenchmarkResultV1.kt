/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.squareup.moshi.JsonClass

/** Strict single-target archival JMH evidence, never paired release-campaign evidence. */
@JsonClass(generateAdapter = true)
data class JmhBenchmarkResultV1(
    val schema: String = JMH_RESULT_SCHEMA_V1,
    val resultId: String,
    val createdAt: String,
    val harness: HarnessIdentity,
    val environment: EnvironmentIdentity,
    val target: TargetIdentity,
    val workload: JmhWorkloadIdentity,
    val configuration: JmhRunConfiguration,
    val benchmarks: List<JmhBenchmarkEvidence>,
) {
    /** Rejects incomplete identities and evidence that loses raw JMH coordinates. */
    fun validate(): JmhBenchmarkResultV1 = apply {
        require(schema == JMH_RESULT_SCHEMA_V1) { "Unsupported JMH result schema: $schema" }
        requireNonBlank("resultId", resultId)
        requireNonBlank("createdAt", createdAt)
        harness.validate()
        environment.validate()
        target.validate("target")
        workload.validate()
        configuration.validate()
        require(harness.adapters.singleOrNull { it.id == target.adapter.id } == target.adapter) {
            "target adapter identity must match exactly one harness adapter"
        }
        require(benchmarks.isNotEmpty()) { "benchmarks must not be empty" }
        require(benchmarks.all { it.forks == configuration.requestedForks }) {
            "configuration.requestedForks must match every effective benchmark fork count"
        }
        val benchmarkKeys = benchmarks.map(JmhBenchmarkEvidence::identityKey)
        require(benchmarkKeys.distinct().size == benchmarkKeys.size) {
            "benchmark name and parameter identities must be unique"
        }
        require(benchmarkKeys == benchmarkKeys.sorted()) {
            "benchmarks must be in canonical name and parameter order"
        }
        benchmarks.forEachIndexed { index, benchmark ->
            benchmark.validate("benchmarks[$index]", target.id)
        }
    }

    internal fun canonicalized(): JmhBenchmarkResultV1 =
        copy(
            configuration = configuration.canonicalized(),
            benchmarks =
                benchmarks
                    .map(JmhBenchmarkEvidence::canonicalized)
                    .sortedBy(JmhBenchmarkEvidence::identityKey),
        )
}

/** Content-addressed workload manifest used by every benchmark row in a JMH result. */
@JsonClass(generateAdapter = true)
data class JmhWorkloadIdentity(
    val manifestSha256: String,
    val manifest: WorkloadManifest,
) {
    internal fun validate() {
        requireSha256("workload.manifestSha256", manifestSha256)
        manifest.validate()
    }
}

/** Records the user-visible include, fork, profiler, and quick-mode request. */
@JsonClass(generateAdapter = true)
data class JmhRunConfiguration(
    val requestedIncludes: List<String>,
    val requestedForks: Int,
    val profilers: List<String>,
    val internalProfilers: List<String>,
    val quick: Boolean,
    val logging: JmhLoggingConfiguration,
) {
    internal fun validate() {
        require(requestedIncludes.isNotEmpty()) { "requestedIncludes must not be empty" }
        require(requestedIncludes.none(String::isBlank)) {
            "requestedIncludes must not contain blanks"
        }
        require(requestedIncludes == requestedIncludes.distinct().sorted()) {
            "requestedIncludes must be unique and sorted"
        }
        require(requestedForks > 0) { "requestedForks must be positive" }
        require(profilers.none(String::isBlank)) { "profilers must not contain blanks" }
        require(profilers == profilers.distinct().sorted()) {
            "profilers must be unique and sorted"
        }
        require(internalProfilers.isNotEmpty()) { "internalProfilers must not be empty" }
        require(internalProfilers.none(String::isBlank)) {
            "internalProfilers must not contain blanks"
        }
        require(internalProfilers == internalProfilers.distinct().sorted()) {
            "internalProfilers must be unique and sorted"
        }
        logging.validate()
    }

    internal fun canonicalized(): JmhRunConfiguration =
        copy(
            requestedIncludes = requestedIncludes.distinct().sorted(),
            profilers = profilers.distinct().sorted(),
            internalProfilers = internalProfilers.distinct().sorted(),
        )
}

/** Content identity of effective external logging configuration and output-suppression settings. */
@JsonClass(generateAdapter = true)
data class JmhLoggingConfiguration(
    val log4j2ConfigurationFileSha256: String,
    val log4j2GlobalConfigurationFileSha256: String,
    val kotlinLoggingStartupMessage: String,
    val revomanBanner: String,
) {
    internal fun validate() {
        requireSha256(
            "logging.log4j2ConfigurationFileSha256",
            log4j2ConfigurationFileSha256,
        )
        requireSha256(
            "logging.log4j2GlobalConfigurationFileSha256",
            log4j2GlobalConfigurationFileSha256,
        )
        requireNonBlank("logging.kotlinLoggingStartupMessage", kotlinLoggingStartupMessage)
        requireNonBlank("logging.revomanBanner", revomanBanner)
    }
}

/** One JMH benchmark/parameter row and all of its independently identified metric evidence. */
@JsonClass(generateAdapter = true)
data class JmhBenchmarkEvidence(
    val name: String,
    val parameters: Map<String, String>,
    val jmhVersion: String,
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
    val metricSeries: List<JmhMetricSeries>,
) {
    internal fun validate(path: String, targetId: String) {
        requireNonBlank("$path.name", name)
        require(parameters.keys.none(String::isBlank)) { "$path parameter names must not be blank" }
        requireNonBlank("$path.jmhVersion", jmhVersion)
        requireNonBlank("$path.mode", mode)
        require(threads > 0) { "$path.threads must be positive" }
        require(forks > 0) { "$path.forks must be positive" }
        requireNonBlank("$path.jvm", jvm)
        require(jvmArgs.none(String::isBlank)) { "$path.jvmArgs must not contain blanks" }
        requireNonBlank("$path.jdkVersion", jdkVersion)
        requireNonBlank("$path.vmName", vmName)
        requireNonBlank("$path.vmVersion", vmVersion)
        require(warmupIterations >= 0) { "$path.warmupIterations must not be negative" }
        requireNonBlank("$path.warmupTime", warmupTime)
        require(warmupBatchSize > 0) { "$path.warmupBatchSize must be positive" }
        require(measurementIterations > 0) { "$path.measurementIterations must be positive" }
        requireNonBlank("$path.measurementTime", measurementTime)
        require(measurementBatchSize > 0) { "$path.measurementBatchSize must be positive" }
        require(metricSeries.isNotEmpty()) { "$path.metricSeries must not be empty" }
        val seriesKeys = metricSeries.map { it.metric.ordinal to it.provider }
        require(seriesKeys.distinct().size == seriesKeys.size) {
            "$path metric/provider identities must be unique"
        }
        require(seriesKeys == seriesKeys.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })) {
            "$path.metricSeries must be in canonical metric and provider order"
        }
        metricSeries.forEachIndexed { index, series ->
            series.validate(
                path = "$path.metricSeries[$index]",
                targetId = targetId,
                forks = forks,
                measurementIterations = measurementIterations,
            )
        }
        val rawObservations = metricSeries.mapNotNull(JmhMetricSeries::rawObservations).flatten()
        if (rawObservations.isNotEmpty()) {
            val processIds =
                (0 until forks).map { fork ->
                    val forkProcessIds =
                        rawObservations
                            .asSequence()
                            .filter { it.fork == fork }
                            .map(MetricObservation::processId)
                            .distinct()
                            .toList()
                    require(forkProcessIds.size == 1) {
                        "$path fork $fork must have one stable process ID across metric series"
                    }
                    forkProcessIds.single()
                }
            require(processIds.distinct().size == forks) {
                "$path must have a distinct process ID for every fork"
            }
        }
    }

    internal fun canonicalized(): JmhBenchmarkEvidence =
        copy(
            metricSeries =
                metricSeries
                    .map(JmhMetricSeries::canonicalized)
                    .sortedWith(compareBy<JmhMetricSeries> { it.metric.ordinal }.thenBy { it.provider }),
        )

    internal fun identityKey(): String =
        name + "\u0000" + parameters.toSortedMap().entries.joinToString("\u0000") { "${it.key}=${it.value}" }
}

/** Raw coordinate evidence or an exact histogram for one JMH metric provider. */
@JsonClass(generateAdapter = true)
data class JmhMetricSeries(
    val metric: MetricId,
    val provider: String,
    val providerConfigurationSha256: String,
    val unit: MetricUnit,
    val rawObservations: List<MetricObservation>?,
    val exactHistogram: ExactHistogram?,
) {
    internal fun validate(
        path: String,
        targetId: String,
        forks: Int,
        measurementIterations: Int,
    ) {
        requireNonBlank("$path.provider", provider)
        requireSha256("$path.providerConfigurationSha256", providerConfigurationSha256)
        require((rawObservations == null) != (exactHistogram == null)) {
            "$path must contain exactly one of rawObservations or exactHistogram"
        }
        rawObservations?.let { observations ->
            require(observations.isNotEmpty()) { "$path.rawObservations must not be empty" }
            val expectedCoordinates =
                (0 until forks).flatMap { fork ->
                    (0 until measurementIterations).map { iteration -> fork to iteration }
                }
            val actualCoordinates = observations.map { it.fork to it.iteration }
            require(actualCoordinates == expectedCoordinates) {
                "$path raw observation coordinates must exactly cover every fork and iteration: " +
                    "expected=$expectedCoordinates, actual=$actualCoordinates"
            }
            observations.forEachIndexed { index, observation ->
                val observationPath = "$path.rawObservations[$index]"
                require(observation.targetId == targetId) {
                    "$observationPath.targetId must match the single JMH target"
                }
                require(observation.metric == metric) {
                    "$observationPath.metric must match parent series"
                }
                require(observation.provider == provider) {
                    "$observationPath.provider must match parent series"
                }
                require(observation.unit == unit) {
                    "$observationPath.unit must match parent series"
                }
                require(observation.processId > 0) { "$observationPath.processId must be positive" }
                requireFiniteNonNegative("$observationPath.value", observation.value)
                val perStepMetric = metric == MetricId.BYTES_PER_STEP
                require(perStepMetric || observation.executionCount == null) {
                    "$observationPath.executionCount is allowed only for BYTES_PER_STEP"
                }
                require(!perStepMetric || (observation.executionCount ?: 0) > 0) {
                    "$observationPath.executionCount is required and must be positive for BYTES_PER_STEP"
                }
                require(observation.replicateGroup == null) {
                    "$observationPath.replicateGroup is not JMH evidence"
                }
                require(observation.retainedEvidence == null) {
                    "$observationPath.retainedEvidence is not JMH evidence"
                }
            }
        }
        exactHistogram?.let { histogram ->
            require(histogram.targetId == targetId) {
                "$path.exactHistogram.targetId must match the single JMH target"
            }
            require(histogram.buckets.isNotEmpty()) {
                "$path.exactHistogram.buckets must not be empty"
            }
            require(histogram.buckets == histogram.buckets.sortedBy(HistogramBucket::value)) {
                "$path.exactHistogram.buckets must be in ascending value order"
            }
            histogram.buckets.forEachIndexed { index, bucket ->
                requireFiniteNonNegative("$path.exactHistogram.buckets[$index].value", bucket.value)
                require(bucket.count > 0) {
                    "$path.exactHistogram.buckets[$index].count must be positive"
                }
            }
        }
    }

    internal fun canonicalized(): JmhMetricSeries =
        copy(
            rawObservations =
                rawObservations?.sortedWith(
                    compareBy(MetricObservation::fork).thenBy(MetricObservation::iteration)
                ),
            exactHistogram =
                exactHistogram?.copy(buckets = exactHistogram.buckets.sortedBy(HistogramBucket::value)),
        )
}

internal const val JMH_RESULT_SCHEMA_V1: String = "revoman-benchmark-jmh/v1"

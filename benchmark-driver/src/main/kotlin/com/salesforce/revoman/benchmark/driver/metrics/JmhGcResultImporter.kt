/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.jmh.ImportedJmhBenchmark
import com.salesforce.revoman.benchmark.driver.jmh.JmhResultImporter
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

/** Raw warm allocation evidence with scheduler-owned coordinates preserved outside provider config. */
data class JmhGcAllocationImport(
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
    val provider: String,
    val providerConfigurationSha256: String,
    val observations: List<MetricObservation>,
)

/** Requires raw `gc.alloc.rate.norm` fork data from the lifecycle allocation benchmark. */
object JmhGcResultImporter {
    /** Imports exactly one controller launch and attaches the caller-supplied paired coordinates. */
    fun import(
        rawResult: Path,
        targetId: String,
        blockId: Int,
        targetRole: TargetRole,
        fork: Int,
    ): JmhGcAllocationImport {
        require(blockId >= 0) { "blockId must not be negative" }
        require(fork >= 0) { "fork must not be negative" }
        val imported =
            try {
                JmhResultImporter.`import`(
                    rawResult = rawResult,
                    targetId = targetId,
                    requestedIncludes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE),
                )
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: Throwable) {
                throw IllegalArgumentException(
                    "Warm allocation JMH evidence is malformed: $rawResult",
                    failure,
                )
            }
        val record =
            imported.records.singleOrNull()
                ?: throw IllegalArgumentException(
                    "Warm allocation JMH launch must produce exactly one benchmark row"
                )
        require(record.forks == 1) {
            "Warm allocation JMH launch must contain exactly one raw fork"
        }
        val allocation = record.observations.filter { it.metric == MetricId.ALLOCATED_BYTES }
        require(allocation.size == record.measurementIterations) {
            "JMH secondary metric gc.alloc.rate.norm must contain raw data for every iteration"
        }
        require(allocation.all { it.unit == MetricUnit.BYTES_PER_OPERATION }) {
            "JMH secondary metric gc.alloc.rate.norm must use B/op"
        }
        val providers = allocation.map(MetricObservation::provider).distinct()
        require(providers.size == 1) {
            "JMH secondary metric gc.alloc.rate.norm must have one provider identity"
        }
        val provider = providers.single()
        return JmhGcAllocationImport(
            blockId = blockId,
            targetRole = targetRole,
            fork = fork,
            provider = provider,
            providerConfigurationSha256 = providerConfigurationSha256(record, provider),
            observations = allocation.map { observation -> observation.copy(fork = fork) },
        )
    }
}

private fun providerConfigurationSha256(record: ImportedJmhBenchmark, provider: String): String =
    ContentHasher.sha256(
        buildList {
                add("revoman-warm-allocation-provider/v2")
                add(record.jmhVersion)
                add(record.benchmark)
                add(record.mode)
                add(record.threads.toString())
                add(record.forks.toString())
                add(record.jvm.substringAfterLast('/').substringAfterLast('\\'))
                add(record.jdkVersion)
                add(record.vmName)
                add(record.vmVersion)
                add(record.jvmArgs.size.toString())
                addAll(record.jvmArgs.map(::normalizedForkJvmArgument))
                add(record.parameters.size.toString())
                record.parameters.toSortedMap().forEach { (name, value) ->
                    add(name)
                    add(value)
                }
                add(record.warmupIterations.toString())
                add(record.warmupTime)
                add(record.warmupBatchSize.toString())
                add(record.measurementIterations.toString())
                add(record.measurementTime)
                add(record.measurementBatchSize.toString())
                add(provider)
                add(MetricUnit.BYTES_PER_OPERATION.name)
                add("gc")
            }
            .joinToString("\u0000")
            .toByteArray(UTF_8)
    )

internal const val WARM_LIFECYCLE_ALLOCATION_INCLUDE: String =
    "WarmLifecycleAllocationBenchmark"

private fun normalizedForkJvmArgument(argument: String): String =
    when {
        argument.startsWith("-javaagent:") -> {
            val options = argument.substringAfter('=', missingDelimiterValue = "")
            "-javaagent:<host-path>" + options.takeIf(String::isNotEmpty)?.let { "=$it" }.orEmpty()
        }
        argument.startsWith("-D") && '=' in argument -> {
            val name = argument.substring(2, argument.indexOf('='))
            val value = argument.substringAfter('=')
            when {
                name in TARGET_SPECIFIC_JVM_PROPERTIES -> "-D$name=<target-input>"
                name in OUTPUT_PATH_JVM_PROPERTIES -> "-D$name=<output-path>"
                name in FIXTURE_PATH_JVM_PROPERTIES -> "-D$name=<fixture-input>"
                name in LOGGING_PATH_JVM_PROPERTIES -> "-D$name=<logging-input>"
                name == "revoman.benchmark.lifecycleBaseUrl" -> "-D$name=<fixture-url>"
                value.startsWith('/') || value.startsWith("file:/") -> "-D$name=<host-path>"
                else -> argument
            }
        }
        else -> argument
    }

private val TARGET_SPECIFIC_JVM_PROPERTIES =
    setOf(
        "revoman.benchmark.targetManifest",
        "revoman.benchmark.targetToken",
        "revoman.benchmark.targetTokenSha256",
        "revoman.benchmark.adapter",
    )
private val OUTPUT_PATH_JVM_PROPERTIES =
    setOf("revoman.benchmark.rawJmhOutput", "revoman.benchmark.resultOutput")
private val FIXTURE_PATH_JVM_PROPERTIES =
    setOf("revoman.benchmark.fixtureRoot", "revoman.benchmark.installationRoot")
private val LOGGING_PATH_JVM_PROPERTIES =
    setOf("log4j2.configurationFile", "log4j2.*.Configuration.file")

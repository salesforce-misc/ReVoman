/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.cli

import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Stable, shell-free command model accepted by the benchmark driver. */
sealed interface BenchmarkCommand {
    data object ListWorkloads : BenchmarkCommand

    data class RunPaired internal constructor(val options: RunOptions) : BenchmarkCommand {
        val mode: RunMode get() = options.mode
        val intent: RunIntent get() = options.intent
        val baseline: Path get() = options.baseline
        val baselineAdapter: String get() = options.baselineAdapter
        val candidate: Path get() = options.candidate
        val candidateAdapter: String get() = options.candidateAdapter
        val workloadId: String get() = options.workloadId
        val blocks: Int get() = options.blocks
        val forksPerBlock: Int get() = options.forksPerBlock
        val warmups: Int get() = options.warmups
        val iterations: Int get() = options.iterations
        val seed: Long get() = options.seed
        val metricPasses: List<MetricPass> get() = options.metricPasses
        val hostPolicy: Path? get() = options.hostPolicy
        val artifactDirectory: Path get() = options.artifactDirectory
        val output: Path get() = options.output
    }

    data class CaptureBaseline internal constructor(val options: RunOptions) : BenchmarkCommand

    data class Compare(val input: Path, val outputJson: Path, val outputMarkdown: Path) : BenchmarkCommand

    data class Verify(val input: Path) : BenchmarkCommand
}

/** Exact argument values shared by paired execution and baseline capture. */
data class RunOptions(
    val mode: RunMode,
    val intent: RunIntent,
    val baseline: Path,
    val baselineAdapter: String,
    val candidate: Path,
    val candidateAdapter: String,
    val workloadId: String,
    val blocks: Int,
    val forksPerBlock: Int,
    val warmups: Int,
    val iterations: Int,
    val seed: Long,
    val metricPasses: List<MetricPass>,
    val hostPolicy: Path?,
    val artifactDirectory: Path,
    val output: Path,
)

/** User input error with stable process-level usage semantics. */
class CliUsageException(message: String) : IllegalArgumentException(message)

/** Parses already-separated argv values. It never invokes a shell or splits option values. */
object BenchmarkCli {
    fun parse(arguments: Array<String>): BenchmarkCommand {
        if (arguments.isEmpty()) throw CliUsageException(USAGE)
        return when (val command = arguments.first()) {
            "list-workloads" -> {
                requireNoOptions(command, arguments.drop(1))
                BenchmarkCommand.ListWorkloads
            }
            "run-paired" -> BenchmarkCommand.RunPaired(parseRun(arguments.drop(1)))
            "capture-baseline" -> BenchmarkCommand.CaptureBaseline(parseRun(arguments.drop(1)))
            "compare" -> parseCompare(parseOptions(command, arguments.drop(1)))
            "verify" -> parseVerify(parseOptions(command, arguments.drop(1)))
            else -> throw CliUsageException("Unknown command '$command'. $USAGE")
        }
    }

    private fun parseRun(arguments: List<String>): RunOptions {
        val values = parseOptions("paired run", arguments)
        requireOptions(values, RUN_REQUIRED)
        requireOnlyOptions(values, RUN_REQUIRED + "--host-policy")
        val mode = values.requiredEnum<RunMode>("--mode")
        val intent = values.requiredEnum<RunIntent>("--intent")
        val metrics =
            values.getValue("--metrics")
                .split(',')
                .map { token ->
                    when (token) {
                        "latency" -> MetricPass.LATENCY
                        "allocation" -> MetricPass.ALLOCATION
                        "peak-rss" -> MetricPass.PEAK_RSS
                        "retained" -> MetricPass.RETAINED
                        else -> throw CliUsageException("Unsupported metric '$token'")
                    }
                }
        if (metrics.isEmpty() || metrics.distinct().size != metrics.size) {
            throw CliUsageException("--metrics must contain distinct supported metrics")
        }
        val canonicalMetrics = metrics.sortedBy(Enum<*>::ordinal)
        validateModeMetrics(mode, canonicalMetrics)
        val blocks = values.positiveInt("--blocks")
        val forks = values.positiveInt("--forks-per-block")
        val warmups = values.nonNegativeInt("--warmups")
        val iterations = values.nonNegativeInt("--iterations")
        if (mode != RunMode.RETAINED && iterations == 0) {
            throw CliUsageException("--iterations must be positive for $mode")
        }
        if (mode == RunMode.RETAINED && iterations != 0) {
            throw CliUsageException("--iterations must be zero for RETAINED")
        }
        val hostPolicy = values["--host-policy"]?.let(Path::of)
        if (intent == RunIntent.CONTROLLED && hostPolicy == null) {
            throw CliUsageException("Controlled execution requires --host-policy")
        }
        val workloadId = values.nonBlank("--workload")
        if (!workloadId.matches(WORKLOAD_ID_PATTERN)) {
            throw CliUsageException("--workload must be one packaged workload ID")
        }
        return RunOptions(
            mode = mode,
            intent = intent,
            baseline = Path.of(values.getValue("--baseline")),
            baselineAdapter = values.nonBlank("--baseline-adapter"),
            candidate = Path.of(values.getValue("--candidate")),
            candidateAdapter = values.nonBlank("--candidate-adapter"),
            workloadId = workloadId,
            blocks = blocks,
            forksPerBlock = forks,
            warmups = warmups,
            iterations = iterations,
            seed =
                values.getValue("--seed").toLongOrNull()
                    ?: throw CliUsageException("--seed must be a signed 64-bit integer"),
            metricPasses = canonicalMetrics,
            hostPolicy = hostPolicy,
            artifactDirectory = Path.of(values.getValue("--artifacts-dir")),
            output = Path.of(values.getValue("--output")),
        )
    }

    private fun parseCompare(values: Map<String, String>): BenchmarkCommand.Compare {
        requireOptions(values, COMPARE_OPTIONS)
        requireOnlyOptions(values, COMPARE_OPTIONS)
        return BenchmarkCommand.Compare(
            input = Path.of(values.getValue("--input")),
            outputJson = Path.of(values.getValue("--output-json")),
            outputMarkdown = Path.of(values.getValue("--output-md")),
        )
    }

    private fun parseVerify(values: Map<String, String>): BenchmarkCommand.Verify {
        requireOptions(values, setOf("--input"))
        requireOnlyOptions(values, setOf("--input"))
        return BenchmarkCommand.Verify(Path.of(values.getValue("--input")))
    }

    private fun parseOptions(command: String, arguments: List<String>): Map<String, String> {
        if (arguments.size % 2 != 0) {
            throw CliUsageException("$command requires --option value pairs")
        }
        val pairs = arguments.chunked(2).map { pair ->
            val option = pair[0]
            if (!option.startsWith("--") || option.length == 2) {
                throw CliUsageException("Unexpected positional argument '$option'")
            }
            if (pair[1].isBlank()) throw CliUsageException("$option must not be blank")
            option to pair[1]
        }
        val duplicates = pairs.groupingBy(Pair<String, String>::first).eachCount().filterValues { it > 1 }
        if (duplicates.isNotEmpty()) {
            throw CliUsageException("Options must not be repeated: ${duplicates.keys.sorted()}")
        }
        return pairs.toMap()
    }

    private fun requireNoOptions(command: String, arguments: List<String>) {
        if (arguments.isNotEmpty()) throw CliUsageException("$command accepts no arguments")
    }

    private fun requireOptions(values: Map<String, String>, required: Set<String>) {
        val missing = required - values.keys
        if (missing.isNotEmpty()) throw CliUsageException("Missing required options: ${missing.sorted()}")
    }

    private fun requireOnlyOptions(values: Map<String, String>, supported: Set<String>) {
        val unknown = values.keys - supported
        if (unknown.isNotEmpty()) throw CliUsageException("Unknown options: ${unknown.sorted()}")
    }

    private inline fun <reified E : Enum<E>> Map<String, String>.requiredEnum(option: String): E {
        val normalized = getValue(option).replace('-', '_').uppercase()
        return enumValues<E>().singleOrNull { it.name == normalized }
            ?: throw CliUsageException("$option has unsupported value '${getValue(option)}'")
    }

    private fun Map<String, String>.positiveInt(option: String): Int =
        nonNegativeInt(option).also { value ->
            if (value == 0) throw CliUsageException("$option must be positive")
        }

    private fun Map<String, String>.nonNegativeInt(option: String): Int =
        (getValue(option).toIntOrNull()
                ?: throw CliUsageException("$option must be a 32-bit integer"))
            .also { value ->
                if (value < 0) throw CliUsageException("$option must not be negative")
            }

    private fun Map<String, String>.nonBlank(option: String): String =
        getValue(option).also { value ->
            if (value.isBlank()) throw CliUsageException("$option must not be blank")
        }

    private fun validateModeMetrics(mode: RunMode, metrics: List<MetricPass>) {
        val allowed =
            when (mode) {
                RunMode.COLD -> setOf(MetricPass.LATENCY, MetricPass.ALLOCATION, MetricPass.PEAK_RSS)
                RunMode.WARM -> setOf(MetricPass.LATENCY, MetricPass.ALLOCATION)
                RunMode.RETAINED -> setOf(MetricPass.RETAINED)
            }
        if (!allowed.containsAll(metrics)) {
            throw CliUsageException("$mode does not support metrics ${metrics.filterNot(allowed::contains)}")
        }
    }
}

/** Reserves an explicit absent campaign child beneath one canonical writable parent. */
object ArtifactDirectory {
    fun reserve(requested: Path): Path {
        val absolute = requested.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent) { "Artifact directory needs a parent: $requested" }
        require(Files.isDirectory(parent) && parent.toRealPath() == parent) {
            "Artifact directory parent must be an existing canonical directory: $parent"
        }
        require(Files.isWritable(parent)) { "Artifact directory parent must be writable: $parent" }
        require(!Files.exists(absolute, NOFOLLOW_LINKS)) {
            "Artifact directory must be absent: $absolute"
        }
        try {
            Files.createDirectory(absolute)
        } catch (failure: FileAlreadyExistsException) {
            throw IllegalArgumentException("Artifact directory must be absent: $absolute", failure)
        }
        return absolute.toRealPath().also { canonical ->
            check(canonical == absolute) { "Reserved artifact directory is not canonical: $absolute" }
        }
    }
}

private val RUN_REQUIRED =
    setOf(
        "--mode",
        "--intent",
        "--baseline",
        "--baseline-adapter",
        "--candidate",
        "--candidate-adapter",
        "--workload",
        "--blocks",
        "--forks-per-block",
        "--warmups",
        "--iterations",
        "--seed",
        "--metrics",
        "--artifacts-dir",
        "--output",
    )
private val COMPARE_OPTIONS = setOf("--input", "--output-json", "--output-md")
private const val USAGE: String =
    "Usage: benchmark-driver <list-workloads|run-paired|compare|verify|capture-baseline>"
private val WORKLOAD_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.metrics.FullGcProtocol
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RetainedCheckpoint
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import com.salesforce.revoman.benchmark.driver.model.requireExpectedExecutionDigest
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.TargetAdapterRegistry
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import kotlin.system.exitProcess
import kotlin.system.measureNanoTime

/** Executes one isolated target fork and communicates only through the atomic result file. */
fun main(arguments: Array<String>) {
    try {
        runTargetFork(arguments)
    } catch (_: Throwable) {
        exitProcess(1)
    }
}

private fun runTargetFork(arguments: Array<String>) {
    require(arguments.size == 1) { "Usage: TargetForkMain <command-file>" }
    val commandPath = normalizedAbsolutePath("Target command path", arguments.single())
    val command = BenchmarkJson.read<TargetForkCommand>(commandPath)
    validateMode(command)
    val expectedDigest =
        requireNotNull(command.expectedDigest) {
            "Target macro fork requires a non-null expectedDigest oracle"
        }
    val allocationRecording = allocationRecording(command)
    var runtime: TargetRuntime? = null
    var prepared: PreparedWorkload? = null
    var failure: Throwable? = null
    var result: TargetForkResult? = null
    try {
        allocationRecording?.recording?.start()
        val verified = VerifiedTargetManifest.fromWorkerCommand(command)
        runtime = TargetRuntime.open(verified)
        prepared = TargetAdapterRegistry.require(command.adapterId).prepare(runtime, command.workload)
        result = execute(command, prepared, expectedDigest)
        allocationRecording?.stopAndDump()
    } catch (primary: Throwable) {
        failure = primary
        throw primary
    } finally {
        failure = closeResource(prepared, failure)
        failure = closeResource(runtime, failure)
        failure = closeResource(allocationRecording?.recording, failure)
        if (failure != null && result != null) throw failure
    }
    val completed = requireNotNull(result)
    BenchmarkJson.write(
        Path.of(command.resultFile),
        completed.copy(jfrConfigurationSha256 = allocationRecording?.configurationSha256),
    )
}

private fun execute(
    command: TargetForkCommand,
    prepared: PreparedWorkload,
    expectedDigest: ExecutionDigest,
): TargetForkResult =
    when (command.mode) {
        RunMode.RETAINED -> retainedResult(command, prepared, expectedDigest)
        RunMode.COLD,
        RunMode.WARM,
        -> ordinaryResult(command, prepared, expectedDigest)
    }

private fun ordinaryResult(
    command: TargetForkCommand,
    prepared: PreparedWorkload,
    expectedDigest: ExecutionDigest,
): TargetForkResult {
    repeat(command.warmupIterations) { iteration ->
        requireExpectedExecutionDigest(
            actual = prepared.execute(),
            expected = expectedDigest,
            location = "warmup[$iteration]",
        )
    }
    val samples =
        List(command.measurementIterations) { iteration ->
            var digest: ExecutionDigest? = null
            val nanos = measureNanoTime { digest = prepared.execute() }
            val validated =
                requireExpectedExecutionDigest(
                    actual = requireNotNull(digest),
                    expected = expectedDigest,
                    location = "measurement[$iteration]",
                )
            TargetSample(iteration, nanos, validated)
        }
    return TargetForkResult(
        processId = ProcessHandle.current().pid(),
        warmupIterations = command.warmupIterations,
        measurementIterations = command.measurementIterations,
        samples = samples,
    )
}

private fun retainedResult(
    command: TargetForkCommand,
    prepared: PreparedWorkload,
    expectedDigest: ExecutionDigest,
): TargetForkResult {
    val executionCount = requireNotNull(command.retainedExecutionCount)
    repeat(executionCount) { iteration ->
        requireExpectedExecutionDigest(
            actual = prepared.execute(),
            expected = expectedDigest,
            location = "retained[$iteration]",
        )
    }
    val fakeToken = createFakeTokenReference()
    val fullGc = FullGcProtocol().sample()
    val weakReferences =
        listOf(
            WeakReferenceOutcome(
                type = CS1_FAKE_WEAK_REFERENCE_TYPE,
                created = 1,
                cleared = if (fakeToken.get() == null) 1 else 0,
            )
        )
    return TargetForkResult(
        processId = ProcessHandle.current().pid(),
        warmupIterations = 0,
        measurementIterations = 0,
        samples = emptyList(),
        retainedCheckpoint =
            RetainedCheckpoint(
                executionCount = executionCount,
                usedHeapBytes = fullGc.usedHeapBytes,
                completedGcCycles = fullGc.completedGcCycles,
                weakReferences = weakReferences,
            ),
    )
}

private fun createFakeTokenReference(): WeakReference<Cs1FakeExecutionToken> =
    WeakReference(Cs1FakeExecutionToken())

private fun allocationRecording(command: TargetForkCommand): AllocationRecording? {
    if (command.metricPass != MetricPass.ALLOCATION) return null
    val configurationPath =
        normalizedAbsolutePath(
            "JFR configuration path",
            requireNotNull(command.jfrConfigurationFile),
        )
    require(Files.isRegularFile(configurationPath)) {
        "JFR configuration path must identify a regular file: $configurationPath"
    }
    val recordingPath =
        normalizedAbsolutePath("JFR recording path", requireNotNull(command.jfrRecordingFile))
    require(Files.isDirectory(recordingPath.parent)) {
        "JFR recording parent must be an existing directory: ${recordingPath.parent}"
    }
    require(!Files.exists(recordingPath)) { "JFR recording path already exists: $recordingPath" }
    return AllocationRecording(
        recording = Recording(Configuration.create(configurationPath)),
        recordingPath = recordingPath,
        configurationSha256 = ContentHasher.sha256(configurationPath),
    )
}

private data class AllocationRecording(
    val recording: Recording,
    val recordingPath: Path,
    val configurationSha256: String,
) {
    fun stopAndDump() {
        recording.stop()
        recording.dump(recordingPath)
        require(Files.isRegularFile(recordingPath) && Files.size(recordingPath) > 0) {
            "JFR recording was not materialized: $recordingPath"
        }
    }
}

private fun closeResource(resource: AutoCloseable?, primary: Throwable?): Throwable? {
    if (resource == null) return primary
    return try {
        if (resource is Recording && resource.state == RecordingState.RUNNING) resource.stop()
        resource.close()
        primary
    } catch (closeFailure: Throwable) {
        primary?.also { existing ->
            if (existing !== closeFailure) existing.addSuppressed(closeFailure)
        } ?: closeFailure
    }
}

private fun validateMode(command: TargetForkCommand) {
    when (command.mode) {
        RunMode.COLD -> {
            require(command.metricPass in COLD_METRIC_PASSES) {
                "Cold target fork does not support ${command.metricPass}"
            }
            require(command.warmupIterations == 0) { "Cold forks cannot run warmup iterations" }
            require(command.measurementIterations == 1) {
                "Cold forks require exactly one measurement iteration"
            }
        }
        RunMode.WARM -> {
            require(command.metricPass == MetricPass.LATENCY) {
                "Warm target forks support only LATENCY; allocation uses lifecycle JMH"
            }
            require(command.measurementIterations > 0) {
                "Warm forks require at least one measurement iteration"
            }
        }
        RunMode.RETAINED -> {
            require(command.metricPass == MetricPass.RETAINED) {
                "Retained target forks require the RETAINED metric pass"
            }
            require(command.warmupIterations == 0 && command.measurementIterations == 0) {
                "Retained target forks require zero warmup and measurement iterations"
            }
            require((command.retainedExecutionCount ?: 0) > 0) {
                "Retained target forks require a positive execution count"
            }
        }
    }
}

private fun normalizedAbsolutePath(name: String, value: String): Path =
    Path.of(value).also { path ->
        require(path.isAbsolute && path.normalize() == path) {
            "$name must be absolute and normalized: $path"
        }
    }

private class Cs1FakeExecutionToken

private val COLD_METRIC_PASSES =
    setOf(MetricPass.LATENCY, MetricPass.ALLOCATION, MetricPass.PEAK_RSS)
private const val CS1_FAKE_WEAK_REFERENCE_TYPE: String = "Cs1FakeExecutionToken"

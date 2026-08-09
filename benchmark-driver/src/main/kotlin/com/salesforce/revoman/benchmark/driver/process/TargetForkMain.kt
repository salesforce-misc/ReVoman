/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import com.salesforce.revoman.benchmark.driver.target.TargetAdapterRegistry
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Path
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
    val commandPath = Path.of(arguments.single())
    require(commandPath.isAbsolute && commandPath.normalize() == commandPath) {
        "Target command path must be absolute and normalized"
    }
    val command = BenchmarkJson.read<TargetForkCommand>(commandPath)
    validateMode(command)
    val verified = VerifiedTargetManifest.fromWorkerCommand(command)
    TargetRuntime.open(verified).use { runtime ->
        TargetAdapterRegistry.require(command.adapterId).prepare(runtime, command.workload).use { prepared ->
            repeat(command.warmupIterations) { prepared.execute() }
            val samples =
                List(command.measurementIterations) { iteration ->
                    var digest: ExecutionDigest? = null
                    val nanos = measureNanoTime { digest = prepared.execute() }
                    TargetSample(iteration, nanos, requireNotNull(digest))
                }
            BenchmarkJson.write(
                Path.of(command.resultFile),
                TargetForkResult(
                    processId = ProcessHandle.current().pid(),
                    warmupIterations = command.warmupIterations,
                    measurementIterations = command.measurementIterations,
                    samples = samples,
                ),
            )
        }
    }
}

private fun validateMode(command: TargetForkCommand) {
    require(command.metricPass == MetricPass.LATENCY) {
        "Task 6 target forks support only the LATENCY metric pass"
    }
    when (command.mode) {
        RunMode.COLD -> {
            require(command.warmupIterations == 0) { "Cold forks cannot run warmup iterations" }
            require(command.measurementIterations == 1) {
                "Cold forks require exactly one measurement iteration"
            }
        }
        RunMode.WARM -> require(command.measurementIterations > 0) {
            "Warm forks require at least one measurement iteration"
        }
        RunMode.RETAINED -> error("Task 6 target forks do not support RETAINED mode")
    }
}

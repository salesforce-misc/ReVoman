/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import java.nio.file.Path
import java.time.Duration

/** Configuration for independent warm forks and their unrecorded warmup iterations. */
data class WarmPlan(
    val intent: RunIntent,
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val forksPerBlock: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val metricPass: MetricPass,
    val timeout: Duration,
    val loggingConfiguration: VerifiedLoggingConfiguration,
)

/** Runs one target process per warm fork and retains only measured target-reported iterations. */
class WarmRunner(private val launcher: ProcessLauncher) {
    /** Executes [plan] and returns only post-warmup per-execution measurements. */
    fun run(plan: WarmPlan): List<MetricObservation> {
        val expectedDigest = validate(plan)
        val campaign =
            RunnerCampaign.open(
                expectedTarget = plan.target,
                targetManifestPath = plan.targetManifestPath,
                loggingConfiguration = plan.loggingConfiguration,
            )
        return campaign.withPostflight {
            val observations =
                (0 until plan.forksPerBlock).flatMap { fork ->
                    val process =
                        campaign.launch(
                            launcher = launcher,
                            adapterId = plan.adapterId,
                            workload = plan.workload,
                            mode = RunMode.WARM,
                            metricPass = plan.metricPass,
                            expectedDigest = expectedDigest,
                            warmupIterations = plan.warmupIterations,
                            measurementIterations = plan.measurementIterations,
                            timeout = plan.timeout,
                        )
                    validateProcess(
                        process,
                        warmupIterations = plan.warmupIterations,
                        measurementIterations = plan.measurementIterations,
                        expectedDigest = expectedDigest,
                    )
                    process.result.samples.map { sample ->
                        MetricObservation(
                            targetId = plan.target.targetId,
                            metric = MetricId.LATENCY,
                            provider = WARM_LATENCY_PROVIDER,
                            unit = MetricUnit.NANOSECONDS,
                            fork = fork,
                            iteration = sample.iteration,
                            processId = process.processId,
                            value = sample.latencyNanos.toDouble(),
                        )
                    }
                }
            val forkPids = observations.groupBy(MetricObservation::fork).values.map { fork ->
                fork.map(MetricObservation::processId).distinct().singleOrNull()
                    ?: error("Each warm fork must have one stable process ID")
            }
            check(forkPids.distinct().size == forkPids.size) {
                "Warm forks require one distinct process per fork"
            }
            observations
        }
    }

    private fun validate(plan: WarmPlan): ExecutionDigest {
        require(plan.forksPerBlock > 0) { "Warm forksPerBlock must be positive" }
        require(plan.warmupIterations >= 0) { "Warm warmupIterations must not be negative" }
        require(plan.measurementIterations > 0) {
            "Warm measurementIterations must be positive"
        }
        validateCommon(plan.adapterId, plan.metricPass, plan.timeout)
        return requireMacroOracle(plan.expectedDigest)
    }
}

internal const val WARM_LATENCY_PROVIDER: String = "target-nano-time/v1"

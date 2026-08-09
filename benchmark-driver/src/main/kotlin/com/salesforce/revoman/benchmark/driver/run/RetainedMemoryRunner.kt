/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.metrics.FullGcProtocol
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RetainedEvidence
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import com.salesforce.revoman.benchmark.driver.process.ProcessObservation
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Duration

/** One scheduler-supplied retained replicate for one target inside one paired host block. */
data class RetainedMemoryPlan(
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
    val replicateGroup: Int,
    val timeout: Duration,
    val loggingConfiguration: VerifiedLoggingConfiguration,
)

/** Retained evidence carrying coordinates that Task 8/11 place into an alternating block. */
data class RetainedMemoryResult(
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
    val replicateGroup: Int,
    val provider: String,
    val providerConfigurationSha256: String,
    val observations: List<MetricObservation>,
)

/** Runs the fixed 1k/2k/4k retained checkpoints in three independent fresh JVMs. */
class RetainedMemoryRunner(private val launcher: ProcessLauncher) {
    /** Executes one caller-supplied retained replicate without owning block scheduling or rejection. */
    fun run(plan: RetainedMemoryPlan): RetainedMemoryResult {
        val expectedDigest = validate(plan)
        val campaign =
            RunnerCampaign.open(
                expectedTarget = plan.target,
                targetManifestPath = plan.targetManifestPath,
                loggingConfiguration = plan.loggingConfiguration,
            )
        return campaign.withPostflight {
            val observations =
                RETAINED_EXECUTION_COUNTS.mapIndexed { iteration, executionCount ->
                    val process =
                        campaign.launch(
                            launcher = launcher,
                            adapterId = plan.adapterId,
                            workload = plan.workload,
                            mode = RunMode.RETAINED,
                            metricPass = MetricPass.RETAINED,
                            expectedDigest = expectedDigest,
                            warmupIterations = 0,
                            measurementIterations = 0,
                            timeout = plan.timeout,
                            retainedExecutionCount = executionCount,
                        )
                    val checkpoint = validateRetainedProcess(process, executionCount)
                    MetricObservation(
                        targetId = plan.target.targetId,
                        metric = MetricId.RETAINED_BYTES,
                        provider = FullGcProtocol.PROVIDER_ID,
                        unit = MetricUnit.BYTES,
                        fork = plan.fork,
                        iteration = iteration,
                        replicateGroup = plan.replicateGroup,
                        processId = process.processId,
                        value = checkpoint.usedHeapBytes.toDouble(),
                        retainedEvidence =
                            RetainedEvidence(
                                executionCount = checkpoint.executionCount,
                                completedGcCycles = checkpoint.completedGcCycles,
                                weakReferences = checkpoint.weakReferences,
                            ),
                    )
                }
            requireDistinctProcessIds(observations, "retained 1k/2k/4k checkpoints")
            RetainedMemoryResult(
                blockId = plan.blockId,
                targetRole = plan.targetRole,
                fork = plan.fork,
                replicateGroup = plan.replicateGroup,
                provider = FullGcProtocol.PROVIDER_ID,
                providerConfigurationSha256 = RETAINED_PROVIDER_CONFIGURATION_SHA256,
                observations = observations,
            )
        }
    }

    private fun validate(plan: RetainedMemoryPlan): ExecutionDigest {
        require(plan.blockId >= 0) { "Retained blockId must not be negative" }
        require(plan.fork >= 0) { "Retained fork must not be negative" }
        require(plan.replicateGroup >= 0) { "Retained replicateGroup must not be negative" }
        validateCommon(plan.adapterId, plan.timeout)
        return requireMacroOracle(plan.expectedDigest)
    }
}

private fun validateRetainedProcess(
    process: ProcessObservation,
    executionCount: Int,
): com.salesforce.revoman.benchmark.driver.model.RetainedCheckpoint {
    check(process.exitCode == 0) { "Target process exited with exit code ${process.exitCode}" }
    check(process.processId > 0) { "Target process ID must be positive" }
    check(process.elapsedNanos >= 0) { "Target process elapsed time must not be negative" }
    check(process.stdoutTail.isEmpty()) { "Target process emitted stdout: ${process.stdoutTail}" }
    check(process.stderrTail.isEmpty()) { "Target process emitted stderr: ${process.stderrTail}" }
    val result = process.result
    check(result.protocolVersion == 1) {
        "Target result protocol version ${result.protocolVersion} is unsupported"
    }
    check(result.processId == process.processId) {
        "Target result PID ${result.processId} differs from process PID ${process.processId}"
    }
    check(result.warmupIterations == 0 && result.measurementIterations == 0) {
        "Retained target result must have zero warmup and measurement iterations"
    }
    check(result.samples.isEmpty()) { "Retained target result samples must be empty" }
    check(result.jfrConfigurationSha256 == null) {
        "Retained target result must not contain JFR configuration metadata"
    }
    return requireNotNull(result.retainedCheckpoint) {
            "Retained target result omitted its checkpoint"
        }
        .also { checkpoint ->
            check(checkpoint.executionCount == executionCount) {
                "Retained checkpoint execution count ${checkpoint.executionCount} differs from $executionCount"
            }
            check(checkpoint.usedHeapBytes >= 0) {
                "Retained checkpoint heap usage must not be negative"
            }
            check(checkpoint.completedGcCycles >= 2) {
                "Retained checkpoint requires two GC acknowledgements"
            }
            check(checkpoint.weakReferences.isNotEmpty()) {
                "Retained checkpoint requires weak-reference evidence"
            }
        }
}

internal val RETAINED_EXECUTION_COUNTS: List<Int> = listOf(1_000, 2_000, 4_000)
internal val RETAINED_PROVIDER_CONFIGURATION_SHA256: String =
    ContentHasher.sha256(
        buildList {
                add("revoman-retained-memory-provider/v1")
                add(FullGcProtocol.PROVIDER_CONFIGURATION_SHA256)
                addAll(RETAINED_EXECUTION_COUNTS.map(Int::toString))
                add("cs1-fixed-weak-reference-token/v1")
            }
            .joinToString("\u0000")
            .toByteArray(UTF_8)
    )

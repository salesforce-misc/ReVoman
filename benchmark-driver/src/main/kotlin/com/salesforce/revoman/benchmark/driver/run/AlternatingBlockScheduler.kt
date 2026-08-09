/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.host.HostGateEvent
import com.salesforce.revoman.benchmark.driver.host.HostGateEventSink
import com.salesforce.revoman.benchmark.driver.host.HostHealthGate
import com.salesforce.revoman.benchmark.driver.host.HostHealthProbe
import com.salesforce.revoman.benchmark.driver.host.HostIncompleteReason
import com.salesforce.revoman.benchmark.driver.host.HostProbePhase
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.intrinsicCoordinate
import com.salesforce.revoman.benchmark.driver.model.validateIntrinsic
import com.squareup.moshi.JsonClass
import org.apache.commons.math3.random.Well19937c

/** One paired block ID and its within-block target order. */
@JsonClass(generateAdapter = true)
data class TargetOrder(val blockId: Int, val targetIds: List<String>)

/** Produces a balanced deterministic order using the pinned Commons Math WELL generator. */
class AlternatingBlockScheduler(private val seed: Long) {
    /** Schedules both targets exactly once per block with first-position counts differing by at most one. */
    fun schedule(blocks: Int, baselineId: String, candidateId: String): List<TargetOrder> {
        require(blocks > 0) { "blocks must be positive" }
        require(baselineId.isNotBlank()) { "baselineId must not be blank" }
        require(candidateId.isNotBlank()) { "candidateId must not be blank" }
        require(baselineId != candidateId) { "baselineId and candidateId must be distinct" }
        val random = Well19937c(seed)
        val orders = mutableListOf<List<String>>()
        repeat(blocks / 2) {
            val baselineFirst = random.nextBoolean()
            val first =
                if (baselineFirst) listOf(baselineId, candidateId)
                else listOf(candidateId, baselineId)
            orders += first
            orders += first.reversed()
        }
        if (blocks % 2 == 1) {
            orders +=
                if (random.nextBoolean()) {
                    listOf(baselineId, candidateId)
                } else {
                    listOf(candidateId, baselineId)
                }
        }
        return orders.mapIndexed { blockId, targetIds -> TargetOrder(blockId, targetIds) }
    }
}

/** Coordinates supplied to Task 7/11 callbacks without reconstructing provider-specific schedules. */
data class ScheduledTarget(
    val blockId: Int,
    val targetId: String,
    val targetRole: TargetRole,
    val orderIndex: Int,
)

/** Executes all provider work owned by the caller for one target position in a paired block. */
fun interface PairedTargetExecutor {
    fun execute(target: ScheduledTarget): List<MetricObservation>
}

/** Terminal scheduling status consumed by Task 11's CLI/result assembly. */
enum class PairedBlockOutcome {
    COMPLETE,
    INCONCLUSIVE,
}

/** Health-bracketed blocks, including rejected evidence, before final campaign assembly. */
data class PairedBlockCampaign(
    val outcome: PairedBlockOutcome,
    val requestedAcceptedBlocks: Int,
    val blocks: List<AlternatingBlock>,
) {
    val acceptedBlocks: List<AlternatingBlock> = blocks.filter(AlternatingBlock::accepted)
    val rejectedBlocks: List<AlternatingBlock> = blocks.filterNot(AlternatingBlock::accepted)
}

/**
 * Runs paired target callbacks in scheduled order, rejects both observations from unhealthy blocks,
 * and bounds replacements without owning any metric runner or result-assembly policy.
 */
class PairedBlockOrchestrator(
    private val policy: ControlledHostPolicy,
    private val scheduler: AlternatingBlockScheduler,
    private val probe: HostHealthProbe,
    private val gate: HostHealthGate,
    private val eventSink: HostGateEventSink = HostGateEventSink.NoOp,
) {
    init {
        policy.validate()
        require(gate.policy == policy) { "HostHealthGate policy must match orchestrator policy" }
    }

    /** Runs until the accepted count is met or the explicit replacement budget is exhausted. */
    fun run(
        requestedAcceptedBlocks: Int,
        baselineId: String,
        candidateId: String,
        executor: PairedTargetExecutor,
    ): PairedBlockCampaign {
        require(requestedAcceptedBlocks > 0) { "requestedAcceptedBlocks must be positive" }
        val maximumAttempts =
            Math.addExact(requestedAcceptedBlocks, policy.maximumReplacementBlocks)
        val orders = scheduler.schedule(maximumAttempts, baselineId, candidateId)
        val roles = mapOf(baselineId to TargetRole.BASELINE, candidateId to TargetRole.CANDIDATE)
        val completed = mutableListOf<AlternatingBlock>()
        var acceptedCount = 0
        for (order in orders) {
            if (acceptedCount == requestedAcceptedBlocks) break
            if (order.blockId >= requestedAcceptedBlocks) {
                emit {
                    HostGateEvent.ReplacementScheduled(
                        blockId = order.blockId,
                        replacementNumber = order.blockId - requestedAcceptedBlocks + 1,
                    )
                }
            }
            val block = runBlock(order, roles, executor)
            completed += block
            if (block.accepted) {
                acceptedCount += 1
            } else {
                emit { HostGateEvent.BlockRejected(block.blockId, block.rejectionReasons) }
            }
        }
        val acceptedFirstPositions =
            completed.filter(AlternatingBlock::accepted).map { block -> block.targetOrder.first() }
        val baselineFirst = acceptedFirstPositions.count { targetId -> targetId == baselineId }
        val candidateFirst = acceptedFirstPositions.size - baselineFirst
        val balanced = kotlin.math.abs(baselineFirst - candidateFirst) <= 1
        val outcome =
            if (acceptedCount == requestedAcceptedBlocks && balanced) {
                PairedBlockOutcome.COMPLETE
            } else {
                PairedBlockOutcome.INCONCLUSIVE
            }
        if (outcome == PairedBlockOutcome.INCONCLUSIVE) {
            val reason =
                if (acceptedCount < requestedAcceptedBlocks) {
                    HostIncompleteReason.INSUFFICIENT_ACCEPTED_BLOCKS
                } else {
                    HostIncompleteReason.IMBALANCED_ACCEPTED_ORDER
                }
            emit {
                HostGateEvent.CampaignIncomplete(
                    requestedAcceptedBlocks = requestedAcceptedBlocks,
                    acceptedBlocks = acceptedCount,
                    attemptedBlocks = completed.size,
                    reason = reason,
                    baselineFirstBlocks = baselineFirst,
                    candidateFirstBlocks = candidateFirst,
                )
            }
        }
        return PairedBlockCampaign(outcome, requestedAcceptedBlocks, completed.toList())
    }

    private fun runBlock(
        order: TargetOrder,
        roles: Map<String, TargetRole>,
        executor: PairedTargetExecutor,
    ): AlternatingBlock {
        val before = samplePoint(order.blockId, HostProbePhase.BEFORE)
        val during = mutableListOf<HostHealthSnapshot>()
        val observationsByTarget = mutableListOf<Pair<ScheduledTarget, List<MetricObservation>>>()
        var primary: Throwable? = null
        var after: HostHealthSnapshot? = null
        try {
            order.targetIds.forEachIndexed { orderIndex, targetId ->
                val scheduled =
                    ScheduledTarget(
                        blockId = order.blockId,
                        targetId = targetId,
                        targetRole = requireNotNull(roles[targetId]),
                        orderIndex = orderIndex,
                    )
                val sampled =
                    try {
                        probe.sampleDuring {
                            try {
                                executor.execute(scheduled)
                            } catch (failure: Throwable) {
                                throw TargetCallbackFailure(failure)
                            }
                        }
                    } catch (failure: Throwable) {
                        throw unwrapDuringFailure(order.blockId, failure)
                    }
                validateCallbackEvidence(scheduled, sampled.value)
                observationsByTarget += scheduled to sampled.value
                during += sampled.snapshot
            }
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            try {
                after = samplePoint(order.blockId, HostProbePhase.AFTER)
            } catch (afterFailure: Throwable) {
                primary = mergeFailures(primary, afterFailure)
            }
        }
        primary?.let { throw it }
        val decision = gate.assess(before, during.toList(), requireNotNull(after))
        val acceptedObservations =
            if (decision.accepted) {
                observationsByTarget.flatMap { (_, observations) -> observations }
            } else {
                emptyList()
            }
        return AlternatingBlock(
            blockId = order.blockId,
            targetOrder = order.targetIds,
            healthBefore = before,
            healthDuring = during.toList(),
            healthAfter = requireNotNull(after),
            accepted = decision.accepted,
            rejectionReasons = decision.reasons,
            observations = acceptedObservations,
        )
    }

    private fun samplePoint(blockId: Int, phase: HostProbePhase): HostHealthSnapshot =
        try {
            probe.sample()
        } catch (failure: Throwable) {
            emitProbeFailure(blockId, phase, failure)
            throw failure
        }

    private fun unwrapDuringFailure(blockId: Int, failure: Throwable): Throwable =
        if (failure is TargetCallbackFailure) {
            failure.suppressed.forEach { samplingFailure ->
                emitProbeFailure(blockId, HostProbePhase.DURING, samplingFailure)
                if (failure.targetFailure !== samplingFailure) {
                    failure.targetFailure.addSuppressed(samplingFailure)
                }
            }
            failure.targetFailure
        } else {
            emitProbeFailure(blockId, HostProbePhase.DURING, failure)
            failure
        }

    private fun emitProbeFailure(
        blockId: Int,
        phase: HostProbePhase,
        failure: Throwable,
    ) {
        emit {
            HostGateEvent.ProbeFailed(
                blockId = blockId,
                phase = phase,
                detail = failure.message ?: failure.javaClass.name,
            )
        }
    }

    private fun emit(event: () -> HostGateEvent) {
        runCatching { eventSink.emit(event) }
    }

    private fun validateCallbackEvidence(
        target: ScheduledTarget,
        observations: List<MetricObservation>,
    ) {
        require(observations.isNotEmpty()) {
            "Block ${target.blockId} callback evidence for ${target.targetId} must not be empty"
        }
        observations.forEachIndexed { index, observation ->
            require(observation.targetId == target.targetId) {
                "Block ${target.blockId} callback returned evidence for the wrong target: " +
                    "expected=${target.targetId}, actual=${observation.targetId}"
            }
            observation.validateIntrinsic(
                "block[${target.blockId}].target[${target.targetId}].observations[$index]"
            )
        }
        val coordinates = observations.map(MetricObservation::intrinsicCoordinate)
        require(coordinates.distinct().size == coordinates.size) {
            "Block ${target.blockId} callback evidence for ${target.targetId} has duplicate coordinates"
        }
    }
}

private class TargetCallbackFailure(
    val targetFailure: Throwable,
) : RuntimeException(null, null, true, false)

private fun mergeFailures(primary: Throwable?, secondary: Throwable): Throwable =
    primary?.also { failure ->
        if (failure !== secondary) failure.addSuppressed(secondary)
    } ?: secondary

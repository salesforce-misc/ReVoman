/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.host.HostHealthGate
import com.salesforce.revoman.benchmark.driver.host.HostHealthProbe
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.TargetRole
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
        val baselineFirstCount =
            blocks / 2 + if (blocks % 2 == 1 && random.nextBoolean()) 1 else 0
        val orders =
            MutableList(blocks) { index ->
                if (index < baselineFirstCount) {
                    listOf(baselineId, candidateId)
                } else {
                    listOf(candidateId, baselineId)
                }
            }
        for (index in orders.lastIndex downTo 1) {
            val replacement = random.nextInt(index + 1)
            val current = orders[index]
            orders[index] = orders[replacement]
            orders[replacement] = current
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
            val block = runBlock(order, roles, executor)
            completed += block
            if (block.accepted) acceptedCount += 1
        }
        val outcome =
            if (acceptedCount == requestedAcceptedBlocks) {
                PairedBlockOutcome.COMPLETE
            } else {
                PairedBlockOutcome.INCONCLUSIVE
            }
        return PairedBlockCampaign(outcome, requestedAcceptedBlocks, completed.toList())
    }

    private fun runBlock(
        order: TargetOrder,
        roles: Map<String, TargetRole>,
        executor: PairedTargetExecutor,
    ): AlternatingBlock {
        val before = probe.sample()
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
                observationsByTarget += scheduled to executor.execute(scheduled)
                during += probe.sample()
            }
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            try {
                after = probe.sample()
            } catch (afterFailure: Throwable) {
                primary = mergeFailures(primary, afterFailure)
            }
        }
        primary?.let { throw it }
        val decision = gate.assess(before, during.toList(), requireNotNull(after))
        val acceptedObservations =
            if (decision.accepted) {
                observationsByTarget.flatMap { (target, observations) ->
                    require(observations.all { observation -> observation.targetId == target.targetId }) {
                        "Accepted block ${order.blockId} callback returned an observation for the wrong target"
                    }
                    observations
                }
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
}

private fun mergeFailures(primary: Throwable?, secondary: Throwable): Throwable =
    primary?.also { failure ->
        if (failure !== secondary) failure.addSuppressed(secondary)
    } ?: secondary

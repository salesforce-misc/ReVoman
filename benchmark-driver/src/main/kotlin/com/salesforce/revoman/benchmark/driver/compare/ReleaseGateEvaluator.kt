/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricSeries
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.model.WorkloadResult
import com.salesforce.revoman.benchmark.driver.stats.RatioInterval
import com.salesforce.revoman.benchmark.driver.stats.SlopeInterval
import com.salesforce.revoman.benchmark.driver.stats.Statistic
import com.salesforce.revoman.benchmark.driver.stats.hierarchicalRatioInterval
import com.salesforce.revoman.benchmark.driver.stats.pairedHierarchyFromAcceptedBlocks
import com.salesforce.revoman.benchmark.driver.stats.retainedHierarchyFromAcceptedBlocks
import com.salesforce.revoman.benchmark.driver.stats.retainedSlopeInterval

/** One explicitly requested improvement claim, independent of normative workload gates. */
data class TargetedClaim(
    val mode: RunMode,
    val metric: MetricId,
    val statistic: Statistic,
)

/** Evaluates controlled evidence under exact release boundaries. */
class ReleaseGateEvaluator(
    private val policy: RegressionPolicy = RegressionPolicy(),
    private val resamples: Int = 10_000,
) {
    init {
        require(resamples > 0) { "resamples must be positive" }
    }

    /** Produces a canonical machine report without reading target manifests or artifact paths. */
    fun evaluate(
        result: BenchmarkResultV1,
        workloadManifests: List<WorkloadManifest>,
        targetedClaims: List<TargetedClaim> = emptyList(),
    ): ComparisonReport {
        require(targetedClaims.map { it.key() }.distinct().size == targetedClaims.size) {
            "Targeted claims must be unique by mode, metric, and statistic"
        }
        val compatibilityErrors = ResultCompatibility.errors(result, workloadManifests)
        val rejectedBlocks = rejectedEvidence(result)
        if (compatibilityErrors.isNotEmpty()) {
            return ComparisonReport(
                    campaignId = result.campaignId,
                    compatibilityErrors = compatibilityErrors,
                    metrics = emptyList(),
                    rejectedBlocks = rejectedBlocks,
                    overall = GateDecision.INCOMPATIBLE,
                )
                .canonicalized()
                .validate()
        }

        val manifestsById = workloadManifests.associateBy(WorkloadManifest::id)
        val normative =
            result.workloads.flatMap { workload ->
                val manifest = requireNotNull(manifestsById[workload.id])
                manifest.requiredGatesByMode.getValue(workload.mode).map { gate ->
                    evaluateGate(result, workload, gate)
                }
            }
        val targeted =
            targetedClaims.map { claim ->
                evaluateTargetedClaim(result, claim)
            }
        val intentAdjusted =
            (normative + targeted).map { decision ->
                when (result.intent) {
                    RunIntent.CONTROLLED -> decision
                    RunIntent.SMOKE,
                    RunIntent.CI_SELF_TEST,
                    ->
                        decision.copy(
                            decision = GateDecision.INCONCLUSIVE,
                            reason = "${result.intent} evidence cannot satisfy release gates",
                        )
                }
            }
        val overall = overallDecision(result.intent, intentAdjusted)
        return ComparisonReport(
                campaignId = result.campaignId,
                compatibilityErrors = emptyList(),
                metrics = intentAdjusted,
                rejectedBlocks = rejectedBlocks,
                overall = overall,
            )
            .canonicalized()
            .validate()
    }

    private fun evaluateGate(
        result: BenchmarkResultV1,
        workload: WorkloadResult,
        gate: GateId,
    ): MetricDecision {
        val descriptor = gateDescriptor(gate)
        if (workload.mode != descriptor.mode) {
            return descriptor.unavailable(gate, "gate $gate does not apply to ${workload.mode}")
        }
        return when (gate) {
            GateId.RETAINED_SLOPE -> evaluateRetained(result, workload, descriptor)
            GateId.PER_STEP_ALLOCATION_SPREAD -> evaluatePerStep(result, workload, descriptor)
            else -> evaluateRatio(result, workload, descriptor, gate, ClaimKind.NON_REGRESSION)
        }
    }

    private fun evaluateTargetedClaim(
        result: BenchmarkResultV1,
        claim: TargetedClaim,
    ): MetricDecision {
        val limit =
            when (claim.mode) {
                RunMode.COLD -> policy.coldImprovementUpper
                RunMode.WARM -> policy.warmImprovementUpper
                RunMode.RETAINED ->
                    return GateDescriptor(
                            claim.mode,
                            claim.metric,
                            claim.statistic,
                            policy.warmImprovementUpper,
                            ClaimKind.TARGETED_IMPROVEMENT,
                        )
                        .unavailable(null, "targeted ratio claims do not support RETAINED mode")
            }
        val descriptor =
            GateDescriptor(
                mode = claim.mode,
                metric = claim.metric,
                statistic = claim.statistic,
                limit = limit,
                claimKind = ClaimKind.TARGETED_IMPROVEMENT,
            )
        if (result.configuration.mode != claim.mode) {
            return descriptor.unavailable(null, "targeted claim mode does not match campaign mode")
        }
        val workload = result.workloads.singleOrNull { candidate ->
            candidate.metricSeries.any { it.metric == claim.metric }
        } ?: return descriptor.unavailable(null, "targeted metric ${claim.metric} is missing")
        return evaluateRatio(result, workload, descriptor, null, ClaimKind.TARGETED_IMPROVEMENT)
    }

    private fun evaluateRatio(
        result: BenchmarkResultV1,
        workload: WorkloadResult,
        descriptor: GateDescriptor,
        gate: GateId?,
        claimKind: ClaimKind,
    ): MetricDecision {
        val series = workload.metricSeries.singleOrNull { it.metric == descriptor.metric }
            ?: return descriptor.unavailable(gate, "metric ${descriptor.metric} is missing")
        val rawBlocks = series.blocks
            ?: return descriptor.unavailable(gate, "metric ${descriptor.metric} lacks raw blocks")
        val accepted = rawBlocks.filter { it.accepted }
        val baselineId = result.targetId(TargetRole.BASELINE)
        val candidateId = result.targetId(TargetRole.CANDIDATE)
        val interval =
            runCatching {
                    hierarchicalRatioInterval(
                        samples =
                            pairedHierarchyFromAcceptedBlocks(
                                accepted,
                                baselineId,
                                candidateId,
                                workload.mode,
                            ),
                        statistic = requireNotNull(descriptor.statistic),
                        resamples = resamples,
                        seed = result.configuration.seed,
                    )
                }
                .getOrElse { failure ->
                    return descriptor.unavailable(
                        gate,
                        "metric ${descriptor.metric} is not statistically usable: ${failure.message}",
                    )
                }
        val sufficient = sufficientIndependentProcesses(workload.mode, accepted, baselineId, candidateId)
        val decision =
            when {
                !sufficient -> GateDecision.INCONCLUSIVE
                interval.upper95 <= descriptor.limit -> GateDecision.PASS
                else -> GateDecision.FAIL
            }
        val reason =
            when (decision) {
                GateDecision.PASS -> "upper95 ${interval.upper95} is at most ${descriptor.limit}"
                GateDecision.FAIL -> "upper95 ${interval.upper95} exceeds ${descriptor.limit}"
                GateDecision.INCONCLUSIVE -> independentProcessReason(workload.mode)
                GateDecision.INCOMPATIBLE -> error("compatibility is handled before ratio evaluation")
            }
        return MetricDecision(
            gate = gate,
            claimKind = claimKind,
            mode = descriptor.mode,
            metric = descriptor.metric,
            statistic = descriptor.statistic,
            interval = interval,
            slopeInterval = null,
            observedValue = null,
            limit = descriptor.limit,
            decision = decision,
            reason = reason,
        )
    }

    private fun evaluateRetained(
        result: BenchmarkResultV1,
        workload: WorkloadResult,
        descriptor: GateDescriptor,
    ): MetricDecision {
        val series = workload.metricSeries.singleOrNull { it.metric == MetricId.RETAINED_BYTES }
            ?: return descriptor.unavailable(GateId.RETAINED_SLOPE, "retained metric is missing")
        val accepted = series.blocks?.filter { it.accepted }
            ?: return descriptor.unavailable(GateId.RETAINED_SLOPE, "retained raw blocks are missing")
        val baselineId = result.targetId(TargetRole.BASELINE)
        val candidateId = result.targetId(TargetRole.CANDIDATE)
        if (!hasFreshRetainedReplicates(accepted, setOf(baselineId, candidateId))) {
            return descriptor.unavailable(
                GateId.RETAINED_SLOPE,
                "retained evidence requires at least five fresh replicate groups per role",
            )
        }
        val interval =
            runCatching {
                    retainedSlopeInterval(
                        samples =
                            retainedHierarchyFromAcceptedBlocks(
                                accepted,
                                baselineId,
                                candidateId,
                            ),
                        targetRole = TargetRole.CANDIDATE,
                        resamples = resamples,
                        seed = result.configuration.seed,
                    )
                }
                .getOrElse { failure ->
                    return descriptor.unavailable(
                        GateId.RETAINED_SLOPE,
                        "retained evidence is insufficient: ${failure.message}",
                    )
                }
        val candidateObservations =
            accepted.flatMap { block -> block.observations.filter { it.targetId == candidateId } }
        val containsRuntimeTypes =
            candidateObservations.isNotEmpty() &&
                candidateObservations.all { observation ->
                    val types = requireNotNull(observation.retainedEvidence).weakReferences.map { it.type }.toSet()
                    RETAINED_RUNTIME_TYPES.all(types::contains)
                }
        val allCleared =
            candidateObservations
                .flatMap { requireNotNull(it.retainedEvidence).weakReferences }
                .all { outcome -> outcome.cleared == outcome.created }
        val decision =
            when {
                !containsRuntimeTypes -> GateDecision.INCONCLUSIVE
                !allCleared -> GateDecision.FAIL
                interval.upper95BytesPerExecution <= descriptor.limit -> GateDecision.PASS
                else -> GateDecision.FAIL
            }
        val reason =
            when {
                !containsRuntimeTypes ->
                    "candidate requires ExecutionSession and KickExecution at every checkpoint"
                !allCleared -> "candidate retained weak references did not all clear"
                decision == GateDecision.PASS ->
                    "upper95 retained slope ${interval.upper95BytesPerExecution} is at most ${descriptor.limit}"
                else ->
                    "upper95 retained slope ${interval.upper95BytesPerExecution} exceeds ${descriptor.limit}"
            }
        return descriptor.slopeDecision(GateId.RETAINED_SLOPE, interval, decision, reason)
    }

    private fun evaluatePerStep(
        result: BenchmarkResultV1,
        workload: WorkloadResult,
        descriptor: GateDescriptor,
    ): MetricDecision {
        val series = workload.metricSeries.singleOrNull { it.metric == MetricId.BYTES_PER_STEP }
            ?: return descriptor.unavailable(
                GateId.PER_STEP_ALLOCATION_SPREAD,
                "per-step allocation metric is missing",
            )
        val accepted = series.blocks?.filter { it.accepted }
            ?: return descriptor.unavailable(
                GateId.PER_STEP_ALLOCATION_SPREAD,
                "per-step allocation raw blocks are missing",
            )
        val targetIds = TargetRole.entries.map { role -> result.targetId(role) }.toSet()
        val groups =
            accepted.flatMap { block ->
                block.observations.groupBy { observation -> observation.targetId to observation.fork }
                    .map { (targetAndFork, observations) ->
                        PerStepGroup(block.blockId, targetAndFork.first, targetAndFork.second, observations)
                    }
            }
        val exactShape =
            groups.isNotEmpty() &&
                groups.all { group ->
                    group.targetId in targetIds &&
                        group.observations.map { it.executionCount }.sortedBy { it } ==
                            PER_STEP_COUNTS.map(Int::toInt)
                }
        val candidateId = result.targetId(TargetRole.CANDIDATE)
        val candidateGroups = groups.filter { it.targetId == candidateId }
        if (!exactShape || candidateGroups.size < MINIMUM_INDEPENDENT_PROCESSES) {
            return descriptor.unavailable(
                GateId.PER_STEP_ALLOCATION_SPREAD,
                "per-step allocation requires 800, 1600, and 3200 for at least five candidate replicates",
            )
        }
        val normalized =
            candidateGroups.flatMap { group ->
                group.observations.map { observation ->
                    observation.value / requireNotNull(observation.executionCount)
                }
            }
        val minimum = normalized.min()
        if (minimum == 0.0) {
            return descriptor.unavailable(
                GateId.PER_STEP_ALLOCATION_SPREAD,
                "per-step normalized allocation minimum must be positive",
            )
        }
        val spread = normalized.max() / minimum
        val decision =
            if (spread <= descriptor.limit) GateDecision.PASS else GateDecision.FAIL
        val reason =
            if (decision == GateDecision.PASS) {
                "normalized allocation spread $spread is at most ${descriptor.limit}"
            } else {
                "normalized allocation spread $spread exceeds ${descriptor.limit}"
            }
        return descriptor.observedDecision(
            GateId.PER_STEP_ALLOCATION_SPREAD,
            spread,
            decision,
            reason,
        )
    }

    private fun gateDescriptor(gate: GateId): GateDescriptor =
        when (gate) {
            GateId.COLD_MEDIAN -> ratioDescriptor(RunMode.COLD, MetricId.LATENCY, Statistic.MEDIAN, policy.coldMedianUpper)
            GateId.COLD_P95 -> ratioDescriptor(RunMode.COLD, MetricId.LATENCY, Statistic.P95, policy.coldP95Upper)
            GateId.COLD_ALLOCATION -> ratioDescriptor(RunMode.COLD, MetricId.ALLOCATED_BYTES, Statistic.MEAN, policy.coldAllocationUpper)
            GateId.COLD_PEAK_RSS -> ratioDescriptor(RunMode.COLD, MetricId.PEAK_RSS, Statistic.MEAN, policy.coldPeakRssUpper)
            GateId.WARM_MEDIAN -> ratioDescriptor(RunMode.WARM, MetricId.LATENCY, Statistic.MEDIAN, policy.warmMedianUpper)
            GateId.WARM_P95 -> ratioDescriptor(RunMode.WARM, MetricId.LATENCY, Statistic.P95, policy.warmP95Upper)
            GateId.WARM_ALLOCATION -> ratioDescriptor(RunMode.WARM, MetricId.ALLOCATED_BYTES, Statistic.MEAN, policy.warmAllocationUpper)
            GateId.RETAINED_SLOPE -> GateDescriptor(RunMode.RETAINED, MetricId.RETAINED_BYTES, null, policy.retainedSlopeUpperBytes, ClaimKind.STRUCTURAL)
            GateId.PER_STEP_ALLOCATION_SPREAD -> GateDescriptor(RunMode.RETAINED, MetricId.BYTES_PER_STEP, null, policy.perStepAllocationSpreadUpper, ClaimKind.STRUCTURAL)
        }

    private fun ratioDescriptor(
        mode: RunMode,
        metric: MetricId,
        statistic: Statistic,
        limit: Double,
    ): GateDescriptor = GateDescriptor(mode, metric, statistic, limit, ClaimKind.NON_REGRESSION)

    private fun rejectedEvidence(result: BenchmarkResultV1): List<RejectedBlockEvidence> =
        result.workloads.flatMap { workload ->
            workload.metricSeries.flatMap { series ->
                series.blocks.orEmpty().filterNot { it.accepted }.map { block ->
                    RejectedBlockEvidence(
                        workloadId = workload.id,
                        metric = series.metric,
                        blockId = block.blockId,
                        reasons = block.rejectionReasons,
                    )
                }
            }
        }

    private fun overallDecision(
        intent: RunIntent,
        decisions: List<MetricDecision>,
    ): GateDecision =
        when {
            intent != RunIntent.CONTROLLED -> GateDecision.INCONCLUSIVE
            decisions.any { it.decision == GateDecision.INCONCLUSIVE } -> GateDecision.INCONCLUSIVE
            decisions.any { it.decision == GateDecision.FAIL } -> GateDecision.FAIL
            else -> GateDecision.PASS
        }

    private fun sufficientIndependentProcesses(
        mode: RunMode,
        blocks: List<com.salesforce.revoman.benchmark.driver.model.AlternatingBlock>,
        baselineId: String,
        candidateId: String,
    ): Boolean {
        val minimum =
            when (mode) {
                RunMode.COLD -> MINIMUM_COLD_PROCESSES
                RunMode.WARM -> MINIMUM_INDEPENDENT_PROCESSES
                RunMode.RETAINED -> return false
            }
        return listOf(baselineId, candidateId).all { targetId ->
            blocks
                .flatMap { block -> block.observations.filter { it.targetId == targetId } }
                .map(MetricObservation::processId)
                .distinct()
                .size >= minimum
        }
    }

    private fun independentProcessReason(mode: RunMode): String =
        when (mode) {
            RunMode.COLD -> "cold release gates require at least 50 unique fresh processes per role"
            RunMode.WARM -> "warm release gates require at least five independent forks per role"
            RunMode.RETAINED -> "retained evidence uses structural gates"
        }

    private fun hasFreshRetainedReplicates(
        blocks: List<com.salesforce.revoman.benchmark.driver.model.AlternatingBlock>,
        targetIds: Set<String>,
    ): Boolean =
        targetIds.all { targetId ->
            val observationsByBlock =
                blocks.associate { block ->
                    block.blockId to block.observations.filter { it.targetId == targetId }
                }
            val observations = observationsByBlock.values.flatten()
            observationsByBlock.values.count { blockObservations ->
                blockObservations.mapNotNull(MetricObservation::replicateGroup).distinct().size == 1
            } >= MINIMUM_INDEPENDENT_PROCESSES &&
                observations.map(MetricObservation::processId).distinct().size == observations.size
        }

    private fun BenchmarkResultV1.targetId(role: TargetRole): String =
        configuration.targets.single { it.role == role }.targetId

    private fun TargetedClaim.key(): Triple<RunMode, MetricId, Statistic> = Triple(mode, metric, statistic)

    private data class GateDescriptor(
        val mode: RunMode,
        val metric: MetricId,
        val statistic: Statistic?,
        val limit: Double,
        val claimKind: ClaimKind,
    ) {
        fun unavailable(gate: GateId?, reason: String): MetricDecision =
            MetricDecision(
                gate = gate,
                claimKind = claimKind,
                mode = mode,
                metric = metric,
                statistic = statistic,
                interval = null,
                slopeInterval = null,
                observedValue = null,
                limit = limit,
                decision = GateDecision.INCONCLUSIVE,
                reason = reason,
            )

        fun slopeDecision(
            gate: GateId,
            interval: SlopeInterval,
            decision: GateDecision,
            reason: String,
        ): MetricDecision =
            MetricDecision(
                gate = gate,
                claimKind = claimKind,
                mode = mode,
                metric = metric,
                statistic = null,
                interval = null,
                slopeInterval = interval,
                observedValue = null,
                limit = limit,
                decision = decision,
                reason = reason,
            )

        fun observedDecision(
            gate: GateId,
            observedValue: Double,
            decision: GateDecision,
            reason: String,
        ): MetricDecision =
            MetricDecision(
                gate = gate,
                claimKind = claimKind,
                mode = mode,
                metric = metric,
                statistic = null,
                interval = null,
                slopeInterval = null,
                observedValue = observedValue,
                limit = limit,
                decision = decision,
                reason = reason,
            )
    }

    private data class PerStepGroup(
        val blockId: Int,
        val targetId: String,
        val fork: Int,
        val observations: List<MetricObservation>,
    )

    private companion object {
        const val MINIMUM_COLD_PROCESSES: Int = 50
        const val MINIMUM_INDEPENDENT_PROCESSES: Int = 5
        val PER_STEP_COUNTS = listOf(800, 1_600, 3_200)
        val RETAINED_RUNTIME_TYPES = setOf("ExecutionSession", "KickExecution")
    }
}

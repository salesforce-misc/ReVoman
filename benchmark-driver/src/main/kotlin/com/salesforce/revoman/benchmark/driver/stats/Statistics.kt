/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.stats

import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import com.squareup.moshi.JsonClass
import kotlin.math.abs
import kotlin.math.floor

/** Selects the sample statistic applied after each complete hierarchical resample. */
enum class Statistic {
    MEDIAN,
    P95,
    MEAN,
}

/** Preserves all measured iterations belonging to one independent process fork. */
data class ForkSeries(val fork: Int, val values: List<Double>)

/** Preserves both target roles inside one accepted host block. */
data class PairedBlockSamples(
    val blockId: Int,
    val baselineForks: List<ForkSeries>,
    val candidateForks: List<ForkSeries>,
)

/** Complete accepted hierarchy used by candidate-over-baseline statistics. */
data class PairedHierarchy(val blocks: List<PairedBlockSamples>)

/** One retained-heap checkpoint at a fixed execution count. */
data class RetainedPoint(val executionCount: Int, val retainedBytes: Double)

/** One independent retained-memory replicate and all of its reachability evidence. */
data class RetainedReplicate(
    val blockId: Int,
    val targetId: String,
    val replicateGroup: Int,
    val points: List<RetainedPoint>,
    val weakReferences: List<WeakReferenceOutcome>,
)

/** Preserves retained-memory replicates for both roles inside one accepted host block. */
data class RetainedBlockSamples(
    val blockId: Int,
    val baselineReplicates: List<RetainedReplicate>,
    val candidateReplicates: List<RetainedReplicate>,
)

/** Complete accepted retained-memory hierarchy. */
data class RetainedHierarchy(val blocks: List<RetainedBlockSamples>)

/** Candidate-over-baseline point estimate and central bootstrap interval. */
@JsonClass(generateAdapter = true)
data class RatioInterval(
    val pointEstimate: Double,
    val lower95: Double,
    val upper95: Double,
)

/** Retained-byte slope point estimate and central bootstrap interval. */
@JsonClass(generateAdapter = true)
data class SlopeInterval(
    val pointEstimateBytesPerExecution: Double,
    val lower95BytesPerExecution: Double,
    val upper95BytesPerExecution: Double,
)

/** Computes the type-seven sample quantile without accepting malformed statistical evidence. */
internal fun r7Quantile(values: List<Double>, probability: Double): Double {
    require(values.isNotEmpty()) { "R7 values must not be empty" }
    require(values.all(Double::isFinite)) { "R7 values must be finite" }
    require(probability.isFinite() && probability in 0.0..1.0) {
        "R7 probability must be finite and between zero and one"
    }
    val sorted = values.sorted()
    if (probability == 0.0 || sorted.size == 1) return sorted.first()
    if (probability == 1.0) return sorted.last()
    val h = 1.0 + (sorted.size - 1) * probability
    val oneBasedLower = floor(h).toInt()
    val fraction = h - oneBasedLower
    val lower = sorted[oneBasedLower - 1]
    val upper = sorted[oneBasedLower]
    val quantile = (1.0 - fraction) * lower + fraction * upper
    require(quantile.isFinite()) { "R7 interpolation produced a non-finite quantile" }
    return quantile
}

internal fun Statistic.calculate(values: List<Double>): Double {
    require(values.isNotEmpty()) { "Statistic values must not be empty" }
    require(values.all(Double::isFinite)) { "Statistic values must be finite" }
    val result =
        when (this) {
            Statistic.MEDIAN -> r7Quantile(values, 0.5)
            Statistic.P95 -> r7Quantile(values, 0.95)
            Statistic.MEAN -> stableMean(values)
        }
    require(result.isFinite()) { "$this produced a non-finite statistic" }
    return result
}

/**
 * Converts accepted raw observations to the statistical hierarchy without taking ownership of
 * campaign assembly or rejected-block filtering.
 */
fun pairedHierarchyFromAcceptedBlocks(
    blocks: List<AlternatingBlock>,
    baselineTargetId: String,
    candidateTargetId: String,
    mode: RunMode,
): PairedHierarchy {
    validateRawBlocks(blocks, baselineTargetId, candidateTargetId)
    require(mode != RunMode.RETAINED) {
        "Retained observations require retainedHierarchyFromAcceptedBlocks"
    }
    return PairedHierarchy(
            blocks.map { block ->
                PairedBlockSamples(
                    blockId = block.blockId,
                    baselineForks =
                        observationsToForks(
                            block.observations.filter { it.targetId == baselineTargetId },
                            mode,
                            "block ${block.blockId} baseline",
                        ),
                    candidateForks =
                        observationsToForks(
                            block.observations.filter { it.targetId == candidateTargetId },
                            mode,
                            "block ${block.blockId} candidate",
                        ),
                )
            }
        )
        .also(::validatePairedHierarchy)
}

/**
 * Converts accepted retained observations while preserving every weak-reference outcome in stable
 * 1,000/2,000/4,000 execution-count order.
 */
fun retainedHierarchyFromAcceptedBlocks(
    blocks: List<AlternatingBlock>,
    baselineTargetId: String,
    candidateTargetId: String,
): RetainedHierarchy {
    validateRawBlocks(blocks, baselineTargetId, candidateTargetId)
    val hierarchy =
        RetainedHierarchy(
            blocks.map { block ->
                require(block.observations.all { it.metric == MetricId.RETAINED_BYTES }) {
                    "Block ${block.blockId} must contain only retained-byte observations"
                }
                RetainedBlockSamples(
                    blockId = block.blockId,
                    baselineReplicates =
                        observationsToRetainedReplicates(
                            block.blockId,
                            baselineTargetId,
                            block.observations.filter { it.targetId == baselineTargetId },
                        ),
                    candidateReplicates =
                        observationsToRetainedReplicates(
                            block.blockId,
                            candidateTargetId,
                            block.observations.filter { it.targetId == candidateTargetId },
                        ),
                )
            }
        )
    validateRetainedHierarchy(hierarchy)
    return hierarchy
}

internal fun validatePairedHierarchy(samples: PairedHierarchy) {
    require(samples.blocks.isNotEmpty()) { "Paired hierarchy blocks must not be empty" }
    require(samples.blocks.map(PairedBlockSamples::blockId).distinct().size == samples.blocks.size) {
        "Paired hierarchy block IDs must be unique"
    }
    samples.blocks.forEach { block ->
        require(block.blockId >= 0) { "Paired hierarchy block ID must not be negative" }
        validateForks(block.baselineForks, "block ${block.blockId} baseline")
        validateForks(block.candidateForks, "block ${block.blockId} candidate")
    }
}

internal fun validateRetainedHierarchy(samples: RetainedHierarchy) {
    require(samples.blocks.isNotEmpty()) { "Retained hierarchy blocks must not be empty" }
    require(
        samples.blocks.map(RetainedBlockSamples::blockId).distinct().size == samples.blocks.size
    ) {
        "Retained hierarchy block IDs must be unique"
    }
    samples.blocks.forEach { block ->
        require(block.blockId >= 0) { "Retained hierarchy block ID must not be negative" }
        validateReplicates(block.blockId, block.baselineReplicates, "baseline")
        validateReplicates(block.blockId, block.candidateReplicates, "candidate")
        require(
            block.baselineReplicates.map(RetainedReplicate::replicateGroup).toSet() ==
                block.candidateReplicates.map(RetainedReplicate::replicateGroup).toSet()
        ) {
            "Block ${block.blockId} retained replicate group IDs must match across roles"
        }
    }
    val baselineTargetIds =
        samples.blocks.flatMap(RetainedBlockSamples::baselineReplicates).map { it.targetId }.toSet()
    val candidateTargetIds =
        samples.blocks.flatMap(RetainedBlockSamples::candidateReplicates).map { it.targetId }.toSet()
    require(baselineTargetIds.size == 1) {
        "Baseline retained replicates must use one stable target ID"
    }
    require(candidateTargetIds.size == 1) {
        "Candidate retained replicates must use one stable target ID"
    }
    require(baselineTargetIds != candidateTargetIds) {
        "Baseline and candidate retained target IDs must be distinct"
    }
}

private fun stableMean(values: List<Double>): Double {
    val scale = values.maxOf { abs(it) }
    if (scale == 0.0) return 0.0
    val normalizedMean = values.sumOf { it / scale } / values.size
    return normalizedMean * scale
}

private fun validateRawBlocks(
    blocks: List<AlternatingBlock>,
    baselineTargetId: String,
    candidateTargetId: String,
) {
    require(blocks.isNotEmpty()) { "Accepted blocks must not be empty" }
    require(baselineTargetId.isNotBlank()) { "Baseline target ID must not be blank" }
    require(candidateTargetId.isNotBlank()) { "Candidate target ID must not be blank" }
    require(baselineTargetId != candidateTargetId) { "Target IDs must be distinct" }
    require(blocks.map(AlternatingBlock::blockId).distinct().size == blocks.size) {
        "Accepted block IDs must be unique"
    }
    val targetIds = setOf(baselineTargetId, candidateTargetId)
    blocks.forEach { block ->
        require(block.blockId >= 0) { "Accepted block ID must not be negative" }
        require(block.accepted && block.rejectionReasons.isEmpty()) {
            "Statistics adapters accept only accepted blocks"
        }
        require(block.targetOrder.size == 2 && block.targetOrder.toSet() == targetIds) {
            "Block ${block.blockId} must contain both target IDs exactly once"
        }
        require(block.observations.isNotEmpty()) {
            "Block ${block.blockId} observations must not be empty"
        }
        require(block.observations.all { it.targetId in targetIds }) {
            "Block ${block.blockId} contains an unknown target ID"
        }
        require(block.observations.all { it.value.isFinite() }) {
            "Block ${block.blockId} observations must be finite"
        }
    }
}

private fun observationsToForks(
    observations: List<MetricObservation>,
    mode: RunMode,
    label: String,
): List<ForkSeries> {
    require(observations.isNotEmpty()) { "$label observations must not be empty" }
    return when (mode) {
        RunMode.COLD -> {
            require(observations.map(MetricObservation::fork).distinct().size == observations.size) {
                "$label cold fork IDs must be unique"
            }
            require(observations.map(MetricObservation::processId).distinct().size == observations.size) {
                "$label cold observations require one fresh process each"
            }
            require(observations.all { it.iteration == 0 }) {
                "$label cold observations must use iteration zero"
            }
            observations.sortedBy(MetricObservation::fork).map { observation ->
                ForkSeries(observation.fork, listOf(observation.value))
            }
        }
        RunMode.WARM -> {
            val grouped = observations.groupBy(MetricObservation::fork).toSortedMap()
            val forkProcessIds =
                grouped.map { (fork, forkObservations) ->
                    forkObservations.map(MetricObservation::processId).distinct().singleOrNull()
                        ?: throw IllegalArgumentException("$label warm fork $fork changed process")
                }
            require(forkProcessIds.distinct().size == forkProcessIds.size) {
                "$label warm forks require distinct processes"
            }
            grouped.map { (fork, forkObservations) ->
                val sorted = forkObservations.sortedBy(MetricObservation::iteration)
                require(sorted.map(MetricObservation::iteration) == sorted.indices.toList()) {
                    "$label warm fork $fork iterations must be unique and contiguous from zero"
                }
                ForkSeries(fork, sorted.map(MetricObservation::value))
            }
        }
        RunMode.RETAINED -> error("Retained mode was rejected before fork conversion")
    }
}

private fun observationsToRetainedReplicates(
    blockId: Int,
    targetId: String,
    observations: List<MetricObservation>,
): List<RetainedReplicate> {
    require(observations.isNotEmpty()) {
        "Block $blockId retained observations for $targetId must not be empty"
    }
    require(observations.all { it.replicateGroup != null && it.retainedEvidence != null }) {
        "Block $blockId retained observations for $targetId require retained evidence"
    }
    require(observations.all { requireNotNull(it.retainedEvidence).weakReferences.isNotEmpty() }) {
        "Block $blockId retained observations for $targetId require weak-reference evidence at every checkpoint"
    }
    return observations
        .groupBy { requireNotNull(it.replicateGroup) }
        .toSortedMap()
        .map { (group, groupObservations) ->
            require(groupObservations.map(MetricObservation::fork).distinct().size == 1) {
                "Block $blockId retained group $group must use one fork ID"
            }
            require(
                groupObservations.map(MetricObservation::processId).distinct().size ==
                    groupObservations.size
            ) {
                "Block $blockId retained group $group requires one fresh process per point"
            }
            val sorted = groupObservations.sortedBy { requireNotNull(it.retainedEvidence).executionCount }
            require(sorted.map { requireNotNull(it.retainedEvidence).executionCount } == RETAINED_COUNTS) {
                "Block $blockId retained group $group must contain execution counts $RETAINED_COUNTS"
            }
            require(sorted.map(MetricObservation::iteration) == RETAINED_COUNTS.indices.toList()) {
                "Block $blockId retained group $group must contain ordered point iterations"
            }
            RetainedReplicate(
                blockId = blockId,
                targetId = targetId,
                replicateGroup = group,
                points =
                    sorted.map { observation ->
                        RetainedPoint(
                            executionCount =
                                requireNotNull(observation.retainedEvidence).executionCount,
                            retainedBytes = observation.value,
                        )
                    },
                weakReferences =
                    sorted.flatMap { observation ->
                        requireNotNull(observation.retainedEvidence).weakReferences
                    },
            )
        }
}

private fun validateForks(forks: List<ForkSeries>, label: String) {
    require(forks.isNotEmpty()) { "$label forks must not be empty" }
    require(forks.map(ForkSeries::fork).distinct().size == forks.size) {
        "$label fork IDs must be unique"
    }
    forks.forEach { fork ->
        require(fork.fork >= 0) { "$label fork ID must not be negative" }
        require(fork.values.isNotEmpty()) { "$label fork ${fork.fork} values must not be empty" }
        require(fork.values.all(Double::isFinite)) {
            "$label fork ${fork.fork} values must be finite"
        }
    }
}

private fun validateReplicates(
    blockId: Int,
    replicates: List<RetainedReplicate>,
    role: String,
) {
    require(replicates.isNotEmpty()) { "Block $blockId $role replicates must not be empty" }
    require(replicates.map(RetainedReplicate::replicateGroup).distinct().size == replicates.size) {
        "Block $blockId $role replicate group IDs must be unique"
    }
    replicates.forEach { replicate ->
        require(replicate.blockId == blockId) {
            "Block $blockId $role replicate has mismatched block ID ${replicate.blockId}"
        }
        require(replicate.targetId.isNotBlank()) {
            "Block $blockId $role replicate target ID must not be blank"
        }
        require(replicate.replicateGroup >= 0) {
            "Block $blockId $role replicate group must not be negative"
        }
        require(replicate.points.map(RetainedPoint::executionCount).sorted() == RETAINED_COUNTS) {
            "Block $blockId $role replicate must contain execution counts $RETAINED_COUNTS"
        }
        require(replicate.points.all { it.retainedBytes.isFinite() && it.retainedBytes >= 0.0 }) {
            "Block $blockId $role retained bytes must be finite and non-negative"
        }
        require(replicate.weakReferences.isNotEmpty()) {
            "Block $blockId $role replicate must preserve weak-reference evidence"
        }
        replicate.weakReferences.forEach { outcome ->
            require(outcome.type.isNotBlank()) {
                "Block $blockId $role weak-reference type must not be blank"
            }
            require(outcome.created >= 0) {
                "Block $blockId $role weak-reference created count must not be negative"
            }
            require(outcome.cleared in 0..outcome.created) {
                "Block $blockId $role weak-reference cleared count must be valid"
            }
        }
    }
}

private val RETAINED_COUNTS = listOf(1_000, 2_000, 4_000)

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.stats

import com.salesforce.revoman.benchmark.driver.model.TargetRole
import org.apache.commons.math3.random.Well19937c

/** Computes the median of every pairwise slope between distinct execution counts. */
fun theilSen(points: List<RetainedPoint>): Double {
    require(points.size >= 2) { "Theil-Sen requires at least two points" }
    require(points.all { it.retainedBytes.isFinite() }) {
        "Theil-Sen retained bytes must be finite"
    }
    require(points.map(RetainedPoint::executionCount).distinct().size == points.size) {
        "Theil-Sen execution counts must be distinct"
    }
    val slopes =
        points.indices.flatMap { leftIndex ->
            (leftIndex + 1 until points.size).map { rightIndex ->
                val left = points[leftIndex]
                val right = points[rightIndex]
                val numerator = right.retainedBytes - left.retainedBytes
                val denominator = right.executionCount.toDouble() - left.executionCount.toDouble()
                val slope = numerator / denominator
                require(slope.isFinite()) { "Theil-Sen pairwise slope must be finite" }
                slope
            }
        }
    return r7Quantile(slopes, 0.5)
}

/**
 * Computes a retained-byte slope interval by resampling host blocks before the replicate slopes in
 * each selected block. Weak-reference failures remain accepted evidence for Task 10 to gate later.
 */
fun retainedSlopeInterval(
    samples: RetainedHierarchy,
    targetRole: TargetRole,
    resamples: Int = DEFAULT_RESAMPLES,
    seed: Long = DEFAULT_STATISTICS_SEED,
): SlopeInterval {
    require(resamples > 0) { "Bootstrap resamples must be positive" }
    validateRetainedHierarchy(samples)
    val slopesByBlock =
        samples.blocks.map { block ->
            when (targetRole) {
                    TargetRole.BASELINE -> block.baselineReplicates
                    TargetRole.CANDIDATE -> block.candidateReplicates
                }
                .map { replicate -> theilSen(replicate.points) }
        }
    require(slopesByBlock.sumOf(List<Double>::size) >= MINIMUM_RETAINED_REPLICATES) {
        "$targetRole retained slope requires at least $MINIMUM_RETAINED_REPLICATES independent replicate groups"
    }
    val pointEstimate = r7Quantile(slopesByBlock.flatten(), 0.5)
    val random = Well19937c(seed)
    val bootstrapSlopes =
        List(resamples) {
            buildList {
                    repeat(slopesByBlock.size) {
                        val selectedBlock = slopesByBlock[random.nextInt(slopesByBlock.size)]
                        repeat(selectedBlock.size) {
                            add(selectedBlock[random.nextInt(selectedBlock.size)])
                        }
                    }
                }
                .let { slopes -> r7Quantile(slopes, 0.5) }
        }
    return SlopeInterval(
        pointEstimateBytesPerExecution = pointEstimate,
        lower95BytesPerExecution = r7Quantile(bootstrapSlopes, LOWER_INTERVAL_QUANTILE),
        upper95BytesPerExecution = r7Quantile(bootstrapSlopes, UPPER_INTERVAL_QUANTILE),
    )
}

private const val MINIMUM_RETAINED_REPLICATES: Int = 5

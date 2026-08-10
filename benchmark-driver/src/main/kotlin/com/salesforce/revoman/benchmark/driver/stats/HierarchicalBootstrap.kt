/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.stats

import org.apache.commons.math3.random.RandomGenerator
import org.apache.commons.math3.random.Well19937c

/**
 * Computes a candidate-over-baseline interval by resampling host blocks, process forks, and fork
 * iterations in that order.
 */
fun hierarchicalRatioInterval(
    samples: PairedHierarchy,
    statistic: Statistic,
    resamples: Int = DEFAULT_RESAMPLES,
    seed: Long = DEFAULT_STATISTICS_SEED,
): RatioInterval {
    require(resamples > 0) { "Bootstrap resamples must be positive" }
    validatePairedHierarchy(samples)
    val pointEstimate =
        ratio(
            baseline =
                statistic.calculate(
                    samples.blocks.flatMap { block ->
                        block.baselineForks.flatMap(ForkSeries::values)
                    }
                ),
            candidate =
                statistic.calculate(
                    samples.blocks.flatMap { block ->
                        block.candidateForks.flatMap(ForkSeries::values)
                    }
                ),
            location = "point estimate",
        )
    val random = Well19937c(seed)
    val bootstrapRatios =
        List(resamples) { replicate ->
            val baselineValues = mutableListOf<Double>()
            val candidateValues = mutableListOf<Double>()
            repeat(samples.blocks.size) {
                val selectedBlock = samples.blocks[random.nextInt(samples.blocks.size)]
                baselineValues += resampleForks(selectedBlock.baselineForks, random)
                candidateValues += resampleForks(selectedBlock.candidateForks, random)
            }
            ratio(
                baseline = statistic.calculate(baselineValues),
                candidate = statistic.calculate(candidateValues),
                location = "bootstrap replicate $replicate",
            )
        }
    return RatioInterval(
        pointEstimate = pointEstimate,
        lower95 = r7Quantile(bootstrapRatios, LOWER_INTERVAL_QUANTILE),
        upper95 = r7Quantile(bootstrapRatios, UPPER_INTERVAL_QUANTILE),
    )
}

private fun resampleForks(forks: List<ForkSeries>, random: RandomGenerator): List<Double> =
    buildList {
        repeat(forks.size) {
            val selectedFork = forks[random.nextInt(forks.size)]
            repeat(selectedFork.values.size) {
                add(selectedFork.values[random.nextInt(selectedFork.values.size)])
            }
        }
    }

private fun ratio(baseline: Double, candidate: Double, location: String): Double {
    require(baseline.isFinite() && baseline != 0.0) {
        "$location baseline statistic must be finite and non-zero"
    }
    require(candidate.isFinite()) { "$location candidate statistic must be finite" }
    val result = candidate / baseline
    require(result.isFinite()) { "$location ratio must be finite" }
    return result
}

internal const val DEFAULT_RESAMPLES: Int = 10_000
internal const val DEFAULT_STATISTICS_SEED: Long = 0x5245564F4D414E31L
internal const val LOWER_INTERVAL_QUANTILE: Double = 0.025
internal const val UPPER_INTERVAL_QUANTILE: Double = 0.975

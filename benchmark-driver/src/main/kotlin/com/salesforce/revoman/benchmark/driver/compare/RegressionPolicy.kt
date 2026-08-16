/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.stats.Statistic

/** Exact asymmetric release limits for controlled cold, warm, and structural evidence. */
data class RegressionPolicy(
    val coldMedianUpper: Double = 1.05,
    val coldP95Upper: Double = 1.10,
    val coldAllocationUpper: Double = 1.05,
    val coldPeakRssUpper: Double = 1.05,
    val warmMedianUpper: Double = 1.03,
    val warmP95Upper: Double = 1.05,
    val warmAllocationUpper: Double = 1.03,
    val coldImprovementUpper: Double = 0.85,
    val warmImprovementUpper: Double = 0.80,
    val retainedSlopeUpperBytes: Double = 1_024.0,
    val perStepAllocationSpreadUpper: Double = 1.10,
) {
    init {
        require(
            listOf(
                    coldMedianUpper,
                    coldP95Upper,
                    coldAllocationUpper,
                    coldPeakRssUpper,
                    warmMedianUpper,
                    warmP95Upper,
                    warmAllocationUpper,
                    coldImprovementUpper,
                    warmImprovementUpper,
                    retainedSlopeUpperBytes,
                    perStepAllocationSpreadUpper,
                )
                .all { it.isFinite() && it >= 0.0 }
        ) {
            "Regression policy limits must be finite and non-negative"
        }
    }
}

/** One canonical normative gate identity and its immutable release threshold. */
internal data class CanonicalGatePolicy(
    val decisionKey: DecisionKey,
    val limit: Double,
)

/** Single source of truth for every normative and targeted release threshold. */
internal object CanonicalReleasePolicy {
    val regressionPolicy: RegressionPolicy = RegressionPolicy()

    private val gates =
        mapOf(
            GateId.COLD_MEDIAN to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.COLD,
                        MetricId.LATENCY,
                        Statistic.MEDIAN,
                    ),
                    regressionPolicy.coldMedianUpper,
                ),
            GateId.COLD_P95 to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.COLD,
                        MetricId.LATENCY,
                        Statistic.P95,
                    ),
                    regressionPolicy.coldP95Upper,
                ),
            GateId.COLD_ALLOCATION to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.COLD,
                        MetricId.ALLOCATED_BYTES,
                        Statistic.MEAN,
                    ),
                    regressionPolicy.coldAllocationUpper,
                ),
            GateId.COLD_PEAK_RSS to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.COLD,
                        MetricId.PEAK_RSS,
                        Statistic.MEAN,
                    ),
                    regressionPolicy.coldPeakRssUpper,
                ),
            GateId.WARM_MEDIAN to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.WARM,
                        MetricId.LATENCY,
                        Statistic.MEDIAN,
                    ),
                    regressionPolicy.warmMedianUpper,
                ),
            GateId.WARM_P95 to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.WARM,
                        MetricId.LATENCY,
                        Statistic.P95,
                    ),
                    regressionPolicy.warmP95Upper,
                ),
            GateId.WARM_ALLOCATION to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.NON_REGRESSION,
                        RunMode.WARM,
                        MetricId.ALLOCATED_BYTES,
                        Statistic.MEAN,
                    ),
                    regressionPolicy.warmAllocationUpper,
                ),
            GateId.RETAINED_SLOPE to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.STRUCTURAL,
                        RunMode.RETAINED,
                        MetricId.RETAINED_BYTES,
                        null,
                    ),
                    regressionPolicy.retainedSlopeUpperBytes,
                ),
            GateId.PER_STEP_ALLOCATION_SPREAD to
                CanonicalGatePolicy(
                    DecisionKey(
                        ClaimKind.STRUCTURAL,
                        RunMode.RETAINED,
                        MetricId.BYTES_PER_STEP,
                        null,
                    ),
                    regressionPolicy.perStepAllocationSpreadUpper,
                ),
        )

    init {
        require(gates.keys == GateId.entries.toSet()) {
            "Canonical release policy must configure every GateId exactly once"
        }
    }

    fun gate(gate: GateId): CanonicalGatePolicy = gates.getValue(gate)

    fun targetedLimit(mode: RunMode): Double =
        when (mode) {
            RunMode.COLD -> regressionPolicy.coldImprovementUpper
            RunMode.WARM -> regressionPolicy.warmImprovementUpper
            RunMode.RETAINED -> error("Targeted improvements support only COLD and WARM modes")
        }

    fun requireCanonical(policy: RegressionPolicy) {
        require(policy == regressionPolicy) {
            "ReleaseGateEvaluator requires the exact canonical RegressionPolicy"
        }
    }
}

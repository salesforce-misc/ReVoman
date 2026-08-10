/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

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

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

/**
 * The tunable policy for the disambiguation gate: the gate compares the MARGIN (gap between the
 * top-two scored graphs) against a [threshold]; if margin is below threshold, the layer asks
 * instead of guessing. Absolute confidence is informational only (emitted as telemetry, not gating).
 * Write graphs default to the financial-services band (0.90) per industry research; reads use
 * [defaultThreshold]. Per-graph overrides win. The margin/threshold trade is deliberately tunable —
 * that is the whole point of the gate.
 */
data class ConfidencePolicy(
  val defaultThreshold: Double = 0.60,
  val perGraphThreshold: Map<String, Double> = emptyMap(),
  val writeGraphs: Set<String> = setOf("quote"),
) {
  fun isWrite(graph: String): Boolean = graph in writeGraphs

  fun threshold(graph: String): Double =
    perGraphThreshold[graph] ?: if (isWrite(graph)) FINANCIAL_SERVICES_BAND else defaultThreshold

  companion object {
    const val FINANCIAL_SERVICES_BAND: Double = 0.90
  }
}

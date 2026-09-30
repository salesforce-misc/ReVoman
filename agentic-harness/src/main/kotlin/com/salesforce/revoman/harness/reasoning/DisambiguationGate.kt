/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.orchestrator.FillResult

/**
 * Scaffold 5, the single most important reliability rule: gate on uncertainty, never guess. When
 * the top-two graphs are within the policy margin, or a required slot is missing/invalid, the layer
 * returns a [ReasoningOutcome.Clarify] (ask the user) rather than a coin-flip. Confident writes
 * route to [ReasoningOutcome.ConfirmRequired] (human validates before execution); confident reads
 * [ReasoningOutcome.Proceed].
 */
class DisambiguationGate(private val policy: ConfidencePolicy = ConfidencePolicy()) {
  fun decide(
    decision: RouteDecision,
    secondBestGraph: String?,
    fill: FillResult,
    chain: List<String>,
  ): ReasoningOutcome {
    val graph = decision.graphName ?: return ReasoningOutcome.NoMatch("(none)")

    if (decision.margin < policy.threshold(graph)) {
      val candidates = listOfNotNull(graph, secondBestGraph)
      return ReasoningOutcome.Clarify(
        "I'm not confident which action you want. Did you mean ${candidates.joinToString(" or ")}?",
        candidates,
      )
    }

    return when (fill) {
      is FillResult.Invalid ->
        ReasoningOutcome.Clarify(
          "I need more detail before I can proceed: ${fill.errors.joinToString("; ")}",
          listOf(graph),
        )
      is FillResult.Valid ->
        if (policy.isWrite(graph))
          ReasoningOutcome.ConfirmRequired(ActionPreview(graph, fill.slots, chain, isWrite = true))
        else ReasoningOutcome.Proceed(graph, fill.slots)
    }
  }
}

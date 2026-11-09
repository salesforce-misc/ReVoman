/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.orchestrator.SlotFiller
import com.salesforce.revoman.harness.retrieval.RetrievalPreFilter
import com.salesforce.revoman.harness.telemetry.NoopTracer
import com.salesforce.revoman.harness.telemetry.Tracer
import com.salesforce.revoman.harness.telemetry.chatAttrs
import com.salesforce.revoman.harness.telemetry.executeToolAttrs
import com.salesforce.revoman.harness.telemetry.invokeAgentAttrs
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.output.Rundown

/**
 * The complete reasoning layer: the one probabilistic surface, scaffolded for reliability.
 * `handle` runs retrieve → route (with confidence) → fill (validated) → gate, and returns a
 * [ReasoningOutcome] WITHOUT executing anything. `confirm` is the only path that hands a graph to
 * ReVoman, and only after the gate said a human must approve (writes) or the caller chose to run a
 * `Proceed`. Every turn is traced with OTel GenAI-convention spans.
 */
class ReasoningLayer(
  private val tools: List<ToolDef>,
  // Must be ScoringLlmClient (not LlmClient) because the gate depends on margin, which only
  // ScoringLlmClient populates. A bare LlmClient leaves margin=0.0, causing all decisions to fall
  // below threshold and always route to Clarify.
  private val llm: ScoringLlmClient = ScoringLlmClient(),
  private val policy: ConfidencePolicy = ConfidencePolicy(),
  private val topK: Int = tools.size,
  private val tracer: Tracer = NoopTracer(),
) {
  private val gate = DisambiguationGate(policy)
  private val slotFiller = SlotFiller(llm)

  fun handle(intent: String): ReasoningOutcome =
    tracer.span("invoke_agent", invokeAgentAttrs("reasoning-layer")) { agent ->
      agent.setAttribute("gen_ai.prompt", intent)
      val candidates = RetrievalPreFilter.topK(intent, tools, topK)

      val decision =
        tracer.span("chat", chatAttrs("router")) { chat ->
          val d = llm.route(intent, candidates)
          chat.setAttribute("gen_ai.response.text", d.graphName ?: "none")
          chat.setAttribute("confidence", d.confidence)
          chat.setAttribute("margin", d.margin)
          d
        }
      val graph = decision.graphName ?: return@span ReasoningOutcome.NoMatch(intent)
      val tool = candidates.first { it.graphName == graph }

      val secondBest =
        llm.scores(intent, candidates)
          .entries
          .sortedByDescending { it.value }
          .getOrNull(1)
          ?.takeIf { it.value > 0 }
          ?.key

      val fill =
        tracer.span("chat", chatAttrs("slot-filler")) { slotFiller.fill(intent, tool) }

      val outcome = gate.decide(decision, secondBest, fill, chain = listOf(graph))
      agent.setAttribute("turn.outcome", outcome::class.simpleName ?: "unknown")
      outcome
    }

  fun confirm(preview: ActionPreview, baseUrl: String): List<Rundown> =
    tracer.span("execute_tool", executeToolAttrs(preview.graph)) {
      GraphRunner.runChain(baseUrl, preview.chain, seedEnv = preview.slots)
    }
}

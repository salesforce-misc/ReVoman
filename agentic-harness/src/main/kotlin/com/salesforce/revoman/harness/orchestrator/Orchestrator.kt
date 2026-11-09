/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.contract.GraphContract
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.telemetry.NoopTracer
import com.salesforce.revoman.harness.telemetry.Tracer
import com.salesforce.revoman.harness.telemetry.chatAttrs
import com.salesforce.revoman.harness.telemetry.executeToolAttrs
import com.salesforce.revoman.harness.telemetry.invokeAgentAttrs
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson

/** The outcome of one orchestration turn. */
sealed interface OrchestrationResult {
  data class NoGraphMatched(val utterance: String) : OrchestrationResult

  data class SlotsRejected(val graph: String, val errors: List<String>) : OrchestrationResult

  data class Executed(
    val graph: String,
    val slots: Map<String, String>,
    val rundowns: List<Rundown>,
    val context: String,
    val contract: GraphContract? = null,
  ) : OrchestrationResult
}

/**
 * The orchestrator-workers loop, made literal: the probabilistic layer (route + slot-fill) selects
 * and parameterises a graph; the deterministic worker ([GraphRunner] → ReVoman) executes it; the
 * `Rundown` flows back as context. The LLM never touches API sequencing or intra-graph threading.
 *
 * Each turn is traced with OpenTelemetry GenAI-convention spans via [tracer] (a no-op by default,
 * so callers that don't want tracing are unaffected): an `invoke_agent` span for the turn, nested
 * `chat` spans for the two LLM jobs, and an `execute_tool` span carrying the Rundown stats.
 */
class Orchestrator(
  private val baseUrl: String,
  private val tools: List<ToolDef>,
  private val llm: LlmClient,
  private val tracer: Tracer = NoopTracer(),
) {
  private val router = Router(llm, tools)
  private val slotFiller = SlotFiller(llm)

  fun orchestrate(utterance: String): OrchestrationResult =
    tracer.span("invoke_agent", invokeAgentAttrs("q2c-agent")) { agent ->
      agent.setAttribute("gen_ai.prompt", utterance)

      val graphName =
        tracer.span("chat", chatAttrs("router")) { chat ->
          val decision = router.route(utterance)
          chat.setAttribute("gen_ai.response.text", decision.graphName ?: "none")
          decision.graphName
        }
      if (graphName == null) {
        agent.setAttribute("turn.outcome", "no_graph_matched")
        return@span OrchestrationResult.NoGraphMatched(utterance)
      }
      val tool =
        tools.firstOrNull { it.graphName == graphName }
          ?: run {
            agent.setAttribute("turn.outcome", "no_graph_matched")
            return@span OrchestrationResult.NoGraphMatched(utterance)
          }

      val fill =
        tracer.span("chat", chatAttrs("slot-filler")) { chat ->
          val result = slotFiller.fill(utterance, tool)
          chat.setAttribute(
            "gen_ai.response.text",
            when (result) {
              is FillResult.Valid -> result.slots.toString()
              is FillResult.Invalid -> "rejected: ${result.errors}"
            },
          )
          result
        }
      val slots =
        when (fill) {
          is FillResult.Valid -> fill.slots
          is FillResult.Invalid -> {
            agent.setAttribute("turn.outcome", "slots_rejected")
            return@span OrchestrationResult.SlotsRejected(graphName, fill.errors)
          }
        }

      tracer.span("execute_tool", executeToolAttrs(graphName)) { exec ->
        val rundowns = GraphRunner.runChain(baseUrl, listOf(graphName), seedEnv = slots)
        val last = rundowns.lastOrNull()
        exec.setAttribute("steps", rundowns.sumOf { it.executedStepCount })
        exec.setAttribute("stop_reason", last?.stopReason?.toString() ?: "NONE")
        exec.setAttribute("unsuccessful_steps", rundowns.sumOf { it.unsuccessfulStepCount })
        agent.setAttribute("turn.outcome", "executed")
        val contract = GraphContract.of(tool, slots, rundowns.last())
        val context = rundowns.joinToString("\n") { it.toJson(Verbosity.SUMMARY) }
        OrchestrationResult.Executed(graphName, slots, rundowns, context, contract)
      }
    }
}

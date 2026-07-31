/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.llm.LlmClient
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
  ) : OrchestrationResult
}

/**
 * The orchestrator-workers loop, made literal: the probabilistic layer (route + slot-fill) selects
 * and parameterises a graph; the deterministic worker ([GraphRunner] → ReVoman) executes it; the
 * `Rundown` flows back as context. The LLM never touches API sequencing or intra-graph threading.
 */
class Orchestrator(
  private val baseUrl: String,
  private val tools: List<ToolDef>,
  private val llm: LlmClient,
) {
  private val router = Router(llm, tools)
  private val slotFiller = SlotFiller(llm)

  fun orchestrate(utterance: String): OrchestrationResult {
    val graphName =
      router.route(utterance).graphName ?: return OrchestrationResult.NoGraphMatched(utterance)
    val tool = tools.first { it.graphName == graphName }
    val slots =
      when (val fill = slotFiller.fill(utterance, tool)) {
        is FillResult.Valid -> fill.slots
        is FillResult.Invalid -> return OrchestrationResult.SlotsRejected(graphName, fill.errors)
      }
    val rundowns = GraphRunner.runChain(baseUrl, listOf(graphName), seedEnv = slots)
    val context = rundowns.joinToString("\n") { it.toJson(Verbosity.SUMMARY) }
    return OrchestrationResult.Executed(graphName, slots, rundowns, context)
  }
}

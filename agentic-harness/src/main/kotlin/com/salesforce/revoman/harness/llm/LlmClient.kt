/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.salesforce.revoman.harness.tooldef.ToolDef

/**
 * The router's output: the chosen graph (or null when nothing matched), a short rationale, and the
 * router's confidence signals. [confidence] is the normalized top score in [0,1]; [margin] is the
 * normalized gap to the second-best graph in [0,1]. The disambiguation gate reads [margin] to
 * decide whether to ask instead of guess. Both default so callers that don't produce them (the
 * keyword stub, the Claude client) are unaffected.
 */
data class RouteDecision(
  val graphName: String?,
  val rationale: String,
  val confidence: Double = 0.0,
  val margin: Double = 0.0,
)

/**
 * The only probabilistic surface in the harness. Two jobs, exactly as the design specifies: pick a
 * graph, and fill that graph's input slots. Synchronous by contract so the deterministic stub and
 * the whole orchestrator core stay coroutine-free; the real Claude implementation lives in a
 * separate source set and bridges its suspend calls internally.
 */
interface LlmClient {
  fun route(utterance: String, tools: List<ToolDef>): RouteDecision

  fun fillSlots(utterance: String, tool: ToolDef): Map<String, String>
}

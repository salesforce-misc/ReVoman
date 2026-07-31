/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.salesforce.revoman.harness.tooldef.ToolDef

/** The router's output: the chosen graph (or null when nothing matched) and a short rationale. */
data class RouteDecision(val graphName: String?, val rationale: String)

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

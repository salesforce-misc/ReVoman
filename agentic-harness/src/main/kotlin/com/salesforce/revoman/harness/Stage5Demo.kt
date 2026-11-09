/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.reasoning.ConfidencePolicy
import com.salesforce.revoman.harness.reasoning.ReasoningLayer
import com.salesforce.revoman.harness.reasoning.ReasoningOutcome
import com.salesforce.revoman.harness.telemetry.GenAiTracer

/**
 * Stage 5 runnable demo: the complete reasoning layer with all six scaffolds, deterministic (no
 * key). Shows the five outcomes — proceed (read), confirm-then-execute (write), ask (slot-missing),
 * ask (low-margin), no-match — each traced with OTel GenAI-convention spans printed to console.
 */
fun main() {
  val tools = GraphRegistry.loadToolDefs()
  val stripped = tools.map { it.copy(whenNotToUse = emptyList()) }
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  // Seed the database with the required price for the quote graph to succeed
  server.db["price:prc-2"] = 100.0
  val layer = ReasoningLayer(tools, tracer = GenAiTracer())
  val strippedLayer = ReasoningLayer(stripped, tracer = GenAiTracer())

  try {
    listOf(
      Triple("price configuration cfg-1", layer, false), // confident read -> Proceed
      Triple("create a draft quote for prc-2", layer, false), // confident write -> ConfirmRequired -> confirm
      Triple("configure or price this", layer, false), // slot-missing -> Clarify (ask, don't guess)
      Triple("price configuration or draft quote", strippedLayer, false), // low-margin -> Clarify (ambiguous between two graphs)
      Triple("what is the weather", layer, false), // no match
    )
      .forEach { (intent, reasoningLayer, _) ->
        println("\n>>> $intent")
        when (val outcome = reasoningLayer.handle(intent)) {
          is ReasoningOutcome.Proceed -> println("  PROCEED: ${outcome.graph} slots=${outcome.slots}")
          is ReasoningOutcome.ConfirmRequired -> {
            println("  CONFIRM REQUIRED (write): ${outcome.preview}")
            val rundowns = reasoningLayer.confirm(outcome.preview, baseUrl)
            println("  confirmed -> executed ${rundowns.size} graph(s); DB=${server.db}")
          }
          is ReasoningOutcome.Clarify -> println("  ASK: ${outcome.question} candidates=${outcome.candidates}")
          is ReasoningOutcome.NoMatch -> println("  NO MATCH")
        }
      }
  } finally {
    server.stop()
  }
}

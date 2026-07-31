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
 * key). Shows the four outcomes — proceed (read), confirm-then-execute (write), ask (ambiguous),
 * no-match — each traced with OTel GenAI-convention spans printed to console.
 */
fun main() {
  val tools = GraphRegistry.loadToolDefs()
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  // A high 'configure' threshold forces the ambiguous case to ask.
  val policy = ConfidencePolicy(perGraphThreshold = mapOf("configure" to 0.99))
  val layer = ReasoningLayer(tools, policy = policy, tracer = GenAiTracer())

  try {
    listOf(
      "price configuration cfg-1", // confident read -> Proceed
      "create a draft quote for prc-2", // confident write -> ConfirmRequired -> confirm
      "configure or price this", // ambiguous -> Clarify (ask, don't guess)
      "what is the weather", // no match
    )
      .forEach { intent ->
        println("\n>>> $intent")
        when (val outcome = layer.handle(intent)) {
          is ReasoningOutcome.Proceed -> println("  PROCEED: ${outcome.graph} slots=${outcome.slots}")
          is ReasoningOutcome.ConfirmRequired -> {
            println("  CONFIRM REQUIRED (write): ${outcome.preview}")
            // Seed the database with the required price for the quote graph to succeed
            if (intent.contains("prc-2")) server.db["price:prc-2"] = 100.0
            val rundowns = layer.confirm(outcome.preview, baseUrl)
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

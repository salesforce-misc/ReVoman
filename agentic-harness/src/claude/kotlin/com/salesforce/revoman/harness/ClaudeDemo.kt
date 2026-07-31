/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.llm.claude.ClaudeLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult

/** Live demo of the orchestrator with the REAL Claude LLM. No-ops (skips) when the key is absent. */
fun main() {
  val llm = ClaudeLlmClient.fromEnv()
  if (llm == null) {
    println("ANTHROPIC_API_KEY not set — skipping the live Claude demo.")
    return
  }
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  val orchestrator = Orchestrator(baseUrl, GraphRegistry.loadToolDefs(), llm)
  try {
    println("Live Claude demo, mock CPQ at $baseUrl")
    listOf("I want to configure ten of SKU-2", "how much will this cost").forEach { utterance ->
      println("\n>>> $utterance")
      when (val r = orchestrator.orchestrate(utterance)) {
        is OrchestrationResult.NoGraphMatched -> println("No graph matched.")
        is OrchestrationResult.SlotsRejected -> println("Slots rejected for '${r.graph}': ${r.errors}")
        is OrchestrationResult.Executed -> println("Routed to '${r.graph}', slots=${r.slots}\n${r.context}")
      }
    }
    println("\nFinal mock DB state: ${server.db}")
  } finally {
    server.stop()
  }
}

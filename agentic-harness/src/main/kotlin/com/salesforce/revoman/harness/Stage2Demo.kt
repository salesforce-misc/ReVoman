/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult

/**
 * Stage 2 runnable demo: the orchestrator-workers loop end to end with the deterministic stub LLM
 * (no API key needed). Routes a handful of utterances, fills+validates slots, executes the chosen
 * graph via ReVoman, and prints the outcome + the Rundown context that would flow back to the LLM.
 */
fun main() {
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  val tools = GraphRegistry.loadToolDefs()
  val orchestrator = Orchestrator(baseUrl, tools, StubLlmClient())
  val utterances =
    listOf(
      "configure 2 units of SKU-1",
      "what is the weather today",
    )
  try {
    println("Mock CPQ server up at $baseUrl")
    utterances.forEach { utterance ->
      println("\n>>> $utterance")
      when (val result = orchestrator.orchestrate(utterance)) {
        is OrchestrationResult.NoGraphMatched -> println("No graph matched.")
        is OrchestrationResult.SlotsRejected ->
          println("Slots rejected for '${result.graph}': ${result.errors}")
        is OrchestrationResult.Executed -> {
          println("Routed to '${result.graph}', slots=${result.slots}")
          println(result.context)
        }
      }
    }
    println("\nFinal mock DB state: ${server.db}")
  } finally {
    server.stop()
  }
}

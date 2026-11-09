/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.contract.toJson
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult
import com.salesforce.revoman.output.Verbosity

/**
 * Blue-box demo: the unified GraphContract in action. Orchestrates one intent, then prints the SAME
 * contract at SUMMARY / STANDARD / VERBOSE so the tiers are visible side by side — descriptor
 * metadata + runtime outcome + data-lineage, fused, deterministic, no LLM key.
 */
fun main() {
  val tools = GraphRegistry.loadToolDefs()
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    val result =
      Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")
    val contract =
      (result as? OrchestrationResult.Executed)?.contract
        ?: run {
          println("No contract (outcome: $result)")
          return
        }
    listOf(Verbosity.SUMMARY, Verbosity.STANDARD, Verbosity.VERBOSE).forEach { v ->
      println("\n===== GraphContract @ $v =====")
      println(contract.toJson(v))
    }
  } finally {
    server.stop()
  }
}

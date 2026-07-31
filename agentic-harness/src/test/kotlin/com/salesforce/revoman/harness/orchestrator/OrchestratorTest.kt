/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String
  private val tools: List<ToolDef> = GraphRegistry.loadToolDefs()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `configure intent routes, fills, executes, and records state`() {
    val result =
      Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")

    assertThat(result).isInstanceOf(OrchestrationResult.Executed::class.java)
    val executed = result as OrchestrationResult.Executed
    assertThat(executed.graph).isEqualTo("configure")
    assertThat(executed.slots).containsEntry("productCode", "SKU-1")
    assertThat(executed.slots).containsEntry("quantity", "2")
    // The filled quantity (2) overrides the env-file default (1) — proof slots reach ReVoman.
    assertThat(server.db.values).contains("SKU-1 x2")
    // Unified GraphContract context flows back for the LLM.
    assertThat(executed.context).contains("succeeded")
    assertThat(executed.context).contains("executedStepCount")
  }

  @Test
  fun `unrelated intent yields NoGraphMatched`() {
    val result = Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("what is the weather")
    assertThat(result).isInstanceOf(OrchestrationResult.NoGraphMatched::class.java)
  }

  @Test
  fun `hallucinated slot value is rejected before execution`() {
    // A fake LLM that routes to configure but fills an out-of-enum productCode.
    val badLlm =
      object : LlmClient {
        override fun route(utterance: String, tools: List<ToolDef>) =
          RouteDecision("configure", "forced")

        override fun fillSlots(utterance: String, tool: ToolDef) =
          mapOf("productCode" to "SKU-99", "quantity" to "2")
      }
    val result = Orchestrator(baseUrl, tools, badLlm).orchestrate("configure something")
    assertThat(result).isInstanceOf(OrchestrationResult.SlotsRejected::class.java)
    // Nothing was executed — the mock DB stays empty.
    assertThat(server.db).isEmpty()
  }
}

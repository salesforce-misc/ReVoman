/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.telemetry.GenAiTracer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorTracingTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String
  private val tools = GraphRegistry.loadToolDefs()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `an executed turn emits invoke_agent with nested chat and execute_tool spans`() {
    val tracer = GenAiTracer(sink = {})
    Orchestrator(baseUrl, tools, StubLlmClient(), tracer)
      .orchestrate("configure 2 units of SKU-1")

    val root = tracer.rootSpans.single()
    assertThat(root.name).isEqualTo("invoke_agent")
    assertThat(root.attributes).containsEntry("gen_ai.operation.name", "invoke_agent")
    val childNames = root.children.map { it.name }
    // Two chat spans (route + slot-fill) then an execute_tool span.
    assertThat(childNames).containsExactly("chat", "chat", "execute_tool").inOrder()
    val executeTool = root.children.last()
    assertThat(executeTool.attributes).containsEntry("gen_ai.tool.name", "configure")
    assertThat(executeTool.attributes).containsEntry("steps", 1)
    assertThat(executeTool.attributes.keys).contains("stop_reason")
  }

  @Test
  fun `a no-match turn emits invoke_agent with only the routing chat span`() {
    val tracer = GenAiTracer(sink = {})
    Orchestrator(baseUrl, tools, StubLlmClient(), tracer).orchestrate("what is the weather")
    val root = tracer.rootSpans.single()
    assertThat(root.children.map { it.name }).containsExactly("chat")
  }
}

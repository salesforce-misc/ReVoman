/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GenAiTracerTest {
  @Test
  fun `nests child spans under the parent and records attributes`() {
    val tracer = GenAiTracer(sink = {})
    tracer.span("invoke_agent", invokeAgentAttrs("q2c-agent")) { agent ->
      tracer.span("chat", chatAttrs("stub")) { it.setAttribute("gen_ai.response.text", "configure") }
      tracer.span("execute_tool", executeToolAttrs("configure")) { it.setAttribute("steps", 1) }
      agent.setAttribute("turn.ok", true)
    }

    assertThat(tracer.rootSpans).hasSize(1)
    val root = tracer.rootSpans.single()
    assertThat(root.name).isEqualTo("invoke_agent")
    assertThat(root.attributes).containsEntry("gen_ai.operation.name", "invoke_agent")
    assertThat(root.attributes).containsEntry("turn.ok", true)
    assertThat(root.children.map { it.name }).containsExactly("chat", "execute_tool").inOrder()
    assertThat(root.children[1].attributes).containsEntry("gen_ai.tool.name", "configure")
  }

  @Test
  fun `renders a nested indented tree and emits to the sink on root close`() {
    val emitted = StringBuilder()
    val tracer = GenAiTracer(sink = { emitted.append(it) })
    tracer.span("invoke_agent", invokeAgentAttrs("q2c-agent")) {
      tracer.span("chat", chatAttrs("stub")) {}
    }
    val text = emitted.toString()
    assertThat(text).contains("invoke_agent")
    assertThat(text).contains("chat")
    assertThat(text).contains("gen_ai.operation.name=invoke_agent")
    // The child is indented deeper than the parent.
    assertThat(text.indexOf("chat")).isGreaterThan(text.indexOf("invoke_agent"))
  }

  @Test
  fun `NoopTracer runs the block but records nothing`() {
    val noop = NoopTracer()
    val result = noop.span("invoke_agent") { 42 }
    assertThat(result).isEqualTo(42)
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.telemetry

/** A scope for adding attributes to the currently-open span. */
interface SpanScope {
  fun setAttribute(key: String, value: Any?)
}

/** Opens spans that nest via the block: a `span(...)` called inside another's block is its child. */
interface Tracer {
  fun <T> span(name: String, attributes: Map<String, Any?> = emptyMap(), block: (SpanScope) -> T): T
}

/** A tracer that runs blocks but records nothing — the default when tracing isn't wanted. */
class NoopTracer : Tracer {
  override fun <T> span(name: String, attributes: Map<String, Any?>, block: (SpanScope) -> T): T =
    block(
      object : SpanScope {
        override fun setAttribute(key: String, value: Any?) {}
      }
    )
}

/**
 * Records the OpenTelemetry GenAI-convention span tree and renders it to [sink] when each top-level
 * span closes. Single-threaded by design (the harness confines a turn to one thread, exactly like
 * ReVoman's `revUp`). Not the OTLP SDK — a dependency-free, faithful console rendering of the
 * `invoke_agent` / `chat` / `execute_tool` span shape.
 */
class GenAiTracer(private val sink: (String) -> Unit = ::println) : Tracer {
  private class Building(val name: String, val attributes: MutableMap<String, Any?>) : SpanScope {
    val children: MutableList<Span> = mutableListOf()

    override fun setAttribute(key: String, value: Any?) {
      attributes[key] = value
    }

    fun toSpan(): Span = Span(name, attributes.toMap(), children.toList())
  }

  private val stack: ArrayDeque<Building> = ArrayDeque()
  private val _rootSpans: MutableList<Span> = mutableListOf()

  val rootSpans: List<Span>
    get() = _rootSpans.toList()

  override fun <T> span(name: String, attributes: Map<String, Any?>, block: (SpanScope) -> T): T {
    val building = Building(name, attributes.toMutableMap())
    stack.addLast(building)
    try {
      return block(building)
    } finally {
      stack.removeLast()
      val span = building.toSpan()
      val parent = stack.lastOrNull()
      if (parent == null) {
        _rootSpans.add(span)
        sink(span.render())
      } else {
        parent.children.add(span)
      }
    }
  }
}

/** GenAI-convention attributes for an agent-invocation (turn) span. */
fun invokeAgentAttrs(agentName: String): Map<String, Any?> =
  mapOf("gen_ai.operation.name" to "invoke_agent", "gen_ai.agent.name" to agentName)

/** GenAI-convention attributes for an LLM chat span. */
fun chatAttrs(model: String): Map<String, Any?> =
  mapOf("gen_ai.operation.name" to "chat", "gen_ai.request.model" to model)

/** GenAI-convention attributes for a tool-execution (graph run) span. */
fun executeToolAttrs(toolName: String): Map<String, Any?> =
  mapOf("gen_ai.operation.name" to "execute_tool", "gen_ai.tool.name" to toolName)

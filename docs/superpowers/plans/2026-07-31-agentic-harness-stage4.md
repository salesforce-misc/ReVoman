# Agentic Harness — Stage 4 (Observability + Feedback Flywheel) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the observability + feedback layer: OpenTelemetry GenAI-convention spans (`invoke_agent` / `chat` / `execute_tool`) emitted to the console for each orchestration turn, a simulated HITL confirm gate (confirm / edit / reject → positive / correction-pair / negative labels), and a nightly-batch function that appends confirmed turns to the eval set and drafts `when_not_to_use` clauses from confusion pairs — closing the loop by showing the CI gate tighten (accuracy rises when the drafted clause is applied).

**Architecture:** Everything is deterministic, koog-free, dependency-free, and CI-safe. Tracing is a self-contained `GenAiTracer` that models the OpenTelemetry GenAI semantic conventions (span names + `gen_ai.*` attributes) and renders the span tree to a sink (console by default; captured in tests). It is NOT the OpenTelemetry OTLP SDK — no new dependency — but it is faithful to the convention's span shape, which is what the design demonstrates ("OTel GenAI-style spans to console or a local collector"). The `Orchestrator` gains one optional constructor parameter (a tracer, defaulting to a no-op) so every prior-stage test stays green unchanged. All new code lives in `agentic-harness/src/main` and `src/test`.

**Tech Stack:** Kotlin (JVM 21), the `agentic-harness` module, ReVoman (`project(":")`), snakeyaml (already a dep), JUnit5 + Google Truth. No koog, no OpenTelemetry SDK.

## Global Constraints

- **JDK 21**; never modify the ReVoman library; all work inside `agentic-harness/`.
- **No koog / kotlinx.coroutines / OpenTelemetry-SDK** dependency in any `src/main` or `src/test` file. The default `test`/`build`/`check` stays green and never compiles the `claude` source set.
- **Stages 1–3 must stay green** after every task: `./gradlew :agentic-harness:test` passes all prior + new test classes. The one modification to existing code (`Orchestrator` gains an optional tracer param with a no-op default) must not change any existing test's behavior.
- **Copyright header** (SFDC Apache-2.0) on every new `.kt`; JUnit5 + Google Truth; ktfmt Google style (`./gradlew spotlessApply` before every commit).
- **Verified facts (do not re-derive):**
  - `orchestrator.Orchestrator(baseUrl, tools, llm)` currently has `orchestrate(utterance): OrchestrationResult` (`NoGraphMatched(utterance)` | `SlotsRejected(graph, errors)` | `Executed(graph, slots, rundowns, context)`). It composes `Router(llm, tools)` and `SlotFiller(llm)`, then `GraphRunner.runChain(baseUrl, listOf(graphName), seedEnv = slots)`, then joins `Rundown.toJson(Verbosity.SUMMARY)`.
  - `output.Rundown` exposes `stopReason: StopReason`, `executedStepCount: Int`, `unsuccessfulStepCount: Int`, `areAllStepsSuccessful: Boolean`.
  - Stage 3 delivered in `eval`: `EvalCase(utterance, expected)`, `EvalSet.load(...)`, `ConfusionMatrix`/`ConfusionMatrices.from(pairs)`, `RouterEvaluator(llm).evaluate(cases, tools): EvalReport(matrix, misses)`, `Miss(utterance, expected, predicted)`, `Scoring­LlmClient`.
  - Stage 2 delivered: `tooldef.ToolDef` (data class; `.copy(...)` available; has `whenNotToUse: List<String>`), `orchestrator.GraphRegistry.loadToolDefs()`, `llm.StubLlmClient`, `mock.MockCpqServer`.
  - The design's GenAI span conventions: an `invoke_agent` span per turn; nested `chat` spans per LLM call (with `gen_ai.request.model`); nested `execute_tool` spans per graph run (nesting Rundown stats: step count, stopReason, failures). Attribute keys follow `gen_ai.*` (e.g. `gen_ai.operation.name`, `gen_ai.agent.name`, `gen_ai.tool.name`).

---

### Task 1: `GenAiTracer` — OTel GenAI-convention span model + console renderer

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/telemetry/Span.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/telemetry/GenAiTracer.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/telemetry/GenAiTracerTest.kt`

**Interfaces:**
- Produces:
  - `data class Span(val name: String, val attributes: Map<String, Any?>, val children: List<Span>)` with `fun render(indent: Int = 0): String` (a nested, indented text tree).
  - `interface Tracer { fun <T> span(name: String, attributes: Map<String, Any?> = emptyMap(), block: (SpanScope) -> T): T }` and `interface SpanScope { fun setAttribute(key: String, value: Any?) }` — spans nest via the block; a child `span(...)` called inside a parent's block becomes that parent's child.
  - `class NoopTracer : Tracer` — runs the block, records nothing (the default for tests that don't assert on spans).
  - `class GenAiTracer(private val sink: (String) -> Unit = ::println) : Tracer` — builds the span tree; when the outermost span closes, emits `root.render()` to `sink`. Exposes `val rootSpans: List<Span>` (completed top-level spans) for assertions.
  - Convention helpers (top-level funs in `GenAiTracer.kt`): `fun invokeAgentAttrs(agentName: String): Map<String, Any?>` = `{"gen_ai.operation.name":"invoke_agent","gen_ai.agent.name":agentName}`; `fun chatAttrs(model: String): Map<String, Any?>` = `{"gen_ai.operation.name":"chat","gen_ai.request.model":model}`; `fun executeToolAttrs(toolName: String): Map<String, Any?>` = `{"gen_ai.operation.name":"execute_tool","gen_ai.tool.name":toolName}`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/telemetry/GenAiTracerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*GenAiTracerTest"`
Expected: FAIL — unresolved references `GenAiTracer`, `NoopTracer`, `Span`, `invokeAgentAttrs`, etc.

- [ ] **Step 3: Write `Span`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/telemetry/Span.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.telemetry

/**
 * One completed span in the OpenTelemetry GenAI-convention shape: a name, `gen_ai.*` attributes,
 * and nested child spans. This is a faithful model of the convention's span tree rendered to a
 * console sink — not the OTLP wire SDK (no dependency), which is what the local demo needs.
 */
data class Span(val name: String, val attributes: Map<String, Any?>, val children: List<Span>) {
  fun render(indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val attrs = attributes.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val head = "$pad• $name" + if (attrs.isEmpty()) "" else "  [$attrs]"
    val kids = children.joinToString("") { "\n" + it.render(indent + 1) }
    return head + kids
  }
}
```

- [ ] **Step 4: Write `GenAiTracer`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/telemetry/GenAiTracer.kt`:

```kotlin
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*GenAiTracerTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/telemetry/Span.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/telemetry/GenAiTracer.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/telemetry/GenAiTracerTest.kt
git commit -m "feat(harness): OTel GenAI-convention tracer (invoke_agent/chat/execute_tool)"
```

---

### Task 2: Instrument `Orchestrator` with GenAI spans (additive, no-op default)

**Files:**
- Modify: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorTracingTest.kt`

**Interfaces:**
- Consumes: `Tracer`, `NoopTracer`, `GenAiTracer`, `invokeAgentAttrs`/`chatAttrs`/`executeToolAttrs` (Task 1).
- Produces:
  - `Orchestrator` gains a 4th constructor param: `private val tracer: Tracer = NoopTracer()`. Existing 3-arg construction is unchanged (default no-op), so all prior tests compile and pass untouched.
  - `orchestrate` wraps the turn in an `invoke_agent` span; emits a `chat` span for routing (attribute `gen_ai.response.text` = the chosen graph or "none"), a `chat` span for slot-filling (attribute `slots.filled` = the filled map's size or the rejection), and — on execution — an `execute_tool` span whose attributes carry the Rundown stats (`gen_ai.tool.name`, `steps` = `executedStepCount`, `stop_reason` = `stopReason`, `unsuccessful_steps` = `unsuccessfulStepCount`). Behavior/return value is unchanged.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorTracingTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*OrchestratorTracingTest"`
Expected: FAIL — the 4-arg `Orchestrator(...)` constructor does not yet exist (unresolved), or spans are absent.

- [ ] **Step 3: Modify `Orchestrator` to emit spans**

Replace the body of `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt` with (keep the `OrchestrationResult` sealed interface exactly as-is at the top of the file; only the class changes):

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.telemetry.NoopTracer
import com.salesforce.revoman.harness.telemetry.Tracer
import com.salesforce.revoman.harness.telemetry.chatAttrs
import com.salesforce.revoman.harness.telemetry.executeToolAttrs
import com.salesforce.revoman.harness.telemetry.invokeAgentAttrs
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson

/** The outcome of one orchestration turn. */
sealed interface OrchestrationResult {
  data class NoGraphMatched(val utterance: String) : OrchestrationResult

  data class SlotsRejected(val graph: String, val errors: List<String>) : OrchestrationResult

  data class Executed(
    val graph: String,
    val slots: Map<String, String>,
    val rundowns: List<Rundown>,
    val context: String,
  ) : OrchestrationResult
}

/**
 * The orchestrator-workers loop, made literal: the probabilistic layer (route + slot-fill) selects
 * and parameterises a graph; the deterministic worker ([GraphRunner] → ReVoman) executes it; the
 * `Rundown` flows back as context. The LLM never touches API sequencing or intra-graph threading.
 *
 * Each turn is traced with OpenTelemetry GenAI-convention spans via [tracer] (a no-op by default,
 * so callers that don't want tracing are unaffected): an `invoke_agent` span for the turn, nested
 * `chat` spans for the two LLM jobs, and an `execute_tool` span carrying the Rundown stats.
 */
class Orchestrator(
  private val baseUrl: String,
  private val tools: List<ToolDef>,
  private val llm: LlmClient,
  private val tracer: Tracer = NoopTracer(),
) {
  private val router = Router(llm, tools)
  private val slotFiller = SlotFiller(llm)

  fun orchestrate(utterance: String): OrchestrationResult =
    tracer.span("invoke_agent", invokeAgentAttrs("q2c-agent")) { agent ->
      agent.setAttribute("gen_ai.prompt", utterance)

      val graphName =
        tracer.span("chat", chatAttrs("router")) { chat ->
          val decision = router.route(utterance)
          chat.setAttribute("gen_ai.response.text", decision.graphName ?: "none")
          decision.graphName
        }
      if (graphName == null) {
        agent.setAttribute("turn.outcome", "no_graph_matched")
        return@span OrchestrationResult.NoGraphMatched(utterance)
      }
      val tool =
        tools.firstOrNull { it.graphName == graphName }
          ?: run {
            agent.setAttribute("turn.outcome", "no_graph_matched")
            return@span OrchestrationResult.NoGraphMatched(utterance)
          }

      val fill =
        tracer.span("chat", chatAttrs("slot-filler")) { chat ->
          val result = slotFiller.fill(utterance, tool)
          chat.setAttribute(
            "gen_ai.response.text",
            when (result) {
              is FillResult.Valid -> result.slots.toString()
              is FillResult.Invalid -> "rejected: ${result.errors}"
            },
          )
          result
        }
      val slots =
        when (fill) {
          is FillResult.Valid -> fill.slots
          is FillResult.Invalid -> {
            agent.setAttribute("turn.outcome", "slots_rejected")
            return@span OrchestrationResult.SlotsRejected(graphName, fill.errors)
          }
        }

      tracer.span("execute_tool", executeToolAttrs(graphName)) { exec ->
        val rundowns = GraphRunner.runChain(baseUrl, listOf(graphName), seedEnv = slots)
        val last = rundowns.lastOrNull()
        exec.setAttribute("steps", rundowns.sumOf { it.executedStepCount })
        exec.setAttribute("stop_reason", last?.stopReason?.toString() ?: "NONE")
        exec.setAttribute("unsuccessful_steps", rundowns.sumOf { it.unsuccessfulStepCount })
        agent.setAttribute("turn.outcome", "executed")
        val context = rundowns.joinToString("\n") { it.toJson(Verbosity.SUMMARY) }
        OrchestrationResult.Executed(graphName, slots, rundowns, context)
      }
    }
}
```

- [ ] **Step 4: Run the tracing test AND the whole suite (existing tests must stay green)**

Run: `./gradlew :agentic-harness:test --tests "*OrchestratorTracingTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — every Stage 1/2/3 test class still passes (the no-op default preserves prior behavior; `OrchestratorTest`, `TauBenchCheckTest`, `LlmJudgeTest` all construct `Orchestrator` with 3 args and are unaffected).

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorTracingTest.kt
git commit -m "feat(harness): trace orchestrator turns with GenAI spans (no-op default)"
```

---

### Task 3: `ConfirmGate` — HITL confirm / edit / reject → labels

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/feedback/ConfirmGate.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/ConfirmGateTest.kt`

**Interfaces:**
- Consumes: nothing from prior tasks (pure feedback modeling).
- Produces:
  - `data class Proposal(val utterance: String, val graph: String, val slots: Map<String, String>)` — the write the agent proposes at the gate.
  - `sealed interface Decision { object Confirm : Decision; data class Edit(val correctedSlots: Map<String, String>) : Decision; data class Reject(val correctGraph: String? = null) : Decision }`.
  - `sealed interface FeedbackLabel { data class Positive(val proposal: Proposal) : FeedbackLabel; data class CorrectionPair(val proposal: Proposal, val correctedSlots: Map<String, String>) : FeedbackLabel; data class Negative(val proposal: Proposal, val correctGraph: String?) : FeedbackLabel }`.
  - `object ConfirmGate { fun apply(proposal: Proposal, decision: Decision): FeedbackLabel }` — `Confirm` → `Positive`; `Edit` → `CorrectionPair` (proposal vs corrected slots); `Reject` → `Negative` (carrying the human's correct graph if given). This is the design's "free labeling machine": the confirm step *is* the feedback signal.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/ConfirmGateTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConfirmGateTest {
  private val proposal =
    Proposal("configure 2 units of SKU-1", "configure", mapOf("productCode" to "SKU-1", "quantity" to "2"))

  @Test
  fun `confirm yields a positive label`() {
    val label = ConfirmGate.apply(proposal, Decision.Confirm)
    assertThat(label).isInstanceOf(FeedbackLabel.Positive::class.java)
    assertThat((label as FeedbackLabel.Positive).proposal).isEqualTo(proposal)
  }

  @Test
  fun `edit yields a correction pair carrying the fixed slots`() {
    val fixed = mapOf("productCode" to "SKU-1", "quantity" to "5")
    val label = ConfirmGate.apply(proposal, Decision.Edit(fixed))
    assertThat(label).isInstanceOf(FeedbackLabel.CorrectionPair::class.java)
    val pair = label as FeedbackLabel.CorrectionPair
    assertThat(pair.proposal.slots).containsEntry("quantity", "2")
    assertThat(pair.correctedSlots).containsEntry("quantity", "5")
  }

  @Test
  fun `reject yields a negative label carrying the human's correct graph`() {
    val label = ConfirmGate.apply(proposal, Decision.Reject(correctGraph = "price"))
    assertThat(label).isInstanceOf(FeedbackLabel.Negative::class.java)
    assertThat((label as FeedbackLabel.Negative).correctGraph).isEqualTo("price")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ConfirmGateTest"`
Expected: FAIL — unresolved references `Proposal`, `Decision`, `FeedbackLabel`, `ConfirmGate`.

- [ ] **Step 3: Write `ConfirmGate`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/feedback/ConfirmGate.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

/** The write the agent proposes at the human confirm gate. */
data class Proposal(val utterance: String, val graph: String, val slots: Map<String, String>)

/** What the human does at the gate. */
sealed interface Decision {
  /** Approve the proposal as-is. */
  object Confirm : Decision

  /** Approve after fixing the slots. */
  data class Edit(val correctedSlots: Map<String, String>) : Decision

  /** Reject; optionally state the graph that should have been chosen. */
  data class Reject(val correctGraph: String? = null) : Decision
}

/** The training signal the gate emits — the design's "free labeling machine". */
sealed interface FeedbackLabel {
  data class Positive(val proposal: Proposal) : FeedbackLabel

  data class CorrectionPair(val proposal: Proposal, val correctedSlots: Map<String, String>) :
    FeedbackLabel

  data class Negative(val proposal: Proposal, val correctGraph: String?) : FeedbackLabel
}

/**
 * The human-in-the-loop confirm gate on every write. Because a human confirms every mutation, the
 * gate is the highest-value labeling signal available at no extra cost: confirm → positive, edit →
 * a correction pair (proposed vs fixed), reject → a negative (mined for `when_not_to_use`).
 */
object ConfirmGate {
  fun apply(proposal: Proposal, decision: Decision): FeedbackLabel =
    when (decision) {
      is Decision.Confirm -> FeedbackLabel.Positive(proposal)
      is Decision.Edit -> FeedbackLabel.CorrectionPair(proposal, decision.correctedSlots)
      is Decision.Reject -> FeedbackLabel.Negative(proposal, decision.correctGraph)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*ConfirmGateTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/feedback/ConfirmGate.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/ConfirmGateTest.kt
git commit -m "feat(harness): HITL confirm gate maps confirm/edit/reject to feedback labels"
```

---

### Task 4: `NightlyBatch` — grow the eval set + draft `when_not_to_use` from confusion pairs

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/feedback/NightlyBatch.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/NightlyBatchTest.kt`

**Interfaces:**
- Consumes: `FeedbackLabel`, `Proposal` (Task 3); `eval.EvalCase` (Stage 3).
- Produces:
  - `data class BatchOutput(val grownEvalSet: List<EvalCase>, val draftedClauses: Map<String, List<String>>)` — `draftedClauses` maps a graph name → the `when_not_to_use` clauses drafted for it.
  - `object NightlyBatch { fun run(seedEvalSet: List<EvalCase>, labels: List<FeedbackLabel>): BatchOutput }`:
    - Confirmed turns (`Positive`) become new `EvalCase(utterance, proposal.graph)` appended to the eval set (deduped against existing utterances).
    - `Negative` turns with a known `correctGraph` become both a new `EvalCase(utterance, correctGraph)` (a regression test so that exact miss never returns) AND a drafted `when_not_to_use` clause on the *wrongly-chosen* graph: `"Do not use for requests like \"<utterance>\" — that is the <correctGraph> graph."`.
    - `CorrectionPair` (slot edit) does not change graph selection, so it grows neither the eval set nor the clauses here (it is slot-fill training data; recorded but not used for router calibration).

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/NightlyBatchTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.eval.EvalCase
import org.junit.jupiter.api.Test

class NightlyBatchTest {
  private val seed = listOf(EvalCase("configure 2 units of SKU-1", "configure"))

  @Test
  fun `confirmed turns append new golden cases to the eval set`() {
    val labels =
      listOf(
        FeedbackLabel.Positive(Proposal("price configuration cfg-1", "price", mapOf("configId" to "cfg-1")))
      )
    val out = NightlyBatch.run(seed, labels)
    assertThat(out.grownEvalSet).hasSize(2)
    assertThat(out.grownEvalSet).contains(EvalCase("price configuration cfg-1", "price"))
  }

  @Test
  fun `a reject with a known correct graph drafts a when_not_to_use clause and a regression case`() {
    val labels =
      listOf(
        FeedbackLabel.Negative(
          Proposal("quote me a price for this config", "quote", emptyMap()),
          correctGraph = "price",
        )
      )
    val out = NightlyBatch.run(seed, labels)
    // A regression eval case pinning the correct answer.
    assertThat(out.grownEvalSet).contains(EvalCase("quote me a price for this config", "price"))
    // A drafted clause on the WRONGLY-chosen graph (quote), pointing at the correct one (price).
    assertThat(out.draftedClauses.keys).contains("quote")
    assertThat(out.draftedClauses["quote"]!!.single()).contains("price")
  }

  @Test
  fun `a slot correction does not change the eval set or clauses`() {
    val labels =
      listOf(
        FeedbackLabel.CorrectionPair(
          Proposal("configure 2 units of SKU-1", "configure", mapOf("quantity" to "2")),
          correctedSlots = mapOf("quantity" to "5"),
        )
      )
    val out = NightlyBatch.run(seed, labels)
    assertThat(out.grownEvalSet).isEqualTo(seed)
    assertThat(out.draftedClauses).isEmpty()
  }

  @Test
  fun `dedupes an already-present utterance`() {
    val labels =
      listOf(FeedbackLabel.Positive(Proposal("configure 2 units of SKU-1", "configure", emptyMap())))
    val out = NightlyBatch.run(seed, labels)
    assertThat(out.grownEvalSet).hasSize(1)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*NightlyBatchTest"`
Expected: FAIL — unresolved references `NightlyBatch`, `BatchOutput`.

- [ ] **Step 3: Write `NightlyBatch`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/feedback/NightlyBatch.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.salesforce.revoman.harness.eval.EvalCase

/** What a nightly batch produces: the grown eval set and clauses drafted per wrongly-chosen graph. */
data class BatchOutput(
  val grownEvalSet: List<EvalCase>,
  val draftedClauses: Map<String, List<String>>,
)

/**
 * The nightly feedback batch: turns the confirm-gate labels into a bigger eval set and drafted
 * `when_not_to_use` clauses — the flywheel that makes the CI gate tighten itself over time.
 * Confirmed turns become positive golden cases; rejects with a known-right graph become both a
 * regression case (so that exact miss never returns) and a drafted clause on the graph that was
 * wrongly chosen. Slot corrections are slot-fill training data and do not affect router selection.
 */
object NightlyBatch {
  fun run(seedEvalSet: List<EvalCase>, labels: List<FeedbackLabel>): BatchOutput {
    val seenUtterances = seedEvalSet.map { it.utterance }.toMutableSet()
    val grown = seedEvalSet.toMutableList()
    val clauses = mutableMapOf<String, MutableList<String>>()

    labels.forEach { label ->
      when (label) {
        is FeedbackLabel.Positive ->
          addCase(grown, seenUtterances, label.proposal.utterance, label.proposal.graph)
        is FeedbackLabel.Negative -> {
          val correct = label.correctGraph ?: return@forEach
          addCase(grown, seenUtterances, label.proposal.utterance, correct)
          val clause =
            "Do not use for requests like \"${label.proposal.utterance}\" — " +
              "that is the $correct graph."
          clauses.getOrPut(label.proposal.graph) { mutableListOf() }.add(clause)
        }
        is FeedbackLabel.CorrectionPair -> Unit // slot-fill signal; not a router-selection change
      }
    }
    return BatchOutput(grown.toList(), clauses.mapValues { it.value.toList() })
  }

  private fun addCase(
    into: MutableList<EvalCase>,
    seen: MutableSet<String>,
    utterance: String,
    graph: String,
  ) {
    if (seen.add(utterance)) into.add(EvalCase(utterance, graph))
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*NightlyBatchTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/feedback/NightlyBatch.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/NightlyBatchTest.kt
git commit -m "feat(harness): nightly batch grows eval set + drafts when_not_to_use from rejects"
```

---

### Task 5: `Stage4Demo` — the full flywheel, runnable; + close the loop with a re-eval

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage4Demo.kt`
- Modify: `agentic-harness/build.gradle.kts` (add a `runStage4Demo` JavaExec task)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/FlywheelClosesLoopTest.kt`

**Interfaces:**
- Consumes: everything above; `GenAiTracer` (Task 1), instrumented `Orchestrator` (Task 2), `ConfirmGate`/`Proposal`/`Decision`/`FeedbackLabel` (Task 3), `NightlyBatch`/`BatchOutput` (Task 4); `RouterEvaluator`/`EvalSet`/`ScoringLlmClient` (Stage 3); `GraphRegistry`, `StubLlmClient`, `MockCpqServer`.
- Produces:
  - `fun main()` in `Stage4Demo.kt` + a `runStage4Demo` JavaExec task (mainClass `com.salesforce.revoman.harness.Stage4DemoKt`) that: (1) runs a couple of turns through the instrumented `Orchestrator` with a `GenAiTracer` printing spans to console; (2) sends each executed turn's proposal through the `ConfirmGate` with a scripted mix (confirm, and a reject of the near-miss utterance with `correctGraph = "price"`); (3) runs `NightlyBatch` and prints the grown eval-set size + the drafted `when_not_to_use` clause; (4) closes the loop: evaluate the router on the grown eval set with seed tools vs tools calibrated with the drafted clause, printing the accuracy move ("CI gate tightened").
  - The `FlywheelClosesLoopTest` proves the loop end-to-end deterministically: a rejected near-miss turn → nightly batch drafts a clause + adds the regression case → applying the drafted clause makes the `RouterEvaluator` score the grown set with strictly higher accuracy than the un-calibrated seed tools do.

- [ ] **Step 1: Write the failing loop-closing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/FlywheelClosesLoopTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.eval.EvalCase
import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class FlywheelClosesLoopTest {
  private val evaluator = RouterEvaluator(ScoringLlmClient())
  private val seedTools = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }

  @Test
  fun `a rejected near-miss, mined by the nightly batch, tightens the CI gate`() {
    // The confirm gate rejected the near-miss turn, stating the correct graph is price.
    val labels =
      listOf(
        FeedbackLabel.Negative(
          Proposal("quote me a price for this config", "quote", emptyMap()),
          correctGraph = "price",
        )
      )
    val seedEval = listOf(EvalCase("create a draft quote for prc-2", "quote"))

    val batch = NightlyBatch.run(seedEval, labels)
    // The batch grew the eval set with the regression case and drafted a clause on 'quote'.
    assertThat(batch.grownEvalSet).contains(EvalCase("quote me a price for this config", "price"))
    val drafted = batch.draftedClauses["quote"]!!

    // Apply the drafted clause to the quote tool = the calibrated tools.
    val calibratedTools =
      seedTools.map { if (it.graphName == "quote") it.copy(whenNotToUse = drafted) else it }

    val before = evaluator.evaluate(batch.grownEvalSet, seedTools)
    val after = evaluator.evaluate(batch.grownEvalSet, calibratedTools)

    // The auto-drafted clause strictly improves accuracy on the grown eval set — the flywheel works.
    assertThat(after.matrix.correct).isGreaterThan(before.matrix.correct)
    assertThat(after.misses).isEmpty()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*FlywheelClosesLoopTest"`
Expected: FAIL — it will not compile until `Stage4Demo` exists only if the test referenced it; here the test uses existing classes, so it should compile and FAIL only if the drafted clause does not improve accuracy. If it fails on the assertion, the drafted-clause wording's trigger tokens (`price`, and the near-miss words) must overlap the utterance — confirm `NightlyBatch`'s clause contains the word `price` and the utterance. (If it passes immediately, that is fine — the loop-closing behavior is the deliverable; proceed to the demo.)

- [ ] **Step 3: Write `Stage4Demo`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage4Demo.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.feedback.ConfirmGate
import com.salesforce.revoman.harness.feedback.Decision
import com.salesforce.revoman.harness.feedback.FeedbackLabel
import com.salesforce.revoman.harness.feedback.NightlyBatch
import com.salesforce.revoman.harness.feedback.Proposal
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult
import com.salesforce.revoman.harness.telemetry.GenAiTracer

/**
 * Stage 4 runnable demo: observability + the feedback flywheel, all deterministic (no API key).
 * (1) Traces turns with OpenTelemetry GenAI-convention spans printed to console. (2) Runs each
 * executed proposal through the HITL confirm gate (a scripted confirm + a reject of the near-miss).
 * (3) Runs the nightly batch to grow the eval set and draft a `when_not_to_use` clause. (4) Closes
 * the loop: re-evaluates the router on the grown set, showing the CI gate tighten.
 */
fun main() {
  val tools = GraphRegistry.loadToolDefs()
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  val tracer = GenAiTracer()
  val orchestrator = Orchestrator(baseUrl, tools, StubLlmClient(), tracer)

  try {
    println("=== 1. Traced orchestration turns (OTel GenAI spans to console) ===")
    val executed = orchestrator.orchestrate("configure 2 units of SKU-1")

    println("\n=== 2. HITL confirm gate ===")
    val labels = mutableListOf<FeedbackLabel>()
    if (executed is OrchestrationResult.Executed) {
      val proposal = Proposal("configure 2 units of SKU-1", executed.graph, executed.slots)
      val label = ConfirmGate.apply(proposal, Decision.Confirm)
      labels.add(label)
      println("  confirm -> $label")
    }
    // A human rejects the near-miss the router would get wrong, stating the correct graph.
    val rejectProposal = Proposal("quote me a price for this config", "quote", emptyMap())
    val rejectLabel = ConfirmGate.apply(rejectProposal, Decision.Reject(correctGraph = "price"))
    labels.add(rejectLabel)
    println("  reject  -> $rejectLabel")

    println("\n=== 3. Nightly batch (mine labels -> grow eval set + draft when_not_to_use) ===")
    val seedEval = EvalSet.load()
    val batch = NightlyBatch.run(seedEval, labels)
    println("  eval set grew: ${seedEval.size} -> ${batch.grownEvalSet.size} cases")
    batch.draftedClauses.forEach { (graph, clauses) ->
      clauses.forEach { println("  drafted when_not_to_use for '$graph': $it") }
    }

    println("\n=== 4. Close the loop: re-eval shows the CI gate tighten ===")
    val evaluator = RouterEvaluator(ScoringLlmClient())
    val seedTools = tools.map { it.copy(whenNotToUse = emptyList()) }
    val calibratedTools =
      seedTools.map {
        if (batch.draftedClauses.containsKey(it.graphName))
          it.copy(whenNotToUse = batch.draftedClauses[it.graphName]!!)
        else it
      }
    val before = evaluator.evaluate(batch.grownEvalSet, seedTools)
    val after = evaluator.evaluate(batch.grownEvalSet, calibratedTools)
    println("  accuracy on grown eval set: ${before.matrix.correct}/${before.matrix.total} -> " +
      "${after.matrix.correct}/${after.matrix.total}  (auto-drafted from a rejected turn)")
  } finally {
    server.stop()
  }
}
```

- [ ] **Step 4: Add the run task to the module build file**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runStage4Demo") {
  group = "harness"
  description = "Run the observability + feedback-flywheel demo (spans, confirm gate, nightly batch)"
  mainClass.set("com.salesforce.revoman.harness.Stage4DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 5: Run the loop test, the demo, and the full suite**

Run: `./gradlew :agentic-harness:test --tests "*FlywheelClosesLoopTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

Run: `./gradlew :agentic-harness:runStage4Demo -q`
Expected: section 1 prints an `• invoke_agent` span tree with nested `• chat` (×2) and `• execute_tool` spans carrying `gen_ai.*` attributes + Rundown stats; section 2 prints `confirm -> Positive(...)` and `reject -> Negative(...)`; section 3 prints `eval set grew: 7 -> 8 cases` and a `drafted when_not_to_use for 'quote': Do not use for requests like "quote me a price for this config" — that is the price graph.`; section 4 prints an accuracy move where the calibrated number is strictly higher than the seed number on the grown set.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all Stage 1/2/3/4 test classes pass.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage4Demo.kt \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/feedback/FlywheelClosesLoopTest.kt
git commit -m "feat(harness): Stage 4 flywheel demo — spans, confirm gate, nightly batch, loop close"
```

---

## Self-Review

**Spec coverage (Stage 4 rows of the design's concept-to-component map):**
- OTel GenAI-style spans (`invoke_agent`/`execute_tool`/`chat`) to console → Task 1 (`GenAiTracer`) + Task 2 (instrumented `Orchestrator`). ✓
- HITL confirm gate: confirm/edit/reject → positive/correction-pair/negative labels → Task 3 (`ConfirmGate`). ✓
- Nightly-batch: append confirmed turns to eval set + draft `when_not_to_use` from confusion pairs → Task 4 (`NightlyBatch`). ✓
- "CI gate tightening": the flywheel demonstrably raises accuracy on the grown set → Task 5 (`FlywheelClosesLoopTest` + demo section 4). ✓
- Runnable proof: `runStage4Demo` (no key) → Task 5. ✓

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every code step shows complete code. ✓

**Type consistency:** `Tracer`/`SpanScope`/`NoopTracer`/`GenAiTracer`/`Span` + `invokeAgentAttrs`/`chatAttrs`/`executeToolAttrs` (Task 1) consumed by Task 2 and Task 5. `Orchestrator(baseUrl, tools, llm, tracer = NoopTracer())` (Task 2) — the 4th param is defaulted so Stage 1-3's 3-arg constructions are unchanged. `Proposal`/`Decision.{Confirm,Edit,Reject}`/`FeedbackLabel.{Positive,CorrectionPair,Negative}`/`ConfirmGate.apply` (Task 3) consumed by Task 4 (`NightlyBatch.run(seedEvalSet, labels)`) and Task 5. `BatchOutput(grownEvalSet, draftedClauses)` (Task 4) consumed by Task 5. Reuses verified Stage 3 APIs (`RouterEvaluator.evaluate`, `EvalSet.load`, `ScoringLlmClient`, `EvalCase`, `ConfusionMatrix.correct/total`) and Stage 1/2 (`GraphRegistry.loadToolDefs`, `StubLlmClient`, `MockCpqServer`, `ToolDef.copy`, `Rundown.executedStepCount/stopReason/unsuccessfulStepCount`). ✓

**Backward-compatibility check (the one existing-code modification):** `Orchestrator` gains only a defaulted 4th param and internal span wrapping; its 3-arg construction and `orchestrate` return values are identical, so `OrchestratorTest`, `TauBenchCheckTest`, `LlmJudgeTest`, `BfclCheck` (which builds no Orchestrator), and all demos remain correct. Task 2 Step 4 explicitly re-runs the whole suite to confirm. ✓

**Flywheel correctness (hand-verified):** the drafted clause for the reject is `"Do not use for requests like \"quote me a price for this config\" — that is the price graph."`. Its trigger tokens (length ≥ 4, after tokenize) include `price`, `config`, `graph`, `quote`, `requests`, `like` — `price` and `config` and `quote` all appear as tokens in the target utterance `"quote me a price for this config"`, so `ScoringLlmClient` applies the −5 penalty to the quote tool for that utterance, flipping it to price. On the grown eval set (which now contains the regression case `("quote me a price for this config" → price)`), seed tools mis-route it (quote) while calibrated tools route it correctly (price) → `after.correct > before.correct`. ✓

**Known risks flagged in-plan:** Task 2 must keep the suite green (defaulted param — explicitly re-run); Task 5 Step 2 notes the loop test may pass immediately (acceptable — the behavior is the deliverable).

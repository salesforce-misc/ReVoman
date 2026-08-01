# GraphContract (Blue-Box Unified Contract) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the "blue box" from the architecture diagram — the **unified, deterministic contract** that feeds the LLM — by fusing the two halves that today live as disjoint artifacts: the pre-execution **metadata descriptor** (`ToolDef`) and the post-execution **runtime information** (`Rundown`), plus the **data-lineage** (which `{{var}}` came from which step) that ReVoman captures but never surfaces. One composite type, one tiered renderer, one version envelope.

**Architecture:** A new `contract` package in `agentic-harness/src/main`. `GraphContract` composes `{descriptor: ToolDef, invocation: filled slots, outcome: Rundown}`; a renderer emits both halves + a `dataFlow` provenance block at matched `Verbosity` tiers; a `contractVersion` envelope stamps drift. All deterministic, koog-free, CI-safe. This is the ReVoman-owned half; it does not touch the reasoning/gate (green-box) code. The existing `Orchestrator` is updated to build a `GraphContract` for its `Executed` result (additive; the loose `context` string is superseded by `GraphContract.toJson`).

**Tech Stack:** Kotlin (JVM 21), the `agentic-harness` module, ReVoman (`project(":")`), JUnit5 + Google Truth. No koog, no new deps.

## Global Constraints

- **JDK 21**; never modify the ReVoman library; all work inside `agentic-harness/`.
- **No koog / kotlinx.coroutines / OTel-SDK** in any `src/main` or `src/test` file. Default `test`/`build`/`check` stays green and never compiles the `claude` source set.
- **All prior work stays green** after every task: `./gradlew :agentic-harness:test` passes every prior + new test class. Reasoning-layer Tasks 1–2 (RouteDecision confidence, RetrievalPreFilter) are already merged; do not disturb them.
- **Copyright header** (SFDC Apache-2.0) on every new `.kt` (copy from any existing `agentic-harness/**/*.kt`); JUnit5 + Google Truth; ktfmt Google style (`./gradlew spotlessApply` before every commit).
- **Verified facts (do not re-derive):**
  - `tooldef.ToolDef(graphName: String, whenToUse: String, whenNotToUse: List<String>, exampleQueries: List<String>, inputExamples: List<Map<String,String>>, slots: Map<String, SlotSchema>)` — data class.
  - `output.Rundown` (`Rundown.kt:16`): `@JvmField stepReports: List<StepReport>`, `mutableEnv`, `stopReason: StopReason`; lazy `executedStepCount`, `unsuccessfulStepCount`, `areAllStepsSuccessful`, `firstUnIgnoredUnsuccessfulStepReport`. `immutableEnv: Map<String,Any?>`.
  - `output.report.StepReport` (`StepReport.kt:30`): `@JvmField step: Step`, `@JvmField envVars: StepEnvVars`, `isSuccessful: Boolean`.
  - `output.report.StepEnvVars(produced: Set<String> = emptySet(), consumed: Set<String> = emptySet())` (`StepEnvVars.kt:14`) — `produced` = keys written via `pm.environment.set`; `consumed` = keys read via `{{key}}`.
  - `output.report.Step` (`Step.kt`): `@JvmField name: String`.
  - `output.Verbosity { SUMMARY, STANDARD, VERBOSE }` (`Verbosity.kt:17`); `fun Rundown.toJson(verbosity: Verbosity = STANDARD): String` is an extension in package `com.salesforce.revoman.output` (`RundownJsonWriter.kt:28`).
  - `orchestrator.OrchestrationResult.Executed(graph: String, slots: Map<String,String>, rundowns: List<Rundown>, context: String)` (`Orchestrator.kt`). `Orchestrator.orchestrate` currently sets `context = rundowns.joinToString("\n") { it.toJson(Verbosity.SUMMARY) }`.
  - `orchestrator.GraphRegistry.loadToolDefs(): List<ToolDef>`; `mock.MockCpqServer` (`start(): Int`, `stop()`, `db`); `GraphRunner.runChain(baseUrl, graphs, seedEnv): List<Rundown>`.
- **Contract of "unified":** the emitted JSON must, in one object, show for a graph: its 4-field descriptor identity + the slots it was invoked with + its runtime outcome (steps/stopReason/success) + a `dataFlow` array mapping each produced/consumed env key to the step that touched it + a `contractVersion`.

---

### Task 1: `DataFlowEdge` + `GraphContract` composite type + version envelope

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/DataFlowEdge.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/GraphContract.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/GraphContractTest.kt`

**Interfaces:**
- Consumes: `ToolDef` (tooldef), `Rundown` + `StepReport` + `StepEnvVars` + `Step` (ReVoman output), `MockCpqServer`/`GraphRunner`/`GraphRegistry` (for the test's real Rundown).
- Produces:
  - `data class DataFlowEdge(val key: String, val producedByStep: String?, val consumedBySteps: List<String>)` — one env var's provenance: which step wrote it, which steps read it.
  - `data class GraphContract(val descriptor: ToolDef, val invocationSlots: Map<String, String>, val outcome: Rundown, val contractVersion: String = CONTRACT_VERSION)` with:
    - `companion object { const val CONTRACT_VERSION = "1.0" }`
    - `val dataFlow: List<DataFlowEdge> by lazy { ... }` — computed from `outcome.stepReports`: for every key in any step's `envVars.produced ∪ consumed`, the producing step is the (first) step whose `envVars.produced` contains it, and consumers are all steps whose `envVars.consumed` contains it. Sorted by key.
    - `val succeeded: Boolean get() = outcome.areAllStepsSuccessful`
  - Factory `GraphContract.of(descriptor: ToolDef, invocationSlots: Map<String,String>, outcome: Rundown): GraphContract` (thin; for Java-friendly call sites and clarity).

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/GraphContractTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphContractTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  private fun configureContract(): GraphContract {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val rundowns = GraphRunner.runChain(baseUrl, listOf("configure"), seedEnv = slots)
    return GraphContract.of(descriptor, slots, rundowns.single())
  }

  @Test
  fun `fuses descriptor, invocation slots, and runtime outcome into one object`() {
    val contract = configureContract()
    assertThat(contract.descriptor.graphName).isEqualTo("configure")
    assertThat(contract.invocationSlots).containsEntry("productCode", "SKU-1")
    assertThat(contract.succeeded).isTrue()
    assertThat(contract.contractVersion).isEqualTo("1.0")
  }

  @Test
  fun `dataFlow exposes provenance — configId was produced by the configure step`() {
    val contract = configureContract()
    val configIdEdge = contract.dataFlow.firstOrNull { it.key == "configId" }
    assertThat(configIdEdge).isNotNull()
    assertThat(configIdEdge!!.producedByStep).isNotNull()
    // The producing step's name identifies where the value came from.
    assertThat(configIdEdge.producedByStep).contains("configure")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*GraphContractTest"`
Expected: FAIL — unresolved references `GraphContract`, `DataFlowEdge`.

- [ ] **Step 3: Write `DataFlowEdge`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/DataFlowEdge.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

/**
 * The provenance of one environment variable within a graph run: which step WROTE it
 * (`pm.environment.set`), and which steps READ it (`{{key}}`). This is the data-lineage ReVoman
 * captures in [com.salesforce.revoman.output.report.StepEnvVars] but never surfaces — the piece
 * that lets the contract explain "where did this value come from".
 */
data class DataFlowEdge(
  val key: String,
  val producedByStep: String?,
  val consumedBySteps: List<String>,
)
```

- [ ] **Step 4: Write `GraphContract`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/GraphContract.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.output.Rundown

/**
 * The blue-box unified contract: the single deterministic artifact that feeds the LLM, fusing the
 * two halves that otherwise live apart —
 *  - the pre-execution METADATA descriptor ([descriptor], a [ToolDef]: when_to_use / when_not_to_use
 *    / example_queries / input_examples + typed slots), and
 *  - the post-execution RUNTIME information ([outcome], a ReVoman [Rundown]: steps, stopReason,
 *    per-step request/response, env) —
 * plus [invocationSlots] (what the graph was actually called with) and [dataFlow] (provenance: which
 * `{{var}}` came from which step). A [contractVersion] stamps the schema so consumers detect drift.
 *
 * Deterministic and testable like ordinary software — the ReVoman-owned half of the system. The
 * probabilistic reasoning that CONSUMES this contract (router / confidence gate) is a separate layer.
 */
data class GraphContract(
  val descriptor: ToolDef,
  val invocationSlots: Map<String, String>,
  val outcome: Rundown,
  val contractVersion: String = CONTRACT_VERSION,
) {
  val succeeded: Boolean
    get() = outcome.areAllStepsSuccessful

  /** Per-env-key provenance derived from each step's produced/consumed sets. */
  val dataFlow: List<DataFlowEdge> by lazy {
    val keys =
      outcome.stepReports.flatMap { it.envVars.produced + it.envVars.consumed }.toSortedSet()
    keys.map { key ->
      val producer = outcome.stepReports.firstOrNull { key in it.envVars.produced }?.step?.name
      val consumers =
        outcome.stepReports.filter { key in it.envVars.consumed }.map { it.step.name }.distinct()
      DataFlowEdge(key, producer, consumers)
    }
  }

  companion object {
    const val CONTRACT_VERSION: String = "1.0"

    fun of(descriptor: ToolDef, invocationSlots: Map<String, String>, outcome: Rundown): GraphContract =
      GraphContract(descriptor, invocationSlots, outcome)
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*GraphContractTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

If `dataFlow` for `configId` has a null `producedByStep`: the mock/graph produced it via `pm.environment.set("configId", ...)` in the configure step's afterResponse script, which ReVoman records in `envVars.produced`. If the set is empty, verify the step actually ran successfully (the test seeds valid slots, so it should). Do not weaken the assertion.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/DataFlowEdge.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/GraphContract.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/GraphContractTest.kt
git commit -m "feat(harness): GraphContract fuses ToolDef descriptor + Rundown + data-lineage"
```

---

### Task 2: `GraphContractWriter` — tiered `toJson(Verbosity)` renderer (both halves + dataFlow)

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/GraphContractWriter.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/GraphContractWriterTest.kt`

**Interfaces:**
- Consumes: `GraphContract`, `DataFlowEdge` (Task 1); `output.Verbosity`; `output.toJson` (the existing Rundown renderer).
- Produces:
  - `fun GraphContract.toJson(verbosity: Verbosity = Verbosity.STANDARD): String` — an extension in package `com.salesforce.revoman.harness.contract`. Hand-built JSON (no new dep; mirror the style of the harness's existing small JSON emitters), emitting a single object:
    - always: `contractVersion`, `graph` (descriptor.graphName), `succeeded`, `invocationSlots`.
    - SUMMARY: + `stopReason`, `executedStepCount`, `unsuccessfulStepCount` (from `outcome`).
    - STANDARD (adds to SUMMARY): + `whenToUse`, `whenNotToUse`, `dataFlow` (array of `{key, producedByStep, consumedBySteps}`).
    - VERBOSE (adds to STANDARD): + `exampleQueries`, `inputExamples`, and the full nested `rundown` = `outcome.toJson(Verbosity.VERBOSE)` (reuse the ReVoman renderer, embedded).
  - Keep it a pure string builder; escape strings minimally (the values here are graph names, keys, enum names — no embedded quotes expected, but escape `"` and `\` defensively).

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/GraphContractWriterTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import com.salesforce.revoman.output.Verbosity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphContractWriterTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  private fun contract(): GraphContract {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val rundowns = GraphRunner.runChain(baseUrl, listOf("configure"), seedEnv = slots)
    return GraphContract.of(descriptor, slots, rundowns.single())
  }

  @Test
  fun `summary carries version, graph, invocation slots, and outcome stats but not descriptions`() {
    val json = contract().toJson(Verbosity.SUMMARY)
    assertThat(json).contains("\"contractVersion\"")
    assertThat(json).contains("\"graph\"")
    assertThat(json).contains("configure")
    assertThat(json).contains("\"invocationSlots\"")
    assertThat(json).contains("\"stopReason\"")
    // SUMMARY omits the heavy descriptor prose and dataFlow.
    assertThat(json).doesNotContain("\"whenToUse\"")
    assertThat(json).doesNotContain("\"dataFlow\"")
  }

  @Test
  fun `standard adds descriptor whenToUse and the dataFlow provenance block`() {
    val json = contract().toJson(Verbosity.STANDARD)
    assertThat(json).contains("\"whenToUse\"")
    assertThat(json).contains("\"dataFlow\"")
    assertThat(json).contains("configId") // a produced key appears in the lineage
  }

  @Test
  fun `verbose embeds the full nested rundown json`() {
    val json = contract().toJson(Verbosity.VERBOSE)
    assertThat(json).contains("\"rundown\"")
    assertThat(json).contains("\"exampleQueries\"")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*GraphContractWriterTest"`
Expected: FAIL — unresolved reference `toJson` on `GraphContract`.

- [ ] **Step 3: Write `GraphContractWriter`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/GraphContractWriter.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson

/**
 * Renders a [GraphContract] to JSON at matched [Verbosity] tiers — the single serializer that emits
 * BOTH halves of the unified contract (metadata descriptor + runtime outcome) plus the data-lineage
 * and version envelope. SUMMARY is a health check; STANDARD adds the descriptions and the dataFlow
 * provenance; VERBOSE embeds the full nested ReVoman [com.salesforce.revoman.output.Rundown] JSON.
 */
fun GraphContract.toJson(verbosity: Verbosity = Verbosity.STANDARD): String {
  val sb = StringBuilder()
  sb.append("{")
  sb.append("\"contractVersion\":").append(str(contractVersion))
  sb.append(",\"graph\":").append(str(descriptor.graphName))
  sb.append(",\"succeeded\":").append(succeeded)
  sb.append(",\"invocationSlots\":").append(strMap(invocationSlots))
  // SUMMARY-and-up: runtime stats.
  sb.append(",\"stopReason\":").append(str(outcome.stopReason.toString()))
  sb.append(",\"executedStepCount\":").append(outcome.executedStepCount)
  sb.append(",\"unsuccessfulStepCount\":").append(outcome.unsuccessfulStepCount)

  if (verbosity == Verbosity.STANDARD || verbosity == Verbosity.VERBOSE) {
    sb.append(",\"whenToUse\":").append(str(descriptor.whenToUse))
    sb.append(",\"whenNotToUse\":").append(strList(descriptor.whenNotToUse))
    sb.append(",\"dataFlow\":").append(dataFlowJson())
  }
  if (verbosity == Verbosity.VERBOSE) {
    sb.append(",\"exampleQueries\":").append(strList(descriptor.exampleQueries))
    sb.append(",\"inputExamples\":").append(strMapList(descriptor.inputExamples))
    // Embed the full ReVoman runtime JSON as a nested object (already valid JSON).
    sb.append(",\"rundown\":").append(outcome.toJson(Verbosity.VERBOSE))
  }
  sb.append("}")
  return sb.toString()
}

private fun GraphContract.dataFlowJson(): String =
  dataFlow.joinToString(",", "[", "]") { edge ->
    "{\"key\":${str(edge.key)}," +
      "\"producedByStep\":${edge.producedByStep?.let { str(it) } ?: "null"}," +
      "\"consumedBySteps\":${strList(edge.consumedBySteps)}}"
  }

private fun str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun strList(xs: List<String>): String = xs.joinToString(",", "[", "]") { str(it) }

private fun strMap(m: Map<String, String>): String =
  m.entries.joinToString(",", "{", "}") { "${str(it.key)}:${str(it.value)}" }

private fun strMapList(ms: List<Map<String, String>>): String =
  ms.joinToString(",", "[", "]") { strMap(it) }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*GraphContractWriterTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/GraphContractWriter.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/GraphContractWriterTest.kt
git commit -m "feat(harness): tiered GraphContract JSON renderer (descriptor + runtime + dataFlow)"
```

---

### Task 3: Wire `GraphContract` into the orchestrator + runnable `ContractDemo`

**Files:**
- Modify: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/ContractDemo.kt`
- Modify: `agentic-harness/build.gradle.kts` (add `runContractDemo` JavaExec task)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorContractTest.kt`

**Interfaces:**
- Consumes: `GraphContract` + `toJson` (Tasks 1–2); existing `Orchestrator`, `OrchestrationResult`, `GraphRegistry`, `MockCpqServer`.
- Produces:
  - `OrchestrationResult.Executed` gains one field: `val contract: GraphContract?` — **defaulted null** so existing constructions/tests are unaffected. The `context` field stays (backward compat). When the orchestrator executes, it builds `GraphContract.of(tool, slots, rundowns.last())` and sets both `contract` and (for continuity) `context = contract.toJson(Verbosity.STANDARD)`.
  - `fun main()` in `ContractDemo.kt` + a `runContractDemo` JavaExec task (mainClass `com.salesforce.revoman.harness.ContractDemoKt`) that orchestrates one intent, then prints the same contract at SUMMARY, STANDARD, and VERBOSE so the tiers are visible side by side — the "unified contract in action".

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorContractTest.kt`:

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
import com.salesforce.revoman.harness.contract.toJson
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.output.Verbosity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorContractTest {
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
  fun `an executed turn carries a unified GraphContract`() {
    val result =
      Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")
    val executed = result as OrchestrationResult.Executed

    assertThat(executed.contract).isNotNull()
    val contract = executed.contract!!
    assertThat(contract.descriptor.graphName).isEqualTo("configure")
    assertThat(contract.invocationSlots).containsEntry("quantity", "2")
    assertThat(contract.succeeded).isTrue()

    // The unified contract renders both halves + provenance.
    val json = contract.toJson(Verbosity.STANDARD)
    assertThat(json).contains("\"whenToUse\"")
    assertThat(json).contains("\"dataFlow\"")
    assertThat(json).contains("configId")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*OrchestratorContractTest"`
Expected: FAIL — `OrchestrationResult.Executed.contract` does not exist.

- [ ] **Step 3: Add `contract` to `Executed` and build it in the orchestrator**

In `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt`:

Add the import:
```kotlin
import com.salesforce.revoman.harness.contract.GraphContract
import com.salesforce.revoman.harness.contract.toJson
```

Change the `Executed` data class to add the defaulted field (keep the other members):
```kotlin
  data class Executed(
    val graph: String,
    val slots: Map<String, String>,
    val rundowns: List<Rundown>,
    val context: String,
    val contract: GraphContract? = null,
  ) : OrchestrationResult
```

In the `execute_tool` span block, after `rundowns` is obtained and before building the result, build the contract and use it for the context (locate the block that currently does `val context = rundowns.joinToString("\n") { it.toJson(Verbosity.SUMMARY) }` and returns `OrchestrationResult.Executed(graphName, slots, rundowns, context)`), replacing it with:
```kotlin
        val contract = GraphContract.of(tool, slots, rundowns.last())
        val context = contract.toJson(Verbosity.STANDARD)
        OrchestrationResult.Executed(graphName, slots, rundowns, context, contract)
```
(Here `tool` is the `ToolDef` already resolved earlier in `orchestrate` as `tools.firstOrNull { it.graphName == graphName }`. If that local is not in scope inside the span lambda, resolve it again: `val tool = tools.first { it.graphName == graphName }`.)

Note: the harness's `Rundown.toJson` import may already be present; the contract's `toJson` is a different extension in `harness.contract`. Both imports coexist (different receiver types). If a clash on the simple name `toJson` occurs, import the contract one and call the Rundown one via its package or keep the existing `Verbosity.SUMMARY` Rundown rendering only inside `GraphContractWriter` (it already lives there) — the orchestrator only needs `contract.toJson(...)`.

- [ ] **Step 4: Run the test AND the full suite**

Run: `./gradlew :agentic-harness:test --tests "*OrchestratorContractTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all prior test classes still green (the new `contract` field is defaulted; `OrchestratorTest`, `OrchestratorTracingTest`, `TauBenchCheck`, `LlmJudge`, Stage-3/4 tests unaffected).

- [ ] **Step 5: Write `ContractDemo`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/ContractDemo.kt`:

```kotlin
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
```

- [ ] **Step 6: Add the run task to the module build file**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runContractDemo") {
  group = "harness"
  description = "Print the unified GraphContract (descriptor + runtime + dataFlow) at all verbosity tiers"
  mainClass.set("com.salesforce.revoman.harness.ContractDemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 7: Run the demo and the full suite**

Run: `./gradlew :agentic-harness:runContractDemo -q`
Expected: three blocks — `===== GraphContract @ SUMMARY =====` (version/graph/slots/stopReason, no descriptions), `@ STANDARD` (adds whenToUse/whenNotToUse/dataFlow with a `configId` edge whose `producedByStep` names the configure step), `@ VERBOSE` (adds exampleQueries/inputExamples and a nested `rundown` object).

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — every prior + new test class passes.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/ContractDemo.kt \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorContractTest.kt
git commit -m "feat(harness): orchestrator emits unified GraphContract; runnable ContractDemo"
```

---

### Task 4: `ContractFidelityCheck` — deterministic drift catch (declared vs actual, no LLM)

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/ContractFidelityCheck.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/ContractFidelityCheckTest.kt`

**Why:** The contract's metadata half *declares* what a graph produces/consumes (`GraphSpec.outputKeys`, `ToolDef.slots`); its runtime half shows what *actually* happened (`GraphContract.dataFlow`, from `StepEnvVars`). Fidelity accuracy = do they match? A mismatch means the API drifted under the graph — caught deterministically, with zero LLM cost, before an agent ever reasons over a stale descriptor. This is the design's Layer-1 "same engine tests graphs in CI".

**Interfaces:**
- Consumes: `GraphContract`/`DataFlowEdge` (Task 1); `tooldef.GraphSpec` (`GraphMetadataParser.parse`), `tooldef.ToolDef.slots`.
- Produces:
  - `data class FidelityReport(val graph: String, val missingProduced: Set<String>, val unexpectedProduced: Set<String>, val faithful: Boolean)` — `missingProduced` = keys the metadata DECLARED as outputs (`spec.outputKeys`) that the run did NOT actually produce; `unexpectedProduced` = keys the run produced that the metadata did NOT declare (the drift signal). `faithful = missingProduced.isEmpty() && unexpectedProduced.isEmpty()`.
  - `class ContractFidelityCheck { fun check(contract: GraphContract, spec: GraphSpec): FidelityReport }` — compares `spec.outputKeys.toSet()` against the set of `contract.dataFlow.filter { it.producedByStep != null }.map { it.key }` (actual producers). (Infra keys like `baseUrl`/`accessToken` are never in `outputKeys` and are consumed-only, so they don't appear as false drift.)

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/ContractFidelityCheckTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.GraphSpec
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ContractFidelityCheckTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String
  private val check = ContractFidelityCheck()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  private fun configureContract(): GraphContract {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val rundowns = GraphRunner.runChain(baseUrl, listOf("configure"), seedEnv = slots)
    return GraphContract.of(descriptor, slots, rundowns.single())
  }

  @Test
  fun `a graph whose runtime matches its declared metadata is faithful`() {
    val spec = GraphMetadataParser.parse("configure")
    val report = check.check(configureContract(), spec)
    assertThat(report.faithful).isTrue()
    assertThat(report.missingProduced).isEmpty()
    assertThat(report.unexpectedProduced).isEmpty()
  }

  @Test
  fun `a declared output the run never produced is caught as drift`() {
    // Simulate metadata drift: the spec claims an extra output 'discountId' the graph never sets.
    val driftedSpec =
      GraphMetadataParser.parse("configure").let {
        GraphSpec(it.name, it.description, it.slots, it.outputKeys + "discountId")
      }
    val report = check.check(configureContract(), driftedSpec)
    assertThat(report.faithful).isFalse()
    assertThat(report.missingProduced).contains("discountId")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ContractFidelityCheckTest"`
Expected: FAIL — unresolved references `ContractFidelityCheck`, `FidelityReport`.

- [ ] **Step 3: Write `ContractFidelityCheck`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/ContractFidelityCheck.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.harness.tooldef.GraphSpec

/** Whether a contract's runtime data-flow matches what its metadata declared, and how it drifted. */
data class FidelityReport(
  val graph: String,
  val missingProduced: Set<String>,
  val unexpectedProduced: Set<String>,
) {
  val faithful: Boolean
    get() = missingProduced.isEmpty() && unexpectedProduced.isEmpty()
}

/**
 * The blue-box Layer-1 eval: deterministic, no LLM. Compares what the graph's metadata DECLARES it
 * produces ([GraphSpec.outputKeys]) against what the run ACTUALLY produced (the contract's data-flow
 * producers). Any mismatch is API drift — the production contract changing under the graph — caught
 * in CI before an agent ever reasons over a stale descriptor.
 */
class ContractFidelityCheck {
  fun check(contract: GraphContract, spec: GraphSpec): FidelityReport {
    val declared = spec.outputKeys.toSet()
    val actual = contract.dataFlow.filter { it.producedByStep != null }.map { it.key }.toSet()
    return FidelityReport(
      graph = spec.name,
      missingProduced = declared - actual,
      unexpectedProduced = actual - declared,
    )
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*ContractFidelityCheckTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

If the "faithful" test fails with `unexpectedProduced` non-empty: the configure graph's afterResponse script sets a key beyond `configId` (e.g. it might set nothing else — check `graphs/configure/configure-product.request.yaml`). If the graph genuinely produces exactly `configId` and `GraphMetadataParser` declares exactly that, the sets match. Do not weaken the assertion; if there is a real extra produced key, that is a true finding — add it to the graph's declared outputs or report it.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/ContractFidelityCheck.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/ContractFidelityCheckTest.kt
git commit -m "feat(harness): ContractFidelityCheck — deterministic API-drift catch (declared vs actual)"
```

---

### Task 5: `ContractAblationEval` — vary the contract, measure accuracy delta + runnable eval demo

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/ContractAblationEval.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/ContractEvalDemo.kt`
- Modify: `agentic-harness/build.gradle.kts` (add `runContractEvalDemo` JavaExec task)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/ContractAblationEvalTest.kt`

**Why:** The blue box exists to feed the LLM, so its efficacy is measured by holding the reasoning fixed and varying the contract's content: does a richer descriptor make the green box more accurate? This reuses the Stage-3 confusion matrix as the measuring instrument — grading the contract by how well it makes reasoning work — and is exactly the calibration loop the design describes, now driven by the *contract* rather than a hand-edit.

**Interfaces:**
- Consumes: `eval.RouterEvaluator`, `eval.EvalSet`, `eval.EvalReport`, `eval.ConfusionMatrix` (Stage 3); `llm.ScoringLlmClient`; `tooldef.ToolDef` (data class, `.copy`).
- Produces:
  - `data class AblationResult(val variantName: String, val accuracyCorrect: Int, val accuracyTotal: Int)` with `val accuracy: Double get() = if (accuracyTotal == 0) 0.0 else accuracyCorrect.toDouble() / accuracyTotal`.
  - `class ContractAblationEval(private val evaluator: RouterEvaluator = RouterEvaluator(ScoringLlmClient())) { fun compare(cases: List<EvalCase>, variantA: Pair<String, List<ToolDef>>, variantB: Pair<String, List<ToolDef>>): Pair<AblationResult, AblationResult> }` — runs the same eval set through the router twice, once per tool-def variant, and returns both results (so the caller sees the delta). Each variant is a `(name, tools)` pair.
  - (This is the generalization of Stage 3's manual "add a when_not_to_use clause and re-run": now any contract-content difference between two `List<ToolDef>` variants is measured against the same eval set.)

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/ContractAblationEvalTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class ContractAblationEvalTest {
  private val cases = EvalSet.load()

  @Test
  fun `enriching the contract (adding when_not_to_use) improves accuracy on the eval set`() {
    // Variant A: descriptors with when_not_to_use STRIPPED (the poorer contract).
    val stripped = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }
    // Variant B: enrich the 'quote' descriptor with the disambiguating clause (the richer contract).
    val enriched =
      stripped.map {
        if (it.graphName == "quote")
          it.copy(whenNotToUse = listOf("Do not use when the user asks for a price or cost — that is the price graph."))
        else it
      }

    val (a, b) =
      ContractAblationEval().compare(cases, "stripped" to stripped, "enriched" to enriched)

    assertThat(a.variantName).isEqualTo("stripped")
    assertThat(b.variantName).isEqualTo("enriched")
    // The richer contract scores strictly higher — the blue box improved reasoning accuracy.
    assertThat(b.accuracyCorrect).isGreaterThan(a.accuracyCorrect)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ContractAblationEvalTest"`
Expected: FAIL — unresolved references `ContractAblationEval`, `AblationResult`.

- [ ] **Step 3: Write `ContractAblationEval`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/ContractAblationEval.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.harness.eval.EvalCase
import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.tooldef.ToolDef

/** Graph-selection accuracy for one contract variant on a fixed eval set. */
data class AblationResult(val variantName: String, val accuracyCorrect: Int, val accuracyTotal: Int) {
  val accuracy: Double
    get() = if (accuracyTotal == 0) 0.0 else accuracyCorrect.toDouble() / accuracyTotal
}

/**
 * The blue-box efficacy eval: hold the reasoning fixed, vary the CONTRACT content, and measure the
 * accuracy delta on the same labeled eval set — reusing the Stage-3 confusion matrix as the
 * instrument. This grades the contract by how well it makes the reasoning work, and is the
 * generalization of the manual calibration loop: any difference between two `ToolDef` variants
 * (richer descriptions, added examples, disambiguating clauses) is measured, not guessed.
 */
class ContractAblationEval(
  private val evaluator: RouterEvaluator = RouterEvaluator(ScoringLlmClient())
) {
  fun compare(
    cases: List<EvalCase>,
    variantA: Pair<String, List<ToolDef>>,
    variantB: Pair<String, List<ToolDef>>,
  ): Pair<AblationResult, AblationResult> = result(cases, variantA) to result(cases, variantB)

  private fun result(cases: List<EvalCase>, variant: Pair<String, List<ToolDef>>): AblationResult {
    val report = evaluator.evaluate(cases, variant.second)
    return AblationResult(variant.first, report.matrix.correct, report.matrix.total)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*ContractAblationEvalTest"`
Expected: BUILD SUCCESSFUL, 1 test passed (enriched > stripped, mirroring the Stage-3 6/7 → 7/7 result).

- [ ] **Step 5: Write `ContractEvalDemo`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/ContractEvalDemo.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.contract.ContractAblationEval
import com.salesforce.revoman.harness.contract.ContractFidelityCheck
import com.salesforce.revoman.harness.contract.GraphContract
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.GraphSpec
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator

/**
 * Blue-box eval demo: how we TEST and IMPROVE the unified contract, deterministic (no key).
 * (1) Fidelity — the contract's runtime matches its declared metadata (Layer-1, no LLM).
 * (2) Simulated drift — a declared output the run never produces is caught.
 * (3) Efficacy — enriching the contract raises graph-selection accuracy on the eval set.
 */
fun main() {
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val contract =
      GraphContract.of(descriptor, slots, GraphRunner.runChain(baseUrl, listOf("configure"), slots).single())
    val check = ContractFidelityCheck()

    println("=== 1. Fidelity: does the contract mirror reality? (no LLM) ===")
    val spec = GraphMetadataParser.parse("configure")
    val ok = check.check(contract, spec)
    println("  configure faithful=${ok.faithful} (declared==actually-produced)")

    println("\n=== 2. Simulated API drift: metadata declares an output the run never produces ===")
    val drifted = GraphSpec(spec.name, spec.description, spec.slots, spec.outputKeys + "discountId")
    val drift = check.check(contract, drifted)
    println("  faithful=${drift.faithful}  missingProduced=${drift.missingProduced}  <-- drift caught in CI")

    println("\n=== 3. Efficacy: enrich the contract, measure accuracy on the eval set ===")
    val cases = EvalSet.load()
    val stripped = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }
    val enriched =
      stripped.map {
        if (it.graphName == "quote")
          it.copy(whenNotToUse = listOf("Do not use when the user asks for a price or cost — that is the price graph."))
        else it
      }
    val (a, b) = ContractAblationEval().compare(cases, "stripped" to stripped, "enriched" to enriched)
    println("  ${a.variantName}: ${a.accuracyCorrect}/${a.accuracyTotal}")
    println("  ${b.variantName}: ${b.accuracyCorrect}/${b.accuracyTotal}")
    println("  contract enrichment moved accuracy: ${a.accuracyCorrect}/${a.accuracyTotal} -> ${b.accuracyCorrect}/${b.accuracyTotal}")
  } finally {
    server.stop()
  }
}
```

- [ ] **Step 6: Add the run task to the module build file**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runContractEvalDemo") {
  group = "harness"
  description = "Blue-box evals: contract fidelity (drift catch) + ablation accuracy delta"
  mainClass.set("com.salesforce.revoman.harness.ContractEvalDemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 7: Run the demo and the full suite**

Run: `./gradlew :agentic-harness:runContractEvalDemo -q`
Expected: section 1 `configure faithful=true`; section 2 `faithful=false  missingProduced=[discountId]  <-- drift caught in CI`; section 3 two accuracy lines and `contract enrichment moved accuracy: 6/7 -> 7/7`.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all prior + new test classes pass.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/contract/ContractAblationEval.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/ContractEvalDemo.kt \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/contract/ContractAblationEvalTest.kt
git commit -m "feat(harness): ContractAblationEval — measure accuracy delta from contract enrichment"
```

---

## Self-Review

**Spec coverage (the blue-box must-haves from the scoping answer):**
1. `GraphContract` composite type → Task 1. ✓
2. `GraphContract.toJson(Verbosity)` tiered renderer → Task 2. ✓
3. `dataFlow` provenance block (surfaces `StepEnvVars.produced/consumed`) → Task 1 (`dataFlow` computed) + Task 2 (rendered). ✓
4. Slots-in ↔ outputs-out binding → Task 1 (`invocationSlots` on the contract) + the `dataFlow` producers = the graph's outputs. ✓
5. `contractVersion` envelope → Task 1 (`CONTRACT_VERSION = "1.0"`, rendered in every tier). ✓
- Wired into the live orchestrator + runnable proof → Task 3. ✓
- **Blue-box test + improve accuracy (the eval story):** fidelity/drift catch (deterministic, no LLM) → Task 4 (`ContractFidelityCheck`); efficacy (vary the contract, measure accuracy delta on the eval set via the Stage-3 confusion matrix) → Task 5 (`ContractAblationEval`). Runnable proof shows fidelity-pass, a simulated drift-fail, and a 6/7 → 7/7 enrichment move → Task 5 (`ContractEvalDemo`). ✓
- Nice-to-haves (mermaid preview, OTel nesting) intentionally deferred — not in this plan (YAGNI for the keystone).

**Eval-scaffold type consistency:** `FidelityReport(graph, missingProduced, unexpectedProduced)` + `ContractFidelityCheck.check(contract, spec)` (Task 4) reuse `GraphContract.dataFlow` (Task 1) and `GraphSpec.outputKeys`. `AblationResult(variantName, accuracyCorrect, accuracyTotal)` + `ContractAblationEval.compare(cases, variantA, variantB)` (Task 5) reuse the Stage-3 `RouterEvaluator.evaluate → EvalReport.matrix.correct/total` and `ToolDef.copy`. Both eval scaffolds are deterministic and koog-free; neither modifies shipped code, so all prior stages stay green. ✓

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every step shows complete code.

**Type consistency:** `DataFlowEdge(key, producedByStep, consumedBySteps)` + `GraphContract(descriptor, invocationSlots, outcome, contractVersion)` + `GraphContract.of(...)` (Task 1) consumed by the renderer (Task 2) and the orchestrator + demo (Task 3). `GraphContract.toJson(Verbosity)` (Task 2) called in Tasks 2 and 3. `OrchestrationResult.Executed` gains a defaulted `contract: GraphContract?` (Task 3) — backward-compatible. Reuses verified ReVoman surface: `Rundown.stepReports/areAllStepsSuccessful/executedStepCount/unsuccessfulStepCount/stopReason/toJson`, `StepReport.step.name/envVars`, `StepEnvVars.produced/consumed`, `Verbosity`. ✓

**Backward-compat:** the only shipped-code edit is `OrchestrationResult.Executed` gaining a defaulted `contract` field and the orchestrator building it (the `context` string is now sourced from `contract.toJson(STANDARD)` instead of the raw join — a richer but still-present context; any test asserting `context` contains `areAllStepsSuccessful`/`unsuccessfulStepCount` still passes because the STANDARD contract JSON embeds those runtime stats). Task 3 Step 4 re-runs the whole suite to confirm. ✓

**Known risk flagged in-plan:** Task 3 Step 3 notes the `toJson` name coexistence (Rundown's vs GraphContract's extension) and the `tool` local scope; both have inline resolutions.

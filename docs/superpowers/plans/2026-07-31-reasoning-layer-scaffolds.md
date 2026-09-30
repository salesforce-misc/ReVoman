# Reasoning-Layer Scaffolds Implementation Plan (Delta on `agentic-harness`)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the reasoning layer's six reliability scaffolds as a delta on the existing `agentic-harness` module: add confidence/margin to routing, a retrieval pre-filter, the confidence/disambiguation gate (ask-don't-guess), a confirm-gate preview object, end-to-end `ReasoningLayer` wiring, and a real Claude client wired to this environment's AWS Bedrock proxy (best-effort, gated).

**Architecture:** All new deterministic scaffolds live in `agentic-harness/src/main` (koog-free, CI-safe), driven by the existing `ScoringLlmClient`/`StubLlmClient`. The new `reasoning.ReasoningLayer` is the reasoning-layer-complete entry point (retrieve → route → fill → gate), leaving the Stage-2 `Orchestrator` intact. The real Claude client (Bedrock-proxy) stays in the isolated `claude` source set the default build never compiles.

**Tech Stack:** Kotlin (JVM 21), the `agentic-harness` module, ReVoman (`project(":")`), JUnit5 + Google Truth. koog (`ai.koog:koog-agents:1.1.1` + `koog-agents-additions:1.1.1-beta`) only in the `claude` source set.

## Global Constraints

- **JDK 21**; never modify the ReVoman library; all work inside `agentic-harness/`.
- **No koog / kotlinx.coroutines / OTel-SDK** in any `src/main` or `src/test` file. Default `test`/`build`/`check` stays green and never compiles the `claude` source set.
- **All prior stages (1–4) stay green** after every task: `./gradlew :agentic-harness:test` passes every prior + new test class. The one modification to shipped code (`RouteDecision` gains two fields with defaults) must not break any existing caller.
- **Tests never require an API key.** The real-LLM path is gated on env vars and falls back to a printed skip.
- **Copyright header** (SFDC Apache-2.0) on every new `.kt` (copy from any existing `agentic-harness/**/*.kt`); JUnit5 + Google Truth; ktfmt Google style (`./gradlew spotlessApply` before every commit).
- **Verified facts (do not re-derive):**
  - `llm.RouteDecision` is currently `data class RouteDecision(val graphName: String?, val rationale: String)`. Constructed in `ScoringLlmClient.route` (2 sites) and `StubLlmClient.route` (2 sites) and referenced in tests/`ClaudeLlmClient`.
  - `llm.LlmClient { fun route(utterance, tools: List<ToolDef>): RouteDecision; fun fillSlots(utterance, tool: ToolDef): Map<String,String> }`.
  - `llm.ScoringLlmClient` scores each tool = (# utterance tokens found in `graphName + whenToUse + exampleQueries`, lowercased, substring) − 5×(# `whenNotToUse` clauses with ≥2 matching trigger tokens). `route` picks max; null if max ≤ 0. `tokenize` = lowercase, split `[^a-z0-9]+`, keep len ≥ 3 minus stopwords.
  - `orchestrator.Router(llm, tools).route(utterance): RouteDecision`; `orchestrator.SlotFiller(llm).fill(utterance, tool): FillResult` (`Valid(slots: Map<String,String>)` | `Invalid(errors: List<String>)`).
  - `tooldef.ToolDef(graphName, whenToUse, whenNotToUse: List<String>, exampleQueries: List<String>, inputExamples, slots: Map<String, SlotSchema>)` — data class. `tooldef.SlotSchema(type: SlotType, values: List<String>, required: Boolean)`; `SlotType { STRING, INT, ENUM }`.
  - `orchestrator.GraphRegistry.loadToolDefs(): List<ToolDef>`, `GRAPHS = [configure, price, quote]`.
  - `GraphRunner.runChain(baseUrl, graphs: List<String>, seedEnv: Map<String,Any?>): List<Rundown>`; `mock.MockCpqServer` (`start(): Int`, `stop()`, `db`).
  - `telemetry.GenAiTracer` / `NoopTracer` / `Tracer` + `invokeAgentAttrs`/`chatAttrs`/`executeToolAttrs` (Stage 4).
  - koog (verified in `~/code-clones/oss/koog`): `ai.koog.prompt.executor.llms.all.simpleBedrockExecutorWithBearerToken(bedrockApiKey: String, settings: BedrockClientSettings = ...)`; `ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings(regionName, endpointUrl: String? = null, maxRetries)`; `ai.koog.prompt.executor.clients.bedrock.BedrockModels.AnthropicClaude45Opus: LLModel` (and `AnthropicClaude4Opus`, `AnthropicClaude41Opus`). Both artifacts (`koog-agents`, `koog-agents-additions`) are already on the `claude` source set. `PromptExecutor.execute(prompt, model): suspend -> Message.Assistant` with `.content: String`; build prompts with `ai.koog.prompt.dsl.prompt("id") { system(...); user(...) }`.
  - This environment's Claude access (from `~/.claude/settings.json`, values not printed): `CLAUDE_CODE_USE_BEDROCK=1`, `ANTHROPIC_BEDROCK_BASE_URL` (SFDC gateway), `ANTHROPIC_AUTH_TOKEN` (bearer), model `global.anthropic.claude-opus-4-8[1m]`. There is NO direct `ANTHROPIC_API_KEY`.

---

### Task 1: Add `confidence` + `margin` to `RouteDecision`; expose scores from `ScoringLlmClient`

**Files:**
- Modify: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/LlmClient.kt`
- Modify: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClient.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/RouteConfidenceTest.kt`

**Interfaces:**
- Consumes: existing `RouteDecision`, `ScoringLlmClient`, `ToolDef`.
- Produces:
  - `RouteDecision(graphName: String?, rationale: String, confidence: Double = 0.0, margin: Double = 0.0)` — the two new fields **default**, so `StubLlmClient` and `ClaudeLlmClient` (which construct `RouteDecision(x, y)`) still compile untouched.
  - `ScoringLlmClient.scores(utterance: String, tools: List<ToolDef>): Map<String, Int>` — the raw per-graph score map (public, for the gate + tests).
  - `ScoringLlmClient.route` now fills `confidence` = `top / (sum of positive scores)` (0.0 if none positive), and `margin` = `(top − secondBest) / top` (1.0 if only one positive, 0.0 if top ≤ 0). Both in `[0,1]`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/RouteConfidenceTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class RouteConfidenceTest {
  private val tools = GraphRegistry.loadToolDefs()
  private val scoring = ScoringLlmClient()

  @Test
  fun `an unambiguous utterance routes with high margin`() {
    val decision = scoring.route("configure 2 units of SKU-1", tools)
    assertThat(decision.graphName).isEqualTo("configure")
    assertThat(decision.confidence).isGreaterThan(0.0)
    assertThat(decision.margin).isGreaterThan(0.0)
  }

  @Test
  fun `scores exposes a per-graph score map`() {
    val scores = scoring.scores("configure 2 units of SKU-1", tools)
    assertThat(scores.keys).containsExactly("configure", "price", "quote")
    assertThat(scores["configure"]!!).isGreaterThan(0)
  }

  @Test
  fun `a no-match utterance has zero confidence and margin`() {
    val decision = scoring.route("xyzzy plugh", tools)
    assertThat(decision.graphName).isNull()
    assertThat(decision.confidence).isEqualTo(0.0)
    assertThat(decision.margin).isEqualTo(0.0)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*RouteConfidenceTest"`
Expected: FAIL — `confidence`/`margin`/`scores` unresolved.

- [ ] **Step 3: Add the two fields to `RouteDecision`**

In `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/LlmClient.kt`, replace the `RouteDecision` line with:

```kotlin
/**
 * The router's output: the chosen graph (or null when nothing matched), a short rationale, and the
 * router's confidence signals. [confidence] is the normalized top score in [0,1]; [margin] is the
 * normalized gap to the second-best graph in [0,1]. The disambiguation gate reads [margin] to
 * decide whether to ask instead of guess. Both default so callers that don't produce them (the
 * keyword stub, the Claude client) are unaffected.
 */
data class RouteDecision(
  val graphName: String?,
  val rationale: String,
  val confidence: Double = 0.0,
  val margin: Double = 0.0,
)
```

- [ ] **Step 4: Expose `scores` and fill confidence/margin in `ScoringLlmClient`**

In `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClient.kt`, replace the `route` function and add `scores` (keep `score`, `tokenize`, `fillSlots`, fields unchanged):

```kotlin
  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val scored = scores(utterance, tools)
    val ranked = scored.entries.sortedByDescending { it.value }
    val top = ranked.firstOrNull()
    if (top == null || top.value <= 0) {
      return RouteDecision(null, "no graph scored above zero for: $utterance", 0.0, 0.0)
    }
    val positiveSum = ranked.sumOf { maxOf(it.value, 0) }
    val second = ranked.getOrNull(1)?.value?.coerceAtLeast(0) ?: 0
    val confidence = if (positiveSum > 0) top.value.toDouble() / positiveSum else 0.0
    val margin = (top.value - second).toDouble() / top.value
    return RouteDecision(top.key, "score=${top.value} for '${top.key}'", confidence, margin)
  }

  /** The raw per-graph score for each tool — the basis for confidence, margin, and the gate. */
  fun scores(utterance: String, tools: List<ToolDef>): Map<String, Int> {
    val tokens = tokenize(utterance)
    return tools.associate { it.graphName to score(tokens, it) }
  }
```

- [ ] **Step 5: Run the test AND the full suite (existing callers must stay green)**

Run: `./gradlew :agentic-harness:test --tests "*RouteConfidenceTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all prior stages green (the defaulted fields keep `StubLlmClient`, Stage-3 `RouterEvaluatorTest`, etc. compiling and passing unchanged).

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/LlmClient.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClient.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/RouteConfidenceTest.kt
git commit -m "feat(harness): RouteDecision carries confidence + margin from ScoringLlmClient"
```

---

### Task 2: `RetrievalPreFilter` — top-K candidate narrowing (embedding stub)

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/retrieval/RetrievalPreFilter.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/retrieval/RetrievalPreFilterTest.kt`

**Interfaces:**
- Consumes: `ToolDef`.
- Produces:
  - `object RetrievalPreFilter { fun topK(intent: String, tools: List<ToolDef>, k: Int): List<ToolDef> }` — bag-of-tokens cosine similarity between `intent` and each tool's `whenToUse + " " + exampleQueries`. Returns the `k` highest-similarity tools (ties broken by input order). When `k >= tools.size`, returns `tools` unchanged (the no-op at 3 graphs). When `intent` shares no tokens with any tool, still returns the first `k` (never empty for `k>0`), so routing always has candidates.
  - Tokenizer identical in spirit to `ScoringLlmClient` (lowercase, split `[^a-z0-9]+`, len ≥ 3) — local private copy (do not couple the two).

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/retrieval/RetrievalPreFilterTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.retrieval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.SlotSchema
import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.Test

class RetrievalPreFilterTest {
  private val realTools = GraphRegistry.loadToolDefs()

  @Test
  fun `is a no-op when k is at least the number of tools`() {
    val out = RetrievalPreFilter.topK("configure a product", realTools, k = 3)
    assertThat(out).isEqualTo(realTools)
  }

  @Test
  fun `narrows a large synthetic tool set to the relevant candidates`() {
    // 10 synthetic graphs; only these three should surface for a pricing intent.
    val tools =
      (1..10).map { i ->
        val (name, blurb) =
          when (i) {
            1 -> "price" to "compute the price and total cost of a configuration"
            2 -> "discount" to "apply a pricing discount to a cost total"
            3 -> "tax" to "compute tax on a price total"
            else -> "graph$i" to "unrelated capability number $i about widgets and gadgets"
          }
        ToolDef(name, blurb, emptyList(), listOf(blurb), emptyList(),
          mapOf("x" to SlotSchema(SlotType.STRING)))
      }
    val top3 = RetrievalPreFilter.topK("what is the price and cost total", tools, k = 3)
    assertThat(top3.map { it.graphName }).containsExactly("price", "discount", "tax")
  }

  @Test
  fun `never returns empty for k greater than zero even with no token overlap`() {
    val out = RetrievalPreFilter.topK("zzzz qqqq", realTools, k = 2)
    assertThat(out).hasSize(2)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*RetrievalPreFilterTest"`
Expected: FAIL — unresolved reference `RetrievalPreFilter`.

- [ ] **Step 3: Write `RetrievalPreFilter`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/retrieval/RetrievalPreFilter.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.retrieval

import com.salesforce.revoman.harness.tooldef.ToolDef
import kotlin.math.sqrt

/**
 * Scaffold 4: narrows many graphs to the top-K relevant candidates BEFORE the router reasons, so
 * the model reasons over a small, clean set (the reliability control from `reasoning-layer.md`).
 * The embedding is a local bag-of-tokens cosine stub — enough to prove the seam; a real vector
 * store drops in behind the same `topK` signature. A no-op at 3 graphs (returns all), it
 * demonstrably narrows a larger set.
 */
object RetrievalPreFilter {
  fun topK(intent: String, tools: List<ToolDef>, k: Int): List<ToolDef> {
    if (k >= tools.size) return tools
    val q = vector(intent)
    return tools
      .withIndex()
      .sortedWith(
        compareByDescending<IndexedValue<ToolDef>> { cosine(q, vector(docText(it.value))) }
          .thenBy { it.index }
      )
      .take(k)
      .map { it.value }
  }

  private fun docText(tool: ToolDef): String =
    tool.whenToUse + " " + tool.exampleQueries.joinToString(" ")

  private fun vector(text: String): Map<String, Int> =
    tokenize(text).groupingBy { it }.eachCount()

  private fun cosine(a: Map<String, Int>, b: Map<String, Int>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val dot = a.keys.intersect(b.keys).sumOf { a.getValue(it) * b.getValue(it) }
    val magA = sqrt(a.values.sumOf { it * it }.toDouble())
    val magB = sqrt(b.values.sumOf { it * it }.toDouble())
    return if (magA == 0.0 || magB == 0.0) 0.0 else dot / (magA * magB)
  }

  private fun tokenize(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*RetrievalPreFilterTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/retrieval/RetrievalPreFilter.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/retrieval/RetrievalPreFilterTest.kt
git commit -m "feat(harness): retrieval pre-filter (top-K candidate narrowing, embedding stub)"
```

---

### Task 3: `ConfidencePolicy` + `ActionPreview` + `ReasoningOutcome`

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ConfidencePolicy.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ActionPreview.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningOutcome.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/ConfidencePolicyTest.kt`

**Interfaces:**
- Produces:
  - `data class ConfidencePolicy(val defaultThreshold: Double = 0.60, val perGraphThreshold: Map<String, Double> = emptyMap(), val writeGraphs: Set<String> = setOf("quote")) { fun threshold(graph: String): Double; fun isWrite(graph: String): Boolean }` — `threshold` returns `perGraphThreshold[graph] ?: if (isWrite(graph)) 0.90 else defaultThreshold`. The 0.90 write default is the financial-services band.
  - `data class ActionPreview(val graph: String, val slots: Map<String, String>, val chain: List<String>, val isWrite: Boolean)`.
  - `sealed interface ReasoningOutcome { data class NoMatch(val intent: String); data class Clarify(val question: String, val candidates: List<String>); data class ConfirmRequired(val preview: ActionPreview); data class Proceed(val graph: String, val slots: Map<String, String>) }`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/ConfidencePolicyTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConfidencePolicyTest {
  @Test
  fun `write graphs default to the financial-services 0-90 band`() {
    val policy = ConfidencePolicy()
    assertThat(policy.isWrite("quote")).isTrue()
    assertThat(policy.threshold("quote")).isEqualTo(0.90)
  }

  @Test
  fun `read graphs use the default threshold`() {
    val policy = ConfidencePolicy()
    assertThat(policy.isWrite("configure")).isFalse()
    assertThat(policy.threshold("configure")).isEqualTo(0.60)
  }

  @Test
  fun `per-graph override wins over the write default`() {
    val policy = ConfidencePolicy(perGraphThreshold = mapOf("quote" to 0.50))
    assertThat(policy.threshold("quote")).isEqualTo(0.50)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ConfidencePolicyTest"`
Expected: FAIL — unresolved references `ConfidencePolicy` (and later `ActionPreview`, `ReasoningOutcome`).

- [ ] **Step 3: Write the three files**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ConfidencePolicy.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

/**
 * The tunable policy for the disambiguation gate: below a graph's [threshold] the layer asks
 * instead of guessing. Write graphs default to the financial-services band (0.90) per the industry
 * research; reads use [defaultThreshold]. Per-graph overrides win. The margin/threshold trade is
 * deliberately tunable — that is the whole point of the gate.
 */
data class ConfidencePolicy(
  val defaultThreshold: Double = 0.60,
  val perGraphThreshold: Map<String, Double> = emptyMap(),
  val writeGraphs: Set<String> = setOf("quote"),
) {
  fun isWrite(graph: String): Boolean = graph in writeGraphs

  fun threshold(graph: String): Double =
    perGraphThreshold[graph] ?: if (isWrite(graph)) FINANCIAL_SERVICES_BAND else defaultThreshold

  companion object {
    const val FINANCIAL_SERVICES_BAND: Double = 0.90
  }
}
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ActionPreview.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

/**
 * The confirm gate's proposed-action preview for a write: exactly what would run, shown to the
 * human, executed only on confirm. Carries what `GraphRunner.runChain` needs so confirmation
 * executes without re-deriving anything.
 */
data class ActionPreview(
  val graph: String,
  val slots: Map<String, String>,
  val chain: List<String>,
  val isWrite: Boolean,
)
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningOutcome.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

/** The reasoning layer's decision for one intent — proceed, ask, confirm, or no match. */
sealed interface ReasoningOutcome {
  data class NoMatch(val intent: String) : ReasoningOutcome

  /** The ask-don't-guess result: top-2 within margin, or a required slot missing/invalid. */
  data class Clarify(val question: String, val candidates: List<String>) : ReasoningOutcome

  /** A confident write: preview shown, nothing executed until the human confirms. */
  data class ConfirmRequired(val preview: ActionPreview) : ReasoningOutcome

  /** A confident read: safe to execute. */
  data class Proceed(val graph: String, val slots: Map<String, String>) : ReasoningOutcome
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*ConfidencePolicyTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ConfidencePolicy.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ActionPreview.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningOutcome.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/ConfidencePolicyTest.kt
git commit -m "feat(harness): ConfidencePolicy + ActionPreview + ReasoningOutcome types"
```

---

### Task 4: `DisambiguationGate` — the ask-don't-guess decision

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/DisambiguationGate.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/DisambiguationGateTest.kt`

**Interfaces:**
- Consumes: `RouteDecision` (Task 1), `FillResult` (`Valid`/`Invalid`), `ConfidencePolicy`/`ActionPreview`/`ReasoningOutcome` (Task 3).
- Produces:
  - `class DisambiguationGate(private val policy: ConfidencePolicy = ConfidencePolicy()) { fun decide(decision: RouteDecision, secondBestGraph: String?, fill: FillResult, chain: List<String>): ReasoningOutcome }`.
  - Decision order: (1) `decision.graphName == null` → `NoMatch`; (2) `decision.margin < policy.threshold(graph)` → `Clarify("Did you mean X or Y?", [graph, secondBestGraph].filterNotNull())`; (3) `fill is Invalid` → `Clarify("I need more detail: <errors>", [graph])`; (4) `fill is Valid` and `policy.isWrite(graph)` → `ConfirmRequired(ActionPreview(graph, slots, chain, isWrite = true))`; (5) else → `Proceed(graph, slots)`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/DisambiguationGateTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.orchestrator.FillResult
import org.junit.jupiter.api.Test

class DisambiguationGateTest {
  private val gate = DisambiguationGate()

  @Test
  fun `no route yields NoMatch`() {
    val outcome = gate.decide(RouteDecision(null, "none", 0.0, 0.0), null, FillResult.Valid(emptyMap()), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.NoMatch::class.java)
  }

  @Test
  fun `low margin asks instead of guessing`() {
    // Confident-ish confidence but a tiny margin between the top two graphs.
    val decision = RouteDecision("configure", "close call", confidence = 0.55, margin = 0.10)
    val outcome =
      gate.decide(decision, secondBestGraph = "price", FillResult.Valid(mapOf("productCode" to "SKU-1")), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    val clarify = outcome as ReasoningOutcome.Clarify
    assertThat(clarify.candidates).containsExactly("configure", "price")
  }

  @Test
  fun `a confident read proceeds`() {
    val decision = RouteDecision("configure", "clear", confidence = 0.9, margin = 0.9)
    val outcome =
      gate.decide(decision, "price", FillResult.Valid(mapOf("productCode" to "SKU-1", "quantity" to "2")), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Proceed::class.java)
  }

  @Test
  fun `a confident write requires confirmation with a preview, not execution`() {
    val decision = RouteDecision("quote", "clear", confidence = 0.95, margin = 0.95)
    val outcome = gate.decide(decision, "price", FillResult.Valid(mapOf("priceId" to "prc-2")), listOf("quote"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.ConfirmRequired::class.java)
    val preview = (outcome as ReasoningOutcome.ConfirmRequired).preview
    assertThat(preview.graph).isEqualTo("quote")
    assertThat(preview.isWrite).isTrue()
    assertThat(preview.slots).containsEntry("priceId", "prc-2")
  }

  @Test
  fun `an invalid slot-fill asks for clarification`() {
    val decision = RouteDecision("configure", "clear", confidence = 0.9, margin = 0.9)
    val outcome =
      gate.decide(decision, "price", FillResult.Invalid(listOf("missing required slot 'quantity'")), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    assertThat((outcome as ReasoningOutcome.Clarify).question).contains("quantity")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*DisambiguationGateTest"`
Expected: FAIL — unresolved reference `DisambiguationGate`.

- [ ] **Step 3: Write `DisambiguationGate`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/DisambiguationGate.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.orchestrator.FillResult

/**
 * Scaffold 5, the single most important reliability rule: gate on uncertainty, never guess. When
 * the top-two graphs are within the policy margin, or a required slot is missing/invalid, the layer
 * returns a [ReasoningOutcome.Clarify] (ask the user) rather than a coin-flip. Confident writes
 * route to [ReasoningOutcome.ConfirmRequired] (human validates before execution); confident reads
 * [ReasoningOutcome.Proceed].
 */
class DisambiguationGate(private val policy: ConfidencePolicy = ConfidencePolicy()) {
  fun decide(
    decision: RouteDecision,
    secondBestGraph: String?,
    fill: FillResult,
    chain: List<String>,
  ): ReasoningOutcome {
    val graph = decision.graphName ?: return ReasoningOutcome.NoMatch("(none)")

    if (decision.margin < policy.threshold(graph)) {
      val candidates = listOfNotNull(graph, secondBestGraph)
      return ReasoningOutcome.Clarify(
        "I'm not confident which action you want. Did you mean ${candidates.joinToString(" or ")}?",
        candidates,
      )
    }

    return when (fill) {
      is FillResult.Invalid ->
        ReasoningOutcome.Clarify(
          "I need more detail before I can proceed: ${fill.errors.joinToString("; ")}",
          listOf(graph),
        )
      is FillResult.Valid ->
        if (policy.isWrite(graph))
          ReasoningOutcome.ConfirmRequired(ActionPreview(graph, fill.slots, chain, isWrite = true))
        else ReasoningOutcome.Proceed(graph, fill.slots)
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*DisambiguationGateTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/DisambiguationGate.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/DisambiguationGateTest.kt
git commit -m "feat(harness): DisambiguationGate — ask-don't-guess on low margin / bad slots"
```

---

### Task 5: `ReasoningLayer` end-to-end wiring + `Stage5Demo`

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningLayer.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage5Demo.kt`
- Modify: `agentic-harness/build.gradle.kts` (add `runStage5Demo` JavaExec task)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningLayerTest.kt`

**Interfaces:**
- Consumes: `RetrievalPreFilter` (Task 2), `ScoringLlmClient` (Task 1), `DisambiguationGate`/`ConfidencePolicy`/`ReasoningOutcome`/`ActionPreview` (Tasks 3–4), `SlotFiller`/`FillResult`, `GraphRunner`, `MockCpqServer`, `GenAiTracer`/`NoopTracer`, `Rundown`.
- Produces:
  - `class ReasoningLayer(private val tools: List<ToolDef>, private val llm: ScoringLlmClient = ScoringLlmClient(), private val policy: ConfidencePolicy = ConfidencePolicy(), private val topK: Int = tools.size, private val tracer: Tracer = NoopTracer())`.
  - `fun handle(intent: String): ReasoningOutcome` — traced `invoke_agent` span; `RetrievalPreFilter.topK(intent, tools, topK)` → candidates; `chat` span for routing via `llm.route(intent, candidates)`; determine `secondBestGraph` from `llm.scores(intent, candidates)` (2nd-highest key, or null); `chat` span for slot-fill via `SlotFiller(llm).fill(intent, tool)` (only when a graph matched); then `DisambiguationGate(policy).decide(...)`. Returns the outcome; **executes nothing**.
  - `fun confirm(preview: ActionPreview, baseUrl: String): List<Rundown>` — `execute_tool` span; runs `GraphRunner.runChain(baseUrl, preview.chain, preview.slots)`. The only path that touches ReVoman.
  - `fun main()` in `Stage5Demo.kt` + `runStage5Demo` task (mainClass `com.salesforce.revoman.harness.Stage5DemoKt`) demonstrating four utterances: a read that proceeds, a write that returns `ConfirmRequired` (then `confirm` executes), an ambiguous one that returns `Clarify`, and a no-match — all traced to console.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningLayerTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReasoningLayerTest {
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
  fun `a confident read proceeds without executing`() {
    val outcome = ReasoningLayer(tools).handle("configure 2 units of SKU-1")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Proceed::class.java)
    assertThat(server.db).isEmpty() // handle() never executes
  }

  @Test
  fun `a write intent requires confirmation and executes only on confirm`() {
    val layer = ReasoningLayer(tools)
    val outcome = layer.handle("create a draft quote for prc-2")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.ConfirmRequired::class.java)
    assertThat(server.db).isEmpty() // nothing ran yet

    val preview = (outcome as ReasoningOutcome.ConfirmRequired).preview
    val rundowns = layer.confirm(preview, baseUrl)
    assertThat(rundowns).isNotEmpty()
    assertThat(server.db.values).contains("DRAFT") // now it ran
  }

  @Test
  fun `an ambiguous intent asks instead of guessing and never executes`() {
    // A high write-threshold (0.90) means a low-margin quote intent must ask.
    val policy = ConfidencePolicy(perGraphThreshold = mapOf("configure" to 0.99))
    val outcome = ReasoningLayer(tools, policy = policy).handle("configure or price this")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    assertThat(server.db).isEmpty()
  }

  @Test
  fun `an unrelated intent is NoMatch`() {
    assertThat(ReasoningLayer(tools).handle("what is the weather"))
      .isInstanceOf(ReasoningOutcome.NoMatch::class.java)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ReasoningLayerTest"`
Expected: FAIL — unresolved reference `ReasoningLayer`.

- [ ] **Step 3: Write `ReasoningLayer`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningLayer.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.orchestrator.SlotFiller
import com.salesforce.revoman.harness.retrieval.RetrievalPreFilter
import com.salesforce.revoman.harness.telemetry.NoopTracer
import com.salesforce.revoman.harness.telemetry.Tracer
import com.salesforce.revoman.harness.telemetry.chatAttrs
import com.salesforce.revoman.harness.telemetry.executeToolAttrs
import com.salesforce.revoman.harness.telemetry.invokeAgentAttrs
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.output.Rundown

/**
 * The complete reasoning layer: the one probabilistic surface, scaffolded for reliability.
 * `handle` runs retrieve → route (with confidence) → fill (validated) → gate, and returns a
 * [ReasoningOutcome] WITHOUT executing anything. `confirm` is the only path that hands a graph to
 * ReVoman, and only after the gate said a human must approve (writes) or the caller chose to run a
 * `Proceed`. Every turn is traced with OTel GenAI-convention spans.
 */
class ReasoningLayer(
  private val tools: List<ToolDef>,
  private val llm: ScoringLlmClient = ScoringLlmClient(),
  private val policy: ConfidencePolicy = ConfidencePolicy(),
  private val topK: Int = tools.size,
  private val tracer: Tracer = NoopTracer(),
) {
  private val gate = DisambiguationGate(policy)
  private val slotFiller = SlotFiller(llm)

  fun handle(intent: String): ReasoningOutcome =
    tracer.span("invoke_agent", invokeAgentAttrs("reasoning-layer")) { agent ->
      agent.setAttribute("gen_ai.prompt", intent)
      val candidates = RetrievalPreFilter.topK(intent, tools, topK)

      val decision =
        tracer.span("chat", chatAttrs("router")) { chat ->
          val d = llm.route(intent, candidates)
          chat.setAttribute("gen_ai.response.text", d.graphName ?: "none")
          chat.setAttribute("confidence", d.confidence)
          chat.setAttribute("margin", d.margin)
          d
        }
      val graph = decision.graphName ?: return@span ReasoningOutcome.NoMatch(intent)
      val tool = candidates.first { it.graphName == graph }

      val secondBest =
        llm.scores(intent, candidates)
          .entries
          .sortedByDescending { it.value }
          .getOrNull(1)
          ?.takeIf { it.value > 0 }
          ?.key

      val fill =
        tracer.span("chat", chatAttrs("slot-filler")) { slotFiller.fill(intent, tool) }

      val outcome = gate.decide(decision, secondBest, fill, chain = listOf(graph))
      agent.setAttribute("turn.outcome", outcome::class.simpleName ?: "unknown")
      outcome
    }

  fun confirm(preview: ActionPreview, baseUrl: String): List<Rundown> =
    tracer.span("execute_tool", executeToolAttrs(preview.graph)) {
      GraphRunner.runChain(baseUrl, preview.chain, seedEnv = preview.slots)
    }
}
```

- [ ] **Step 4: Run the test AND the full suite**

Run: `./gradlew :agentic-harness:test --tests "*ReasoningLayerTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

If the ambiguous test does not produce `Clarify`: the stub's scoring may give the top graph a margin at/above the threshold. The test forces this by setting `configure`'s threshold to 0.99 — confirm `ScoringLlmClient.route`'s margin for "configure or price this" is below 0.99 (it will be, since both `configure` and `price` score > 0, so margin = (top−second)/top < 1.0 < 0.99 only if second > 0.01·top; if the utterance yields a single positive graph, rephrase the test intent to one that scores two graphs — e.g. "configure or price this" contains both "configure" and "price" tokens). Do not weaken the assertion; adjust the intent string so two graphs genuinely score.

- [ ] **Step 5: Write `Stage5Demo`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage5Demo.kt`:

```kotlin
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
```

- [ ] **Step 6: Add the run task**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runStage5Demo") {
  group = "harness"
  description = "Run the complete reasoning layer (retrieve -> route -> fill -> gate: ask|confirm|proceed)"
  mainClass.set("com.salesforce.revoman.harness.Stage5DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 7: Run the demo and the full suite**

Run: `./gradlew :agentic-harness:runStage5Demo -q`
Expected: `>>> price configuration cfg-1` → `PROCEED: price ...`; `>>> create a draft quote for prc-2` → `CONFIRM REQUIRED (write)` then `confirmed -> executed 1 graph(s); DB={...DRAFT...}`; `>>> configure or price this` → `ASK: ... candidates=[configure, price]`; `>>> what is the weather` → `NO MATCH`. Each preceded by an `• invoke_agent` span tree.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all prior + new test classes pass.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningLayer.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage5Demo.kt \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/reasoning/ReasoningLayerTest.kt
git commit -m "feat(harness): ReasoningLayer wires retrieve->route->fill->gate; Stage5 demo"
```

---

### Task 6: Real Claude via Bedrock proxy (best-effort, gated) + native tool-use slot-fill

**Files:**
- Modify: `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/llm/claude/ClaudeLlmClient.kt`
- Modify: `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/ClaudeDemo.kt`

**Interfaces:**
- Consumes (claude source set only): koog `simpleBedrockExecutorWithBearerToken`, `BedrockClientSettings`, `BedrockModels`; the existing `ClaudeLlmClient` structure; `ReasoningLayer` for the demo.
- Produces:
  - `ClaudeLlmClient` gains a companion `fromBedrockEnv(): LlmClient?` that returns an instance wired to the Bedrock proxy when `ANTHROPIC_BEDROCK_BASE_URL` + a bearer token (`ANTHROPIC_AUTH_TOKEN`) are set, else `null`. It builds `simpleBedrockExecutorWithBearerToken(token, BedrockClientSettings(endpointUrl = baseUrl))` and uses `BedrockModels.AnthropicClaude45Opus` (or the closest available). Keep the existing direct-Anthropic `fromEnv()` for the public-API case.
  - `fillSlots` additionally builds an Anthropic tool-use `input_schema` from the tool's `SlotSchema` map (types + enum `values`) and forces that tool (constrained decoding), so the model returns strict structured arguments; the returned args still pass through the existing validation before use.
  - `ClaudeDemo.main()` prefers `fromBedrockEnv()` then `fromEnv()`; if both null, prints a skip line and exits 0. On a live client, it drives `ReasoningLayer` over a couple of intents.
- **Scope note:** deliverable is that `compileClaudeKotlin` still SUCCEEDS and the default `test` stays green and never compiles this source set. A live call working against the SFDC proxy is **best-effort** — if the proxy's protocol/auth rejects koog's Bedrock client, capture the exact error and report `DONE_WITH_CONCERNS`; do NOT modify `main`/`test` to compensate, and do NOT block. The deterministic layer (Tasks 1–5) is the real deliverable.

- [ ] **Step 1: Read the current claude source-set files**

Read `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/llm/claude/ClaudeLlmClient.kt` and `.../ClaudeDemo.kt` in full before editing — preserve the existing `fromEnv()` / direct-Anthropic path and the `LlmClient` contract.

- [ ] **Step 2: Add the Bedrock-proxy factory + native tool-use to `ClaudeLlmClient`**

Add to the `ClaudeLlmClient` companion (adjust imports; the executor construction differs from the direct-Anthropic one). The client should accept a pre-built `PromptExecutor` + `LLModel` so both factories share the routing/slot-fill logic:

```kotlin
// imports (claude source set)
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.llms.all.simpleBedrockExecutorWithBearerToken

  companion object {
    /** Direct public Anthropic API (sk-ant-... key). Null when unset. */
    fun fromEnv(): LlmClient? = System.getenv("ANTHROPIC_API_KEY")?.let(::ClaudeLlmClient)

    /**
     * This environment's path: an AWS Bedrock proxy (bearer token + custom gateway URL) rather
     * than the public Anthropic API. Best-effort — koog's Bedrock client may or may not accept a
     * non-AWS gateway; if it doesn't, the caller falls back to skip.
     */
    fun fromBedrockEnv(): LlmClient? {
      val token = System.getenv("ANTHROPIC_AUTH_TOKEN") ?: return null
      val baseUrl = System.getenv("ANTHROPIC_BEDROCK_BASE_URL") ?: return null
      val executor =
        simpleBedrockExecutorWithBearerToken(token, BedrockClientSettings(endpointUrl = baseUrl))
      return ClaudeLlmClient(executor, BedrockModels.AnthropicClaude45Opus)
    }
  }
```

Refactor the primary constructor to `class ClaudeLlmClient(private val executor: PromptExecutor, private val model: LLModel)` and make the existing `ClaudeLlmClient(apiKey: String)` a secondary constructor delegating via `simpleAnthropicExecutor(apiKey)` + `AnthropicModels.Sonnet_4_5`, so both real paths reuse one `route`/`fillSlots` body.

For native tool-use in `fillSlots`, build the `input_schema` from `tool.slots` (map each `SlotSchema` to a JSON-schema property: `type` INT→`integer`/STRING,ENUM→`string`; ENUM adds `enum: values`; required names) and pass it as an Anthropic tool the model is forced to call; parse the tool-call arguments as the slot map. Keep the existing regex-JSON fallback for the non-tool-use path. (Consult the koog source at `~/code-clones/oss/koog` for the exact tool-descriptor/`tool_choice` API on `PromptExecutor.execute`; if the forced-tool API is not readily available in koog 1.1.1, keep the current prompt-based structured reply and note it — the Bedrock wiring is the priority.)

- [ ] **Step 3: Prefer the Bedrock factory in `ClaudeDemo` and drive `ReasoningLayer`**

Update `ClaudeDemo.main()` to `val llm = ClaudeLlmClient.fromBedrockEnv() ?: ClaudeLlmClient.fromEnv()`; if null, print the skip line and return. Otherwise boot `MockCpqServer` and drive a `ReasoningLayer(GraphRegistry.loadToolDefs(), llm = <wrap>, ...)` — note `ReasoningLayer` currently takes a `ScoringLlmClient`; for the demo, call the lower-level `Router`/`SlotFiller` with the real `llm` directly, OR add a demo path that routes+fills with the real client and prints the outcome. Keep it simple: the demo's job is to prove a live Claude call returns a graph + slots against the proxy.

- [ ] **Step 4: Compile the claude source set (the gate)**

Run: `./gradlew :agentic-harness:compileClaudeKotlin`
Expected: BUILD SUCCESSFUL — koog Bedrock symbols resolve and the source set compiles.

If it fails to resolve `simpleBedrockExecutorWithBearerToken` / `BedrockModels` / `BedrockClientSettings`: verify the import paths against the koog source; both `koog-agents` and `koog-agents-additions` are already deps. If it fails for another reason, capture the exact error, report `DONE_WITH_CONCERNS`, and STOP (do not touch main/test).

- [ ] **Step 5: Confirm the default suite is still green and koog-free**

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all deterministic tests pass; the build log shows NO `compileClaudeKotlin` task.

- [ ] **Step 6 (best-effort, gated): try a live call against the Bedrock proxy**

Only if the environment exports the Bedrock vars (they come from `~/.claude/settings.json`; export them into the shell first, e.g. `export ANTHROPIC_AUTH_TOKEN=... ANTHROPIC_BEDROCK_BASE_URL=...`). If Bedrock auth requires TLS to the SFDC gateway, this may need the workspace network:

Run: `./gradlew :agentic-harness:claudeDemo -q`
Expected (best case): live routing/slot-fill decisions from Claude via the proxy, executed against the mock. If the proxy rejects the request (auth/protocol/TLS), capture the exact error and report `DONE_WITH_CONCERNS` — the deterministic layer already proves the design; a live proxy call is a bonus.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/llm/claude/ClaudeLlmClient.kt \
  agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/ClaudeDemo.kt
git commit -m "feat(harness): real Claude via Bedrock-proxy bearer token + native tool-use (gated)"
```

---

## Self-Review

**Spec coverage (the delta rows of the reasoning-layer scaffolds):**
- Scaffold 2 (router returns `{graph_id, confidence}`) → Task 1 (`RouteDecision.confidence/margin` + `scores`). ✓
- Scaffold 4 (retrieval pre-filter) → Task 2 (`RetrievalPreFilter.topK`). ✓
- Scaffold 5 (confidence/disambiguation gate — the core) → Task 3 (`ConfidencePolicy`/`ReasoningOutcome`) + Task 4 (`DisambiguationGate`). ✓
- Scaffold 6 (confirm gate preview object) → Task 3 (`ActionPreview`) + Task 5 (`ReasoningLayer.confirm`). ✓
- End-to-end wiring (retrieve→route→fill→gate→ReVoman) → Task 5 (`ReasoningLayer` + `Stage5Demo`). ✓
- Measurable: low-margin→ask, write→confirm, both empty-DB proofs → Tasks 4–5 tests. ✓
- Native strict tool-use + Bedrock-proxy real client → Task 6 (gated, best-effort). ✓
- Scaffolds 1, 3, 7, 8a (tool-def gen, slot-filler, confusion matrix, BFCL) reused as-is from Stages 1–4, not rebuilt. ✓

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every deterministic step (Tasks 1–5) shows complete code. Task 6 (the isolated, best-effort koog path) intentionally gives the factory + wiring verbatim and directs the implementer to the koog source for the exact forced-tool API, because that one API surface is the sole genuinely-uncertain piece in this environment — flagged explicitly, with a `DONE_WITH_CONCERNS` fallback, not left vague.

**Type consistency:** `RouteDecision(graphName, rationale, confidence=0.0, margin=0.0)` (Task 1) consumed by `DisambiguationGate.decide` (Task 4) and `ReasoningLayer` (Task 5). `ScoringLlmClient.scores` (Task 1) used in `ReasoningLayer` for `secondBestGraph` (Task 5). `RetrievalPreFilter.topK(intent, tools, k)` (Task 2) used in Task 5. `ConfidencePolicy.threshold/isWrite` + `ActionPreview(graph, slots, chain, isWrite)` + `ReasoningOutcome.{NoMatch,Clarify,ConfirmRequired,Proceed}` (Task 3) used in Tasks 4–5. `DisambiguationGate.decide(decision, secondBestGraph, fill, chain)` (Task 4) called by Task 5. Reuses verified APIs: `SlotFiller(llm).fill → FillResult.Valid/Invalid`, `GraphRunner.runChain(baseUrl, chain, seedEnv)`, `MockCpqServer.{start,stop,db}`, `GenAiTracer`/`NoopTracer` + attr helpers, `ToolDef`/`SlotSchema`/`SlotType`, `GraphRegistry.loadToolDefs`. ✓

**Backward-compat:** the only shipped-code edits are `RouteDecision` (two defaulted fields — existing 2-arg constructions unaffected) and `ScoringLlmClient.route` (same return type, now with confidence/margin populated; `scores` is additive). Task 1 Step 5 re-runs the whole suite to confirm Stages 1–4 stay green. ✓

**Known risks flagged in-plan:** Task 5 Step 4 (the ambiguous test needs an intent that scores two graphs — the intent string is chosen so both `configure` and `price` tokens are present); Task 6 (koog Bedrock client against a non-AWS SFDC gateway is best-effort → `DONE_WITH_CONCERNS`, never blocks, never touches main/test).

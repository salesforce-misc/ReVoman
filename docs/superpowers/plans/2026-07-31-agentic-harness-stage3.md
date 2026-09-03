# Agentic Harness — Stage 3 (Evals + Calibration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the evals + calibration layer: a labeled eval set, a confusion matrix over router graph-selection, a live calibration loop (off-diagonal miss → add a `when_not_to_use` clause → re-run → accuracy moves), a BFCL-style slot-fill check, a tau-bench-style final-DB-state check, and a bounded LLM-as-judge alongside a deterministic ground-truth check.

**Architecture:** Everything is deterministic, koog-free, and CI-safe — driven by the Stage 2 `StubLlmClient` plus a new calibration-aware `ScoringLlmClient` whose routing genuinely changes when a `when_not_to_use` clause is added (that is what makes the calibration demo real, not staged). All new code lives in `agentic-harness/src/main` and `src/test`; nothing touches koog or the `claude` source set.

**Tech Stack:** Kotlin (JVM 21), the `agentic-harness` module, ReVoman (`project(":")`), snakeyaml (eval/gold resource parsing; already a Stage 2 dep), JUnit5 + Google Truth. No koog.

## Global Constraints

- **JDK 21**; never modify the ReVoman library; all work inside `agentic-harness/`.
- **No koog / kotlinx.coroutines** in any `src/main` or `src/test` file. The default `test`/`build`/`check` must stay green and must never compile the `claude` source set.
- **Stage 1 and Stage 2 must stay green** after every task: `./gradlew :agentic-harness:test` passes all prior + new test classes.
- **Copyright header** (SFDC Apache-2.0) on every new `.kt`; JUnit5 + Google Truth; ktfmt Google style (`./gradlew spotlessApply` before every commit).
- **Verified facts (do not re-derive):**
  - Stage 2 delivered, in package `com.salesforce.revoman.harness`:
    - `tooldef.ToolDef(graphName: String, whenToUse: String, whenNotToUse: List<String>, exampleQueries: List<String>, inputExamples: List<Map<String,String>>, slots: Map<String, SlotSchema>)` — a `data class` (so `.copy(...)` is available).
    - `tooldef.GraphMetadataParser.parse(graph)`, `tooldef.GraphOasLoader.load(graph)`, `tooldef.ToolDefGenerator.generate(spec, oas)`.
    - `orchestrator.GraphRegistry.GRAPHS: List<String>` = `[configure, price, quote]` and `GraphRegistry.loadToolDefs(): List<ToolDef>`.
    - `orchestrator.Orchestrator(baseUrl, tools, llm)` with `orchestrate(utterance): OrchestrationResult` (`NoGraphMatched` | `SlotsRejected` | `Executed(graph, slots, rundowns, context)`).
    - `orchestrator.Router(llm, tools).route(utterance): RouteDecision`, `orchestrator.SlotFiller(llm).fill(utterance, tool): FillResult` (`Valid(slots)` | `Invalid(errors)`).
    - `llm.LlmClient { route(utterance, tools): RouteDecision; fillSlots(utterance, tool): Map<String,String> }`; `llm.RouteDecision(graphName: String?, rationale)`; `llm.StubLlmClient`.
    - `mock.MockCpqServer` (`start(): Int`, `stop()`, `db: MutableMap<String,Any?>`).
  - The mock DB after a configure with `productCode=SKU-1, quantity=2` contains the value `"SKU-1 x2"` under key `config:cfg-1`; after price, `price:prc-2 = 100.0`; after quote, `quote:qot-3 = "DRAFT"`. (Stage 1/2 verified.)
  - snakeyaml import is `org.yaml.snakeyaml.Yaml`; `Yaml().load<Map<String,Any?>>(text)`.

---

### Task 1: `EvalSet` (labeled resource + loader) + `ConfusionMatrix`

**Files:**
- Create resource: `agentic-harness/src/main/resources/evals/router-eval.yaml`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/EvalCase.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/EvalSet.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/ConfusionMatrix.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/EvalSetTest.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/ConfusionMatrixTest.kt`

**Interfaces:**
- Produces:
  - `data class EvalCase(val utterance: String, val expected: String)`.
  - `object EvalSet { fun load(resource: String = "evals/router-eval.yaml"): List<EvalCase> }` — parses the YAML `cases:` list via snakeyaml.
  - `data class ConfusionMatrix(val rowLabels: List<String>, val colLabels: List<String>, val counts: Map<String, Map<String, Int>>)` with `val total: Int`, `val correct: Int`, `val accuracy: Double`, and `fun render(): String`.
  - `object ConfusionMatrices { fun from(pairs: List<Pair<String, String?>>): ConfusionMatrix }` — `pairs` is `(expected, predicted)`; a `null` predicted becomes the column label `"none"`.

- [ ] **Step 1: Write the eval resource**

Create `agentic-harness/src/main/resources/evals/router-eval.yaml`:

```yaml
# Labeled utterance -> expected graph. The single deliberate near-miss is the last case:
# "quote me a price for this config" is really a PRICE request, but the word "quote" lures a
# naive router to the quote graph. Stage 3's calibration loop fixes exactly this with a
# when_not_to_use clause. Keep this file human-editable — Stage 4's flywheel appends to it.
cases:
  - { utterance: "configure 2 units of SKU-1", expected: configure }
  - { utterance: "set up a new product configuration", expected: configure }
  - { utterance: "what does this configuration cost", expected: price }
  - { utterance: "compute the total for my config", expected: price }
  - { utterance: "create a draft quote for prc-2", expected: quote }
  - { utterance: "draft a quote from prc-2", expected: quote }
  - { utterance: "quote me a price for this config", expected: price }
```

- [ ] **Step 2: Write the failing tests**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/EvalSetTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EvalSetTest {
  @Test
  fun `loads the labeled router eval set`() {
    val cases = EvalSet.load()
    assertThat(cases).hasSize(7)
    assertThat(cases.first()).isEqualTo(EvalCase("configure 2 units of SKU-1", "configure"))
    assertThat(cases.map { it.expected }.toSet()).containsExactly("configure", "price", "quote")
    // The deliberate near-miss: a price intent phrased with the word "quote".
    assertThat(cases.last()).isEqualTo(EvalCase("quote me a price for this config", "price"))
  }
}
```

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/ConfusionMatrixTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConfusionMatrixTest {
  @Test
  fun `counts correct and off-diagonal predictions`() {
    val pairs =
      listOf(
        "configure" to "configure",
        "price" to "price",
        "price" to "quote", // one off-diagonal miss
        "quote" to "quote",
      )
    val m = ConfusionMatrices.from(pairs)
    assertThat(m.total).isEqualTo(4)
    assertThat(m.correct).isEqualTo(3)
    assertThat(m.accuracy).isWithin(1e-9).of(0.75)
    assertThat(m.counts["price"]!!["quote"]).isEqualTo(1)
    assertThat(m.counts["price"]!!["price"]).isEqualTo(1)
  }

  @Test
  fun `renders a labeled grid and treats a null prediction as none`() {
    val m = ConfusionMatrices.from(listOf("configure" to null, "price" to "price"))
    val text = m.render()
    assertThat(text).contains("price")
    assertThat(text).contains("none")
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :agentic-harness:test --tests "*EvalSetTest" --tests "*ConfusionMatrixTest"`
Expected: FAIL — unresolved references `EvalSet`, `EvalCase`, `ConfusionMatrices`, `ConfusionMatrix`.

- [ ] **Step 4: Write `EvalCase` + `EvalSet`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/EvalCase.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

/** One labeled router eval: a user utterance and the graph it should route to. */
data class EvalCase(val utterance: String, val expected: String)
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/EvalSet.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import org.yaml.snakeyaml.Yaml

/** Loads the labeled router eval set from a classpath YAML resource. */
object EvalSet {
  fun load(resource: String = "evals/router-eval.yaml"): List<EvalCase> {
    val text =
      javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.readText()
        ?: error("Eval set not found on classpath: $resource")
    @Suppress("UNCHECKED_CAST") val root = Yaml().load<Map<String, Any?>>(text)
    @Suppress("UNCHECKED_CAST")
    val cases = (root["cases"] as? List<Map<String, Any?>>).orEmpty()
    return cases.map { EvalCase(it["utterance"].toString(), it["expected"].toString()) }
  }
}
```

- [ ] **Step 5: Write `ConfusionMatrix`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/ConfusionMatrix.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

/**
 * A graph-selection confusion matrix: `counts[expected][predicted]` tallies how often the router
 * predicted each graph for each gold label. The diagonal is correct; every off-diagonal cell is a
 * confusion that Stage 3's calibration loop turns into a `when_not_to_use` clause.
 */
data class ConfusionMatrix(
  val rowLabels: List<String>,
  val colLabels: List<String>,
  val counts: Map<String, Map<String, Int>>,
) {
  val total: Int
    get() = counts.values.sumOf { row -> row.values.sum() }

  val correct: Int
    get() = rowLabels.sumOf { label -> counts[label]?.get(label) ?: 0 }

  val accuracy: Double
    get() = if (total == 0) 0.0 else correct.toDouble() / total

  /** Renders an aligned text grid: rows are gold labels, columns are predictions. */
  fun render(): String {
    val width = (colLabels + rowLabels).maxOf { it.length } + 2
    val header = "gold\\pred".padEnd(width) + colLabels.joinToString("") { it.padStart(width) }
    val rows =
      rowLabels.joinToString("\n") { row ->
        row.padEnd(width) +
          colLabels.joinToString("") { col -> (counts[row]?.get(col) ?: 0).toString().padStart(width) }
      }
    return "$header\n$rows\naccuracy: $correct/$total"
  }
}

/** Builds a [ConfusionMatrix] from `(expected, predicted)` pairs; a null prediction becomes "none". */
object ConfusionMatrices {
  private const val NONE = "none"

  fun from(pairs: List<Pair<String, String?>>): ConfusionMatrix {
    val rowLabels = pairs.map { it.first }.distinct().sorted()
    val predicted = pairs.map { it.second ?: NONE }.distinct()
    val colLabels = (rowLabels + predicted).distinct()
    val counts =
      rowLabels.associateWith { row ->
        colLabels.associateWith { col ->
          pairs.count { it.first == row && (it.second ?: NONE) == col }
        }
      }
    return ConfusionMatrix(rowLabels, colLabels, counts)
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :agentic-harness:test --tests "*EvalSetTest" --tests "*ConfusionMatrixTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/resources/evals/router-eval.yaml \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/EvalCase.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/EvalSet.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/ConfusionMatrix.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/EvalSetTest.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/ConfusionMatrixTest.kt
git commit -m "feat(harness): labeled eval set + confusion matrix"
```

---

### Task 2: `ScoringLlmClient` — calibration-aware deterministic router

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClient.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClientTest.kt`

**Interfaces:**
- Consumes: `LlmClient`, `RouteDecision`, `StubLlmClient` (Stage 2/3); `ToolDef` (Stage 2).
- Produces:
  - `class ScoringLlmClient(private val slotDelegate: LlmClient = StubLlmClient()) : LlmClient`.
  - `route`: scores each tool = (# distinct utterance tokens found as substrings in the tool's `graphName + whenToUse + exampleQueries`) minus `5 ×` (# of the tool's `whenNotToUse` clauses whose trigger words appear in the utterance). Highest score wins; `null` if the best score `<= 0`. **This is the key property: routing changes when a `whenNotToUse` clause is added.**
  - `fillSlots`: delegates to `slotDelegate` (calibration is about routing, not extraction).
  - Tokenizer: lowercase, split on `[^a-z0-9]+`, keep tokens of length `>= 3` minus a small stopword set. Trigger words of a clause: its tokens of length `>= 4`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClientTest.kt`:

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
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import org.junit.jupiter.api.Test

class ScoringLlmClientTest {
  // Seed tool defs with when_not_to_use STRIPPED — the un-calibrated starting point.
  private val seedTools: List<ToolDef> =
    listOf("configure", "price", "quote").map {
      ToolDefGenerator.generate(GraphMetadataParser.parse(it), GraphOasLoader.load(it))
        .copy(whenNotToUse = emptyList())
    }
  private val scoring = ScoringLlmClient()

  @Test
  fun `routes an unambiguous configure utterance`() {
    assertThat(scoring.route("configure 2 units of SKU-1", seedTools).graphName).isEqualTo("configure")
  }

  @Test
  fun `the price-phrased-as-quote utterance mis-routes to quote before calibration`() {
    assertThat(scoring.route("quote me a price for this config", seedTools).graphName).isEqualTo("quote")
  }

  @Test
  fun `adding a when_not_to_use clause to quote fixes the mis-route`() {
    val calibrated =
      seedTools.map {
        if (it.graphName == "quote")
          it.copy(
            whenNotToUse =
              listOf("Do not use when the user asks for a price or cost — that is the price graph.")
          )
        else it
      }
    assertThat(scoring.route("quote me a price for this config", calibrated).graphName)
      .isEqualTo("price")
  }

  @Test
  fun `returns null for an utterance that matches nothing`() {
    assertThat(scoring.route("xyzzy plugh", seedTools).graphName).isNull()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ScoringLlmClientTest"`
Expected: FAIL — unresolved reference `ScoringLlmClient`.

- [ ] **Step 3: Write `ScoringLlmClient`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClient.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.salesforce.revoman.harness.tooldef.ToolDef

/**
 * A deterministic, calibration-aware router. Scores each tool by keyword overlap between the
 * utterance and the tool's `when_to_use` + `example_queries`, then subtracts a penalty for every
 * `when_not_to_use` clause whose trigger words appear in the utterance. Unlike [StubLlmClient]'s
 * fixed keyword map, this client's routing genuinely CHANGES when a `when_not_to_use` clause is
 * added — which is what makes Stage 3's confusion-matrix calibration loop a real experiment, not a
 * staged one. Slot-filling is delegated (calibration is about selection, not extraction).
 */
class ScoringLlmClient(private val slotDelegate: LlmClient = StubLlmClient()) : LlmClient {
  private val stopwords =
    setOf(
      "the", "for", "this", "that", "with", "into", "from", "and", "you", "your", "does", "what",
      "are", "our", "its", "new", "saved",
    )
  private val penaltyPerClause = 5

  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val tokens = tokenize(utterance)
    val best = tools.map { it to score(tokens, it) }.maxByOrNull { it.second }
    return if (best == null || best.second <= 0) {
      RouteDecision(null, "no graph scored above zero for: $utterance")
    } else {
      RouteDecision(best.first.graphName, "score=${best.second} for '${best.first.graphName}'")
    }
  }

  override fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> =
    slotDelegate.fillSlots(utterance, tool)

  private fun score(tokens: List<String>, tool: ToolDef): Int {
    val haystack =
      (tool.graphName + " " + tool.whenToUse + " " + tool.exampleQueries.joinToString(" "))
        .lowercase()
    val positive = tokens.count { token -> haystack.contains(token) }
    val penalty =
      tool.whenNotToUse.count { clause ->
        val triggers = tokenize(clause).filter { it.length >= 4 }
        triggers.any { trigger -> tokens.any { it.contains(trigger) } }
      } * penaltyPerClause
    return positive - penalty
  }

  private fun tokenize(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stopwords }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*ScoringLlmClientTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed. (If the calibration test fails, the penalty is not dominating — confirm `penaltyPerClause = 5` and that the clause's trigger `price` matches the utterance token `price`.)

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClient.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/ScoringLlmClientTest.kt
git commit -m "feat(harness): calibration-aware ScoringLlmClient (when_not_to_use changes routing)"
```

---

### Task 3: `RouterEvaluator` + the live calibration loop

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/RouterEvaluator.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/RouterEvaluatorTest.kt`

**Interfaces:**
- Consumes: `EvalCase`, `ConfusionMatrix`, `ConfusionMatrices` (Task 1); `ScoringLlmClient` (Task 2); `LlmClient`, `Router`, `ToolDef`, `GraphRegistry`.
- Produces:
  - `data class Miss(val utterance: String, val expected: String, val predicted: String?)`.
  - `data class EvalReport(val matrix: ConfusionMatrix, val misses: List<Miss>)` with `val accuracy: Double get() = matrix.accuracy`.
  - `class RouterEvaluator(private val llm: LlmClient) { fun evaluate(cases: List<EvalCase>, tools: List<ToolDef>): EvalReport }` — routes each case via a `Router`, tallies `(expected, predicted)` into a `ConfusionMatrix`, and collects the mismatches as `Miss`es.

- [ ] **Step 1: Write the failing test (this is the headline calibration test)**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/RouterEvaluatorTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.Test

class RouterEvaluatorTest {
  private val cases = EvalSet.load()
  private val evaluator = RouterEvaluator(ScoringLlmClient())

  // Seed = the four fields as generated, but with when_not_to_use STRIPPED (un-calibrated start).
  private val seedTools: List<ToolDef> =
    GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }

  // Calibrated = add the disambiguating clause to the quote graph (mined from the off-diagonal).
  private val calibratedTools: List<ToolDef> =
    seedTools.map {
      if (it.graphName == "quote")
        it.copy(
          whenNotToUse =
            listOf("Do not use when the user asks for a price or cost — that is the price graph.")
        )
      else it
    }

  @Test
  fun `seed router confuses the price-phrased-as-quote utterance`() {
    val report = evaluator.evaluate(cases, seedTools)
    assertThat(report.matrix.correct).isEqualTo(6)
    assertThat(report.matrix.total).isEqualTo(7)
    // The single off-diagonal: a price intent predicted as quote.
    assertThat(report.matrix.counts["price"]!!["quote"]).isEqualTo(1)
    assertThat(report.misses)
      .contains(Miss("quote me a price for this config", "price", "quote"))
  }

  @Test
  fun `adding the when_not_to_use clause moves accuracy to 100 percent`() {
    val report = evaluator.evaluate(cases, calibratedTools)
    assertThat(report.matrix.correct).isEqualTo(7)
    assertThat(report.matrix.total).isEqualTo(7)
    assertThat(report.misses).isEmpty()
    assertThat(report.matrix.counts["price"]!!["quote"]).isEqualTo(0)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*RouterEvaluatorTest"`
Expected: FAIL — unresolved references `RouterEvaluator`, `EvalReport`, `Miss`.

- [ ] **Step 3: Write `RouterEvaluator`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/RouterEvaluator.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.orchestrator.Router
import com.salesforce.revoman.harness.tooldef.ToolDef

/** A single graph-selection mismatch from an eval run. */
data class Miss(val utterance: String, val expected: String, val predicted: String?)

/** The outcome of running the router over an eval set: the confusion matrix plus its misses. */
data class EvalReport(val matrix: ConfusionMatrix, val misses: List<Miss>) {
  val accuracy: Double
    get() = matrix.accuracy
}

/**
 * Runs the router over a labeled eval set and produces a confusion matrix + the list of misses.
 * This is the design's Layer-2 graph-selection eval: run against gold, read the matrix, and turn
 * each off-diagonal cell into a `when_not_to_use` clause. Re-running with the calibrated tool defs
 * moves the number — the calibration loop, made measurable.
 */
class RouterEvaluator(private val llm: LlmClient) {
  fun evaluate(cases: List<EvalCase>, tools: List<ToolDef>): EvalReport {
    val router = Router(llm, tools)
    val results = cases.map { it to router.route(it.utterance).graphName }
    val matrix = ConfusionMatrices.from(results.map { (case, predicted) -> case.expected to predicted })
    val misses =
      results
        .filter { (case, predicted) -> case.expected != predicted }
        .map { (case, predicted) -> Miss(case.utterance, case.expected, predicted) }
    return EvalReport(matrix, misses)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*RouterEvaluatorTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/RouterEvaluator.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/RouterEvaluatorTest.kt
git commit -m "feat(harness): RouterEvaluator + confusion-matrix calibration loop (6/7 -> 7/7)"
```

---

### Task 4: `BfclCheck` (slot-fill vs gold) + `TauBenchCheck` (final DB state)

**Files:**
- Create resource: `agentic-harness/src/main/resources/evals/slotfill-gold.yaml`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/BfclCheck.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/TauBenchCheck.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/BfclCheckTest.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/TauBenchCheckTest.kt`

**Interfaces:**
- Consumes: `LlmClient`, `StubLlmClient`, `Router`, `SlotFiller`, `FillResult`, `ToolDef`, `GraphRegistry`, `Orchestrator`, `OrchestrationResult`, `MockCpqServer`.
- Produces:
  - `data class SlotFillGold(val utterance: String, val graph: String, val slots: Map<String, String>)` and `object SlotFillGoldSet { fun load(resource: String = "evals/slotfill-gold.yaml"): List<SlotFillGold> }`.
  - `data class BfclResult(val gold: SlotFillGold, val predictedGraph: String?, val predictedSlots: Map<String, String>, val graphMatch: Boolean, val slotsMatch: Boolean) { val pass: Boolean get() = graphMatch && slotsMatch }`.
  - `class BfclCheck(private val llm: LlmClient, private val tools: List<ToolDef>) { fun check(gold: SlotFillGold): BfclResult }` — routes + slot-fills the utterance and AST-compares (value equality of graph + slot map) against gold. A `SlotFiller.Invalid` yields empty predicted slots (a fail).
  - `data class TaskCase(val utterance: String, val expectedDbValues: List<String>)`.
  - `class TauBenchCheck(private val baseUrl: String, private val tools: List<ToolDef>, private val llm: LlmClient) { fun check(server: MockCpqServer, case: TaskCase): Boolean }` — orchestrates the utterance and asserts the mock DB's values contain all `expectedDbValues` (final-state comparison, not transcript).

- [ ] **Step 1: Write the gold resource**

Create `agentic-harness/src/main/resources/evals/slotfill-gold.yaml`:

```yaml
# BFCL-style gold: the exact graph + filled slots each utterance should produce.
cases:
  - utterance: "configure 2 units of SKU-1"
    graph: configure
    slots: { productCode: "SKU-1", quantity: "2" }
  - utterance: "price configuration cfg-1"
    graph: price
    slots: { configId: "cfg-1" }
  - utterance: "create a draft quote for prc-2"
    graph: quote
    slots: { priceId: "prc-2" }
```

- [ ] **Step 2: Write the failing tests**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/BfclCheckTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class BfclCheckTest {
  private val tools = GraphRegistry.loadToolDefs()
  private val bfcl = BfclCheck(StubLlmClient(), tools)

  @Test
  fun `loads the slot-fill gold set`() {
    assertThat(SlotFillGoldSet.load()).hasSize(3)
  }

  @Test
  fun `predicted call matches gold for a configure utterance`() {
    val gold = SlotFillGold("configure 2 units of SKU-1", "configure", mapOf("productCode" to "SKU-1", "quantity" to "2"))
    val result = bfcl.check(gold)
    assertThat(result.graphMatch).isTrue()
    assertThat(result.slotsMatch).isTrue()
    assertThat(result.pass).isTrue()
  }

  @Test
  fun `every gold case passes with the stub client`() {
    val results = SlotFillGoldSet.load().map { bfcl.check(it) }
    assertThat(results.all { it.pass }).isTrue()
  }
}
```

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/TauBenchCheckTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TauBenchCheckTest {
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
  fun `configure task reaches the expected final DB state`() {
    val check = TauBenchCheck(baseUrl, tools, StubLlmClient())
    val passed = check.check(server, TaskCase("configure 2 units of SKU-1", listOf("SKU-1 x2")))
    assertThat(passed).isTrue()
  }

  @Test
  fun `an unmet final state fails the task`() {
    val check = TauBenchCheck(baseUrl, tools, StubLlmClient())
    val passed = check.check(server, TaskCase("configure 2 units of SKU-1", listOf("SKU-9 x99")))
    assertThat(passed).isFalse()
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :agentic-harness:test --tests "*BfclCheckTest" --tests "*TauBenchCheckTest"`
Expected: FAIL — unresolved references `BfclCheck`, `SlotFillGold`, `SlotFillGoldSet`, `BfclResult`, `TauBenchCheck`, `TaskCase`.

- [ ] **Step 4: Write `BfclCheck`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/BfclCheck.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.orchestrator.FillResult
import com.salesforce.revoman.harness.orchestrator.Router
import com.salesforce.revoman.harness.orchestrator.SlotFiller
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.yaml.snakeyaml.Yaml

/** BFCL-style gold: the exact graph + slot values an utterance should produce. */
data class SlotFillGold(val utterance: String, val graph: String, val slots: Map<String, String>)

/** Loads the BFCL gold set from a classpath YAML resource. */
object SlotFillGoldSet {
  fun load(resource: String = "evals/slotfill-gold.yaml"): List<SlotFillGold> {
    val text =
      javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.readText()
        ?: error("Slot-fill gold not found on classpath: $resource")
    @Suppress("UNCHECKED_CAST") val root = Yaml().load<Map<String, Any?>>(text)
    @Suppress("UNCHECKED_CAST") val cases = (root["cases"] as? List<Map<String, Any?>>).orEmpty()
    return cases.map { c ->
      @Suppress("UNCHECKED_CAST") val slots = (c["slots"] as? Map<String, Any?>).orEmpty()
      SlotFillGold(c["utterance"].toString(), c["graph"].toString(), slots.mapValues { it.value.toString() })
    }
  }
}

/** The outcome of one BFCL comparison: predicted call vs gold, by value equality. */
data class BfclResult(
  val gold: SlotFillGold,
  val predictedGraph: String?,
  val predictedSlots: Map<String, String>,
  val graphMatch: Boolean,
  val slotsMatch: Boolean,
) {
  val pass: Boolean
    get() = graphMatch && slotsMatch
}

/**
 * BFCL-style check: run the router + slot-filler over an utterance and AST-compare the predicted
 * call (graph name + filled slots) against gold by value equality. Rejected (invalid) slot-fills
 * predict no slots — a clean fail, never a silent partial pass.
 */
class BfclCheck(private val llm: LlmClient, private val tools: List<ToolDef>) {
  private val router = Router(llm, tools)
  private val slotFiller = SlotFiller(llm)

  fun check(gold: SlotFillGold): BfclResult {
    val predictedGraph = router.route(gold.utterance).graphName
    val tool = tools.firstOrNull { it.graphName == predictedGraph }
    val predictedSlots =
      if (tool == null) emptyMap()
      else
        when (val fill = slotFiller.fill(gold.utterance, tool)) {
          is FillResult.Valid -> fill.slots
          is FillResult.Invalid -> emptyMap()
        }
    val graphMatch = predictedGraph == gold.graph
    val slotsMatch = predictedSlots == gold.slots
    return BfclResult(gold, predictedGraph, predictedSlots, graphMatch, slotsMatch)
  }
}
```

- [ ] **Step 5: Write `TauBenchCheck`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/TauBenchCheck.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.tooldef.ToolDef

/** A task-success case: an utterance and the final DB values it should produce. */
data class TaskCase(val utterance: String, val expectedDbValues: List<String>)

/**
 * tau-bench-style check: grade the final database STATE, not the chat transcript. Orchestrates the
 * utterance end to end and asserts the mock CPQ DB's values contain every expected value.
 */
class TauBenchCheck(
  private val baseUrl: String,
  private val tools: List<ToolDef>,
  private val llm: LlmClient,
) {
  fun check(server: MockCpqServer, case: TaskCase): Boolean {
    Orchestrator(baseUrl, tools, llm).orchestrate(case.utterance)
    val actual = server.db.values.map { it.toString() }
    return case.expectedDbValues.all { expected -> actual.contains(expected) }
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :agentic-harness:test --tests "*BfclCheckTest" --tests "*TauBenchCheckTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/resources/evals/slotfill-gold.yaml \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/BfclCheck.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/TauBenchCheck.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/BfclCheckTest.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/TauBenchCheckTest.kt
git commit -m "feat(harness): BFCL slot-fill check + tau-bench final-DB-state check"
```

---

### Task 5: `LlmJudge` (bounded) + `Stage3Demo`

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/LlmJudge.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage3Demo.kt`
- Modify: `agentic-harness/build.gradle.kts` (add a `runStage3Demo` JavaExec task)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/LlmJudgeTest.kt`

**Interfaces:**
- Consumes: everything above; `Orchestrator`, `OrchestrationResult`, `MockCpqServer`, `GraphRegistry`, `StubLlmClient`, `ScoringLlmClient`, `Rundown`.
- Produces:
  - `data class Judgment(val pass: Boolean, val reason: String)`.
  - `interface LlmJudge { fun judge(utterance: String, responseContext: String): Judgment }`.
  - `class StubJudge : LlmJudge` — deterministic: passes when the response context indicates success (`"unsuccessfulStepCount": 0` present and `"areAllStepsSuccessful": true` present). This is the *screening* judge.
  - `object GroundTruth { fun allStepsSucceeded(rundowns: List<Rundown>): Boolean }` — the deterministic *gate* (never drifts): `rundowns.isNotEmpty() && rundowns.all { it.areAllStepsSuccessful }`.
  - `fun main()` in `Stage3Demo.kt` + a `runStage3Demo` JavaExec task (mainClass `com.salesforce.revoman.harness.Stage3DemoKt`) that runs: (1) the confusion matrix before/after calibration, printing both matrices and the accuracy move; (2) the BFCL check over the gold set; (3) the tau-bench check; (4) the judge alongside the deterministic ground-truth, noting the judge only screens.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/LlmJudgeTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmJudgeTest {
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
  fun `judge screens a successful turn and ground truth confirms it`() {
    val result =
      Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")
    val executed = result as OrchestrationResult.Executed

    val judgment = StubJudge().judge("configure 2 units of SKU-1", executed.context)
    assertThat(judgment.pass).isTrue()

    // The deterministic gate agrees — the judge is only a screen, not the source of truth.
    assertThat(GroundTruth.allStepsSucceeded(executed.rundowns)).isTrue()
  }

  @Test
  fun `judge fails an empty or unsuccessful context`() {
    assertThat(StubJudge().judge("x", "no useful context here").pass).isFalse()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*LlmJudgeTest"`
Expected: FAIL — unresolved references `StubJudge`, `Judgment`, `GroundTruth`.

- [ ] **Step 3: Write `LlmJudge`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/LlmJudge.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.output.Rundown

/** A judge's verdict on a fuzzy metric — advisory, used only to screen. */
data class Judgment(val pass: Boolean, val reason: String)

/**
 * LLM-as-judge for fuzzy metrics (response helpfulness / trajectory quality). Bounded on purpose:
 * it only SCREENS. The gate is always the deterministic [GroundTruth]. A real implementation would
 * call Claude here; the [StubJudge] is the deterministic stand-in for CI.
 */
interface LlmJudge {
  fun judge(utterance: String, responseContext: String): Judgment
}

/** Deterministic screening judge: "helpful" iff the response context reports a clean run. */
class StubJudge : LlmJudge {
  override fun judge(utterance: String, responseContext: String): Judgment {
    val clean =
      responseContext.contains("\"unsuccessfulStepCount\": 0") &&
        responseContext.contains("\"areAllStepsSuccessful\": true")
    return if (clean) Judgment(true, "context reports all steps successful")
    else Judgment(false, "context does not report a clean, successful run")
  }
}

/**
 * The deterministic ground-truth gate that never drifts. Prefer this over the judge wherever a
 * deterministic check exists; the judge only covers what this cannot.
 */
object GroundTruth {
  fun allStepsSucceeded(rundowns: List<Rundown>): Boolean =
    rundowns.isNotEmpty() && rundowns.all { it.areAllStepsSuccessful }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*LlmJudgeTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Write `Stage3Demo`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage3Demo.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.eval.BfclCheck
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.eval.GroundTruth
import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.eval.SlotFillGoldSet
import com.salesforce.revoman.harness.eval.StubJudge
import com.salesforce.revoman.harness.eval.TaskCase
import com.salesforce.revoman.harness.eval.TauBenchCheck
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult

/**
 * Stage 3 runnable demo: evals + calibration in action, entirely deterministic (no API key). Shows
 * (1) the confusion matrix before/after adding a `when_not_to_use` clause and the accuracy move,
 * (2) BFCL slot-fill checks vs gold, (3) a tau-bench final-DB-state check, (4) an LLM-as-judge
 * screening a fuzzy metric alongside the deterministic ground-truth gate.
 */
fun main() {
  val cases = EvalSet.load()
  val evaluator = RouterEvaluator(ScoringLlmClient())
  val seedTools = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }
  val clause = "Do not use when the user asks for a price or cost — that is the price graph."
  val calibratedTools =
    seedTools.map { if (it.graphName == "quote") it.copy(whenNotToUse = listOf(clause)) else it }

  println("=== 1. Graph-selection confusion matrix (SEED, un-calibrated) ===")
  val seed = evaluator.evaluate(cases, seedTools)
  println(seed.matrix.render())
  println("misses: ${seed.misses}")

  println("\n=== 2. Calibration: add when_not_to_use to 'quote' ===")
  println("  + $clause")
  val calibrated = evaluator.evaluate(cases, calibratedTools)
  println(calibrated.matrix.render())
  println(
    "accuracy moved: ${seed.matrix.correct}/${seed.matrix.total} -> " +
      "${calibrated.matrix.correct}/${calibrated.matrix.total}"
  )

  println("\n=== 3. BFCL-style slot-fill check (predicted call vs gold) ===")
  val tools = GraphRegistry.loadToolDefs()
  val bfcl = BfclCheck(StubLlmClient(), tools)
  SlotFillGoldSet.load().forEach { gold ->
    val r = bfcl.check(gold)
    println("  ${if (r.pass) "PASS" else "FAIL"}  '${gold.utterance}' -> ${r.predictedGraph} ${r.predictedSlots}")
  }

  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    println("\n=== 4. tau-bench-style task success (final DB state) ===")
    val tau = TauBenchCheck(baseUrl, tools, StubLlmClient())
    val taskCase = TaskCase("configure 2 units of SKU-1", listOf("SKU-1 x2"))
    println("  ${if (tau.check(server, taskCase)) "PASS" else "FAIL"}  final DB = ${server.db}")

    println("\n=== 5. LLM-as-judge (screen) alongside deterministic ground-truth (gate) ===")
    when (val result = Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")) {
      is OrchestrationResult.Executed -> {
        val judgment = StubJudge().judge("configure 2 units of SKU-1", result.context)
        println("  judge (advisory screen): pass=${judgment.pass} — ${judgment.reason}")
        println("  ground-truth (gate):     pass=${GroundTruth.allStepsSucceeded(result.rundowns)}")
      }
      else -> println("  (unexpected: $result)")
    }
  } finally {
    server.stop()
  }
}
```

- [ ] **Step 6: Add the run task to the module build file**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runStage3Demo") {
  group = "harness"
  description = "Run the evals + calibration demo (confusion matrix, BFCL, tau-bench, LLM-judge)"
  mainClass.set("com.salesforce.revoman.harness.Stage3DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 7: Run the demo and the full module suite**

Run: `./gradlew :agentic-harness:runStage3Demo -q`
Expected: section 1 prints a 3×3 matrix with `accuracy: 6/7` and a miss `Miss(utterance=quote me a price for this config, expected=price, predicted=quote)`; section 2 prints a matrix with `accuracy: 7/7` and `accuracy moved: 6/7 -> 7/7`; section 3 prints three `PASS` BFCL lines; section 4 prints `PASS  final DB = {config:cfg-1=SKU-1 x2}`; section 5 prints `judge ... pass=true` and `ground-truth ... pass=true`.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all Stage 1, 2, and 3 test classes pass.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/eval/LlmJudge.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage3Demo.kt \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/eval/LlmJudgeTest.kt
git commit -m "feat(harness): LLM-as-judge (bounded) + Stage 3 evals/calibration demo"
```

---

## Self-Review

**Spec coverage (Stage 3 rows of the design's concept-to-component map):**
- Labeled eval set (utterance → expected graph) → Task 1 (`EvalSet` + resource). ✓
- Confusion matrix, graph-selection accuracy → Task 1 (`ConfusionMatrix`) + Task 3 (`RouterEvaluator`). ✓
- Live calibration: off-diagonal miss → add `when_not_to_use` → re-run → number moves → Task 2 (`ScoringLlmClient` makes routing sensitive to `when_not_to_use`) + Task 3 (6/7 → 7/7 test + demo). ✓
- BFCL-style predicted-call-vs-gold for slot-fill → Task 4 (`BfclCheck`). ✓
- tau-bench-style final-DB-state for task success → Task 4 (`TauBenchCheck`). ✓
- LLM-as-judge on a fuzzy metric, bounded, with a deterministic ground-truth alongside → Task 5 (`StubJudge` + `GroundTruth`). ✓
- Runnable proof: `runStage3Demo` (no key) → Task 5. ✓
- Stage 4 (OTel + flywheel) is out of scope for this plan.

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every code step shows complete code. ✓

**Type consistency:** `EvalCase(utterance, expected)` (Task 1) used in Tasks 3, 5. `ConfusionMatrix`/`ConfusionMatrices.from(pairs)` (Task 1) used in Task 3. `ScoringLlmClient(slotDelegate = StubLlmClient())` (Task 2) implements the Stage 2 `LlmClient` and is used in Tasks 3, 5. `EvalReport(matrix, misses)` + `Miss(utterance, expected, predicted)` (Task 3) used in Task 5. `SlotFillGold`/`SlotFillGoldSet`/`BfclResult`/`BfclCheck` and `TaskCase`/`TauBenchCheck` (Task 4) used in Task 5. `Judgment`/`LlmJudge`/`StubJudge`/`GroundTruth` (Task 5). All reuse verified Stage 1/2 APIs (`GraphRegistry.loadToolDefs`, `Router`, `SlotFiller`/`FillResult`, `Orchestrator`/`OrchestrationResult`, `MockCpqServer`, `ToolDef.copy`, `Rundown.areAllStepsSuccessful`). ✓

**Calibration correctness (hand-verified):** with `when_not_to_use` stripped, the `ScoringLlmClient` scores `quote`=3 vs `price`=2 on "quote me a price for this config" (gold=price) → mis-routes to quote → seed accuracy 6/7 with the single off-diagonal `[price→quote]`. Adding the clause (trigger word `price` matches the utterance token `price`) applies a `-5` penalty to quote → quote 3−5=−2 < price 2 → routes to price → 7/7. The other six cases are unaffected (their utterances contain none of the clause's trigger words in a way that changes the winner). ✓

**Known risks flagged in-plan:** Task 2 Step 4 notes the penalty must dominate; Task 4/5 reuse the mock DB value formats verified in Stages 1–2 (`"SKU-1 x2"`, `config:cfg-1`).

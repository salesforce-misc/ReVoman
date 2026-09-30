# Agentic Harness — Stage 2 (Tool-Def Gen + Probabilistic Layer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the probabilistic layer on top of Stage 1's deterministic spine: auto-generate the 4-field tool definition from graph metadata + a small OAS, put the LLM behind a pluggable `LlmClient` interface (deterministic STUB in `main`/tests; real Claude via koog in an isolated source set), and wire router → slot-filler (schema-validated) → `GraphRunner.revUp` → Rundown-as-context.

**Architecture:** Everything testable and CI-safe lives in `agentic-harness/src/main` with **zero koog and zero coroutines** — the `StubLlmClient` drives all of it deterministically, so `./gradlew :agentic-harness:test` never needs an API key or a network. The real Claude implementation (`ClaudeLlmClient`) is confined to a **separate `claude` source set** (`src/claude/kotlin`) that carries the koog dependency and is compiled only by an opt-in `compileClaudeKotlin` / `claudeDemo` task — the default `test`/`build`/`check` tasks never compile it, so a koog resolution or version problem cannot break Stage 1 or the Stage 2 core.

**Tech Stack:** Kotlin (JVM 21), Gradle multi-module (existing `agentic-harness` module), ReVoman library (`project(":")`), snakeyaml (OAS parsing; already in the repo version catalog), JUnit5 + Google Truth. koog (`ai.koog:koog-agents:1.1.1`) ONLY in the isolated `claude` source set.

## Global Constraints

- **JDK 21** runtime and build.
- **Never modify the ReVoman library** (`build.gradle.kts`, `src/main`, `src/test`, `src/integrationTest`). All work is inside `agentic-harness/`.
- **koog is quarantined to the `claude` source set.** No file under `agentic-harness/src/main` or `agentic-harness/src/test` may import anything under `ai.koog.*` or `kotlinx.coroutines.*`. The default `test`/`build`/`check` tasks must never compile the `claude` source set.
- **Stage 1 must stay green.** After every task, `./gradlew :agentic-harness:test` passes all Stage-1 and Stage-2 test classes.
- **Dependencies added in Stage 2:** `implementation(libs.snakeyaml)` on the module `main` (already in the repo's version catalog — used for OAS parsing), and `ai.koog:koog-agents:1.1.1` on the `claude` source set only.
- **Copyright header:** every new `.kt` starts with the standard SFDC Apache-2.0 header block (copy from any existing `agentic-harness/**/*.kt`).
- **Test convention:** JUnit5 (`org.junit.jupiter.api`) + Google Truth (`com.google.common.truth.Truth.assertThat`). ktfmt Google style — run `./gradlew spotlessApply` before every commit.
- **Verified facts (do not re-derive):**
  - Stage 1 delivered: `com.salesforce.revoman.harness.GraphRunner` (`object`) with `runChain(baseUrl: String, graphs: List<String> = DEFAULT_CHAIN, seedEnv: Map<String, Any?> = emptyMap()): List<Rundown>`; `com.salesforce.revoman.harness.mock.MockCpqServer` with `start(): Int`, `stop()`, `db: MutableMap<String,Any?>`; three V3 graphs under `src/main/resources/graphs/{configure,price,quote}`.
  - `GraphRunner.runChain`'s `seedEnv` is passed to ReVoman as `dynamicEnvironment`, which **overrides** the env-file values (Stage 1 Task 4 verified). So filled slots seeded here replace the graph's env-file defaults.
  - ReVoman `{{var}}` substitution is string substitution: a slot value of the string `"2"` rendered into `"quantity":{{quantity}}` yields valid JSON `"quantity":2`.
  - Graph edge facts: `configure` reads `{{productCode}}`,`{{quantity}}` and sets `configId`; `price` reads `{{configId}}` and sets `priceId`,`total`; `quote` reads `{{priceId}}` and sets `quoteId`,`quoteStatus`. Infra placeholders are `{{baseUrl}}`,`{{accessToken}}`.
  - koog (verified from `~/code-clones/oss/koog`): `ai.koog:koog-agents:1.1.1` umbrella re-exports `prompt-executor-llms-all`, which provides `ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor(apiKey: String): PromptExecutor` (JVM no-factory overload) and `ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Sonnet_4_5: LLModel`. `PromptExecutor.execute(prompt: Prompt, model: LLModel): Message.Assistant` is a `suspend` fun; `Message.Assistant.content: String` holds the text. Build a prompt with `ai.koog.prompt.dsl.prompt("<id>") { system("..."); user("...") }`. koog is compiled against Kotlin 2.3.10.

---

### Task 1: `GraphSpec` + `GraphMetadataParser` — extract graph metadata

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphSpec.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphMetadataParser.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/tooldef/GraphMetadataParserTest.kt`

**Interfaces:**
- Consumes: the three V3 graph resource dirs under `graphs/` (Stage 1).
- Produces:
  - `data class GraphSpec(val name: String, val description: String, val slots: List<String>, val outputKeys: List<String>)` — `slots` and `outputKeys` are sorted, distinct.
  - `object GraphMetadataParser { fun parse(graph: String): GraphSpec }` — reads `graphs/<graph>/` from the classpath: `description` from `.resources/definition.yaml`, `slots` = `{{placeholders}}` referenced across the `*.request.yaml` files minus infra (`baseUrl`,`accessToken`) minus `outputKeys`, `outputKeys` = keys passed to `pm.environment.set(...)`.
  - Note (documented limitation): resolves the graph resource directory as a filesystem `Path` (dev/test exploded resources). This harness runs via Gradle, never as a packaged jar, so this is sufficient — do not add jar-walking.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/tooldef/GraphMetadataParserTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphMetadataParserTest {
  @Test
  fun `configure graph exposes productCode and quantity as slots and configId as output`() {
    val spec = GraphMetadataParser.parse("configure")
    assertThat(spec.name).isEqualTo("configure")
    assertThat(spec.description).isNotEmpty()
    assertThat(spec.slots).containsExactly("productCode", "quantity")
    assertThat(spec.outputKeys).contains("configId")
    // Infra placeholders are never slots.
    assertThat(spec.slots).containsNoneOf("baseUrl", "accessToken")
  }

  @Test
  fun `price graph consumes configId as its only slot and emits priceId`() {
    val spec = GraphMetadataParser.parse("price")
    assertThat(spec.slots).containsExactly("configId")
    assertThat(spec.outputKeys).containsAtLeast("priceId", "total")
  }

  @Test
  fun `quote graph consumes priceId as its only slot`() {
    val spec = GraphMetadataParser.parse("quote")
    assertThat(spec.slots).containsExactly("priceId")
    assertThat(spec.outputKeys).contains("quoteId")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*GraphMetadataParserTest"`
Expected: FAIL — "unresolved reference: GraphMetadataParser".

- [ ] **Step 3: Write `GraphSpec`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphSpec.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/**
 * Structural metadata distilled from a ReVoman V3 graph collection — the raw material the
 * [ToolDefGenerator] turns into an LLM-facing tool definition.
 *
 * @property slots the input `{{placeholder}}` names the LLM must fill (infra + intra-graph outputs
 *   excluded — those are threaded deterministically by ReVoman, never by the LLM).
 * @property outputKeys the `pm.environment.set(...)` keys the graph produces (its internal edges).
 */
data class GraphSpec(
  val name: String,
  val description: String,
  val slots: List<String>,
  val outputKeys: List<String>,
)
```

- [ ] **Step 4: Write `GraphMetadataParser`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphMetadataParser.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Parses a V3 graph collection directory (on the classpath) into a [GraphSpec]. Placeholder and
 * output extraction is done by regex over the raw request-yaml text — more robust for finding
 * `{{var}}` tokens and `pm.environment.set` keys than parsing the YAML structure and re-scanning
 * string values.
 */
object GraphMetadataParser {
  private val INFRA_PLACEHOLDERS = setOf("baseUrl", "accessToken")
  private val PLACEHOLDER = Regex("""\{\{(\w+)}}""")
  private val ENV_SET = Regex("""pm\.environment\.set\(\s*["'](\w+)["']""")
  private val DESCRIPTION = Regex("""^description:\s*["']?(.*?)["']?\s*$""", RegexOption.MULTILINE)

  fun parse(graph: String): GraphSpec {
    val dir = resourceDir("graphs/$graph")
    val defText = dir.resolve(".resources/definition.yaml").readText()
    val description = DESCRIPTION.find(defText)?.groupValues?.get(1)?.trim().orEmpty()

    val requestText =
      Files.list(dir).use { stream ->
        stream
          .filter { it.name.endsWith(".request.yaml") }
          .map { it.readText() }
          .toList()
          .joinToString("\n")
      }
    val referenced = PLACEHOLDER.findAll(requestText).map { it.groupValues[1] }.toSet()
    val outputKeys = ENV_SET.findAll(requestText).map { it.groupValues[1] }.toSet()
    val slots = (referenced - INFRA_PLACEHOLDERS - outputKeys).sorted()
    return GraphSpec(graph, description, slots, outputKeys.sorted())
  }

  private fun resourceDir(path: String): Path {
    val url =
      javaClass.classLoader.getResource(path)
        ?: error("Graph resource directory not found on classpath: $path")
    return Path.of(url.toURI())
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*GraphMetadataParserTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphSpec.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphMetadataParser.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/tooldef/GraphMetadataParserTest.kt
git commit -m "feat(harness): GraphMetadataParser extracts slots/outputs from V3 graphs"
```

---

### Task 2: OAS model + loader + `ToolDefGenerator` (the pure 4-field generator)

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphOas.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphOasLoader.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/ToolDef.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/ToolDefGenerator.kt`
- Create OAS resources: `agentic-harness/src/main/resources/oas/configure.yaml`, `oas/price.yaml`, `oas/quote.yaml`
- Modify: `agentic-harness/build.gradle.kts` (add `implementation(libs.snakeyaml)`)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/tooldef/ToolDefGeneratorTest.kt`

**Interfaces:**
- Consumes: `GraphSpec` (Task 1).
- Produces:
  - `enum class SlotType { STRING, INT, ENUM }`
  - `data class SlotSchema(val type: SlotType, val values: List<String> = emptyList(), val required: Boolean = true)`
  - `data class GraphOas(val graph: String, val slots: Map<String, SlotSchema>, val exampleQueries: List<String>, val inputExamples: List<Map<String, String>>, val whenNotToUse: List<String> = emptyList())`
  - `object GraphOasLoader { fun load(graph: String): GraphOas }` — parses `oas/<graph>.yaml` from the classpath via snakeyaml.
  - `data class ToolDef(val graphName: String, val whenToUse: String, val whenNotToUse: List<String>, val exampleQueries: List<String>, val inputExamples: List<Map<String, String>>, val slots: Map<String, SlotSchema>)`
  - `object ToolDefGenerator { fun generate(spec: GraphSpec, oas: GraphOas): ToolDef }` — reconciles: **requires** `oas.slots.keys == spec.slots.toSet()` (every metadata slot has a schema and no extra), else throws `IllegalArgumentException` naming the diff. `whenToUse` = `spec.description`; the other three fields come from `oas`.

- [ ] **Step 1: Add snakeyaml to the module build**

In `agentic-harness/build.gradle.kts`, inside the existing `dependencies { ... }` block, add:

```kotlin
implementation(libs.snakeyaml)
```

- [ ] **Step 2: Write the OAS resource files**

`agentic-harness/src/main/resources/oas/configure.yaml`:

```yaml
graph: configure
slots:
  productCode: { type: enum, values: [SKU-1, SKU-2, SKU-3] }
  quantity: { type: int }
exampleQueries:
  - "configure 2 units of SKU-1"
  - "set up a new product configuration"
  - "add SKU-2 to a fresh config"
inputExamples:
  - { productCode: "SKU-1", quantity: "2" }
  - { productCode: "SKU-2", quantity: "10" }
whenNotToUse:
  - "Do not use to compute a price or total — that is the price graph."
```

`agentic-harness/src/main/resources/oas/price.yaml`:

```yaml
graph: price
slots:
  configId: { type: string }
exampleQueries:
  - "price configuration cfg-1"
  - "what does this configuration cost"
  - "compute the total for my config"
inputExamples:
  - { configId: "cfg-1" }
whenNotToUse:
  - "Do not use to build a configuration from a product — that is the configure graph."
  - "Do not use to create a draft quote — that is the quote graph."
```

`agentic-harness/src/main/resources/oas/quote.yaml`:

```yaml
graph: quote
slots:
  priceId: { type: string }
exampleQueries:
  - "create a draft quote for prc-2"
  - "turn this price into a quote"
  - "draft a quote"
inputExamples:
  - { priceId: "prc-2" }
whenNotToUse:
  - "Do not use to price a configuration — that is the price graph."
```

- [ ] **Step 3: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/tooldef/ToolDefGeneratorTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolDefGeneratorTest {
  @Test
  fun `generates the four fields from configure metadata and OAS`() {
    val spec = GraphMetadataParser.parse("configure")
    val oas = GraphOasLoader.load("configure")
    val toolDef = ToolDefGenerator.generate(spec, oas)

    assertThat(toolDef.graphName).isEqualTo("configure")
    assertThat(toolDef.whenToUse).isEqualTo(spec.description)
    assertThat(toolDef.whenNotToUse).isNotEmpty()
    assertThat(toolDef.exampleQueries).hasSize(3)
    assertThat(toolDef.inputExamples).isNotEmpty()
    // The slot schema is carried through for the slot-filler/validator.
    assertThat(toolDef.slots.keys).containsExactly("productCode", "quantity")
    assertThat(toolDef.slots["productCode"]!!.type).isEqualTo(SlotType.ENUM)
    assertThat(toolDef.slots["productCode"]!!.values).containsExactly("SKU-1", "SKU-2", "SKU-3")
    assertThat(toolDef.slots["quantity"]!!.type).isEqualTo(SlotType.INT)
  }

  @Test
  fun `rejects an OAS whose declared slots do not match the graph metadata`() {
    val spec = GraphSpec("configure", "desc", slots = listOf("productCode", "quantity"), outputKeys = listOf("configId"))
    val badOas =
      GraphOas(
        graph = "configure",
        slots = mapOf("productCode" to SlotSchema(SlotType.ENUM, listOf("SKU-1"))), // missing 'quantity'
        exampleQueries = listOf("x"),
        inputExamples = listOf(mapOf("productCode" to "SKU-1")),
      )
    val ex = assertThrows<IllegalArgumentException> { ToolDefGenerator.generate(spec, badOas) }
    assertThat(ex.message).contains("quantity")
  }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*ToolDefGeneratorTest"`
Expected: FAIL — unresolved references (`GraphOas`, `GraphOasLoader`, `ToolDef`, `ToolDefGenerator`, `SlotType`, `SlotSchema`).

- [ ] **Step 5: Write the OAS model, loader, ToolDef, and generator**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphOas.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/** The type of a graph input slot, used to validate LLM-filled arguments before execution. */
enum class SlotType {
  STRING,
  INT,
  ENUM,
}

/** Schema for one input slot: its type, allowed values (for [SlotType.ENUM]), and whether required. */
data class SlotSchema(
  val type: SlotType,
  val values: List<String> = emptyList(),
  val required: Boolean = true,
)

/**
 * A small, hand-authored OpenAPI-style spec for one graph. The [ToolDefGenerator] distils it — plus
 * the graph metadata — into the four LLM-facing tool-def fields. Never dumped raw into a prompt.
 */
data class GraphOas(
  val graph: String,
  val slots: Map<String, SlotSchema>,
  val exampleQueries: List<String>,
  val inputExamples: List<Map<String, String>>,
  val whenNotToUse: List<String> = emptyList(),
)
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphOasLoader.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import org.yaml.snakeyaml.Yaml

/** Loads a graph's OAS from `oas/<graph>.yaml` on the classpath. */
object GraphOasLoader {
  fun load(graph: String): GraphOas {
    val text =
      javaClass.classLoader.getResourceAsStream("oas/$graph.yaml")?.bufferedReader()?.readText()
        ?: error("OAS not found on classpath: oas/$graph.yaml")
    @Suppress("UNCHECKED_CAST") val root = Yaml().load<Map<String, Any?>>(text)

    @Suppress("UNCHECKED_CAST") val slotsRaw = (root["slots"] as? Map<String, Any?>).orEmpty()
    val slots =
      slotsRaw.mapValues { (_, v) ->
        @Suppress("UNCHECKED_CAST") val s = v as Map<String, Any?>
        val type = SlotType.valueOf((s["type"] as String).uppercase())
        @Suppress("UNCHECKED_CAST") val values = (s["values"] as? List<Any?>).orEmpty().map { it.toString() }
        val required = (s["required"] as? Boolean) ?: true
        SlotSchema(type, values, required)
      }

    @Suppress("UNCHECKED_CAST")
    val exampleQueries = (root["exampleQueries"] as? List<Any?>).orEmpty().map { it.toString() }
    @Suppress("UNCHECKED_CAST")
    val inputExamples =
      (root["inputExamples"] as? List<Map<String, Any?>>).orEmpty().map { ex ->
        ex.mapValues { it.value.toString() }
      }
    @Suppress("UNCHECKED_CAST")
    val whenNotToUse = (root["whenNotToUse"] as? List<Any?>).orEmpty().map { it.toString() }

    return GraphOas(root["graph"] as String, slots, exampleQueries, inputExamples, whenNotToUse)
  }
}
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/ToolDef.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/**
 * The LLM-facing definition of one graph tool: the four calibration fields from the design
 * (`when_to_use`, `when_not_to_use`, `example_queries`, `input_examples`) plus the slot schema the
 * slot-filler validates against before execution.
 */
data class ToolDef(
  val graphName: String,
  val whenToUse: String,
  val whenNotToUse: List<String>,
  val exampleQueries: List<String>,
  val inputExamples: List<Map<String, String>>,
  val slots: Map<String, SlotSchema>,
)
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/ToolDefGenerator.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/**
 * Pure function: distils graph metadata + a small OAS into the four-field [ToolDef]. Reconciles the
 * two sources — the OAS must declare a schema for exactly the graph's input slots (no more, no
 * less) — so a graph and its spec cannot silently drift apart.
 */
object ToolDefGenerator {
  fun generate(spec: GraphSpec, oas: GraphOas): ToolDef {
    val metadataSlots = spec.slots.toSet()
    val oasSlots = oas.slots.keys
    require(metadataSlots == oasSlots) {
      val missing = metadataSlots - oasSlots
      val extra = oasSlots - metadataSlots
      "OAS for '${spec.name}' does not match graph slots. missing schema for=$missing, unknown slots=$extra"
    }
    return ToolDef(
      graphName = spec.name,
      whenToUse = spec.description,
      whenNotToUse = oas.whenNotToUse,
      exampleQueries = oas.exampleQueries,
      inputExamples = oas.inputExamples,
      slots = oas.slots,
    )
  }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*ToolDefGeneratorTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphOas.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/GraphOasLoader.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/ToolDef.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/tooldef/ToolDefGenerator.kt \
  agentic-harness/src/main/resources/oas \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/tooldef/ToolDefGeneratorTest.kt
git commit -m "feat(harness): auto-generate 4-field tool defs from graph metadata + OAS"
```

---

### Task 3: `LlmClient` interface + `StubLlmClient`

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/LlmClient.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/StubLlmClient.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/StubLlmClientTest.kt`

**Interfaces:**
- Consumes: `ToolDef`, `SlotSchema` (Task 2).
- Produces:
  - `data class RouteDecision(val graphName: String?, val rationale: String)` — `graphName == null` means no confident match.
  - `interface LlmClient { fun route(utterance: String, tools: List<ToolDef>): RouteDecision; fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> }` — synchronous (no coroutines in `main`).
  - `class StubLlmClient : LlmClient` — deterministic. `route`: keyword match against each tool's `graphName` + `exampleQueries` keywords (configure ← "configure","product","set up","add"; price ← "price","cost","total","how much"; quote ← "quote","draft"); returns the first match, else `null`. `fillSlots`: extracts `SKU-\d+` for a `productCode` slot, the first integer for a `quantity`/`int` slot, and `cfg-\w+`/`prc-\w+` for `configId`/`priceId`; only returns keys the tool actually declares.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/StubLlmClientTest.kt`:

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

class StubLlmClientTest {
  private val tools: List<ToolDef> =
    listOf("configure", "price", "quote").map {
      ToolDefGenerator.generate(GraphMetadataParser.parse(it), GraphOasLoader.load(it))
    }
  private val stub = StubLlmClient()

  @Test
  fun `routes configure intent`() {
    assertThat(stub.route("configure 2 units of SKU-1", tools).graphName).isEqualTo("configure")
  }

  @Test
  fun `routes price intent`() {
    assertThat(stub.route("how much does this configuration cost", tools).graphName).isEqualTo("price")
  }

  @Test
  fun `routes quote intent`() {
    assertThat(stub.route("create a draft quote", tools).graphName).isEqualTo("quote")
  }

  @Test
  fun `returns null graph for an unrelated utterance`() {
    assertThat(stub.route("what is the weather today", tools).graphName).isNull()
  }

  @Test
  fun `fills configure slots from the utterance`() {
    val configure = tools.first { it.graphName == "configure" }
    val slots = stub.fillSlots("configure 2 units of SKU-1", configure)
    assertThat(slots).containsEntry("productCode", "SKU-1")
    assertThat(slots).containsEntry("quantity", "2")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*StubLlmClientTest"`
Expected: FAIL — unresolved references `StubLlmClient`, `RouteDecision`.

- [ ] **Step 3: Write `LlmClient`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/LlmClient.kt`:

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

/** The router's output: the chosen graph (or null when nothing matched) and a short rationale. */
data class RouteDecision(val graphName: String?, val rationale: String)

/**
 * The only probabilistic surface in the harness. Two jobs, exactly as the design specifies: pick a
 * graph, and fill that graph's input slots. Synchronous by contract so the deterministic stub and
 * the whole orchestrator core stay coroutine-free; the real Claude implementation lives in a
 * separate source set and bridges its suspend calls internally.
 */
interface LlmClient {
  fun route(utterance: String, tools: List<ToolDef>): RouteDecision

  fun fillSlots(utterance: String, tool: ToolDef): Map<String, String>
}
```

- [ ] **Step 4: Write `StubLlmClient`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/StubLlmClient.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef

/**
 * A deterministic, network-free [LlmClient] for tests, CI, and the no-key demo. Routing is keyword
 * matching; slot-filling is regex extraction. It stands in for a real model everywhere the key is
 * absent, so the entire orchestrator is exercisable without an LLM.
 */
class StubLlmClient : LlmClient {
  private val routingKeywords: Map<String, List<String>> =
    mapOf(
      "configure" to listOf("configure", "product", "set up", "add "),
      "price" to listOf("price", "cost", "total", "how much"),
      "quote" to listOf("quote", "draft"),
    )
  private val skuRegex = Regex("""SKU-\d+""")
  private val configIdRegex = Regex("""cfg-\w+""")
  private val priceIdRegex = Regex("""prc-\w+""")
  private val intRegex = Regex("""\b(\d+)\b""")

  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val lower = utterance.lowercase()
    val match =
      tools.firstOrNull { tool ->
        routingKeywords[tool.graphName].orEmpty().any { lower.contains(it) }
      }
    return match?.let { RouteDecision(it.graphName, "keyword match on '${it.graphName}'") }
      ?: RouteDecision(null, "no keyword matched any graph")
  }

  override fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> =
    tool.slots.keys
      .mapNotNull { slot -> extract(slot, tool.slots[slot]!!.type, utterance)?.let { slot to it } }
      .toMap()

  private fun extract(slot: String, type: SlotType, utterance: String): String? =
    when {
      slot == "productCode" -> skuRegex.find(utterance)?.value
      slot == "configId" -> configIdRegex.find(utterance)?.value
      slot == "priceId" -> priceIdRegex.find(utterance)?.value
      type == SlotType.INT -> intRegex.find(utterance)?.groupValues?.get(1)
      else -> null
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*StubLlmClientTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/LlmClient.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/llm/StubLlmClient.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/llm/StubLlmClientTest.kt
git commit -m "feat(harness): LlmClient interface + deterministic StubLlmClient"
```

---

### Task 4: `Router` + `SlotFiller` (schema validation / hallucination rejection)

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Router.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/SlotFiller.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/SlotFillerTest.kt`

**Interfaces:**
- Consumes: `LlmClient`, `RouteDecision` (Task 3); `ToolDef`, `SlotType`, `SlotSchema` (Task 2).
- Produces:
  - `class Router(private val llm: LlmClient, private val tools: List<ToolDef>) { fun route(utterance: String): RouteDecision }`.
  - `sealed interface FillResult { data class Valid(val slots: Map<String, String>) : FillResult; data class Invalid(val errors: List<String>) : FillResult }`.
  - `class SlotFiller(private val llm: LlmClient) { fun fill(utterance: String, tool: ToolDef): FillResult }` — calls `llm.fillSlots`, then validates each filled value against the tool's `SlotSchema` **before** returning: missing required slot → error; unknown slot key (not in schema) → error (rejects hallucinated param names); `INT` value that is not an integer → error; `ENUM` value not in `values` → error (rejects hallucinated arguments). Empty error list → `Valid`, else `Invalid`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/SlotFillerTest.kt`:

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
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.tooldef.SlotSchema
import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.Test

class SlotFillerTest {
  private val configureTool =
    ToolDef(
      graphName = "configure",
      whenToUse = "configure a product",
      whenNotToUse = emptyList(),
      exampleQueries = emptyList(),
      inputExamples = emptyList(),
      slots =
        mapOf(
          "productCode" to SlotSchema(SlotType.ENUM, listOf("SKU-1", "SKU-2", "SKU-3")),
          "quantity" to SlotSchema(SlotType.INT),
        ),
    )

  /** A fake LlmClient that returns pre-canned slot values, so validation can be tested in isolation. */
  private fun fakeLlm(filled: Map<String, String>): LlmClient =
    object : LlmClient {
      override fun route(utterance: String, tools: List<ToolDef>) = RouteDecision(null, "")

      override fun fillSlots(utterance: String, tool: ToolDef) = filled
    }

  @Test
  fun `accepts valid enum and int slots`() {
    val result =
      SlotFiller(fakeLlm(mapOf("productCode" to "SKU-1", "quantity" to "2")))
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Valid::class.java)
    assertThat((result as FillResult.Valid).slots).containsEntry("quantity", "2")
  }

  @Test
  fun `rejects a hallucinated enum value`() {
    val result =
      SlotFiller(fakeLlm(mapOf("productCode" to "SKU-99", "quantity" to "2")))
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("productCode")
  }

  @Test
  fun `rejects a non-integer quantity`() {
    val result =
      SlotFiller(fakeLlm(mapOf("productCode" to "SKU-1", "quantity" to "lots")))
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("quantity")
  }

  @Test
  fun `rejects a missing required slot`() {
    val result = SlotFiller(fakeLlm(mapOf("productCode" to "SKU-1"))).fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("quantity")
  }

  @Test
  fun `rejects a hallucinated slot name`() {
    val result =
      SlotFiller(
          fakeLlm(mapOf("productCode" to "SKU-1", "quantity" to "2", "discountPct" to "50"))
        )
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("discountPct")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*SlotFillerTest"`
Expected: FAIL — unresolved references `SlotFiller`, `FillResult`.

- [ ] **Step 3: Write `Router`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Router.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.tooldef.ToolDef

/** Prompt A: turns an intent into a graph choice by delegating to the pluggable [LlmClient]. */
class Router(private val llm: LlmClient, private val tools: List<ToolDef>) {
  fun route(utterance: String): RouteDecision = llm.route(utterance, tools)
}
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/SlotFiller.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef

/** The result of slot-filling: validated arguments, or the list of validation failures. */
sealed interface FillResult {
  data class Valid(val slots: Map<String, String>) : FillResult

  data class Invalid(val errors: List<String>) : FillResult
}

/**
 * Prompt B: fills a graph's input placeholders via the [LlmClient], then validates every argument
 * against the graph's typed schema BEFORE it can reach ReVoman. Hallucinated argument names,
 * out-of-enum values, non-integer numbers, and missing required slots are all rejected at the door.
 */
class SlotFiller(private val llm: LlmClient) {
  fun fill(utterance: String, tool: ToolDef): FillResult {
    val filled = llm.fillSlots(utterance, tool)
    val errors = buildList {
      // Reject hallucinated slot names not declared by the graph.
      (filled.keys - tool.slots.keys).forEach { add("unknown slot '$it' (not declared by graph '${tool.graphName}')") }
      // Validate each declared slot.
      tool.slots.forEach { (name, schema) ->
        val value = filled[name]
        when {
          value == null -> if (schema.required) add("missing required slot '$name'")
          schema.type == SlotType.INT && value.toIntOrNull() == null ->
            add("slot '$name' expects an integer but got '$value'")
          schema.type == SlotType.ENUM && value !in schema.values ->
            add("slot '$name' value '$value' is not one of ${schema.values}")
        }
      }
    }
    return if (errors.isEmpty()) FillResult.Valid(filled) else FillResult.Invalid(errors)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*SlotFillerTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Router.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/SlotFiller.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/SlotFillerTest.kt
git commit -m "feat(harness): Router + SlotFiller with pre-execution schema validation"
```

---

### Task 5: `GraphRegistry` + `Orchestrator` + runnable Stage 2 demo

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/GraphRegistry.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage2Demo.kt`
- Modify: `agentic-harness/build.gradle.kts` (add a `runStage2Demo` JavaExec task)
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorTest.kt`

**Interfaces:**
- Consumes: `GraphMetadataParser`, `GraphOasLoader`, `ToolDefGenerator`, `ToolDef` (Tasks 1–2); `LlmClient`, `StubLlmClient` (Task 3); `Router`, `SlotFiller`, `FillResult` (Task 4); `GraphRunner` (Stage 1); ReVoman `Rundown`, `Verbosity`, `toJson`.
- Produces:
  - `object GraphRegistry { val GRAPHS: List<String>; fun loadToolDefs(): List<ToolDef> }` — assembles the three tool defs (parser + OAS loader + generator).
  - `sealed interface OrchestrationResult { data class NoGraphMatched(val utterance: String); data class SlotsRejected(val graph: String, val errors: List<String>); data class Executed(val graph: String, val slots: Map<String, String>, val rundowns: List<Rundown>, val context: String) }`.
  - `class Orchestrator(private val baseUrl: String, private val tools: List<ToolDef>, private val llm: LlmClient) { fun orchestrate(utterance: String): OrchestrationResult }` — route → (null ⇒ `NoGraphMatched`); slot-fill+validate → (`Invalid` ⇒ `SlotsRejected`); else `GraphRunner.runChain(baseUrl, listOf(graph), seedEnv = slots)` and return `Executed` with `context` = each Rundown's `toJson(Verbosity.SUMMARY)` joined by newlines.
  - `fun main()` in `Stage2Demo.kt` and a `runStage2Demo` JavaExec task (mainClass `com.salesforce.revoman.harness.Stage2DemoKt`).

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorTest.kt`:

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
    // Rundown context flows back for the LLM.
    assertThat(executed.context).contains("areAllStepsSuccessful")
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*OrchestratorTest"`
Expected: FAIL — unresolved references `GraphRegistry`, `Orchestrator`, `OrchestrationResult`.

- [ ] **Step 3: Write `GraphRegistry`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/GraphRegistry.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator

/**
 * The static graph registry (design decision: static tool list at v1). Assembles the LLM-facing
 * tool definitions for every graph from its metadata + OAS. Retrieval-based selection drops in here
 * later without changing consumers.
 */
object GraphRegistry {
  val GRAPHS: List<String> = listOf("configure", "price", "quote")

  fun loadToolDefs(): List<ToolDef> =
    GRAPHS.map { ToolDefGenerator.generate(GraphMetadataParser.parse(it), GraphOasLoader.load(it)) }
}
```

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt`:

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
 */
class Orchestrator(
  private val baseUrl: String,
  private val tools: List<ToolDef>,
  private val llm: LlmClient,
) {
  private val router = Router(llm, tools)
  private val slotFiller = SlotFiller(llm)

  fun orchestrate(utterance: String): OrchestrationResult {
    val graphName =
      router.route(utterance).graphName ?: return OrchestrationResult.NoGraphMatched(utterance)
    val tool = tools.first { it.graphName == graphName }
    val slots =
      when (val fill = slotFiller.fill(utterance, tool)) {
        is FillResult.Valid -> fill.slots
        is FillResult.Invalid -> return OrchestrationResult.SlotsRejected(graphName, fill.errors)
      }
    val rundowns = GraphRunner.runChain(baseUrl, listOf(graphName), seedEnv = slots)
    val context = rundowns.joinToString("\n") { it.toJson(Verbosity.SUMMARY) }
    return OrchestrationResult.Executed(graphName, slots, rundowns, context)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*OrchestratorTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Write the demo `main()`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage2Demo.kt`:

```kotlin
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
```

- [ ] **Step 6: Add the run task to the module build file**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runStage2Demo") {
  group = "harness"
  description = "Run the orchestrator-workers loop (route -> slot-fill -> revUp) with the stub LLM"
  mainClass.set("com.salesforce.revoman.harness.Stage2DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 7: Run the demo and the full module suite**

Run: `./gradlew :agentic-harness:runStage2Demo -q`
Expected: `Mock CPQ server up at ...`, a `>>> configure 2 units of SKU-1` block routed to `configure` with `slots={productCode=SKU-1, quantity=2}` and a JSON summary showing success, a `>>> what is the weather today` block printing `No graph matched.`, and `Final mock DB state: {config:cfg-1=SKU-1 x2}`.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all Stage-1 and Stage-2 test classes pass.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/GraphRegistry.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/orchestrator/Orchestrator.kt \
  agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage2Demo.kt \
  agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/orchestrator/OrchestratorTest.kt
git commit -m "feat(harness): Orchestrator wires route -> slot-fill -> revUp -> Rundown context"
```

---

### Task 6: `ClaudeLlmClient` in an isolated `claude` source set (koog, key-gated)

**Files:**
- Modify: `agentic-harness/build.gradle.kts` (create the `claude` source set + its koog dependency + a `claudeDemo` JavaExec task)
- Create: `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/llm/claude/ClaudeLlmClient.kt`
- Create: `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/ClaudeDemo.kt`

**Interfaces:**
- Consumes: `LlmClient`, `RouteDecision`, `ToolDef` (from `main`, on the source set's compile classpath); `GraphRegistry`, `Orchestrator`, `OrchestrationResult`, `MockCpqServer` for the demo. koog (`ai.koog:koog-agents:1.1.1`).
- Produces:
  - A `claude` source set whose `compileClasspath`/`runtimeClasspath` include `main`'s output; it is NOT wired into `test`/`build`/`check`.
  - `class ClaudeLlmClient(private val apiKey: String) : LlmClient` in package `...harness.llm.claude` — builds a prompt embedding the tool defs' four fields, calls koog's `simpleAnthropicExecutor(apiKey).execute(prompt, AnthropicModels.Sonnet_4_5)`, and parses the assistant text into a `RouteDecision` (route) or a `Map<String,String>` (slot-fill). Bridges koog's `suspend` API with `kotlinx.coroutines.runBlocking` internally, so it still satisfies the synchronous `LlmClient`. A companion `fromEnv(): LlmClient?` returns an instance when `ANTHROPIC_API_KEY` is set, else `null`.
  - `fun main()` in `ClaudeDemo.kt`: if `ClaudeLlmClient.fromEnv()` is null, print a skip message and return; else run the `Orchestrator` (against a booted `MockCpqServer`) with the real client on a couple of utterances.
- **Scope note:** this task's deliverable is that the `claude` source set **compiles** against koog (`./gradlew :agentic-harness:compileClaudeKotlin`) and that `./gradlew :agentic-harness:test` remains green and never compiles this source set. A live Claude call is a manual, key-gated demo — NOT part of any automated test. If koog fails to resolve or compile against Kotlin 2.4.20-Beta1, report the exact error as `DONE_WITH_CONCERNS` (core Stage 2 is already complete and green) — do NOT change the `main`/`test` code to accommodate koog.

- [ ] **Step 1: Add the isolated `claude` source set + koog dependency + demo task**

In `agentic-harness/build.gradle.kts`, after the existing `dependencies { ... }` block, add:

```kotlin
// --- Isolated `claude` source set: the ONLY place koog lives -------------------------------------
// Quarantines koog (a large Kotlin-multiplatform dependency built against a different Kotlin
// version) so a resolution/compat problem can never break the default `test`/`build`/`check`
// tasks, which never compile this source set. `compileClaudeKotlin` / `claudeDemo` are opt-in.
val claude: SourceSet by sourceSets.creating {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += sourceSets["main"].output
}

dependencies { "claudeImplementation"("ai.koog:koog-agents:1.1.1") }

tasks.register<JavaExec>("claudeDemo") {
  group = "harness"
  description = "Run the orchestrator with the REAL Claude LLM (requires ANTHROPIC_API_KEY)"
  mainClass.set("com.salesforce.revoman.harness.ClaudeDemoKt")
  classpath = claude.runtimeClasspath
}
```

- [ ] **Step 2: Verify the source set exists and the default suite ignores it**

Run: `./gradlew :agentic-harness:tasks --all | grep -i claude`
Expected: `compileClaudeKotlin`, `claudeDemo` listed.

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL, still green — and the build log shows NO `compileClaudeKotlin` task executed.

- [ ] **Step 3: Write `ClaudeLlmClient`**

Create `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/llm/claude/ClaudeLlmClient.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm.claude

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.tooldef.ToolDef
import kotlinx.coroutines.runBlocking

/**
 * The REAL [LlmClient] backed by Claude via koog. Confined to the isolated `claude` source set so
 * koog never touches the CI-tested core. Bridges koog's suspend API with [runBlocking] to satisfy
 * the synchronous [LlmClient] contract. Never required to run tests — gated on [fromEnv].
 */
class ClaudeLlmClient(apiKey: String) : LlmClient {
  private val executor = simpleAnthropicExecutor(apiKey)
  private val model = AnthropicModels.Sonnet_4_5

  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val toolBlock =
      tools.joinToString("\n\n") { t ->
        buildString {
          appendLine("graph: ${t.graphName}")
          appendLine("when_to_use: ${t.whenToUse}")
          appendLine("when_not_to_use: ${t.whenNotToUse.joinToString("; ")}")
          appendLine("example_queries: ${t.exampleQueries.joinToString("; ")}")
        }
      }
    val text =
      runBlocking {
        executor
          .execute(
            prompt("route") {
              system(
                "You are a router. Choose exactly ONE graph name for the user's request, or the " +
                  "literal word NONE if no graph fits. Reply with ONLY the graph name or NONE.\n\n" +
                  toolBlock
              )
              user(utterance)
            },
            model,
          )
          .content
          .trim()
      }
    val chosen = tools.firstOrNull { it.graphName.equals(text, ignoreCase = true) }?.graphName
    return RouteDecision(chosen, "claude replied: $text")
  }

  override fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> {
    val schema =
      tool.slots.entries.joinToString("\n") { (name, s) ->
        "- $name: type=${s.type}${if (s.values.isNotEmpty()) ", allowed=${s.values}" else ""}"
      }
    val examples = tool.inputExamples.joinToString("\n") { it.toString() }
    val text =
      runBlocking {
        executor
          .execute(
            prompt("slot-fill") {
              system(
                "Extract the input slots for the '${tool.graphName}' graph from the user request. " +
                  "Reply with ONLY compact JSON of slot->string-value. Use only these slots:\n" +
                  "$schema\n\nExamples:\n$examples"
              )
              user(utterance)
            },
            model,
          )
          .content
          .trim()
      }
    return parseFlatJson(text)
  }

  /** Minimal flat-JSON parser for `{"k":"v",...}` — the slot-fill reply shape. */
  private fun parseFlatJson(json: String): Map<String, String> =
    Regex(""""(\w+)"\s*:\s*"?([^",}]+)"?""")
      .findAll(json)
      .associate { it.groupValues[1] to it.groupValues[2].trim() }

  companion object {
    fun fromEnv(): LlmClient? = System.getenv("ANTHROPIC_API_KEY")?.let(::ClaudeLlmClient)
  }
}
```

- [ ] **Step 4: Write the Claude demo `main()`**

Create `agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/ClaudeDemo.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.llm.claude.ClaudeLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult

/** Live demo of the orchestrator with the REAL Claude LLM. No-ops (skips) when the key is absent. */
fun main() {
  val llm = ClaudeLlmClient.fromEnv()
  if (llm == null) {
    println("ANTHROPIC_API_KEY not set — skipping the live Claude demo.")
    return
  }
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  val orchestrator = Orchestrator(baseUrl, GraphRegistry.loadToolDefs(), llm)
  try {
    println("Live Claude demo, mock CPQ at $baseUrl")
    listOf("I want to configure ten of SKU-2", "how much will this cost").forEach { utterance ->
      println("\n>>> $utterance")
      when (val r = orchestrator.orchestrate(utterance)) {
        is OrchestrationResult.NoGraphMatched -> println("No graph matched.")
        is OrchestrationResult.SlotsRejected -> println("Slots rejected for '${r.graph}': ${r.errors}")
        is OrchestrationResult.Executed -> println("Routed to '${r.graph}', slots=${r.slots}\n${r.context}")
      }
    }
    println("\nFinal mock DB state: ${server.db}")
  } finally {
    server.stop()
  }
}
```

- [ ] **Step 5: Compile the isolated source set (koog resolution gate)**

Run: `./gradlew :agentic-harness:compileClaudeKotlin`
Expected: BUILD SUCCESSFUL — koog resolves from Maven Central and the source set compiles.

If it FAILS to resolve or compile (e.g. Kotlin metadata version mismatch): capture the exact error, report `DONE_WITH_CONCERNS`, and STOP. Do not modify any `main`/`test` file. The core Stage 2 is already delivered and green; the real-Claude path is an isolated, opt-in extra.

- [ ] **Step 6: Confirm the default suite is still green and koog-free**

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — all Stage-1 + Stage-2 tests pass; `compileClaudeKotlin` does NOT run as part of this.

- [ ] **Step 7 (optional, key-gated): run the live demo**

Only if `ANTHROPIC_API_KEY` is exported: `./gradlew :agentic-harness:claudeDemo -q`
Expected: real routing/slot-fill decisions from Claude, executed against the mock, ending with a `Final mock DB state:` line. Without the key it prints the skip message and exits 0.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/build.gradle.kts \
  agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/llm/claude/ClaudeLlmClient.kt \
  agentic-harness/src/claude/kotlin/com/salesforce/revoman/harness/ClaudeDemo.kt
git commit -m "feat(harness): real ClaudeLlmClient in isolated koog source set (key-gated)"
```

---

## Self-Review

**Spec coverage (Stage 2 rows of the design's concept-to-component map):**
- Tool-def auto-generation (4 fields) from graph metadata + OAS, pure + unit-tested → Tasks 1–2. ✓
- Router (intent → graph) → Tasks 3 (stub), 4 (Router), 6 (Claude). ✓
- Slot-filler, schema-validated before execution, rejects hallucinated args → Task 4. ✓
- `LlmClient` interface with deterministic STUB (CI, no key) + REAL Claude (koog, key-gated) → Tasks 3, 6. ✓
- Orchestrator-workers loop: route → slot-fill → revUp → Rundown-as-context → Task 5. ✓
- koog isolation so tests never need a key / never break on koog → Task 6 source-set design + Global Constraints. ✓
- Runnable proof: `runStage2Demo` (stub, no key) + `claudeDemo` (real, gated) → Tasks 5, 6. ✓
- Stages 3–4 are out of scope for this plan (own plans).

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every code step shows complete code. ✓

**Type consistency:** `GraphSpec(name, description, slots, outputKeys)` (Task 1) consumed unchanged in Tasks 2, 5. `ToolDef(graphName, whenToUse, whenNotToUse, exampleQueries, inputExamples, slots)` (Task 2) used unchanged in Tasks 3, 4, 5, 6. `SlotSchema(type, values, required)` + `SlotType` (Task 2) used in Tasks 3, 4. `LlmClient.route(utterance, tools): RouteDecision` / `fillSlots(utterance, tool): Map<String,String>` (Task 3) implemented by `StubLlmClient` (3), `ClaudeLlmClient` (6), the inline fakes (4, 5). `FillResult.Valid/Invalid` (Task 4) matched in Task 5's `when`. `OrchestrationResult.{NoGraphMatched,SlotsRejected,Executed}` (Task 5) matched in the demos (5, 6). `GraphRunner.runChain(baseUrl, graphs, seedEnv)` and `MockCpqServer.{start,stop,db}` reused from Stage 1 with their verified signatures. koog symbols (`simpleAnthropicExecutor`, `AnthropicModels.Sonnet_4_5`, `prompt{}`, `execute(...).content`) confined to Task 6, verified against the koog source. ✓

**Known risks flagged in-plan:**
- Task 1: classpath-dir-as-Path resolution is dev/test-only (documented; harness runs via Gradle, never as a jar).
- Task 6: koog may not resolve/compile against Kotlin 2.4.20-Beta1 → isolated source set means the core stays green; the task reports `DONE_WITH_CONCERNS` rather than touching `main`/`test`.

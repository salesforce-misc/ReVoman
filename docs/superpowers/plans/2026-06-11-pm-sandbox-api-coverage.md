# PM Sandbox — Full Wiring of Script-Only `pm` APIs + Coverage Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `pm.collectionVariables`, `pm.environment.name`, `pm.test`/`pm.expect` results, and `pm.execution.setNextRequest` observable end-to-end through a real `ReVoman.revUp(...)` run (they currently run in the sandbox but are dropped before reaching `Rundown`), and lock every listed script-only `pm` API with unit + a live integration test.

**Architecture:** Thread `collectionVariables` + environment `name` INTO the sandbox via `PmExecutionContext`; read `collectionVariables`, `assertions`, and `nextRequest` back OUT of `PmExecutionResult`; diff collection-vars back into a new `PostmanSDK.collectionVariables` store (mirroring the env path); stash per-step assertions + nextRequest on `PostmanSDK` and surface them as two new read-only `StepReport` fields. `setNextRequest` is captured + warned-on, NOT executed (the jump-capable sequencer is a separate deferred cycle).

**Tech Stack:** Kotlin, GraalJS (real postman-sandbox bootcode), Moshi, Kotest + JUnit5 (unit), JUnit5 + Truth/AssertJ (integration, live HTTP to pokeapi.co + restful-api.dev).

**Spec:** `docs/superpowers/specs/2026-06-11-pm-sandbox-api-coverage-design.md`

---

## Current State (already done this session — do NOT redo)

These are committed/uncommitted WIP and are GREEN; tasks below build on them:

- `PmScope` has an optional `name: String? = null` field (`PmExecutionContext.kt`).
- `SandboxBridge.scopeToProxy` forwards `name`; `decodeResult` captures `execution.return.nextRequest` into `PmExecutionResult.nextRequest`.
- `PmSandboxApiCoverageTest` (11 tests) + 2 added tests in `PmSandboxScriptApiTest` — all GREEN. These cover every listed API **at the sandbox layer**.

The work below wires those sandbox-proven APIs through **ReVoman's exe layer** so they reach `Rundown`.

---

## File Structure

**Created (production):**
- `src/main/kotlin/com/salesforce/revoman/output/report/PmTestAssertion.kt` — public assertion type for `StepReport`.

**Modified (production):**
- `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt` — add `collectionVariables` store, `environmentName`, per-step assertion/nextRequest capture.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` — `mergeEnvs` returns values + env name.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt` — thread collectionVariables + name in; diff cVars out; map+stash assertions/nextRequest; warn on nextRequest.
- `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt` — two new read-only fields.
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — unpack `mergeEnvs` result, set `pm.environmentName`, populate new StepReport fields in the fold.

**Created (tests):**
- `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKCollectionVariablesTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/postman/template/MergeEnvsNameTest.kt`
- `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`
- `src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json`
- `src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json`

---

## Task 1: Public `PmTestAssertion` type

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/report/PmTestAssertion.kt`

**Context:** The bridge's `PmAssertion` is `internal` to the `sandbox` package. We must not leak an internal type onto the public `StepReport` API, so we add a public mirror in `output.report` and map to it in `PmJsEval`.

- [ ] **Step 1: Write the type (no test — pure public data class, exercised by Task 5/6 tests)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

/**
 * The result of a single `pm.test(name, fn)` assertion block reported by the Postman sandbox.
 * Attached to [StepReport.pmTestAssertions]. A failing assertion is DATA here (not a thrown error):
 * [passed] is false and [error] carries the chai/AssertionError message. [skipped] is true for
 * `pm.test.skip(...)`.
 */
data class PmTestAssertion
@JvmOverloads
constructor(
  @JvmField val name: String,
  @JvmField val passed: Boolean,
  @JvmField val skipped: Boolean = false,
  @JvmField val error: String? = null,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/report/PmTestAssertion.kt
git commit -m "feat(report): public PmTestAssertion type for StepReport"
```

---

## Task 2: `PostmanSDK` — collectionVariables store + environmentName + per-step capture

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKCollectionVariablesTest.kt`

**Context:** `PostmanSDK` already holds `environment: PostmanEnvironment<Any?>`. We add a parallel `collectionVariables` store (same wrapper type — gives `set`/`unset`/`toMap`-via-`mutableEnv` for free, and its ledger capture stays dormant because we never set its `currentStep`), an `environmentName`, and per-`Step` capture maps for assertions + nextRequest that `PmJsEval` writes and the fold reads.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.Step
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PostmanSDKCollectionVariablesTest {
  private fun sdk() = PostmanSDK(initMoshi())

  private fun step(name: String) = Step(index = "1", rawPMStep = Item(name = name))

  @Test
  fun `collectionVariables store round-trips set and toMap`() {
    val pm = sdk()
    pm.collectionVariables.set("a", "1")
    pm.collectionVariables.toMap() shouldBe mapOf("a" to "1")
  }

  @Test
  fun `environmentName defaults to null and is settable`() {
    val pm = sdk()
    pm.environmentName shouldBe null
    pm.environmentName = "Pokemon"
    pm.environmentName shouldBe "Pokemon"
  }

  @Test
  fun `per-step pmTestAssertions capture is keyed by step`() {
    val pm = sdk()
    val s1 = step("s1")
    val assertions = listOf(PmTestAssertion("t", passed = true))
    pm.recordPmTestAssertions(s1, assertions)
    pm.pmTestAssertionsFor(s1) shouldHaveSize 1
    pm.pmTestAssertionsFor(step("other")) shouldHaveSize 0
  }

  @Test
  fun `per-step pmTestAssertions accumulate across pre-req and post-res`() {
    val pm = sdk()
    val s1 = step("s1")
    pm.recordPmTestAssertions(s1, listOf(PmTestAssertion("pre", passed = true)))
    pm.recordPmTestAssertions(s1, listOf(PmTestAssertion("post", passed = false)))
    pm.pmTestAssertionsFor(s1) shouldHaveSize 2
  }

  @Test
  fun `per-step nextRequest capture is keyed by step and last-write-wins`() {
    val pm = sdk()
    val s1 = step("s1")
    pm.recordNextRequest(s1, "first")
    pm.recordNextRequest(s1, "second")
    pm.nextRequestFor(s1) shouldBe "second"
    pm.nextRequestFor(step("other")) shouldBe null
  }
}
```

> `Step(index, rawPMStep)` + `Item(name=...)` is the existing constructor pattern used across this repo's tests (e.g. `LedgerDecisionTest`, `RegexReplacerConsumedTest`). Two `Step` instances with the same name+index are equal (data class) — fine for keying; the "other" step uses a different name so it won't collide.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.PostmanSDKCollectionVariablesTest"`
Expected: FAIL — `collectionVariables` / `environmentName` / `recordPmTestAssertions` unresolved.

- [ ] **Step 3: Write the implementation**

In `PostmanSDK.kt`, add imports near the top:

```kotlin
import com.salesforce.revoman.output.report.PmTestAssertion
```

Add fields right after the existing `environment` field (line ~38):

```kotlin
  @JvmField val environment: PostmanEnvironment<Any?> = PostmanEnvironment(mutableEnv, moshiReVoman)

  /**
   * Collection-level variables (`pm.collectionVariables`). A plain key→value store reusing
   * [PostmanEnvironment] for its set/unset/toMap utilities; its per-step ledger capture stays
   * dormant because [PostmanEnvironment.currentStep] is never set on this instance. Script-seeded
   * only (ReVoman does not parse collection-root `variable[]`), so it starts empty and is populated
   * by scripts calling `pm.collectionVariables.set(...)`.
   */
  @JvmField
  val collectionVariables: PostmanEnvironment<Any?> = PostmanEnvironment(mutableMapOf(), moshiReVoman)

  /** The active environment's display name, exposed to scripts via `pm.environment.name`. */
  @JvmField var environmentName: String? = null

  // Per-step capture written by PmJsEval after each sandbox run, read by the executor fold when it
  // builds the StepReport. Keyed by Step (a step can run a pre-req AND a post-res script).
  private val pmTestAssertionsByStep: MutableMap<Step, List<PmTestAssertion>> = mutableMapOf()
  private val nextRequestByStep: MutableMap<Step, String?> = mutableMapOf()
```

Add the import for `Step` if not present:

```kotlin
import com.salesforce.revoman.output.report.Step
```

Add these methods inside the class (e.g. right after `syncProgress`):

```kotlin
  /** Accumulates assertions across a step's pre-req + post-res scripts. */
  internal fun recordPmTestAssertions(step: Step, assertions: List<PmTestAssertion>) {
    pmTestAssertionsByStep[step] = (pmTestAssertionsByStep[step] ?: emptyList()) + assertions
  }

  internal fun pmTestAssertionsFor(step: Step): List<PmTestAssertion> =
    pmTestAssertionsByStep[step] ?: emptyList()

  /** Last-write-wins: a post-res `setNextRequest` overrides a pre-req one (matches Postman). */
  internal fun recordNextRequest(step: Step, nextRequest: String?) {
    nextRequestByStep[step] = nextRequest
  }

  internal fun nextRequestFor(step: Step): String? = nextRequestByStep[step]
```

> `PostmanEnvironment` already implements `MutableMap` by delegation, so `collectionVariables.toMap()` (Kotlin stdlib on Map) and `.set(k,v)` (its own method) both exist. No extra API needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.PostmanSDKCollectionVariablesTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKCollectionVariablesTest.kt
git commit -m "feat(sdk): collectionVariables store + environmentName + per-step pm-test/nextRequest capture"
```

---

## Task 3: `mergeEnvs` returns env name

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/MergeEnvsNameTest.kt`

**Context:** `mergeEnvs` returns only the merged value-map; the environment `name` (V3 yaml `name:` / V2 json `"name"`) is discarded. We change it to return both. Streams are consumed once, so the name MUST be captured in the same parse that reads values — hence we parse to `Environment` objects first, then derive values + name. Name precedence = last non-null across sources (yaml → json → streams), consistent with the value-merge order.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MergeEnvsNameTest {
  @Test
  fun `mergeEnvs reads the environment name from a v2 json env file`() {
    val merged =
      mergeEnvs(
        setOf("pm-templates/v2/pokemon/pokemon.postman_environment.json"),
        emptyList(),
        emptyMap(),
      )
    merged.name shouldBe "Pokemon"
    merged.values["baseUrl"] shouldBe "https://pokeapi.co/api/v2"
  }

  @Test
  fun `mergeEnvs name is null when there is no env source`() {
    val merged = mergeEnvs(emptySet(), emptyList(), mapOf("k" to "v"))
    merged.name shouldBe null
    merged.values["k"] shouldBe "v"
  }
}
```

> This unit test reads a resource under `src/test` resources path resolution. The pokemon env file lives under `src/integrationTest/resources`. If the test source set can't see it, copy a tiny 2-line env JSON into `src/test/resources/pm-templates/mini-env.postman_environment.json` with `{"name":"Pokemon","values":[{"key":"baseUrl","value":"https://pokeapi.co/api/v2","enabled":true}]}` and point the test at that path. Decide at RED time based on the failure (FileNotFound vs assertion).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.MergeEnvsNameTest"`
Expected: FAIL — `merged.name` unresolved (mergeEnvs returns `Map`, not a type with `.name`).

- [ ] **Step 3: Write the implementation**

In `Environment.kt`, add the result type above the `companion object` (inside the file, top-level):

```kotlin
/** The merged environment values plus the chosen environment display name (null if unnamed). */
internal data class MergedEnv(val values: Map<String, Any?>, val name: String?)
```

Replace the entire `mergeEnvs` function body with this (preserves value-merge precedence, adds name):

```kotlin
    @OptIn(ExperimentalStdlibApi::class)
    internal fun mergeEnvs(
      pmEnvironmentPaths: Set<String>,
      pmEnvironmentInputStreams: List<InputStream>,
      dynamicEnvironment: Map<String, Any?>,
    ): MergedEnv {
      val envAdapter = Moshi.Builder().build().adapter<Environment>()

      // V3 yaml paths — read name + values from each.
      val yamlEnvs: List<V3EnvRead> =
        pmEnvironmentPaths.filter { isV3EnvFile(it) }.map { V3EnvLoader.readWithName(it) }
      val envFromYamlPaths: Map<String, Any?> =
        yamlEnvs.fold(emptyMap()) { acc, e -> acc + e.values }

      // V2 json paths — parse to Environment once, derive values + name.
      val jsonEnvs: List<Environment> =
        pmEnvironmentPaths.filterNot { isV3EnvFile(it) }.mapNotNull { envAdapter.fromJson(bufferFile(it)) }
      val envFromJsonPaths: Map<String, Any?> =
        jsonEnvs.flatMap { it.values.filter { v -> v.enabled } }.associate { it.key to it.value }

      // Streams — parse to Environment once (single read), derive values + name.
      val streamEnvs: List<Environment> =
        pmEnvironmentInputStreams.mapNotNull { envAdapter.fromJson(bufferInputStream(it)) }
      val envFromStreams: Map<String, Any?> =
        streamEnvs.flatMap { it.values.filter { v -> v.enabled } }.associate { it.key to it.value }

      // * NOTE 10/09/23 gopala.akshintala: dynamicEnvironment keys replace envFromEnvFiles when
      // clashed
      // ! TODO 11 Jun 2025 gopala.akshintala: serialize only during regex replace
      val values = envFromYamlPaths + envFromJsonPaths + envFromStreams + dynamicEnvironment
      // Name precedence mirrors value order: last non-null wins (yaml -> json -> streams).
      val name =
        (yamlEnvs.map { it.name } + jsonEnvs.map { it.name } + streamEnvs.map { it.name })
          .lastOrNull { it != null }
      return MergedEnv(values, name)
    }
```

Add the import for `bufferInputStream` is already present; ensure `V3EnvLoader` import is present (it's referenced via fully-qualified name in the original — keep using the fully-qualified `com.salesforce.revoman.internal.postman.template.v3.V3EnvLoader.readWithName(it)` if no import exists).

- [ ] **Step 4: Add `V3EnvLoader.readWithName`**

In `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt`, add a small read-with-name helper and a tiny carrier (keep the existing `loadFromPath` untouched — other callers use it):

```kotlin
/** V3 env read carrying both the display name and the flattened key→value map. */
internal data class V3EnvRead(val name: String?, val values: Map<String, Any?>)

internal object V3EnvLoader {
  fun loadFromPath(path: String): Map<String, Any?> = readWithName(path).values

  fun readWithName(path: String): V3EnvRead {
    val yaml = bufferFile(path).readUtf8()
    val env = V3YamlReader.readEnv(yaml)
    return V3EnvRead(env.name, env.values.associate { it.key to it.value })
  }
}
```

> Place `V3EnvRead` as a top-level `internal data class` in the same file (above the object) and reference it from `Environment.kt` via import `com.salesforce.revoman.internal.postman.template.v3.V3EnvRead` (or fully-qualified). `loadFromPath` now delegates to `readWithName` — DRY, zero behavior change for its existing callers.

- [ ] **Step 5: Update the `mergeEnvs` call site in `ReVoman.kt`**

In `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`, replace (around line 155-161):

```kotlin
    val environment =
      ledgerValues +
        mergeEnvs(
          kick.environmentPaths(),
          kick.environmentInputStreams(),
          kick.dynamicEnvironment(),
        )
```

with:

```kotlin
    val mergedEnv =
      mergeEnvs(
        kick.environmentPaths(),
        kick.environmentInputStreams(),
        kick.dynamicEnvironment(),
      )
    val environment = ledgerValues + mergedEnv.values
```

(The heavily-commented ledger-as-floor precedence is preserved: `ledgerValues + mergedEnv.values`.)

- [ ] **Step 6: Run test to verify it passes + full compile**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.MergeEnvsNameTest" && ./gradlew compileKotlin`
Expected: PASS (2 tests), BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt src/main/kotlin/com/salesforce/revoman/ReVoman.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/MergeEnvsNameTest.kt
git commit -m "feat(env): mergeEnvs captures environment name (v2 json + v3 yaml + streams)"
```

---

## Task 4: Set `pm.environmentName` from the merged env in `ReVoman.kt`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Test: covered E2E by the integration test (Task 7); no standalone unit test (pure wiring of a field already unit-tested in Task 2/3).

- [ ] **Step 1: Wire the name onto `pm`**

In `ReVoman.kt`, immediately after the `pm` is constructed (line ~162-163):

```kotlin
    val pm =
      PostmanSDK(moshiReVoman, kick.nodeModulesPath(), regexReplacer, environment.toMutableMap())
```

add:

```kotlin
    pm.environmentName = mergedEnv.name
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt
git commit -m "feat(env): thread environment name onto PostmanSDK for pm.environment.name"
```

---

## Task 5: `PmJsEval` — thread collectionVariables + name in, diff out, stash + warn

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt`
- Test: covered by Task 2 unit (capture API) + Task 7 integration (E2E behavior). The wiring is exercised end-to-end; a focused unit test would require a full sandbox boot already covered by `PmSandboxApiCoverageTest`.

**Context:** `runSandboxScript` currently threads only `environment`. We add `collectionVariables` + env `name` to the context, diff the returned collection-vars back into `pm.collectionVariables`, map the sandbox assertions to public `PmTestAssertion` and stash them + `nextRequest` per step, and `warn` when a script set `nextRequest` (captured-but-not-executed).

- [ ] **Step 1: Rewrite `runSandboxScript` and its callers to pass the step**

The two callers already receive `currentStep: Step`. Thread it into `runSandboxScript`. Replace the whole `runSandboxScript` function and update both call sites.

In `executePreReqJS`, change the call:

```kotlin
        runSandboxScript(preReqJS, ScriptTarget.PRE_REQUEST, itemWithRegex.request, pm, sandbox, currentStep)
```

In `executePostResJS`, change the call:

```kotlin
        runSandboxScript(postResJs, ScriptTarget.TEST, item.request, pm, sandbox, currentStep)
```

Replace the `runSandboxScript` body with:

```kotlin
/**
 * Runs a pm script in the real Postman sandbox, then applies the returned scopes back onto the
 * [PostmanSDK] so the rest of ReVoman observes script effects:
 * - environment: diffed back via [PostmanSDK.environment] set/unset (the ledger path — unchanged).
 * - collectionVariables: diffed back via [PostmanSDK.collectionVariables] set/unset.
 * - pm.test assertions + setNextRequest: stashed per [step] for the executor to read onto StepReport.
 *
 * Throws on a script error so the surrounding [runCatching] maps it to the right failure type.
 *
 * Only sandbox-safe values (String/Number/Boolean/null — real Postman variable semantics) are sent
 * into and read back from the sandbox. Typed POJOs ReVoman stores in the env (hooks, cross-step
 * reuse) are NOT pm-script variables and are intentionally left untouched in the Kotlin env.
 */
private fun runSandboxScript(
  script: String,
  target: ScriptTarget,
  pmRequest: Request,
  pm: PostmanSDK,
  sandbox: PmSandbox,
  step: Step,
) {
  val beforeEnv: Map<String, Any?> = sandboxSafeEnv(pm.environment.mutableEnv)
  val beforeCVars: Map<String, Any?> = sandboxSafeEnv(pm.collectionVariables.mutableEnv)
  val context =
    PmExecutionContext(
      environment = PmScope("environment", beforeEnv, name = pm.environmentName),
      collectionVariables = PmScope("collectionVariables", beforeCVars),
      request = requestAsContextMap(pmRequest),
      response = if (target == ScriptTarget.TEST) responseAsContextMap(pm) else null,
    )
  val result = sandbox.execute(script, target, context)
  result.error?.let { throw it }

  // Apply env mutations back through the same set()/unset() paths the ledger reads.
  val envDiff = diffScopes(beforeEnv, result.environment)
  envDiff.produced.forEach { key -> pm.environment.set(key, result.environment[key]) }
  envDiff.unset.forEach { key -> pm.environment.unset(key) }

  // Apply collection-variable mutations back (no ledger involvement — separate store).
  val cVarDiff = diffScopes(beforeCVars, result.collectionVariables)
  cVarDiff.produced.forEach { key -> pm.collectionVariables.set(key, result.collectionVariables[key]) }
  cVarDiff.unset.forEach { key -> pm.collectionVariables.unset(key) }

  // Surface pm.test results + setNextRequest onto the StepReport (read by the executor fold).
  pm.recordPmTestAssertions(
    step,
    result.assertions.map { PmTestAssertion(it.name, it.passed, it.skipped, it.error) },
  )
  result.nextRequest?.let { next ->
    pm.recordNextRequest(step, next)
    RevomanLog.warn {
      "pm.execution.setNextRequest('$next') was captured but ReVoman does not yet reorder steps " +
        "(linear execution); directive recorded on StepReport.nextRequest only (Phase 2 will honor it)."
    }
  }
}

/** Filters a scope map to sandbox-safe values (real Postman variable values). */
private fun sandboxSafeEnv(scope: Map<String, Any?>): Map<String, Any?> =
  scope.filterValues { it == null || it is String || it is Number || it is Boolean }
```

> Note: the OLD `sandboxSafeEnv(pm: PostmanSDK)` helper is replaced by `sandboxSafeEnv(scope: Map)` so it can serve both env and collection-vars. Delete the old single-arg version.

- [ ] **Step 2: Add the imports**

At the top of `PmJsEval.kt`, add:

```kotlin
import com.salesforce.revoman.internal.log.RevomanLog
import com.salesforce.revoman.output.report.PmTestAssertion
```

(`Step` is already imported; `diffScopes`, `PmScope`, `PmExecutionContext`, `ScriptTarget` already imported.)

- [ ] **Step 3: Compile + run the existing sandbox suite (no regressions)**

Run: `./gradlew compileKotlin && ./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.*"`
Expected: BUILD SUCCESSFUL; all sandbox tests still GREEN.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt
git commit -m "feat(exe): wire collectionVariables + env name into sandbox; stash pm-test/nextRequest; warn on setNextRequest"
```

---

## Task 6: `StepReport` — two new read-only fields, populated in the fold

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Test: covered E2E by Task 7 (the integration test asserts on both fields). Field defaults keep all existing constructors/tests compiling.

- [ ] **Step 1: Add the fields to `StepReport`'s primary constructor**

In `StepReport.kt`, add to the primary constructor param list (after `envVars`):

```kotlin
  @JvmField val envVars: StepEnvVars = StepEnvVars(),
  @JvmField val pmTestAssertions: List<PmTestAssertion> = emptyList(),
  @JvmField val nextRequest: String? = null,
) {
```

Add the import:

```kotlin
import com.salesforce.revoman.output.report.PmTestAssertion
```

> `PmTestAssertion` is in the same package (`output.report`), so the import is unnecessary — remove it if the compiler flags a redundant same-package import. The secondary constructor delegates to the primary and need NOT list the new params (they default).

- [ ] **Step 2: Populate them in the executor fold**

In `ReVoman.kt`, in the terminal `.copy(...)` of the step pipeline (the block that sets `exeTimings`, `pmEnvSnapshot`, `envVars` ~line 356-365), add the two fields:

```kotlin
            .copy(
              exeTimings = exeTimings,
              pmEnvSnapshot =
                pm.environment.copy(mutableEnv = pm.environment.mutableEnv.toMutableMap()),
              envVars =
                StepEnvVars(
                  produced = pm.environment.producedKeysFor(step),
                  consumed = pm.environment.consumedKeysFor(step),
                ),
              pmTestAssertions = pm.pmTestAssertionsFor(step),
              nextRequest = pm.nextRequestFor(step),
            )
```

- [ ] **Step 3: Compile + run full unit suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL; all unit tests GREEN (existing `StepReport` consumers unaffected by additive defaulted fields).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt src/main/kotlin/com/salesforce/revoman/ReVoman.kt
git commit -m "feat(report): surface pmTestAssertions + nextRequest on StepReport"
```

---

## Task 7: Creative live integration test + collection + env

**Files:**
- Create: `src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json`
- Create: `src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json`
- Create: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`

**Context:** One collection exercising every listed API end-to-end against free live APIs: pokeapi.co (GET) for variables/environment/response/test, restful-api.dev (POST then PUT) for `pm.request.body`. Assertions read the new `StepReport` fields from `Rundown`.

- [ ] **Step 1: Create the environment file**

`src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json`:

```json
{
  "id": "a1b2c3d4-0000-0000-0000-pokemonsandbox",
  "name": "PokemonSandboxApi",
  "values": [
    { "key": "baseUrl", "value": "https://pokeapi.co/api/v2", "enabled": true },
    { "key": "uri", "value": "api.restful-api.dev", "enabled": true },
    { "key": "limit", "value": "5", "enabled": true }
  ],
  "_postman_variable_scope": "environment"
}
```

- [ ] **Step 2: Create the collection**

`src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json`:

```json
{
  "info": {
    "name": "pokemon-sandbox-api",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "all-pokemon",
      "event": [
        {
          "listen": "test",
          "script": {
            "type": "text/javascript",
            "exec": [
              "pm.test('status is 200', function () { pm.response.to.have.status(200); });",
              "var json = pm.response.json();",
              "pm.test('has results array', function () { pm.expect(json.results).to.be.an('array'); });",
              "pm.test('environment has a name', function () { pm.expect(pm.environment.name).to.be.a('string'); });",
              "var firstName = json.results[0].name;",
              "pm.environment.set('pokemonName', firstName);",
              "pm.collectionVariables.set('firstPokemon', firstName);",
              "pm.collectionVariables.set('resultCount', json.results.length);"
            ]
          }
        }
      ],
      "request": {
        "method": "GET",
        "header": [],
        "url": { "raw": "{{baseUrl}}/pokemon?limit={{limit}}" }
      }
    },
    {
      "name": "pokemon-by-name",
      "event": [
        {
          "listen": "test",
          "script": {
            "type": "text/javascript",
            "exec": [
              "pm.test('response code is 200', function () { pm.expect(pm.response.code).to.eql(200); });",
              "pm.test('text body is non-empty', function () { pm.expect(pm.response.text().length).to.be.above(0); });",
              "pm.test('collection var carried over', function () { pm.expect(pm.collectionVariables.get('firstPokemon')).to.be.a('string'); });",
              "var allCVars = pm.collectionVariables.toObject();",
              "pm.test('toObject exposes resultCount', function () { pm.expect(allCVars).to.have.property('resultCount'); });",
              "var json = pm.response.json();",
              "pm.collectionVariables.set('pokemonId', json.id);",
              "pm.execution.setNextRequest('pokemon-species');"
            ]
          }
        }
      ],
      "request": {
        "method": "GET",
        "header": [],
        "url": { "raw": "{{baseUrl}}/pokemon/{{pokemonName}}" }
      }
    },
    {
      "name": "pokemon-species",
      "event": [
        {
          "listen": "test",
          "script": {
            "type": "text/javascript",
            "exec": [
              "pm.test('species status 200', function () { pm.response.to.have.status(200); });",
              "pm.test('id matches earlier pokemon', function () { pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('pokemonId')); });"
            ]
          }
        }
      ],
      "request": {
        "method": "GET",
        "header": [],
        "url": { "raw": "{{baseUrl}}/pokemon-species/{{pokemonName}}" }
      }
    },
    {
      "name": "add-object",
      "event": [
        {
          "listen": "test",
          "script": {
            "type": "text/javascript",
            "exec": [
              "pm.test('object created', function () { pm.expect(pm.response.code).to.be.oneOf([200, 201]); });",
              "pm.environment.set('objId', pm.response.json().id);"
            ]
          }
        }
      ],
      "request": {
        "method": "POST",
        "header": [{ "key": "Content-Type", "value": "application/json" }],
        "body": { "mode": "raw", "raw": "{\n  \"name\": \"revoman-{{pokemonName}}\",\n  \"data\": { \"source\": \"revoman-sandbox-test\" }\n}" },
        "url": { "raw": "https://{{uri}}/objects" }
      }
    },
    {
      "name": "update-object",
      "event": [
        {
          "listen": "test",
          "script": {
            "type": "text/javascript",
            "exec": [
              "pm.test('request body was readable', function () { pm.expect(pm.request.body.raw).to.include('updated-revoman'); });",
              "pm.test('update echoes new name', function () { pm.expect(pm.response.json().data.name).to.eql('updated-revoman'); });"
            ]
          }
        }
      ],
      "request": {
        "method": "PUT",
        "header": [{ "key": "Content-Type", "value": "application/json" }],
        "body": { "mode": "raw", "raw": "{\n  \"name\": \"revoman-{{pokemonName}}\",\n  \"data\": { \"name\": \"updated-revoman\" }\n}" },
        "url": { "raw": "https://{{uri}}/objects/{{objId}}" }
      }
    }
  ]
}
```

> restful-api.dev `PUT /objects/{id}` requires a real id; `add-object` (POST) creates one and stashes `objId` for the PUT. The PUT body sets `data.name='updated-revoman'`, asserted both via `pm.request.body.raw` (the API) AND the echoed response.

- [ ] **Step 3: Write the integration test**

`src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`:

```java
/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.report.PmTestAssertion;
import com.salesforce.revoman.output.report.StepReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the script-only `pm` APIs through a real ReVoman run against free live
 * APIs (pokeapi.co GET + restful-api.dev POST/PUT). Verifies that variables, environment(.name),
 * request/response, test/expect, collectionVariables, and setNextRequest all surface on the Rundown.
 */
class PokemonSandboxApiTest {
  private static final String PM_COLLECTION_PATH =
      "pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json";

  @Test
  @DisplayName("script-only pm APIs surface end-to-end")
  void pmSandboxApisEndToEnd() {
    final Rundown rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(PM_COLLECTION_PATH)
                .environmentPath(PM_ENVIRONMENT_PATH)
                .nodeModulesPath("js")
                .off());

    // No step failed (HTTP + all scripts ran without thrown error).
    assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull();
    assertThat(rundown.stepReports).hasSize(5);

    // --- pm.environment / pm.test / pm.expect / pm.response.* (all-pokemon) ---
    final StepReport allPokemon = rundown.reportForStepName("all-pokemon");
    assertThat(allPokemon).isNotNull();
    assertThat(allPokemon.pmTestAssertions).isNotEmpty();
    assertThat(allPokemon.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();
    assertThat(rundown.mutableEnv).containsKey("pokemonName");

    // --- pm.collectionVariables set in step 1, read in steps 2-3 (cross-step) ---
    final StepReport byName = rundown.reportForStepName("pokemon-by-name");
    assertThat(byName.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();

    // --- pm.execution.setNextRequest: CAPTURED (not executed — Phase 2 reorders) ---
    // ReVoman still runs steps linearly; we assert only that the directive was surfaced.
    assertThat(byName.nextRequest).isEqualTo("pokemon-species");
    // Proof it was NOT executed: pokemon-species still ran in linear order after pokemon-by-name.
    assertThat(rundown.reportForStepName("pokemon-species")).isNotNull();

    // --- pm.request.body via restful-api.dev PUT ---
    final StepReport update = rundown.reportForStepName("update-object");
    assertThat(update.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();

    // --- Every assertion across the whole run passed ---
    final List<PmTestAssertion> all =
        rundown.stepReports.stream().flatMap(s -> s.pmTestAssertions.stream()).toList();
    assertThat(all).isNotEmpty();
    assertThat(all.stream().allMatch(a -> a.passed)).isTrue();
  }
}
```

> `reportForStepName` is the existing `Rundown` accessor. If a live API hiccup makes this flaky in CI, that is an environmental failure, not a logic failure — re-run; do not weaken assertions to mask it.

- [ ] **Step 4: Run the integration test**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest"`
Expected: PASS (1 test, 5 steps). If `pm.response.json()` / context field-name mismatches surface, align against `PmSandboxApiCoverageTest` (already-green reference for the same calls).

- [ ] **Step 5: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/ src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java
git commit -m "test(sandbox): live E2E integration test covering all script-only pm APIs"
```

---

## Task 8: Full verification + format + handoff

**Files:**
- Modify: (formatting only) any files spotless touches.
- Create: `~/work/handoff/2026-06-11-pm-sandbox-phase2-sequencer.md`

- [ ] **Step 1: Spotless + full build**

Run: `./gradlew spotlessApply && ./gradlew build`
Expected: BUILD SUCCESSFUL (compile, spotless, all `test`; `integrationTest` green — live APIs reachable).

- [ ] **Step 2: Run the complete sandbox unit suite once more**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.*" --tests "com.salesforce.revoman.internal.postman.PostmanSDKCollectionVariablesTest" --tests "com.salesforce.revoman.internal.postman.template.MergeEnvsNameTest"`
Expected: ALL GREEN.

- [ ] **Step 3: Write the Phase 2 handoff note**

Create `~/work/handoff/2026-06-11-pm-sandbox-phase2-sequencer.md` documenting: what's captured today (`StepReport.nextRequest`, the warn), what Phase 2 must build (jump-capable `executeStepsSerially`), the exact landmines (ledger skip/inject reconciliation, `haltOnFailure`, name→index resolution, infinite-loop guard, `setNextRequest(null)`=halt), and the test that should flip from "captured" to "executed" (`PokemonSandboxApiTest` reorder assertion). Full content provided in Step 4.

- [ ] **Step 4: Handoff note content**

Write this into `~/work/handoff/2026-06-11-pm-sandbox-phase2-sequencer.md`:

```markdown
# Handoff — PM Sandbox Phase 2: setNextRequest executing sequencer

**Date:** 2026-06-11
**Prereq (done):** API-coverage cycle — all script-only pm APIs wired E2E; setNextRequest is
CAPTURED on `StepReport.nextRequest` and warned-on, but NOT executed.

## What exists now
- `SandboxBridge.decodeResult` reads `execution.return.nextRequest` -> `PmExecutionResult.nextRequest`.
- `PmJsEval.runSandboxScript` stashes it via `pm.recordNextRequest(step, next)` and emits a
  `RevomanLog.warn` ("captured but ReVoman does not yet reorder...").
- `StepReport.nextRequest: String?` surfaces it. `PokemonSandboxApiTest` asserts it was CAPTURED
  (and that linear order was NOT changed).

## What Phase 2 builds
Turn `ReVoman.executeStepsSerially`'s linear `fold` into an index-driven driver that HONORS
`setNextRequest`:
- `setNextRequest("name")` -> jump to the step with that name (forward or backward).
- `setNextRequest(null)` -> halt the run (Postman semantics).
- absent -> fall through to the next step.

## Landmines (do NOT skip — each needs a test)
1. **Ledger skip/inject** (`shadowedPaths`, `ledgerSkipDecision`): a backward jump re-runs a producer
   step. Decide provenance: does a re-run refresh the ledger entry, or is jump-mode ledger-exempt?
2. **`haltOnFailureOfTypeExcept`**: jump logic must compose with existing halt logic, not bypass it.
3. **Name -> index resolution**: `setNextRequest` takes a request NAME; duplicate names across folders
   need a documented rule (first match? error?). Steps are deep-flattened — names may collide.
4. **Infinite-loop guard**: `A -> setNextRequest('A')` must cap visited count and fail loud.
5. **Reports for skipped/repeated steps**: linear `fold` assumes one report per step in order. A jump
   skips steps (no report?) or repeats them (multiple reports?). Define what `rundown.stepReports`
   contains after a jump.

## Test to flip
`PokemonSandboxApiTest` currently asserts `byName.nextRequest == "pokemon-species"` + linear order
preserved. Phase 2 adds/【flips to】an assertion that execution actually jumped (e.g. an in-between
step's report is absent, or order differs from declaration). Keep the capture assertion; add the
execution assertion.

## Files
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — `executeStepsSerially` (the fold).
- Spec: `docs/superpowers/specs/2026-06-11-pm-sandbox-api-coverage-design.md` (Non-Goals #1).
```

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore(sandbox): full verification; Phase 2 sequencer handoff note"
```

---

## Self-Review Notes

- **Spec coverage:** PmTestAssertion type (T1) ✓; collectionVariables store + environmentName + per-step capture (T2) ✓; mergeEnvs name (T3) ✓; environmentName wiring (T4) ✓; PmJsEval thread-in/diff-out/stash/warn (T5) ✓; StepReport fields + fold population (T6) ✓; live integration test + collection + env covering all listed APIs incl. PUT for request.body (T7) ✓; verification + Phase 2 handoff (T8) ✓. Non-goals respected: setNextRequest captured-not-executed (warn + handoff), no sendRequest, no root-variable parsing.
- **Type consistency:** `PmTestAssertion(name, passed, skipped, error)` used identically in T1/T2/T5/T6/T7. `MergedEnv(values, name)` in T3 consumed in T3/T4. `recordPmTestAssertions`/`pmTestAssertionsFor`/`recordNextRequest`/`nextRequestFor` defined T2, called T5/T6. `V3EnvRead(name, values)` defined+used T3.
- **Known soft spots flagged inline:** (a) `Step.fromName` test-factory name — verify against existing tests at RED; (b) `MergeEnvsNameTest` resource visibility across source sets — fallback mini-env provided; (c) same-package redundant `PmTestAssertion` import in StepReport — remove if flagged; (d) live-API flakiness — explicitly an environmental, not logic, failure.
- **setNextRequest scope:** captured + warned + handoff documented; executing sequencer explicitly deferred per approved design.
```

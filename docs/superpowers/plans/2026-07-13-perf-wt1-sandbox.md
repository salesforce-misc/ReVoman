# Perf WT-1: Sandbox / GraalJS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut GraalJS per-eval latency in the Postman sandbox by sharing one immutable GraalVM `Engine` across the two per-run `Context`s (reusing the parsed 2.2 MB bootcode Source + JIT), memoizing the `JSON.parse` closure, and stop the per-step 3× duplication of the current step's report in `pm.rundown` — all behavior-preserving except the intentional mid-run `Rundown` de-duplication (E1).

**Architecture:** ONE process-wide immutable `Engine` (a top-level `by lazy` val in `SandboxBridge.kt`) is passed to BOTH `Context.newBuilder("js").engine(sharedGraalEngine)` sites — the sandbox `Context` in `SandboxBridge.boot()` and the `JSEvaluator` `Context` in `PostmanSDK`. Each `Context` stays STRICTLY per-run (never shared) so guest globals/env cannot bleed between runs. Stateless guest closures (`JSON.parse`) are memoized as `by lazy` `Value`s bound to their per-run Context; the constant `imports` prefix becomes an immutable `val`.

**Tech Stack:** Kotlin, GraalVM 25.1.3 polyglot (`js-language` + `truffle-runtime`), Moshi, JMH.

## Global Constraints
- JDK 21+; branched off WT-0 (has `libs.truffle.runtime` + JMH source set)
- Owns ONLY `SandboxBridge.kt`, `PmJsEval.kt`, `PostmanSDK.kt` — touch no other source file
- Correctness gate: `./gradlew test integrationTest` green (non-core; core IT needs `-PincludeCoreIT` + a real org — skip it)
- Preserve FP style (STYLE.md): Either/map/flatMap/fold, immutable flow — no imperative rewrites
- CRITICAL: share GraalVM Engine ONLY, never a Context (guest-state bleed)
- `./gradlew spotlessApply` before every commit
---

## Interfaces (WT-0 contract this worktree consumes)

- **Consumes from WT-0:** `libs.truffle.runtime` catalog accessor added as `api` in `build.gradle.kts` (A1); a `src/jmh/kotlin` source set wired with the `me.champeau.jmh`-style plugin and a **friend-path to `main`** (`associateWith` the `main` compilation, mirroring the `integrationTest` wiring at `build.gradle.kts:83-85`) so the benchmark can see `internal` members (`PmSandbox`, `PmScope`, `PmExecutionContext`, `ScriptTarget`). Benchmark run contract: `./gradlew jmh -Pjmh.includes=SandboxBenchmark`.
- **Produces (for the whole effort):** a shared `internal val sharedGraalEngine: Engine` (top-level in `SandboxBridge.kt`) — no `ReVoman.kt` edit required; a de-duplicated mid-run `pm.rundown` from `syncProgress` (E1).
- **DO NOT TOUCH `ReVoman.kt`** (owned by WT-4). The `Rundown` seed at `ReVoman.kt:402` (`stepReportsSoFar + preStepReport`) is deliberately left in place this pass — see the E1 coupling note at the end.

---

## Task 0 — Preflight: confirm WT-0 landed

**Files:** `build.gradle.kts`, `gradle/libs.versions.toml` (READ-ONLY here — do not edit; they belong to WT-0)
**Interfaces:** Consumes WT-0 deps + jmh source set.

- [ ] Confirm the Truffle runtime dep is present:
  ```bash
  grep -n "truffle" gradle/libs.versions.toml build.gradle.kts
  ```
  Expected: a `truffle-runtime = { module = "org.graalvm.truffle:truffle-runtime", version.ref = "graal" }` catalog entry and an `api(libs.truffle.runtime)` line in `build.gradle.kts`.
- [ ] Confirm the JMH source set + friend-path exist:
  ```bash
  ls src/jmh/kotlin 2>/dev/null; grep -n "jmh\|associateWith" build.gradle.kts
  ```
  Expected: `src/jmh/kotlin` exists; a jmh compilation `associateWith` the `main` compilation. If the friend-path is MISSING, the sandbox benchmark (Task 12) must fall back to the PUBLIC-API variant (`PostmanSDK.jsonStrToObj` / `evaluateJS`) — note it and proceed.
- [ ] Baseline the existing suite so later regressions are attributable:
  ```bash
  ./gradlew test integrationTest
  ```
  Expected: `BUILD SUCCESSFUL`. (Core IT excluded by `build.gradle.kts:188-193`.)

---

## Task 1 — A2 characterization + state-bleed tests (RED-safe: green on baseline, must stay green)

**Files:**
- NEW `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEngineSharingTest.kt`
- NEW `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKEvalIsolationTest.kt`

**Interfaces:** Consumes `PmSandbox`, `PmExecutionContext`, `PmScope`, `ScriptTarget` (internal, sandbox pkg) and `PostmanSDK` + `initMoshi`. Produces the state-bleed gate (#1 risk).

- [ ] Write the sandbox reuse + state-bleed test. Real APIs: `PmSandbox()`, `sandbox.execute(script, ScriptTarget.TEST, PmExecutionContext(environment = PmScope("e", emptyMap()), response = ...))` returns `PmExecutionResult` with `.environment`, `.assertions`, `.error` (all confirmed from `PmSandboxScriptApiTest.kt`).
  ```kotlin
  /**
   * ************************************************************************************************
   * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
   * Version 2.0 For full license text, see the LICENSE file in the repo root or
   * http://www.apache.org/licenses/LICENSE-2.0
   * ************************************************************************************************
   */
  package com.salesforce.revoman.internal.postman.sandbox

  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test

  /**
   * A2 guard: the shared immutable GraalVM Engine reuses parsed bootcode across runs, but each run
   * gets its OWN Context. These tests pin that (a) repeated evals in one sandbox are deterministic
   * and (b) two sandboxes NEVER see each other's guest globals or env — the #1 state-bleed risk.
   */
  class SandboxEngineSharingTest {
      private fun testCtx(env: Map<String, Any?> = emptyMap()) =
          PmExecutionContext(environment = PmScope("e", env))

      @Test
      fun `same script evaluated twice in one sandbox yields identical results`() {
          val sandbox = PmSandbox()
          val script =
              """
              pm.test('sum', () => pm.expect(1 + 1).to.eql(2));
              pm.environment.set('n', 41 + 1);
              """
                  .trimIndent()
          val r1 = sandbox.execute(script, ScriptTarget.TEST, testCtx())
          val r2 = sandbox.execute(script, ScriptTarget.TEST, testCtx())
          sandbox.close()
          r1.error shouldBe null
          r2.error shouldBe null
          r1.assertions[0].passed shouldBe true
          r2.assertions[0].passed shouldBe true
          r1.environment["n"] shouldBe 42
          r2.environment["n"] shouldBe 42
      }

      @Test
      fun `two sandboxes sharing the Engine do not leak guest globals or env`() {
          val s1 = PmSandbox()
          s1.execute(
              "globalThis.__leak = 'from-s1'; pm.environment.set('leakedEnv', 'v1');",
              ScriptTarget.TEST,
              testCtx(),
          )
          val s2 = PmSandbox()
          val r =
              s2.execute(
                  """
                  pm.environment.set('sawGlobal', typeof globalThis.__leak);
                  pm.environment.set('sawEnv', pm.environment.has('leakedEnv'));
                  """
                      .trimIndent(),
                  ScriptTarget.TEST,
                  testCtx(),
              )
          s1.close()
          s2.close()
          r.error shouldBe null
          r.environment["sawGlobal"] shouldBe "undefined" // fresh Context: s1's global unseen
          r.environment["sawEnv"] shouldBe false // fresh Context: s1's env unseen
      }
  }
  ```
- [ ] Write the JSEvaluator (PostmanSDK) isolation test. Real APIs: `PostmanSDK(initMoshi(), "js")`, `pm.evaluateJS(js): Value`, `Value.asString()` (confirmed from `EvalJsTest.kt`). Use `globalThis`, NOT `pm.variables.set` (the latter logs `currentStepReport.step`, which is `lateinit` and would NPE).
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
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test

  /**
   * A2 guard for the JSEvaluator Context (peer of the sandbox Context). The Engine is shared; the
   * Context is per-PostmanSDK. Two instances must NOT see each other's guest globals.
   */
  class PostmanSDKEvalIsolationTest {
      @Test
      fun `two PostmanSDK instances sharing the Engine do not leak JS globals`() {
          val pm1 = PostmanSDK(initMoshi())
          pm1.evaluateJS("globalThis.__leak = 'from-pm1'; 1")
          val pm2 = PostmanSDK(initMoshi())
          pm2.evaluateJS("typeof globalThis.__leak").asString() shouldBe "undefined"
      }
  }
  ```
- [ ] Run them on the pre-change tree:
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.SandboxEngineSharingTest" --tests "com.salesforce.revoman.internal.postman.PostmanSDKEvalIsolationTest"
  ```
  Expected: `BUILD SUCCESSFUL` — both PASS already (per-run Context isolation holds today; these lock it so the Engine-sharing refactor can't regress it).
- [ ] `./gradlew spotlessApply` + commit: `test(sandbox): pin Engine-reuse determinism + cross-run state-bleed isolation (A2)`

---

## Task 2 — A2 implement: one shared immutable Engine

**Files:** `SandboxBridge.kt` (~line 45 Context build; ~line 56 the `engine.WarnInterpreterOnly` option), `PostmanSDK.kt` (~line 122 options map; ~line 130 `engine.WarnInterpreterOnly`; ~line 132 Context build)
**Interfaces:** Produces `sharedGraalEngine`. Consumes WT-0's `truffle-runtime`.

- [ ] Add the shared Engine as a top-level `by lazy` val in `SandboxBridge.kt`. Add `import org.graalvm.polyglot.Engine` (the file already imports `Context`, `Source`, `Value` from `org.graalvm.polyglot`). Place it just below the imports, above `internal class SandboxBridge`:
  ```kotlin
  /**
   * ONE process-wide immutable GraalVM [Engine], shared by every per-run [Context] (the sandbox
   * Context here AND the PostmanSDK JSEvaluator Context). The Engine caches the parsed 2.2 MB
   * bootcode [Source] and JIT-compiled code across runs and across both Context kinds, so the
   * interpreter->JIT warm-up and the bootcode parse are paid once per JVM, not per ReVoman run.
   *
   * Engines are thread-safe and long-lived by design; Contexts are single-threaded and per-run.
   * Sharing the Engine does NOT weaken the single-threaded Context contract, and — critically —
   * guest state (globals/env) lives in the Context, so sharing the Engine cannot bleed state
   * between runs. Never construct a Context WITHOUT this Engine. Left unclosed for the JVM lifetime
   * (standard for a shared library Engine).
   */
  internal val sharedGraalEngine: Engine by lazy {
      Engine.newBuilder("js")
          .allowExperimentalOptions(true)
          // Engine-level option: silence the "interpreter only / fallback runtime" warning. With
          // WT-0's truffle-runtime on the classpath this warning should no longer fire — Task 14
          // verifies and removes this line if so. Kept here (single home) until then.
          .option("engine.WarnInterpreterOnly", "false")
          .build()
  }
  ```
- [ ] In `SandboxBridge.boot()`, wire the Engine into the Context and DROP the now-duplicated engine option. BEFORE (lines 45-59):
  ```kotlin
      ctx =
        Context.newBuilder("js")
          .allowExperimentalOptions(true)
          .option("js.esm-eval-returns-exports", "true")
          .option("js.ecmascript-version", "2024")
          // Silence GraalVM's "fallback runtime / interpreter only" warning. ...
          .option("engine.WarnInterpreterOnly", "false")
          .allowHostAccess(HostAccess.ALL)
          .allowHostClassLookup { true }
          .build()
  ```
  AFTER (engine option removed — it now lives on `sharedGraalEngine`; the long comment block goes away):
  ```kotlin
      ctx =
        Context.newBuilder("js")
          .engine(sharedGraalEngine)
          .allowExperimentalOptions(true)
          .option("js.esm-eval-returns-exports", "true")
          .option("js.ecmascript-version", "2024")
          .allowHostAccess(HostAccess.ALL)
          .allowHostClassLookup { true }
          .build()
  ```
- [ ] In `PostmanSDK.kt`, add `import com.salesforce.revoman.internal.postman.sandbox.sharedGraalEngine` (both are `internal` in the same module — cross-package `internal` access is allowed). Then in `JSEvaluator.init`, drop the engine-level option from the options map and wire the Engine into the Context. BEFORE (lines 122-140):
  ```kotlin
      init {
        val options = buildMap {
          if (!nodeModulesPath.isNullOrBlank()) {
            logger.info { "nodeModulesPath: $nodeModulesPath" }
            put("js.commonjs-require", "true")
            put("js.commonjs-require-cwd", nodeModulesPath)
            imports = "var _ = require('lodash')\n"
          }
          put("js.esm-eval-returns-exports", "true")
          put("engine.WarnInterpreterOnly", "false")
        }
        jsContext =
          Context.newBuilder("js")
            .allowExperimentalOptions(true)
            .allowIO(IOAccess.ALL)
            .allowExperimentalOptions(true)
            .options(options)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .build()
  ```
  AFTER (engine option removed; the duplicate `allowExperimentalOptions(true)` collapsed to one; `.engine(sharedGraalEngine)` added; `imports` becomes a `val` — see Task 7/A4, which this init already enables):
  ```kotlin
      init {
        val options = buildMap {
          if (!nodeModulesPath.isNullOrBlank()) {
            logger.info { "nodeModulesPath: $nodeModulesPath" }
            put("js.commonjs-require", "true")
            put("js.commonjs-require-cwd", nodeModulesPath)
          }
          put("js.esm-eval-returns-exports", "true")
        }
        jsContext =
          Context.newBuilder("js")
            .engine(sharedGraalEngine)
            .allowExperimentalOptions(true)
            .allowIO(IOAccess.ALL)
            .options(options)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .build()
  ```
  (The `imports` assignment moves out of `init` in Task 7. If Task 7 is deferred, keep `imports = "var _ = require('lodash')\n"` inside the `if` block for now.)
- [ ] Run the A2 tests + the existing sandbox/eval suites:
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.*" --tests "com.salesforce.revoman.input.EvalJsTest" --tests "com.salesforce.revoman.internal.postman.PostmanSDKEvalIsolationTest"
  ```
  Expected: `BUILD SUCCESSFUL` — all green (reuse + isolation preserved; `PmSandboxBootTest`, `PmSandboxScriptApiTest`, `EvalJsTest` unaffected).
- [ ] Full correctness gate:
  ```bash
  ./gradlew test integrationTest
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] `./gradlew spotlessApply` + commit: `perf(sandbox): share one immutable GraalVM Engine across both JS contexts (A2)`

---

## Task 3 — A3 characterization test: repeated JSON parsing

**Files:** NEW `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKJsonStrToObjTest.kt`
**Interfaces:** Consumes `PostmanSDK.jsonStrToObj(String): Value` (public, line 226). Produces the memoization-safety gate.

- [ ] Write the test. Real API: `pm.jsonStrToObj(jsonStr).getMember("k")` / `.asInt()` / `.asString()` on the returned `Value` (GraalJS `Value` reflection). Two calls with DIFFERENT JSON must return DIFFERENT correct results — proving a memoized parse CLOSURE (A3) is reused but the parse itself is NOT wrongly cached by input.
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
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test

  /**
   * A3 guard: `jsonStrToObj` memoizes the JSON.parse arrow CLOSURE, not the parse RESULT. Repeated
   * calls with different inputs must still parse each input correctly (closure reuse != result
   * cache), and comment-tolerant parsing (`allowComments: true`) must be preserved.
   */
  class PostmanSDKJsonStrToObjTest {
      private val pm = PostmanSDK(initMoshi())

      @Test
      fun `repeated parses with different inputs each parse correctly`() {
          pm.jsonStrToObj("""{"a": 1}""").getMember("a").asInt() shouldBe 1
          pm.jsonStrToObj("""{"a": 2}""").getMember("a").asInt() shouldBe 2
          pm.jsonStrToObj("""{"b": "x"}""").getMember("b").asString() shouldBe "x"
      }

      @Test
      fun `parse still tolerates JSON5 comments`() {
          pm.jsonStrToObj(
                  """
                  {
                    // a line comment
                    "a": 1
                  }
                  """
                      .trimIndent()
              )
              .getMember("a")
              .asInt() shouldBe 1
      }
  }
  ```
- [ ] Run on the pre-change tree:
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.PostmanSDKJsonStrToObjTest"
  ```
  Expected: `BUILD SUCCESSFUL` — PASS today (behavior is what we preserve).
- [ ] `./gradlew spotlessApply` + commit: `test(sandbox): pin jsonStrToObj closure-reuse + comment tolerance (A3)`

---

## Task 4 — A3 implement: memoize the JSON.parse closure

**Files:** `PostmanSDK.kt` (~line 226 `jsonStrToObj`)
**Interfaces:** none new.

- [ ] Memoize the arrow function as a `by lazy` `Value` and reuse it. BEFORE (lines 225-227):
  ```kotlin
    @Language("JavaScript")
    fun jsonStrToObj(jsonStr: String): Value =
      evaluateJS("jsonStr => JSON.parse(jsonStr, {allowComments: true})").execute(jsonStr)
  ```
  AFTER:
  ```kotlin
    // Memoized JSON.parse closure: a stateless guest function bound to this instance's jsContext.
    // Parsing the function literal once (not per call) skips a Source parse + compile on every
    // json()/jsonStrToObj call. Reuse is safe — the closure holds no per-call state; only its
    // argument varies. `by lazy` defers forcing until the first parse (jsEvaluator is init'd last).
    @Language("JavaScript")
    private val jsonParseFn: Value by lazy {
      evaluateJS("jsonStr => JSON.parse(jsonStr, {allowComments: true})")
    }

    fun jsonStrToObj(jsonStr: String): Value = jsonParseFn.execute(jsonStr)
  ```
- [ ] Run A3 + the JSON-heavy eval test (`EvalJsTest.pm response to json()` exercises `pm.response.json()` → `jsonStrToObj`):
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.PostmanSDKJsonStrToObjTest" --tests "com.salesforce.revoman.input.EvalJsTest"
  ```
  Expected: `BUILD SUCCESSFUL` — all green.
- [ ] Full gate:
  ```bash
  ./gradlew test integrationTest
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] `./gradlew spotlessApply` + commit: `perf(sandbox): memoize JSON.parse closure in jsonStrToObj (A3)`

---

## Task 5 — A4 implement: hoist `imports` to an immutable val; document the (unsafe) result-memo decision

**Files:** `PostmanSDK.kt` (~line 119 `private var imports`; ~line 146 `evaluateJS`)
**Interfaces:** none.

> **Decision (record in code):** the spec's *optional* `Map<String,Value>` memo for repeated identical scripts is INTENTIONALLY SKIPPED — it is NOT clean. `evaluateJS` injects per-call `bindings` into the Context (line 148) and pm scripts routinely have side effects (`pm.environment.set(...)`) and identical text with different bindings (e.g. `xml2Json` with different `responseBody`). A script->Value result cache would skip binding injection and re-execution, corrupting behavior. The real repeated-script win is captured by A3's closure memo. A4 here is limited to making `imports` immutable.

- [ ] Turn `imports` from a mutated `var` into a computed `val`. This depends on the Task 2/A2 `init` edit that already removed `imports = ...` from the options `buildMap`. BEFORE (line 119):
  ```kotlin
      private var imports = ""
  ```
  AFTER:
  ```kotlin
      // Constant per-instance prefix, computed once. Non-empty only when a nodeModulesPath enabled
      // commonjs-require (so lodash `_` is importable). Hoisted to a val — never mutated after init.
      private val imports: String =
          if (!nodeModulesPath.isNullOrBlank()) "var _ = require('lodash')\n" else ""
  ```
  Confirm `evaluateJS` (line 149) still reads it: `Source.newBuilder("js", imports + js, "script.js").build()` — unchanged.
- [ ] Sanity-run the lodash/require path (`EvalJsTest.eval JS with lodash` / `eval JS with moment` boot the `nodeModulesPath="js"` instance):
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.input.EvalJsTest"
  ```
  Expected: `BUILD SUCCESSFUL` — `imports` still prepends `var _ = require('lodash')` for the js-node instance.
- [ ] Full gate:
  ```bash
  ./gradlew test integrationTest
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] `./gradlew spotlessApply` + commit: `perf(sandbox): hoist imports prefix to an immutable val; document no result-memo (A4)`

---

## Task 6 — E1 test: `syncProgress` replaces the current step's report (RED first)

**Files:** NEW `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKSyncProgressTest.kt`
**Interfaces:** Consumes `PostmanSDK.syncProgress(StepReport)` (internal, line 155), `Rundown` ctor, `StepReport` ctor, `Step`, `Item`, `PostmanEnvironment` (all confirmed from `PollingTest.kt` + `PostmanSDKCollectionVariablesTest.kt`). Produces the E1 correctness gate.

> This is the ONE genuinely red->green fix (behavior of mid-run `pm.rundown` intentionally changes). The test mimics `ReVoman.runStep`'s real sequence: `ReVoman.kt:402` seeds `pm.rundown` with `stepReportsSoFar + preStepReport` (current step LAST), then `syncProgress` is called 3× per step (`ReVoman.kt:451,468,483`). Today each call APPENDS → the current step ends up 4× in `pm.rundown.stepReports`. E1 makes each call REPLACE → exactly once, prior steps preserved.

- [ ] Write the test:
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
  import com.salesforce.revoman.output.Rundown
  import com.salesforce.revoman.output.postman.PostmanEnvironment
  import com.salesforce.revoman.output.report.Step
  import com.salesforce.revoman.output.report.StepReport
  import io.kotest.matchers.collections.shouldHaveSize
  import io.kotest.matchers.shouldBe
  import org.junit.jupiter.api.Test

  /**
   * E1 guard: mirrors ReVoman.runStep — the Rundown is seeded with prior steps + THIS step's
   * pre-step report (current LAST), then syncProgress runs 3x per step. syncProgress must REPLACE
   * the current step's entry (not append), so the mid-run pm.rundown holds each step exactly once
   * and prior steps + loop-iteration history are preserved.
   */
  class PostmanSDKSyncProgressTest {
      private fun step(name: String) = Step(index = "1", rawPMStep = Item(name = name))

      private fun report(step: Step) = StepReport(step = step, pmEnvSnapshot = PostmanEnvironment())

      private fun pmWithSeededRundown(stepReportsSoFar: List<StepReport>): PostmanSDK =
          PostmanSDK(initMoshi()).apply {
              rundown =
                  Rundown(
                      stepReports = stepReportsSoFar,
                      mutableEnv = PostmanEnvironment(),
                      haltOnFailureOfTypeExcept = emptyMap(),
                      providedStepsToExecuteCount = stepReportsSoFar.size,
                  )
          }

      @Test
      fun `three syncProgress calls keep the current step exactly once and preserve prior steps`() {
          val stepA = step("a")
          val stepB = step("b")
          val reportA = report(stepA)
          val preReportB = report(stepB)
          // ReVoman.kt:402 seed: prior steps + current step's pre-step report (current LAST).
          val pm = pmWithSeededRundown(listOf(reportA, preReportB))

          // ReVoman.kt:451,468,483: 3 syncProgress calls for the SAME step, each with an evolved sr.
          val srB1 = preReportB.copy(nextRequest = "afterHttp")
          val srB2 = srB1.copy(nextRequest = "afterPostRes")
          val srB3 = srB2.copy(nextRequest = "afterHooks")
          pm.syncProgress(srB1)
          pm.syncProgress(srB2)
          pm.syncProgress(srB3)

          pm.rundown.stepReports shouldHaveSize 2 // a + b, NOT a + b + b + b + b
          pm.rundown.stepReports.map { it.step.name } shouldBe listOf("a", "b")
          pm.rundown.stepReports.last() shouldBe srB3 // most-evolved report wins
          pm.currentStepReport shouldBe srB3
      }

      @Test
      fun `first syncProgress on an empty-prefix seed keeps a single entry`() {
          val stepA = step("a")
          val pre = report(stepA)
          val pm = pmWithSeededRundown(listOf(pre)) // first step of the run
          val evolved = pre.copy(nextRequest = "x")
          pm.syncProgress(evolved)
          pm.rundown.stepReports shouldHaveSize 1
          pm.rundown.stepReports.last() shouldBe evolved
      }

      @Test
      fun `looped step preserves the prior iteration report`() {
          val stepA = step("a")
          val iter0Final = report(stepA).copy(iteration = 0, nextRequest = "loop")
          val preIter1 = report(stepA).copy(iteration = 1)
          // 2nd iteration seed: prior iteration's final report + this iteration's pre-step report.
          val pm = pmWithSeededRundown(listOf(iter0Final, preIter1))
          val iter1Final = preIter1.copy(nextRequest = "done")
          pm.syncProgress(iter1Final)
          pm.rundown.stepReports shouldHaveSize 2 // iter0Final preserved, preIter1 replaced
          pm.rundown.stepReports[0] shouldBe iter0Final
          pm.rundown.stepReports[1] shouldBe iter1Final
      }
  }
  ```
- [ ] Run on the pre-change tree (expect RED):
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.PostmanSDKSyncProgressTest"
  ```
  Expected: FAILS — `three syncProgress calls...` sees `stepReports` size 5 (`[a, b, b, b, b]`), not 2. This proves the append bug is real.
- [ ] `./gradlew spotlessApply` + commit: `test(sandbox): pin syncProgress replace-current-step semantics (E1, red)`

---

## Task 7 — E1 implement: replace instead of append in `syncProgress`

**Files:** `PostmanSDK.kt` (~line 155 `syncProgress`)
**Interfaces:** none new. STRICTLY scoped to `PostmanSDK.kt` — no `ReVoman.kt` edit.

- [ ] Replace the current step's report (always the LAST entry post-seed) instead of appending. BEFORE (lines 155-158):
  ```kotlin
    internal fun syncProgress(stepReport: StepReport) {
      currentStepReport = stepReport
      rundown = rundown.copy(stepReports = rundown.stepReports + stepReport)
    }
  ```
  AFTER:
  ```kotlin
    /**
     * Publishes the current step's evolving [StepReport] into [rundown] for mid-run readers (hooks,
     * the halt predicate). ReVoman seeds [rundown] with this step's pre-step report as the LAST
     * entry, then calls this 3x per step as the report gains request/response/hook detail. REPLACES
     * the current step's entry (matched as the last report for the same [Step]) rather than
     * appending, so a step appears exactly ONCE mid-run — earlier steps and prior loop iterations
     * (which sit before it) are untouched. Keeps [Rundown] immutable via [Rundown.copy].
     */
    internal fun syncProgress(stepReport: StepReport) {
      currentStepReport = stepReport
      val reports = rundown.stepReports
      val updated =
        if (reports.lastOrNull()?.step == stepReport.step) reports.dropLast(1) + stepReport
        else reports + stepReport
      rundown = rundown.copy(stepReports = updated)
    }
  ```
  > Why "last report for the same Step" is correct: `Step` is a `data class(index, rawPMStep, parentFolder, sourceHash)` — its `==` ignores the mutable hook-count vars, so equality is stable across a step's report copies. The `ReVoman.kt:402` seed guarantees the current step's report is LAST when the first `syncProgress` fires; every subsequent `sr` is a `.copy()` of it (same `Step`). Prior steps and prior loop iterations precede it, so `dropLast(1)` never touches them. The `else` branch (append) preserves the defensive path if `syncProgress` were ever called with no matching tail (e.g. a future caller that skips the seed).
- [ ] Run E1 (expect GREEN now) + the E2E tests that assert `stepReports` shape:
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.PostmanSDKSyncProgressTest" --tests "com.salesforce.revoman.PmTestFailureE2ETest" --tests "com.salesforce.revoman.ControlFlowE2ETest" --tests "com.salesforce.revoman.LedgerSkipE2ETest" --tests "com.salesforce.revoman.PmTestPhaseTagE2ETest"
  ```
  Expected: `BUILD SUCCESSFUL` — E1 green; the E2E tests (which assert on the FINAL rundown built from `sequenceResult.reports`, unaffected by this change) still pass.
- [ ] Full gate (this is the highest-risk fix — hooks read mid-run `pm.rundown`):
  ```bash
  ./gradlew test integrationTest
  ```
  Expected: `BUILD SUCCESSFUL`. If a hook/halt-predicate test regresses, it was relying on the buggy duplicate mid-run entries — use `/systematic-debugging`; do NOT "fix" it by touching `ReVoman.kt`.
- [ ] `./gradlew spotlessApply` + commit: `perf(sandbox): replace current step's report in syncProgress instead of appending 3x (E1)`

---

## Task 8 — JMH benchmark (measured, not a gate)

**Files:** NEW `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt`
**Interfaces:** Consumes WT-0 jmh source set + friend-path to `main`. If NO friend-path (Task 0), use the PUBLIC-API fallback variant below.

- [ ] Write the benchmark (primary: through the sandbox — captures A2 interpreter->JIT + Engine reuse + A3 via `pm.response.json()`). Real APIs confirmed from `PmSandboxScriptApiTest.kt`.
  ```kotlin
  /**
   * ************************************************************************************************
   * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
   * Version 2.0 For full license text, see the LICENSE file in the repo root or
   * http://www.apache.org/licenses/LICENSE-2.0
   * ************************************************************************************************
   */
  package com.salesforce.revoman.benchmark

  import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
  import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
  import com.salesforce.revoman.internal.postman.sandbox.PmScope
  import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
  import java.util.concurrent.TimeUnit
  import org.openjdk.jmh.annotations.Benchmark
  import org.openjdk.jmh.annotations.BenchmarkMode
  import org.openjdk.jmh.annotations.Fork
  import org.openjdk.jmh.annotations.Level
  import org.openjdk.jmh.annotations.Measurement
  import org.openjdk.jmh.annotations.Mode
  import org.openjdk.jmh.annotations.OutputTimeUnit
  import org.openjdk.jmh.annotations.Scope
  import org.openjdk.jmh.annotations.Setup
  import org.openjdk.jmh.annotations.State
  import org.openjdk.jmh.annotations.TearDown
  import org.openjdk.jmh.annotations.Warmup

  /**
   * Measures the GraalJS sandbox hot path: repeated eval of a representative Postman test script.
   * Captures A2 (interpreter->JIT once via truffle-runtime + shared-Engine bootcode reuse) and A3
   * (JSON.parse closure reuse via pm.response.json()). One booted sandbox per trial; each @Benchmark
   * invocation is one eval — the steady-state per-script latency after warm-up.
   */
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  @State(Scope.Benchmark)
  @Fork(1)
  @Warmup(iterations = 5, time = 1)
  @Measurement(iterations = 8, time = 1)
  open class SandboxBenchmark {
      private lateinit var sandbox: PmSandbox

      private val script =
          """
          pm.test('status is ok', () => pm.expect(pm.response.code).to.eql(200));
          const body = pm.response.json();
          pm.environment.set('id', body.id);
          pm.test('has id', () => pm.expect(body.id).to.eql(42));
          """
              .trimIndent()

      private fun context() =
          PmExecutionContext(
              environment = PmScope("e", emptyMap()),
              response = mapOf("code" to 200, "status" to "OK", "body" to """{"id":42}"""),
          )

      @Setup(Level.Trial)
      fun setup() {
          sandbox = PmSandbox() // boots lazily on the first execute
      }

      @TearDown(Level.Trial)
      fun tearDown() = sandbox.close()

      @Benchmark
      fun evalPostmanTestScript(): Any? =
          sandbox.execute(script, ScriptTarget.TEST, context()).environment["id"]
  }
  ```
  **Fallback (only if Task 0 found no friend-path):** replace the body with the public API — construct `PostmanSDK(initMoshi())` in `@Setup`, `@Benchmark` calls `pm.jsonStrToObj("""{"id":42}""").getMember("id")` and `pm.evaluateJS("1 + 1")`. This still captures A2 (JSEvaluator Context shares the same Engine) + A3, just not the 2.2 MB bootcode path.
- [ ] Run it:
  ```bash
  ./gradlew jmh -Pjmh.includes=SandboxBenchmark
  ```
  Expected: `BUILD SUCCESSFUL` and a JMH result table, e.g.:
  ```
  Benchmark                             Mode  Cnt   Score   Error  Units
  SandboxBenchmark.evalPostmanTestScript avgt    8  XXX.X ± YY.Y  us/op
  ```
  Record the `Score` in the worktree ledger. This is MEASURED, not pass/fail. (WT-0 holds the pre-fix baseline; compare deltas there.)
- [ ] `./gradlew spotlessApply` + commit: `test(jmh): add SandboxBenchmark for the GraalJS sandbox eval hot path`

---

## Task 9 — WarnInterpreterOnly cleanup (conditional; ties off A1's tail)

**Files:** `SandboxBridge.kt` (the `sharedGraalEngine` `engine.WarnInterpreterOnly` option)
**Interfaces:** Consumes WT-0's `truffle-runtime`.

- [ ] Check whether the interpreter warning still fires with `truffle-runtime` present. Temporarily remove the `.option("engine.WarnInterpreterOnly", "false")` line from `sharedGraalEngine`, then run a sandbox test capturing stderr/logs:
  ```bash
  ./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxBootTest" --info 2>&1 | grep -i "interpreter\|fallback runtime\|optimizing runtime" || echo "NO WARNING FIRED"
  ```
  Expected with truffle-runtime on the classpath: `NO WARNING FIRED` (the optimizing runtime is found, so the warning is silent).
- [ ] Branch on the result:
  - If `NO WARNING FIRED`: keep the line removed — the suppression is now genuinely unneeded (A1's stated cleanup). Full gate `./gradlew test integrationTest` → `BUILD SUCCESSFUL`. Commit: `refactor(sandbox): drop now-unneeded WarnInterpreterOnly suppression (truffle-runtime silences it)`.
  - If the warning STILL fires: restore the line on `sharedGraalEngine` and add a code comment: `// truffle-runtime present but the interpreter warning still fires on this JVM config — suppression retained (A1).` Commit: `chore(sandbox): retain WarnInterpreterOnly suppression — warning still fires with truffle-runtime`.

---

## Final verification

- [ ] Full suite once more:
  ```bash
  ./gradlew test integrationTest
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] Benchmark delta recorded vs. WT-0 baseline (informational).
- [ ] `git log --oneline` shows the A2 -> A3 -> A4 -> E1 -> JMH -> (cleanup) commit sequence, each behind a green gate.
- [ ] Confirm only the three owned files + new test/benchmark files changed:
  ```bash
  git diff --name-only master...HEAD
  ```
  Expected: only `SandboxBridge.kt`, `PostmanSDK.kt` (PmJsEval.kt untouched — it builds no Context), the four new test files, and `SandboxBenchmark.kt`. NOTHING else — especially not `ReVoman.kt`.

---

## Cross-worktree coupling note (E1 ↔ WT-4 / `ReVoman.kt`) — READ BEFORE MERGE

`syncProgress` (WT-1) and the `Rundown` seed (WT-4, `ReVoman.kt:402`) are coupled through `pm.rundown.stepReports`:

- **`ReVoman.kt:402-410` seeds** `pm.rundown = Rundown(stepReportsSoFar + preStepReport, ...)` — this is the "4th copy" the spec leaves in place. It puts the current step's pre-step report as the LAST entry. **WT-1's E1 fix depends on this ordering invariant** (the current step is always last when `syncProgress` first fires). If WT-4 ever changes how the seed orders `stepReports` (e.g. stops appending the current step last, or switches to a persistent map that reorders), WT-1's `reports.lastOrNull()?.step == stepReport.step` check must be revisited.
- **`stepReportsSoFar` is the sequencer's clean `reports` list** (`ReVoman.kt:246,255`), NOT `pm.rundown` — each finished step is appended once. So the append-bug duplication is contained WITHIN one step's `pm.rundown` lifecycle and never leaks into the next step's seed or the FINAL consumer `Rundown` (built at `ReVoman.kt:191` from `sequenceResult.reports`). **E1 therefore changes ONLY the mid-run `pm.rundown` that hooks + the halt predicate observe**, not the returned rundown — which is why the E2E tests asserting on final `stepReports` size (`PmTestFailureE2ETest`, `LedgerSkipE2ETest`, etc.) are unaffected.
- **Mid-run readers of `pm.rundown`** (all in `ReVoman.kt`, WT-4-owned, passing it down): `preStepHookExe` (:439), `unmarshallRequest` (:433), `unmarshallResponse` (:477), `postStepHookExe` (:485), `executePolling` (:490), and `shouldHaltExecution` (:278 → `Rundown.isStepIgnoredForFailure` → `PostTxnStepPick.pick(stepReport, rundown)`). A consumer `PostTxnStepPick`/hook that read `rundown.stepReports` / `executedStepCount` mid-step previously saw the current step 2-4×; after E1 it sees it once. **This is the intended correctness improvement**, but it is the one consumer-observable behavior change in WT-1 — flag it in the merge PR so WT-4 (and any downstream hook authors) are aware.
- **E2 (WT-4) will re-back the env with a persistent map** and may further touch the seed. WT-1 and WT-4 do NOT share a file (`PostmanSDK.kt` is WT-1-only; `ReVoman.kt`/`PostmanEnvironment.kt`/`StepReport.kt` are WT-4-only), so the merge is textually conflict-free — but the `pm.rundown.stepReports` ordering invariant above is a SEMANTIC coupling both sides must preserve.

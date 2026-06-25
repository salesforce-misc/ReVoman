# Honor Postman Control-Flow Directives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ReVoman honor `pm.execution.setNextRequest(name)` (jump), `setNextRequest(null)` (stop), and `skipRequest()` (skip HTTP) — directives currently captured but ignored.

**Architecture:** Extract the linear-fold step body into a `runStep` function, then drive it with an index-based `while` loop that consults each step's control-flow directive to pick the next cursor. A no-directive run takes only the `cursor + 1` branch → byte-for-byte identical to today. Backward jumps are bounded by a global execution budget. The ledger warm-path is bypassed via a one-way latch from the first divergence onward.

**Tech Stack:** Kotlin, GraalJS sandbox (Postman uvm bootcode), Immutables (`KickDef`), JUnit5 + Google Truth + Kotest, `com.sun.net.httpserver` loopback for E2E.

## Global Constraints

- **No-compromise invariant:** a collection with NO directives must execute identically to today — same `StepReport`s, same order, same ledger warm-path behavior, same halt semantics, `stopReason == COMPLETED`. Every new branch is lazily activated behind "a directive actually fired."
- JDK 21+. Functional style (the repo is heavily FP): prefer `when`-expressions, immutable data flow, `firstOrNull`/`map`/`fold` over loops where the existing code does — BUT the sequencer is intrinsically a stateful cursor walk; a `while` with explicit indices is the correct shape here and is acceptable.
- All new public API documented with KDoc.
- All new code covered by tests; existing tests must stay green.
- `./gradlew spotlessApply` before every commit. Build/verify with `./gradlew test integrationTest`.
- Directive precedence within a step: post-response `setNextRequest` overrides pre-request (last-write-wins, already implemented in `PostmanSDK.recordNextRequest`).
- `skipRequest` is pre-request-only (`allowSkipRequest` is wired `true` only for `PRE_REQUEST` in `SandboxBridge.kt:170`).

---

## File Structure

**Create:**
- `src/main/kotlin/com/salesforce/revoman/output/StopReason.kt` — the terminal-reason enum.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/StepDirective.kt` — sealed directive (`None`/`Jump`/`Stop`) + derivation + `resolveTarget`.
- `src/test/resources/pm-templates/v3/cf-forward-jump/` — forward-jump fixture (3 steps).
- `src/test/resources/pm-templates/v3/cf-loop/` — backward-jump loop fixture.
- `src/test/resources/pm-templates/v3/cf-stop/` — `setNextRequest(null)` fixture.
- `src/test/resources/pm-templates/v3/cf-skip/` — `skipRequest()` fixture.
- `src/test/resources/pm-templates/v3/cf-unresolved/` — unknown-target fixture.
- `src/test/kotlin/com/salesforce/revoman/internal/exe/StepDirectiveTest.kt` — unit tests for derivation + resolveTarget.
- `src/test/kotlin/com/salesforce/revoman/ControlFlowE2ETest.kt` — E2E for all directives.

**Modify:**
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmExecutionContext.kt` — add `nextRequestSet` to `PmExecutionResult`.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt` — capture `execution.skipRequest.<id>` and `nextRequestSet`.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt` — record/read `skipRequest` + `nextRequestSet` per step.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt` — apply `skipRequest`/`nextRequestSet` to SDK; delete the stale Phase-2 warning.
- `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt` — add `nextRequestSet`, `iteration`, `isRequestSkipped`, `requestSkipped(...)` factory.
- `src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt` — add `Jumped`/`RunStopped`/`RequestSkipped`/`LoopBudgetExceeded`.
- `src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt` — add `maxStepExecutionFactor()` knob + check.
- `src/main/kotlin/com/salesforce/revoman/output/Rundown.kt` — add `stopReason`, change `reportForStepName` to `lastOrNull`, add `reportsForStepName`.
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — extract `runStep`, rewrite `executeStepsSerially` as a cursor loop, thread `stopReason` into the `Rundown`.

---

## Task 1: Capture `skipRequest` from the sandbox

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt` (`decodeResult`, ~lines 203-264)
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt`

**Interfaces:**
- Consumes: `PmSandbox.execute(script, target, context)` → `PmExecutionResult` (existing). `PmExecutionResult.skipRequest: Boolean` field already exists (default `false`).
- Produces: after this task, a pre-request script calling `pm.execution.skipRequest()` yields `result.skipRequest == true`.

**Background:** `skipRequest` is a SEPARATE guest emit `execution.skipRequest.<id>` (not on `execution.return`). The bootcode does `W=!0, t.dispatch(B, o.cursor), U.terminate(null)` — so a terminal `execution.result.<id>` still follows; the loop drain does NOT hang. `allowSkipRequest` is already `true` for `PRE_REQUEST` (`SandboxBridge.kt:170`).

- [ ] **Step 1: Write the failing test**

Add to `PmSandboxScriptApiTest.kt` (note: this class's `runTest` helper uses `ScriptTarget.TEST`; skip is pre-request-only, so call `execute` with `PRE_REQUEST` directly):

```kotlin
@Test
fun `skipRequest in pre-request sets skipRequest flag and does not hang`() {
    val r =
        sandbox.execute(
            "pm.execution.skipRequest();",
            ScriptTarget.PRE_REQUEST,
            PmExecutionContext(environment = PmScope("e", emptyMap())),
        )
    r.skipRequest shouldBe true
    r.error shouldBe null
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxScriptApiTest"`
Expected: FAIL — `r.skipRequest` is `false` (the emit is never decoded).

- [ ] **Step 3: Implement minimal decode**

In `SandboxBridge.decodeResult`, add a local `var skipRequest = false` next to the other `var`s (after `var nextRequest: String? = null`, ~line 209), add a `when` branch inside the `for (raw in emits)` loop (alongside `"execution.assertion.$id"` etc.):

```kotlin
"execution.skipRequest.$id" -> skipRequest = true
```

Then thread it into the returned `PmExecutionResult` (the constructor call near line 256):

```kotlin
return PmExecutionResult(
    environment,
    globals,
    collectionVariables,
    assertions,
    error,
    nextRequest,
    skipRequest,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxScriptApiTest"`
Expected: PASS (all tests in the class, including the new one).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt \
        src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt
git commit -m "feat(pm): capture skipRequest directive from the sandbox"
```

---

## Task 2: Distinguish `setNextRequest(null)` (STOP) from "never called"

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmExecutionContext.kt:54-62`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt` (`decodeResult`)
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt`

**Interfaces:**
- Produces: `PmExecutionResult.nextRequestSet: Boolean` (default `false`). Semantics: `false` → directive never called; `true` with `nextRequest == null/blank` → STOP; `true` with a name → JUMP.

**Background:** The bootcode's `setNextRequest(t)` does `e.return.nextRequest = t`. `execution.return` is serialized into `execution.result`'s `return` map. So "key present in `return`" distinguishes a call from no-call. `setNextRequest(null)` writes the `nextRequest` key with a null value.

- [ ] **Step 1: Write the failing tests**

Add to `PmSandboxScriptApiTest.kt`:

```kotlin
@Test
fun `setNextRequest with a name sets nextRequest and nextRequestSet`() {
    val r = runTest("pm.execution.setNextRequest('target');")
    r.nextRequest shouldBe "target"
    r.nextRequestSet shouldBe true
}

@Test
fun `setNextRequest null sets the flag with a null name (STOP)`() {
    val r = runTest("pm.execution.setNextRequest(null);")
    r.nextRequest shouldBe null
    r.nextRequestSet shouldBe true
}

@Test
fun `no setNextRequest call leaves nextRequestSet false`() {
    val r = runTest("pm.test('noop', () => pm.expect(1).to.eql(1));")
    r.nextRequestSet shouldBe false
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxScriptApiTest"`
Expected: FAIL — `nextRequestSet` does not compile / does not exist yet.

- [ ] **Step 3: Add the field to `PmExecutionResult`**

In `PmExecutionContext.kt`, update `PmExecutionResult` and its KDoc:

```kotlin
/**
 * The outcome of a single sandbox execution.
 * - [environment]/[globals]/[collectionVariables]: the FULL post-execution scope values (caller
 *   diffs against the pre-snapshot to derive produced/unset).
 * - [assertions]: pm.test results (failures are data, NOT thrown).
 * - [error]: a thrown script error (pre-req/test JS failure) — null on success.
 * - [nextRequest]: the `setNextRequest` argument; null when never set OR explicitly set to null.
 * - [nextRequestSet]: true iff `setNextRequest` was called at all. Disambiguates an explicit
 *   `setNextRequest(null)` (STOP) from "never called" (no directive) — both leave [nextRequest] null.
 * - [skipRequest]: true iff `pm.execution.skipRequest()` was called (pre-request only).
 */
internal data class PmExecutionResult(
    val environment: Map<String, Any?>,
    val globals: Map<String, Any?>,
    val collectionVariables: Map<String, Any?>,
    val assertions: List<PmAssertion>,
    val error: Throwable?,
    val nextRequest: String? = null,
    val skipRequest: Boolean = false,
    val nextRequestSet: Boolean = false,
)
```

- [ ] **Step 4: Capture presence in `decodeResult`**

In `SandboxBridge.decodeResult`, add `var nextRequestSet = false` near the other vars. In the `"execution.result.$id"` branch, replace the existing `nextRequest = ...` line with:

```kotlin
val returnMap = (execution["return"] as? Map<*, *>)
nextRequestSet = returnMap?.containsKey("nextRequest") == true
nextRequest = returnMap?.get("nextRequest") as? String
```

Add `nextRequestSet` to the returned `PmExecutionResult(...)` constructor call (after `skipRequest`).

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxScriptApiTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmExecutionContext.kt \
        src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt \
        src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt
git commit -m "feat(pm): distinguish setNextRequest(null) STOP from never-called"
```

---

## Task 3: Thread `skipRequest` + `nextRequestSet` through PostmanSDK and onto StepReport

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt:96-175`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt:139-155`
- Modify: `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt:30-51`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt:358-369` (the `.copy(...)` capture block)
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmJsEvalScopesDiffTest.kt` (or a new sibling test) — but simplest is to assert via the E2E in Task 9. For unit coverage here, add to `PmJsEvalScopesDiffTest.kt`.

**Interfaces:**
- Consumes: `PmExecutionResult.skipRequest`, `PmExecutionResult.nextRequestSet` (Tasks 1-2).
- Produces:
  - `PostmanSDK.recordSkipRequest(step: Step)`, `PostmanSDK.skipRequestFor(step: Step): Boolean`.
  - `PostmanSDK.recordNextRequest(step, nextRequest, set)` (extended signature), `PostmanSDK.nextRequestSetFor(step: Step): Boolean`.
  - `StepReport.nextRequestSet: Boolean` (default `false`).

- [ ] **Step 1: Add SDK state + accessors**

In `PostmanSDK.kt`, near the existing `nextRequestByStep` (line 97), add:

```kotlin
private val nextRequestSetByStep: MutableMap<Step, Boolean> = mutableMapOf()
private val skipRequestByStep: MutableMap<Step, Boolean> = mutableMapOf()
```

Replace `recordNextRequest` (lines 166-173) and `nextRequestFor` (line 175) with:

```kotlin
/**
 * Last-write-wins: a post-res `setNextRequest` overrides a pre-req one (matches Postman). [set] is
 * true iff `setNextRequest` was called at all this phase, so the sequencer can tell an explicit
 * `setNextRequest(null)` (STOP) from "never called" (no directive). The latest phase's [set] wins
 * alongside its [nextRequest].
 */
internal fun recordNextRequest(step: Step, nextRequest: String?, set: Boolean) {
    nextRequestByStep[step] = nextRequest
    nextRequestSetByStep[step] = set
}

internal fun nextRequestFor(step: Step): String? = nextRequestByStep[step]

internal fun nextRequestSetFor(step: Step): Boolean = nextRequestSetByStep[step] ?: false

/** Records a pre-request `pm.execution.skipRequest()` for [step]. */
internal fun recordSkipRequest(step: Step) {
    skipRequestByStep[step] = true
}

internal fun skipRequestFor(step: Step): Boolean = skipRequestByStep[step] ?: false
```

- [ ] **Step 2: Apply the directives in `PmJsEval.runSandboxScript`**

In `PmJsEval.kt`, replace the `result.nextRequest?.let { ... }` block (lines 148-154) with:

```kotlin
if (result.nextRequestSet) {
    pm.recordNextRequest(step, result.nextRequest, set = true)
}
if (result.skipRequest) {
    pm.recordSkipRequest(step)
}
```

(This deletes the stale "ReVoman does not yet reorder steps … Phase 2 will honor it" warning.)

- [ ] **Step 3: Add `nextRequestSet` to `StepReport`**

In `StepReport.kt`, add after the `nextRequest` field (line 50), and update the `nextRequest` KDoc:

```kotlin
/**
 * Next request set via `pm.execution.setNextRequest(...)` in this step's scripts, if any. Now
 * HONORED by the sequencer: a name causes a jump, a null causes a run stop.
 */
@JvmField val nextRequest: String? = null,
/** True iff `setNextRequest` was called at all (distinguishes `setNextRequest(null)` STOP). */
@JvmField val nextRequestSet: Boolean = false,
```

- [ ] **Step 4: Capture it in `ReVoman.kt`**

In the `.copy(...)` block at `ReVoman.kt:358-369`, add `nextRequestSet = pm.nextRequestSetFor(step),` right after the existing `nextRequest = pm.nextRequestFor(step),` line.

- [ ] **Step 5: Write a unit test for the SDK plumbing**

Add to `PmJsEvalScopesDiffTest.kt` (it already constructs a `PostmanSDK` + runs scripts — mirror its setup). If that harness is awkward, assert through the E2E in Task 9 instead and skip this step, noting why. Minimal direct test:

```kotlin
@Test
fun `setNextRequest and skipRequest are recorded on the SDK per step`() {
    // Arrange a PostmanSDK + sandbox as the sibling tests do, run a pre-request script:
    //   "pm.execution.setNextRequest('z'); pm.execution.skipRequest();"
    // Assert: pm.nextRequestFor(step) == "z"; pm.nextRequestSetFor(step); pm.skipRequestFor(step).
}
```

- [ ] **Step 6: Run full unit tests (verify no regressions in callers of `recordNextRequest`)**

Run: `./gradlew test`
Expected: PASS. (The only caller of `recordNextRequest` is `PmJsEval`; the signature change is covered.)

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt \
        src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt \
        src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt \
        src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
        src/test/kotlin/com/salesforce/revoman/internal/exe/PmJsEvalScopesDiffTest.kt
git commit -m "feat(pm): plumb skipRequest + nextRequestSet onto StepReport"
```

---

## Task 4: `StopReason` enum + `Rundown` wiring

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/StopReason.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/output/Rundown.kt:16-33, 74-86`
- Test: `src/test/kotlin/com/salesforce/revoman/output/RundownStopReasonTest.kt` (create)

**Interfaces:**
- Produces:
  - `enum class StopReason { COMPLETED, STOPPED_BY_DIRECTIVE, HALTED_ON_FAILURE, LOOP_BUDGET_EXCEEDED }`.
  - `Rundown.stopReason: StopReason` (default `COMPLETED`, last constructor param so existing positional constructions are unaffected).
  - `Rundown.reportForStepName` returns the LAST matching report.
  - `Rundown.reportsForStepName(stepName): List<StepReport>`.

- [ ] **Step 1: Create the enum**

`src/main/kotlin/com/salesforce/revoman/output/StopReason.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

/** Why a Rundown's step execution loop terminated. */
enum class StopReason {
    /** All picked steps ran to natural completion (the default, no directive intervened). */
    COMPLETED,
    /** A `pm.execution.setNextRequest(null)` stopped the run. */
    STOPPED_BY_DIRECTIVE,
    /** A step failed and halt config (`haltOnAnyFailure`/`haltOnFailureOfTypeExcept`) halted the run. */
    HALTED_ON_FAILURE,
    /** A jump loop exceeded the per-run execution budget (`maxStepExecutionFactor`). */
    LOOP_BUDGET_EXCEEDED,
}
```

- [ ] **Step 2: Write the failing test**

`src/test/kotlin/com/salesforce/revoman/output/RundownStopReasonTest.kt`:

```kotlin
/* license header */
package com.salesforce.revoman.output

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RundownStopReasonTest {
    @Test
    fun `stopReason defaults to COMPLETED`() {
        val rundown =
            Rundown(
                stepReports = emptyList(),
                mutableEnv = PostmanEnvironment(),
                haltOnFailureOfTypeExcept = emptyMap(),
                providedStepsToExecuteCount = 0,
            )
        assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RundownStopReasonTest"`
Expected: FAIL — `stopReason` does not exist.

- [ ] **Step 4: Add `stopReason` to `Rundown`**

In `Rundown.kt`, add as the LAST constructor parameter (after `globals`):

```kotlin
@JvmField val stopReason: StopReason = StopReason.COMPLETED,
```

- [ ] **Step 5: Change `reportForStepName` to last-wins + add plural**

Replace `reportForStepName` (lines 74-76):

```kotlin
/**
 * The report for the named step. When a step ran multiple times (a control-flow loop), returns the
 * LAST (most-recent) execution. Use [reportsForStepName] for the full per-iteration history.
 */
fun reportForStepName(stepName: String): StepReport? = stepReports.lastOrNull {
    it.step.stepNameMatches(stepName)
}

/** All reports for the named step, in execution order (one per iteration for a looped step). */
fun reportsForStepName(stepName: String): List<StepReport> = stepReports.filter {
    it.step.stepNameMatches(stepName)
}
```

- [ ] **Step 6: Run to verify pass + no regressions**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RundownStopReasonTest" && ./gradlew test`
Expected: PASS. (`reportForStepName` returning last instead of first is behavior-identical for non-looped runs, where there is exactly one match.)

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/StopReason.kt \
        src/main/kotlin/com/salesforce/revoman/output/Rundown.kt \
        src/test/kotlin/com/salesforce/revoman/output/RundownStopReasonTest.kt
git commit -m "feat(pm): add Rundown.stopReason + last-wins reportForStepName"
```

---

## Task 5: `maxStepExecutionFactor` Kick knob

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt:47, 110-117`
- Test: `src/test/kotlin/com/salesforce/revoman/input/config/KickTest.kt`

**Interfaces:**
- Produces: `Kick.maxStepExecutionFactor(): Int` (default `10`), builder setter `.maxStepExecutionFactor(Int)`, validated `>= 1`.

- [ ] **Step 1: Write the failing test**

Add to `KickTest.kt`:

```kotlin
@Test
fun `maxStepExecutionFactor defaults to 10 and is configurable`() {
    val def = Kick.configure().templatePath("x").off()
    assertThat(def.maxStepExecutionFactor()).isEqualTo(10)
    val custom = Kick.configure().templatePath("x").maxStepExecutionFactor(3).off()
    assertThat(custom.maxStepExecutionFactor()).isEqualTo(3)
}
```

(Match the import style already in `KickTest.kt` — check whether it uses Truth or Kotest and follow it; the snippet above assumes Truth `assertThat`. If the file uses Kotest, write `def.maxStepExecutionFactor() shouldBe 10`.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.KickTest"`
Expected: FAIL — `maxStepExecutionFactor` does not exist.

- [ ] **Step 3: Add the knob + check**

In `KickDef.kt`, after `haltOnAnyFailure` (line 47), add:

```kotlin
/**
 * Per-run execution budget multiplier: the sequencer aborts once total step executions exceed
 * `pickedSteps × maxStepExecutionFactor`. Bounds backward-jump loops created by
 * `pm.execution.setNextRequest`. Default 10 is generous for legitimate loops while bounding
 * runaways; a no-jump run executes each step once, far under the cap. Must be >= 1.
 */
@Value.Default fun maxStepExecutionFactor(): Int = 10
```

In `validateConfig()` (after the existing `require` blocks, ~line 116), add:

```kotlin
require(maxStepExecutionFactor() >= 1) {
    "`maxStepExecutionFactor` must be >= 1, was ${maxStepExecutionFactor()}"
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.KickTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt \
        src/test/kotlin/com/salesforce/revoman/input/config/KickTest.kt
git commit -m "feat(pm): add maxStepExecutionFactor loop-budget knob to Kick"
```

---

## Task 6: `StepDirective` — derivation + target resolution

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/exe/StepDirective.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/exe/StepDirectiveTest.kt`

**Interfaces:**
- Consumes: `StepReport.nextRequest`, `StepReport.nextRequestSet`, `StepReport.isRequestSkipped` (Tasks 3, 7), `Step.stepNameMatches` (existing).
- Produces:
  - `sealed interface StepDirective { object None; object Stop; data class Jump(val target: String) }`.
  - `fun directiveOf(report: StepReport): StepDirective` — derives from the report's `nextRequestSet`/`nextRequest`.
  - `fun resolveTarget(target: String, pickedSteps: List<Step>, fromCursor: Int): Int` — index of the first step matching via `stepNameMatches`, else `fromCursor + 1` (with a warn logged by the caller — see note).

**Note on resolveTarget logging:** keep `resolveTarget` PURE (returns the index or the linear-fallback index) so it is trivially unit-testable; it returns a sentinel the caller interprets. Simpler: return `Int?` — `null` means "unresolved, caller should warn + advance linearly." That keeps logging at the call site.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/salesforce/revoman/internal/exe/StepDirectiveTest.kt`:

```kotlin
/* license header */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.Step
import org.junit.jupiter.api.Test

class StepDirectiveTest {

    // directiveOf: derive None / Jump / Stop from a report's nextRequestSet + nextRequest.
    @Test
    fun `directiveOf returns None when setNextRequest never called`() {
        assertThat(directiveFromRaw(set = false, name = null)).isEqualTo(StepDirective.None)
    }

    @Test
    fun `directiveOf returns Stop when set with null or blank name`() {
        assertThat(directiveFromRaw(set = true, name = null)).isEqualTo(StepDirective.Stop)
        assertThat(directiveFromRaw(set = true, name = "  ")).isEqualTo(StepDirective.Stop)
    }

    @Test
    fun `directiveOf returns Jump when set with a name`() {
        assertThat(directiveFromRaw(set = true, name = "target"))
            .isEqualTo(StepDirective.Jump("target"))
    }

    // resolveTarget: first picked step matching by name, else null (unresolved).
    @Test
    fun `resolveTarget finds the index of the matching picked step`() {
        val steps = listOf(stepNamed("a"), stepNamed("b"), stepNamed("c"))
        assertThat(resolveTarget("c", steps, fromCursor = 0)).isEqualTo(2)
    }

    @Test
    fun `resolveTarget returns null when no picked step matches`() {
        val steps = listOf(stepNamed("a"), stepNamed("b"))
        assertThat(resolveTarget("nope", steps, fromCursor = 0)).isNull()
    }

    private fun stepNamed(name: String): Step =
        Step(index = "1", rawPMStep = Item(name = name))  // adjust Item ctor to the minimal valid form
}
```

**Implementer note:** `Item` construction here must produce a `Step` whose `name == name`. Inspect `Item`'s constructor (`com.salesforce.revoman.internal.postman.template.Item`) and the existing test helpers — search the test tree for `Item(` to copy a minimal valid instantiation. If `Item` is awkward to build directly, prefer driving `directiveOf` through a real `StepReport` and testing `resolveTarget` with steps obtained from a tiny `deepFlattenItems` over a loaded fixture. Pick whichever the existing tests already do. `directiveFromRaw` is a tiny private helper in the test that constructs a `StepDirective` the same way `directiveOf` will — OR call `directiveOf` on a `StepReport` you build. Keep the assertions; adapt the construction to compile.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.StepDirectiveTest"`
Expected: FAIL — `StepDirective`/`directiveOf`/`resolveTarget` do not exist.

- [ ] **Step 3: Implement `StepDirective.kt`**

```kotlin
/* license header */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport

/** A step's control-flow intent, derived from its `pm.execution.setNextRequest` directive. */
internal sealed interface StepDirective {
    /** No directive — advance to the next step linearly. */
    object None : StepDirective

    /** `setNextRequest(null)` — stop the run. */
    object Stop : StepDirective

    /** `setNextRequest(name)` — jump to the step matching [target]. */
    data class Jump(val target: String) : StepDirective
}

/**
 * Derives the control-flow directive from a finished step's report.
 * - `nextRequestSet == false` → [StepDirective.None] (no directive).
 * - set with a null/blank name → [StepDirective.Stop].
 * - set with a name → [StepDirective.Jump].
 */
internal fun directiveOf(report: StepReport): StepDirective =
    when {
        !report.nextRequestSet -> StepDirective.None
        report.nextRequest.isNullOrBlank() -> StepDirective.Stop
        else -> StepDirective.Jump(report.nextRequest)
    }

/**
 * Resolves a jump [target] to an index in [pickedSteps] (the execution universe). Returns the index
 * of the FIRST step whose [Step.stepNameMatches] is true, or null when no picked step matches
 * (target was filtered out by run/skip picks, or is a typo). The caller warns + advances linearly on
 * null. [fromCursor] is unused for matching but documents the jump origin for callers.
 */
internal fun resolveTarget(target: String, pickedSteps: List<Step>, fromCursor: Int): Int? {
    val idx = pickedSteps.indexOfFirst { it.stepNameMatches(target) }
    return if (idx >= 0) idx else null
}
```

If the test uses a private `directiveFromRaw`, implement it in the test to call `directiveOf` on a constructed `StepReport`, OR simplify the test to call `directiveOf` directly. The production `directiveOf` takes a `StepReport`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.StepDirectiveTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/exe/StepDirective.kt \
        src/test/kotlin/com/salesforce/revoman/internal/exe/StepDirectiveTest.kt
git commit -m "feat(pm): add StepDirective derivation + jump target resolution"
```

---

## Task 7: `StepReport.requestSkipped` factory + `isRequestSkipped` + `iteration`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/report/StepReportSkipTest.kt` (create)

**Interfaces:**
- Produces:
  - `StepReport.iteration: Int` (default `0`).
  - `StepReport.isRequestSkipped: Boolean` — successful, no req/resp, and explicitly a request-skip (distinct from `isLedgerSkipped`).
  - `StepReport.Companion.requestSkipped(step, env, iteration): StepReport`.
  - `StepEvent.RequestSkipped(path)`, `StepEvent.Jumped(path, toPath)`, `StepEvent.RunStopped(path, reason)`, `StepEvent.LoopBudgetExceeded(path, budget)`.

**Design note on distinguishing skip from ledger-skip:** both have null req/resp + successful. Add a private constructor flag. The cleanest non-breaking way: add `@JvmField val requestSkippedFlag: Boolean = false` to the primary constructor (default false → ledger-skipped reports keep `isRequestSkipped == false`), and derive `isRequestSkipped` from it.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/salesforce/revoman/output/report/StepReportSkipTest.kt`:

```kotlin
/* license header */
package com.salesforce.revoman.output.report

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class StepReportSkipTest {
    @Test
    fun `requestSkipped report is successful, has no req or resp, and is flagged isRequestSkipped`() {
        val step = loadAnyStep()  // implementer: build/obtain a Step (see note)
        val sr = StepReport.requestSkipped(step, PostmanEnvironment(), iteration = 2)
        assertThat(sr.isSuccessful).isTrue()
        assertThat(sr.requestInfo).isNull()
        assertThat(sr.responseInfo).isNull()
        assertThat(sr.isRequestSkipped).isTrue()
        assertThat(sr.isLedgerSkipped).isFalse()
        assertThat(sr.iteration).isEqualTo(2)
    }

    @Test
    fun `ledgerSkipped report is not isRequestSkipped`() {
        val step = loadAnyStep()
        val sr = StepReport.ledgerSkipped(step, setOf("k"), PostmanEnvironment())
        assertThat(sr.isLedgerSkipped).isTrue()
        assertThat(sr.isRequestSkipped).isFalse()
    }
}
```

**Implementer note for `loadAnyStep()`:** reuse the project's existing way of obtaining a `Step` in a unit test (search the test tree: `grep -rl "Step(" src/test`). If none is trivial, load the `cf-skip` fixture via the same loader the E2E uses and take `stepReports.first().step`, or construct a minimal `Step` exactly as `StepDirectiveTest` does. Keep the assertions identical.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.StepReportSkipTest"`
Expected: FAIL — `requestSkipped`/`isRequestSkipped`/`iteration` do not exist.

- [ ] **Step 3: Extend `StepReport`**

Add to the primary constructor (after `nextRequestSet` from Task 3):

```kotlin
/** Iteration index of this execution of the step (0 for the common single-run case; >0 in a loop). */
@JvmField val iteration: Int = 0,
/** Internal marker: this report is a pre-request `skipRequest()` skip (set by [requestSkipped]). */
@JvmField val requestSkippedFlag: Boolean = false,
```

Add the derived flag near `isLedgerSkipped` (line 112):

```kotlin
/**
 * True when this step's HTTP dispatch was SKIPPED by a pre-request `pm.execution.skipRequest()`.
 * Like [isLedgerSkipped] it carries no [requestInfo]/[responseInfo], but it is a script-driven skip
 * (not a ledger reuse) and produces no env vars. Distinguished from [isLedgerSkipped] via an
 * explicit marker so the two never conflate.
 */
@JvmField val isRequestSkipped: Boolean = requestSkippedFlag
```

Add the factory in the `companion object` (next to `ledgerSkipped`):

```kotlin
/**
 * A RECORDED report for a step whose HTTP dispatch was skipped by a pre-request
 * `pm.execution.skipRequest()`. Successful (a skip is not a failure), carries no
 * request/response, produces no env vars. [iteration] tags the loop iteration (0 if not looped).
 */
@JvmStatic
@JvmOverloads
fun requestSkipped(
    step: Step,
    env: PostmanEnvironment<Any?>,
    iteration: Int = 0,
): StepReport =
    StepReport(
        step = step,
        pmEnvSnapshot = env.copy(mutableEnv = env.mutableEnv.toMutableMap()),
        iteration = iteration,
        requestSkippedFlag = true,
    )
```

- [ ] **Step 4: Add the new `StepEvent`s**

In `StepEvent.kt`, add inside the `sealed interface StepEvent` (after `LedgerSkipped`):

```kotlin
/** A pre-request `skipRequest()` skipped this step's HTTP dispatch. */
data class RequestSkipped(override val path: String) : StepEvent

/** `setNextRequest(name)` jumped from [path] to [toPath]. */
data class Jumped(override val path: String, val toPath: String) : StepEvent

/** The run stopped at [path] for [reason] (e.g. `setNextRequest(null)` or loop budget). */
data class RunStopped(override val path: String, val reason: String) : StepEvent

/** A jump loop exceeded the per-run execution [budget] at [path]. */
data class LoopBudgetExceeded(override val path: String, val budget: Int) : StepEvent
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.StepReportSkipTest" && ./gradlew test`
Expected: PASS (full suite — the new optional constructor params default, so existing `StepReport`/`ledgerSkipped` constructions are unaffected).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt \
        src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt \
        src/test/kotlin/com/salesforce/revoman/output/report/StepReportSkipTest.kt
git commit -m "feat(pm): add requestSkipped report shape + iteration + control-flow events"
```

---

## Task 8: Rewrite the sequencer — extract `runStep`, drive with a cursor loop

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt:181-416`
- Test: covered by the E2E suite in Task 9; this task's gate is **the full existing suite stays green** (the no-compromise proof).

**Interfaces:**
- Consumes: `directiveOf`, `resolveTarget` (Task 6); `StepReport.requestSkipped`, `isRequestSkipped`, `iteration` (Task 7); `pm.skipRequestFor(step)` (Task 3); `StopReason` (Task 4); `kick.maxStepExecutionFactor()` (Task 5); the new `StepEvent`s (Task 7).
- Produces: `executeStepsSerially` returns the list of `StepReport`s AND the terminal `StopReason`. Change its return type to `Pair<List<StepReport>, StopReason>` (or a small internal data class `SequenceResult(reports, stopReason)`), consumed in `revUp` to build the `Rundown`.

**This is the largest task. Approach: pure refactor first (behavior-preserving), then add directive handling.**

- [ ] **Step 1: Baseline — run the full suite GREEN before touching anything**

Run: `./gradlew test integrationTest`
Expected: PASS. Record the pass count. This is the regression baseline.

- [ ] **Step 2: Extract the step lifecycle into `runStep` (pure refactor, no behavior change)**

Pull the body of the current `fold` lambda (everything from `pm.environment.currentStep = step` through building `currentStepReport`, but NOT the `haltExecution =` assignment, the `StepEvent.StepFinished` emit, or the `stepReports + currentStepReport` accumulation) into:

```kotlin
private fun runStep(
    step: Step,
    iteration: Int,
    bypassLedger: Boolean,
    stepReportsSoFar: List<StepReport>,
    pmStepsCount: Int,
    shadowedPaths: Set<String>,
    kick: Kick,
    moshiReVoman: MoshiReVoman,
    regexReplacer: RegexReplacer,
    pm: PostmanSDK,
    sandbox: PmSandbox,
): StepReport {
    // ... existing ledger warm-path block (skip+inject / warn-and-run) ...
    // ... existing pre-req → http → post-res → hooks → polling chain ...
    // ... existing .copy(...) capture, now ALSO setting iteration = iteration ...
}
```

Key edits while extracting:
- The ledger warm-path block (the `if (step.path !in shadowedPaths && ledgerSkipDecision(...))` skip and the warn-and-run `if`) must be guarded by `!bypassLedger`. When `bypassLedger` is true, skip BOTH the skip+inject branch and the `shadowedPaths` consultation entirely — the step always dispatches fresh. (The warn-and-run log is harmless either way but gate it too for cleanliness.)
- The ledger-skip early return currently does `return@fold ...`; in `runStep` it becomes `return StepReport.ledgerSkipped(...)`.
- Add `iteration = iteration` to the final `.copy(...)` capture block.
- `runStep` does NOT compute `haltExecution`, does NOT emit `StepFinished`, does NOT accumulate — the caller does (so the caller owns the loop + events).

Leave `executeStepsSerially` calling `runStep` from inside the existing `fold` for THIS step, still returning `List<StepReport>` and still computing halt the old way. Goal: identical behavior, just relocated code.

- [ ] **Step 3: Run the full suite to verify the pure refactor is green**

Run: `./gradlew test integrationTest`
Expected: PASS, same count as Step 1. If anything changed, the extraction altered behavior — fix before proceeding.

- [ ] **Step 4: Commit the refactor separately**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt
git commit -m "refactor(pm): extract runStep from the sequencer fold (no behavior change)"
```

- [ ] **Step 5: Replace the fold with a cursor-driven while loop**

Rewrite `executeStepsSerially` to return `SequenceResult` and drive the cursor. The `StepFinished` event emit (currently lines 372-412) stays — move it into the loop body after `runStep`, unchanged. Full new body:

```kotlin
internal data class SequenceResult(val reports: List<StepReport>, val stopReason: StopReason)

private fun executeStepsSerially(
    pmStepsFlattened: List<Step>,
    kick: Kick,
    moshiReVoman: MoshiReVoman,
    regexReplacer: RegexReplacer,
    pm: PostmanSDK,
    sandbox: PmSandbox,
): SequenceResult {
    val pickedSteps =
        pmStepsFlattened.filter { shouldStepBePicked(it, kick.runOnlySteps(), kick.skipSteps()) }
    val shadowedPaths = shadowedProducerPaths(pickedSteps, kick.ledger())
    val budget = pickedSteps.size * kick.maxStepExecutionFactor()

    val reports = mutableListOf<StepReport>()
    val iterationByPath = mutableMapOf<String, Int>()
    var cursor = 0
    var executions = 0
    var bypassLedger = false           // one-way latch: set on the first real jump/skip
    var stopReason = StopReason.COMPLETED

    while (cursor in pickedSteps.indices) {
        val step = pickedSteps[cursor]
        pm.environment.currentStep = step
        val iteration = iterationByPath.getOrDefault(step.path, 0)

        val report =
            runStep(
                step, iteration, bypassLedger, reports, pmStepsFlattened.size,
                shadowedPaths, kick, moshiReVoman, regexReplacer, pm, sandbox,
            )
        reports += report
        iterationByPath[step.path] = iteration + 1
        executions++

        // (StepFinished event emit — moved here verbatim from the old fold, using `report`.)
        emitStepFinished(step, report)   // implementer: inline the existing StepEvent.StepFinished(...) block

        // A pre-request skip diverges control flow (ledger order assumption broken from here on).
        if (report.isRequestSkipped) bypassLedger = true

        // Budget guard (catches runaway backward-jump loops).
        if (executions >= budget) {
            RevomanLog.event(StepEvent.LoopBudgetExceeded(step.path, budget))
            RevomanLog.warn { "🛑 Loop budget exceeded ($budget executions); stopping the run." }
            stopReason = StopReason.LOOP_BUDGET_EXCEEDED
            break
        }

        // Failure halt (same predicate as before; rundown built from reports-so-far).
        val rundown =
            Rundown(
                reports, pm.environment, kick.haltOnFailureOfTypeExcept(),
                pmStepsFlattened.size, collectionVariables = pm.collectionVariables,
                globals = pm.globals,
            )
        if (shouldHaltExecution(report, kick, rundown)) {
            stopReason = StopReason.HALTED_ON_FAILURE
            break
        }

        cursor =
            when (val directive = directiveOf(report)) {
                StepDirective.None -> cursor + 1
                StepDirective.Stop -> {
                    RevomanLog.event(StepEvent.RunStopped(step.path, "setNextRequest(null)"))
                    RevomanLog.info { "🛑 setNextRequest(null) at ${step.path} — stopping the run." }
                    stopReason = StopReason.STOPPED_BY_DIRECTIVE
                    pickedSteps.size // out of indices -> loop exits
                }
                is StepDirective.Jump -> {
                    bypassLedger = true
                    val target = resolveTarget(directive.target, pickedSteps, cursor)
                    if (target == null) {
                        RevomanLog.warn {
                            "⚠️ setNextRequest('${directive.target}') at ${step.path} matched no " +
                                "picked step; continuing linearly."
                        }
                        cursor + 1
                    } else {
                        RevomanLog.event(StepEvent.Jumped(step.path, pickedSteps[target].path))
                        RevomanLog.info {
                            "↪️ Jump ${step.path} -> ${pickedSteps[target].path}"
                        }
                        target
                    }
                }
            }
    }
    return SequenceResult(reports, stopReason)
}
```

**Implementer notes:**
- `emitStepFinished(step, report)` is shorthand: inline the EXISTING `RevomanLog.event(StepEvent.StepFinished(...))` block (ReVoman.kt:372-412) here, swapping `currentStepReport` → `report`. Do not extract a helper unless it reads cleaner; verbatim inline is fine.
- The `Stop` branch returns `pickedSteps.size` (an out-of-range index) so the `while (cursor in pickedSteps.indices)` condition exits cleanly — equivalent to `break` but keeps the `when` exhaustive and expression-shaped.
- The `bypassLedger` latch is set on a Jump BEFORE the next iteration runs, and on a skip right after the report. Both are one-way (never reset). The linear prefix before the first divergence runs with the ledger fully active.

- [ ] **Step 6: Handle `skipRequest` inside `runStep`**

Within `runStep`, after the pre-req JS executes and BEFORE unmarshall-request/HTTP, check the skip flag. The pre-req runs as part of the existing chain; the cleanest insertion is right after the `executePreReqJS` step resolves successfully. Restructure the chain so that, once pre-req JS has run, you check `pm.skipRequestFor(step)`:

```kotlin
// After pre-req JS has been applied to the SDK (pm.skipRequestFor is now populated for this step):
if (pm.skipRequestFor(step)) {
    RevomanLog.event(StepEvent.RequestSkipped(step.path))
    RevomanLog.info { "⏭️ skipRequest() at ${step.path} — skipping HTTP dispatch." }
    return StepReport.requestSkipped(step, pm.environment, iteration)
}
```

**Implementer note:** the existing chain is a single `Either` fold (`executePreReqJS(...).mapLeft{...}.flatMap{...}...`). The skip check must happen AFTER `executePreReqJS` (which is what records the skip onto the SDK via `PmJsEval`) but only when pre-req succeeded. Insert it as a `.flatMap` stage immediately after the PRE-REQ-JS stage: if `pm.skipRequestFor(step)` short-circuit by returning a `Left`/sentinel that the outer code turns into the `requestSkipped` report — OR, simpler and clearer, pull the pre-req JS call out of the fold, run it first, check skip, and only enter the rest of the chain if not skipped. Prefer the second (explicit) form for readability; it keeps the skip a top-level early `return` rather than threading a sentinel through Either. Ensure a skipped step still gets its `pmEnvSnapshot`/`iteration` set (the `requestSkipped` factory handles env; iteration is passed in).

- [ ] **Step 7: Wire `SequenceResult` into `revUp`**

At the `executeStepsSerially` call site (ReVoman.kt:161-164), capture the result and pass `stopReason` to the `Rundown`:

```kotlin
val sequenceResult =
    PmSandbox().use { sandbox ->
        executeStepsSerially(pmStepsDeepFlattened, kick, moshiReVoman, regexReplacer, pm, sandbox)
    }
val stepNameToReport = sequenceResult.reports
// ... learnedLedger derivation unchanged (operates on stepNameToReport) ...
return Rundown(
    stepNameToReport,
    pm.environment,
    kick.haltOnFailureOfTypeExcept(),
    pmStepsDeepFlattened.size,
    learnedLedger,
    pm.collectionVariables,
    pm.globals,
    sequenceResult.stopReason,
)
```

- [ ] **Step 8: Run the FULL suite — the no-compromise gate**

Run: `./gradlew test integrationTest`
Expected: PASS, same count as Step 1's baseline (plus any new tests added in Tasks 1-7). `LedgerSkipE2ETest` MUST pass unchanged — it proves the linear/ledger path is intact. If any pre-existing test fails, the loop is not behavior-equivalent on the no-directive path; fix before proceeding.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt
git commit -m "feat(pm): honor setNextRequest/skipRequest in a cursor-driven sequencer"
```

---

## Task 9: End-to-end tests for all directives

**Files:**
- Create fixtures under `src/test/resources/pm-templates/v3/` (one dir per scenario; each dir needs a `.resources/definition.yaml` containing `$kind: collection` and one `*.request.yaml` per step, mirroring `pm-test-fail`).
- Create: `src/test/kotlin/com/salesforce/revoman/ControlFlowE2ETest.kt`

**Interfaces:**
- Consumes everything above. Loopback `HttpServer` per `PmTestFailureE2ETest` (echoes `{}` 200; record hit counts per path for the skip/jump assertions).

**Fixture pattern reminder (from `pm-test-fail`):** each `*.request.yaml` is:
```yaml
$kind: http-request
name: <step-name>
url: "{{baseUrl}}/<path>"
method: GET
scripts:
  - type: afterResponse        # or beforeRequest for pre-request scripts
    code: |-
      <js>
    language: text/javascript
order: <n>                      # controls execution order
```

- [ ] **Step 1: Create the forward-jump fixture**

`src/test/resources/pm-templates/v3/cf-forward-jump/.resources/definition.yaml`:
```yaml
$kind: collection
```
`a.request.yaml` (jumps to c, skipping b):
```yaml
$kind: http-request
name: a
url: "{{baseUrl}}/a"
method: GET
scripts:
  - type: afterResponse
    code: |-
      pm.execution.setNextRequest('c');
    language: text/javascript
order: 1000
```
`b.request.yaml`:
```yaml
$kind: http-request
name: b
url: "{{baseUrl}}/b"
method: GET
order: 2000
```
`c.request.yaml`:
```yaml
$kind: http-request
name: c
url: "{{baseUrl}}/c"
method: GET
order: 3000
```

- [ ] **Step 2: Create the loop fixture (`cf-loop`)**

Two steps. `seed` (pre-request initializes a counter), `loop` (post-response increments and jumps back to itself until counter reaches 3). Use `beforeRequest` on `seed` to set `count` if unset, and `afterResponse` on `loop`:

`seed.request.yaml`:
```yaml
$kind: http-request
name: seed
url: "{{baseUrl}}/seed"
method: GET
scripts:
  - type: beforeRequest
    code: |-
      if (!pm.environment.get('count')) { pm.environment.set('count', 0); }
    language: text/javascript
order: 1000
```
`loop.request.yaml`:
```yaml
$kind: http-request
name: loop
url: "{{baseUrl}}/loop"
method: GET
scripts:
  - type: afterResponse
    code: |-
      let n = Number(pm.environment.get('count')) + 1;
      pm.environment.set('count', n);
      if (n < 3) { pm.execution.setNextRequest('loop'); }
    language: text/javascript
order: 2000
```

- [ ] **Step 3: Create the stop fixture (`cf-stop`)**

`a.request.yaml` calls `pm.execution.setNextRequest(null);` in `afterResponse`; `b.request.yaml` is a plain step at `order: 2000` that must NOT run.

- [ ] **Step 4: Create the skip fixture (`cf-skip`)**

`skipme.request.yaml` with a `beforeRequest` script `pm.execution.skipRequest();` at `order: 1000`, and `after.request.yaml` plain at `order: 2000` (must run).

- [ ] **Step 5: Create the unresolved-target fixture (`cf-unresolved`)**

`a.request.yaml` calls `pm.execution.setNextRequest('does-not-exist');` in `afterResponse`; `b.request.yaml` plain at `order: 2000` (must still run — linear continue).

- [ ] **Step 6: Write the E2E test class**

`src/test/kotlin/com/salesforce/revoman/ControlFlowE2ETest.kt`:

```kotlin
/* license header */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.StopReason
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ControlFlowE2ETest {

    private fun run(collection: String, factor: Int? = null) =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(collection)
                .dynamicEnvironment("baseUrl", baseUrl)
                .insecureHttp(true)
                .let { if (factor != null) it.maxStepExecutionFactor(factor) else it }
                .off()
        )

    @Test
    fun `forward jump skips the intermediate step`() {
        val rundown = run("pm-templates/v3/cf-forward-jump")
        // a then c; b never executed.
        assertThat(rundown.stepReports.map { it.step.name }).containsExactly("a", "c").inOrder()
        assertThat(rundown.reportForStepName("b")).isNull()
        assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }

    @Test
    fun `backward jump loops until the condition is met`() {
        val rundown = run("pm-templates/v3/cf-loop")
        // seed once + loop three times (n=1,2,3; jumps back while n<3).
        assertThat(rundown.reportsForStepName("loop")).hasSize(3)
        assertThat(rundown.reportsForStepName("loop").map { it.iteration })
            .containsExactly(0, 1, 2).inOrder()
        assertThat(rundown.mutableEnv.getAsString("count")).isEqualTo("3")
        assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }

    @Test
    fun `unbounded loop is bounded by the execution budget`() {
        // cf-loop with factor 1 => budget = 2 picked steps * 1 = 2 executions, loop wants more.
        val rundown = run("pm-templates/v3/cf-loop", factor = 1)
        assertThat(rundown.stopReason).isEqualTo(StopReason.LOOP_BUDGET_EXCEEDED)
    }

    @Test
    fun `setNextRequest null stops the run`() {
        val rundown = run("pm-templates/v3/cf-stop")
        assertThat(rundown.stopReason).isEqualTo(StopReason.STOPPED_BY_DIRECTIVE)
        assertThat(rundown.reportForStepName("b")).isNull()
    }

    @Test
    fun `skipRequest skips HTTP but the run continues`() {
        val before = hits.getOrDefault("/skipme", AtomicInteger(0)).get()
        val rundown = run("pm-templates/v3/cf-skip")
        val skipped = rundown.reportForStepName("skipme")!!
        assertThat(skipped.isRequestSkipped).isTrue()
        assertThat(skipped.isSuccessful).isTrue()
        // No HTTP reached the server for the skipped step.
        assertThat(hits.getOrDefault("/skipme", AtomicInteger(0)).get()).isEqualTo(before)
        // The following step ran.
        assertThat(rundown.reportForStepName("after")!!.isSuccessful).isTrue()
        assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }

    @Test
    fun `unresolved jump target warns and continues linearly`() {
        val rundown = run("pm-templates/v3/cf-unresolved")
        // Both steps ran (linear continue), run completed.
        assertThat(rundown.reportForStepName("a")!!.isSuccessful).isTrue()
        assertThat(rundown.reportForStepName("b")!!.isSuccessful).isTrue()
        assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }

    companion object {
        private lateinit var server: HttpServer
        private lateinit var baseUrl: String
        private val hits = ConcurrentHashMap<String, AtomicInteger>()

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                hits.computeIfAbsent(exchange.requestURI.path) { AtomicInteger(0) }.incrementAndGet()
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        @AfterAll @JvmStatic fun stopServer() = server.stop(0)
    }
}
```

- [ ] **Step 7: Run the E2E suite**

Run: `./gradlew test --tests "com.salesforce.revoman.ControlFlowE2ETest"`
Expected: PASS (all 6 tests).

**If a test fails:** debug per `superpowers:systematic-debugging`. Likely culprits: `order:` not producing the expected pick order (verify via `rundown.stepReports.map { it.step.name }`); the loop fixture's counter starting wrong (the `beforeRequest` seed must run before the first `afterResponse`); `getAsString` not being the right accessor (check `PostmanEnvironment` API — mirror `LedgerSkipE2ETest`'s `mutableEnv.getAsString`).

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add src/test/resources/pm-templates/v3/cf-forward-jump \
        src/test/resources/pm-templates/v3/cf-loop \
        src/test/resources/pm-templates/v3/cf-stop \
        src/test/resources/pm-templates/v3/cf-skip \
        src/test/resources/pm-templates/v3/cf-unresolved \
        src/test/kotlin/com/salesforce/revoman/ControlFlowE2ETest.kt
git commit -m "test(pm): E2E for setNextRequest jump/stop/loop-budget + skipRequest"
```

---

## Task 10: Ledger-interaction E2E (jump disables warm-path from divergence onward)

**Files:**
- Create: `src/test/resources/pm-templates/v3/cf-ledger-jump/` (3 steps: a producer, a jumper, an act-step after the jump target).
- Create: `src/test/kotlin/com/salesforce/revoman/ControlFlowLedgerE2ETest.kt`

**Interfaces:** consumes `LedgerSnapshot`/`LedgerEntry` (see `LedgerSkipE2ETest` for construction) + the cursor loop's `bypassLedger` latch.

**Goal:** prove (a) the linear prefix before a jump STILL ledger-skips, and (b) steps at/after a jump dispatch FRESH even if they have a matching ledger entry.

- [ ] **Step 1: Design the fixture**

Three steps by `order`:
- `p1` (order 1000): a producer the ledger can skip (no scripts, like `ledger-skip/produce-id`).
- `jumper` (order 2000): `afterResponse` does `pm.execution.setNextRequest('p3');`.
- `p3` (order 3000): another producer the ledger COULD skip — but because it runs AFTER the jump, it must dispatch fresh.

`cf-ledger-jump/.resources/definition.yaml` → `$kind: collection`. Write the three `*.request.yaml` files accordingly (model on `ledger-skip/produce-id.request.yaml` + the jump script from Task 9 Step 1).

- [ ] **Step 2: Write the test**

```kotlin
/* license header */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerSnapshot
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ControlFlowLedgerE2ETest {
    private val collection = "pm-templates/v3/cf-ledger-jump"

    private fun kick(snap: LedgerSnapshot? = null) =
        Kick.configure()
            .templatePath(collection)
            .dynamicEnvironment("baseUrl", baseUrl)
            .insecureHttp(true)
            .let { if (snap != null) it.ledger(snap) else it }
            .off()

    @Test
    fun `linear prefix still ledger-skips but post-jump steps dispatch fresh`() {
        // Cold run to learn real paths + hashes.
        val cold = ReVoman.revUp(kick())
        val p1 = cold.stepReports.first { it.step.name == "p1" }.step
        val p3 = cold.reportsForStepName("p3").last().step

        // Build a ledger that COULD skip both p1 and p3.
        val snap =
            LedgerSnapshot(
                orgId = null,
                steps =
                    mapOf(
                        p1.path to LedgerEntry(setOf("p1key"), p1.sourceHash),
                        p3.path to LedgerEntry(setOf("p3key"), p3.sourceHash),
                    ),
                values = mapOf("p1key" to "P1V", "p3key" to "P3V"),
            )

        val before = hits.getOrDefault("/p1", AtomicInteger(0)).get()
        val warm = ReVoman.revUp(kick(snap))

        // p1 is BEFORE the jump => ledger-skipped (no HTTP).
        assertThat(hits.getOrDefault("/p1", AtomicInteger(0)).get()).isEqualTo(before)
        assertThat(warm.reportForStepName("p1")!!.isLedgerSkipped).isTrue()

        // p3 is the jump TARGET (control diverged) => dispatched fresh despite a matching entry.
        assertThat(warm.reportForStepName("p3")!!.isLedgerSkipped).isFalse()
        assertThat(warm.reportForStepName("p3")!!.responseInfo).isNotNull()
    }

    companion object {
        private lateinit var server: HttpServer
        private lateinit var baseUrl: String
        private val hits = ConcurrentHashMap<String, AtomicInteger>()

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                hits.computeIfAbsent(exchange.requestURI.path) { AtomicInteger(0) }.incrementAndGet()
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        @AfterAll @JvmStatic fun stopServer() = server.stop(0)
    }
}
```

**Implementer note:** the producer steps need their produced key to ALREADY be in env for `ledgerSkipDecision` to skip (the env-superset precondition). As `LedgerSkipE2ETest` documents, `revUp` seeds `ledger.values` into the env up front, satisfying this — no manual pre-seed. If `p1` is not getting skipped, confirm `p1.sourceHash` is non-empty (real v3 hash) and that `p1` is genuinely before the jump in pick order. Adjust the fixture's `order:` if needed.

- [ ] **Step 3: Run**

Run: `./gradlew test --tests "com.salesforce.revoman.ControlFlowLedgerE2ETest"`
Expected: PASS. Debug per `systematic-debugging` if not.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply
git add src/test/resources/pm-templates/v3/cf-ledger-jump \
        src/test/kotlin/com/salesforce/revoman/ControlFlowLedgerE2ETest.kt
git commit -m "test(pm): E2E - jumps disable the ledger warm-path from divergence onward"
```

---

## Task 11: Docs + final full verification

**Files:**
- Modify: any Antora/`docs/` page documenting execution flow or pm support (search `docs/` for "setNextRequest" / "linear" / "Phase 2"). Update to state directives are now honored, document the `maxStepExecutionFactor` knob and `StopReason`.
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt` — verify the stale warning is gone (done in Task 3; confirm here).

- [ ] **Step 1: Find docs mentioning the captured-only behavior**

Run: `grep -rln "setNextRequest\|does not yet reorder\|Phase 2\|captured but" docs/ src/main 2>/dev/null`
Expected: a list. Review each; update prose that claims directives are ignored.

- [ ] **Step 2: Update the docs**

For each doc hit, replace "captured but not honored" / "linear execution only" language with a short section: ReVoman honors `setNextRequest(name)` (jump), `setNextRequest(null)` (stop), and `skipRequest()` (skip); loops are bounded by `maxStepExecutionFactor` (default 10); the terminal reason is on `Rundown.stopReason`; jumps disable the ledger warm-path from the divergence point onward. (If there is NO existing doc page on execution flow, add a short subsection to the most relevant existing page — do not create a new doc site structure.)

- [ ] **Step 3: Confirm no stale code comments remain**

Run: `grep -rn "Phase 2\|does not yet reorder\|CAPTURED ONLY\|captured but" src/main`
Expected: no hits referring to control flow being unimplemented. Fix any stragglers (e.g. `StepReport.nextRequest` KDoc — updated in Task 3; double-check).

- [ ] **Step 4: Full build + test**

Run: `./gradlew spotlessApply && ./gradlew build`
Expected: BUILD SUCCESSFUL, all tests (unit + integration) pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs(pm): document honored control-flow directives + stopReason + loop budget"
```

---

## Self-Review Notes (verification the plan covers the spec)

- **Component 1 (skipRequest capture)** → Task 1.
- **Component 2 (setNextRequest(null) vs never-called)** → Task 2.
- **Component 3 (cursor sequencer)** → Task 8 (extract + rewrite).
- **Component 4 (skipRequest semantics)** → Task 3 (SDK plumb) + Task 7 (report shape) + Task 8 Step 6 (short-circuit).
- **Component 5 (ledger latch, divergence-onward)** → Task 8 (`bypassLedger` one-way latch) + Task 10 (E2E proof).
- **Component 6 (report shape + stopReason)** → Task 4 (Rundown) + Task 7 (iteration/skip report).
- **Component 7 (Kick knob)** → Task 5.
- **Component 8 (logging + stale-warning removal)** → Task 3 (delete warning) + Task 7 (events) + Task 11 (docs/comments).
- **Testing strategy (8 E2E + units)** → Tasks 1,2,5,6,7 (units) + Task 9 (6 E2E) + Task 10 (ledger E2E). Regression gate is Task 8 Step 8 (full suite green = the no-compromise proof).

**Type consistency check:** `recordNextRequest(step, nextRequest, set)` (Task 3) matches its sole caller in `PmJsEval` (Task 3 Step 2). `directiveOf(report)` (Task 6) reads `report.nextRequestSet`/`report.nextRequest` (Task 3). `resolveTarget(...): Int?` (Task 6) matches the `when` in Task 8 Step 5 (null → warn + `cursor+1`). `SequenceResult(reports, stopReason)` (Task 8) consumed in `revUp` (Task 8 Step 7) and feeds `Rundown.stopReason` (Task 4). `requestSkipped(step, env, iteration)` (Task 7) called in Task 8 Step 6. `StepReport.iteration` (Task 7) set in `runStep`'s `.copy` (Task 8 Step 2) and asserted in Task 9. All consistent.

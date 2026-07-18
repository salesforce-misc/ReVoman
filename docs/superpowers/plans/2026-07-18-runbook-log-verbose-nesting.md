# Runbook Log Verbose Nesting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the per-test run-log read as a verbose runbook — every HTTP request/response nested under its `┌ … └` step via a `│` spine, DEBUG narration flood gated — by moving the generic event→string grammar into the ReVoman library and enhancing it once for all consumers.

**Architecture:** Extract the duplicated `StepEvent → String` grammar (today in both the library `ConsoleRunLogSink.kt` and Core `PerTestRunLogSink.java`) into ONE library object `RunLogRenderer`. Both sinks delegate to it, so they cannot re-diverge. Enhance the renderer with a single-spine TUI grammar (every inner line `│`-prefixed, `── REQ ── / ── RESP ──` sub-rules). Core keeps only file/OrgMode/toggle/DEBUG-gate policy.

**Tech Stack:** Kotlin (library, `revoman-root`), Java 21 (Core wrapper, `loki-core`), Gradle + Kotest/JUnit5 + Google Truth, bazel (Core), `.bazelrc-local` source override.

## Global Constraints

- JDK 21+ for the JVM target (`revoman-root`). Detekt breaks on JDK 25 — use JDK 21.
- `revoman-root` is Kotlin, four-space indent, functional style (STYLE.md): prefer `when`, sequences, immutable flow. Document public APIs with KDoc.
- `loki-core` Java follows Gopal's Java style: `final var` locals, functional/declarative, no parameter mutation, newest Java syntax the level allows.
- The library sink `line()` is a no-op by contract; the DEBUG gate is a Core-ONLY concern (Core's `line()` tees narration to the file).
- Renderer must be a pure, stateless `object` — Java calls its methods statically (`@JvmStatic`).
- Sinks MUST NOT throw from `event`/`line`/`close` (hot execution path); existing `runCatching`/best-effort guards stay.
- Core compiles library SOURCES via `~/core-public/core/.bazelrc-local` (compile sees `RunLogRenderer` immediately). The running E2E server holds JARS — a live run needs a jar rebuild + server restart (Task 4). USER runs builds/restarts.
- Spec: `revoman-root/docs/superpowers/specs/2026-07-18-runbook-log-verbose-nesting-impl.md`.

---

## File Structure

- `revoman-root/src/main/kotlin/com/salesforce/revoman/output/log/RunLogRenderer.kt` — NEW. Pure object; the single `StepEvent → String` grammar (glyphs, spine, `┌└` corners, phase/sub rules, `gutter`).
- `revoman-root/src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt` — MODIFY. Delegates `event()` to `RunLogRenderer.render`; drops its own `render`/`phaseRule`/`renderStepOpen`/`renderStepClose`/`renderContractFailed`/`renderFinished`/`RULE_WIDTH`.
- `revoman-root/src/test/kotlin/com/salesforce/revoman/output/log/RunLogRendererTest.kt` — NEW. Grammar unit tests.
- `revoman-root/src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt` — MODIFY. Assertions retargeted to the enhanced shape.
- `loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSink.java` — MODIFY. `event()` delegates to `RunLogRenderer.render`; deletes duplicated grammar (`renderCoarse`/`renderFinished`/`phaseRule`/`RULE_WIDTH`/`toJsonLine`/`jsonStringMap`/`jsonStrings`/`esc`); `line()` gains the DEBUG gate; banner legend rewritten.
- `loki-core/test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSinkTest.java` — MODIFY. Assertions retargeted; DEBUG-gate tests added.

---

## Task 1: Extract `RunLogRenderer` (behavior-preserving refactor)

Move the library's existing `StepEvent → String` grammar into a new pure object, with `ConsoleRunLogSink` delegating. NO grammar change — the existing `ConsoleRunLogSinkTest` (unchanged) is the safety net proving the extraction preserves behavior.

**Files:**
- Create: `revoman-root/src/main/kotlin/com/salesforce/revoman/output/log/RunLogRenderer.kt`
- Modify: `revoman-root/src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt`
- Test: existing `revoman-root/src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt` (UNCHANGED — the net)

**Interfaces:**
- Consumes: `StepEvent`, `Outcome`, `Phase` (existing).
- Produces: `RunLogRenderer.render(event: StepEvent): String` (`@JvmStatic`), `RunLogRenderer.RULE_WIDTH: Int`. Later tasks and Core call `RunLogRenderer.render(event)`.

- [ ] **Step 1: Create `RunLogRenderer.kt` with the CURRENT grammar (verbatim move)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/**
 * The single source of truth for rendering a [StepEvent] to its one-or-more display lines. Shared by
 * EVERY [RunLogSink] (the library [ConsoleRunLogSink] and any consumer sink, e.g. Core's
 * per-test-file sink) so the grammar — glyph markers, the `│` spine, `┌ … └` step corners, phase
 * rules — is defined ONCE and cannot drift between sinks. Pure and stateless: every method is a
 * function of its arguments only, so a single shared instance is safe across threads and runs.
 *
 * Methods are [JvmStatic] so a Java consumer calls `RunLogRenderer.render(event)` directly.
 */
object RunLogRenderer {

  /** Fixed width for phase-rule horizontal lines. */
  const val RULE_WIDTH: Int = 52

  /** Render [event] to its display string (newline-terminated). Total function over [StepEvent]. */
  @JvmStatic
  fun render(event: StepEvent): String =
    when (event) {
      is StepEvent.PhaseEntered -> phaseRule(event.phase.name)
      is StepEvent.RunbookStepStarted -> renderStepOpen(event)
      is StepEvent.RunbookStepFinished -> renderStepClose(event)
      is StepEvent.RunbookContractFailed -> renderContractFailed(event)
      is StepEvent.StepStarted -> "│ · ${event.name}\n"
      is StepEvent.StepFinished -> renderFinished(event)
      is StepEvent.LedgerSkipped -> "│ ↺ reused ${event.reused}\n"
      is StepEvent.RequestSkipped -> "│ ⊘ skipped ${event.path}\n"
      is StepEvent.Jumped -> "│ ↪ ${event.path} → ${event.toPath}\n"
      is StepEvent.RunStopped -> "■ STOP ${event.path}: ${event.reason}\n"
      is StepEvent.LoopBudgetExceeded -> "✖ LOOP-BUDGET ${event.path} budget=${event.budget}\n"
    }

  private fun phaseRule(name: String): String {
    val prefix = "━━ $name "
    val fill = (RULE_WIDTH - prefix.length).coerceAtLeast(3)
    return prefix + "━".repeat(fill) + "\n"
  }

  private fun renderStepOpen(e: StepEvent.RunbookStepStarted): String {
    val marker = if (e.underTest) "◆" else "▶"
    val consumes = if (e.consumes.isEmpty()) "—" else e.consumes.joinToString(", ")
    val underTest = if (e.underTest) "   ★ UNDER TEST" else ""
    return "┌ $marker ${e.intent}          ⟵ $consumes$underTest\n"
  }

  private fun renderStepClose(e: StepEvent.RunbookStepFinished): String {
    val marker = if (e.outcome == Outcome.FAILED) "✘" else "✔"
    val produced =
      if (e.produced.isEmpty()) "—"
      else e.produced.entries.joinToString(", ") { (k, v) -> if (v == null) k else "$k=$v" }
    return "└ $marker ${e.intent}          ⟶ $produced\n"
  }

  private fun renderContractFailed(e: StepEvent.RunbookContractFailed): String {
    val parts =
      listOfNotNull(
        e.missingConsumed.takeIf { it.isNotEmpty() }?.let { "missing consumed: $it" },
        e.missingProduced.takeIf { it.isNotEmpty() }?.let { "missing produced: $it" },
        e.valueMismatches
          .takeIf { it.isNotEmpty() }
          ?.let { "value mismatch (expected→actual): $it" },
      )
    return "│ ⚠ CONTRACT  ${parts.joinToString("; ")}\n"
  }

  private fun renderFinished(event: StepEvent.StepFinished): String {
    val word =
      when (event.outcome) {
        Outcome.SUCCESS -> "OK"
        Outcome.FAILED -> "FAIL"
        Outcome.SKIPPED -> "SKIP"
      }
    val header = "│   ${event.httpStatus} $word ${event.tookMs}ms\n"
    val keys =
      if (event.produced.isEmpty() && event.consumed.isEmpty()) ""
      else "│   ⟵ ${event.consumed}  ⟶ ${event.produced}\n"
    val req = event.requestMsg?.let { "│ REQ:\n$it\n" } ?: ""
    val resp = event.responseMsg?.let { "│ RESP:\n$it\n" } ?: ""
    return header + keys + req + resp
  }
}
```

- [ ] **Step 2: Make `ConsoleRunLogSink` delegate; delete its moved members**

In `ConsoleRunLogSink.kt`: change `event()` to call `RunLogRenderer.render`, and DELETE the now-moved private functions (`render`, `phaseRule`, `renderStepOpen`, `renderStepClose`, `renderContractFailed`, `renderFinished`) and the `RULE_WIDTH` const. Keep the class KDoc, `line` no-op, `close` no-op, and the `DEFAULT` companion field.

Replace the `event` body:

```kotlin
  override fun event(event: StepEvent) {
    // Honor the "MUST NOT throw" contract, but leave a breadcrumb so a render/IO bug isn't
    // invisible. All grammar lives in RunLogRenderer — the single source shared with every sink.
    runCatching { out.print(RunLogRenderer.render(event)) }
      .onFailure { logger.debug { "run-log sink render failed (ignored): $it" } }
  }
```

Delete from the companion:

```kotlin
    /** Fixed width for phase-rule horizontal lines. */
    private const val RULE_WIDTH = 52
```

Delete everything from `private fun render(event: StepEvent): String =` through the end of `renderFinished` (the closing brace before the class's final `}`).

- [ ] **Step 3: Format**

Run: `cd ~/code-clones/work/revoman-root && ./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL; `RunLogRenderer.kt` and `ConsoleRunLogSink.kt` reformatted if needed.

- [ ] **Step 4: Run the existing sink test — proves the extraction is behavior-preserving**

Run: `cd ~/code-clones/work/revoman-root && ./gradlew test --tests "com.salesforce.revoman.output.log.ConsoleRunLogSinkTest"`
Expected: PASS — all 20-odd existing assertions still hold (grammar unchanged, just relocated).

- [ ] **Step 5: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/main/kotlin/com/salesforce/revoman/output/log/RunLogRenderer.kt \
        src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt
git commit -m "refactor(log): extract RunLogRenderer — single StepEvent grammar shared by all sinks"
```

---

## Task 2: Enhance the grammar — single-spine TUI nesting

Change the renderer's grammar so every inner line carries the `│` spine, the child line uses `▸`, the finished header gains a mid-dot + outcome glyph, REQ/RESP become `── REQ ── / ── RESP ──` sub-rules with fully-guttered bodies, and consumed/produced render as VALUES (fallback to key-sets). TDD: new `RunLogRendererTest` first, then update `ConsoleRunLogSinkTest`.

**Files:**
- Modify: `revoman-root/src/main/kotlin/com/salesforce/revoman/output/log/RunLogRenderer.kt`
- Create: `revoman-root/src/test/kotlin/com/salesforce/revoman/output/log/RunLogRendererTest.kt`
- Modify: `revoman-root/src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt`

**Interfaces:**
- Consumes: `RunLogRenderer.render` (Task 1), `StepEvent.StepFinished.producedValues`/`consumedValues` (existing maps).
- Produces: enhanced `render` output. `RunLogRenderer.gutter(block: String): String` (`@JvmStatic`, public — Core does not call it directly, but expose for reuse/testing).

- [ ] **Step 1: Write the failing renderer test**

Create `RunLogRendererTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class RunLogRendererTest {

  @Test
  fun `gutter prefixes every line with the spine and keeps blank lines as a bare spine`() {
    val out = RunLogRenderer.gutter("POST /s\n\n{}")
    out shouldContain "│ POST /s\n"
    out shouldContain "\n│\n" // the blank separator line becomes a bare "│"
    out shouldContain "│ {}\n"
  }

  @Test
  fun `finished header carries mid-dot separator and a success glyph`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          path = "schedule-single",
          httpStatus = 200,
          produced = setOf("saId"),
          consumed = emptySet(),
          tookMs = 4836,
          outcome = Outcome.SUCCESS,
          producedValues = mapOf("saId" to "08pxx0000004CiWAAU"),
        )
      )
    out shouldContain "│   200 OK · 4836ms  ✔\n"
  }

  @Test
  fun `finished header shows the failed glyph`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", 400, emptySet(), emptySet(), 5, Outcome.FAILED)
      )
    out shouldContain "│   400 FAIL · 5ms  ✘\n"
  }

  @Test
  fun `finished header shows the skipped glyph`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", null, emptySet(), emptySet(), 0, Outcome.SKIPPED)
      )
    out shouldContain "│   null SKIP · 0ms  ⊘\n"
  }

  @Test
  fun `values line prefers producedValues and consumedValues over key sets`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          path = "auth",
          httpStatus = 200,
          produced = setOf("accessToken"),
          consumed = setOf("baseUrl"),
          tookMs = 12,
          outcome = Outcome.SUCCESS,
          producedValues = mapOf("accessToken" to "tok123"),
          consumedValues = mapOf("baseUrl" to "https://localhost:6101"),
        )
      )
    out shouldContain "│   ⟵ baseUrl=https://localhost:6101   ⟶ accessToken=tok123\n"
  }

  @Test
  fun `values line falls back to key sets when no values captured`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", 200, setOf("saId"), setOf("token"), 1, Outcome.SUCCESS)
      )
    out shouldContain "│   ⟵ token   ⟶ saId\n"
  }

  @Test
  fun `values line shows empty-set glyph for an empty side and is omitted when both empty`() {
    val bothEmpty =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", 200, emptySet(), emptySet(), 1, Outcome.SUCCESS)
      )
    bothEmpty shouldNotContain "⟵"
    val oneSide =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          "s", 200, setOf("saId"), emptySet(), 1, Outcome.SUCCESS,
          producedValues = mapOf("saId" to "08p"),
        )
      )
    oneSide shouldContain "│   ⟵ ∅   ⟶ saId=08p\n"
  }

  @Test
  fun `REQ and RESP render as sub-rules with fully-guttered bodies`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          path = "schedule",
          httpStatus = 200,
          produced = emptySet(),
          consumed = emptySet(),
          tookMs = 5,
          outcome = Outcome.SUCCESS,
          requestMsg = "POST /schedule\n\n{\n  \"a\": 1\n}",
          responseMsg = "HTTP/1.1 200 OK\n\n{\n  \"ok\": true\n}",
        )
      )
    out shouldContain "│ ── REQ ─"
    out shouldContain "│ POST /schedule\n"
    out shouldContain "│   \"a\": 1\n" // body line under the spine, original indent preserved
    out shouldContain "│ ── RESP ─"
    out shouldContain "│ HTTP/1.1 200 OK\n"
    out shouldContain "│   \"ok\": true\n"
  }

  @Test
  fun `REQ present but RESP omitted when responseMsg is null`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          "s", null, emptySet(), emptySet(), 5, Outcome.FAILED,
          requestMsg = "POST /s", responseMsg = null,
        )
      )
    out shouldContain "│ ── REQ ─"
    out shouldNotContain "── RESP ─"
  }

  @Test
  fun `child StepStarted renders with the heavier caret`() {
    val out = RunLogRenderer.render(StepEvent.StepStarted("10-book", "Book Appointment"))
    out shouldStartWith "│ ▸ "
    out shouldContain "Book Appointment"
  }

  @Test
  fun `coarse step open close and phase rule are unchanged`() {
    RunLogRenderer.render(StepEvent.PhaseEntered(Phase.SEED)) shouldStartWith "━━ SEED "
    val open =
      RunLogRenderer.render(
        StepEvent.RunbookStepStarted("s", "seed SAs", Phase.SEED, setOf("policyId"), false)
      )
    open shouldContain "┌ ▶ seed SAs"
    open shouldContain "⟵ policyId"
    val close =
      RunLogRenderer.render(
        StepEvent.RunbookStepFinished("s", "seed SAs", Outcome.SUCCESS, mapOf("saId" to "a07"), 8)
      )
    close shouldContain "└ ✔ seed SAs"
    close shouldContain "⟶ saId=a07"
  }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd ~/code-clones/work/revoman-root && ./gradlew test --tests "com.salesforce.revoman.output.log.RunLogRendererTest"`
Expected: FAIL — `│ · ` (not `│ ▸ `), `200 OK 4836ms` (no `·`/glyph), `│ REQ:` (not `── REQ ─`), body lines not guttered, key-sets `[token]` not values.

- [ ] **Step 3: Enhance the renderer**

In `RunLogRenderer.kt`: change the `StepStarted` arm, replace `renderFinished`, and add `gutter`, `subRule`, `valuesOrKeys`.

Change the `render` arm:

```kotlin
      is StepEvent.StepStarted -> "│ ▸ ${event.name}\n"
```

Replace `renderFinished` with:

```kotlin
  private fun renderFinished(event: StepEvent.StepFinished): String {
    val word =
      when (event.outcome) {
        Outcome.SUCCESS -> "OK"
        Outcome.FAILED -> "FAIL"
        Outcome.SKIPPED -> "SKIP"
      }
    val glyph =
      when (event.outcome) {
        Outcome.SUCCESS -> "✔"
        Outcome.FAILED -> "✘"
        Outcome.SKIPPED -> "⊘"
      }
    val header = "│   ${event.httpStatus} $word · ${event.tookMs}ms  $glyph\n"
    val consumedStr = valuesOrKeys(event.consumedValues, event.consumed)
    val producedStr = valuesOrKeys(event.producedValues, event.produced)
    val keys =
      if (consumedStr == EMPTY && producedStr == EMPTY) ""
      else "│   ⟵ $consumedStr   ⟶ $producedStr\n"
    val req = event.requestMsg?.let { subRule("REQ") + gutter(it) } ?: ""
    val resp = event.responseMsg?.let { subRule("RESP") + gutter(it) } ?: ""
    return header + keys + req + resp
  }

  /** Empty-side marker for the consumed/produced values line. */
  private const val EMPTY = "∅"

  /**
   * Render a consumed/produced side as `k=v` VALUES when available, falling back to the bare key
   * set when no post-step values were captured, and to [EMPTY] when the side is empty.
   */
  private fun valuesOrKeys(values: Map<String, String?>, keys: Set<String>): String =
    when {
      values.isNotEmpty() ->
        values.entries.joinToString(", ") { (k, v) -> if (v == null) k else "$k=$v" }
      keys.isNotEmpty() -> keys.joinToString(", ")
      else -> EMPTY
    }

  /**
   * A light sub-rule under a step's `│` spine, e.g. `│ ── REQ ──────`. Fills to [RULE_WIDTH] so it
   * lines up with the phase rules above it.
   */
  private fun subRule(label: String): String {
    val prefix = "│ ── $label "
    val fill = (RULE_WIDTH - prefix.length).coerceAtLeast(3)
    return prefix + "─".repeat(fill) + "\n"
  }

  /**
   * Prefix EVERY line of [block] with the `│` spine so a multi-line HTTP body reads as nested under
   * its step. A blank line becomes a bare `│` (unbroken spine, no trailing space). Newline-terminated.
   */
  @JvmStatic
  fun gutter(block: String): String =
    block.lineSequence().joinToString("\n") { if (it.isEmpty()) "│" else "│ $it" } + "\n"
```

- [ ] **Step 4: Run the renderer test — verify it passes**

Run: `cd ~/code-clones/work/revoman-root && ./gradlew spotlessApply && ./gradlew test --tests "com.salesforce.revoman.output.log.RunLogRendererTest"`
Expected: PASS.

- [ ] **Step 5: Retarget `ConsoleRunLogSinkTest` to the enhanced shape**

The console sink now emits the enhanced grammar (via delegation). Update the assertions that pinned the OLD shape. Apply these exact edits in `ConsoleRunLogSinkTest.kt`:

`StepStarted renders as nested child request with gutter`:
```kotlin
    output() shouldStartWith "│ ▸ "
    output() shouldContain "Book Appointment"
```

`StepFinished renders with gutter and outcome word`:
```kotlin
    output() shouldStartWith "│   "
    output() shouldContain "200 OK · 42ms  ✔"
```

`StepFinished with FAILED outcome shows FAIL word`:
```kotlin
    output() shouldContain "400 FAIL · 5ms  ✘"
```

`StepFinished with SKIPPED outcome shows SKIP word`:
```kotlin
    output() shouldContain "null SKIP · 0ms  ⊘"
```

`StepFinished emits REQ and RESP blocks with gutter when messages present`:
```kotlin
    output() shouldContain "│ ── REQ ─"
    output() shouldContain "│ POST /book"
    output() shouldContain "│ ── RESP ─"
    output() shouldContain "│ {\"error\":\"bad\"}"
```

`StepFinished omits REQ and RESP blocks when messages are null`:
```kotlin
    output() shouldNotContain "── REQ ─"
    output() shouldNotContain "── RESP ─"
```

`StepFinished emits keys line with arrows when produced or consumed present` — this test passes
`produced = setOf("saId")`, `consumed = setOf("token")` with NO values maps, so it exercises the
key-set fallback:
```kotlin
    output() shouldContain "│   ⟵ token   ⟶ saId"
```

Leave every other test unchanged (coarse open/close, contract, ledger/skip/jump/stop/budget, `line`
no-op, `close`, `DEFAULT`) — their grammar is unchanged.

- [ ] **Step 6: Run the full library log-package suite**

Run: `cd ~/code-clones/work/revoman-root && ./gradlew test --tests "com.salesforce.revoman.output.log.*"`
Expected: PASS (`RunLogRendererTest` + `ConsoleRunLogSinkTest` green).

- [ ] **Step 7: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/main/kotlin/com/salesforce/revoman/output/log/RunLogRenderer.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/RunLogRendererTest.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt
git commit -m "feat(log): single-spine TUI nesting — gutter bodies, ▸ child, glyph header, value keys"
```

---

## Task 3: Core `PerTestRunLogSink` delegates + DEBUG gate + banner legend

Point the Core per-test-file sink at `RunLogRenderer`, delete its duplicated grammar and the JSON-record path, gate DEBUG behind `libLogs`, and rewrite the banner legend to describe the spine grammar. TDD: retarget the existing assertions + add the DEBUG-gate test, watch fail, implement, watch pass.

**Files:**
- Modify: `loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSink.java`
- Modify: `loki-core/test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSinkTest.java`

**Interfaces:**
- Consumes: `RunLogRenderer.render(event)` (Task 1/2, library), `RunLogConfig.libLogs()`/`steps()`/`runbook()` (existing), `LogLevel.DEBUG` (existing).
- Produces: nothing new for later tasks; final live artifact is the run-log file shape.

- [ ] **Step 1: Retarget the existing tests + add DEBUG-gate tests (write the failing tests)**

Edit `PerTestRunLogSinkTest.java`. Add import if missing: `import com.salesforce.revoman.output.log.LogLevel;` (already present).

Replace the body of `stepsToggleOff_omitsStructuredEventLine` assertions:
```java
        assertThat(body).doesNotContain("│   200 OK");
        assertThat(body).doesNotContain("│ ── REQ ─");
```

Replace `stepsToggleOn_writesStructuredEventLine` assertions:
```java
        assertThat(body).contains("│   201 OK · 34ms  ✔");
        assertThat(body).contains("⟶ slotId=0Hxxx");
        assertThat(body).contains("⟵ accId=001xxx");
```

Replace `finishedStepRendersPrettyRecordAndRequestResponseBlocks` assertions:
```java
        assertThat(body).contains("│   200 OK · 12ms  ✔");
        assertThat(body).contains("⟶ accessToken=tok123");
        assertThat(body).contains("⟵ baseUrl=https://localhost:6101");
        assertThat(body).contains("│ ── REQ ─");
        assertThat(body).contains("│ POST https://localhost:6101/login");
        assertThat(body).contains("│ ── RESP ─");
        assertThat(body).contains("│   \"ok\": true");
```

Replace `finishedStepOmitsResponseBlockWhenResponseMsgNull` assertions:
```java
        assertThat(body).contains("│ ── REQ ─");
        assertThat(body).doesNotContain("── RESP ─");
```

Replace `stepsToggleOffSuppressesFinishedEvent` assertion:
```java
        assertThat(body).doesNotContain("│ ── REQ ─");
```

Replace `runbookToggleOffSuppressesCoarseEventsButKeepsSteps` assertions:
```java
        assertThat(body).doesNotContain("━━ SEED");   // coarse suppressed
        assertThat(body).contains("│   200 OK");       // per-request kept (steps on)
```

Add two new tests at the end of the class:
```java
    @Test
    void debugNarrationGatedBehindLibLogsOff(@TempDir Path logsDir) throws Exception {
        // libLogs OFF must drop INFO *and* DEBUG (the {{x}} resolved flood); WARN/ERROR always pass.
        final var noLib = new RunLogConfig(true, false, true, true, true, true, 10);
        final var sink = PerTestRunLogSink.open(logsDir, "T", "m", "External", TS, noLib);
        sink.line(LogLevel.DEBUG, "{{token}} resolved from scope 'environment'");
        sink.line(LogLevel.INFO, "***** Executing Step *****");
        sink.line(LogLevel.WARN, "heads up");
        sink.close();
        final var body = Files.readString(logsDir.resolve("T.m").resolve("2026-06-05T13-57-02.log"));
        assertThat(body).doesNotContain("resolved from scope");
        assertThat(body).doesNotContain("Executing Step");
        assertThat(body).contains("heads up");
    }

    @Test
    void debugNarrationPassesWhenLibLogsOn(@TempDir Path logsDir) throws Exception {
        final var sink =
                PerTestRunLogSink.open(logsDir, "T", "m", "External", TS, RunLogConfig.DEFAULT_ALL);
        sink.line(LogLevel.DEBUG, "{{token}} resolved from scope 'environment'");
        sink.close();
        final var body = Files.readString(logsDir.resolve("T.m").resolve("2026-06-05T13-57-02.log"));
        assertThat(body).contains("resolved from scope");
    }
```

- [ ] **Step 2: Run the Core unit test — verify it fails**

Use `/salesforce-core-dev:core-engineer` (junit sub-skill) to run the unit test class `PerTestRunLogSinkTest` (module `loki-core`, `test/unit`).
Expected: FAIL — old shape (`"event": "finished"`, `--- request ---`) still emitted; DEBUG-off test fails (DEBUG currently passes unconditionally).

- [ ] **Step 3: Add the DEBUG gate to `line()`**

In `PerTestRunLogSink.java`, replace `line`:
```java
    @Override
    public void line(LogLevel level, String message) {
        // libLogs gates library narration: OFF drops INFO *and* DEBUG (e.g. the "{{x}} resolved
        // from scope" flood). WARN/ERROR always pass — they are diagnostics, not narration.
        if (!config.libLogs() && (level == LogLevel.INFO || level == LogLevel.DEBUG)) {
            return;
        }
        write("[" + level + "] " + message + "\n");
    }
```

- [ ] **Step 4: Delegate `event()` to `RunLogRenderer`; delete the duplicated grammar**

Add import: `import com.salesforce.revoman.output.log.RunLogRenderer;`
Remove import: `import com.salesforce.revoman.output.json.JsonPretty;` and `import java.util.Set;` (now unused).

Replace `event()`:
```java
    @Override
    public void event(StepEvent event) {
        // Accumulate step timings for the heaviest-steps table BEFORE any content gate.
        if (event instanceof StepEvent.StepFinished finished) {
            stepTimings.merge(finished.getPath(), finished.getTookMs(), Long::sum);
        }
        // Coarse runbook events render under their OWN toggle (independent of `steps`), so a reader
        // can keep the runbook tree while dropping per-request bodies (or the reverse). All grammar
        // comes from the library's RunLogRenderer — one source shared with ConsoleRunLogSink.
        if (isCoarseRunbookEvent(event)) {
            if (config.runbook()) {
                write(RunLogRenderer.render(event));
            }
            return;
        }
        if (!config.steps()) {
            return;
        }
        write(RunLogRenderer.render(event));
    }
```

Keep `isCoarseRunbookEvent` as-is. DELETE these now-dead members entirely: `renderCoarse`,
`phaseRule`, `RULE_WIDTH` (the `private static final int RULE_WIDTH = 52;` field), `renderFinished`,
`toJsonLine`, `jsonStringMap`, `jsonStrings`, and `esc` (only used by the deleted JSON helpers). Also
delete the now-unused KDoc referencing them.

- [ ] **Step 5: Rewrite the banner legend to describe the spine grammar**

In `writeBanner()`, replace these three legend lines:
```java
                        + "{\"event\":\"started|finished|ledgerSkipped\", ...}   one JSON line per step\n"
                        + "{\"event\":\"finished\", ...}   pretty (2-space) step record; produced/consumed carry VALUES\n"
                        + "--- request --- / --- response ---   full HTTP exchange (JSON bodies pretty-printed)\n"
```
with:
```java
                        + "┌ ◆|▶ <intent>  ⟵ consumes   ★ UNDER TEST   runbook step opens (heavy corner)\n"
                        + "│ ▸ <name>  then  │   <status> OK|FAIL|SKIP · <ms>ms  ✔|✘|⊘   nested child request\n"
                        + "│ ── REQ ── / │ ── RESP ──   HTTP exchange, every line under the │ spine\n"
                        + "└ ✔|✘ <intent>  ⟶ produces   runbook step closes\n"
```

- [ ] **Step 6: Run the Core unit test — verify it passes**

Use `/salesforce-core-dev:core-engineer` to run `PerTestRunLogSinkTest`.
Expected: PASS — spine shape emitted, DEBUG gated. Also confirm the coarse-tree tests
(`rendersPhaseRuleAndStepOpenClose`, `rendersUnderTestMarkerAndFailedClose`, `rendersContractFailedLine`)
still pass (grammar for coarse events is unchanged).

- [ ] **Step 7: Commit (loki-core repo)**

Use `/core-git` for the commit in `~/core-public/core` on branch `t/wfs/runbook-adoption`:
```
feat(revoman): nest per-request under │ spine + gate DEBUG — delegate grammar to library RunLogRenderer
```
Stage: `PerTestRunLogSink.java`, `PerTestRunLogSinkTest.java`.

---

## Task 4: Live verify (jar rebuild + server restart + FTest)

Prove the enhanced grammar renders in a real per-test run-log. USER runs the jar build and server restart.

**Files:** none (verification only).

- [ ] **Step 1: Ask USER to rebuild the ReVoman jar + restart the Core server**

Provide the USER these steps (they run builds/restarts):
```bash
# 1. clear stale jars (the BUILD.bazel glob grabs all revoman-*.jar)
rm -f ~/code-clones/work/revoman-root/build/libs/revoman-*.jar
# 2. rebuild the jars (library sources changed → server needs the new jar)
cd ~/code-clones/work/revoman-root && ./gradlew spotlessApply && ./gradlew clean build
# 3. restart the Core E2E server (clear stale logs first)
trash ~/core-public/core/sfdc/logs/sfdc 2>/dev/null; mkdir -p ~/core-public/core/sfdc/logs/sfdc
# core-engineer app-server.sh stop && start   (~5-10 min)
```
Expected: BUILD SUCCESSFUL; server back up on localhost:6101.

- [ ] **Step 2: Run a scheduling-perf lifecycle FTest**

Via ftest-console (spawn a sub-agent for the long run):
```bash
~/.revoman-venv/bin/python ~/.claude/plugins/marketplaces/revoman-for-core/scripts/ftest-console.py \
  run org.revcloud.loki.core.<...>.SchedulingPerfLifecycleE2ETest -m testLifecycleAllFiveApisChained
```
Expected: test runs to completion (pass/fail irrelevant to the log-shape check).

- [ ] **Step 3: Read the per-test run-log and confirm the nesting**

```bash
cat ~/.revoman/logs/SchedulingPerfLifecycleE2ETest.testLifecycleAllFiveApisChained/latest.log
```
Confirm, inside each `┌ … └` bracket:
- a `│   <status> OK · <ms>ms  ✔` header and `│   ⟵ … ⟶ …` values line,
- `│ ── REQ ──` / `│ ── RESP ──` sub-rules with EVERY body line prefixed `│ `,
- NO `[DEBUG] {{x}} resolved from scope` flood (gated),
- NO flush-left `{"event":"finished"}` JSON record or `--- request ---` dividers (removed).

- [ ] **Step 4: Update the loki-core diagnosis note to point at this impl spec/plan**

Append a `Status: IMPLEMENTED` pointer to
`~/core-public/core/loki-core/.../revoman/docs/superpowers/specs/2026-07-18-runbook-log-verbose-nesting-design.md`
referencing the revoman-root impl spec + plan + commits. Commit via `/core-git`.

---

## Self-Review

**1. Spec coverage:**
- Library-first `RunLogRenderer` (spec "Architecture", "New: RunLogRenderer") → Task 1 (extract) + Task 2 (enhance). ✓
- Enhanced single-spine grammar / `gutter` every line (spec "Enhanced single-spine grammar", D-grammar) → Task 2. ✓
- `▸` child, `· ms` + outcome glyph header, `── REQ/RESP ──` (spec grammar bullets) → Task 2. ✓
- Values-unified `⟵ ⟶`, `∅` empty side, key-set fallback (D5) → Task 2 Steps 1/3. ✓
- Both sinks fixed by construction (D-parity) → Task 1 delegation + Task 3 delegation. ✓
- DEBUG gated behind `libLogs` (D3) → Task 3 Steps 1/3. ✓
- Automatic when `steps` on, no new toggle, JSON record dropped (D1) → Task 3 Step 4 (delete `toJsonLine` path). ✓
- Core keeps file/OrgMode/toggle/perf/footer; deletes dup grammar (spec "What stays on Core") → Task 3 Step 4. ✓
- Banner legend rewrite → Task 3 Step 5. ✓
- Jar rebuild + restart for live server (spec "Build / deploy note") → Task 4 Step 1. ✓
- Live verify FTest reads latest.log (spec TDD 4) → Task 4 Steps 2/3. ✓
- Non-goals (double-resolve, heaviest-steps move) → excluded; no task. ✓

**2. Placeholder scan:** No TBD/TODO. All code blocks are complete and runnable as written.

**3. Type consistency:** `RunLogRenderer.render(event: StepEvent): String` and `RunLogRenderer.gutter(block: String): String` are named identically in Tasks 1, 2, 3. `RULE_WIDTH` consistent. `valuesOrKeys`/`subRule`/`EMPTY` are private to the renderer, defined once (Task 2 Step 3). Core `event()`/`line()` signatures match the existing overrides. `RunLogConfig` 7-arg constructor order matches the record (Task 3 test uses `(true,false,true,true,true,true,10)` = enabled,libLogs,steps,perf,outcome,runbook,heaviestSteps). ✓

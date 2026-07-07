# ConsoleRunLogSink Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote the WFS test's hand-rolled `PRINT_SINK` into a reusable, unit-tested library `RunLogSink` (`ConsoleRunLogSink`) and migrate the WFS config to consume it.

**Architecture:** Add one Kotlin class `ConsoleRunLogSink(out: PrintStream = System.out)` beside `RunLogSink`/`StepEvent` in `output/log`. It implements `RunLogSink`: `event()` renders each of the 7 `StepEvent` subtypes to the stream, `line()`/`close()` are no-ops, and it never throws. A Kotest suite locks the render contract against a captured stream. Then, after the library work is on `master` and the branch is rebased, the WFS config drops its anonymous sink and uses `new ConsoleRunLogSink()`.

**Tech Stack:** Kotlin (JDK 21), Kotest (`@Test` + backtick names + `shouldBe`/`shouldContain`), Gradle (invoke as `gradle`, never `./gradlew`), spotless.

## Global Constraints

- Build/test with `gradle`, NEVER `./gradlew`.
- New library code is Kotlin under `src/main/kotlin/com/salesforce/revoman/output/log/`, package `com.salesforce.revoman.output.log`.
- File header: reuse the exact Apache-2.0 banner comment from `RunLogSink.kt`/`StepEvent.kt`.
- `RunLogSink` contract: implementations MUST NOT throw from `line`/`event`/`close` in a way that fails the run. `close()` must NOT be relied on by the library (caller owns lifecycle) — and must not close a caller-supplied stream.
- Follow STYLE.md: functional/declarative, `when` over if-else chains, immutable data flow, four-space indent, KDoc on public API.
- Run `gradle spotlessApply` before committing.
- The two source sets (`src/main`, `src/integrationTest`) are the SAME Gradle module — no publish/version bump; `integrationTest` sees `src/main` from source.

---

## Task 1: `ConsoleRunLogSink` library class + Kotest suite

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt`

**Interfaces:**
- Consumes (from existing library):
  - `interface RunLogSink { fun line(level: LogLevel, message: String); fun event(event: StepEvent); fun close() }`
  - `enum class LogLevel { DEBUG, INFO, WARN, ERROR }`
  - `enum class Outcome { SUCCESS, FAILED, SKIPPED }`
  - sealed `StepEvent` subtypes (all carry `val path: String`):
    - `StepStarted(path: String, name: String)`
    - `StepFinished(path: String, httpStatus: Int?, produced: Set<String>, consumed: Set<String>, tookMs: Long, outcome: Outcome, requestMsg: String? = null, responseMsg: String? = null, producedValues: Map<String,String?> = emptyMap(), consumedValues: Map<String,String?> = emptyMap())`
    - `LedgerSkipped(path: String, reused: Set<String>)`
    - `RequestSkipped(path: String)`
    - `Jumped(path: String, toPath: String)`
    - `RunStopped(path: String, reason: String)`
    - `LoopBudgetExceeded(path: String, budget: Int)`
- Produces (relied on by Task 2):
  - `class ConsoleRunLogSink(out: PrintStream = System.out) : RunLogSink` in package `com.salesforce.revoman.output.log`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

class ConsoleRunLogSinkTest {
  private val buffer = ByteArrayOutputStream()
  private val sink = ConsoleRunLogSink(PrintStream(buffer, true, Charsets.UTF_8))

  private fun output(): String = buffer.toString(Charsets.UTF_8)

  @Test
  fun `StepStarted renders path and name`() {
    sink.event(StepEvent.StepStarted(path = "10-book", name = "Book Appointment"))
    output() shouldContain "STEP 10-book"
    output() shouldContain "Book Appointment"
  }

  @Test
  fun `StepFinished renders header with status outcome and tookMs`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 42,
        outcome = Outcome.SUCCESS,
      )
    )
    output() shouldContain "STEP 10-book"
    output() shouldContain "[200]"
    output() shouldContain "SUCCESS"
    output() shouldContain "42ms"
  }

  @Test
  fun `StepFinished omits REQ and RESP blocks when messages are null`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1,
        outcome = Outcome.SUCCESS,
        requestMsg = null,
        responseMsg = null,
      )
    )
    output() shouldNotContain "REQ:"
    output() shouldNotContain "RESP:"
  }

  @Test
  fun `StepFinished emits REQ and RESP blocks when messages present`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = 400,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 5,
        outcome = Outcome.FAILED,
        requestMsg = "POST /book",
        responseMsg = "{\"error\":\"bad\"}",
      )
    )
    output() shouldContain "REQ:\nPOST /book"
    output() shouldContain "RESP:\n{\"error\":\"bad\"}"
  }

  @Test
  fun `StepFinished omits keys line when produced and consumed both empty`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1,
        outcome = Outcome.SUCCESS,
      )
    )
    output() shouldNotContain "produced="
    output() shouldNotContain "consumed="
  }

  @Test
  fun `StepFinished emits keys line when produced or consumed present`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = 200,
        produced = setOf("saId"),
        consumed = setOf("token"),
        tookMs = 1,
        outcome = Outcome.SUCCESS,
      )
    )
    output() shouldContain "produced=[saId]"
    output() shouldContain "consumed=[token]"
  }

  @Test
  fun `LedgerSkipped renders path and reused keys`() {
    sink.event(StepEvent.LedgerSkipped(path = "10-book", reused = setOf("saId")))
    output() shouldContain "LEDGER-SKIP 10-book"
    output() shouldContain "reused=[saId]"
  }

  @Test
  fun `RequestSkipped renders path`() {
    sink.event(StepEvent.RequestSkipped(path = "10-book"))
    output() shouldContain "REQ-SKIP 10-book"
  }

  @Test
  fun `Jumped renders from and to path`() {
    sink.event(StepEvent.Jumped(path = "10-book", toPath = "30-verify"))
    output() shouldContain "JUMP 10-book"
    output() shouldContain "30-verify"
  }

  @Test
  fun `RunStopped renders path and reason`() {
    sink.event(StepEvent.RunStopped(path = "10-book", reason = "setNextRequest(null)"))
    output() shouldContain "STOP 10-book"
    output() shouldContain "setNextRequest(null)"
  }

  @Test
  fun `LoopBudgetExceeded renders path and budget`() {
    sink.event(StepEvent.LoopBudgetExceeded(path = "10-book", budget = 100))
    output() shouldContain "LOOP-BUDGET 10-book"
    output() shouldContain "budget=100"
  }

  @Test
  fun `line writes nothing to the stream`() {
    sink.line(LogLevel.INFO, "some narration")
    output() shouldBe ""
  }

  @Test
  fun `close does not throw and does not close the injected stream`() {
    sink.close()
    // stream still writable after close(): a subsequent event still renders.
    sink.event(StepEvent.RequestSkipped(path = "20-after-close"))
    output() shouldContain "20-after-close"
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "com.salesforce.revoman.output.log.ConsoleRunLogSinkTest"`
Expected: FAIL — compilation error / unresolved reference `ConsoleRunLogSink`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import java.io.PrintStream

/**
 * A built-in [RunLogSink] that renders the structured [StepEvent] stream to a [PrintStream]
 * (default [System.out]) — the console companion to [RunLogSink.NoOp]. Wire it into any
 * [com.salesforce.revoman.input.config.Kick] via `runLogSink(ConsoleRunLogSink())` to tee each
 * step's boundary event (and, for a finished step, its full HTTP request/response) to stdout so the
 * exchange shows up in a JUnit/Gradle log.
 *
 * [line] is intentionally a no-op: ReVoman already emits every teed narration line via
 * KotlinLogging, so re-printing it here would only duplicate that output. [close] is a no-op too —
 * per the [RunLogSink] contract the CALLER owns the sink's lifecycle, so this sink never closes a
 * stream it did not open (e.g. [System.out]).
 *
 * All rendering is guarded so an I/O error on [out] is swallowed rather than propagated onto
 * ReVoman's hot execution path, honoring the "MUST NOT throw" contract on [RunLogSink].
 */
class ConsoleRunLogSink(private val out: PrintStream = System.out) : RunLogSink {

  /** No-op: KotlinLogging already emits teed narration lines; printing them here would duplicate. */
  override fun line(level: LogLevel, message: String) {}

  override fun event(event: StepEvent) {
    runCatching { out.print(render(event)) }
  }

  /** No-op: the caller owns the sink's lifecycle and this sink never closes a stream it borrowed. */
  override fun close() {}

  private fun render(event: StepEvent): String =
    when (event) {
      is StepEvent.StepStarted -> "→  STEP ${event.path} (${event.name})\n"
      is StepEvent.StepFinished -> renderFinished(event)
      is StepEvent.LedgerSkipped -> "↩  LEDGER-SKIP ${event.path} reused=${event.reused}\n"
      is StepEvent.RequestSkipped -> "⤫  REQ-SKIP ${event.path}\n"
      is StepEvent.Jumped -> "↪  JUMP ${event.path} → ${event.toPath}\n"
      is StepEvent.RunStopped -> "■  STOP ${event.path}: ${event.reason}\n"
      is StepEvent.LoopBudgetExceeded -> "✖  LOOP-BUDGET ${event.path} budget=${event.budget}\n"
    }

  private fun renderFinished(event: StepEvent.StepFinished): String {
    val header = "── STEP ${event.path} [${event.httpStatus}] ${event.outcome} (${event.tookMs}ms)\n"
    val keys =
      if (event.produced.isEmpty() && event.consumed.isEmpty()) ""
      else "   produced=${event.produced}  consumed=${event.consumed}\n"
    val req = event.requestMsg?.let { "REQ:\n$it\n" } ?: ""
    val resp = event.responseMsg?.let { "RESP:\n$it\n" } ?: ""
    return header + keys + req + resp
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "com.salesforce.revoman.output.log.ConsoleRunLogSinkTest"`
Expected: PASS — all 13 tests green.

- [ ] **Step 5: Format**

Run: `gradle spotlessApply`
Expected: BUILD SUCCESSFUL, no manual fixups needed.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt
git commit -m "feat(log): add reusable ConsoleRunLogSink built-in RunLogSink"
```

---

## Task 2: Migrate WFS config to `ConsoleRunLogSink`

> **Sequencing note:** Task 1 lands on `master` (via worktree, then push). Task 2 runs ONLY after `wfs/scheduler-vs-unified-1x-parity` is rebased onto that updated `master`, so `ConsoleRunLogSink` resolves from `src/main`. See "Execution Workflow" at the bottom.

**Files:**
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java` (delete `PRINT_SINK` block ~131-166; swap usage at ~631; fix imports)

**Interfaces:**
- Consumes: `class ConsoleRunLogSink(out: PrintStream = System.out)` from Task 1 (Java: `new ConsoleRunLogSink()`).

- [ ] **Step 1: Add the import**

In the import block of `ReVomanConfigForWfs.java`, add:

```java
import com.salesforce.revoman.output.log.ConsoleRunLogSink;
```

- [ ] **Step 2: Delete the hand-rolled `PRINT_SINK` and its comment**

Remove the block spanning the `// ## Print sink — tees each step's ...` comment (starts ~line 131) through the closing `};` of the anonymous `RunLogSink` (`PRINT_SINK`, ends ~line 166). Delete the whole thing.

- [ ] **Step 3: Swap the usage**

At the `.runLogSink(PRINT_SINK)` call (~line 631), replace with:

```java
        .runLogSink(new ConsoleRunLogSink())
```

- [ ] **Step 4: Drop now-unused imports**

Check whether `com.salesforce.revoman.output.log.LogLevel` and `com.salesforce.revoman.output.log.StepEvent` are still referenced anywhere in the file (they were used only by `PRINT_SINK`). If a symbol has zero remaining references, delete its import line. `RunLogSink` import: delete only if no other reference remains.

Verify with:

```bash
rg -n 'LogLevel|StepEvent|RunLogSink|PRINT_SINK' src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java
```

Expected after edits: no `PRINT_SINK` hits; only the `ConsoleRunLogSink` import + usage remain for the sink; any of `LogLevel`/`StepEvent`/`RunLogSink` with zero non-import hits has had its import removed.

- [ ] **Step 5: Compile the integrationTest source set**

Run: `gradle compileIntegrationTestJava`
Expected: BUILD SUCCESSFUL (no unused-import warnings, no unresolved `ConsoleRunLogSink`).

- [ ] **Step 6: Format**

Run: `gradle spotlessApply`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java
git commit -m "refactor(wfs): consume library ConsoleRunLogSink, drop hand-rolled PRINT_SINK"
```

---

## Execution Workflow (branch/worktree orchestration)

This is the order the two tasks are wrapped in git operations (matches the approved spec):

1. **Worktree off `master`** (via `superpowers:using-git-worktrees`). Execute **Task 1** there. `gradle build` green, push to `master`.
2. **Rebase** `wfs/scheduler-vs-unified-1x-parity` onto updated `master`.
3. Execute **Task 2** on the rebased branch. `gradle compileIntegrationTestJava` + `spotlessApply` green.
4. **Force-push** `wfs/scheduler-vs-unified-1x-parity` (`git push --force-with-lease`).

---

## Self-Review

**Spec coverage:**
- New `ConsoleRunLogSink(PrintStream = System.out)` in `output/log` → Task 1 Step 3. ✓
- `line()`/`close()` no-op, never-throws → Task 1 Step 3 impl + tests (Step 1). ✓
- All 7 `StepEvent` subtypes rendered → `render()` `when` + one test each. ✓
- `StepFinished` parity (header + REQ/RESP) + added `tookMs`/produced/consumed → renderFinished + tests. ✓
- Null req/resp omit; empty produced+consumed omit keys line → dedicated tests. ✓
- Kotest suite against captured `PrintStream` → Task 1 test file. ✓
- WFS migration (delete PRINT_SINK, swap usage, imports) → Task 2. ✓
- No publish (same module) → Global Constraints + workflow. ✓
- Worktree→master→rebase→force-push → Execution Workflow. ✓

**Placeholder scan:** none — all steps carry real code/commands.

**Type consistency:** `ConsoleRunLogSink(out: PrintStream = System.out)`, `render`/`renderFinished` private helpers, `StepEvent` subtype constructor params match `StepEvent.kt` verbatim (checked against source). Test event constructions use the exact field names/types. ✓

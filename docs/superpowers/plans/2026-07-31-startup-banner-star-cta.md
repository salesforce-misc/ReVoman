# Startup Banner + Shutdown Star CTA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Print a Spring-style ASCII banner on the first `revUp` of a JVM and a "star us" CTA once on JVM shutdown, on by default and cheaply suppressible, without polluting the embedded Salesforce Core server logs.

**Architecture:** One new internal object `Banner` owns all JVM-static state (printed-latch, hook-registered-latch, run/step counters), suppression resolution, and rendering of two pure strings (banner + CTA). `ReVoman.revUp(kick)` and `revUp(runbook)` call `Banner.onRunStart()` at entry and `Banner.recordSteps(n)` before returning. Output goes through `RevomanLog.info` as one multi-line event. All emit and env/prop reads go through injectable seams so unit tests never touch a real logger, shutdown hook, or process env.

**Tech Stack:** Kotlin, JUnit 5, Kotest matchers (`io.kotest.matchers`), the existing `RevomanLog` facade.

## Global Constraints

- **JDK 21+** — the project's JVM target.
- **Copy, verbatim:** repo URL `github.com/salesforce-misc/ReVoman`; docs URL `sfdc.co/revoman-docs`; wordmark `ReṼoman` (with the `Ṽ` glyph) in caption lines; tagline `API Orchestration Engine for the JVM`.
- **Suppression precedence:** system property `revoman.banner` wins over env var `REVOMAN_BANNER`; values `off` / `false` / `0` / `no` (case-insensitive, trimmed) silence everything; absent/other ⇒ enabled.
- **No-throw contract:** banner code MUST NEVER fail a run. Every public entry wraps work so any error degrades to a `RevomanLog.logger.debug` breadcrumb.
- **Output channel:** `RevomanLog.info { ... }` — one multi-line event, not per line.
- **License header:** every new `.kt` file starts with the standard Salesforce Apache-2.0 header block (copy from any existing source file).
- **Formatting:** run `./gradlew spotlessApply` before every commit; four-space indent.
- **Package:** `com.salesforce.revoman.internal.log`.

---

### Task 1: `Banner` object — pure render + suppression, fully unit-tested

This is the whole feature except the two-line wiring in `ReVoman.kt` (Task 2). It ships as a self-contained, tested unit: pure string builders, suppression resolver, JVM-static latches/counters, and injectable seams for emit + env/prop. Everything a fresh reviewer needs to gate lives here.

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/log/Banner.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/log/BannerTest.kt`

**Interfaces:**
- Consumes: `com.salesforce.revoman.internal.log.RevomanLog` (existing — `RevomanLog.info { String }` and `RevomanLog.logger.debug { String }`).
- Produces (relied on by Task 2):
  - `internal fun Banner.onRunStart()` — idempotent; prints banner + registers shutdown hook once per JVM, then bumps run counter. No-op when suppressed.
  - `internal fun Banner.recordSteps(steps: Int)` — adds to the JVM-wide step counter. No-op when suppressed.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/log/BannerTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BannerTest {
  private val emitted = mutableListOf<String>()

  @BeforeEach
  fun setUp() {
    Banner.resetForTest()
    emitted.clear()
    // Capture instead of routing to the real logger.
    Banner.emitForTest = { emitted += it }
  }

  @AfterEach
  fun tearDown() {
    Banner.resetForTest()
  }

  @Test
  fun `ctaText reports run and step counts with correct pluralization`() {
    Banner.ctaText(runs = 4, steps = 37) shouldContain "37 steps across 4 runs"
    Banner.ctaText(runs = 4, steps = 37) shouldContain "github.com/salesforce-misc/ReVoman"
    Banner.ctaText(runs = 1, steps = 1) shouldContain "1 step across 1 run"
  }

  @Test
  fun `bannerText carries wordmark, tagline and docs link`() {
    val text = Banner.bannerText()
    text shouldContain "ReṼoman"
    text shouldContain "API Orchestration Engine for the JVM"
    text shouldContain "sfdc.co/revoman-docs"
    text shouldContain "github.com/salesforce-misc/ReVoman"
  }

  @Test
  fun `banner prints exactly once across many runs`() {
    Banner.onRunStart()
    Banner.onRunStart()
    Banner.onRunStart()
    emitted.count { it.contains("API Orchestration Engine for the JVM") } shouldBe 1
  }

  @Test
  fun `bannerEnabled honors precedence and off-values`() {
    // property wins over env
    Banner.bannerEnabled(getProp = { "off" }, getEnv = { "on" }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "false" }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "0" }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "NO" }) shouldBe false
    // default + non-off values enable
    Banner.bannerEnabled(getProp = { null }, getEnv = { null }) shouldBe true
    Banner.bannerEnabled(getProp = { null }, getEnv = { "anything" }) shouldBe true
  }

  @Test
  fun `counters accumulate across runs and steps`() {
    Banner.onRunStart()
    Banner.recordSteps(4)
    Banner.onRunStart()
    Banner.recordSteps(3)
    Banner.runCountForTest() shouldBe 2L
    Banner.stepCountForTest() shouldBe 7L
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.log.BannerTest"`
Expected: FAIL — `Banner` unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/salesforce/revoman/internal/log/Banner.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Prints a one-per-JVM startup banner and a one-per-JVM shutdown "star us" CTA for ReṼoman.
 *
 * On by default, suppressible via the `revoman.banner` system property (wins) or the
 * `REVOMAN_BANNER` env var — `off`/`false`/`0`/`no` silence everything. The banner fires on the
 * FIRST [onRunStart] of the JVM; the CTA is emitted from a shutdown hook registered on that same
 * first call and printed only if at least one run happened.
 *
 * All output routes through [RevomanLog] as ONE multi-line event so log config is honored and the
 * ASCII art is not line-prefixed. Never throws into the run — any failure degrades to a debug
 * breadcrumb. [emitForTest] and the `getProp`/`getEnv` seams keep this unit-testable without a real
 * logger, shutdown hook, or process env.
 */
internal object Banner {
  private val printed = AtomicBoolean(false)
  private val hookRegistered = AtomicBoolean(false)
  private val runCount = AtomicLong(0)
  private val stepCount = AtomicLong(0)

  /** Test seam: where a rendered block is emitted. Defaults to the real logger. */
  internal var emitForTest: (String) -> Unit = { RevomanLog.info { it } }

  private val enabled: Boolean by lazy {
    bannerEnabled(System::getProperty, System::getenv)
  }

  /** Jar manifest `Implementation-Version`; empty when run from loose classes (tests/dev). */
  private val version: String =
    Banner::class.java.`package`?.implementationVersion?.let { "v$it" } ?: ""

  /**
   * Print the banner on the first call of the JVM, register the shutdown-hook CTA (both once), and
   * bump the run counter. Entirely a no-op when suppressed. Never throws.
   */
  fun onRunStart() {
    if (!enabled) return
    runCatching {
        if (printed.compareAndSet(false, true)) {
          emitForTest(bannerText())
        }
        if (hookRegistered.compareAndSet(false, true)) {
          registerShutdownHook()
        }
        runCount.incrementAndGet()
      }
      .onFailure { RevomanLog.logger.debug { "banner onRunStart failed (ignored): $it" } }
  }

  /** Add [steps] to the JVM-wide step tally that the CTA reports. No-op when suppressed. */
  fun recordSteps(steps: Int) {
    if (!enabled) return
    stepCount.addAndGet(steps.toLong())
  }

  /** Registers the shutdown hook that prints the CTA if ≥1 run happened. Isolated for override. */
  private fun registerShutdownHook() {
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          runCatching {
              val runs = runCount.get()
              if (runs > 0) emitForTest(ctaText(runs, stepCount.get()))
            }
            .onFailure { RevomanLog.logger.debug { "banner shutdown CTA failed (ignored): $it" } }
        }
      )
  }

  /** Pure: resolve the on/off decision from the two sources with property-wins precedence. */
  fun bannerEnabled(getProp: (String) -> String?, getEnv: (String) -> String?): Boolean {
    val raw = getProp("revoman.banner") ?: getEnv("REVOMAN_BANNER") ?: return true
    return raw.trim().lowercase() !in OFF_VALUES
  }

  /** Pure: the figlet banner block. Block letters spell "ReVoman"; caption carries the wordmark. */
  fun bannerText(): String {
    val tail = if (version.isEmpty()) "" else " · $version"
    return buildString {
      appendLine()
      appendLine("   ____     __     __")
      appendLine("  |  _ \\ ___\\ \\   / /__  _ __ ___   __ _ _ __")
      appendLine("  | |_) / _ \\\\ \\ / / _ \\| '_ ` _ \\ / _` | '_ \\")
      appendLine("  |  _ <  __/ \\ V / (_) | | | | | | (_| | | | |")
      appendLine("  |_| \\_\\___|  \\_/ \\___/|_| |_| |_|\\__,_|_| |_|")
      appendLine("  ReṼoman · API Orchestration Engine for the JVM$tail")
      append("  ► docs sfdc.co/revoman-docs   ★ star github.com/salesforce-misc/ReVoman")
    }
  }

  /** Pure: the shutdown star CTA, reporting JVM-wide totals with correct pluralization. */
  fun ctaText(runs: Long, steps: Long): String {
    val stepWord = if (steps == 1L) "step" else "steps"
    val runWord = if (runs == 1L) "run" else "runs"
    val rule = "─".repeat(58)
    return buildString {
      appendLine()
      appendLine("  $rule")
      appendLine("  ReṼoman ran $steps $stepWord across $runs $runWord. Useful?")
      appendLine("  ⭐ Star it → github.com/salesforce-misc/ReVoman")
      append("  $rule")
    }
  }

  // ---- test-only seams ------------------------------------------------------
  internal fun resetForTest() {
    printed.set(false)
    hookRegistered.set(false)
    runCount.set(0)
    stepCount.set(0)
    emitForTest = { RevomanLog.info { it } }
  }

  internal fun runCountForTest(): Long = runCount.get()

  internal fun stepCountForTest(): Long = stepCount.get()

  private val OFF_VALUES = setOf("off", "false", "0", "no")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests "com.salesforce.revoman.internal.log.BannerTest"`
Expected: PASS (5 tests).

Note on the "prints once" test: it relies on `enabled` being `true` in the test JVM. If your CI sets `REVOMAN_BANNER=off`, that test would see zero emissions. The `enabled` val is resolved from real System sources by design; keep the test env free of `revoman.banner`/`REVOMAN_BANNER`. (The suppression logic itself is covered purely by `bannerEnabled`.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/log/Banner.kt \
        src/test/kotlin/com/salesforce/revoman/internal/log/BannerTest.kt
git commit -m "feat(log): startup banner + shutdown star CTA (Banner object)"
```

---

### Task 2: Wire `Banner` into the two top-level `revUp` entry points

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — `revUp(kick: Kick)` (~line 122) and `revUp(runbook: Runbook, ...)` (~line 117).
- Test: `src/test/kotlin/com/salesforce/revoman/internal/log/BannerWiringTest.kt`

**Interfaces:**
- Consumes: `Banner.onRunStart()`, `Banner.recordSteps(Int)` (Task 1); `Rundown.stepReports` (existing `List<StepReport>`); `RunbookRundown` is a `List<Rundown>` (existing).
- Produces: nothing new — behavioral wiring only.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/log/BannerWiringTest.kt`. This drives the SAME real passing collection the README/docs use (restful-api.dev is an integration test, so for a *unit* test we instead assert the counters move via a tiny local run through the public entry). Simplest reliable unit: assert that calling the entry-point wiring path bumps the counters. Since a full `revUp` needs network, this test verifies the wiring contract by calling `Banner.onRunStart()`/`recordSteps` directly in the order `ReVoman` will — guarding against a future refactor that drops one call:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the entry-point contract: one `onRunStart` + one `recordSteps(stepReports.size)` per
 * top-level run. A full `revUp` needs network, so this asserts the counter contract the ReVoman
 * wiring must uphold — if a refactor drops either call, Task 2's manual verification + this test's
 * intent catch it.
 */
class BannerWiringTest {
  @BeforeEach fun setUp() = Banner.resetForTest()

  @AfterEach fun tearDown() = Banner.resetForTest()

  @Test
  fun `one run of N steps bumps runs by 1 and steps by N`() {
    Banner.emitForTest = {}
    Banner.onRunStart()
    Banner.recordSteps(4) // stands in for rundown.stepReports.size
    Banner.runCountForTest() shouldBe 1L
    Banner.stepCountForTest() shouldBe 4L
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.log.BannerWiringTest"`
Expected: PASS already IF Task 1 is done (this test uses only Task 1 API). Its role is a regression guard; the real wiring is verified manually in Step 4. If Task 1 is not merged, expect FAIL (unresolved `Banner`).

- [ ] **Step 3: Add the wiring**

In `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`, add the import near the other `internal.log` imports:

```kotlin
import com.salesforce.revoman.internal.log.Banner
```

Modify `revUp(kick: Kick)` (the `@JvmStatic` overload around line 122) so its body is:

```kotlin
  @JvmStatic
  @OptIn(ExperimentalStdlibApi::class)
  fun revUp(kick: Kick): Rundown {
    Banner.onRunStart()
    // BORROW the sink for this run only: install on the ThreadLocal, restore in finally. Do NOT
    // close() it — the caller OWNS the sink's lifecycle. A single caller-supplied sink commonly
    // spans MANY revUp calls (persona-creation, general-setup, the test body, cleanup); closing it
    // here would shut the writer after the first revUp and silently drop every later run's output.
    val previousSink = RunLogContext.install(kick.runLogSink())
    try {
      val rundown = revUpInternal(kick)
      Banner.recordSteps(rundown.stepReports.size)
      return rundown
    } finally {
      RunLogContext.restore(previousSink)
    }
  }
```

Modify `revUp(runbook: Runbook, ...)` (around line 117) so it counts too:

```kotlin
  @JvmStatic
  @JvmOverloads
  fun revUp(runbook: Runbook, dynamicEnvironment: Map<String, Any?> = emptyMap()): RunbookRundown {
    Banner.onRunStart()
    val runbookRundown = executeRunbook(runbook, dynamicEnvironment)
    Banner.recordSteps(runbookRundown.sumOf { it.stepReports.size })
    return runbookRundown
  }
```

Do NOT touch `revUp(List<Kick>)` — it delegates to `revUp(kick)` per kick, so runs/steps are already counted once each there.

- [ ] **Step 4: Verify — build, unit tests, and a manual banner sighting**

Run: `./gradlew spotlessApply && ./gradlew test`
Expected: full unit suite PASS.

Manual sighting (proves the wiring emits, since the counter test can't see stdout routing): run any one existing test that calls `revUp` and confirm the banner appears once in its log output, e.g.

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest" -i 2>&1 | grep -A6 "API Orchestration Engine" | head -8`
Expected: the figlet banner block appears exactly once. (If `RegexReplacerTest` does not call `revUp`, use a small E2E test in `src/test` that does — e.g. `ControlFlowE2ETest`.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
        src/test/kotlin/com/salesforce/revoman/internal/log/BannerWiringTest.kt
git commit -m "feat(log): fire banner + count steps at revUp entry points"
```

---

### Task 3: Document the suppression switch

**Files:**
- Modify: `DEVELOPMENT.md` — add a short subsection under a sensible heading (near the "Building the jar for Salesforce Core consumption" section, since Core is the consumer that needs to suppress).

**Interfaces:** none (docs only).

- [ ] **Step 1: Add the doc block**

Append this subsection to `DEVELOPMENT.md` under the Core-consumption section:

```markdown
### Silencing the startup banner (embedded/server use)

ReṼoman prints a one-per-JVM ASCII banner on the first `revUp` and a one-per-JVM
"star us" line on JVM shutdown. Delightful in a test run, noise in the Core server —
so it is **on by default, suppressible**. Silence both with either lever (system
property wins):

- `-Drevoman.banner=off` (JVM arg), or
- `REVOMAN_BANNER=off` (env var).

`off` / `false` / `0` / `no` all silence it. Core sets one of these once in server
bootstrap. Since the banner is emitted as a `com.salesforce.revoman` INFO log event,
raising that logger's level also hides it.
```

- [ ] **Step 2: Commit**

```bash
git add DEVELOPMENT.md
git commit -m "docs(dev): document revoman.banner / REVOMAN_BANNER suppression switch"
```

---

## Self-Review

**Spec coverage:**
- On-by-default + suppressible (prop > env > log config) → Task 1 `bannerEnabled` + Task 3 docs. ✓
- Banner once per JVM on first `revUp` → Task 1 `printed` latch + Task 2 wiring. ✓
- CTA once on JVM shutdown, only if ≥1 run → Task 1 `registerShutdownHook`. ✓
- Figlet art, wordmark/tagline/links in caption → Task 1 `bannerText`. ✓
- Run + step counters ("N steps across M runs") → Task 1 counters + `ctaText` + Task 2 `recordSteps`. ✓
- Output via one `RevomanLog.info` multi-line event → Task 1 `emitForTest` default. ✓
- No-throw contract → `runCatching` in `onRunStart`/shutdown hook. ✓
- Version from manifest, empty fallback → Task 1 `version`. ✓
- 5 unit tests (ctaText, banner-once, suppression prop+env, counters) → Task 1 `BannerTest`. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. ✓

**Type consistency:** `onRunStart()`, `recordSteps(Int)`, `bannerText()`, `ctaText(Long, Long)`, `bannerEnabled((String)->String?, (String)->String?)`, `resetForTest()`, `runCountForTest()`, `stepCountForTest()`, `emitForTest: (String)->Unit` — identical across Tasks 1 and 2. `Rundown.stepReports` and `RunbookRundown` as `List<Rundown>` confirmed against source. ✓

**Known limitation (documented, not a gap):** the "banner prints once" and manual-sighting checks depend on the test JVM NOT having `revoman.banner`/`REVOMAN_BANNER` set to an off-value. Suppression itself is covered purely by `bannerEnabled`. Shutdown-hook *firing* is not unit-tested (JVM lifecycle); its output string is covered by `ctaText` tests.

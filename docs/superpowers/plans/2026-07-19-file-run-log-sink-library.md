# FileRunLogSink Library Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the generic per-test file-log orchestration out of loki-core's `PerTestRunLogSink.java` into a library `FileRunLogSink` (revoman-root), shrinking loki-core to a thin adapter.

**Architecture:** revoman-root (PRIMARY, Kotlin) gains `FileRunLogConfig` (a value type of content toggles) and `FileRunLogSink : RunLogSink` (owns file layout, live writer, banner+legend, footer+stacktrace, `latest.log`, toggle gating, never-throw, and content-agnostic perf HOOKS). loki-core (CONSUMER, Java) shrinks: `RunLogConfig` becomes a pure `~/.revoman/config.yaml` reader returning `(enabled, FileRunLogConfig)`; `ReVomanFTest`/`ReVomanPerf` retarget to `FileRunLogSink`; `PerTestRunLogSink.java` + `RunLogSinkHandle.java` + `PerTestRunLogSinkTest.java` are deleted.

**Tech Stack:** Kotlin (JDK 21), Kotest matchers + JUnit5 `@TempDir` (library tests), Gradle. loki-core side: Java 21, Google Truth + JUnit5, Bazel.

## Global Constraints

- JDK 21 for the JVM target (detekt breaks on 25).
- Library source is 100% Kotlin under `src/main/kotlin`; new sink lives in package `com.salesforce.revoman.output.log`, beside `RunLogRenderer`.
- The library must NOT learn consumer vocabulary: no `enabled` master switch, no `~/.revoman` path, no Salesforce "org" concept, no `OrgMode`. Identity crosses via a mode string at `open` + a generic `recordRunFact(key, value)`.
- Public library methods a Java consumer calls are `@JvmStatic` on the companion (factories) or plain instance methods; keep the `@JvmStatic`/`@JvmField` idiom already used by `RunLogRenderer`/`ConsoleRunLogSink`.
- All rendering delegates to `RunLogRenderer` — the single grammar source. Do NOT re-implement any glyph/spine/rule in the sink.
- Never-throw contract: `line`/`event`/`close` and every write swallow their own I/O errors (log once, never propagate onto ReVoman's hot path).
- Preserve the on-disk contract exactly: file layout `<logsDir>/<runLabel>/<timestamp>.log`, UTC timestamp stem `yyyy-MM-dd'T'HH-mm-ss`, `latest.log` symlink-then-pointer-fallback, banner at line 1. A consumer's existing logs/configs keep working.
- Run library tests with `./gradlew test`. Format with `./gradlew spotlessApply` before any commit that touches Kotlin (else spotlessCheck fails the build).

---

## File Structure

**revoman-root (create):**
- `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt` — content-toggle value type.
- `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt` — the sink (accretes across Tasks 2–5).
- `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt` — defaults test.
- `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt` — sink tests (accretes across Tasks 2–5).

**loki-core (modify/delete):**
- Modify: `.../revoman/runtime/RunLogConfig.java` — shrink to YAML reader → `(enabled, FileRunLogConfig)`.
- Modify: `.../revoman/runtime/ReVomanFTest.java` — retarget to `FileRunLogSink`.
- Modify: `.../revoman/runtime/ReVomanPerf.java` — retarget `ThreadLocal`/`setSink` type.
- Modify (test): `test/unit/.../revoman/runtime/RunLogConfigTest.java` — retarget assertions.
- Delete: `.../revoman/runtime/PerTestRunLogSink.java`, `.../revoman/runtime/RunLogSinkHandle.java`, `test/unit/.../revoman/runtime/PerTestRunLogSinkTest.java`.

Path prefix for the loki-core func-test-utils files: `~/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/`. Unit tests: `~/core-public/core/loki-core/test/unit/java/src/org/revcloud/loki/core/testutils/`.

---

## Task 1: `FileRunLogConfig` value type

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class FileRunLogConfig(libLogs: Boolean, steps: Boolean, perf: Boolean, outcome: Boolean, runbook: Boolean, heaviestSteps: Int)` with companion `const val DEFAULT_HEAVIEST_STEPS: Int = 10` and `@JvmField val DEFAULT_ALL: FileRunLogConfig`. Consumed by Tasks 2–5 (sink) and Task 6 (Core reader).

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt`:

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
import org.junit.jupiter.api.Test

class FileRunLogConfigTest {
  @Test
  fun `DEFAULT_ALL turns every content toggle on with the default heaviest-steps size`() {
    val cfg = FileRunLogConfig.DEFAULT_ALL
    cfg.libLogs shouldBe true
    cfg.steps shouldBe true
    cfg.perf shouldBe true
    cfg.outcome shouldBe true
    cfg.runbook shouldBe true
    cfg.heaviestSteps shouldBe FileRunLogConfig.DEFAULT_HEAVIEST_STEPS
    FileRunLogConfig.DEFAULT_HEAVIEST_STEPS shouldBe 10
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogConfigTest"`
Expected: FAIL — compile error, unresolved reference `FileRunLogConfig`.

- [ ] **Step 3: Write minimal implementation**

`src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt`:

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
 * The content-toggle knobs of a [FileRunLogSink]: which sections of a per-run log file get written.
 * A pure value type — a consumer supplies the values (e.g. Core reads them from
 * `~/.revoman/config.yaml`), and the library never learns WHERE they came from. The master
 * on/off switch and any "should we open a file at all" gate are the CONSUMER's orchestration, so
 * there is deliberately no `enabled` field here.
 *
 * @param libLogs tee the ReVoman library narration (INFO/DEBUG lines); WARN/ERROR always pass
 * @param steps per-step structured req/resp records
 * @param perf the perf tee lines + the perf summary block + the heaviest-steps table
 * @param outcome the pass/fail + failing-step + stacktrace footer
 * @param runbook the coarse runbook glyph brackets (phase rules, step open/close, contract lines)
 * @param heaviestSteps size of the heaviest-steps table
 */
data class FileRunLogConfig(
  val libLogs: Boolean,
  val steps: Boolean,
  val perf: Boolean,
  val outcome: Boolean,
  val runbook: Boolean,
  val heaviestSteps: Int,
) {
  companion object {
    /** Default size of the heaviest-steps table when a consumer supplies no explicit value. */
    const val DEFAULT_HEAVIEST_STEPS: Int = 10

    /** All-on default — the richest signal; a consumer degrades to this when config is absent. */
    @JvmField
    val DEFAULT_ALL: FileRunLogConfig =
      FileRunLogConfig(
        libLogs = true,
        steps = true,
        perf = true,
        outcome = true,
        runbook = true,
        heaviestSteps = DEFAULT_HEAVIEST_STEPS,
      )
  }
}
```

- [ ] **Step 4: Format, run test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt
git commit -m "feat(log): FileRunLogConfig content-toggle value type"
```

---

## Task 2: `FileRunLogSink` skeleton — open, banner+legend, line gating, close, latest.log

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt`

**Interfaces:**
- Consumes: `FileRunLogConfig` (Task 1); `RunLogSink`, `LogLevel` (existing).
- Produces:
  - `class FileRunLogSink private constructor(...) : RunLogSink`
  - `companion object`:
    - `@JvmStatic fun open(logsDir: Path, runLabel: String, mode: String, startedAt: Instant, config: FileRunLogConfig): FileRunLogSink` (throws `IOException`)
    - `@JvmStatic fun openOrNoOp(logsDir: Path, runLabel: String, mode: String, startedAt: Instant, config: FileRunLogConfig): FileRunLogSink?` (never throws; `null` on failure)
  - `override fun line(level: LogLevel, message: String)`
  - `override fun close()`
  - Later tasks add `event`, `recordRunFact`, `footer`, and the perf hooks to THIS class.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileRunLogSinkTest {

  private val ts: Instant = Instant.parse("2026-06-05T13:57:02Z")
  private val stamp = "2026-06-05T13-57-02.log"

  private fun runFile(logsDir: Path, label: String): Path =
    logsDir.resolve(label).resolve(stamp)

  @Test
  fun `banner sits at line 1 with identity and legend`(@TempDir logsDir: Path) {
    val sink =
      FileRunLogSink.open(logsDir, "UnifiedValidationE2ETest.getSlots", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.line(LogLevel.INFO, "***** Executing Step: get-slots *****")
    sink.close()

    val body = Files.readString(runFile(logsDir, "UnifiedValidationE2ETest.getSlots"))
    body shouldStartWith "=== ReVoman run"
    body shouldContain "[run] test=UnifiedValidationE2ETest.getSlots"
    body shouldContain "[run] mode=External"
    body shouldContain "--- legend ---"
    body shouldContain "Executing Step: get-slots"
  }

  @Test
  fun `latest points to the newest run`(@TempDir logsDir: Path) {
    FileRunLogSink.open(logsDir, "T.m", "FTest", ts, FileRunLogConfig.DEFAULT_ALL).close()
    val latest = logsDir.resolve("T.m").resolve("latest.log")
    Files.exists(latest) shouldBe true
    val pointed =
      if (Files.isSymbolicLink(latest)) Files.readSymbolicLink(latest).fileName.toString()
      else Files.readString(latest).trim()
    pointed shouldBe stamp
  }

  @Test
  fun `libLogs off drops INFO and DEBUG but keeps WARN`(@TempDir logsDir: Path) {
    val noLib = FileRunLogConfig(false, true, true, true, true, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noLib)
    sink.line(LogLevel.DEBUG, "{{token}} resolved from scope 'environment'")
    sink.line(LogLevel.INFO, "***** Executing Step *****")
    sink.line(LogLevel.WARN, "heads up")
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldNotContain "resolved from scope"
    body shouldNotContain "Executing Step"
    body shouldContain "heads up"
  }

  @Test
  fun `libLogs on passes DEBUG narration`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.line(LogLevel.DEBUG, "{{token}} resolved from scope 'environment'")
    sink.close()
    Files.readString(runFile(logsDir, "T.m")) shouldContain "resolved from scope"
  }

  @Test
  fun `openOrNoOp on an unwritable dir returns null and does not throw`() {
    val bogus = Path.of("/dev/null/cannot/create")
    val sink = FileRunLogSink.openOrNoOp(bogus, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.shouldBeNull()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: FAIL — compile error, unresolved reference `FileRunLogSink`.

- [ ] **Step 3: Write minimal implementation**

`src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * A built-in [RunLogSink] that streams ONE run's log to `<logsDir>/<runLabel>/<timestamp>.log`,
 * live (append + flush per write, so an external `tail -f` sees it during the run), and repoints
 * `latest.log` to it on [close]. The generic file-sink policy — the file layout, the
 * self-describing banner + legend, the outcome footer, content-toggle gating, the `latest.log`
 * pointer, and the never-throw guarantee — lives HERE in the library, shared by every consumer;
 * a consumer supplies only its own data (the logs dir, a run label, a mode string, a
 * [FileRunLogConfig], and any run facts via [recordRunFact]).
 *
 * All step/line rendering delegates to [RunLogRenderer] — the single grammar source — so the file
 * and console sinks cannot drift. Best-effort: any I/O error is swallowed (logged once) so logging
 * can never fail a run; the run is the product, the log is a convenience. Single-threaded for its
 * lifetime per the [RunLogSink] contract.
 */
class FileRunLogSink
private constructor(
  private val runFile: Path,
  private val testDir: Path,
  private val runLabel: String,
  private val mode: String,
  private val startedAt: Instant,
  private val config: FileRunLogConfig,
  private val out: BufferedWriter,
) : RunLogSink {

  override fun line(level: LogLevel, message: String) {
    // libLogs gates library narration: OFF drops INFO *and* DEBUG (e.g. the "{{x}} resolved from
    // scope" flood). WARN/ERROR always pass — they are diagnostics, not narration.
    if (!config.libLogs && (level == LogLevel.INFO || level == LogLevel.DEBUG)) {
      return
    }
    write("[$level] $message\n")
  }

  override fun event(event: StepEvent) {
    // Filled in Task 3.
  }

  override fun close() {
    try {
      out.flush()
      out.close()
    } catch (e: IOException) {
      logger.warn { "FileRunLogSink close/flush failed (ignored): $e" }
    }
    repointLatest()
  }

  /**
   * Banner written at file-open (line 1). Self-describing for an AI/human reading the file cold:
   * the run's identity, then a one-block legend of the line grammar that follows so the rest of the
   * file is parseable with no external doc. The legend describes exactly what [RunLogRenderer]
   * emits — it lives HERE, one file away from that grammar, so the two are edited together and
   * cannot drift. Run facts learned mid-run (e.g. a bound org id) are appended later via
   * [recordRunFact].
   */
  private fun writeBanner() {
    write(
      "=== ReVoman run =====================================\n" +
        "[run] test=$runLabel\n" +
        "[run] mode=$mode\n" +
        "[run] started=${HEADER_TIME.format(startedAt)}\n" +
        "--- legend ------------------------------------------\n" +
        "[run] ...      run-level facts (test, mode, org, started)\n" +
        "[INFO|WARN|ERROR] ...   ReVoman library narration (tee of 3prvm)\n" +
        "[ReVomanPerf] mode=.. stage=.. tookMs=..   per-stage timing\n" +
        "[ReVomanPerf] SUMMARY ...   end-of-run timing roll-up\n" +
        "┌ ◆|▶ <intent>  ⟵ consumes   ★ UNDER TEST   runbook step opens (heavy corner)\n" +
        "│ ▸ <name>  then  │   <status> OK|FAIL|SKIP · <ms>ms  ✔|✘|⊘   nested child request\n" +
        "│ ── REQ ── / │ ── RESP ──   HTTP exchange, every line under the │ spine\n" +
        "└ ✔|✘ <intent>  ⟶ produces   runbook step closes\n" +
        "the final block reads OUTCOME PASSED or FAILED; on FAILED it carries the\n" +
        "  failing step, a one-line error summary, and the full stacktrace + cause chain\n" +
        "=====================================================\n"
    )
  }

  // --- internals ---

  /** Append [s], flushing immediately so a `tail -f` reader sees every write live. Swallow-safe. */
  private fun write(s: String) {
    try {
      out.write(s)
      out.flush()
    } catch (e: Exception) {
      logger.warn { "FileRunLogSink write failed (ignored): $e" }
    }
  }

  /** Repoint `latest.log` -> this run file. Symlink first; fall back to a pointer file. */
  private fun repointLatest() {
    val latest = testDir.resolve(LATEST)
    val target = runFile.fileName
    try {
      Files.deleteIfExists(latest)
      Files.createSymbolicLink(latest, target)
    } catch (e: Exception) {
      try {
        Files.writeString(latest, target.toString(), StandardCharsets.UTF_8)
      } catch (ignored: IOException) {
        logger.warn { "FileRunLogSink latest pointer failed (ignored): $e" }
      }
    }
  }

  companion object {
    /** Filesystem-safe instant: ISO-8601 with `:` replaced by `-`, UTC. */
    private val STAMP: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)
    private val HEADER_TIME: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
    private const val LATEST = "latest.log"

    /**
     * Open a live writer for one run, creating `<logsDir>/<runLabel>/` and the timestamped file,
     * and writing the banner at line 1. Throws [IOException] on failure — callers that want the
     * never-fail guarantee use [openOrNoOp].
     */
    @JvmStatic
    @Throws(IOException::class)
    fun open(
      logsDir: Path,
      runLabel: String,
      mode: String,
      startedAt: Instant,
      config: FileRunLogConfig,
    ): FileRunLogSink {
      val testDir = logsDir.resolve(runLabel)
      Files.createDirectories(testDir)
      val runFile = testDir.resolve(STAMP.format(startedAt) + ".log")
      val out =
        Files.newBufferedWriter(
          runFile,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
        )
      val sink = FileRunLogSink(runFile, testDir, runLabel, mode, startedAt, config, out)
      sink.writeBanner()
      return sink
    }

    /** Never-throw factory: a real sink, or `null` when opening failed (per-run file logging off). */
    @JvmStatic
    fun openOrNoOp(
      logsDir: Path,
      runLabel: String,
      mode: String,
      startedAt: Instant,
      config: FileRunLogConfig,
    ): FileRunLogSink? =
      try {
        open(logsDir, runLabel, mode, startedAt, config)
      } catch (e: Exception) {
        // Belt-and-suspenders: okio can surface a checked IOException undeclared through Kotlin, so
        // catch broadly. Any open failure degrades to null (logging disabled for this run).
        logger.warn { "FileRunLogSink open failed; per-run file logging disabled for this run: $e" }
        null
      }
  }
}
```

- [ ] **Step 4: Format, run test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt
git commit -m "feat(log): FileRunLogSink skeleton — open, banner+legend, line gating, latest.log"
```

---

## Task 3: `FileRunLogSink.event` — step rendering, toggle gating, timing accumulation

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt`

**Interfaces:**
- Consumes: `StepEvent`, `RunLogRenderer.render(event)`, `Outcome`, `Phase` (existing).
- Produces:
  - `override fun event(event: StepEvent)` — real body (replaces the Task 2 stub).
  - Private `stepTimings: LinkedHashMap<String, Long>` field on the class, populated from `StepEvent.StepFinished` (read by Task 5's `renderHeaviestSteps`).

- [ ] **Step 1: Write the failing test**

Append to `FileRunLogSinkTest.kt` (add imports `com.salesforce.revoman.input.config.Phase`):

```kotlin
  @Test
  fun `steps off omits the finished-step block`(@TempDir logsDir: Path) {
    val noSteps = FileRunLogConfig(true, false, true, true, true, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noSteps)
    sink.event(
      StepEvent.StepFinished(
        path = "s",
        httpStatus = 200,
        produced = setOf("a"),
        consumed = emptySet(),
        tookMs = 12L,
        outcome = Outcome.SUCCESS,
        requestMsg = "POST /s\n\n{}",
        responseMsg = "HTTP/1.1 200 OK\n\n{}",
      )
    )
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldNotContain "│   200 OK"
    // The banner legend mentions "│ ── REQ ──" with 2 dashes; the REAL sub-rule fills wider.
    // Anchor on a 4-dash run to isolate the real block from the legend line.
    body shouldNotContain "│ ── REQ ────"
  }

  @Test
  fun `steps on writes the finished-step block with values and REQ RESP`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.event(
      StepEvent.StepFinished(
        path = "auth/login",
        httpStatus = 200,
        produced = setOf("accessToken"),
        consumed = setOf("baseUrl"),
        tookMs = 12L,
        outcome = Outcome.SUCCESS,
        requestMsg = "POST https://localhost:6101/login\n\n{\n  \"u\": 1\n}",
        responseMsg = "HTTP/1.1 200 OK\n\n{\n  \"ok\": true\n}",
        producedValues = mapOf("accessToken" to "tok123"),
        consumedValues = mapOf("baseUrl" to "https://localhost:6101"),
      )
    )
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldContain "│   200 OK · 12ms  ✔"
    body shouldContain "⟶ accessToken=tok123"
    body shouldContain "⟵ baseUrl=https://localhost:6101"
    body shouldContain "│ ── REQ ────"
    body shouldContain "│ POST https://localhost:6101/login"
    body shouldContain "│ ── RESP ────"
    body shouldContain "│   \"ok\": true"
  }

  @Test
  fun `coarse runbook events render phase rule and step open close`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.event(StepEvent.PhaseEntered(Phase.SEED))
    sink.event(StepEvent.RunbookStepStarted("seed SAs", "seed SAs", Phase.SEED, setOf("policyId"), false))
    sink.event(StepEvent.RunbookStepFinished("seed SAs", "seed SAs", Outcome.SUCCESS, mapOf("saId1" to "a07xx"), 812L))
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldContain "━━ SEED "
    body shouldContain "┌ ▶ seed SAs"
    body shouldContain "⟵ policyId"
    body shouldContain "└ ✔ seed SAs"
    body shouldContain "saId1=a07xx"
  }

  @Test
  fun `runbook off suppresses coarse events but keeps per-step`(@TempDir logsDir: Path) {
    val noRunbook = FileRunLogConfig(true, true, true, true, false, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noRunbook)
    sink.event(StepEvent.PhaseEntered(Phase.SEED))
    sink.event(
      StepEvent.StepFinished(
        path = "s",
        httpStatus = 200,
        produced = setOf("a"),
        consumed = emptySet(),
        tookMs = 12L,
        outcome = Outcome.SUCCESS,
        requestMsg = "POST /s\n\n{}",
        responseMsg = "HTTP/1.1 200 OK\n\n{}",
      )
    )
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldNotContain "━━ SEED"
    body shouldContain "│   200 OK"
  }

  @Test
  fun `contract-failed line renders`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.event(StepEvent.RunbookContractFailed("validate", "validate", setOf("saId1"), emptySet(), emptyMap()))
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldContain "⚠ CONTRACT"
    body shouldContain "missing consumed: [saId1]"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: FAIL — the new tests fail (`event` is a stub, nothing written).

- [ ] **Step 3: Write minimal implementation**

In `FileRunLogSink.kt`, add the timings field (inside the class body, near the top):

```kotlin
  /** Per-run step timings (path -> summed tookMs) for the heaviest-steps table (Task 5). */
  private val stepTimings = LinkedHashMap<String, Long>()
```

Replace the `event` stub with:

```kotlin
  override fun event(event: StepEvent) {
    // Accumulate step timings for the heaviest-steps table BEFORE any content gate.
    if (event is StepEvent.StepFinished) {
      stepTimings.merge(event.path, event.tookMs, Long::sum)
    }
    // Coarse runbook events render under their OWN toggle (independent of `steps`), so a reader can
    // keep the runbook tree while dropping per-request bodies (or the reverse). All grammar comes
    // from RunLogRenderer — one source shared with ConsoleRunLogSink.
    if (isCoarseRunbookEvent(event)) {
      if (config.runbook) {
        write(RunLogRenderer.render(event))
      }
      return
    }
    if (!config.steps) {
      return
    }
    write(RunLogRenderer.render(event))
  }

  private fun isCoarseRunbookEvent(event: StepEvent): Boolean =
    event is StepEvent.PhaseEntered ||
      event is StepEvent.RunbookStepStarted ||
      event is StepEvent.RunbookStepFinished ||
      event is StepEvent.RunbookContractFailed
```

- [ ] **Step 4: Format, run test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: PASS (9 tests total).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt
git commit -m "feat(log): FileRunLogSink.event — step rendering, toggle gating, timing accumulation"
```

---

## Task 4: `FileRunLogSink` — recordRunFact + outcome footer with stacktrace

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt`

**Interfaces:**
- Consumes: nothing new (JDK `Throwable`, `PrintWriter`, `StringWriter`).
- Produces:
  - `fun recordRunFact(key: String, value: String?)` — appends `[run] key=value` (`null` → `(unset)`).
  - `fun footer(passed: Boolean, failingStep: String?, failure: Throwable?)` — outcome-toggle-gated verdict block + full stacktrace/cause chain.

- [ ] **Step 1: Write the failing test**

Append to `FileRunLogSinkTest.kt`:

```kotlin
  @Test
  fun `recordRunFact appends a tagged run-level line`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.recordRunFact("org", "00Dxx0000001gPq")
    sink.close()
    Files.readString(runFile(logsDir, "T.m")) shouldContain "[run] org=00Dxx0000001gPq"
  }

  @Test
  fun `recordRunFact renders null value as unset`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.recordRunFact("org", null)
    sink.close()
    Files.readString(runFile(logsDir, "T.m")) shouldContain "[run] org=(unset)"
  }

  @Test
  fun `footer captures failing step and full stacktrace with cause`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    val root = IllegalStateException("DB partition specifier not cleared")
    val failure = AssertionError("expected status 200 but was 400", root)
    sink.footer(false, "pq | POST /services/data/v62.0/...  HTTP 400", failure)
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldContain "=== OUTCOME: FAILED"
    body shouldContain "failingStep"
    body shouldContain "HTTP 400"
    body shouldContain "error        java.lang.AssertionError: expected status 200 but was 400"
    body shouldContain "Caused by: java.lang.IllegalStateException: DB partition specifier not cleared"
    body shouldContain "at "
  }

  @Test
  fun `footer with null failure still writes verdict without trace`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.footer(false, null, null)
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldContain "=== OUTCOME: FAILED"
    body shouldNotContain "Caused by:"
  }

  @Test
  fun `outcome off omits the footer`(@TempDir logsDir: Path) {
    val noOutcome = FileRunLogConfig(true, true, true, false, true, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noOutcome)
    sink.footer(true, null, null)
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldNotContain "=== OUTCOME: PASSED"
    body shouldNotContain "=== OUTCOME: FAILED"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: FAIL — compile error, unresolved references `recordRunFact` / `footer`.

- [ ] **Step 3: Write minimal implementation**

Add the imports to `FileRunLogSink.kt`:

```kotlin
import java.io.PrintWriter
import java.io.StringWriter
```

Add these methods to the class body:

```kotlin
  /**
   * Append a run-level fact as a tagged `[run] key=value` line — grep-able as `[run] key=`
   * regardless of where in the file it lands. Generic: a consumer records whatever it learns
   * mid-run (e.g. a bound org id, once setUp binds it — it cannot be in the line-1 banner). A
   * `null` [value] renders `(unset)`.
   */
  fun recordRunFact(key: String, value: String?) {
    write("[run] $key=${value ?: "(unset)"}\n")
  }

  /**
   * Write the outcome footer (the final block). Gated by the `outcome` content toggle. On a
   * failure, surfaces a one-line `error` summary for a quick scan PLUS the FULL stack trace of
   * [failure] — including its `Caused by:` / `Suppressed:` chain — so a reader can diagnose the
   * break from the log alone without rerunning. Rendered via [Throwable.printStackTrace] (not a
   * hand-rolled walk) so the cause chain and the JDK's circular-reference guard are handled
   * correctly. The trace is intentionally NOT trimmed — a truncated trace can hide the very
   * root-cause frame that explains the failure.
   */
  fun footer(passed: Boolean, failingStep: String?, failure: Throwable?) {
    if (!config.outcome) {
      return
    }
    val sb = StringBuilder()
    sb.append("=== OUTCOME: ")
      .append(if (passed) "PASSED" else "FAILED")
      .append(" =================================\n")
    if (!passed) {
      if (failingStep != null) {
        sb.append("failingStep  ").append(failingStep).append('\n')
      }
      if (failure != null) {
        sb.append("error        ").append(failure).append('\n')
        sb.append("--- stacktrace --------------------------------------\n")
        sb.append(stackTraceOf(failure))
      }
    }
    sb.append("=====================================================\n")
    write(sb.toString())
  }

  /** Full stack trace + cause/suppressed chain of [t], as the JVM would print it. */
  private fun stackTraceOf(t: Throwable): String {
    val sw = StringWriter()
    PrintWriter(sw).use { t.printStackTrace(it) }
    val trace = sw.toString()
    // printStackTrace already ends each line with a newline; guarantee a trailing one so the
    // closing banner sits on its own line even for an exotic Throwable override.
    return if (trace.endsWith("\n")) trace else trace + "\n"
  }
```

- [ ] **Step 4: Format, run test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: PASS (14 tests total).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt
git commit -m "feat(log): FileRunLogSink recordRunFact + outcome footer with stacktrace"
```

---

## Task 5: `FileRunLogSink` — perf hooks (perfLine, renderHeaviestSteps, recordPerfSummary + splice)

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt`

**Interfaces:**
- Consumes: `stepTimings` (Task 3), `write`/`runFile`/`config` (Task 2).
- Produces:
  - `fun perfLine(line: String)` — tee one raw perf line, perf-toggle gated.
  - `fun renderHeaviestSteps(topN: Int): String` — the `--- perf: heaviest steps ---` table from accumulated timings, slowest first.
  - `fun recordPerfSummary(block: String)` — perf-toggle gated: live-write `block` AND store it so `close()` splices the identical block below the banner.
  - `close()` gains the splice-below-banner step.

- [ ] **Step 1: Write the failing test**

Append to `FileRunLogSinkTest.kt`:

```kotlin
  private fun finished(path: String, tookMs: Long): StepEvent.StepFinished =
    StepEvent.StepFinished(
      path = path,
      httpStatus = 200,
      produced = emptySet(),
      consumed = emptySet(),
      tookMs = tookMs,
      outcome = Outcome.SUCCESS,
      requestMsg = "POST /$path\n\n{}",
      responseMsg = "HTTP/1.1 200 OK\n\n{}",
    )

  @Test
  fun `heaviest steps sorts by tookMs desc and caps at topN`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.event(finished("fast", 5L))
    sink.event(finished("slow", 900L))
    sink.event(finished("mid", 100L))
    val table = sink.renderHeaviestSteps(2)
    sink.close()
    val slow = table.indexOf("slow")
    val mid = table.indexOf("mid")
    (slow > -1) shouldBe true
    (mid > -1) shouldBe true
    (slow < mid) shouldBe true
    table shouldNotContain "fast"
    table shouldContain "--- perf: heaviest steps"
  }

  @Test
  fun `heaviest steps topN larger than count shows all`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.event(finished("only", 42L))
    val table = sink.renderHeaviestSteps(10)
    sink.close()
    table shouldContain "only"
    table shouldContain "42ms"
  }

  @Test
  fun `recordPerfSummary writes at footer and splices below banner`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.line(LogLevel.INFO, "***** Executing Step: only *****")
    val block = "--- perf: where the time went ---\n[ReVomanPerf] mode=External total=42ms\n"
    sink.recordPerfSummary(block)
    sink.footer(true, null, null)
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    val first = body.indexOf("[ReVomanPerf] mode=External total=42ms")
    val second = body.indexOf("[ReVomanPerf] mode=External total=42ms", first + 1)
    val stream = body.indexOf("Executing Step: only")
    (first > -1) shouldBe true
    (second > first) shouldBe true
    (first < stream) shouldBe true // header copy ABOVE the live stream
    (second > stream) shouldBe true // footer copy BELOW it
  }

  @Test
  fun `recordPerfSummary with perf off writes nothing`(@TempDir logsDir: Path) {
    val noPerf = FileRunLogConfig(true, true, false, true, true, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noPerf)
    sink.recordPerfSummary("--- perf: where the time went ---\nSHOULD_NOT_APPEAR\n")
    sink.close()
    Files.readString(runFile(logsDir, "T.m")) shouldNotContain "SHOULD_NOT_APPEAR"
  }

  @Test
  fun `perfLine with perf off writes nothing`(@TempDir logsDir: Path) {
    val noPerf = FileRunLogConfig(true, true, false, true, true, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noPerf)
    sink.perfLine("[ReVomanPerf] mode=External stage=x tookMs=1")
    sink.close()
    Files.readString(runFile(logsDir, "T.m")) shouldNotContain "ReVomanPerf] mode=External stage=x"
  }

  @Test
  fun `close without recordPerfSummary leaves file intact`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.line(LogLevel.INFO, "hello")
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldStartWith "=== ReVoman run"
    body shouldContain "hello"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogSinkTest"`
Expected: FAIL — compile error, unresolved references `renderHeaviestSteps` / `recordPerfSummary` / `perfLine`.

- [ ] **Step 3: Write minimal implementation**

Add the perf-block field next to `stepTimings`:

```kotlin
  /** The rendered perf summary block, stored at footer-write time so close() can splice it below the banner. */
  private var perfBlock: String? = null
```

Add these methods to the class body:

```kotlin
  /** Tee one raw perf line; gated by the `perf` content toggle. */
  fun perfLine(line: String) {
    if (config.perf) {
      write(line + "\n")
    }
  }

  /**
   * Render the `--- perf: heaviest steps ---` table: the [topN] steps by accumulated `tookMs`,
   * slowest first. Reflects every step this sink saw — including chained body collections and
   * cleanup steps — so it is the "where did the time go" view at step granularity. Newline-
   * terminated; header-only when no steps ran. When a step path repeats in a run (retries/chained
   * collections), its times are summed — this is total time under that path, not one execution.
   */
  fun renderHeaviestSteps(topN: Int): String {
    val sb = StringBuilder("--- perf: heaviest steps ----------------------------\n")
    stepTimings.entries
      .sortedByDescending { it.value }
      .take(topN.coerceAtLeast(0))
      .forEach { sb.append(String.format("  %-44s%8dms\n", truncatePath(it.key), it.value)) }
    return sb.toString()
  }

  /** Keep the heaviest-steps table aligned: cap an over-long step path so the ms column doesn't shift. */
  private fun truncatePath(path: String): String =
    if (path.length <= 44) path else path.substring(0, 41) + "..."

  /**
   * Write the consolidated perf summary [block] at the footer (live, so a tailing reader sees it as
   * the run ends) AND store it so [close] can splice the IDENTICAL block in below the header banner.
   * The block therefore appears TWICE in the final file: once below the banner (spliced at close)
   * for an at-a-glance read, once at the footer (written live) for streaming visibility. Gated by
   * the `perf` content toggle. Call once, just before [footer]. Content-agnostic: the block text is
   * the consumer's (e.g. Core's perf breakdown + this sink's heaviest-steps table).
   */
  fun recordPerfSummary(block: String) {
    if (!config.perf) {
      return
    }
    this.perfBlock = block
    write(block)
  }
```

Replace the `close` body to add the splice step (between the flush/close and `repointLatest`):

```kotlin
  override fun close() {
    try {
      out.flush()
      out.close()
    } catch (e: IOException) {
      logger.warn { "FileRunLogSink close/flush failed (ignored): $e" }
    }
    splicePerfBlockBelowBanner()
    repointLatest()
  }
```

Add the splice helpers to the class body:

```kotlin
  /**
   * One-shot whole-file rewrite that inserts the stored [perfBlock] right after the header banner's
   * closing rule (the first line consisting solely of `=`). No-op when no block was recorded or the
   * anchor is not found. Best-effort: any I/O error leaves the footer copy intact and is logged —
   * the splice is a convenience, never a run-failing operation. The writer is already closed at this
   * point, so this reads + rewrites the finished file.
   */
  private fun splicePerfBlockBelowBanner() {
    val block = perfBlock ?: return
    try {
      val content = Files.readString(runFile, StandardCharsets.UTF_8)
      val anchorEnd = bannerCloseLineEnd(content)
      if (anchorEnd < 0) {
        logger.warn { "FileRunLogSink perf splice: banner anchor not found (ignored)" }
        return
      }
      val spliced = content.substring(0, anchorEnd) + block + content.substring(anchorEnd)
      Files.writeString(runFile, spliced, StandardCharsets.UTF_8)
    } catch (e: Exception) {
      logger.warn { "FileRunLogSink perf splice failed (ignored): $e" }
    }
  }

  /**
   * Index just PAST the newline that ends the banner's closing rule — the first line made up only
   * of `=` characters (the banner opens with `=== ReVoman run ...`, which has trailing text, so the
   * first all-`=` line is the closing rule). Returns `-1` if absent.
   */
  private fun bannerCloseLineEnd(content: String): Int {
    var pos = 0
    while (pos < content.length) {
      val nl = content.indexOf('\n', pos)
      val lineEnd = if (nl < 0) content.length else nl
      val line = content.substring(pos, lineEnd)
      if (line.isNotEmpty() && line.all { it == '=' }) {
        return if (nl < 0) -1 else nl + 1 // just past the newline; -1 if no trailing newline (malformed)
      }
      if (nl < 0) {
        break
      }
      pos = nl + 1
    }
    return -1
  }
```

- [ ] **Step 4: Format, run the FULL library test suite**

Run: `./gradlew spotlessApply && ./gradlew test`
Expected: PASS — the whole `test` suite green, including all `FileRunLogSinkTest` (20 tests) and `FileRunLogConfigTest`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogSinkTest.kt
git commit -m "feat(log): FileRunLogSink perf hooks — perfLine, renderHeaviestSteps, recordPerfSummary splice"
```

---

## Task 6: loki-core — shrink `RunLogConfig` to a `(enabled, FileRunLogConfig)` reader

> **Repo:** loki-core (`~/core-public/core`). Core compiles ReVoman from sources via `.bazelrc-local`, so the library classes from Tasks 1–5 are already visible once committed. Run Core unit tests via the Core junit runner — see the `/salesforce-core-dev:core-engineer` junit sub-skill (loki-core unit tests live under `test/unit`). Format Java per `/my-java-coding-style`.

**Files:**
- Modify: `test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogConfig.java`
- Test: `test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogConfigTest.java`

**Interfaces:**
- Consumes: `com.salesforce.revoman.output.log.FileRunLogConfig` (Task 1), `com.salesforce.revoman.input.FileUtils.readYamlMap` (existing).
- Produces: `record RunLogConfig(boolean enabled, FileRunLogConfig content)` with `static RunLogConfig read()`, `static RunLogConfig read(Path)`, and `static final RunLogConfig DEFAULT_ALL`. Consumed by Task 7 (`ReVomanFTest`).

- [ ] **Step 1: Retarget the failing test**

Replace `RunLogConfigTest.java` body assertions to read through `content()`. Full file:

```java
/*
 * Copyright 2026 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */
package org.revcloud.loki.core.testutils.revoman.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure unit coverage of {@link RunLogConfig}: the {@code logs:} block reader from {@code
 * ~/.revoman/config.yaml}, mapping it onto the {@code enabled} master switch plus a library {@link
 * com.salesforce.revoman.output.log.FileRunLogConfig} of content toggles. Default is
 * enabled-with-all-content (best signal for the dev loop); a missing/malformed file degrades to
 * that default rather than failing. Path-parameterized so it needs no real home dir.
 */
class RunLogConfigTest {

    @Test
    void missingFile_enabledWithAllContent(@TempDir Path dir) {
        RunLogConfig cfg = RunLogConfig.read(dir.resolve("absent.yaml"));
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.content().getLibLogs()).isTrue();
        assertThat(cfg.content().getSteps()).isTrue();
        assertThat(cfg.content().getPerf()).isTrue();
        assertThat(cfg.content().getOutcome()).isTrue();
        assertThat(cfg.content().getRunbook()).isTrue();
    }

    @Test
    void explicitDisable(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs:\n  enabled: false\n");
        assertThat(RunLogConfig.read(f).enabled()).isFalse();
    }

    @Test
    void perContentToggle(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs:\n  content:\n    steps: false\n    perf: false\n");
        RunLogConfig cfg = RunLogConfig.read(f);
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.content().getLibLogs()).isTrue();
        assertThat(cfg.content().getSteps()).isFalse();
        assertThat(cfg.content().getPerf()).isFalse();
        assertThat(cfg.content().getOutcome()).isTrue();
    }

    @Test
    void malformed_fallsBackToDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs: : : not-a-map\n");
        RunLogConfig cfg = RunLogConfig.read(f);
        assertThat(cfg.enabled()).isTrue();
    }

    @Test
    void heaviestSteps_defaultsTo10WhenAbsent(@TempDir Path dir) {
        RunLogConfig cfg = RunLogConfig.read(dir.resolve("absent.yaml"));
        assertThat(cfg.content().getHeaviestSteps()).isEqualTo(10);
    }

    @Test
    void heaviestSteps_readsExplicitValue(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs:\n  heaviestSteps: 25\n");
        assertThat(RunLogConfig.read(f).content().getHeaviestSteps()).isEqualTo(25);
    }

    @Test
    void heaviestSteps_nonPositiveFallsBackToDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs:\n  heaviestSteps: 0\n");
        assertThat(RunLogConfig.read(f).content().getHeaviestSteps()).isEqualTo(10);
    }

    @Test
    void heaviestSteps_negativeFallsBackToDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs:\n  heaviestSteps: -5\n");
        assertThat(RunLogConfig.read(f).content().getHeaviestSteps()).isEqualTo(10);
    }

    @Test
    void heaviestSteps_malformedFallsBackToDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, "logs:\n  heaviestSteps: not-a-number\n");
        assertThat(RunLogConfig.read(f).content().getHeaviestSteps()).isEqualTo(10);
    }

    @Test
    void runbookToggleDefaultsOnWhenAbsent(@TempDir Path dir) throws Exception {
        final Path cfg = dir.resolve("config.yaml");
        Files.writeString(cfg, "logs:\n  enabled: true\n");
        assertThat(RunLogConfig.read(cfg).content().getRunbook()).isTrue();
    }

    @Test
    void runbookToggleReadsExplicitFalse(@TempDir Path dir) throws Exception {
        final Path cfg = dir.resolve("config.yaml");
        Files.writeString(cfg, "logs:\n  enabled: true\n  content:\n    runbook: false\n");
        assertThat(RunLogConfig.read(cfg).content().getRunbook()).isFalse();
    }
}
```

Note: Kotlin `data class` boolean properties expose `getX()` accessors to Java (no `is` prefix for non-`Boolean`-named vals declared as `val libLogs: Boolean`; Kotlin generates `getLibLogs()`). If Core compilation reports the accessor is `isLibLogs()`, switch the calls accordingly — verify against the generated bytecode at Step 4.

- [ ] **Step 2: Run test to verify it fails**

Run the retargeted `RunLogConfigTest` via the Core junit runner.
Expected: FAIL — compile error (`RunLogConfig.content()` does not exist yet; `libLogs()` etc. removed).

- [ ] **Step 3: Write minimal implementation**

Replace `RunLogConfig.java` fully:

```java
/*
 * Copyright 2026 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */
package org.revcloud.loki.core.testutils.revoman.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.revcloud.loki.core.testutils.revoman.orgmode.RevomanHome;

import com.salesforce.revoman.input.FileUtils;
import com.salesforce.revoman.output.log.FileRunLogConfig;

/**
 * Reader for the {@code logs:} block of {@code ~/.revoman/config.yaml}: maps it onto the {@code
 * enabled} master switch plus the library {@link FileRunLogConfig} of content toggles that a {@link
 * com.salesforce.revoman.output.log.FileRunLogSink} consumes. Default (missing block, missing file,
 * or any read error) is {@code enabled} with ALL content toggles on — the richest signal for the
 * external-org dev loop. Only an explicit {@code logs.enabled: false} turns it off; only an explicit
 * {@code logs.content.<x>: false} drops a section. Mirrors the never-throw, default-on-error
 * contract of {@link org.revcloud.loki.core.testutils.revoman.orgmode.RevomanLocalOrgConfig}.
 *
 * @param enabled master switch (Core orchestration; NOT a library concept)
 * @param content the library content toggles a FileRunLogSink is opened with
 */
public record RunLogConfig(boolean enabled, FileRunLogConfig content) {

    /** All-on default used when the file/block is absent or unreadable. */
    static final RunLogConfig DEFAULT_ALL = new RunLogConfig(true, FileRunLogConfig.DEFAULT_ALL);

    /** Read from the standard {@code ~/.revoman/config.yaml}. */
    public static RunLogConfig read() {
        return read(RevomanHome.configPath());
    }

    /** Read from {@code configPath}; never throws — any problem yields {@link #DEFAULT_ALL}. */
    static RunLogConfig read(Path configPath) {
        if (!Files.exists(configPath)) {
            return DEFAULT_ALL;
        }
        try {
            final Map<String, Object> config = FileUtils.readYamlMap(configPath.toString());
            if (!(config.get("logs") instanceof Map<?, ?> logs)) {
                return DEFAULT_ALL;
            }
            final boolean enabled = boolAt(logs, "enabled", true);
            final Map<?, ?> content = logs.get("content") instanceof Map<?, ?> c ? c : Map.of();
            final FileRunLogConfig toggles =
                    new FileRunLogConfig(
                            boolAt(content, "libLogs", true),
                            boolAt(content, "steps", true),
                            boolAt(content, "perf", true),
                            boolAt(content, "outcome", true),
                            boolAt(content, "runbook", true),
                            heaviestStepsAt(logs));
            return new RunLogConfig(enabled, toggles);
        } catch (Exception e) {
            // Belt to the Files.exists guard: okio surfaces a checked IOException undeclared through
            // Kotlin, which a RuntimeException-only catch would miss. Any residual error => default-on.
            return DEFAULT_ALL;
        }
    }

    private static boolean boolAt(Map<?, ?> map, String key, boolean fallback) {
        return Optional.ofNullable(map.get(key))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(fallback);
    }

    /**
     * Read {@code logs.heaviestSteps} as a positive int, defaulting to {@link
     * FileRunLogConfig#DEFAULT_HEAVIEST_STEPS} when the key is absent, non-numeric, or {@code <= 0}.
     * A non-positive value falls back to the default rather than hiding the table.
     */
    private static int heaviestStepsAt(Map<?, ?> map) {
        final Object raw = map.get("heaviestSteps");
        if (raw instanceof Number number) {
            final int value = number.intValue();
            return value > 0 ? value : FileRunLogConfig.DEFAULT_HEAVIEST_STEPS;
        }
        return FileRunLogConfig.DEFAULT_HEAVIEST_STEPS;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the retargeted `RunLogConfigTest` via the Core junit runner.
Expected: PASS. If an accessor name mismatch surfaced (`getLibLogs` vs `isLibLogs`), fix the test calls to match the generated Kotlin accessors and re-run.

- [ ] **Step 5: Commit** (in the loki-core repo)

```bash
git add loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogConfig.java \
        loki-core/test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogConfigTest.java
git commit -m "refactor(revoman): RunLogConfig reads onto library FileRunLogConfig"
```

---

## Task 7: loki-core — retarget `ReVomanFTest` + `ReVomanPerf` to `FileRunLogSink`; delete dead files

> **Repo:** loki-core. This is the shrink: `PerTestRunLogSink.java`, `RunLogSinkHandle.java`, and `PerTestRunLogSinkTest.java` are DELETED (their coverage now lives in the library `FileRunLogSinkTest`). `ReVomanFTest` holds a `@Nullable FileRunLogSink` instead of a `RunLogSinkHandle`; `ReVomanPerf`'s `ThreadLocal` retypes. Format Java per `/my-java-coding-style`.

**Files:**
- Modify: `test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/ReVomanFTest.java`
- Modify: `test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/ReVomanPerf.java`
- Delete: `test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSink.java`
- Delete: `test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogSinkHandle.java`
- Delete: `test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSinkTest.java`

**Interfaces:**
- Consumes: `FileRunLogSink` (Tasks 2–5), `RunLogConfig` (Task 6), `com.salesforce.revoman.output.log.RunLogSink` (existing).
- Produces: no new public API; internal wiring only.

- [ ] **Step 1: Delete the dead files**

```bash
cd ~/core-public/core
git rm loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSink.java \
       loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogSinkHandle.java \
       loki-core/test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/PerTestRunLogSinkTest.java
```

- [ ] **Step 2: Retarget `ReVomanPerf`**

Two edits in `ReVomanPerf.java`:

Replace the `SINK` field (lines ~67–72) type:

```java
    /**
     * Per-dispatch run-log sink. ThreadLocal for the same reason as {@link #STAGES} — set by {@link
     * ReVomanFTest} at ftestSetUp so the {@code [ReVomanPerf]} lines are teed into that test's run
     * file, cleared at ftestTearDown. Null when per-test logging is off (the no-tee default).
     */
    private static final ThreadLocal<FileRunLogSink> SINK = new ThreadLocal<>();
```

Replace the `setSink` signature + `teePerf` local type:

```java
    public static void setSink(FileRunLogSink sink) {
        if (sink == null) {
            SINK.remove();
        } else {
            SINK.set(sink);
        }
    }
```

```java
    private static void teePerf(String line) {
        FileRunLogSink sink = SINK.get();
        if (sink != null) {
            sink.perfLine(line);
        }
    }
```

Add the import (with the other `com.salesforce.revoman...` imports):

```java
import com.salesforce.revoman.output.log.FileRunLogSink;
```

- [ ] **Step 3: Retarget `ReVomanFTest`**

Edit `ReVomanFTest.java`.

Imports — add:

```java
import com.salesforce.revoman.output.log.FileRunLogSink;
import com.salesforce.revoman.output.log.RunLogSink;
```

Fields (replace lines ~39–47):

```java
    // Per-dispatch run-log state. The sink is a live FileRunLogSink (or null when logging is off /
    // the file couldn't open); runStartedAt stamps the run file + header; lastFailure carries the
    // testBody throwable into the outcome footer at ftestTearDown.
    private FileRunLogSink runLogSink;
    private Instant runStartedAt;
    private volatile Throwable lastFailure;
    // RunLogConfig read once per dispatch in buildRunLogSink and reused by the env snapshot so the
    // two share ONE gate (they must enable/disable together). Null until ftestSetUp runs.
    private RunLogConfig runLogConfig;
```

`ftestSetUp` wiring (replace lines ~118–131):

```java
        runLogSink = buildRunLogSink(resolved, runStartedAt);
        runner().setRunLogSink(runLogSink != null ? runLogSink : RunLogSink.NoOp.INSTANCE);
        ReVomanPerf.setSink(runLogSink);
        try {
            ReVomanPerf.timed(ReVomanPerf.mode(resolved), "ftestSetUp", () -> {
                if (resolved instanceof OrgMode.FTestOrg) {
                    super.ftestSetUp();
                }
                runner().setUp(spec);
                // Banner is already at line 1 (written at sink open). The org binds DURING setUp, so
                // append it now as a tagged [run] org= run fact — can't be in the line-1 banner.
                if (runLogSink != null) {
                    runLogSink.recordRunFact("org", boundOrgId());
                }
            });
        } catch (Throwable t) {
```

`closeRunLog` (replace lines ~142–149):

```java
    /** Close the run-log sink (if any) and clear the perf tee. Swallow-safe; idempotent (null-safe). */
    private void closeRunLog() {
        if (runLogSink != null) {
            runLogSink.close();
        }
        ReVomanPerf.setSink(null);
        runLogSink = null;
    }
```

`ftestTearDown` perf/footer block (replace lines ~166–179):

```java
            if (runLogSink != null) {
                // Consolidated perf block = phase breakdown + heaviest-steps table. Recorded BEFORE
                // the footer so it precedes the OUTCOME block in the stream; close() then splices the
                // identical block below the header banner.
                String perfBlock =
                        ReVomanPerf.renderBreakdown(breakdown)
                                + runLogSink.renderHeaviestSteps(runLogConfig.content().heaviestSteps());
                runLogSink.recordPerfSummary(perfBlock);
                boolean passed = lastFailure == null;
                // The failing STEP is already visible as an Outcome=FAILED step event in the stream;
                // the footer carries the test-level pass/fail + the thrown exception (the WHY).
                runLogSink.footer(passed, null, lastFailure);
            }
```

Note: `runLogConfig.content().heaviestSteps()` — the Kotlin `val heaviestSteps: Int` exposes `getHeaviestSteps()` to Java, so the record component accessor is `content().getHeaviestSteps()`. Use whichever the compiler accepts (see Task 6 Step 4 note); the value is the same.

`buildRunLogSink` (replace lines ~275–290):

```java
    private FileRunLogSink buildRunLogSink(OrgMode resolved, Instant startedAt) {
        if (TestContext.isRunningOnAutoBuild()) {
            return null;
        }
        runLogConfig = RunLogConfig.read();
        if (!runLogConfig.enabled()) {
            return null;
        }
        return FileRunLogSink.openOrNoOp(
                RevomanHome.logsDir(),
                getClass().getSimpleName() + "." + getTestMethodName(),
                ReVomanPerf.mode(resolved),
                startedAt,
                runLogConfig.content());
    }
```

Its Javadoc (lines ~270–274) stays accurate; update the return-type sentence to "or {@code null} when logging is disabled".

- [ ] **Step 4: Compile loki-core and run the retargeted unit tests**

Compile the loki-core test-utils + unit tests (Bazel build of the module), then run `RunLogConfigTest` via the Core junit runner.
Expected: compiles clean (no reference to `PerTestRunLogSink`/`RunLogSinkHandle` remains — verify with `grep -rn 'PerTestRunLogSink\|RunLogSinkHandle' loki-core` → no hits), `RunLogConfigTest` PASSES.

- [ ] **Step 5: Commit** (in the loki-core repo)

```bash
git add -A loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/ \
          loki-core/test/unit/java/src/org/revcloud/loki/core/testutils/revoman/runtime/
git commit -m "refactor(revoman): adopt library FileRunLogSink; delete PerTestRunLogSink + RunLogSinkHandle"
```

---

## Task 8: End-to-end verification against a live Core server

> **Repo:** both. Use `/salesforce-core-dev:core-engineer` (and `/revoman-for-core:core-app-ops`) to rebuild the consumable jar, restart the server, and run one real ReVoman FTest. This is a manual observation gate — no code changes — confirming the extracted sink renders identically end-to-end. Spawn a sub-agent for the long-running server restart / FTest.

**Files:** none (verification only).

- [ ] **Step 1: Rebuild the consumable jar (revoman-root)**

Per DEVELOPMENT.md:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn
cd ~/code-clones/work/revoman-root
rm -f build/libs/revoman-*.jar        # the Core BUILD.bazel glob grabs all revoman-*.jar
./gradlew spotlessApply
./gradlew clean build                 # full green: unit + integration + spotless + kover
```

Expected: BUILD SUCCESSFUL, and `build/libs/revoman-<version>.jar` present. Verify the kotlinx bundle is still intact:

```bash
unzip -l build/libs/revoman-*.jar | grep -c 'kotlinx/collections/immutable'   # expect ~130
```

- [ ] **Step 2: Restart the Core server**

Clear stale logs, then restart (sub-agent):

```bash
trash ~/core-public/core/sfdc/logs/sfdc 2>/dev/null; mkdir -p ~/core-public/core/sfdc/logs/sfdc
```

Restart the Core app server per core-app-ops.

- [ ] **Step 3: Run one real ReVoman FTest and inspect the run log**

Run a representative external-org ReVoman FTest (e.g. a `*E2ETest`) via ftest-console. Then inspect its run file:

```bash
cat "$(ls -t ~/.revoman/logs/*/latest.log | head -1)"
```

Expected in the file:
- banner at line 1 with `[run] test=`, `[run] mode=`, `--- legend ---`;
- `[run] org=...` line (from `recordRunFact`);
- per-step blocks (`│   <status> ... ✔`) and, if a runbook, coarse `┌ … └` brackets + `━━ PHASE`;
- perf block present TWICE (spliced below banner AND at footer), including `--- perf: heaviest steps ---`;
- `=== OUTCOME: PASSED/FAILED ===` footer;
- `latest.log` points to the newest timestamped file.

- [ ] **Step 4: Confirm parity + no drift**

Confirm the rendered file matches the pre-refactor format (same grammar, same legend, same sections). Any difference is a regression to fix before closing.

- [ ] **Step 5: (Optional) library version bump + Maven publish**

Only if a published artifact is required downstream (Core E2E uses `.bazelrc-local` sources, so this is NOT required for the source path). If requested: bump version, `./gradlew publish` per CONTRIBUTING/scripts/release.sh, and update the Core dependency pin.

---

## Self-Review

**Spec coverage:**
- Generic bulk → library `FileRunLogSink` (banner+legend, writer, footer+stacktrace, latest.log, gating, never-throw): Tasks 2–5. ✓
- `FileRunLogConfig` value type (no `enabled`): Task 1. ✓
- Perf as generic hooks (perfLine / renderHeaviestSteps / recordPerfSummary+splice), content on Core: Task 5 (hooks) + Task 7 (Core feeds `renderBreakdown` text). ✓
- Config as library value type; Core reads YAML → `(enabled, FileRunLogConfig)`: Task 6. ✓
- Identity via mode-at-open + generic `recordRunFact`: Task 2 (`open` mode) + Task 4 (`recordRunFact`) + Task 7 (`recordRunFact("org", …)`). ✓
- `RunLogSinkHandle` deleted, nullability = isReal: Task 2 (`openOrNoOp: FileRunLogSink?`) + Task 7 (delete + null-checks). ✓
- `PerTestRunLogSink.java` + test deleted, coverage ported: Task 7 (delete) + Tasks 2–5 (ported tests). ✓
- E2E parity check: Task 8. ✓
- Out-of-scope respected: no `RunLogRenderer`/`RunLogSink`/`StepEvent`/grammar change; no config-schema/file-layout change; perf breakdown stays on Core; publish optional. ✓

**Placeholder scan:** No TBD/TODO; every code step carries full code; every command has an expected result. ✓

**Type consistency:** `FileRunLogConfig(libLogs, steps, perf, outcome, runbook, heaviestSteps: Int)` used identically in Tasks 1/2/3/5/6/7. `FileRunLogSink.open`/`openOrNoOp(logsDir, runLabel, mode, startedAt, config)` consistent Tasks 2/7. `recordRunFact(key, value)` Tasks 4/7. `recordPerfSummary(block)` / `renderHeaviestSteps(topN)` / `perfLine(line)` Tasks 5/7. `RunLogConfig(enabled, content)` Tasks 6/7. One explicitly-flagged uncertainty: the Kotlin→Java accessor spelling for boolean vals (`getLibLogs()` vs `isLibLogs()`, `content().heaviestSteps()` vs `getHeaviestSteps()`) — Tasks 6/7 note to confirm at compile and adjust. ✓

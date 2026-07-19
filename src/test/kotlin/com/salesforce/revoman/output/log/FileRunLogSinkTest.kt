/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase
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

  private fun runFile(logsDir: Path, label: String): Path = logsDir.resolve(label).resolve(stamp)

  @Test
  fun `banner sits at line 1 with identity and legend`(@TempDir logsDir: Path) {
    val sink =
      FileRunLogSink.open(
        logsDir,
        "UnifiedValidationE2ETest.getSlots",
        "External",
        ts,
        FileRunLogConfig.DEFAULT_ALL,
      )
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
    sink.event(
      StepEvent.RunbookStepStarted("seed SAs", "seed SAs", Phase.SEED, setOf("policyId"), false)
    )
    sink.event(
      StepEvent.RunbookStepFinished(
        "seed SAs",
        "seed SAs",
        Outcome.SUCCESS,
        mapOf("saId1" to "a07xx"),
        812L,
      )
    )
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
    sink.event(
      StepEvent.RunbookContractFailed(
        "validate",
        "validate",
        setOf("saId1"),
        emptySet(),
        emptyMap(),
      )
    )
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldContain "⚠ CONTRACT"
    body shouldContain "missing consumed: [saId1]"
  }

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
    body shouldContain
      "Caused by: java.lang.IllegalStateException: DB partition specifier not cleared"
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
}

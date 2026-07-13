/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

class ConsoleRunLogSinkTest {
  private val buffer = ByteArrayOutputStream()
  private val sink = ConsoleRunLogSink(PrintStream(buffer, true, Charsets.UTF_8))

  private fun output(): String = buffer.toString(Charsets.UTF_8)

  @Test
  fun `PhaseEntered renders phase rule`() {
    sink.event(StepEvent.PhaseEntered(Phase.SEED))
    output() shouldStartWith "━━ SEED "
    output() shouldContain "━━━━"
  }

  @Test
  fun `RunbookStepStarted renders with regular marker when not under test`() {
    sink.event(
      StepEvent.RunbookStepStarted(
        path = "schedule",
        intent = "schedule appointment",
        phase = Phase.ACT,
        consumes = setOf("accountId"),
        underTest = false,
      )
    )
    output() shouldContain "┌ ▶ schedule appointment"
    output() shouldContain "⟵ accountId"
    output() shouldNotContain "★ UNDER TEST"
  }

  @Test
  fun `RunbookStepStarted renders with diamond marker and under test flag when underTest`() {
    sink.event(
      StepEvent.RunbookStepStarted(
        path = "schedule",
        intent = "schedule appointment",
        phase = Phase.ACT,
        consumes = setOf("accountId", "slotId"),
        underTest = true,
      )
    )
    output() shouldContain "┌ ◆ schedule appointment"
    output() shouldContain "⟵ accountId, slotId"
    output() shouldContain "★ UNDER TEST"
  }

  @Test
  fun `RunbookStepStarted with empty consumes shows dash`() {
    sink.event(
      StepEvent.RunbookStepStarted(
        path = "schedule",
        intent = "schedule appointment",
        phase = Phase.ACT,
        consumes = emptySet(),
        underTest = false,
      )
    )
    output() shouldContain "⟵ —"
  }

  @Test
  fun `RunbookStepFinished renders success with checkmark and produced values`() {
    sink.event(
      StepEvent.RunbookStepFinished(
        path = "schedule",
        intent = "schedule appointment",
        outcome = Outcome.SUCCESS,
        produced = mapOf("schedulingStatus" to "Success", "saId" to "SA123"),
        tookMs = 5,
      )
    )
    output() shouldContain "└ ✔ schedule appointment"
    output() shouldContain "⟶ schedulingStatus=Success, saId=SA123"
  }

  @Test
  fun `RunbookStepFinished renders failure with cross mark`() {
    sink.event(
      StepEvent.RunbookStepFinished(
        path = "schedule",
        intent = "schedule appointment",
        outcome = Outcome.FAILED,
        produced = emptyMap(),
        tookMs = 10,
      )
    )
    output() shouldContain "└ ✘ schedule appointment"
    output() shouldContain "⟶ —"
  }

  @Test
  fun `RunbookStepFinished with null produced value shows key only`() {
    sink.event(
      StepEvent.RunbookStepFinished(
        path = "schedule",
        intent = "schedule appointment",
        outcome = Outcome.SUCCESS,
        produced = mapOf("schedulingStatus" to null, "saId" to "SA123"),
        tookMs = 5,
      )
    )
    output() shouldContain "⟶ schedulingStatus, saId=SA123"
  }

  @Test
  fun `RunbookContractFailed renders with warning marker and details`() {
    sink.event(
      StepEvent.RunbookContractFailed(
        path = "schedule",
        intent = "schedule appointment",
        missingConsumed = setOf("accountId"),
        missingProduced = setOf("schedulingStatus"),
        valueMismatches = emptyMap(),
      )
    )
    output() shouldContain "│ ⚠ CONTRACT"
    output() shouldContain "missing consumed: [accountId]"
    output() shouldContain "missing produced: [schedulingStatus]"
  }

  @Test
  fun `RunbookContractFailed with value mismatches`() {
    sink.event(
      StepEvent.RunbookContractFailed(
        path = "schedule",
        intent = "schedule appointment",
        missingConsumed = emptySet(),
        missingProduced = emptySet(),
        valueMismatches = mapOf("status" to Pair("expected", "actual")),
      )
    )
    output() shouldContain "│ ⚠ CONTRACT"
    output() shouldContain "value mismatch (expected→actual): {status=(expected, actual)}"
  }

  @Test
  fun `StepStarted renders as nested child request with gutter`() {
    sink.event(StepEvent.StepStarted(path = "10-book", name = "Book Appointment"))
    output() shouldStartWith "│ · "
    output() shouldContain "Book Appointment"
  }

  @Test
  fun `StepFinished renders with gutter and outcome word`() {
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
    output() shouldStartWith "│   "
    output() shouldContain "200 OK 42ms"
  }

  @Test
  fun `StepFinished with FAILED outcome shows FAIL word`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = 400,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 5,
        outcome = Outcome.FAILED,
      )
    )
    output() shouldContain "400 FAIL 5ms"
  }

  @Test
  fun `StepFinished with SKIPPED outcome shows SKIP word`() {
    sink.event(
      StepEvent.StepFinished(
        path = "10-book",
        httpStatus = null,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 0,
        outcome = Outcome.SKIPPED,
      )
    )
    output() shouldContain "null SKIP 0ms"
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
  fun `StepFinished emits REQ and RESP blocks with gutter when messages present`() {
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
    output() shouldContain "│ REQ:\nPOST /book"
    output() shouldContain "│ RESP:\n{\"error\":\"bad\"}"
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
    output() shouldNotContain "⟵"
    output() shouldNotContain "⟶"
  }

  @Test
  fun `StepFinished emits keys line with arrows when produced or consumed present`() {
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
    output() shouldContain "│   ⟵ [token]  ⟶ [saId]"
  }

  @Test
  fun `LedgerSkipped renders with gutter and circular arrow`() {
    sink.event(StepEvent.LedgerSkipped(path = "10-book", reused = setOf("saId")))
    output() shouldContain "│ ↺ reused [saId]"
  }

  @Test
  fun `RequestSkipped renders with gutter and skip symbol`() {
    sink.event(StepEvent.RequestSkipped(path = "10-book"))
    output() shouldContain "│ ⊘ skipped 10-book"
  }

  @Test
  fun `Jumped renders with gutter and jump arrow`() {
    sink.event(StepEvent.Jumped(path = "10-book", toPath = "30-verify"))
    output() shouldContain "│ ↪ 10-book → 30-verify"
  }

  @Test
  fun `RunStopped renders with stop square`() {
    sink.event(StepEvent.RunStopped(path = "10-book", reason = "setNextRequest(null)"))
    output() shouldContain "■ STOP 10-book: setNextRequest(null)"
  }

  @Test
  fun `LoopBudgetExceeded renders with cross mark`() {
    sink.event(StepEvent.LoopBudgetExceeded(path = "10-book", budget = 100))
    output() shouldContain "✖ LOOP-BUDGET 10-book budget=100"
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
    output() shouldContain "│ ⊘ skipped 20-after-close"
  }

  @Test
  fun `DEFAULT is a shared reusable instance`() {
    // Same singleton on every access — callers reuse it instead of allocating per Kick.
    ConsoleRunLogSink.DEFAULT shouldBeSameInstanceAs ConsoleRunLogSink.DEFAULT
    // Renders to System.out without throwing (honors the never-throw contract).
    ConsoleRunLogSink.DEFAULT.event(StepEvent.RequestSkipped(path = "30-default"))
  }
}

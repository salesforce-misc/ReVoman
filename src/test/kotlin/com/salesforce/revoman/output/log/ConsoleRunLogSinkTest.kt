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
import io.kotest.matchers.types.shouldBeSameInstanceAs
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

  @Test
  fun `DEFAULT is a shared reusable instance`() {
    // Same singleton on every access — callers reuse it instead of allocating per Kick.
    ConsoleRunLogSink.DEFAULT shouldBeSameInstanceAs ConsoleRunLogSink.DEFAULT
    // Renders to System.out without throwing (honors the never-throw contract).
    ConsoleRunLogSink.DEFAULT.event(StepEvent.RequestSkipped(path = "30-default"))
  }
}

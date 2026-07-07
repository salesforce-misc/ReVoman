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

  /**
   * No-op: KotlinLogging already emits teed narration lines; printing them here would duplicate.
   */
  override fun line(level: LogLevel, message: String) {}

  override fun event(event: StepEvent) {
    runCatching { out.print(render(event)) }
  }

  /**
   * No-op: the caller owns the sink's lifecycle and this sink never closes a stream it borrowed.
   */
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
    val header =
      "── STEP ${event.path} [${event.httpStatus}] ${event.outcome} (${event.tookMs}ms)\n"
    val keys =
      if (event.produced.isEmpty() && event.consumed.isEmpty()) ""
      else "   produced=${event.produced}  consumed=${event.consumed}\n"
    val req = event.requestMsg?.let { "REQ:\n$it\n" } ?: ""
    val resp = event.responseMsg?.let { "RESP:\n$it\n" } ?: ""
    return header + keys + req + resp
  }
}

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
 * [com.salesforce.revoman.input.config.Kick] via `runLogSink(ConsoleRunLogSink.DEFAULT)` (the
 * shared [System.out] instance) or `runLogSink(ConsoleRunLogSink(myStream))` for a custom stream,
 * to tee each step's boundary event (and, for a finished step, its full HTTP request/response) to
 * stdout so the exchange shows up in a JUnit/Gradle log.
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

  companion object {
    /**
     * Shared, reusable instance writing to [System.out]. Reference this from a
     * [com.salesforce.revoman.input.config.Kick]'s `runLogSink` instead of allocating a new
     * [ConsoleRunLogSink] per Kick — the sink is stateless (its [out] is `final`, [line]/[close]
     * are no-ops), so a single instance is safe to share across every Kick and revUp run.
     */
    @JvmField val DEFAULT: ConsoleRunLogSink = ConsoleRunLogSink()

    /** Fixed width for phase-rule horizontal lines. */
    private const val RULE_WIDTH = 52
  }

  override fun event(event: StepEvent) {
    runCatching { out.print(render(event)) }
  }

  /**
   * No-op: the caller owns the sink's lifecycle and this sink never closes a stream it borrowed.
   */
  override fun close() {}

  private fun render(event: StepEvent): String =
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

  /**
   * Renders a phase boundary as a horizontal rule filled to [RULE_WIDTH] characters. Example: `━━
   * SEED ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n`
   */
  private fun phaseRule(name: String): String {
    val prefix = "━━ $name "
    val fill = (RULE_WIDTH - prefix.length).coerceAtLeast(3)
    return prefix + "━".repeat(fill) + "\n"
  }

  /**
   * Renders a runbook step's opening line: `┌ <marker> <intent> ⟵ <consumes>` + optional `★ UNDER
   * TEST`. Marker is `◆` when [StepEvent.RunbookStepStarted.underTest], else `▶`.
   */
  private fun renderStepOpen(e: StepEvent.RunbookStepStarted): String {
    val marker = if (e.underTest) "◆" else "▶"
    val consumes = if (e.consumes.isEmpty()) "—" else e.consumes.joinToString(", ")
    val underTest = if (e.underTest) "   ★ UNDER TEST" else ""
    return "┌ $marker ${e.intent}          ⟵ $consumes$underTest\n"
  }

  /**
   * Renders a runbook step's closing line: `└ <✔ or ✘> <intent> ⟶ <produced>`. Marker is `✘` when
   * [StepEvent.RunbookStepFinished.outcome] is [Outcome.FAILED], else `✔`.
   */
  private fun renderStepClose(e: StepEvent.RunbookStepFinished): String {
    val marker = if (e.outcome == Outcome.FAILED) "✘" else "✔"
    val produced =
      if (e.produced.isEmpty()) "—"
      else e.produced.entries.joinToString(", ") { (k, v) -> if (v == null) k else "$k=$v" }
    return "└ $marker ${e.intent}          ⟶ $produced\n"
  }

  /**
   * Renders a contract violation: `│ ⚠ CONTRACT <detail>` where detail lists missing
   * consumed/produced keys and value mismatches.
   */
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

  /**
   * Renders a child request step's finished line: `│ <status> <OK|FAIL|SKIP> <ms>ms` + optional
   * consumed/produced keys + optional REQ/RESP blocks (all indented with `│` gutter).
   */
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

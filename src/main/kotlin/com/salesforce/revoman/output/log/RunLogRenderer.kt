/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/**
 * The single source of truth for rendering a [StepEvent] to its one-or-more display lines. Shared
 * by EVERY [RunLogSink] (the library [ConsoleRunLogSink] and any consumer sink, e.g. Core's
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

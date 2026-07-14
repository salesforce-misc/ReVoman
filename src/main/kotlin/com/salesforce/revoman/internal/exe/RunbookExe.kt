/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.internal.log.RevomanLog
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.output.RunbookRundown
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.log.Outcome
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.output.renderRunbookMarkdown

/**
 * Drives a [Runbook] over the existing single-kick [ReVoman.revUp], threading env exactly as the
 * multi-kick fold (ReVoman.kt) does, and interleaving coarse log events + data-flow contract
 * checks + per-step assertions. A contract/assert breach always throws [AssertionError] (halt); an
 * underlying-collection step failure halts or is recorded-and-continued per
 * [Runbook.haltOnStepFailure].
 */
internal fun executeRunbook(
  runbook: Runbook,
  dynamicEnvironment: Map<String, Any?>,
): RunbookRundown {
  val runbookSink = runbook.runLogSink
  // Install the runbook-scope sink ONLY when it is a real sink. For the NoOp default there is
  // nothing to route to, and skipping the install keeps `current()` null so the zero-config path
  // stays bit-identical to before this layer existed (e.g. the summary line below renders lazily).
  val installed = runbookSink !== RunLogSink.NoOp
  val previousSink = if (installed) RunLogContext.install(runbookSink) else null
  try {
    // Immutable state transformation via fold (mirrors the multi-kick fold in ReVoman.kt): each
    // step yields a NEW accumulator (threaded env, last phase, accumulated (step, rundown) pairs).
    // A halt propagates as a thrown exception, aborting the fold just like the former `.map`.
    val finalAcc =
      runbook.steps.fold(RunbookAcc(dynamicEnvironment, null, emptyList())) { acc, step ->
        executeStep(runbook, runbookSink, step, acc)
      }
    val result = RunbookRundown(runbook.name, finalAcc.pairs)
    RevomanLog.info { "\n" + renderRunbookMarkdown(result) }
    return result
  } finally {
    if (installed) RunLogContext.restore(previousSink)
  }
}

/**
 * The fold accumulator threaded across runbook steps: the env carried forward (all value types),
 * the [Phase] of the previous step (to emit a [StepEvent.PhaseEntered] only on change), and the
 * ordered (step, rundown) pairs produced so far.
 */
private data class RunbookAcc(
  val env: Map<String, Any?>,
  val lastPhase: Phase?,
  val pairs: List<Pair<RunbookStep, Rundown>>,
)

/**
 * Executes ONE runbook step and returns the next [RunbookAcc]. Emits exactly one
 * [StepEvent.RunbookStepFinished] per step, always, carrying the correct [Outcome]: any throw
 * between the open bracket and the normal close routes through the `catch` which emits a FAILED
 * close before rethrowing (so no halt path leaves a dangling `┌` open with no `└` close).
 *
 * Outcome is derived, never hardcoded. The underlying-collection failure is checked FIRST — before
 * produces/assertAfter — because a failed collection makes those downstream checks meaningless and
 * their messages misleading.
 */
@Suppress("TooGenericExceptionCaught")
private fun executeStep(
  runbook: Runbook,
  runbookSink: RunLogSink,
  step: RunbookStep,
  acc: RunbookAcc,
): RunbookAcc {
  emitStepOpen(step, acc.lastPhase)
  val startNs = System.nanoTime()
  val close = StepCloseGuard(step, startNs)
  // A catch-all is intentional here: the bracket MUST be closed on EVERY throw between the open
  // and the normal close — an AssertionError from a contract/assertAfter breach, a step-failure
  // halt, or any error out of `revUp` — after which we rethrow verbatim (see [StepCloseGuard]).
  try {
    return runStepBody(runbook, runbookSink, step, acc, startNs, close)
  } catch (t: Throwable) {
    close.emitFailedIfOpen()
    throw t
  }
}

/** Emits the phase rule (only on a phase change) and the step-open bracket for [step]. */
private fun emitStepOpen(step: RunbookStep, lastPhase: Phase?) {
  if (step.phase != lastPhase) {
    RevomanLog.event(StepEvent.PhaseEntered(step.phase))
  }
  RevomanLog.event(
    StepEvent.RunbookStepStarted(
      step.intent,
      step.intent,
      step.phase,
      step.consumes,
      step.underTest,
    )
  )
}

/**
 * Runs [step]'s kick and resolves its outcome into the next [RunbookAcc]. Underlying-collection
 * failure is checked FIRST — before produces/assertAfter — because a failed collection makes those
 * downstream checks meaningless and their messages misleading. Outcome is derived, never hardcoded.
 */
private fun runStepBody(
  runbook: Runbook,
  runbookSink: RunLogSink,
  step: RunbookStep,
  acc: RunbookAcc,
  startNs: Long,
  close: StepCloseGuard,
): RunbookAcc {
  checkConsumesOrHalt(step, acc.env.keys)
  // Thread the runbook sink into the kick so its child per-request events nest under this runbook's
  // brackets (same sink instance = coherent tree). Only when the runbook has a real sink —
  // otherwise leave the kick's own sink untouched (backward compat).
  val kickToRun =
    step.kick.overrideDynamicEnvironment(step.kick.dynamicEnvironment() + acc.env).let {
      if (runbookSink !== RunLogSink.NoOp) it.overrideRunLogSink(runbookSink) else it
    }
  val rundown = ReVoman.revUp(kickToRun)
  val tookMs = (System.nanoTime() - startNs) / 1_000_000
  val nextAcc =
    RunbookAcc(rundown.mutableEnv.immutableEnv, step.phase, acc.pairs + (step to rundown))
  val failedReport = rundown.firstUnIgnoredUnsuccessfulStepReport
  return when {
    failedReport != null -> {
      close.emit(Outcome.FAILED, producedValues(step, rundown), tookMs)
      if (runbook.haltOnStepFailure) {
        throw AssertionError(
          "Runbook step '${step.intent}' failed: underlying collection step " +
            "'${failedReport.step.path}' was unsuccessful (stopReason=${rundown.stopReason})"
        )
      }
      // Continue: keep the (step, rundown) pair and thread env forward; do NOT emit a SUCCESS
      // close. The caller inspects the returned RunbookRundown (mirrors base revUp(List<Kick>)).
      nextAcc
    }
    else -> {
      checkProducesOrHalt(step, rundown)
      step.assertAfter?.assertStep(rundown, rundown.mutableEnv)
      close.emit(Outcome.SUCCESS, producedValues(step, rundown), tookMs)
      nextAcc
    }
  }
}

/**
 * Guarantees EXACTLY ONE [StepEvent.RunbookStepFinished] close per step. The normal paths call
 * [emit] with the derived outcome; any throw before that routes through [emitStepOpen]'s caller
 * catch, which calls [emitFailedIfOpen] to close a still-open bracket with [Outcome.FAILED] (so no
 * halt path leaves a dangling `┌` with no `└`). The [StepEvent.RunbookContractFailed] detail line
 * is emitted separately by the check helpers.
 */
private class StepCloseGuard(private val step: RunbookStep, private val startNs: Long) {
  private var emitted = false

  fun emit(outcome: Outcome, produced: Map<String, String?>, tookMs: Long) {
    RevomanLog.event(
      StepEvent.RunbookStepFinished(step.intent, step.intent, outcome, produced, tookMs)
    )
    emitted = true
  }

  fun emitFailedIfOpen() {
    if (!emitted) {
      val tookMs = (System.nanoTime() - startNs) / 1_000_000
      emit(Outcome.FAILED, emptyMap(), tookMs)
    }
  }
}

private fun producedValues(step: RunbookStep, rundown: Rundown): Map<String, String?> =
  step.produces.keys.associateWith { rundown.mutableEnv.getAsString(it) }

private fun checkConsumesOrHalt(step: RunbookStep, envKeys: Set<String>) {
  val missing = checkConsumes(step, envKeys)
  if (missing.isNotEmpty()) {
    RevomanLog.event(
      StepEvent.RunbookContractFailed(step.intent, step.intent, missing, emptySet(), emptyMap())
    )
    throw AssertionError("Runbook step '${step.intent}' missing consumed env keys: $missing")
  }
}

private fun checkProducesOrHalt(step: RunbookStep, rundown: Rundown) {
  val violation = checkProduces(step, rundown.mutableEnv)
  if (!violation.isEmpty()) {
    // Emit the CONTRACT detail line only; the FAILED close bracket is emitted by executeStep's
    // catch, so there is exactly one RunbookStepFinished per step.
    RevomanLog.event(
      StepEvent.RunbookContractFailed(
        step.intent,
        step.intent,
        emptySet(),
        violation.missingProduced,
        violation.valueMismatches,
      )
    )
    throw AssertionError(
      "Runbook step '${step.intent}' contract breach — missing produced: ${violation.missingProduced}, " +
        "value mismatches (expected→actual): ${violation.valueMismatches}"
    )
  }
}

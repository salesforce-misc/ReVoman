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
 * checks + per-step assertions. First breach throws [AssertionError] (halt).
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
    var accumulatedEnv: Map<String, Any?> = dynamicEnvironment
    var lastPhase: Phase? = null
    val pairs =
      runbook.steps.map { step ->
        if (step.phase != lastPhase) {
          RevomanLog.event(StepEvent.PhaseEntered(step.phase))
          lastPhase = step.phase
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
        checkConsumesOrHalt(step, accumulatedEnv.keys)
        val startNs = System.nanoTime()
        // Thread the runbook sink into the kick so its child per-request events nest under
        // this runbook's brackets (same sink instance = coherent tree). Only when the runbook
        // has a real sink — otherwise leave the kick's own sink untouched (backward compat).
        val kickToRun =
          step.kick
            .overrideDynamicEnvironment(step.kick.dynamicEnvironment() + accumulatedEnv)
            .let { if (runbookSink !== RunLogSink.NoOp) it.overrideRunLogSink(runbookSink) else it }
        val rundown = ReVoman.revUp(kickToRun)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000
        checkProducesOrHalt(step, rundown, tookMs)
        step.assertAfter?.assertStep(rundown, rundown.mutableEnv)
        RevomanLog.event(
          StepEvent.RunbookStepFinished(
            step.intent,
            step.intent,
            Outcome.SUCCESS,
            producedValues(step, rundown),
            tookMs,
          )
        )
        accumulatedEnv = rundown.mutableEnv.immutableEnv
        step to rundown
      }
    val result = RunbookRundown(runbook.name, pairs)
    RevomanLog.info { "\n" + renderRunbookMarkdown(result) }
    return result
  } finally {
    if (installed) RunLogContext.restore(previousSink)
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

private fun checkProducesOrHalt(step: RunbookStep, rundown: Rundown, tookMs: Long) {
  val violation = checkProduces(step, rundown.mutableEnv)
  if (!violation.isEmpty()) {
    RevomanLog.event(
      StepEvent.RunbookContractFailed(
        step.intent,
        step.intent,
        emptySet(),
        violation.missingProduced,
        violation.valueMismatches,
      )
    )
    RevomanLog.event(
      StepEvent.RunbookStepFinished(step.intent, step.intent, Outcome.FAILED, emptyMap(), tookMs)
    )
    throw AssertionError(
      "Runbook step '${step.intent}' contract breach — missing produced: ${violation.missingProduced}, " +
        "value mismatches (expected→actual): ${violation.valueMismatches}"
    )
  }
}

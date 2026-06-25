/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport

/** A step's control-flow intent, derived from its `pm.execution.setNextRequest` directive. */
internal sealed interface StepDirective {
  /** No directive — advance to the next step linearly. */
  object None : StepDirective

  /** `setNextRequest(null)` — stop the run. */
  object Stop : StepDirective

  /** `setNextRequest(name)` — jump to the step matching [target]. */
  data class Jump(val target: String) : StepDirective
}

/**
 * Derives the control-flow directive from a finished step's report.
 * - `nextRequestSet == false` → [StepDirective.None] (no directive).
 * - set with a null/blank name → [StepDirective.Stop].
 * - set with a name → [StepDirective.Jump].
 */
internal fun directiveOf(report: StepReport): StepDirective =
  when {
    !report.nextRequestSet -> StepDirective.None
    report.nextRequest.isNullOrBlank() -> StepDirective.Stop
    else -> StepDirective.Jump(report.nextRequest)
  }

/**
 * Resolves a jump [target] to an index in [pickedSteps] (the execution universe). Returns the index
 * of the FIRST step whose [Step.stepNameMatches] is true, or null when no picked step matches
 * (target was filtered out by run/skip picks, or is a typo). The caller warns + advances linearly
 * on null. [fromCursor] is unused for matching but documents the jump origin for callers.
 */
internal fun resolveTarget(target: String, pickedSteps: List<Step>, fromCursor: Int): Int? {
  val idx = pickedSteps.indexOfFirst { it.stepNameMatches(target) }
  return if (idx >= 0) idx else null
}

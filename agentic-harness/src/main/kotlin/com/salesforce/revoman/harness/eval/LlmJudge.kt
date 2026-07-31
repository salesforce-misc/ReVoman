/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.output.Rundown

/** A judge's verdict on a fuzzy metric — advisory, used only to screen. */
data class Judgment(val pass: Boolean, val reason: String)

/**
 * LLM-as-judge for fuzzy metrics (response helpfulness / trajectory quality). Bounded on purpose:
 * it only SCREENS. The gate is always the deterministic [GroundTruth]. A real implementation would
 * call Claude here; the [StubJudge] is the deterministic stand-in for CI.
 */
interface LlmJudge {
  fun judge(utterance: String, responseContext: String): Judgment
}

/** Deterministic screening judge: "helpful" iff the response context reports a clean run. */
class StubJudge : LlmJudge {
  override fun judge(utterance: String, responseContext: String): Judgment {
    val clean =
      responseContext.contains("\"unsuccessfulStepCount\":0") &&
        responseContext.contains("\"succeeded\":true")
    return if (clean) Judgment(true, "context reports all steps successful")
    else Judgment(false, "context does not report a clean, successful run")
  }
}

/**
 * The deterministic ground-truth gate that never drifts. Prefer this over the judge wherever a
 * deterministic check exists; the judge only covers what this cannot.
 */
object GroundTruth {
  fun allStepsSucceeded(rundowns: List<Rundown>): Boolean =
    rundowns.isNotEmpty() && rundowns.all { it.areAllStepsSuccessful }
}

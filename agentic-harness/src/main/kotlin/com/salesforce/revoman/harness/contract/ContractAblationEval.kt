/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.harness.eval.EvalCase
import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.tooldef.ToolDef

/** Graph-selection accuracy for one contract variant on a fixed eval set. */
data class AblationResult(val variantName: String, val accuracyCorrect: Int, val accuracyTotal: Int) {
  val accuracy: Double
    get() = if (accuracyTotal == 0) 0.0 else accuracyCorrect.toDouble() / accuracyTotal
}

/**
 * The blue-box efficacy eval: hold the reasoning fixed, vary the CONTRACT content, and measure the
 * accuracy delta on the same labeled eval set — reusing the Stage-3 confusion matrix as the
 * instrument. This grades the contract by how well it makes the reasoning work, and is the
 * generalization of the manual calibration loop: any difference between two `ToolDef` variants
 * (richer descriptions, added examples, disambiguating clauses) is measured, not guessed.
 */
class ContractAblationEval(
  private val evaluator: RouterEvaluator = RouterEvaluator(ScoringLlmClient())
) {
  fun compare(
    cases: List<EvalCase>,
    variantA: Pair<String, List<ToolDef>>,
    variantB: Pair<String, List<ToolDef>>,
  ): Pair<AblationResult, AblationResult> = result(cases, variantA) to result(cases, variantB)

  private fun result(cases: List<EvalCase>, variant: Pair<String, List<ToolDef>>): AblationResult {
    val report = evaluator.evaluate(cases, variant.second)
    return AblationResult(variant.first, report.matrix.correct, report.matrix.total)
  }
}

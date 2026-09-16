/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.orchestrator.Router
import com.salesforce.revoman.harness.tooldef.ToolDef

/** A single graph-selection mismatch from an eval run. */
data class Miss(val utterance: String, val expected: String, val predicted: String?)

/** The outcome of running the router over an eval set: the confusion matrix plus its misses. */
data class EvalReport(val matrix: ConfusionMatrix, val misses: List<Miss>) {
  val accuracy: Double
    get() = matrix.accuracy
}

/**
 * Runs the router over a labeled eval set and produces a confusion matrix + the list of misses.
 * This is the design's Layer-2 graph-selection eval: run against gold, read the matrix, and turn
 * each off-diagonal cell into a `when_not_to_use` clause. Re-running with the calibrated tool defs
 * moves the number — the calibration loop, made measurable.
 */
class RouterEvaluator(private val llm: LlmClient) {
  fun evaluate(cases: List<EvalCase>, tools: List<ToolDef>): EvalReport {
    val router = Router(llm, tools)
    val results = cases.map { it to router.route(it.utterance).graphName }
    val matrix = ConfusionMatrices.from(results.map { (case, predicted) -> case.expected to predicted })
    val misses =
      results
        .filter { (case, predicted) -> case.expected != predicted }
        .map { (case, predicted) -> Miss(case.utterance, case.expected, predicted) }
    return EvalReport(matrix, misses)
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.salesforce.revoman.harness.eval.EvalCase

/** What a nightly batch produces: the grown eval set and clauses drafted per wrongly-chosen graph. */
data class BatchOutput(
  val grownEvalSet: List<EvalCase>,
  val draftedClauses: Map<String, List<String>>,
)

/**
 * The nightly feedback batch: turns the confirm-gate labels into a bigger eval set and drafted
 * `when_not_to_use` clauses — the flywheel that makes the CI gate tighten itself over time.
 * Confirmed turns become positive golden cases; rejects with a known-right graph become both a
 * regression case (so that exact miss never returns) and a drafted clause on the graph that was
 * wrongly chosen. Slot corrections are slot-fill training data and do not affect router selection.
 */
object NightlyBatch {
  fun run(seedEvalSet: List<EvalCase>, labels: List<FeedbackLabel>): BatchOutput {
    val seenUtterances = seedEvalSet.map { it.utterance }.toMutableSet()
    val grown = seedEvalSet.toMutableList()
    val clauses = mutableMapOf<String, MutableList<String>>()

    labels.forEach { label ->
      when (label) {
        is FeedbackLabel.Positive ->
          addCase(grown, seenUtterances, label.proposal.utterance, label.proposal.graph)
        is FeedbackLabel.Negative -> {
          val correct = label.correctGraph ?: return@forEach
          addCase(grown, seenUtterances, label.proposal.utterance, correct)
          val clause =
            "Do not use for requests like \"${label.proposal.utterance}\" — " +
              "that is the $correct graph."
          clauses.getOrPut(label.proposal.graph) { mutableListOf() }.add(clause)
        }
        is FeedbackLabel.CorrectionPair -> Unit // slot-fill signal; not a router-selection change
      }
    }
    return BatchOutput(grown.toList(), clauses.mapValues { it.value.toList() })
  }

  private fun addCase(
    into: MutableList<EvalCase>,
    seen: MutableSet<String>,
    utterance: String,
    graph: String,
  ) {
    if (seen.add(utterance)) into.add(EvalCase(utterance, graph))
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.eval.EvalCase
import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class FlywheelClosesLoopTest {
  private val evaluator = RouterEvaluator(ScoringLlmClient())
  private val seedTools = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }

  @Test
  fun `a rejected near-miss, mined by the nightly batch, tightens the CI gate`() {
    // The confirm gate rejected the near-miss turn, stating the correct graph is price.
    val labels =
      listOf(
        FeedbackLabel.Negative(
          Proposal("quote me a price for this config", "quote", emptyMap()),
          correctGraph = "price",
        )
      )
    val seedEval = listOf(EvalCase("create a draft quote for prc-2", "quote"))

    val batch = NightlyBatch.run(seedEval, labels)
    // The batch grew the eval set with the regression case and drafted a clause on 'quote'.
    assertThat(batch.grownEvalSet).contains(EvalCase("quote me a price for this config", "price"))
    val drafted = batch.draftedClauses["quote"]!!

    // Apply the drafted clause to the quote tool = the calibrated tools.
    val calibratedTools =
      seedTools.map { if (it.graphName == "quote") it.copy(whenNotToUse = drafted) else it }

    val before = evaluator.evaluate(batch.grownEvalSet, seedTools)
    val after = evaluator.evaluate(batch.grownEvalSet, calibratedTools)

    // The auto-drafted clause strictly improves accuracy on the grown eval set — the flywheel works.
    assertThat(after.matrix.correct).isGreaterThan(before.matrix.correct)
    assertThat(after.misses).isEmpty()
  }
}

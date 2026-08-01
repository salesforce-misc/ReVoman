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
import org.junit.jupiter.api.Test

class NightlyBatchTest {
  private val seed = listOf(EvalCase("configure 2 units of SKU-1", "configure"))

  @Test
  fun `confirmed turns append new golden cases to the eval set`() {
    val labels =
      listOf(
        FeedbackLabel.Positive(Proposal("price configuration cfg-1", "price", mapOf("configId" to "cfg-1")))
      )
    val out = NightlyBatch.run(seed, labels)
    assertThat(out.grownEvalSet).hasSize(2)
    assertThat(out.grownEvalSet).contains(EvalCase("price configuration cfg-1", "price"))
  }

  @Test
  fun `a reject with a known correct graph drafts a when_not_to_use clause and a regression case`() {
    val labels =
      listOf(
        FeedbackLabel.Negative(
          Proposal("quote me a price for this config", "quote", emptyMap()),
          correctGraph = "price",
        )
      )
    val out = NightlyBatch.run(seed, labels)
    // A regression eval case pinning the correct answer.
    assertThat(out.grownEvalSet).contains(EvalCase("quote me a price for this config", "price"))
    // A drafted clause on the WRONGLY-chosen graph (quote), pointing at the correct one (price).
    assertThat(out.draftedClauses.keys).contains("quote")
    assertThat(out.draftedClauses["quote"]!!.single()).contains("price")
  }

  @Test
  fun `a slot correction does not change the eval set or clauses`() {
    val labels =
      listOf(
        FeedbackLabel.CorrectionPair(
          Proposal("configure 2 units of SKU-1", "configure", mapOf("quantity" to "2")),
          correctedSlots = mapOf("quantity" to "5"),
        )
      )
    val out = NightlyBatch.run(seed, labels)
    assertThat(out.grownEvalSet).isEqualTo(seed)
    assertThat(out.draftedClauses).isEmpty()
  }

  @Test
  fun `dedupes an already-present utterance`() {
    val labels =
      listOf(FeedbackLabel.Positive(Proposal("configure 2 units of SKU-1", "configure", emptyMap())))
    val out = NightlyBatch.run(seed, labels)
    assertThat(out.grownEvalSet).hasSize(1)
  }
}

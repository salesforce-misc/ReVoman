/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConfirmGateTest {
  private val proposal =
    Proposal("configure 2 units of SKU-1", "configure", mapOf("productCode" to "SKU-1", "quantity" to "2"))

  @Test
  fun `confirm yields a positive label`() {
    val label = ConfirmGate.apply(proposal, Decision.Confirm)
    assertThat(label).isInstanceOf(FeedbackLabel.Positive::class.java)
    assertThat((label as FeedbackLabel.Positive).proposal).isEqualTo(proposal)
  }

  @Test
  fun `edit yields a correction pair carrying the fixed slots`() {
    val fixed = mapOf("productCode" to "SKU-1", "quantity" to "5")
    val label = ConfirmGate.apply(proposal, Decision.Edit(fixed))
    assertThat(label).isInstanceOf(FeedbackLabel.CorrectionPair::class.java)
    val pair = label as FeedbackLabel.CorrectionPair
    assertThat(pair.proposal.slots).containsEntry("quantity", "2")
    assertThat(pair.correctedSlots).containsEntry("quantity", "5")
  }

  @Test
  fun `reject yields a negative label carrying the human's correct graph`() {
    val label = ConfirmGate.apply(proposal, Decision.Reject(correctGraph = "price"))
    assertThat(label).isInstanceOf(FeedbackLabel.Negative::class.java)
    assertThat((label as FeedbackLabel.Negative).correctGraph).isEqualTo("price")
  }
}

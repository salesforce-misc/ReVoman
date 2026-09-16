/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.orchestrator.FillResult
import org.junit.jupiter.api.Test

class DisambiguationGateTest {
  private val gate = DisambiguationGate()

  @Test
  fun `no route yields NoMatch`() {
    val outcome = gate.decide(RouteDecision(null, "none", 0.0, 0.0), null, FillResult.Valid(emptyMap()), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.NoMatch::class.java)
  }

  @Test
  fun `low margin asks instead of guessing`() {
    // Confident-ish confidence but a tiny margin between the top two graphs.
    val decision = RouteDecision("configure", "close call", confidence = 0.55, margin = 0.10)
    val outcome =
      gate.decide(decision, secondBestGraph = "price", FillResult.Valid(mapOf("productCode" to "SKU-1")), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    val clarify = outcome as ReasoningOutcome.Clarify
    assertThat(clarify.candidates).containsExactly("configure", "price")
  }

  @Test
  fun `a confident read proceeds`() {
    val decision = RouteDecision("configure", "clear", confidence = 0.9, margin = 0.9)
    val outcome =
      gate.decide(decision, "price", FillResult.Valid(mapOf("productCode" to "SKU-1", "quantity" to "2")), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Proceed::class.java)
  }

  @Test
  fun `a confident write requires confirmation with a preview, not execution`() {
    val decision = RouteDecision("quote", "clear", confidence = 0.95, margin = 0.95)
    val outcome = gate.decide(decision, "price", FillResult.Valid(mapOf("priceId" to "prc-2")), listOf("quote"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.ConfirmRequired::class.java)
    val preview = (outcome as ReasoningOutcome.ConfirmRequired).preview
    assertThat(preview.graph).isEqualTo("quote")
    assertThat(preview.isWrite).isTrue()
    assertThat(preview.slots).containsEntry("priceId", "prc-2")
  }

  @Test
  fun `an invalid slot-fill asks for clarification`() {
    val decision = RouteDecision("configure", "clear", confidence = 0.9, margin = 0.9)
    val outcome =
      gate.decide(decision, "price", FillResult.Invalid(listOf("missing required slot 'quantity'")), listOf("configure"))
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    assertThat((outcome as ReasoningOutcome.Clarify).question).contains("quantity")
  }
}

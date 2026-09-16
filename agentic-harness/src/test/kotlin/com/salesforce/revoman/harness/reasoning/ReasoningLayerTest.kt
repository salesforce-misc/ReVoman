/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReasoningLayerTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String
  private val tools = GraphRegistry.loadToolDefs()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `a confident read proceeds without executing`() {
    val outcome = ReasoningLayer(tools).handle("configure 2 units of SKU-1")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Proceed::class.java)
    assertThat(server.db).isEmpty() // handle() never executes
  }

  @Test
  fun `a write intent requires confirmation and executes only on confirm`() {
    val layer = ReasoningLayer(tools)
    // Populate the database with the required price record so the quote can be created
    server.db["price:prc-2"] = 100.0
    val outcome = layer.handle("create a draft quote for prc-2")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.ConfirmRequired::class.java)
    assertThat(server.db.size).isEqualTo(1) // only the price we added, nothing ran yet

    val preview = (outcome as ReasoningOutcome.ConfirmRequired).preview
    val rundowns = layer.confirm(preview, baseUrl)
    assertThat(rundowns).isNotEmpty()
    assertThat(server.db.values).contains("DRAFT") // now it ran
  }

  @Test
  fun `a slot-missing intent asks instead of guessing and never executes`() {
    val outcome = ReasoningLayer(tools).handle("configure or price this")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    assertThat(server.db).isEmpty()
  }

  @Test
  fun `a low-margin intent asks which graph, never guessing or executing`() {
    // Un-calibrated (when_not_to_use stripped) tools score price=2, quote=4 on this intent
    // -> margin 0.5, below quote's 0.90 write-graph threshold -> the gate asks, never guesses.
    val stripped = tools.map { it.copy(whenNotToUse = emptyList()) }
    val outcome = ReasoningLayer(stripped).handle("price configuration or draft quote")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    val clarify = outcome as ReasoningOutcome.Clarify
    assertThat(clarify.candidates.size).isAtLeast(2)
    assertThat(clarify.question).contains("Did you mean")
    assertThat(server.db).isEmpty()
  }

  @Test
  fun `an unrelated intent is NoMatch`() {
    assertThat(ReasoningLayer(tools).handle("what is the weather"))
      .isInstanceOf(ReasoningOutcome.NoMatch::class.java)
  }
}

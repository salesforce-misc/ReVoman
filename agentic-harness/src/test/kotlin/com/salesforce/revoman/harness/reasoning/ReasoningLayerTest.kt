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
  fun `an ambiguous intent asks instead of guessing and never executes`() {
    // A high write-threshold (0.90) means a low-margin quote intent must ask.
    val policy = ConfidencePolicy(perGraphThreshold = mapOf("configure" to 0.99))
    val outcome = ReasoningLayer(tools, policy = policy).handle("configure or price this")
    assertThat(outcome).isInstanceOf(ReasoningOutcome.Clarify::class.java)
    assertThat(server.db).isEmpty()
  }

  @Test
  fun `an unrelated intent is NoMatch`() {
    assertThat(ReasoningLayer(tools).handle("what is the weather"))
      .isInstanceOf(ReasoningOutcome.NoMatch::class.java)
  }
}

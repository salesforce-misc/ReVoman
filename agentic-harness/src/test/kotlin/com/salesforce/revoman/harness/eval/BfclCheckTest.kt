/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class BfclCheckTest {
  private val tools = GraphRegistry.loadToolDefs()
  private val bfcl = BfclCheck(StubLlmClient(), tools)

  @Test
  fun `loads the slot-fill gold set`() {
    assertThat(SlotFillGoldSet.load()).hasSize(3)
  }

  @Test
  fun `predicted call matches gold for a configure utterance`() {
    val gold =
      SlotFillGold(
        "configure 2 units of SKU-1",
        "configure",
        mapOf("productCode" to "SKU-1", "quantity" to "2"),
      )
    val result = bfcl.check(gold)
    assertThat(result.graphMatch).isTrue()
    assertThat(result.slotsMatch).isTrue()
    assertThat(result.pass).isTrue()
  }

  @Test
  fun `every gold case passes with the stub client`() {
    val results = SlotFillGoldSet.load().map { bfcl.check(it) }
    assertThat(results.all { it.pass }).isTrue()
  }

  @Test
  fun `a gold with a wrong slot value does not pass`() {
    // Gold claims quantity=99, but the stub extracts 2 from the utterance -> slotsMatch false.
    val gold =
      SlotFillGold(
        "configure 2 units of SKU-1",
        "configure",
        mapOf("productCode" to "SKU-1", "quantity" to "99"),
      )
    val result = bfcl.check(gold)
    assertThat(result.graphMatch).isTrue()
    assertThat(result.slotsMatch).isFalse()
    assertThat(result.pass).isFalse()
  }

  @Test
  fun `a gold with a wrong graph does not pass`() {
    // "configure ..." routes to configure, but gold claims price -> graphMatch false.
    val gold = SlotFillGold("configure 2 units of SKU-1", "price", mapOf("configId" to "cfg-1"))
    val result = bfcl.check(gold)
    assertThat(result.graphMatch).isFalse()
    assertThat(result.pass).isFalse()
  }
}

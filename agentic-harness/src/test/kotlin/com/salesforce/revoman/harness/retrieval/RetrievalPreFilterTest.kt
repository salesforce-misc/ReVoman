/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.retrieval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.SlotSchema
import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.Test

class RetrievalPreFilterTest {
  private val realTools = GraphRegistry.loadToolDefs()

  @Test
  fun `is a no-op when k is at least the number of tools`() {
    val out = RetrievalPreFilter.topK("configure a product", realTools, k = 3)
    assertThat(out).isEqualTo(realTools)
  }

  @Test
  fun `narrows a large synthetic tool set to the relevant candidates`() {
    // 10 synthetic graphs; only these three should surface for a pricing intent.
    val tools =
      (1..10).map { i ->
        val (name, blurb) =
          when (i) {
            1 -> "price" to "compute the price and total cost of a configuration"
            2 -> "discount" to "apply a pricing discount to a cost total"
            3 -> "tax" to "compute tax on a price total"
            else -> "graph$i" to "unrelated capability number $i about widgets and gadgets"
          }
        ToolDef(name, blurb, emptyList(), listOf(blurb), emptyList(),
          mapOf("x" to SlotSchema(SlotType.STRING)))
      }
    val top3 = RetrievalPreFilter.topK("what is the price and cost total", tools, k = 3)
    assertThat(top3.map { it.graphName }).containsExactly("price", "discount", "tax")
  }

  @Test
  fun `never returns empty for k greater than zero even with no token overlap`() {
    val out = RetrievalPreFilter.topK("zzzz qqqq", realTools, k = 2)
    assertThat(out).hasSize(2)
  }
}

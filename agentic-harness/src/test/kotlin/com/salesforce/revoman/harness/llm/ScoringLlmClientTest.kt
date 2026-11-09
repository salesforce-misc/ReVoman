/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import org.junit.jupiter.api.Test

class ScoringLlmClientTest {
  // Seed tool defs with when_not_to_use STRIPPED — the un-calibrated starting point.
  private val seedTools: List<ToolDef> =
    listOf("configure", "price", "quote").map {
      ToolDefGenerator.generate(GraphMetadataParser.parse(it), GraphOasLoader.load(it))
        .copy(whenNotToUse = emptyList())
    }
  private val scoring = ScoringLlmClient()

  @Test
  fun `routes an unambiguous configure utterance`() {
    assertThat(scoring.route("configure 2 units of SKU-1", seedTools).graphName).isEqualTo("configure")
  }

  @Test
  fun `the price-phrased-as-quote utterance mis-routes to quote before calibration`() {
    assertThat(scoring.route("quote me a price for this config", seedTools).graphName).isEqualTo("quote")
  }

  @Test
  fun `adding a when_not_to_use clause to quote fixes the mis-route`() {
    val calibrated =
      seedTools.map {
        if (it.graphName == "quote")
          it.copy(
            whenNotToUse =
              listOf("Do not use when the user asks for a price or cost — that is the price graph.")
          )
        else it
      }
    assertThat(scoring.route("quote me a price for this config", calibrated).graphName)
      .isEqualTo("price")
  }

  @Test
  fun `returns null for an utterance that matches nothing`() {
    assertThat(scoring.route("xyzzy plugh", seedTools).graphName).isNull()
  }
}

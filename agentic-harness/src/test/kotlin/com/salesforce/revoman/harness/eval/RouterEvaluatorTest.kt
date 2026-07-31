/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.Test

class RouterEvaluatorTest {
  private val cases = EvalSet.load()
  private val evaluator = RouterEvaluator(ScoringLlmClient())

  // Seed = the four fields as generated, but with when_not_to_use STRIPPED (un-calibrated start).
  private val seedTools: List<ToolDef> =
    GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }

  // Calibrated = add the disambiguating clause to the quote graph (mined from the off-diagonal).
  private val calibratedTools: List<ToolDef> =
    seedTools.map {
      if (it.graphName == "quote")
        it.copy(
          whenNotToUse =
            listOf("Do not use when the user asks for a price or cost — that is the price graph.")
        )
      else it
    }

  @Test
  fun `seed router confuses the price-phrased-as-quote utterance`() {
    val report = evaluator.evaluate(cases, seedTools)
    assertThat(report.matrix.correct).isEqualTo(6)
    assertThat(report.matrix.total).isEqualTo(7)
    // The single off-diagonal: a price intent predicted as quote.
    assertThat(report.matrix.counts["price"]!!["quote"]).isEqualTo(1)
    assertThat(report.misses)
      .contains(Miss("quote me a price for this config", "price", "quote"))
  }

  @Test
  fun `adding the when_not_to_use clause moves accuracy to 100 percent`() {
    val report = evaluator.evaluate(cases, calibratedTools)
    assertThat(report.matrix.correct).isEqualTo(7)
    assertThat(report.matrix.total).isEqualTo(7)
    assertThat(report.misses).isEmpty()
    assertThat(report.matrix.counts["price"]!!["quote"]).isEqualTo(0)
  }
}

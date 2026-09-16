/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class ContractAblationEvalTest {
  private val cases = EvalSet.load()

  @Test
  fun `enriching the contract (adding when_not_to_use) improves accuracy on the eval set`() {
    // Variant A: descriptors with when_not_to_use STRIPPED (the poorer contract).
    val stripped = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }
    // Variant B: enrich the 'quote' descriptor with the disambiguating clause (the richer contract).
    val enriched =
      stripped.map {
        if (it.graphName == "quote")
          it.copy(whenNotToUse = listOf("Do not use when the user asks for a price or cost — that is the price graph."))
        else it
      }

    val (a, b) =
      ContractAblationEval().compare(cases, "stripped" to stripped, "enriched" to enriched)

    assertThat(a.variantName).isEqualTo("stripped")
    assertThat(b.variantName).isEqualTo("enriched")
    // The richer contract scores strictly higher — the blue box improved reasoning accuracy.
    assertThat(b.accuracyCorrect).isGreaterThan(a.accuracyCorrect)
  }
}

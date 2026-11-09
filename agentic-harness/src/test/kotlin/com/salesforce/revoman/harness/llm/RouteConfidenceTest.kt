/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import org.junit.jupiter.api.Test

class RouteConfidenceTest {
  private val tools = GraphRegistry.loadToolDefs()
  private val scoring = ScoringLlmClient()

  @Test
  fun `an unambiguous utterance routes with high margin`() {
    val decision = scoring.route("configure 2 units of SKU-1", tools)
    assertThat(decision.graphName).isEqualTo("configure")
    assertThat(decision.confidence).isGreaterThan(0.0)
    assertThat(decision.margin).isGreaterThan(0.0)
  }

  @Test
  fun `scores exposes a per-graph score map`() {
    val scores = scoring.scores("configure 2 units of SKU-1", tools)
    assertThat(scores.keys).containsExactly("configure", "price", "quote")
    assertThat(scores["configure"]!!).isGreaterThan(0)
  }

  @Test
  fun `a no-match utterance has zero confidence and margin`() {
    val decision = scoring.route("xyzzy plugh", tools)
    assertThat(decision.graphName).isNull()
    assertThat(decision.confidence).isEqualTo(0.0)
    assertThat(decision.margin).isEqualTo(0.0)
  }
}

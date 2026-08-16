/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.Step
import org.junit.jupiter.api.Test

class RegexReplacerConsumedTest {
  @Test
  fun `resolving a double-brace var records it as consumed`() {
    val graph = focusedPostmanTestGraph(environmentValues = mapOf("policyId" to "0Pol1"))
    val step = Step(index = "1", rawPMStep = Item(name = "validate"))
    graph.scopes.environment.currentStep = step
    // present in env (also marks produced for this step — fine)
    graph.scopes.environment.set("policyId", "0Pol1")
    val out = graph.replacer.replaceVariablesRecursively("id={{policyId}}")
    assertThat(out).isEqualTo("id=0Pol1")
    assertThat(graph.scopes.environment.consumedKeysFor(step)).contains("policyId")
  }
}

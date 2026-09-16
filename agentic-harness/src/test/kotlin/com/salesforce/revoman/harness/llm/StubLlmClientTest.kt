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

class StubLlmClientTest {
  private val tools: List<ToolDef> =
    listOf("configure", "price", "quote").map {
      ToolDefGenerator.generate(GraphMetadataParser.parse(it), GraphOasLoader.load(it))
    }
  private val stub = StubLlmClient()

  @Test
  fun `routes configure intent`() {
    assertThat(stub.route("configure 2 units of SKU-1", tools).graphName).isEqualTo("configure")
  }

  @Test
  fun `routes price intent`() {
    assertThat(stub.route("how much does this configuration cost", tools).graphName).isEqualTo("price")
  }

  @Test
  fun `routes quote intent`() {
    assertThat(stub.route("create a draft quote", tools).graphName).isEqualTo("quote")
  }

  @Test
  fun `returns null graph for an unrelated utterance`() {
    assertThat(stub.route("what is the weather today", tools).graphName).isNull()
  }

  @Test
  fun `fills configure slots from the utterance`() {
    val configure = tools.first { it.graphName == "configure" }
    val slots = stub.fillSlots("configure 2 units of SKU-1", configure)
    assertThat(slots).containsEntry("productCode", "SKU-1")
    assertThat(slots).containsEntry("quantity", "2")
  }
}

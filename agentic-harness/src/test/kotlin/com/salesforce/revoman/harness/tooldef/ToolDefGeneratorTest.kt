/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolDefGeneratorTest {
  @Test
  fun `generates the four fields from configure metadata and OAS`() {
    val spec = GraphMetadataParser.parse("configure")
    val oas = GraphOasLoader.load("configure")
    val toolDef = ToolDefGenerator.generate(spec, oas)

    assertThat(toolDef.graphName).isEqualTo("configure")
    assertThat(toolDef.whenToUse).isEqualTo(spec.description)
    assertThat(toolDef.whenNotToUse).isNotEmpty()
    assertThat(toolDef.exampleQueries).hasSize(3)
    assertThat(toolDef.inputExamples).isNotEmpty()
    // The slot schema is carried through for the slot-filler/validator.
    assertThat(toolDef.slots.keys).containsExactly("productCode", "quantity")
    assertThat(toolDef.slots["productCode"]!!.type).isEqualTo(SlotType.ENUM)
    assertThat(toolDef.slots["productCode"]!!.values).containsExactly("SKU-1", "SKU-2", "SKU-3")
    assertThat(toolDef.slots["quantity"]!!.type).isEqualTo(SlotType.INT)
  }

  @Test
  fun `rejects an OAS whose declared slots do not match the graph metadata`() {
    val spec = GraphSpec("configure", "desc", slots = listOf("productCode", "quantity"), outputKeys = listOf("configId"))
    val badOas =
      GraphOas(
        graph = "configure",
        slots = mapOf("productCode" to SlotSchema(SlotType.ENUM, listOf("SKU-1"))), // missing 'quantity'
        exampleQueries = listOf("x"),
        inputExamples = listOf(mapOf("productCode" to "SKU-1")),
      )
    val ex = assertThrows<IllegalArgumentException> { ToolDefGenerator.generate(spec, badOas) }
    assertThat(ex.message).contains("quantity")
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.tooldef.SlotSchema
import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.junit.jupiter.api.Test

class SlotFillerTest {
  private val configureTool =
    ToolDef(
      graphName = "configure",
      whenToUse = "configure a product",
      whenNotToUse = emptyList(),
      exampleQueries = emptyList(),
      inputExamples = emptyList(),
      slots =
        mapOf(
          "productCode" to SlotSchema(SlotType.ENUM, listOf("SKU-1", "SKU-2", "SKU-3")),
          "quantity" to SlotSchema(SlotType.INT),
        ),
    )

  /** A fake LlmClient that returns pre-canned slot values, so validation can be tested in isolation. */
  private fun fakeLlm(filled: Map<String, String>): LlmClient =
    object : LlmClient {
      override fun route(utterance: String, tools: List<ToolDef>) = RouteDecision(null, "")

      override fun fillSlots(utterance: String, tool: ToolDef) = filled
    }

  @Test
  fun `accepts valid enum and int slots`() {
    val result =
      SlotFiller(fakeLlm(mapOf("productCode" to "SKU-1", "quantity" to "2")))
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Valid::class.java)
    assertThat((result as FillResult.Valid).slots).containsEntry("quantity", "2")
  }

  @Test
  fun `rejects a hallucinated enum value`() {
    val result =
      SlotFiller(fakeLlm(mapOf("productCode" to "SKU-99", "quantity" to "2")))
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("productCode")
  }

  @Test
  fun `rejects a non-integer quantity`() {
    val result =
      SlotFiller(fakeLlm(mapOf("productCode" to "SKU-1", "quantity" to "lots")))
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("quantity")
  }

  @Test
  fun `rejects a missing required slot`() {
    val result = SlotFiller(fakeLlm(mapOf("productCode" to "SKU-1"))).fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("quantity")
  }

  @Test
  fun `rejects a hallucinated slot name`() {
    val result =
      SlotFiller(
          fakeLlm(mapOf("productCode" to "SKU-1", "quantity" to "2", "discountPct" to "50"))
        )
        .fill("x", configureTool)
    assertThat(result).isInstanceOf(FillResult.Invalid::class.java)
    assertThat((result as FillResult.Invalid).errors.joinToString()).contains("discountPct")
  }
}

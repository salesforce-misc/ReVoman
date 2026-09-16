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

class GraphMetadataParserTest {
  @Test
  fun `configure graph exposes productCode and quantity as slots and configId as output`() {
    val spec = GraphMetadataParser.parse("configure")
    assertThat(spec.name).isEqualTo("configure")
    assertThat(spec.description).isNotEmpty()
    assertThat(spec.slots).containsExactly("productCode", "quantity")
    assertThat(spec.outputKeys).contains("configId")
    // Infra placeholders are never slots.
    assertThat(spec.slots).containsNoneOf("baseUrl", "accessToken")
  }

  @Test
  fun `price graph consumes configId as its only slot and emits priceId`() {
    val spec = GraphMetadataParser.parse("price")
    assertThat(spec.slots).containsExactly("configId")
    assertThat(spec.outputKeys).containsAtLeast("priceId", "total")
  }

  @Test
  fun `quote graph consumes priceId as its only slot`() {
    val spec = GraphMetadataParser.parse("quote")
    assertThat(spec.slots).containsExactly("priceId")
    assertThat(spec.outputKeys).contains("quoteId")
  }
}

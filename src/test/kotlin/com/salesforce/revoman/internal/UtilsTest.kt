/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal

import com.salesforce.revoman.internal.postman.state.Template
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class UtilsTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `deepFlattenItems with nested items`() {
    val itemAdapter = Moshi.Builder().build().adapter<Template>()
    val stepNames =
      itemAdapter
        .fromJson(readFileToString("pmCollection/steps-with-folders.json"))
        ?.item
        ?.deepFlattenItems()
        ?.map { it.name }
    stepNames shouldContainExactly
      listOf(
        "1.1.1 -> POST: product-setup|>pre|>Login to ProductPricingAdmin",
        "1.1.2 -> GET: product-setup|>pre|>Proration Policy",
        "1.2.1 -> POST: product-setup|>OneTime|>One-Time Product",
        "1.2.2 -> POST: product-setup|>OneTime|>OneTime PBE",
        "1.3.1 -> POST: product-setup|>Evergreen|>Evergreen Product",
        "1.3.2 -> POST: product-setup|>Evergreen|>Evergreen PSM",
        "1.3.3 -> GET: product-setup|>Evergreen|>Evergreen PSM",
        "1.3.4 -> POST: product-setup|>Evergreen|>Evergreen PSMO",
        "1.3.5 -> POST: product-setup|>Evergreen|>Evergreen PBE",
        "2.1 -> POST: bundle-setup|>ProductRelationshipType",
        "2.2 -> GET: bundle-setup|>ProductRelationShipType",
        "2.3 -> POST: bundle-setup|>ProductRelatedComponent"
      )
  }
}

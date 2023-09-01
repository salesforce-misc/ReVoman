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
        "POST: product-setup|>pre|>Login to ProductPricingAdmin",
        "GET: product-setup|>pre|>Proration Policy",
        "POST: product-setup|>OneTime|>One-Time Product",
        "POST: product-setup|>OneTime|>OneTime PBE",
        "POST: product-setup|>Evergreen|>Evergreen Product",
        "POST: product-setup|>Evergreen|>Evergreen PSM",
        "GET: product-setup|>Evergreen|>Evergreen PSM",
        "POST: product-setup|>Evergreen|>Evergreen PSMO",
        "POST: product-setup|>Evergreen|>Evergreen PBE",
        "POST: bundle-setup|>ProductRelationshipType",
        "GET: bundle-setup|>ProductRelationShipType",
        "POST: bundle-setup|>ProductRelatedComponent"
      )
  }
}

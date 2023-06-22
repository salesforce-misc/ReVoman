package org.revcloud.revoman.internal

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.revcloud.revoman.TEST_RESOURCES_PATH
import org.revcloud.revoman.internal.postman.state.Steps

class UtilsTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `deepFlattenItems with nested items`() {
    val itemAdapter = Moshi.Builder().build().adapter<Steps>()
    val stepNames = itemAdapter.fromJson(readTextFromFile("$TEST_RESOURCES_PATH/steps-with-folders.json"))?.item?.deepFlattenItems()?.map { it["name"] }
    stepNames shouldContainExactly listOf(
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
    "POST: bundle-setup|>ProductRelatedComponent")
      
  }
}

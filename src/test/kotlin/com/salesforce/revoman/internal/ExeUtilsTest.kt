/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved.
 * SPDX-License-Identifier${HTTP_METHOD_SEPARATOR}BSD-3-Clause For full license text, see the
 * LICENSE file in the repo root or http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.internal

import com.salesforce.revoman.input.bufferFileInResources
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.stepNameVariants
import com.salesforce.revoman.internal.postman.state.Template
import com.salesforce.revoman.output.FOLDER_DELIMITER
import com.salesforce.revoman.output.HTTP_METHOD_SEPARATOR
import com.salesforce.revoman.output.INDEX_SEPARATOR
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

class ExeUtilsTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `deepFlattenItems with nested items`() {
    val itemAdapter = Moshi.Builder().build().adapter<Template>()
    val stepNames =
      itemAdapter
        .fromJson(bufferFileInResources("pmCollection/steps-with-folders.json"))
        ?.item
        ?.deepFlattenItems()
        ?.map { it.name }
    stepNames shouldContainExactly
      listOf(
        "1.1.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}pre${FOLDER_DELIMITER}Login to ProductPricingAdmin",
        "1.1.2${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}pre${FOLDER_DELIMITER}Proration Policy",
        "1.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}OneTime${FOLDER_DELIMITER}One-Time Product",
        "1.2.2${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}OneTime${FOLDER_DELIMITER}OneTime PBE",
        "1.3.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}Evergreen${FOLDER_DELIMITER}Evergreen Product",
        "1.3.2${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}Evergreen${FOLDER_DELIMITER}Evergreen PSM",
        "1.3.3${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}Evergreen${FOLDER_DELIMITER}Evergreen PSM",
        "1.3.4${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}Evergreen${FOLDER_DELIMITER}Evergreen PSMO",
        "1.3.5${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}Evergreen${FOLDER_DELIMITER}Evergreen PBE",
        "2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}bundle-setup${FOLDER_DELIMITER}ProductRelationshipType",
        "2.2${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}bundle-setup${FOLDER_DELIMITER}ProductRelationShipType",
        "2.3${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}bundle-setup${FOLDER_DELIMITER}ProductRelatedComponent"
      )
  }

  @Test
  fun `stepName variants should contain step in root`() {
    val fqStepName = "3${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}nature"
    val currentStepName = "nature"
    stepNameVariants(fqStepName) shouldContain currentStepName
  }

  @Test
  fun `stepName variants should contain step in a folder`() {
    val fqStepName =
      "1.1${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}all-pokemon${FOLDER_DELIMITER}all-pokemon"
    val currentStepName = "all-pokemon"
    stepNameVariants(fqStepName) shouldContain currentStepName
  }

  @Test
  fun `stepName variants`() {
    val stepName = "nature"
    stepNameVariants(stepName) shouldContainExactly setOf(stepName)
  }

  @Test
  fun `possible stepName variants`() {
    val stepName =
      "1.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}OneTime${FOLDER_DELIMITER}One-Time Product"
    stepNameVariants(stepName) shouldContainExactlyInAnyOrder
      setOf(stepName, "POST ~~> product-setup|>OneTime|>One-Time Product", "One-Time Product")
  }
}
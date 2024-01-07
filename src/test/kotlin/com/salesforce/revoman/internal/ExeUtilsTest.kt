/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal

import com.salesforce.revoman.input.bufferFileInResources
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import com.salesforce.revoman.output.report.Step.Companion.HTTP_METHOD_SEPARATOR
import com.salesforce.revoman.output.report.Step.Companion.INDEX_SEPARATOR
import com.salesforce.revoman.output.report.Step.Companion.STEP_SEPARATOR
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExeUtilsTest {

  @OptIn(ExperimentalStdlibApi::class)
  val steps =
    deepFlattenItems(
        Moshi.Builder()
          .build()
          .adapter<Template>()
          .fromJson(bufferFileInResources("pmCollection/steps-with-folders.json"))!!
          .item
      )
      .map { it.first }

  @Test
  fun `stepNames for deepFlattened nested items`() {
    val stepNames = steps.map { it.displayName }
    stepNames shouldContainExactly
      listOf(
        "1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}step-at-root",
        "2.1.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-11${STEP_SEPARATOR}Login to ProductPricingAdmin",
        "2.1.2${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-11${STEP_SEPARATOR}Proration Policy",
        "2.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-12${STEP_SEPARATOR}One-Time Product",
        "2.2.2${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-12${STEP_SEPARATOR}OneTime PBE",
        "2.3.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_SEPARATOR}Evergreen Product",
        "2.3.2${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_SEPARATOR}Evergreen PSM",
        "2.3.3${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_SEPARATOR}Evergreen PSM",
        "2.3.4${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_SEPARATOR}Evergreen PSMO",
        "2.3.5${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_SEPARATOR}Evergreen PBE",
        "3.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-2${STEP_SEPARATOR}step-21",
        "3.2${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}folder-2${STEP_SEPARATOR}step-22",
        "3.3${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-2${STEP_SEPARATOR}step-23"
      )
  }

  @Test
  fun `stepName matches`() {
    steps[0].stepNameMatches("step-at-root") shouldBe true
    steps[1].stepNameMatches("Login to ProductPricingAdmin") shouldBe true
  }
}

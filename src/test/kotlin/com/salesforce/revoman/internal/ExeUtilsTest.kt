/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal

import com.salesforce.revoman.input.bufferFile
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.withName
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import com.salesforce.revoman.output.report.Step.Companion.HTTP_METHOD_SEPARATOR
import com.salesforce.revoman.output.report.Step.Companion.INDEX_SEPARATOR
import com.salesforce.revoman.output.report.Step.Companion.STEP_NAME_SEPARATOR
import com.salesforce.revoman.output.report.Step.Companion.STEP_NAME_TERMINATOR
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.assertions.throwables.shouldThrow
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
          .fromJson(bufferFile("pm-templates/v2/steps-with-folders.json"))!!
          .item
      )
      .map { it }

  @Test
  fun `stepNames for deepFlattened nested items`() {
    val stepNames = steps.map { it.displayName }
    stepNames shouldContainExactly
      listOf(
        "1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}step-at-root",
        "2.1.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-11${STEP_NAME_SEPARATOR}Login to ProductPricingAdmin$STEP_NAME_TERMINATOR",
        "2.1.2${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-11${STEP_NAME_SEPARATOR}Proration Policy$STEP_NAME_TERMINATOR",
        "2.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-12${STEP_NAME_SEPARATOR}One-Time Product$STEP_NAME_TERMINATOR",
        "2.2.2${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-12${STEP_NAME_SEPARATOR}OneTime PBE$STEP_NAME_TERMINATOR",
        "2.3.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_NAME_SEPARATOR}Evergreen Product$STEP_NAME_TERMINATOR",
        "2.3.2${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_NAME_SEPARATOR}Evergreen PSM$STEP_NAME_TERMINATOR",
        "2.3.3${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_NAME_SEPARATOR}Evergreen PSM$STEP_NAME_TERMINATOR",
        "2.3.4${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_NAME_SEPARATOR}Evergreen PSMO$STEP_NAME_TERMINATOR",
        "2.3.5${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-1${FOLDER_DELIMITER}folder-13${STEP_NAME_SEPARATOR}Evergreen PBE$STEP_NAME_TERMINATOR",
        "3.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-2${STEP_NAME_SEPARATOR}step-21$STEP_NAME_TERMINATOR",
        "3.2${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}folder-2${STEP_NAME_SEPARATOR}step-22$STEP_NAME_TERMINATOR",
        "3.3${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}folder-2${STEP_NAME_SEPARATOR}step-23$STEP_NAME_TERMINATOR",
      )
  }

  @Test
  fun `stepName matches`() {
    steps[0].stepNameMatches("step-at-root") shouldBe true
    steps[1].stepNameMatches("Login to ProductPricingAdmin") shouldBe true
  }

  @Test
  fun `shouldStepBePicked - empty runOnly and skip - picks every step`() {
    shouldStepBePicked(steps[0], emptyList(), emptyList()) shouldBe true
    shouldStepBePicked(steps[1], emptyList(), emptyList()) shouldBe true
  }

  @Test
  fun `shouldStepBePicked - skipSteps only - skips named, runs others`() {
    val skip = listOf(withName("step-at-root"))
    shouldStepBePicked(steps[0], emptyList(), skip) shouldBe false
    shouldStepBePicked(steps[1], emptyList(), skip) shouldBe true
  }

  @Test
  fun `shouldStepBePicked - runOnlySteps only - runs named, skips others`() {
    val runOnly = listOf(withName("Login to ProductPricingAdmin"))
    shouldStepBePicked(steps[0], runOnly, emptyList()) shouldBe false
    shouldStepBePicked(steps[1], runOnly, emptyList()) shouldBe true
  }

  @Test
  fun `shouldStepBePicked - disjoint runOnly and skip - both honored`() {
    val runOnly = listOf(withName("Login to ProductPricingAdmin"))
    val skip = listOf(withName("step-at-root"))
    shouldStepBePicked(steps[0], runOnly, skip) shouldBe false
    shouldStepBePicked(steps[1], runOnly, skip) shouldBe true
    shouldStepBePicked(steps[2], runOnly, skip) shouldBe false
  }

  @Test
  fun `shouldStepBePicked - same name in runOnly and skip - throws Ambiguous`() {
    val runOnly = listOf(withName("step-at-root"))
    val skip = listOf(withName("step-at-root"))
    shouldThrow<IllegalStateException> { shouldStepBePicked(steps[0], runOnly, skip) }
  }
}

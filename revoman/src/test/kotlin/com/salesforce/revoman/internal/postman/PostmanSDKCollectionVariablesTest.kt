/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.Step
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PostmanSDKCollectionVariablesTest {
  private fun sdk() = PostmanSDK(initMoshi())

  private fun step(name: String) = Step(index = "1", rawPMStep = Item(name = name))

  @Test
  fun `collectionVariables store round-trips set and toMap`() {
    val pm = sdk()
    pm.collectionVariables.set("a", "1")
    pm.collectionVariables.toMap() shouldBe mapOf("a" to "1")
  }

  @Test
  fun `environmentName defaults to null and is settable`() {
    val pm = sdk()
    pm.environmentName shouldBe null
    pm.environmentName = "Pokemon"
    pm.environmentName shouldBe "Pokemon"
  }

  @Test
  fun `per-step pmTestAssertions capture is keyed by step`() {
    val pm = sdk()
    val s1 = step("s1")
    val assertions = listOf(PmTestAssertion("t", true))
    pm.recordPmTestAssertions(s1, assertions)
    pm.pmTestAssertionsFor(s1) shouldHaveSize 1
    pm.pmTestAssertionsFor(step("other")) shouldHaveSize 0
  }

  @Test
  fun `per-step pmTestAssertions accumulate across pre-req and post-res`() {
    val pm = sdk()
    val s1 = step("s1")
    pm.recordPmTestAssertions(s1, listOf(PmTestAssertion("pre", true)))
    pm.recordPmTestAssertions(s1, listOf(PmTestAssertion("post", false)))
    pm.pmTestAssertionsFor(s1) shouldHaveSize 2
  }

  @Test
  fun `per-step nextRequest capture is keyed by step and last-write-wins`() {
    val pm = sdk()
    val s1 = step("s1")
    pm.recordNextRequest(s1, "first", set = true)
    pm.recordNextRequest(s1, "second", set = true)
    pm.nextRequestFor(s1) shouldBe "second"
    pm.nextRequestFor(step("other")) shouldBe null
  }
}

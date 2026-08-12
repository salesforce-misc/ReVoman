/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.Step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepScriptCaptureTest {
  @Test
  fun `assertions accumulate per step across script phases`() {
    val capture = stepScriptCapture()
    val first = step("first")
    val second = step("second")
    val pre = PmTestAssertion("pre", passed = true)
    val post = PmTestAssertion("post", passed = false)

    capture.recordAssertions(first, listOf(pre))
    capture.recordAssertions(first, listOf(post))

    capture.assertionsFor(first) shouldContainExactly listOf(pre, post)
    capture.assertionsFor(second).shouldBeEmpty()
  }

  @Test
  fun `next request is last-write-wins and preserves an explicit null set bit`() {
    val capture = stepScriptCapture()
    val step = step("loop")

    capture.recordNextRequest(step, "again", wasSet = true)
    capture.recordNextRequest(step, null, wasSet = true)

    capture.nextRequestFor(step) shouldBe null
    capture.nextRequestWasSetFor(step) shouldBe true
  }

  @Test
  fun `skip and reset are isolated by Step key`() {
    val capture = stepScriptCapture()
    val first = step("first")
    val second = step("second")
    capture.recordAssertions(first, listOf(PmTestAssertion("first", passed = true)))
    capture.recordNextRequest(first, "second", wasSet = true)
    capture.recordSkipRequest(first)
    capture.recordSkipRequest(second)

    capture.reset(first)

    capture.assertionsFor(first).shouldBeEmpty()
    capture.nextRequestFor(first) shouldBe null
    capture.nextRequestWasSetFor(first) shouldBe false
    capture.skipRequestFor(first) shouldBe false
    capture.skipRequestFor(second) shouldBe true
  }

  private fun step(name: String): Step = Step(index = name, rawPMStep = Item(name = name))
}

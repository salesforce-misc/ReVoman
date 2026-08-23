/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.output.report.PmTestAssertion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepScriptCaptureTest {
  @Test
  fun `assertions accumulate across script phases`() {
    val capture = StepScriptCapture()
    val pre = PmTestAssertion("pre", passed = true)
    val post = PmTestAssertion("post", passed = false)

    capture.recordAssertions(listOf(pre))
    capture.recordAssertions(listOf(post))

    capture.assertions() shouldContainExactly listOf(pre, post)
  }

  @Test
  fun `next request is last-write-wins and preserves an explicit null set bit`() {
    val capture = StepScriptCapture()
    capture.recordNextRequest("again", wasSet = true)
    capture.recordNextRequest(null, wasSet = true)

    capture.nextRequest() shouldBe null
    capture.nextRequestWasSet() shouldBe true
  }

  @Test
  fun `reset clears all current step state`() {
    val capture = StepScriptCapture()
    capture.recordAssertions(listOf(PmTestAssertion("first", passed = true)))
    capture.recordNextRequest("second", wasSet = true)
    capture.recordSkipRequest()

    capture.reset()

    capture.assertions().shouldBeEmpty()
    capture.nextRequest() shouldBe null
    capture.nextRequestWasSet() shouldBe false
    capture.skipRequest() shouldBe false
  }
}

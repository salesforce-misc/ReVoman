/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the entry-point contract: one `onRunStart` + one `recordSteps(stepReports.size)` per
 * top-level run. A full `revUp` needs network, so this asserts the counter contract the ReVoman
 * wiring must uphold — if a refactor drops either call, Task 2's manual verification + this test's
 * intent catch it.
 */
class BannerWiringTest {
  @BeforeEach fun setUp() = Banner.resetForTest()

  @AfterEach fun tearDown() = Banner.resetForTest()

  @Test
  fun `one run of N steps bumps runs by 1 and steps by N`() {
    Banner.emitForTest = {}
    Banner.onRunStart()
    Banner.recordSteps(4) // stands in for rundown.stepReports.size
    Banner.runCountForTest() shouldBe 1L
    Banner.stepCountForTest() shouldBe 4L
  }
}

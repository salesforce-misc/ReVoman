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
 * Pins the Banner counter contract: one [Banner.onRunStart] bumps runs by 1, one
 * [Banner.recordSteps] bumps steps by N. The ReVoman.revUp entry-point wiring relies on this
 * contract — if a refactor drops either call there, the counter totals will be wrong. This test
 * guards the contract, not the actual wiring (which needs network and is tested manually).
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

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepReportPmTestTest {

  @Test
  fun `PmTestAssertion exeType defaults to POST_RES_JS and is settable`() {
    PmTestAssertion("t", passed = true).exeType shouldBe POST_RES_JS
    PmTestAssertion("t", passed = false, exeType = PRE_REQ_JS).exeType shouldBe PRE_REQ_JS
  }
}

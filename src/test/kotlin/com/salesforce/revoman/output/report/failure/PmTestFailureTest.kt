/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.failure.PmTestFailure.PostResJsTestFailure
import com.salesforce.revoman.output.report.failure.PmTestFailure.PreReqJsTestFailure
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PmTestFailureTest {

  @Test
  fun `no failed assertions yields no PmTestFailure`() {
    buildPmTestFailures(
      listOf(
        PmTestAssertion("ok", passed = true, exeType = POST_RES_JS),
        PmTestAssertion("skipped", passed = false, skipped = true, exeType = POST_RES_JS),
      )
    ) shouldHaveSize 0
  }

  @Test
  fun `a failed post-res assertion yields one PostResJsTestFailure with a descriptive message`() {
    val failures =
      buildPmTestFailures(
        listOf(
          PmTestAssertion(
            "status is 200",
            passed = false,
            error = "expected 500 to equal 200",
            exeType = POST_RES_JS,
          )
        )
      )
    failures shouldHaveSize 1
    val f = failures.single()
    f.shouldBeInstanceOf<PostResJsTestFailure>()
    f.exeType shouldBe POST_RES_JS
    f.failedAssertions shouldHaveSize 1
    f.failure.message!! shouldContain "status is 200"
    f.failure.message!! shouldContain "expected 500 to equal 200"
  }

  @Test
  fun `failures from both phases produce pre-req first then post-res`() {
    val failures =
      buildPmTestFailures(
        listOf(
          PmTestAssertion("pre fail", passed = false, exeType = PRE_REQ_JS),
          PmTestAssertion("post fail", passed = false, exeType = POST_RES_JS),
        )
      )
    failures shouldHaveSize 2
    failures[0].shouldBeInstanceOf<PreReqJsTestFailure>()
    failures[1].shouldBeInstanceOf<PostResJsTestFailure>()
  }
}

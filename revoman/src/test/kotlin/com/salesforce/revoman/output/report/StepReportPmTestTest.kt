/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.failure.HttpStatusUnsuccessful
import com.salesforce.revoman.output.report.failure.PmTestFailure.PostResJsTestFailure
import com.salesforce.revoman.output.report.failure.PmTestFailure.PreReqJsTestFailure
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class StepReportPmTestTest {

  private val moshiReVoman = initMoshi()

  @Test
  fun `PmTestAssertion exeType defaults to POST_RES_JS and is settable`() {
    PmTestAssertion("t", passed = true).exeType shouldBe POST_RES_JS
    PmTestAssertion("t", passed = false, exeType = PRE_REQ_JS).exeType shouldBe PRE_REQ_JS
  }

  private fun okReport(status: org.http4k.core.Status = OK): StepReport {
    val rawRequest = Request(method = GET.toString(), url = Url("https://test.example.com/x"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "req",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "res",
        httpMsg = Response(status).body("{}"),
        moshiReVoman = moshiReVoman,
      )
    return StepReport(
      Step("1", Item(request = rawRequest)),
      Right(requestInfo),
      null,
      Right(responseInfo),
      pmEnvSnapshot = PostmanEnvironment(),
    )
  }

  @Test
  fun `all assertions passing keeps the step successful`() {
    val report =
      okReport()
        .copy(
          pmTestAssertions = listOf(PmTestAssertion("ok", passed = true, exeType = POST_RES_JS))
        )
    report.isSuccessful shouldBe true
    report.pmTestFailure shouldHaveSize 0
  }

  @Test
  fun `a failing post-res assertion fails the step`() {
    val report =
      okReport()
        .copy(
          pmTestAssertions =
            listOf(
              PmTestAssertion(
                "status is 200",
                passed = false,
                error = "expected 200",
                exeType = POST_RES_JS,
              )
            )
        )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe POST_RES_JS
    report.exeFailure.shouldBeInstanceOf<PostResJsTestFailure>()
    report.pmTestFailure shouldHaveSize 1
  }

  @Test
  fun `a failing pre-req assertion fails the step tagged PRE_REQ_JS`() {
    val report =
      okReport()
        .copy(
          pmTestAssertions = listOf(PmTestAssertion("pre", passed = false, exeType = PRE_REQ_JS))
        )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe PRE_REQ_JS
    report.exeFailure.shouldBeInstanceOf<PreReqJsTestFailure>()
  }

  @Test
  fun `both phases failing - failure is pre-req first, pmTestFailure lists both`() {
    val report =
      okReport()
        .copy(
          pmTestAssertions =
            listOf(
              PmTestAssertion("pre", passed = false, exeType = PRE_REQ_JS),
              PmTestAssertion("post", passed = false, exeType = POST_RES_JS),
            )
        )
    report.exeFailure.shouldBeInstanceOf<PreReqJsTestFailure>()
    report.pmTestFailure shouldHaveSize 2
  }

  @Test
  fun `HTTP non-2xx takes precedence but pm test failure is still surfaced (surface both)`() {
    val report =
      okReport(BAD_REQUEST)
        .copy(
          pmTestAssertions =
            listOf(PmTestAssertion("body check", passed = false, exeType = POST_RES_JS))
        )
    report.isSuccessful shouldBe false
    // Primary cause is the HTTP status, NOT the assertion.
    report.failure!!.isRight shouldBe true
    report.failure!!.get().shouldBeInstanceOf<HttpStatusUnsuccessful>()
    // ...yet the assertion failure is independently visible.
    report.pmTestFailure shouldHaveSize 1
  }

  @Test
  fun `skipped failing assertions do not fail the step`() {
    val report =
      okReport()
        .copy(
          pmTestAssertions =
            listOf(PmTestAssertion("skipme", passed = false, skipped = true, exeType = POST_RES_JS))
        )
    report.isSuccessful shouldBe true
    report.pmTestFailure shouldHaveSize 0
  }
}

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
import com.salesforce.revoman.output.ExeType.POLLING
import com.salesforce.revoman.output.ExeType.POST_STEP_HOOK
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingRequestFailure
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingTimeoutFailure
import io.kotest.matchers.shouldBe
import java.time.Duration
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class StepReportPollingTest {

  private val moshiReVoman = initMoshi()

  private fun successfulRequestAndResponse():
    Pair<TxnInfo<org.http4k.core.Request>, TxnInfo<Response>> {
    val rawRequest =
      Request(method = Method.POST.toString(), url = Url("https://test.example.com/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK).body("ok"),
        moshiReVoman = moshiReVoman,
      )
    return requestInfo to responseInfo
  }

  @Test
  fun `StepReport with PollingTimeoutFailure is not successful`() {
    val rawRequest =
      Request(method = Method.POST.toString(), url = Url("https://test.example.com/"))
    val (requestInfo, responseInfo) = successfulRequestAndResponse()
    val pollingFailure =
      PollingTimeoutFailure(
        failure = RuntimeException("timed out"),
        pollAttempts = 5,
        timeout = Duration.ofSeconds(30),
        lastPollResponse = Response(OK).body("still pending"),
      )
    val report =
      StepReport(
        Step("1", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        null,
        pollingFailure,
        null,
        PostmanEnvironment(),
      )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe POLLING
  }

  @Test
  fun `StepReport with PollingRequestFailure is not successful`() {
    val rawRequest =
      Request(method = Method.POST.toString(), url = Url("https://test.example.com/"))
    val (requestInfo, responseInfo) = successfulRequestAndResponse()
    val pollingFailure =
      PollingRequestFailure(
        failure = RuntimeException("request build error"),
        pollAttempts = 1,
        failedRequest = org.http4k.core.Request(Method.GET, "http://poll.test/status"),
      )
    val report =
      StepReport(
        Step("1", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        null,
        pollingFailure,
        null,
        PostmanEnvironment(),
      )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe POLLING
  }

  @Test
  fun `StepReport with pollingReport and no failure is successful`() {
    val rawRequest =
      Request(method = Method.POST.toString(), url = Url("https://test.example.com/"))
    val (requestInfo, responseInfo) = successfulRequestAndResponse()
    val pollingReport =
      PollingReport(
        pollAttempts = 3,
        totalDuration = Duration.ofSeconds(6),
        responses = listOf(Response(OK).body("done")),
      )
    val report =
      StepReport(
        Step("1", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        null,
        null,
        pollingReport,
        PostmanEnvironment(),
      )
    report.isSuccessful shouldBe true
    report.pollingReport shouldBe pollingReport
  }

  @Test
  fun `pollingFailure priority is after postStepHookFailure`() {
    val rawRequest =
      Request(method = Method.POST.toString(), url = Url("https://test.example.com/"))
    val (requestInfo, responseInfo) = successfulRequestAndResponse()
    val postStepHookFailure =
      PostStepHookFailure(RuntimeException("hook failed"), requestInfo, responseInfo)
    val pollingFailure =
      PollingTimeoutFailure(
        failure = RuntimeException("timed out"),
        pollAttempts = 5,
        timeout = Duration.ofSeconds(30),
        lastPollResponse = null,
      )
    val report =
      StepReport(
        Step("1", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        postStepHookFailure,
        pollingFailure,
        null,
        PostmanEnvironment(),
      )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe POST_STEP_HOOK
  }
}

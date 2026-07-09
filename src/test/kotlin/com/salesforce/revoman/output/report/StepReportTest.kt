/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PreStepHookFailure
import com.salesforce.revoman.output.report.failure.PollingFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure
import io.kotest.matchers.shouldBe
import java.time.Duration
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class StepReportTest {

  private val moshiReVoman = initMoshi()

  @Test
  fun `StepReport HttpStatusSuccessful`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val stepReportSuccess =
      StepReport(
        Step("1.3.7", Item(request = rawRequest)),
        Right(requestInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportSuccess)
    stepReportSuccess.isHttpStatusSuccessful shouldBe true
  }

  @Test
  fun `StepReport HttpRequestFailure`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val stepReportHttpFailure =
      StepReport(
        Step("1.3.7", Item(request = rawRequest)),
        Left(HttpRequestFailure(RuntimeException("fakeRTE"), requestInfo)),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportHttpFailure)
    stepReportHttpFailure.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `StepReport Bad Response`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val badResponseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeBadResponse",
        httpMsg = Response(BAD_REQUEST).body("fakeBadResponse"),
        moshiReVoman = moshiReVoman,
      )
    val stepReportBadRequest =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(badResponseInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportBadRequest)
    stepReportBadRequest.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `StepReport PostHookFailure`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val stepReportPostStepHookFailure =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        PostStepHookFailure(RuntimeException("fakeRTE"), requestInfo, responseInfo),
        null,
        null,
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportPostStepHookFailure)
    stepReportPostStepHookFailure.isHttpStatusSuccessful shouldBe true
  }

  @Test
  fun `ledger-skipped report is flagged isLedgerSkipped and the guard throws`() {
    val skipped =
      StepReport.ledgerSkipped(
        Step("1", Item(name = "schedule", request = Request())),
        setOf("bookingStart"),
        PostmanEnvironment(),
      )
    // No request/response was sent, yet the report is "successful".
    skipped.isSuccessful shouldBe true
    skipped.isLedgerSkipped shouldBe true
    // The guard a test's response-accessor calls must fail loud on it.
    val ex =
      org.junit.jupiter.api.assertThrows<IllegalStateException> {
        StepReport.assertNotLedgerSkipped(skipped)
      }
    ex.message!!.contains("LEDGER-SKIPPED") shouldBe true
  }

  @Test
  fun `a normally executed report is NOT ledger-skipped and passes the guard`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val executed =
      StepReport(
        Step("1", Item(request = rawRequest)),
        Right(requestInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    executed.isLedgerSkipped shouldBe false
    StepReport.assertNotLedgerSkipped(executed) // no-op, must not throw
  }

  @Test
  fun `http status successful when pm-test fails after 200 response`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val stepReportPmTestFailure =
      StepReport(
          Step("", Item(request = rawRequest)),
          Right(requestInfo),
          null,
          Right(responseInfo),
          pmEnvSnapshot = PostmanEnvironment(),
        )
        .copy(
          pmTestAssertions =
            listOf(PmTestAssertion("test1", false, false, "test failed", ExeType.POST_RES_JS))
        )
    println(stepReportPmTestFailure)
    stepReportPmTestFailure.isHttpStatusSuccessful shouldBe true
  }

  @Test
  fun `http status successful when polling fails after 200 response`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val pollingFailure =
      PollingFailure.PollingTimeoutFailure(
        failure = RuntimeException("polling timeout"),
        pollAttempts = 5,
        timeout = Duration.ofSeconds(10),
        lastPollResponse = Response(OK),
      )
    val stepReportPollingFailure =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        null,
        pollingFailure,
        null,
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportPollingFailure)
    stepReportPollingFailure.isHttpStatusSuccessful shouldBe true
  }

  @Test
  fun `http status unsuccessful when genuine non-2xx response`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val badResponseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeBadResponse",
        httpMsg = Response(BAD_REQUEST),
        moshiReVoman = moshiReVoman,
      )
    val stepReportBadResponse =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(badResponseInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportBadResponse)
    stepReportBadResponse.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `http status unsuccessful when request fails`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val stepReportRequestFailure =
      StepReport(
        Step("", Item(request = rawRequest)),
        Left(HttpRequestFailure(RuntimeException("connection error"), requestInfo)),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportRequestFailure)
    stepReportRequestFailure.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `http status unsuccessful when response fails`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val stepReportResponseFailure =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Left(
          PostResJSFailure(RuntimeException("response script error"), requestInfo, responseInfo)
        ),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportResponseFailure)
    stepReportResponseFailure.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `http status unsuccessful when pre-step hook fails`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val preStepHookFailure = PreStepHookFailure(RuntimeException("pre-hook error"), requestInfo)
    val stepReportPreHookFailure =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        preStepHookFailure,
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportPreHookFailure)
    stepReportPreHookFailure.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `http status successful for fully successful step`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val stepReportSuccess =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )
    println(stepReportSuccess)
    stepReportSuccess.isHttpStatusSuccessful shouldBe true
  }

  @Test
  fun `http status successful for skipped step`() {
    val skipped =
      StepReport.ledgerSkipped(
        Step("1", Item(name = "skipped-step", request = Request())),
        setOf("someKey"),
        PostmanEnvironment(),
      )
    skipped.isHttpStatusSuccessful shouldBe true
  }
}

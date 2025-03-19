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
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import io.kotest.matchers.shouldBe
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
        PostmanEnvironment(),
      )
    println(stepReportPostStepHookFailure)
    stepReportPostStepHookFailure.isHttpStatusSuccessful shouldBe true
  }
}

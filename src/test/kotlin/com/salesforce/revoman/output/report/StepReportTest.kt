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
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import io.kotest.matchers.shouldBe
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class StepReportTest {

  @Test
  fun `StepReport HttpStatusSuccessful`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo = TxnInfo(String::class.java, "fakeRequest", rawRequest.toHttpRequest())
    val stepReportSuccess =
      StepReport(Step("1.3.7", Item(request = rawRequest)), Right(requestInfo))
    println(stepReportSuccess)
    stepReportSuccess.isHttpStatusSuccessful shouldBe true
  }

  @Test
  fun `StepReport HttpRequestFailure`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo = TxnInfo(String::class.java, "fakeRequest", rawRequest.toHttpRequest())
    val stepReportHttpFailure =
      StepReport(
        Step("1.3.7", Item(request = rawRequest)),
        Left(HttpRequestFailure(RuntimeException("fakeRTE"), requestInfo)),
      )
    println(stepReportHttpFailure)
    stepReportHttpFailure.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `StepReport Bad Response`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo = TxnInfo(String::class.java, "fakeRequest", rawRequest.toHttpRequest())
    val badResponseInfo: TxnInfo<Response> =
      TxnInfo(String::class.java, "fakeBadResponse", Response(BAD_REQUEST).body("fakeBadResponse"))
    val stepReportBadRequest =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(badResponseInfo),
      )
    println(stepReportBadRequest)
    stepReportBadRequest.isHttpStatusSuccessful shouldBe false
  }

  @Test
  fun `StepReport PostHookFailure`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo = TxnInfo(String::class.java, "fakeRequest", rawRequest.toHttpRequest())
    val responseInfo: TxnInfo<Response> = TxnInfo(String::class.java, "fakeResponse", Response(OK))
    val stepReportPostStepHookFailure =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        PostStepHookFailure(RuntimeException("fakeRTE")),
      )
    println(stepReportPostStepHookFailure)
    stepReportPostStepHookFailure.isHttpStatusSuccessful shouldBe true
  }
}

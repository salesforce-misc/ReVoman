/**
 * *************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * *************************************************************************************************
 */
package com.salesforce.revoman.output.report

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.output.report.Step.Companion.HTTP_METHOD_SEPARATOR
import com.salesforce.revoman.output.report.Step.Companion.INDEX_SEPARATOR
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import io.kotest.matchers.shouldBe
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.junit.jupiter.api.Test

class StepReportTest {

  @Test
  fun `StepReport isHttpStatusSuccessful`() {
    val stepName = "3${INDEX_SEPARATOR}GET${HTTP_METHOD_SEPARATOR}nature"
    val requestInfo: TxInfo<Request> =
      TxInfo(
        String::class.java,
        "fakeRequest",
        Request(POST, Uri.of("https://overfullstack.github.io/"))
      )
    val stepReportSuccess =
      StepReport(
        Step("", stepName, com.salesforce.revoman.internal.postman.state.Request()),
        Right(requestInfo)
      )
    println(stepReportSuccess)
    stepReportSuccess.isHttpStatusSuccessful shouldBe true

    val stepReportHttpFailure =
      StepReport(
        Step("", stepName, com.salesforce.revoman.internal.postman.state.Request()),
        Left(HttpRequestFailure(RuntimeException("fakeRTE"), requestInfo))
      )
    println(stepReportHttpFailure)
    stepReportHttpFailure.isHttpStatusSuccessful shouldBe false

    val badResponseInfo: TxInfo<Response> =
      TxInfo(String::class.java, "fakeResponse", Response(BAD_REQUEST).body("fakeResponse"))
    val stepReportBadRequest =
      StepReport(
        Step("", stepName, com.salesforce.revoman.internal.postman.state.Request()),
        Right(requestInfo),
        null,
        Right(badResponseInfo)
      )
    println(stepReportBadRequest)
    stepReportBadRequest.isHttpStatusSuccessful shouldBe false

    val responseInfo: TxInfo<Response> = TxInfo(String::class.java, "fakeResponse", Response(OK))
    val stepReportPostHookFailure =
      StepReport(
        Step("", stepName, com.salesforce.revoman.internal.postman.state.Request()),
        Right(requestInfo),
        null,
        Right(responseInfo),
        PostHookFailure(RuntimeException("fakeRTE"))
      )
    println(stepReportPostHookFailure)
    stepReportPostHookFailure.isHttpStatusSuccessful shouldBe true
  }
}

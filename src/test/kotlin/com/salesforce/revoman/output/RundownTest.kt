package com.salesforce.revoman.output

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.report.TxInfo
import io.kotest.matchers.shouldBe
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.junit.jupiter.api.Test

class RundownTest {

  @Test
  fun `build step name`() {
    buildStepName("1.2.1", "POST", "product-setup", "OneTime", "One-Time Product") shouldBe
      "1.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}OneTime${FOLDER_DELIMITER}One-Time Product"
  }

  // ! TODO 04/12/23 gopala.akshintala: Add assertions for toString
  @Test
  fun `StepReport toString`() {
    val requestInfo: TxInfo<Request> =
      TxInfo(
        String::class.java,
        "fakeRequest",
        Request(POST, Uri.of("https://overfullstack.github.io/"))
      )
    val stepReportSuccess = StepReport(Right(requestInfo))
    println(stepReportSuccess)
    stepReportSuccess.isHttpStatusSuccessful shouldBe true

    val stepReportHttpFailure =
      StepReport(Left(HttpRequestFailure(RuntimeException("fakeRTE"), requestInfo)))
    println(stepReportHttpFailure)
    stepReportHttpFailure.isHttpStatusSuccessful shouldBe false

    val badResponseInfo: TxInfo<Response> =
      TxInfo(String::class.java, "fakeResponse", Response(BAD_REQUEST).body("fakeResponse"))
    val stepReportBadRequest = StepReport(Right(requestInfo), null, Right(badResponseInfo))
    println(stepReportBadRequest)
    stepReportBadRequest.isHttpStatusSuccessful shouldBe false

    val responseInfo: TxInfo<Response> = TxInfo(String::class.java, "fakeResponse", Response(OK))
    val stepReportPostHookFailure =
      StepReport(
        Right(requestInfo),
        null,
        Right(responseInfo),
        PostHookFailure(RuntimeException("fakeRTE"))
      )
    println(stepReportPostHookFailure)
    stepReportPostHookFailure.isHttpStatusSuccessful shouldBe true
  }
}

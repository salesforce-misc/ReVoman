package com.salesforce.revoman.output

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.output.Rundown.StepReport
import com.salesforce.revoman.output.Rundown.StepReport.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import io.kotest.matchers.shouldBe
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Uri
import org.junit.jupiter.api.Test

class RundownTest {

  @Test
  fun `build step name`() {
    buildStepName("1.2.1", "POST", "product-setup", "OneTime", "One-Time Product") shouldBe
      "1.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}OneTime${FOLDER_DELIMITER}One-Time Product"
  }

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
    val stepReportFailure =
      StepReport(Left(HttpRequestFailure(RuntimeException("fakeRTE"), requestInfo)))
    println(stepReportFailure)
    val badRequestRespInfo: TxInfo<Response> =
      TxInfo(String::class.java, "fakeResponse", Response(BAD_REQUEST).body("fakeResponse"))
    val stepReportBadRequest = StepReport(Right(requestInfo), null, Right(badRequestRespInfo))
    println(stepReportBadRequest)
  }
}

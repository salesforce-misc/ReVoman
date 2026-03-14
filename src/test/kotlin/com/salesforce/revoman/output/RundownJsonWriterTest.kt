/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import com.squareup.moshi.JsonReader
import io.kotest.matchers.shouldBe
import okio.Buffer
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class RundownJsonWriterTest {

  private val moshiReVoman = initMoshi()

  private fun newRequest(
    method: String = POST.toString(),
    url: String = "https://api.test.com/v1/objects",
  ) = Request(method = method, url = Url(url))

  private fun newRequestInfo(rawRequest: Request): TxnInfo<org.http4k.core.Request> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "testRequest",
      httpMsg = rawRequest.toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )

  private fun newResponseInfo(
    status: org.http4k.core.Status = OK,
    body: String = "",
  ): TxnInfo<Response> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "testResponse",
      httpMsg = Response(status).body(body),
      moshiReVoman = moshiReVoman,
    )

  private fun parseJson(json: String): Map<*, *> {
    val reader = JsonReader.of(Buffer().writeUtf8(json))
    return reader.readJsonValue() as Map<*, *>
  }

  private fun successfulRundown(): Rundown {
    val rawRequest = newRequest()
    val requestInfo = newRequestInfo(rawRequest)
    val responseInfo = newResponseInfo(OK, """{"id":"123"}""")
    return Rundown(
      stepReports =
        listOf(
          StepReport(
            Step("1", Item(name = "Create Object", request = rawRequest)),
            Right(requestInfo),
            responseInfo = Right(responseInfo),
            pmEnvSnapshot = PostmanEnvironment(),
          )
        ),
      mutableEnv = PostmanEnvironment<Any?>().apply { put("objectId", "123") },
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 1,
    )
  }

  private fun failureRundown(): Rundown {
    val rawRequest = newRequest(GET.toString(), "https://api.test.com/v1/objects/999")
    val requestInfo = newRequestInfo(rawRequest)
    val responseInfo = newResponseInfo(BAD_REQUEST, """{"error":"Not Found"}""")
    val rawRequest2 = newRequest()
    val requestInfo2 = newRequestInfo(rawRequest2)
    return Rundown(
      stepReports =
        listOf(
          StepReport(
            Step("1", Item(name = "Get Object", request = rawRequest)),
            Right(requestInfo),
            responseInfo = Right(responseInfo),
            pmEnvSnapshot = PostmanEnvironment(),
          ),
          StepReport(
            Step("2", Item(name = "Create Object", request = rawRequest2)),
            Left(HttpRequestFailure(RuntimeException("Connection refused"), requestInfo2)),
            pmEnvSnapshot = PostmanEnvironment(),
          ),
        ),
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 3,
    )
  }

  @Test
  fun `toJson SUMMARY contains counts and first failure`() {
    val rundown = failureRundown()
    val json = rundown.toJson(Verbosity.SUMMARY)
    val parsed = parseJson(json)

    parsed["providedStepsToExecuteCount"] shouldBe 3.0
    parsed["executedStepCount"] shouldBe 2.0
    parsed["areAllStepsSuccessful"] shouldBe false
    parsed["firstUnsuccessfulStepReport"].let { report ->
      val reportMap = report as Map<*, *>
      reportMap["name"] shouldBe "Get Object"
      reportMap["isSuccessful"] shouldBe false
    }
    // SUMMARY should NOT contain stepReports array
    parsed.containsKey("stepReports") shouldBe false
    parsed.containsKey("environment") shouldBe false
  }

  @Test
  fun `toJson SUMMARY for successful rundown has null firstUnsuccessfulStepReport`() {
    val rundown = successfulRundown()
    val json = rundown.toJson(Verbosity.SUMMARY)
    val parsed = parseJson(json)

    parsed["areAllStepsSuccessful"] shouldBe true
    parsed["firstUnsuccessfulStepReport"] shouldBe null
  }

  @Test
  fun `toJson STANDARD contains step reports with request and response`() {
    val rundown = successfulRundown()
    val json = rundown.toJson(Verbosity.STANDARD)
    val parsed = parseJson(json)

    val stepReports = parsed["stepReports"] as List<*>
    stepReports.size shouldBe 1
    val step = stepReports[0] as Map<*, *>
    step["name"] shouldBe "Create Object"
    step["isSuccessful"] shouldBe true
    step["displayName"].let {
      it as String
      it.contains("POST") shouldBe true
    }

    val request = step["request"] as Map<*, *>
    request["method"] shouldBe "POST"
    request.containsKey("uri") shouldBe true
    // STANDARD should NOT contain body
    request.containsKey("body") shouldBe false

    val response = step["response"] as Map<*, *>
    response["statusCode"] shouldBe 200.0
    response.containsKey("body") shouldBe false

    // STANDARD should NOT contain environment
    parsed.containsKey("environment") shouldBe false
  }

  @Test
  fun `toJson STANDARD includes failure details`() {
    val rundown = failureRundown()
    val json = rundown.toJson(Verbosity.STANDARD)
    val parsed = parseJson(json)

    val stepReports = parsed["stepReports"] as List<*>
    val failedStep = stepReports[1] as Map<*, *>
    failedStep["isSuccessful"] shouldBe false
    val failure = failedStep["failure"] as Map<*, *>
    failure["type"] shouldBe "http-request"
    failure["message"] shouldBe "Connection refused"
    // STANDARD should NOT contain stackTrace
    failure.containsKey("stackTrace") shouldBe false
  }

  @Test
  fun `toJson VERBOSE includes bodies headers environment and stackTrace`() {
    val rundown = failureRundown()
    val json = rundown.toJson(Verbosity.VERBOSE)
    val parsed = parseJson(json)

    // Should have environment
    parsed.containsKey("environment") shouldBe true

    val stepReports = parsed["stepReports"] as List<*>
    val step1 = stepReports[0] as Map<*, *>
    val response = step1["response"] as Map<*, *>
    response.containsKey("body") shouldBe true
    response.containsKey("headers") shouldBe true
    response["statusDescription"] shouldBe "Bad Request"

    // Failure step should have stackTrace
    val step2 = stepReports[1] as Map<*, *>
    val failure = step2["failure"] as Map<*, *>
    failure.containsKey("stackTrace") shouldBe true

    // Should have envSnapshot per step
    step1.containsKey("envSnapshot") shouldBe true
  }

  @Test
  fun `toJson VERBOSE includes request body and headers`() {
    val rundown = successfulRundown()
    val json = rundown.toJson(Verbosity.VERBOSE)
    val parsed = parseJson(json)

    val stepReports = parsed["stepReports"] as List<*>
    val step = stepReports[0] as Map<*, *>
    val request = step["request"] as Map<*, *>
    request.containsKey("body") shouldBe true
    request.containsKey("headers") shouldBe true
    val response = step["response"] as Map<*, *>
    response.containsKey("body") shouldBe true
    response["body"] shouldBe """{"id":"123"}"""
  }

  @Test
  fun `toJson with postStepHookFailure shows correct failure type`() {
    val rawRequest = newRequest()
    val requestInfo = newRequestInfo(rawRequest)
    val responseInfo = newResponseInfo()
    val rundown =
      Rundown(
        stepReports =
          listOf(
            StepReport(
              Step("1", Item(name = "Hook Fail Step", request = rawRequest)),
              Right(requestInfo),
              responseInfo = Right(responseInfo),
              postStepHookFailure =
                PostStepHookFailure(RuntimeException("hook failed"), requestInfo, responseInfo),
              pmEnvSnapshot = PostmanEnvironment(),
            )
          ),
        mutableEnv = PostmanEnvironment(),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 1,
      )
    val json = rundown.toJson(Verbosity.STANDARD)
    val parsed = parseJson(json)
    val step = (parsed["stepReports"] as List<*>)[0] as Map<*, *>
    val failure = step["failure"] as Map<*, *>
    failure["type"] shouldBe "post-step-hook"
    failure["message"] shouldBe "hook failed"
    step["isHttpStatusSuccessful"] shouldBe true
  }

  @Test
  fun `toJson default verbosity is STANDARD`() {
    val rundown = successfulRundown()
    val jsonDefault = rundown.toJson()
    val jsonStandard = rundown.toJson(Verbosity.STANDARD)
    jsonDefault shouldBe jsonStandard
  }
}

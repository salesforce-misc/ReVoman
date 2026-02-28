/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RundownJsonSerializer")
class RundownJsonSerializerTest {

  private val moshiReVoman = initMoshi()

  @Test
  @DisplayName("toJson with SUMMARY verbosity")
  fun testToJsonSummary() {
    val rundown = createSampleRundown()
    val json = rundown.toJson(RundownVerbosity.SUMMARY)

    json shouldContain "\"providedStepsToExecuteCount\""
    json shouldContain "\"executedStepCount\""
    json shouldContain "\"areAllStepsSuccessful\""
    json shouldContain "\"environmentKeys\""
    json shouldContain "key1"
    json shouldNotContain "\"value1\"" // Values not included in SUMMARY
    json shouldNotContain "\"requestInfo\"" // Request info not included in SUMMARY
  }

  @Test
  @DisplayName("toJson with STANDARD verbosity")
  fun testToJsonStandard() {
    val rundown = createSampleRundown()
    val json = rundown.toJson(RundownVerbosity.STANDARD)

    json shouldContain "\"providedStepsToExecuteCount\""
    json shouldContain "\"executedStepCount\""
    json shouldContain "\"environment\""
    json shouldContain "\"key1\""
    json shouldContain "\"value1\"" // Values included in STANDARD
    json shouldContain "\"stepReports\""
    json shouldContain "\"isSuccessful\""
    json shouldNotContain "\"requestInfo\"" // Request details not in STANDARD
  }

  @Test
  @DisplayName("toJson with DETAILED verbosity")
  fun testToJsonDetailed() {
    val rundown = createSampleRundownWithRequest()
    val json = rundown.toJson(RundownVerbosity.DETAILED)

    json shouldContain "\"providedStepsToExecuteCount\""
    json shouldContain "\"stepReports\""
    json shouldContain "\"requestInfo\""
    json shouldContain "\"uri\""
    json shouldContain "\"method\""
    json shouldContain "\"statusCode\""
    json shouldNotContain "\"body\"" // Body not included in DETAILED
  }

  @Test
  @DisplayName("toJson with FULL verbosity")
  fun testToJsonFull() {
    val rundown = createSampleRundownWithRequest()
    val json = rundown.toJson(RundownVerbosity.FULL)

    json shouldContain "\"providedStepsToExecuteCount\""
    json shouldContain "\"stepReports\""
    json shouldContain "\"requestInfo\""
    json shouldContain "\"uri\""
    json shouldContain "\"method\""
    json shouldContain "\"body\"" // Body included in FULL
    json shouldContain "\"pmEnvSnapshot\"" // Environment snapshot in FULL
  }

  @Test
  @DisplayName("toJson default verbosity is STANDARD")
  fun testToJsonDefaultVerbosity() {
    val rundown = createSampleRundown()
    val jsonDefault = rundown.toJson()
    val jsonStandard = rundown.toJson(RundownVerbosity.STANDARD)

    jsonDefault shouldBe jsonStandard
  }

  @Test
  @DisplayName("toJson produces valid JSON")
  fun testToJsonProducesValidJson() {
    val rundown = createSampleRundown()
    val json = rundown.toJson(RundownVerbosity.FULL)

    // Verify it's parseable JSON by parsing it back
    val parsed = moshiReVoman.fromJson<Map<String, Any>>(json)
    parsed shouldNotBe null
    parsed?.containsKey("executedStepCount") shouldBe true
  }

  private fun createSampleRundown(): Rundown {
    val rawRequest = Request(method = GET.toString(), url = Url("http://example.com"))
    val step = Step(index = "1", rawPMStep = Item(name = "Test Step", request = rawRequest))

    val stepReport =
      StepReport(
        step,
        null
          as
          arrow.core.Either<
            com.salesforce.revoman.output.report.failure.RequestFailure,
            TxnInfo<org.http4k.core.Request>,
          >?,
        null,
        null
          as
          arrow.core.Either<
            com.salesforce.revoman.output.report.failure.ResponseFailure,
            TxnInfo<org.http4k.core.Response>,
          >?,
        null,
        null,
        null,
        PostmanEnvironment(mutableMapOf("key1" to "value1", "key2" to 123), moshiReVoman),
      )

    return Rundown(
      stepReports = listOf(stepReport),
      mutableEnv =
        PostmanEnvironment(mutableMapOf("key1" to "value1", "key2" to 123), moshiReVoman),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 1,
    )
  }

  private fun createSampleRundownWithRequest(): Rundown {
    val rawRequest = Request(method = POST.toString(), url = Url("http://example.com/api/test"))
    val step =
      Step(index = "1", rawPMStep = Item(name = "Test Step With Request", request = rawRequest))

    val request =
      rawRequest
        .toHttpRequest(moshiReVoman)
        .body("""{"test": "data"}""")
        .header("Content-Type", "application/json")

    val response =
      Response(OK).body("""{"result": "success"}""").header("Content-Type", "application/json")

    val requestInfo =
      TxnInfo(
        isJson = true,
        txnObjType = Any::class.java,
        txnObj = null,
        httpMsg = request,
        moshiReVoman = moshiReVoman,
      )

    val responseInfo =
      TxnInfo(
        isJson = true,
        txnObjType = Any::class.java,
        txnObj = null,
        httpMsg = response,
        moshiReVoman = moshiReVoman,
      )

    val stepReport =
      StepReport(
        step = step,
        requestInfo = Right(requestInfo),
        preStepHookFailure = null,
        responseInfo = Right(responseInfo),
        postStepHookFailure = null,
        pollingFailure = null,
        pollingReport = null,
        pmEnvSnapshot =
          PostmanEnvironment(mutableMapOf("key1" to "value1", "key2" to 123), moshiReVoman),
      )

    return Rundown(
      stepReports = listOf(stepReport),
      mutableEnv =
        PostmanEnvironment(mutableMapOf("key1" to "value1", "key2" to 123), moshiReVoman),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 1,
    )
  }
}

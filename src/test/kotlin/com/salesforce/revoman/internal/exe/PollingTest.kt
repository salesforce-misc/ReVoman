/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.input.config.PollingCompletionPredicate
import com.salesforce.revoman.input.config.PollingConfig
import com.salesforce.revoman.input.config.PollingRequestBuilder
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.PollingReport
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingRequestFailure
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingTimeoutFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Duration
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PollingTest {

  private val moshiReVoman = initMoshi()

  @BeforeEach
  fun setUp() {
    mockkStatic(::prepareHttpClient)
  }

  @AfterEach
  fun tearDown() {
    unmockkStatic(::prepareHttpClient)
  }

  private fun successfulStepReport(): StepReport {
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
    return StepReport(
      Step("1", Item(request = rawRequest)),
      arrow.core.Either.Right(requestInfo),
      responseInfo = arrow.core.Either.Right(responseInfo),
      pmEnvSnapshot = PostmanEnvironment(),
    )
  }

  private fun failedStepReport(): StepReport {
    val rawRequest =
      Request(method = Method.POST.toString(), url = Url("https://test.example.com/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    return StepReport(
      Step("1", Item(request = rawRequest)),
      arrow.core.Either.Left(
        com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure(
          RuntimeException("fakeFailure"),
          requestInfo,
        )
      ),
      pmEnvSnapshot = PostmanEnvironment(),
    )
  }

  private fun emptyRundown(): Rundown =
    Rundown(
      stepReports = emptyList(),
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  private fun testPm(): PostmanSDK = PostmanSDK(moshiReVoman)

  private fun alwaysPickConfig(
    requestBuilder: PollingRequestBuilder = PollingRequestBuilder { _, _ ->
      org.http4k.core.Request(Method.GET, "http://poll.test/status")
    },
    completionPredicate: PollingCompletionPredicate = PollingCompletionPredicate { _, _ -> true },
    interval: Duration = Duration.ofMillis(50),
    timeout: Duration = Duration.ofMillis(200),
  ): PollingConfig =
    PollingConfig.poll { _, _ -> true }
      .request(requestBuilder)
      .every(interval)
      .timeout(timeout)
      .until(completionPredicate)

  @Test
  fun `returns null when step is not successful`() {
    val result =
      executePolling(
        pollingConfigs = listOf(alwaysPickConfig()),
        currentStepReport = failedStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result shouldBe Right(null)
  }

  @Test
  fun `returns null when no config matches`() {
    val neverPick = PostTxnStepPick { _, _ -> false }
    val config =
      PollingConfig.poll(neverPick)
        .request { _, _ -> org.http4k.core.Request(Method.GET, "http://x") }
        .every(Duration.ofMillis(50))
        .timeout(Duration.ofMillis(200))
        .until { _, _ -> true }
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result shouldBe Right(null)
  }

  @Test
  fun `returns PollingReport on first attempt`() {
    every { prepareHttpClient(any()) } returns { Response(OK).body("done") }
    val config = alwaysPickConfig(completionPredicate = PollingCompletionPredicate { _, _ -> true })
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result.shouldBeInstanceOf<Right<PollingReport>>()
    val report = result.value!!
    report.pollAttempts shouldBe 1
    report.responses.size shouldBe 1
  }

  @Test
  fun `returns PollingReport after multiple attempts`() {
    every { prepareHttpClient(any()) } returns { Response(OK).body("pending") }
    var callCount = 0
    val config =
      alwaysPickConfig(
        completionPredicate =
          PollingCompletionPredicate { _, _ ->
            callCount++
            callCount >= 3
          }
      )
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result.shouldBeInstanceOf<Right<PollingReport>>()
    val report = result.value!!
    report.pollAttempts shouldBe 3
    report.responses.size shouldBe 3
  }

  @Test
  fun `returns PollingRequestFailure when requestBuilder throws`() {
    val config =
      alwaysPickConfig(requestBuilder = { _, _ -> throw RuntimeException("request build failed") })
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result.shouldBeInstanceOf<Left<PollingRequestFailure>>()
    val failure = result.value
    failure.pollAttempts shouldBe 1
  }

  @Test
  fun `returns PollingRequestFailure when httpClient throws`() {
    every { prepareHttpClient(any()) } returns { throw RuntimeException("http call failed") }
    val config = alwaysPickConfig()
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result.shouldBeInstanceOf<Left<PollingRequestFailure>>()
    val failure = result.value
    failure.pollAttempts shouldBe 1
  }

  @Test
  fun `returns PollingTimeoutFailure when timeout expires`() {
    every { prepareHttpClient(any()) } returns { Response(OK).body("still pending") }
    val config =
      alwaysPickConfig(
        completionPredicate = PollingCompletionPredicate { _, _ -> false },
        interval = Duration.ofMillis(30),
        timeout = Duration.ofMillis(100),
      )
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result.shouldBeInstanceOf<Left<PollingTimeoutFailure>>()
  }

  @Test
  fun `completionPredicate exception is swallowed and treated as false`() {
    every { prepareHttpClient(any()) } returns { Response(OK).body("ok") }
    var callCount = 0
    val config =
      alwaysPickConfig(
        completionPredicate =
          PollingCompletionPredicate { _, _ ->
            callCount++
            when {
              callCount == 1 -> throw RuntimeException("predicate failed")
              else -> true
            }
          }
      )
    val result =
      executePolling(
        pollingConfigs = listOf(config),
        currentStepReport = successfulStepReport(),
        rundown = emptyRundown(),
        pm = testPm(),
        insecureHttp = false,
      )
    result.shouldBeInstanceOf<Right<PollingReport>>()
    val report = result.value!!
    report.pollAttempts shouldBe 2
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.salesforce.revoman.input.config.PollingConfig
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.output.ExeType.POLLING
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.PollingReport
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.PollingFailure
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingRequestFailure
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingTimeoutFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response

@JvmSynthetic
internal fun executePolling(
  pollingConfigs: List<PollingConfig>,
  currentStepReport: StepReport,
  rundown: Rundown,
  pm: PostmanSDK,
  insecureHttp: Boolean,
): Either<PollingFailure, PollingReport?> {
  if (!currentStepReport.isSuccessful) return null.right()
  val matchingConfig: PollingConfig =
    pollingConfigs.firstOrNull { it.pick.pick(currentStepReport, rundown) } ?: return null.right()
  logger.info { "${currentStepReport.step} Polling triggered" }
  val httpClient: HttpHandler = prepareHttpClient(insecureHttp)
  val responses: MutableList<Response> = mutableListOf()
  val startTime: Instant = Instant.now()
  var attempts = 0
  while (Duration.between(startTime, Instant.now()) < matchingConfig.timeout) {
    attempts++
    val pollAttempts = attempts
    runCatching(currentStepReport.step, POLLING) {
        matchingConfig.requestBuilder.buildRequest(currentStepReport, pm.environment)
      }
      .mapLeft { throwable: Throwable -> throwable to Request(Method.GET, "") }
      .flatMap { pollRequest: Request ->
        runCatching(currentStepReport.step, POLLING) { httpClient(pollRequest) }
          .mapLeft { throwable: Throwable -> throwable to pollRequest }
      }
      .map { response: Response ->
        responses.add(response)
        runCatching(currentStepReport.step, POLLING) {
            matchingConfig.completionPredicate.isComplete(response, pm.environment)
          }
          .fold({ false }, { it })
      }
      .fold(
        { (failure: Throwable, failedRequest: Request) ->
          return PollingRequestFailure(
              failure = failure,
              pollAttempts = pollAttempts,
              failedRequest = failedRequest,
            )
            .left()
        },
        { isComplete: Boolean ->
          if (isComplete) {
            val totalDuration: Duration = Duration.between(startTime, Instant.now())
            logger.info {
              "${currentStepReport.step} Polling completed after $pollAttempts attempts in $totalDuration"
            }
            return PollingReport(
                pollAttempts = pollAttempts,
                totalDuration = totalDuration,
                responses = responses,
              )
              .right()
          }
        },
      )
    Thread.sleep(matchingConfig.interval.toMillis())
  }
  val totalDuration: Duration = Duration.between(startTime, Instant.now())
  logger.info {
    "${currentStepReport.step} Polling timed out after $attempts attempts in $totalDuration"
  }
  return PollingTimeoutFailure(
      failure =
        RuntimeException(
          "Polling timed out after $totalDuration ($attempts attempts) for step ${currentStepReport.step}"
        ),
      pollAttempts = attempts,
      timeout = matchingConfig.timeout,
      lastPollResponse = responses.lastOrNull(),
    )
    .left()
}

private val logger = KotlinLogging.logger {}

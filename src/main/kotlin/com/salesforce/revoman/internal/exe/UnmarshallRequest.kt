/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Right
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.json.asA
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.UnmarshallRequestFailure
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Type
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Request
import org.http4k.format.ConfigurableMoshi

internal fun unmarshallRequest(
  currentStep: Step,
  pmRequest: com.salesforce.revoman.internal.postman.template.Request,
  kick: Kick,
  moshiReVoman: ConfigurableMoshi,
  stepReports: List<StepReport>
): Either<UnmarshallRequestFailure, TxInfo<Request>> {
  val httpRequest = pmRequest.toHttpRequest()
  val requestType: Type =
    kick
      .requestConfig()
      .firstOrNull {
        it.preTxnStepPick.pick(
          currentStep,
          TxInfo(null, null, httpRequest),
          Rundown(stepReports, pm.environment, kick.haltOnAnyFailureExcept())
        )
      }
      ?.also { logger.info { "$currentStep RequestConfig found : ${pprint(it)}" } }
      ?.requestType ?: Any::class.java
  return when {
    isJson(httpRequest) ->
      runChecked<Any?>(currentStep, UNMARSHALL_REQUEST) {
          pmRequest.body?.let { body -> moshiReVoman.asA(body.raw, requestType) }
        }
        .mapLeft { UnmarshallRequestFailure(it, TxInfo(requestType, null, httpRequest)) }
    else -> {
      // ! TODO 15/10/23 gopala.akshintala: xml2Json
      logger.info {
        "$currentStep No JSON found in the Request body or content-type header didn't match ${APPLICATION_JSON.value}"
      }
      Right(null)
    }
  }.map { TxInfo(requestType, it, pmRequest.toHttpRequest()) }
}

private val logger = KotlinLogging.logger {}

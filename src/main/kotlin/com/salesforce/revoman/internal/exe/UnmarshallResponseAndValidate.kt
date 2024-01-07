/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.flatMap
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.json.asA
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType.RESPONSE_VALIDATION
import com.salesforce.revoman.output.report.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxInfo
import com.salesforce.revoman.output.report.failure.ResponseFailure.ResponseValidationFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.ResponseValidationFailure.ValidationFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.UnmarshallResponseFailure
import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either.left
import java.lang.reflect.Type
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.format.ConfigurableMoshi

internal fun unmarshallResponseAndValidate(
  currentStepReport: StepReport,
  kick: Kick,
  moshiReVoman: ConfigurableMoshi,
  stepReports: List<StepReport>
): Either<StepReport, StepReport> {
  val responseInfo = currentStepReport.responseInfo?.get()!!
  val httpResponse = responseInfo.httpMsg
  return when {
    isJson(httpResponse) -> {
      val httpStatus = httpResponse.status.successful
      val responseConfig =
        kick.pickToResponseConfig()[httpStatus]?.let {
          it.firstOrNull { pick ->
            pick.postTxnStepPick.pick(
              currentStepReport,
              Rundown(
                stepReports + currentStepReport,
                pm.environment,
                kick.haltOnAnyFailureExcept()
              )
            )
          }
        }
      val currentStep = currentStepReport.step
      val responseType: Type =
        responseConfig
          ?.also { logger.info { "$currentStep ResponseConfig found : ${pprint(it)}" } }
          ?.responseType ?: Any::class.java
      runChecked(currentStep, UNMARSHALL_RESPONSE) {
          moshiReVoman.asA<Any>(httpResponse.bodyString(), responseType)
        }
        .mapLeft {
          currentStepReport.copy(
            responseInfo =
              left(
                UnmarshallResponseFailure(
                  it,
                  TxInfo(responseType, null, httpResponse),
                ),
              ),
          )
        }
        .flatMap { responseObj ->
          runChecked(currentStep, RESPONSE_VALIDATION) {
              responseConfig?.validationConfig?.let { validate(currentStep, responseObj, it) }
            }
            .fold(
              { validationExeException ->
                Left(
                  ResponseValidationFailure(
                    validationExeException,
                    responseInfo,
                  ),
                )
              },
              { validationFailure ->
                validationFailure?.let {
                  logger.info {
                    "${currentStepReport.step} Validation failed : ${pprint(validationFailure)}"
                  }
                  Left(
                    ResponseValidationFailure(
                      ValidationFailure(validationFailure),
                      responseInfo,
                    ),
                  )
                } ?: Right(responseInfo)
              },
            )
            .mapLeft { currentStepReport.copy(responseInfo = left(it)) }
        }
        .map { currentStepReport }
    }
    else -> {
      logger.info {
        "${currentStepReport.step} No JSON found in the Response body or content-type didn't match ${APPLICATION_JSON.value}"
      }
      Right(currentStepReport)
    }
  }
}

// ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
private fun validate(
  currentStep: Step,
  responseObj: Any,
  validationConfig: BaseValidationConfig<out Any, out Any>?
): Any? =
  validationConfig?.let {
    logger.info { "$currentStep ValidationConfig found : ${pprint(it)}" }
    Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      .orElse(null)
  }

private val logger = KotlinLogging.logger {}

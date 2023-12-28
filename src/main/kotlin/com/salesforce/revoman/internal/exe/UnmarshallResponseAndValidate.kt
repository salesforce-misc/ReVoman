package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.flatMap
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.asA
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxInfo
import com.salesforce.revoman.output.report.failure.ResponseFailure
import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import java.lang.reflect.Type
import org.http4k.format.ConfigurableMoshi

internal fun unmarshallResponseAndValidate(
  stepName: String,
  stepReport: StepReport,
  kick: Kick,
  moshiReVoman: ConfigurableMoshi,
  stepNameToReport: Map<String, StepReport>
): Either<StepReport, StepReport> {
  val responseInfo = stepReport.responseInfo?.get()!!
  val httpResponse = responseInfo.httpMsg
  return when {
    isContentTypeApplicationJson(httpResponse) -> {
      val httpStatus = httpResponse.status.successful
      val responseConfig =
        (getResponseConfigForStepName(
          stepName,
          httpStatus,
          kick.stepNameToResponseConfig(),
        )
          ?: kick.pickToResponseConfig()[httpStatus]?.let {
            pickResponseConfig(
              it,
              stepName,
              Rundown(
                stepNameToReport + (stepName to stepReport),
                pm.environment,
                kick.haltOnAnyFailureExceptForSteps()
              )
            )
          })
      val responseType: Type = responseConfig?.responseType ?: Any::class.java
      runChecked(stepName, ExeType.UNMARSHALL_RESPONSE) {
          moshiReVoman.asA<Any>(httpResponse.bodyString(), responseType)
        }
        .mapLeft {
          stepReport.copy(
            responseInfo =
              io.vavr.control.Either.left(
                ResponseFailure.UnmarshallResponseFailure(
                  it,
                  TxInfo(responseType, null, httpResponse),
                ),
              ),
          )
        }
        .flatMap { responseObj ->
          runChecked(stepName, ExeType.RESPONSE_VALIDATION) {
              responseConfig?.validationConfig?.let { validate(responseObj, it) }
            }
            .fold(
              { validationExeException ->
                Either.Left(
                  ResponseFailure.ResponseValidationFailure(
                    validationExeException,
                    responseInfo,
                  ),
                )
              },
              { validationFailure ->
                validationFailure?.let {
                  Either.Left(
                    ResponseFailure.ResponseValidationFailure(
                      ResponseFailure.ResponseValidationFailure.ValidationFailure(
                        validationFailure
                      ),
                      responseInfo,
                    ),
                  )
                } ?: Either.Right(responseInfo)
              },
            )
            .mapLeft { stepReport.copy(responseInfo = io.vavr.control.Either.left(it)) }
        }
        .map { stepReport }
    }
    else -> Either.Right(stepReport)
  }
}

// ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
private fun validate(
  responseObj: Any,
  validationConfig: BaseValidationConfig<out Any, out Any>?
): Any? =
  validationConfig?.let {
    Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      .orElse(null)
  }

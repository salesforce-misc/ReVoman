/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.flatMap
import com.salesforce.revoman.input.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.HookConfig.HookType.POST
import com.salesforce.revoman.input.HookConfig.HookType.PRE
import com.salesforce.revoman.input.Kick
import com.salesforce.revoman.input.RequestConfig
import com.salesforce.revoman.input.ResponseConfig
import com.salesforce.revoman.internal.asA
import com.salesforce.revoman.internal.deepFlattenItems
import com.salesforce.revoman.internal.executeTestScriptJs
import com.salesforce.revoman.internal.getHooksForStep
import com.salesforce.revoman.internal.getRequestConfigForStepName
import com.salesforce.revoman.internal.getResponseConfigForStepName
import com.salesforce.revoman.internal.initMoshi
import com.salesforce.revoman.internal.isContentTypeApplicationJson
import com.salesforce.revoman.internal.isStepNameInPassList
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.initPmEnvironment
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.internal.postman.state.Template
import com.salesforce.revoman.internal.prepareHttpClient
import com.salesforce.revoman.internal.readFileToString
import com.salesforce.revoman.internal.shouldStepBeExecuted
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport
import com.salesforce.revoman.output.Rundown.StepReport.ExeType
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.POST_HOOK
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.PRE_HOOK
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.RESPONSE_VALIDATION
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.TEST_SCRIPT_JS
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.Rundown.StepReport.HookFailure.PostHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.HookFailure.PreHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.Rundown.StepReport.RequestFailure.UnmarshallRequestFailure
import com.salesforce.revoman.output.Rundown.StepReport.ResponseFailure.ResponseValidationFailure
import com.salesforce.revoman.output.Rundown.StepReport.ResponseFailure.ResponseValidationFailure.ValidationFailure
import com.salesforce.revoman.output.Rundown.StepReport.ResponseFailure.TestScriptJsFailure
import com.salesforce.revoman.output.Rundown.StepReport.ResponseFailure.UnmarshallResponseFailure
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either.left
import java.lang.reflect.Type
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.format.ConfigurableMoshi

object ReVoman {
  @JvmStatic
  @OptIn(ExperimentalStdlibApi::class)
  fun revUp(kick: Kick): Rundown {
    initPmEnvironment(
      kick.environmentPaths(),
      kick.dynamicEnvironmentsFlattened(),
      kick.customDynamicVariables()
    )
    val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
    val pmStepsDeepFlattened =
      kick
        .templatePaths()
        .mapNotNull { pmTemplateAdapter.fromJson(readFileToString(it)) }
        .flatMap { (pmSteps, authFromRoot) ->
          pmSteps.deepFlattenItems(authFromRoot = authFromRoot)
        }
    val stepNameToReport =
      executeStepsSerially(
        pmStepsDeepFlattened,
        kick,
        initMoshi(
          kick.customAdaptersFromRequestConfig() + kick.customAdaptersFromResponseConfig(),
          kick.customAdapters(),
          kick.typesToIgnoreForMarshalling()
        )
      )
    return Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
  }

  private fun executeStepsSerially(
    pmStepsFlattened: List<Item>,
    kick: Kick,
    moshiReVoman: ConfigurableMoshi,
  ): Map<String, StepReport> {
    var noFailureInStep = true
    return pmStepsFlattened
      .asSequence()
      .takeWhile { noFailureInStep }
      .filter { shouldStepBeExecuted(kick.runOnlySteps(), kick.skipSteps(), it.name) }
      .fold(mapOf()) { stepNameToReport, itemWithRegex ->
        val stepName = itemWithRegex.name
        logger.info { "***** Processing Step: $stepName *****" }
        val pmRequest: com.salesforce.revoman.internal.postman.state.Request =
          RegexReplacer(pm.environment, kick.customDynamicVariables())
            .replaceRegex(itemWithRegex.request)
        val stepReport: StepReport = // --------### UNMARSHALL-REQUEST ###--------
          unmarshallRequest(stepName, pmRequest, kick.stepNameToRequestConfig(), moshiReVoman)
            .mapLeft { StepReport(Left(it)) }
            .map { requestInfo: TxInfo<Request> -> // --------### PRE-HOOKS ###--------
              preHookExe(stepName, kick, requestInfo, stepNameToReport)?.mapLeft {
                StepReport(Right(requestInfo), it)
              }
              requestInfo
            }
            .flatMap { requestInfo: TxInfo<Request> -> // --------### HTTP-REQUEST ###--------
              val httpRequest =
                RegexReplacer(pm.environment).replaceRegex(itemWithRegex.request).toHttpRequest()
              httpRequest(stepName, itemWithRegex, httpRequest, kick.insecureHttp())
                .mapLeft { StepReport(Left(HttpRequestFailure(it, requestInfo))) }
                .map { StepReport(Right(requestInfo), null, Right(TxInfo(httpMsg = it))) }
            }
            .flatMap { stepReport: StepReport -> // --------### TEST-SCRIPT-JS ###--------
              val httpResponse = stepReport.responseInfo?.get()?.httpMsg!!
              runChecked(stepName, TEST_SCRIPT_JS) {
                  executeTestScriptJs(
                    itemWithRegex.event,
                    kick.customDynamicVariables(),
                    pmRequest,
                    httpResponse
                  )
                }
                .mapLeft {
                  stepReport.copy(
                    responseInfo = left(TestScriptJsFailure(it, TxInfo(httpMsg = httpResponse)))
                  )
                }
                .map { stepReport }
            }
            .flatMap { stepReport: StepReport -> // ---### UNMARSHALL + VALIDATE RESPONSE ###---
              unmarshallResponseAndValidate(
                stepName,
                stepReport,
                kick.stepNameToResponseConfig(),
                moshiReVoman
              )
            }
            .map { stepReport: StepReport -> // --------### POST-HOOKS ###--------
              postHookExe(stepName, kick, stepNameToReport + (stepName to stepReport))
                ?.fold(
                  { stepReport.copy(postHookFailure = it) },
                  { stepReport.copy(envSnapshot = pm.environment.copy()) },
                )
                ?: stepReport
            }
            .fold({ it }, { it })
        // * NOTE 15/10/23 gopala.akshintala: http status code can be non-success
        noFailureInStep =
          stepReport.isSuccessful ||
            kick.haltOnAnyFailure() ||
            isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
        stepNameToReport + (stepName to stepReport)
      }
  }

  private fun unmarshallRequest(
    stepName: String,
    pmRequest: com.salesforce.revoman.internal.postman.state.Request,
    stepNameToRequestConfig: Map<String, RequestConfig>,
    moshiReVoman: ConfigurableMoshi
  ): Either<UnmarshallRequestFailure, TxInfo<Request>> {
    val requestType: Type =
      getRequestConfigForStepName(stepName, stepNameToRequestConfig)?.requestType ?: Any::class.java
    val httpRequest = pmRequest.toHttpRequest()
    return when {
      isContentTypeApplicationJson(httpRequest) ->
        runChecked<Any?>(stepName, UNMARSHALL_REQUEST) {
            pmRequest.body?.let { body -> moshiReVoman.asA(body.raw, requestType) }
          }
          .mapLeft { UnmarshallRequestFailure(it, TxInfo(requestType, null, httpRequest)) }
      else -> Right(null) // ! TODO 15/10/23 gopala.akshintala: xml2Json
    }.map { TxInfo(requestType, it, pmRequest.toHttpRequest()) }
  }

  private fun preHookExe(
    stepName: String,
    kick: Kick,
    requestInfo: TxInfo<Request>,
    stepNameToReport: Map<String, StepReport>
  ): Either<PreHookFailure, Unit>? =
    getHooksForStep<PreHook>(stepName, PRE, kick.hooksFlattened())
      .asSequence()
      .map { preHook ->
        runChecked(stepName, PRE_HOOK) {
            preHook.accept(
              stepName,
              requestInfo,
              Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
            )
          }
          .mapLeft { PreHookFailure(it, requestInfo) }
      }
      .firstOrNull { it.isLeft() }

  private fun httpRequest(
    stepName: String,
    itemWithRegex: Item,
    httpRequest: Request,
    insecureHttp: Boolean
  ): Either<Throwable, Response> =
    runChecked(stepName, HTTP_REQUEST) {
      // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
      // * as there can be intermediate auths
      val httpClient: HttpHandler =
        prepareHttpClient(
          pm.environment.getString(itemWithRegex.auth?.bearerTokenKeyFromRegex),
          insecureHttp,
        )
      httpClient(httpRequest)
    }

  private fun unmarshallResponseAndValidate(
    stepName: String,
    stepReport: StepReport,
    stepNameToResponseConfig: Map<Pair<Boolean, String>, ResponseConfig>,
    moshiReVoman: ConfigurableMoshi
  ): Either<StepReport, StepReport> {
    val responseInfo = stepReport.responseInfo?.get()!!
    val httpResponse = responseInfo.httpMsg
    return when {
      isContentTypeApplicationJson(httpResponse) -> {
        val responseConfig =
          getResponseConfigForStepName(
            stepName,
            httpResponse.status.successful,
            stepNameToResponseConfig,
          )
        val responseType: Type = responseConfig?.responseType ?: Any::class.java
        runChecked(stepName, UNMARSHALL_RESPONSE) {
            moshiReVoman.asA<Any>(httpResponse.bodyString(), responseType)
          }
          .mapLeft {
            stepReport.copy(
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
            runChecked(stepName, RESPONSE_VALIDATION) {
                responseConfig?.validationConfig?.let { validate(responseObj, it) }
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
                    Left(
                      ResponseValidationFailure(
                        ValidationFailure(validationFailure),
                        responseInfo,
                      ),
                    )
                  }
                    ?: Right(responseInfo)
                },
              )
              .mapLeft { stepReport.copy(responseInfo = left(it)) }
          }
          .map { stepReport }
      }
      else -> Right(stepReport)
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

  private fun postHookExe(
    stepName: String,
    kick: Kick,
    stepNameToStepReport: Map<String, StepReport>
  ): Either<PostHookFailure, Unit>? =
    getHooksForStep<PostHook>(stepName, POST, kick.hooksFlattened())
      .asSequence()
      .map { postHook ->
        runChecked(stepName, POST_HOOK) {
            postHook.accept(
              stepName,
              Rundown(stepNameToStepReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
            )
          }
          .mapLeft { PostHookFailure(it) }
      }
      .firstOrNull { it.isLeft() }

  private fun <T> runChecked(
    stepName: String,
    exeType: ExeType,
    fn: () -> T
  ): Either<Throwable, T> =
    runCatching(fn)
      .fold(
        { Right(it) },
        {
          logger.error(it) { "‼️ $stepName: Exception while executing $exeType" }
          Left(it)
        }
      )
}

private val logger = KotlinLogging.logger {}

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
import arrow.core.left
import arrow.core.right
import com.salesforce.revoman.input.HookConfig
import com.salesforce.revoman.input.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.HookConfig.HookType
import com.salesforce.revoman.input.HookConfig.HookType.POST
import com.salesforce.revoman.input.HookConfig.HookType.PRE
import com.salesforce.revoman.input.Kick
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
import com.salesforce.revoman.internal.postman.postManVariableRegex
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.internal.postman.state.Template
import com.salesforce.revoman.internal.prepareHttpClient
import com.salesforce.revoman.internal.readFileToString
import com.salesforce.revoman.internal.shouldStepBeExecuted
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport
import com.salesforce.revoman.output.Rundown.StepReport.Failure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.POST_HOOK
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.PRE_HOOK
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.RESPONSE_VALIDATION
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.TEST_SCRIPT_JS
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.Rundown.StepReport.Failure.HttpRequestFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.PostHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.PreHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ResponseValidationFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.TestScriptJsFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.UnknownFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.UnmarshallRequestFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.ValidationFailure
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Type
import java.util.*
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.format.ConfigurableMoshi

object ReVoman {
  @JvmStatic
  @OptIn(ExperimentalStdlibApi::class)
  fun revUp(kick: Kick): Rundown {
    // ! TODO 18/06/23 gopala.akshintala: Add some more require conditions and Move to a separate
    // component Config validation
    // ! TODO 22/06/23 gopala.akshintala: Validate if validation config for a step is mentioned but
    // the stepName is not present
    // ! TODO 22/06/23 gopala.akshintala: Validate if steps with the same name are used in config
    require(Collections.disjoint(kick.runOnlySteps(), kick.skipSteps())) {
      "runOnlySteps and skipSteps cannot have intersection"
    }
    initPmEnvironment(
      kick.environmentPath(),
      kick.dynamicEnvironment(),
      kick.customDynamicVariables()
    )
    val (pmSteps, auth) =
      Moshi.Builder().build().adapter<Template>().fromJson(readFileToString(kick.templatePath()))
        ?: return Rundown()
    val bearerTokenKey =
      kick.bearerTokenKey()
        ?: auth?.bearer?.firstOrNull()?.value?.let {
          postManVariableRegex.find(it)?.groups?.get("variableKey")?.value ?: ""
        }
    val moshiReVoman =
      initMoshi(
        kick.customAdaptersFromRequestConfig() + kick.customAdaptersFromResponseConfig(),
        kick.customAdapters(),
        kick.typesToIgnoreForMarshalling()
      )
    val stepNameToReport = executeStep(pmSteps, kick, moshiReVoman, bearerTokenKey)
    return Rundown(stepNameToReport, pm.environment)
  }

  private fun executeStep(
    pmSteps: List<Item>,
    kick: Kick,
    moshiReVoman: ConfigurableMoshi,
    bearerTokenKey: String?
  ): Map<String, StepReport> {
    var noFailureInStep = true
    val stepNameToReport =
      pmSteps
        .deepFlattenItems()
        .asSequence()
        .takeWhile { noFailureInStep }
        .filter { shouldStepBeExecuted(kick.runOnlySteps(), kick.skipSteps(), it.name) }
        .fold<Item, Map<String, StepReport>>(mapOf()) { stepNameToReport, itemWithRegex ->
          val stepName = itemWithRegex.name
          logger.info { "***** Processing Step: $stepName *****" }
          val pmRequest: com.salesforce.revoman.internal.postman.state.Request =
            RegexReplacer(pm.environment, kick.customDynamicVariables())
              .replaceRegex(itemWithRegex.request)
          val requestType =
            getRequestConfigForStepName(stepName, kick.requestConfigFlattened())?.requestType
          val unmarshallAndPreHookResult: Either<StepReport, Either<StepReport, TxInfo<Request>>> =
            executePreHooks(
              stepName,
              pmRequest,
              requestType,
              stepNameToReport,
              kick.hooksFlattened(),
              moshiReVoman,
            )
          val requestInfo: TxInfo<Request> =
            when {
              unmarshallAndPreHookResult.isLeft() ->
                return@fold stepNameToReport +
                  (stepName to unmarshallAndPreHookResult.leftOrNull()!!)
              else -> {
                val preHookResult = unmarshallAndPreHookResult.getOrNull()!!
                when {
                  preHookResult.isLeft() ->
                    return@fold stepNameToReport + (stepName to preHookResult.leftOrNull()!!)
                  else ->
                    preHookResult
                      .getOrNull()!!
                      .copy(
                        httpMsg =
                          RegexReplacer(pm.environment).replaceRegex(pmRequest).toHttpRequest()
                      )
                }
              }
            }
          // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
          // * as there can be intermediate auths
          val httpClient: HttpHandler =
            prepareHttpClient(pm.environment.getString(bearerTokenKey), kick.insecureHttp())
          val stepReport: StepReport =
            runChecked(stepName, HTTP_REQUEST) { httpClient(requestInfo.httpMsg) }
              .mapLeft { HttpRequestFailure(it, requestInfo) }
              .flatMap { httpResponse ->
                when {
                  httpResponse.status.successful -> {
                    runChecked(stepName, TEST_SCRIPT_JS) {
                        executeTestScriptJs(
                          itemWithRegex.event,
                          kick.customDynamicVariables(),
                          pmRequest,
                          httpResponse
                        )
                      }
                      .mapLeft {
                        TestScriptJsFailure(it, requestInfo, TxInfo(null, null, httpResponse))
                      }
                      .flatMap {
                        when {
                          isContentTypeApplicationJson(httpResponse) ->
                            handleResponse(
                              stepName,
                              moshiReVoman,
                              httpResponse,
                              kick.responseConfigFlattened().first,
                              requestInfo
                            )
                          else ->
                            Right(
                              TxInfo(null, null, httpResponse),
                            )
                        }
                      }
                  }
                  else ->
                    handleResponse(
                      stepName,
                      moshiReVoman,
                      httpResponse,
                      kick.responseConfigFlattened().second,
                      requestInfo
                    )
                }
              }
              .fold(
                { StepReport(Right(requestInfo), null, Left(it), null, pm.environment.copy()) },
                { responseInfo ->
                  val stepReportBeforePostHook =
                    StepReport(
                      Right(requestInfo),
                      null,
                      Right(responseInfo),
                      null,
                      pm.environment.copy(),
                    )
                  postHook(
                      stepName,
                      kick.hooksFlattened(),
                      stepNameToReport + (stepName to stepReportBeforePostHook),
                    )
                    ?.fold(
                      { stepReportBeforePostHook.copy(postHookFailure = it) },
                      { stepReportBeforePostHook },
                    )
                    ?: stepReportBeforePostHook
                },
              )
              .also {
                noFailureInStep =
                  it.isSuccessful ||
                    isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
              }
          stepNameToReport +
            (stepName to stepReport.copy(postmanEnvironmentSnapshot = pm.environment.copy()))
        }
    return stepNameToReport
  }

  private fun executePreHooks(
    stepName: String,
    pmRequest: com.salesforce.revoman.internal.postman.state.Request,
    requestType: Type?,
    stepNameToReport: Map<String, StepReport>,
    hooksFlattened: Map<HookType, List<HookConfig>>,
    moshiReVoman: ConfigurableMoshi
  ): Either<StepReport, Either<StepReport, TxInfo<Request>>> =
    unmarshallRequest(stepName, pmRequest, requestType, moshiReVoman)
      .mapLeft { StepReport(Left(it), null, null, null, pm.environment.copy()) }
      .map { requestObj ->
        val txInfo = TxInfo(requestType, requestObj, pmRequest.toHttpRequest())
        preHookExe(stepName, hooksFlattened, txInfo, stepNameToReport)
          ?.mapLeft { preHookFailure ->
            StepReport(Right(txInfo), preHookFailure, null, null, pm.environment.copy())
          }
          ?.map { txInfo }
          ?: Right(txInfo)
      }

  private fun unmarshallRequest(
    stepName: String,
    pmRequest: com.salesforce.revoman.internal.postman.state.Request,
    requestType: Type?,
    moshiReVoman: ConfigurableMoshi
  ): Either<UnmarshallRequestFailure, Any?> {
    return requestType?.let { rt ->
      runChecked<Any?>(stepName, UNMARSHALL_REQUEST) {
          pmRequest.body?.let { body -> moshiReVoman.asA(body.raw, rt) }
        }
        .mapLeft {
          UnmarshallRequestFailure(it, TxInfo(requestType, null, pmRequest.toHttpRequest()))
        }
    }
      ?: Right(null)
  }

  private fun preHookExe(
    stepName: String,
    hooksFlattened: Map<HookType, List<HookConfig>>,
    requestInfo: TxInfo<Request>,
    stepNameToReport: Map<String, StepReport>
  ): Either<PreHookFailure, Unit>? =
    getHooksForStep<PreHook>(stepName, PRE, hooksFlattened)
      .asSequence()
      .map { preHook ->
        runChecked(stepName, PRE_HOOK) {
            preHook.accept(stepName, requestInfo, Rundown(stepNameToReport, pm.environment))
          }
          .mapLeft { PreHookFailure(it, requestInfo) }
      }
      .firstOrNull { it.isLeft() }

  private fun postHook(
    stepName: String,
    hooksFlattened: Map<HookType, List<HookConfig>>,
    stepNameToStepReport: Map<String, StepReport>
  ): Either<PostHookFailure, Unit>? =
    getHooksForStep<PostHook>(stepName, POST, hooksFlattened)
      .asSequence()
      .map { postHook ->
        runChecked(stepName, POST_HOOK) {
            postHook.accept(stepName, Rundown(stepNameToStepReport, pm.environment))
          }
          .mapLeft { PostHookFailure(it) }
      }
      .firstOrNull { it.isLeft() }

  private fun handleResponse(
    stepName: String,
    moshiReVoman: ConfigurableMoshi,
    httpResponse: Response,
    responseConfigs: List<ResponseConfig>,
    requestInfo: TxInfo<Request>
  ): Either<Failure, TxInfo<Response>> {
    val responseConfig: ResponseConfig? = getResponseConfigForStepName(stepName, responseConfigs)
    val responseType: Type = responseConfig?.responseType ?: Any::class.java as Type
    return runChecked(stepName, UNMARSHALL_RESPONSE) {
        moshiReVoman.asA<Any>(httpResponse.bodyString(), responseType)
      }
      .mapLeft {
        Failure.UnmarshallResponseFailure(it, requestInfo, TxInfo(responseType, null, httpResponse))
      }
      .flatMap { responseObj ->
        runChecked(stepName, RESPONSE_VALIDATION) {
            responseConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
          }
          .fold(
            { exceptionDuringValidation ->
              ResponseValidationFailure(
                  exceptionDuringValidation,
                  requestInfo,
                  TxInfo(responseType, responseObj, httpResponse)
                )
                .left()
            },
            { validationFailure ->
              validationFailure?.let {
                ResponseValidationFailure(
                    ValidationFailure(RESPONSE_VALIDATION, validationFailure),
                    requestInfo,
                    TxInfo(responseType, responseObj, httpResponse)
                  )
                  .left()
              }
                ?: TxInfo(responseType, responseObj, httpResponse).right()
            }
          )
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

  private fun <T> runChecked(stepName: String, exeType: ExeType, fn: () -> T): Either<Failure, T> =
    Either.catch(fn)
      .onLeft { logger.error(it) { "‼️ $stepName: Exception while executing $exeType" } }
      .mapLeft { UnknownFailure(exeType, it) }
}

private val logger = KotlinLogging.logger {}

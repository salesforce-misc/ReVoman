/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman

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
import com.salesforce.revoman.internal.getResponseConfigForStepName
import com.salesforce.revoman.internal.initMoshi
import com.salesforce.revoman.internal.isContentTypeApplicationJson
import com.salesforce.revoman.internal.isStepNameInPassList
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.initPmEnvironment
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.postManVariableRegex
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.internal.postman.state.Request
import com.salesforce.revoman.internal.postman.state.Template
import com.salesforce.revoman.internal.prepareHttpClient
import com.salesforce.revoman.internal.readFileToString
import com.salesforce.revoman.internal.shouldStepBeExecuted
import com.salesforce.revoman.internal.toEither
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.StepReport
import com.salesforce.revoman.output.StepReport.Failure
import com.salesforce.revoman.output.StepReport.Failure.ExeType
import com.salesforce.revoman.output.StepReport.Failure.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.StepReport.Failure.ExeType.MARSHALL_RESPONSE
import com.salesforce.revoman.output.StepReport.Failure.ExeType.POST_HOOK
import com.salesforce.revoman.output.StepReport.Failure.ExeType.PRE_HOOK
import com.salesforce.revoman.output.StepReport.Failure.ExeType.RESPONSE_VALIDATION
import com.salesforce.revoman.output.StepReport.Failure.ExeType.TEST_SCRIPT_JS
import com.salesforce.revoman.output.StepReport.TxInfo
import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type
import java.util.*
import org.http4k.core.HttpHandler
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
        kick.environmentPath(), kick.dynamicEnvironment(), kick.customDynamicVariables())
    val (pmSteps, auth) =
        Moshi.Builder().build().adapter<Template>().fromJson(readFileToString(kick.templatePath()))
            ?: return Rundown()
    val bearerTokenKey =
        kick.bearerTokenKey()
            ?: auth?.bearer?.firstOrNull()?.value?.let {
              postManVariableRegex.find(it)?.groups?.get("variableKey")?.value ?: ""
            }
    val moshiReVoman = initMoshi(kick.customAdaptersForResponse(), kick.typesInResponseToIgnore())
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
              val pmRequest: Request =
                  RegexReplacer(pm.environment, kick.customDynamicVariables())
                      .replaceRegex(itemWithRegex.request)
              val httpRequest: org.http4k.core.Request = pmRequest.toHttpRequest()

            // ! TODO 18/09/23 gopala.akshintala: Implement MARSHALL_REQUEST

              preHook(stepName, kick.hooksFlattened(), httpRequest, stepNameToReport)?.let {
                return@fold stepNameToReport +
                    (stepName to
                        StepReport(
                            TxInfo(httpMsg = httpRequest),
                            left(it.left),
                            null,
                            pm.environment.copy()))
              }
              // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
              // * as there can be intermediate auths
              val httpClient: HttpHandler =
                  prepareHttpClient(pm.environment.getString(bearerTokenKey), kick.insecureHttp())
              val stepReport: StepReport =
                  runChecked(stepName, HTTP_REQUEST) { httpClient(httpRequest) }
                      .flatMap { httpResponse ->
                        when {
                          httpResponse.status.successful -> {
                            runChecked(stepName, TEST_SCRIPT_JS) {
                                  executeTestScriptJs(pmRequest, itemWithRegex.event, httpResponse)
                                }
                                .flatMap {
                                  when {
                                    isContentTypeApplicationJson(httpResponse) ->
                                        handleResponse(
                                            stepName,
                                            moshiReVoman,
                                            httpResponse,
                                            kick.responseConfigFlattened().first)
                                    else ->
                                        right(
                                            TxInfo(
                                                String::class.java,
                                                httpResponse.bodyString(),
                                                httpResponse))
                                  }
                                }
                          }
                          else ->
                              handleResponse(
                                  stepName,
                                  moshiReVoman,
                                  httpResponse,
                                  kick.responseConfigFlattened().second)
                        }
                      }
                      .fold(
                          {
                            StepReport(
                                TxInfo(httpMsg = httpRequest),
                                left(it),
                                null,
                                pm.environment.copy())
                          },
                          { responseInfo ->
                            val stepReportBeforePostHook =
                                StepReport(
                                    TxInfo(httpMsg = httpRequest),
                                    right(responseInfo),
                                    null,
                                    pm.environment.copy())
                            postHook(
                                    stepName,
                                    kick.hooksFlattened(),
                                    stepNameToReport + (stepName to stepReportBeforePostHook))
                                ?.fold(
                                    { stepReportBeforePostHook.copy(postHookFailure = it) },
                                    { stepReportBeforePostHook })
                                ?: stepReportBeforePostHook
                          })
                      .also {
                        noFailureInStep =
                            it.isSuccessful ||
                                isStepNameInPassList(
                                    stepName, kick.haltOnAnyFailureExceptForSteps())
                      }
              stepNameToReport +
                  (stepName to stepReport.copy(postmanEnvironmentSnapshot = pm.environment.copy()))
            }
    return Rundown(stepNameToReport, pm.environment)
  }

  private fun preHook(
      stepName: String,
      hooksFlattened: Map<HookType, List<HookConfig>>,
      httpRequest: org.http4k.core.Request,
      stepNameToReport: Map<String, StepReport>
  ): Either<Failure, Unit>? =
      getHooksForStep<PreHook>(stepName, PRE, hooksFlattened)
          .asSequence()
          .map { preHook ->
            runChecked(stepName, PRE_HOOK) {
              preHook.accept(
                  stepName,
                  TxInfo(httpMsg = httpRequest),
                  Rundown(stepNameToReport, pm.environment))
            }
          }
          .firstOrNull { it.isLeft }

  private fun postHook(
      stepName: String,
      hooksFlattened: Map<HookType, List<HookConfig>>,
      it: Map<String, StepReport>
  ): Either<Failure, Unit>? =
      getHooksForStep<PostHook>(stepName, POST, hooksFlattened)
          .asSequence()
          .map { postHook ->
            runChecked(stepName, POST_HOOK) {
              postHook.accept(stepName, Rundown(it, pm.environment))
            }
          }
          .firstOrNull { it.isLeft }

  private fun handleResponse(
      stepName: String,
      moshiReVoman: ConfigurableMoshi,
      httpResponse: Response,
      responseConfigs: List<ResponseConfig>
  ): Either<Failure, TxInfo<Response>> {
    val responseConfig: ResponseConfig? = getResponseConfigForStepName(stepName, responseConfigs)
    val responseType: Type = responseConfig?.responseType ?: Any::class.java as Type
    return runChecked(stepName, MARSHALL_RESPONSE) {
          moshiReVoman.asA<Any>(httpResponse.bodyString(), responseType)
        }
        .flatMap { responseObj ->
          runChecked(stepName, RESPONSE_VALIDATION) {
                responseConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
              }
              .map { TxInfo(responseType, responseObj, httpResponse) }
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
      runCatching(fn)
          .onFailure { logger.error(it) { "❗️ $stepName: Exception while executing $exeType" } }
          .toEither()
          .mapLeft { Failure(exeType, it) }
}

private val logger = KotlinLogging.logger {}

/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman

import com.salesforce.revoman.input.HookType.POST
import com.salesforce.revoman.input.HookType.PRE
import com.salesforce.revoman.input.Kick
import com.salesforce.revoman.input.ResponseConfig
import com.salesforce.revoman.internal.asA
import com.salesforce.revoman.internal.deepFlattenItems
import com.salesforce.revoman.internal.executeTestScriptJs
import com.salesforce.revoman.internal.filterStep
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
import com.salesforce.revoman.internal.postman.state.Template
import com.salesforce.revoman.internal.prepareHttpClient
import com.salesforce.revoman.internal.readFileToString
import com.salesforce.revoman.output.Failures
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.StepReport
import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.rawType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import java.lang.reflect.Type
import java.util.*

class ReVoman private constructor(private val kick: Kick) {
  companion object {
    @JvmStatic
    fun revUp(kick: Kick): ReVoman =
      ReVoman(kick)
  }
  
  private val logger = KotlinLogging.logger {}

  @OptIn(ExperimentalStdlibApi::class)
  fun go(): Rundown {
    // ! TODO 18/06/23 gopala.akshintala: Add some more require conditions and Move to a separate
    // component Config validation
    // ! TODO 22/06/23 gopala.akshintala: Validate if validation config for a step is mentioned but
    // the stepName is not present
    require(Collections.disjoint(kick.runOnlySteps(), kick.skipSteps())) {
      "runOnlySteps and skipSteps cannot be intersected"
    }
    initPmEnvironment(kick.environmentPath(), kick.dynamicEnvironment(), kick.customDynamicVariables())
    val (pmSteps, auth) =
      Moshi.Builder().build().adapter<Template>().fromJson(readFileToString(kick.templatePath()))
        ?: return Rundown()
    val bearerTokenKey =
      kick.bearerTokenKey()
        ?: auth?.bearer?.firstOrNull()?.value?.let {
          postManVariableRegex.find(it)?.groups?.get("variableKey")?.value ?: ""
        }
    val moshiReVoman = initMoshi(kick.customAdaptersForResponse(), kick.typesInResponseToIgnore())
    var noFailure = true
    // ! TODO 22/06/23 gopala.akshintala: Validate if steps with the same name are used in config
    val stepNameToReport =
      pmSteps
        .deepFlattenItems()
        .asSequence()
        .takeWhile { noFailure }
        .filter { filterStep(kick.runOnlySteps(), kick.skipSteps(), it.name) }
        .fold<Item, Map<String, StepReport>>(mapOf()) { stepNameToReport, itemWithRegex ->
          val stepName = itemWithRegex.name
          logger.info { "***** Processing Step: $stepName *****" }
          val preHookFailures =
            getHooksForStep(kick.hooks(), stepName, PRE)
              .mapNotNull { hook ->
                runCatching { hook.accept(stepName, Rundown(stepNameToReport, pm.environment)) }
                  .exceptionOrNull()
              }
              .onEach {
                logger.error(it) { "❗️ $stepName: Exception while validating response" }
                noFailure = isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
              }
          if (preHookFailures.isNotEmpty()) {
            return@fold stepNameToReport +
              (stepName to
                StepReport(
                  hookFailures = Failures(pre = preHookFailures),
                  postmanEnvironmentSnapshot = pm.environment.copy()
                ))
          }
          // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
          // * as there can be intermediate auths
          val httpClient: HttpHandler =
            prepareHttpClient(pm.environment.getString(bearerTokenKey), kick.insecureHttp())
          val pmRequest =
            RegexReplacer(pm.environment, kick.customDynamicVariables())
              .replaceRegex(itemWithRegex.request)
          val request = pmRequest.toHttpRequest()
          val response: Response =
            runCatching { httpClient(request) }
              .getOrElse { throwable ->
                noFailure = isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
                return@fold stepNameToReport +
                  (stepName to
                    StepReport(
                      request,
                      httpFailure = throwable,
                      postmanEnvironmentSnapshot = pm.environment.copy()
                    ))
              }
          val stepReport: StepReport =
            when {
              response.status.successful -> {
                val testScriptJsResult = runCatching {
                  executeTestScriptJs(pmRequest, itemWithRegex.event, response)
                }
                if (testScriptJsResult.isFailure) {
                  logger.error(testScriptJsResult.exceptionOrNull()) {
                    "❗️ $stepName: Exception while executing test script"
                  }
                  noFailure = isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
                }
                when {
                  isContentTypeApplicationJson(response) -> {
                    val responseConfig: ResponseConfig? =
                      getResponseConfigForStepName(stepName, kick.responseConfig())
                    val successType = responseConfig?.successType ?: Any::class.java as Type
                    val responseObj = moshiReVoman.asA<Any>(response.bodyString(), successType)
                    val validationResult = runCatching {
                      responseConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
                    }
                    if (validationResult.isFailure) {
                      logger.error(validationResult.exceptionOrNull()) {
                        "❗️ $stepName: Exception while validating response"
                      }
                      noFailure = isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
                    }
                    StepReport(
                      request,
                      responseObj,
                      responseObj.javaClass,
                      response,
                      testScriptJsResult.exceptionOrNull(),
                      validationFailure = validationResult,
                      postmanEnvironmentSnapshot = pm.environment.copy()
                    )
                  }
                  else -> {
                    // ! TODO gopala.akshintala 04/08/22: Support other non-JSON content types
                    StepReport(
                      request,
                      response.bodyString(),
                      String::class.java,
                      response,
                      testScriptJsFailure = testScriptJsResult.exceptionOrNull(),
                      postmanEnvironmentSnapshot = pm.environment.copy()
                    )
                  }
                }
              }
              else -> {
                logger.error { "❌ $stepName: Http request failed" }
                noFailure = isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
                val responseConfig: ResponseConfig? =
                  getResponseConfigForStepName(stepName, kick.responseConfig())
                when {
                  responseConfig?.errorType != null -> {
                    val errorType = responseConfig.errorType.rawType.kotlin
                    val errorResponseObj = moshiReVoman.asA(response.bodyString(), errorType)
                    StepReport(
                      request,
                      errorResponseObj,
                      errorResponseObj.javaClass,
                      response,
                      postmanEnvironmentSnapshot = pm.environment.copy()
                    )
                  }
                  responseConfig?.successType != null -> {
                    val errorMsg =
                      "‼️ $stepName: Unable to validate due to unsuccessful response: $response"
                    logger.error { errorMsg }
                    StepReport(
                      request,
                      response.bodyString(),
                      String::class.java,
                      response,
                      validationFailure = errorMsg,
                      postmanEnvironmentSnapshot = pm.environment.copy()
                    )
                  }
                  else ->
                    StepReport(
                      request,
                      response.bodyString(),
                      String::class.java,
                      response,
                      postmanEnvironmentSnapshot = pm.environment.copy()
                    )
                }
              }
            }
          val postHookFailures =
            (stepNameToReport + (stepName to stepReport)).let { snr ->
              getHooksForStep(kick.hooks(), stepName, POST)
                .mapNotNull { hook ->
                  runCatching { hook.accept(stepName, Rundown(snr, pm.environment)) }
                    .exceptionOrNull()
                }
                .onEach {
                  logger.error(it) { "❗️ $stepName: Exception while validating response" }
                  noFailure = isStepNameInPassList(stepName, kick.haltOnAnyFailureExceptForSteps())
                }
            }
          stepNameToReport +
            (stepName to
              stepReport.copy(hookFailures = stepReport.hookFailures.copy(post = postHookFailures)))
        }
    return Rundown(stepNameToReport, pm.environment)
  }

  // ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
  private fun validate(
    responseObj: Any,
    validationConfig: BaseValidationConfig<out Any, out Any>?
  ): Any? =
    if (validationConfig != null) {
      val result =
        Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      result.orElse(null)
    } else null
}

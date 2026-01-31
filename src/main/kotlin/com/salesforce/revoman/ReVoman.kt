/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.flatMap
import arrow.core.merge
import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.exe.executePostResJS
import com.salesforce.revoman.internal.exe.executePreReqJS
import com.salesforce.revoman.internal.exe.fireHttpRequest
import com.salesforce.revoman.internal.exe.postStepHookExe
import com.salesforce.revoman.internal.exe.preStepHookExe
import com.salesforce.revoman.internal.exe.shouldHaltExecution
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.exe.unmarshallRequest
import com.salesforce.revoman.internal.exe.unmarshallResponse
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.Info
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.dynamicVariableGenerator
import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import com.salesforce.revoman.internal.httpclient.HttpClientEnvironment
import com.salesforce.revoman.internal.template.TemplateLoadResult
import com.salesforce.revoman.internal.template.TemplateProviders
import com.salesforce.revoman.internal.template.TemplateType
import com.salesforce.revoman.input.template.TemplateFormat
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.toVavr
import com.salesforce.revoman.output.report.TxnInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either.left
import org.http4k.core.Request

object ReVoman {
  @JvmStatic
  @JvmOverloads
  fun revUp(
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, String> = emptyMap(),
    vararg kicks: Kick,
  ): List<Rundown> = revUp(kicks.toList(), postExeHook, dynamicEnvironment)

  @JvmStatic
  @JvmOverloads
  fun revUp(
    kicks: List<Kick>,
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, String> = emptyMap(),
  ): List<Rundown> =
    kicks
      .fold(dynamicEnvironment to listOf<Rundown>()) { (accumulatedMutableEnv, rundowns), kick ->
        val rundown =
          revUp(kick.overrideDynamicEnvironment(kick.dynamicEnvironment() + accumulatedMutableEnv))
        val accumulatedRundowns = rundowns + rundown
        postExeHook.accept(rundown, accumulatedRundowns)
        rundown.mutableEnv.mutableEnvCopyWithValuesOfType<String>() to accumulatedRundowns
      }
      .second

  @JvmStatic
  fun revUp(kick: Kick): Rundown {
    val templateResults = TemplateProviders.loadTemplates(kick)
    val pmStepsDeepFlattened = templateResults.flatMap { it.steps }
    val fileVariablesByStep = templateResults.associateFileVariablesByStep()
    logger.info {
      val templateLabel =
        when (val templateCount = templateResults.size) {
          0 -> "0 Templates"
          1 -> "the Template"
          else -> "$templateCount Templates"
        }
      "Total Steps from $templateLabel provided: ${pmStepsDeepFlattened.size}"
    }
    val regexReplacer =
      RegexReplacer(kick.customDynamicVariableGenerators(), ::dynamicVariableGenerator)
    val moshiReVoman =
      initMoshi(
        kick.globalCustomTypeAdapters(),
        kick.customTypeAdaptersFromRequestConfig() + kick.customTypeAdaptersFromResponseConfig(),
        kick.globalSkipTypes(),
      )
    val postmanEnvironment =
      mergeEnvs(postmanEnvPaths(kick), kick.environmentInputStreams(), kick.dynamicEnvironment())
    val httpEnvironment =
      HttpClientEnvironment.mergeEnvs(httpEnvPaths(kick), emptyList(), kick.dynamicEnvironment())
    val combinedEnvironment =
      postmanEnvironment + httpEnvironment.filterKeys { !postmanEnvironment.containsKey(it) }
    val pm =
      PostmanSDK(
        moshiReVoman,
        kick.nodeModulesPath(),
        regexReplacer,
        combinedEnvironment.toMutableMap(),
      )
    pm.updateJetbrainsEnvironment(httpEnvironment)
    val stepNameToReport =
      executeStepsSerially(
        pmStepsDeepFlattened,
        kick,
        moshiReVoman,
        regexReplacer,
        pm,
        fileVariablesByStep,
      )
    return Rundown(
      stepNameToReport,
      pm.environment,
      kick.haltOnFailureOfTypeExcept(),
      pmStepsDeepFlattened.size,
    )
  }

  private fun executeStepsSerially(
    pmStepsFlattened: List<Step>,
    kick: Kick,
    moshiReVoman: MoshiReVoman,
    regexReplacer: RegexReplacer,
    pm: PostmanSDK,
    fileVariablesByStep: Map<Step, Map<String, Any?>>,
  ): List<StepReport> {
    var haltExecution = false
    return pmStepsFlattened
      .asSequence()
      .takeWhile { !haltExecution }
      .filter { shouldStepBePicked(it, kick.runOnlySteps(), kick.skipSteps()) }
      .fold(listOf()) { stepReports, step ->
        logger.info { "***** Executing Step: $step *****" }
        pm.environment.currentStep = step
        pm.clearRequestVariables()
        pm.setTemplateContext(step.templateType.toTemplateFormat(), fileVariablesByStep[step].orEmpty())
        val itemWithRegex = step.rawPMStep
        val itemForPreReq =
          if (step.templateType == TemplateType.JETBRAINS_HTTP) {
            itemWithRegex.copy(request = pm.applyGlobalHeaders(itemWithRegex.request))
          } else {
            itemWithRegex
          }
        if (step.templateType == TemplateType.JETBRAINS_HTTP) {
          pm.updateJetbrainsRequest(itemForPreReq.request)
        }
        val preStepReport =
          StepReport(
            step = step,
            requestInfo =
              Right(
                TxnInfo(
                  httpMsg = itemForPreReq.request.toHttpRequest(null),
                  moshiReVoman = moshiReVoman,
                )
              ),
            pmEnvSnapshot = pm.environment,
          )
        pm.info = Info(step.name)
        pm.currentStepReport = preStepReport
        pm.rundown =
          Rundown(
            stepReports + preStepReport,
            pm.environment,
            kick.haltOnFailureOfTypeExcept(),
            pmStepsFlattened.size,
          )
        pm.environment.putAll(regexReplacer.replaceVariablesInEnv(pm))
        val currentStepReport: StepReport = // --------### PRE-REQ-JS ###--------
          executePreReqJS(step, itemForPreReq, pm)
            .mapLeft { preStepReport.copy(requestInfo = left(it)) }
            .flatMap { // --------### UNMARSHALL-REQUEST ###--------
              val itemForRequest =
                if (step.templateType == TemplateType.JETBRAINS_HTTP) {
                  itemWithRegex.copy(request = pm.applyGlobalHeaders(itemWithRegex.request))
                } else {
                  itemWithRegex
                }
              if (step.templateType == TemplateType.JETBRAINS_HTTP) {
                pm.updateJetbrainsRequest(itemForRequest.request)
              }
              val pmRequest =
                regexReplacer.replaceVariablesInRequestRecursively(itemForRequest.request, pm)
              unmarshallRequest(step, pmRequest, kick, moshiReVoman, pm).mapLeft {
                preStepReport.copy(requestInfo = left(it))
              }
            }
            .flatMap { requestInfo: TxnInfo<Request> -> // --------### PRE-HOOKS ###--------
              preStepHookExe(step, kick, requestInfo, pm)?.let {
                Left(
                  preStepReport.copy(
                    requestInfo = Right(requestInfo).toVavr(),
                    preStepHookFailure = it,
                  )
                )
              } ?: Right(preStepReport.copy(requestInfo = Right(requestInfo).toVavr()))
            }
            .flatMap { sr: StepReport -> // --------### HTTP-REQUEST ###--------
              pm.currentStepReport = sr
              pm.rundown = pm.rundown.copy(stepReports = pm.rundown.stepReports + sr)
              // * NOTE 15 Mar 2025 gopala.akshintala: Replace again to accommodate variables set by
              // PRE-REQ-JS
              val itemForRequest =
                if (step.templateType == TemplateType.JETBRAINS_HTTP) {
                  itemWithRegex.copy(request = pm.applyGlobalHeaders(itemWithRegex.request))
                } else {
                  itemWithRegex
                }
              val item = regexReplacer.replaceVariablesInPmItem(itemForRequest, pm)
              val httpRequest = item.request.toHttpRequest(moshiReVoman)
              fireHttpRequest(step, httpRequest, kick.insecureHttp(), moshiReVoman)
                .mapLeft { sr.copy(requestInfo = Left(it).toVavr()) }
                .map {
                  sr.copy(
                    requestInfo =
                      sr.requestInfo?.map { txnInfo -> txnInfo.copy(httpMsg = httpRequest) },
                    responseInfo = Right(it).toVavr(),
                  )
                }
            }
            .flatMap { sr: StepReport -> // --------### PRE-RES-JS ###--------
              pm.currentStepReport = sr
              pm.rundown = pm.rundown.copy(stepReports = pm.rundown.stepReports + sr)
              if (step.templateType == TemplateType.JETBRAINS_HTTP) {
                pm.updateJetbrainsResponse(sr.responseInfo!!.get().httpMsg)
              }
              executePostResJS(step, itemWithRegex, pm)
                .mapLeft { sr.copy(responseInfo = left(it)) }
                .map { sr }
            }
            .flatMap { sr: StepReport -> // ---### UNMARSHALL RESPONSE ###---
              unmarshallResponse(kick, moshiReVoman, pm)
                .mapLeft { sr.copy(responseInfo = Left(it).toVavr()) }
                .map { sr.copy(responseInfo = Right(it).toVavr()) }
            }
            .map { sr: StepReport -> // --------### POST-HOOKS ###--------
              pm.currentStepReport = sr
              pm.rundown = pm.rundown.copy(stepReports = pm.rundown.stepReports + sr)
              sr.copy(postStepHookFailure = postStepHookExe(kick, pm))
            }
            .merge()
            .copy(
              pmEnvSnapshot =
                pm.environment.copy(mutableEnv = pm.environment.mutableEnv.toMutableMap())
            )
        haltExecution = shouldHaltExecution(currentStepReport, kick, pm.rundown)
        stepReports + currentStepReport
      }
  }
}

private val logger = KotlinLogging.logger {}

private fun postmanEnvPaths(kick: Kick): Set<String> =
  kick.environmentPaths().filterNot(HttpClientEnvironment::isHttpClientEnvPath).toSet()

private fun httpEnvPaths(kick: Kick): Set<String> =
  kick.environmentPaths().filter(HttpClientEnvironment::isHttpClientEnvPath).toSet()

private fun TemplateType.toTemplateFormat(): TemplateFormat =
  when (this) {
    TemplateType.POSTMAN -> TemplateFormat.POSTMAN_JSON
    TemplateType.JETBRAINS_HTTP -> TemplateFormat.JETBRAINS_HTTP
  }

private fun List<TemplateLoadResult>.associateFileVariablesByStep():
  Map<Step, Map<String, Any?>> =
  flatMap { result -> result.steps.map { step -> step to result.fileVariables } }.toMap()

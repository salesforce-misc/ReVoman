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
import com.salesforce.revoman.input.bufferFileInResources
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.initJSContext
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.executePreReqJS
import com.salesforce.revoman.internal.exe.executeTestsJS
import com.salesforce.revoman.internal.exe.fireHttpRequest
import com.salesforce.revoman.internal.exe.postHookExe
import com.salesforce.revoman.internal.exe.preHookExe
import com.salesforce.revoman.internal.exe.shouldHaltExecution
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.exe.unmarshallRequest
import com.salesforce.revoman.internal.exe.unmarshallResponse
import com.salesforce.revoman.internal.json.initMoshi
import com.salesforce.revoman.internal.postman.Info
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.dynamicVariableGenerator
import com.salesforce.revoman.internal.postman.mergeEnvs
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.toArrow
import com.salesforce.revoman.output.report.StepReport.Companion.toVavr
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either.left
import org.http4k.core.Request
import org.http4k.format.ConfigurableMoshi

object ReVoman {
  @JvmStatic
  @OptIn(ExperimentalStdlibApi::class)
  fun revUp(kick: Kick): Rundown {
    val environment = mergeEnvs(kick.environmentPaths(), kick.dynamicEnvironmentsFlattened())
    val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
    val pmStepsDeepFlattened =
      kick
        .templatePaths()
        .mapNotNull { pmTemplateAdapter.fromJson(bufferFileInResources(it)) }
        .flatMap { (pmSteps, authFromRoot) ->
          deepFlattenItems(pmSteps.map { it.copy(auth = it.auth ?: authFromRoot) })
        }
    logger.info {
      val templateCount = kick.templatePaths().size
      "Total Steps from ${if (templateCount > 1) "$templateCount Collections" else "the Collection"} provided: ${pmStepsDeepFlattened.size}"
    }
    val stepNameToReport =
      executeStepsSerially(
        pmStepsDeepFlattened,
        environment,
        kick,
        initMoshi(
          kick.customAdapters(),
          kick.customAdaptersFromRequestConfig() + kick.customAdaptersFromResponseConfig(),
          kick.skipTypes()
        )
      )
    return Rundown(stepNameToReport, kick.haltOnFailureOfTypeExcept())
  }

  private fun executeStepsSerially(
    pmStepsFlattened: List<Step>,
    environment: Map<String, String?>,
    kick: Kick,
    moshiReVoman: ConfigurableMoshi,
  ): List<StepReport> {
    val regexReplacer =
      RegexReplacer(kick.customDynamicVariableGenerators(), ::dynamicVariableGenerator)
    // ! TODO 17 Mar 2024 gopala.akshintala: Streamline global mutable state `pm`
    pm = PostmanSDK(regexReplacer)
    pm.environment.clear()
    pm.environment.putAll(environment)
    initJSContext(kick.nodeModulesRelativePath())
    var haltExecution = false
    return pmStepsFlattened
      .asSequence()
      .takeWhile { !haltExecution }
      .filter { shouldStepBePicked(it, kick.runOnlySteps(), kick.skipSteps()) }
      .fold(listOf()) { stepReports, step ->
        logger.info { "***** Executing Step: $step *****" }
        val itemWithRegex = step.rawPMStep
        pm.info = Info(step.name)
        val stepReport =
          StepReport(step, Right(TxnInfo(httpMsg = itemWithRegex.request.toHttpRequest())))
        val rundown = Rundown(stepReports + stepReport, kick.haltOnFailureOfTypeExcept())
        pm.currentStepReport = stepReport
        pm.rundown = rundown
        pm.environment.putAll(regexReplacer.replaceVariablesInEnv(stepReport, rundown))
        regexReplacer.replaceVariablesInRequest(itemWithRegex.request, stepReport, rundown)
        val currentStepReport: StepReport = // --------### PRE-REQUEST-JS ###--------
          executePreReqJS(step, itemWithRegex, stepReport)
            .mapLeft { stepReport.copy(requestInfo = left(it)) }
            .flatMap { // --------### UNMARSHALL-REQUEST ###--------
              val pmRequest: com.salesforce.revoman.internal.postman.template.Request =
                regexReplacer.replaceVariablesInRequest(itemWithRegex.request, stepReport, rundown)
              unmarshallRequest(step, pmRequest, kick, moshiReVoman, stepReports).mapLeft {
                StepReport(step, Left(it))
              }
            }
            .flatMap { requestInfo: TxnInfo<Request> -> // --------### PRE-HOOKS ###--------
              preHookExe(step, kick, requestInfo, stepReports)?.let {
                Left(StepReport(step, Right(requestInfo), it))
              } ?: Right(StepReport(step, Right(requestInfo)))
            }
            .flatMap { sr: StepReport -> // --------### HTTP-REQUEST ###--------
              val item =
                regexReplacer.replaceVariablesInPmItem(
                  itemWithRegex,
                  sr,
                  Rundown(stepReports + sr, kick.haltOnFailureOfTypeExcept())
                )
              val httpRequest = item.request.toHttpRequest()
              fireHttpRequest(step, item.auth, httpRequest, kick.insecureHttp())
                .mapLeft { StepReport(step, Left(it)) }
                .map {
                  StepReport(
                    step,
                    sr.requestInfo?.toArrow()?.map { txnInfo ->
                      txnInfo.copy(httpMsg = httpRequest)
                    },
                    null,
                    Right(it)
                  )
                }
            }
            .flatMap { sr: StepReport -> // --------### TESTS-JS ###--------
              pm.currentStepReport = sr
              pm.rundown = Rundown(stepReports + sr, kick.haltOnFailureOfTypeExcept())
              executeTestsJS(step, itemWithRegex, sr)
                .mapLeft { sr.copy(responseInfo = left(it)) }
                .map { sr }
            }
            .flatMap { sr: StepReport -> // ---### UNMARSHALL RESPONSE ###---
              unmarshallResponse(sr, kick, moshiReVoman, stepReports)
                .mapLeft { sr.copy(responseInfo = Left(it).toVavr()) }
                .map { sr.copy(responseInfo = Right(it).toVavr()) }
            }
            .map { sr: StepReport -> // --------### POST-HOOKS ###--------
              sr.copy(postHookFailure = postHookExe(sr, kick, stepReports + sr))
            }
            .merge()
            .copy(
              envSnapshot =
                pm.environment.copy(mutableEnv = pm.environment.mutableEnv.toMutableMap())
            )
        // * NOTE 15/10/23 gopala.akshintala: http status code can be non-success
        haltExecution = shouldHaltExecution(currentStepReport, kick, stepReports)
        stepReports + currentStepReport
      }
  }
}

private val logger = KotlinLogging.logger {}

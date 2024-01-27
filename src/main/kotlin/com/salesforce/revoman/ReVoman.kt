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
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.executeTestScriptJs
import com.salesforce.revoman.internal.exe.httpRequest
import com.salesforce.revoman.internal.exe.isConsideredFailure
import com.salesforce.revoman.internal.exe.postHookExe
import com.salesforce.revoman.internal.exe.preHookExe
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.exe.unmarshallRequest
import com.salesforce.revoman.internal.exe.unmarshallResponse
import com.salesforce.revoman.internal.json.initMoshi
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.initPmEnvironment
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
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
    initPmEnvironment(
      kick.environmentPaths(),
      kick.dynamicEnvironmentsFlattened(),
      kick.customDynamicVariables()
    )
    val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
    val pmStepsDeepFlattened =
      kick
        .templatePaths()
        .mapNotNull { pmTemplateAdapter.fromJson(bufferFileInResources(it)) }
        .flatMap { (pmSteps, authFromRoot) ->
          deepFlattenItems(pmSteps.map { it.copy(auth = it.auth ?: authFromRoot) })
        }
    logger.info { "Total steps from the Collection(s) provided: ${pmStepsDeepFlattened.size}" }
    val stepNameToReport =
      executeStepsSerially(
        pmStepsDeepFlattened,
        kick,
        initMoshi(
          kick.customAdaptersForMarshalling(),
          kick.customAdaptersFromRequestConfig() + kick.customAdaptersFromResponseConfig(),
          kick.typesToIgnoreForMarshalling()
        )
      )
    return Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExcept())
  }

  private fun executeStepsSerially(
    pmStepsFlattened: List<Step>,
    kick: Kick,
    moshiReVoman: ConfigurableMoshi,
  ): List<StepReport> {
    var noFailureInStep = true
    return pmStepsFlattened
      .asSequence()
      .takeWhile { noFailureInStep }
      .filter { shouldStepBePicked(it, kick.runOnlySteps(), kick.skipSteps()) }
      .fold(listOf()) { stepReports, step ->
        logger.info { "***** Executing Step: $step *****" }
        val itemWithRegex = step.rawPMStep
        val pmRequest: com.salesforce.revoman.internal.postman.template.Request =
          RegexReplacer(pm.environment, kick.customDynamicVariables())
            .replaceRegex(itemWithRegex.request)
        val currentStepReport: StepReport = // --------### UNMARSHALL-REQUEST ###--------
          unmarshallRequest(step, pmRequest, kick, moshiReVoman, stepReports)
            .mapLeft { StepReport(step, Left(it)) }
            .flatMap { requestInfo: TxnInfo<Request> -> // --------### PRE-HOOKS ###--------
              preHookExe(step, kick, requestInfo, stepReports)
                .mapLeft { StepReport(step, Right(requestInfo), it) }
                .map { requestInfo }
            }
            .flatMap { requestInfo: TxnInfo<Request> -> // --------### HTTP-REQUEST ###--------
              val httpRequest =
                RegexReplacer(pm.environment).replaceRegex(itemWithRegex.request).toHttpRequest()
              httpRequest(step, itemWithRegex, httpRequest, kick.insecureHttp())
                .mapLeft { StepReport(step, Left(it)) }
                .map {
                  StepReport(step, Right(requestInfo.copy(httpMsg = httpRequest)), null, Right(it))
                }
            }
            .flatMap { stepReport: StepReport -> // --------### TEST-SCRIPT-JS ###--------
              executeTestScriptJs(
                  step,
                  itemWithRegex.event,
                  kick.customDynamicVariables(),
                  pmRequest,
                  stepReport
                )
                .mapLeft { stepReport.copy(responseInfo = left(it)) }
                .map { stepReport }
            }
            .flatMap { stepReport: StepReport -> // ---### UNMARSHALL RESPONSE ###---
              unmarshallResponse(stepReport, kick, moshiReVoman, stepReports)
            }
            .flatMap { stepReport: StepReport -> // --------### POST-HOOKS ###--------
              postHookExe(stepReport, kick, stepReports + stepReport)
                .mapLeft { stepReport.copy(postHookFailure = it) }
                .map { stepReport }
            }
            .merge()
            .copy(envSnapshot = pm.environment.copy())
        // * NOTE 15/10/23 gopala.akshintala: http status code can be non-success
        noFailureInStep = isConsideredFailure(currentStepReport, kick, stepReports)
        stepReports + currentStepReport
      }
  }
}

private val logger = KotlinLogging.logger {}

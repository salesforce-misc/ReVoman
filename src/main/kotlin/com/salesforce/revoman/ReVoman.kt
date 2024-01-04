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
import com.salesforce.revoman.input.bufferFileInResources
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.httpRequest
import com.salesforce.revoman.internal.exe.postHookExe
import com.salesforce.revoman.internal.exe.preHookExe
import com.salesforce.revoman.internal.exe.runChecked
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.exe.unmarshallRequest
import com.salesforce.revoman.internal.exe.unmarshallResponseAndValidate
import com.salesforce.revoman.internal.executeTestScriptJs
import com.salesforce.revoman.internal.initMoshi
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.initPmEnvironment
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.internal.postman.state.Template
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType.TEST_SCRIPT_JS
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.TestScriptJsFailure
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
    pmStepsFlattened: List<Pair<Step, Item>>,
    kick: Kick,
    moshiReVoman: ConfigurableMoshi,
  ): List<StepReport> {
    var noFailureInStep = true
    return pmStepsFlattened
      .asSequence()
      .takeWhile { noFailureInStep }
      .filter { (step, _) -> shouldStepBePicked(step, kick.runOnlySteps(), kick.skipSteps()) }
      .fold(listOf()) { stepReports, (step, itemWithRegex) ->
        logger.info { "***** Executing Step: $step *****" }
        val pmRequest: com.salesforce.revoman.internal.postman.state.Request =
          RegexReplacer(pm.environment, kick.customDynamicVariables())
            .replaceRegex(itemWithRegex.request)
        val currentStepReport: StepReport = // --------### UNMARSHALL-REQUEST ###--------
          unmarshallRequest(step, pmRequest, kick, moshiReVoman, stepReports)
            .mapLeft { StepReport(step, Left(it)) }
            .flatMap { requestInfo: TxInfo<Request> -> // --------### PRE-HOOKS ###--------
              preHookExe(step, kick, requestInfo, stepReports)
                ?.mapLeft { StepReport(step, Right(requestInfo), it) }
                ?.map { requestInfo } ?: Right(requestInfo)
            }
            .flatMap { requestInfo: TxInfo<Request> -> // --------### HTTP-REQUEST ###--------
              val httpRequest =
                RegexReplacer(pm.environment).replaceRegex(itemWithRegex.request).toHttpRequest()
              httpRequest(step, itemWithRegex, httpRequest, kick.insecureHttp())
                .mapLeft { StepReport(step, Left(HttpRequestFailure(it, requestInfo))) }
                .map { StepReport(step, Right(requestInfo), null, Right(TxInfo(httpMsg = it))) }
            }
            .flatMap { stepReport: StepReport -> // --------### TEST-SCRIPT-JS ###--------
              val httpResponse = stepReport.responseInfo?.get()?.httpMsg!!
              runChecked(step, TEST_SCRIPT_JS) {
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
              unmarshallResponseAndValidate(stepReport, kick, moshiReVoman, stepReports)
            }
            .map { stepReport: StepReport -> // --------### POST-HOOKS ###--------
              postHookExe(stepReport, kick, stepReports + stepReport)
                ?.fold(
                  { stepReport.copy(postHookFailure = it) },
                  { stepReport.copy(envSnapshot = pm.environment.copy()) },
                ) ?: stepReport
            }
            .fold({ it }, { it })
        // * NOTE 15/10/23 gopala.akshintala: http status code can be non-success
        noFailureInStep =
          currentStepReport.isSuccessful ||
            kick.haltOnAnyFailure() ||
            (kick
              .haltOnAnyFailureExcept()
              ?.pick(
                currentStepReport,
                Rundown(
                  stepReports + currentStepReport,
                  pm.environment,
                  kick.haltOnAnyFailureExcept()
                )
              ) ?: false)
        stepReports + currentStepReport
      }
  }
}

private val logger = KotlinLogging.logger {}

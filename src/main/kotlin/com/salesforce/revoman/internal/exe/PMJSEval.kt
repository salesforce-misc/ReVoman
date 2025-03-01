/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Right
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.RequestFailure.PreReqJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure

internal fun executePreReqJS(
  currentStep: Step,
  item: Item,
  pm: PostmanSDK,
): Either<PreReqJSFailure, Unit> {
  val preReqJS = item.event?.find { it.listen == "prerequest" }?.script?.exec?.joinToString("\n")
  return if (!preReqJS.isNullOrBlank()) {
    runCatching(currentStep, PRE_REQ_JS) { executePreReqJSWithPolyglot(preReqJS, item.request, pm) }
      .mapLeft { PreReqJSFailure(it, pm.currentStepReport.requestInfo!!.get()) }
  } else {
    Right(Unit)
  }
}

private fun executePreReqJSWithPolyglot(preReqJS: String, pmRequest: Request, pm: PostmanSDK) {
  pm.request = pm.from(pmRequest)
  pm.evaluateJS(preReqJS)
}

internal fun executePostResJS(
  currentStep: Step,
  item: Item,
  pm: PostmanSDK,
): Either<PostResJSFailure, Unit> {
  val postResJs = item.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  return if (!postResJs.isNullOrBlank()) {
    runCatching(currentStep, POST_RES_JS) {
        executePostResJSWithPolyglot(postResJs, item.request, pm.currentStepReport, pm)
      }
      .mapLeft {
        PostResJSFailure(
          it,
          pm.currentStepReport.requestInfo!!.get(),
          pm.currentStepReport.responseInfo!!.get(),
        )
      }
  } else {
    Right(Unit)
  }
}

private fun executePostResJSWithPolyglot(
  postResJS: String,
  pmRequest: Request,
  stepReport: StepReport,
  pm: PostmanSDK,
) {
  val httpResponse = stepReport.responseInfo!!.get().httpMsg
  pm.setRequestAndResponse(pm.from(pmRequest), httpResponse)
  pm.evaluateJS(postResJS, mapOf("responseBody" to httpResponse.bodyString()))
}

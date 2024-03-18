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
import com.salesforce.revoman.input.evaluateJS
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.ExeType.PRE_REQUEST_JS
import com.salesforce.revoman.output.ExeType.TESTS_JS
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.RequestFailure.PreRequestJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.TestsJSFailure
import org.http4k.core.Response

internal fun executePreReqJS(
  currentStep: Step,
  item: Item,
  stepReport: StepReport
): Either<PreRequestJSFailure, Unit> {
  val preReqJS = item.event?.find { it.listen == "prerequest" }?.script?.exec?.joinToString("\n")
  return if (!preReqJS.isNullOrBlank()) {
    runChecked(currentStep, PRE_REQUEST_JS) { executePreReqJSWithPolyglot(preReqJS, item.request) }
      .mapLeft { PreRequestJSFailure(it, stepReport.requestInfo!!.get()) }
  } else {
    Right(Unit)
  }
}

private fun executePreReqJSWithPolyglot(preReqJS: String, pmRequest: Request) {
  pm.request = pmRequest
  evaluateJS(preReqJS)
}

internal fun executeTestsJS(
  currentStep: Step,
  item: Item,
  stepReport: StepReport
): Either<TestsJSFailure, Unit> {
  val testsJS = item.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  return if (!testsJS.isNullOrBlank()) {
    runChecked(currentStep, TESTS_JS) {
        executeTestsJSWithPolyglot(testsJS, item.request, stepReport)
      }
      .mapLeft {
        TestsJSFailure(it, stepReport.requestInfo!!.get(), stepReport.responseInfo!!.get())
      }
  } else {
    Right(Unit)
  }
}

private fun executeTestsJSWithPolyglot(
  testsJs: String,
  pmRequest: Request,
  stepReport: StepReport
) {
  val httpResponse = stepReport.responseInfo!!.get().httpMsg
  loadIntoPmEnvironment(pmRequest, httpResponse)
  evaluateJS(testsJs, mapOf("responseBody" to httpResponse.bodyString()))
}

private fun loadIntoPmEnvironment(pmRequest: Request, response: Response) {
  pm.request = pmRequest
  pm.response =
    com.salesforce.revoman.internal.postman.Response(
      response.status.code,
      response.status.toString(),
      response.bodyString()
    )
}

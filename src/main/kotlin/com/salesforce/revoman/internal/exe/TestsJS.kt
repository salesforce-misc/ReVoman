/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.ResponseFailure.TestsJsFailure
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.http4k.core.Response

/** Refer: https://www.graalvm.org/22.3/reference-manual/embed-languages/ */
internal val jsContext =
  buildJsContext(false).also {
    it.getBindings("js").putMember("pm", pm)
    it.getBindings("js").putMember("xml2Json", pm.xml2Json)
  }

private fun buildJsContext(useCommonjsRequire: Boolean = true): Context {
  val options = buildMap {
    if (useCommonjsRequire) {
      put("js.commonjs-require", "true")
      put("js.commonjs-require-cwd", ".")
      put("js.commonjs-core-modules-replacements", "path:path-browserify")
    }
    put("js.esm-eval-returns-exports", "true")
    put("engine.WarnInterpreterOnly", "false")
  }
  return Context.newBuilder("js")
    .allowExperimentalOptions(true)
    .allowIO(true)
    .options(options)
    .allowHostAccess(HostAccess.ALL)
    .allowHostClassLookup { true }
    .build()
}

internal fun executeTestsJS(
  currentStep: Step,
  events: List<Event>?,
  regexReplacer: RegexReplacer,
  pmRequest: Request,
  stepReport: StepReport,
  rundown: Rundown
): Either<TestsJsFailure, Unit> =
  runChecked(currentStep, ExeType.TESTS_JS) {
      executeWithPolyglot(events, regexReplacer, pmRequest, stepReport, rundown)
    }
    .mapLeft { TestsJsFailure(it, stepReport.requestInfo!!.get(), stepReport.responseInfo!!.get()) }

private fun executeWithPolyglot(
  events: List<Event>?,
  regexReplacer: RegexReplacer,
  pmRequest: Request,
  stepReport: StepReport,
  rundown: Rundown
) {
  val httpResponse = stepReport.responseInfo!!.get().httpMsg
  loadIntoPmEnvironment(pmRequest, httpResponse)
  val testScriptWithRegex = events?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  val testScript =
    regexReplacer.replaceVariablesRecursively(testScriptWithRegex, stepReport, rundown)
  if (!testScript.isNullOrBlank()) {
    jsContext.getBindings("js").putMember("responseBody", httpResponse.bodyString())
    val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
    // ! TODO gopala.akshintala 15/05/22: Keep a tab on jsContext mix-up from different steps
    jsContext.eval(testSource)
  }
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

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
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.sandbox.PmScope
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.internal.postman.sandbox.diffScopes
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.RequestFailure.PreReqJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure

@JvmSynthetic
internal fun executePreReqJS(
  currentStep: Step,
  itemWithRegex: Item,
  currentStepReport: StepReport,
  pm: PostmanSDK,
  sandbox: PmSandbox,
): Either<PreReqJSFailure, Unit> {
  val preReqJS =
    itemWithRegex.event?.find { it.listen == "prerequest" }?.script?.exec?.joinToString("\n")
  return if (!preReqJS.isNullOrBlank()) {
    runCatching(currentStep, PRE_REQ_JS) {
        pm.request = pm.from(itemWithRegex.request)
        runSandboxScript(preReqJS, ScriptTarget.PRE_REQUEST, itemWithRegex.request, pm, sandbox)
      }
      .mapLeft { PreReqJSFailure(it, currentStepReport.requestInfo!!.get()) }
  } else {
    Right(Unit)
  }
}

@JvmSynthetic
internal fun executePostResJS(
  currentStep: Step,
  item: Item,
  currentStepReport: StepReport,
  pm: PostmanSDK,
  sandbox: PmSandbox,
): Either<PostResJSFailure, Unit> {
  val postResJs = item.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  return if (!postResJs.isNullOrBlank()) {
    runCatching(currentStep, POST_RES_JS) {
        val httpResponse = currentStepReport.responseInfo!!.get().httpMsg
        pm.setRequestAndResponse(pm.from(item.request), httpResponse)
        runSandboxScript(postResJs, ScriptTarget.TEST, item.request, pm, sandbox)
      }
      .mapLeft {
        PostResJSFailure(
          it,
          currentStepReport.requestInfo!!.get(),
          currentStepReport.responseInfo!!.get(),
        )
      }
  } else {
    Right(Unit)
  }
}

/**
 * Runs a pm script in the real Postman sandbox, then applies the returned environment scope back
 * onto [PostmanSDK.environment] via a diff so the ledger records produced/unset keys exactly as the
 * old in-JS `pm.environment.set` path did. Throws on a script error so the surrounding
 * [runCatching] maps it to the right failure type.
 *
 * Only sandbox-safe env values (String/Number/Boolean/null — i.e. real Postman variable semantics)
 * are sent into and read back from the sandbox. ReVoman additionally stores typed POJOs in the env
 * (set by hooks for cross-step reuse); those are NOT pm-script variables, would not survive the
 * Flatted round-trip cleanly, and are intentionally left untouched in the Kotlin env.
 */
private fun runSandboxScript(
  script: String,
  target: ScriptTarget,
  pmRequest: Request,
  pm: PostmanSDK,
  sandbox: PmSandbox,
) {
  val before: Map<String, Any?> = sandboxSafeEnv(pm)
  val context =
    PmExecutionContext(
      environment = PmScope("environment", before),
      request = requestAsContextMap(pmRequest),
      response = if (target == ScriptTarget.TEST) responseAsContextMap(pm) else null,
    )
  val result = sandbox.execute(script, target, context)
  result.error?.let { throw it }
  // Apply env mutations back through the same set()/unset() paths the ledger reads.
  val diff = diffScopes(before, result.environment)
  diff.produced.forEach { key -> pm.environment.set(key, result.environment[key]) }
  diff.unset.forEach { key -> pm.environment.unset(key) }
}

/** Env entries that are real Postman variable values (safe to round-trip through the sandbox). */
private fun sandboxSafeEnv(pm: PostmanSDK): Map<String, Any?> =
  pm.environment.mutableEnv.filterValues {
    it == null || it is String || it is Number || it is Boolean
  }

private fun requestAsContextMap(request: Request): Map<String, Any?> =
  linkedMapOf(
    "method" to request.method,
    "url" to request.url.raw,
    "header" to request.header.map { linkedMapOf("key" to it.key, "value" to it.value) },
    "body" to request.body?.let { linkedMapOf("mode" to it.mode, "raw" to it.raw) },
  )

private fun responseAsContextMap(pm: PostmanSDK): Map<String, Any?> =
  linkedMapOf(
    "code" to pm.response.code,
    "status" to pm.response.status,
    "body" to pm.response.body,
  )

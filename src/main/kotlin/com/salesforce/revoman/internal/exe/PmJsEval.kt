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
import com.salesforce.revoman.internal.log.RevomanLog
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
import com.salesforce.revoman.output.report.PmTestAssertion
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
        runSandboxScript(
          preReqJS,
          ScriptTarget.PRE_REQUEST,
          itemWithRegex.request,
          pm,
          sandbox,
          currentStep,
        )
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
        runSandboxScript(postResJs, ScriptTarget.TEST, item.request, pm, sandbox, currentStep)
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
 * Runs a pm script in the real Postman sandbox, then applies the returned scopes back onto the
 * [PostmanSDK] so the rest of ReVoman observes script effects:
 * - environment: diffed back via [PostmanSDK.environment] set/unset (the ledger path — unchanged).
 * - collectionVariables: diffed back via [PostmanSDK.collectionVariables] set/unset.
 * - globals: diffed back via [PostmanSDK.globals] set/unset (separate store, no ledger
 *   involvement).
 * - pm.test assertions + setNextRequest: stashed per [step] for the executor to read onto
 *   StepReport.
 *
 * Throws on a script error so the surrounding [runCatching] maps it to the right failure type.
 *
 * Only sandbox-safe values (String/Number/Boolean/null — real Postman variable semantics) are sent
 * into and read back from the sandbox. Typed POJOs ReVoman stores in the env (hooks, cross-step
 * reuse) are NOT pm-script variables and are intentionally left untouched in the Kotlin env.
 */
private fun runSandboxScript(
  script: String,
  target: ScriptTarget,
  pmRequest: Request,
  pm: PostmanSDK,
  sandbox: PmSandbox,
  step: Step,
) {
  val beforeEnv: Map<String, Any?> = sandboxSafeEnv(pm.environment.mutableEnv)
  val beforeCVars: Map<String, Any?> = sandboxSafeEnv(pm.collectionVariables.mutableEnv)
  val beforeGlobals: Map<String, Any?> = sandboxSafeEnv(pm.globals.mutableEnv)
  val context =
    PmExecutionContext(
      environment = PmScope("environment", beforeEnv, name = pm.environmentName),
      globals = PmScope("globals", beforeGlobals),
      collectionVariables = PmScope("collectionVariables", beforeCVars),
      request = requestAsContextMap(pmRequest),
      response = if (target == ScriptTarget.TEST) responseAsContextMap(pm) else null,
    )
  val result = sandbox.execute(script, target, context)
  result.error?.let { throw it }

  // Apply env mutations back through the same set()/unset() paths the ledger reads.
  val envDiff = diffScopes(beforeEnv, result.environment)
  envDiff.produced.forEach { key -> pm.environment.set(key, result.environment[key]) }
  envDiff.unset.forEach { key -> pm.environment.unset(key) }

  // Apply collection-variable mutations back (no ledger involvement — separate store).
  val cVarDiff = diffScopes(beforeCVars, result.collectionVariables)
  cVarDiff.produced.forEach { key ->
    pm.collectionVariables.set(key, result.collectionVariables[key])
  }
  cVarDiff.unset.forEach { key -> pm.collectionVariables.unset(key) }

  // Apply global mutations back (no ledger involvement — separate store, like collectionVariables).
  val gVarDiff = diffScopes(beforeGlobals, result.globals)
  gVarDiff.produced.forEach { key -> pm.globals.set(key, result.globals[key]) }
  gVarDiff.unset.forEach { key -> pm.globals.unset(key) }

  // Surface pm.test results + setNextRequest onto the StepReport (read by the executor fold).
  pm.recordPmTestAssertions(
    step,
    result.assertions.map { PmTestAssertion(it.name, it.passed, it.skipped, it.error) },
  )
  result.nextRequest?.let { next ->
    pm.recordNextRequest(step, next)
    RevomanLog.warn {
      "pm.execution.setNextRequest('$next') was captured but ReVoman does not yet reorder steps " +
        "(linear execution); directive recorded on StepReport.nextRequest only (Phase 2 will honor it)."
    }
  }
}

/** Filters a scope map to sandbox-safe values (real Postman variable values). */
private fun sandboxSafeEnv(scope: Map<String, Any?>): Map<String, Any?> = scope.filterValues {
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

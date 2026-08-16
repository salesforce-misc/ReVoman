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
import com.salesforce.revoman.internal.postman.PostmanVariableScopes
import com.salesforce.revoman.internal.postman.StepScriptCapture
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmScope
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.internal.postman.sandbox.diffScopes
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.runtime.ScriptExecutor
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.RequestFailure.PreReqJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure
import org.http4k.core.Response

@JvmSynthetic
internal fun executePreReqJS(
  currentStep: Step,
  itemWithRegex: Item,
  currentStepReport: StepReport,
  scopes: PostmanVariableScopes,
  capture: StepScriptCapture,
  scripts: ScriptExecutor,
): Either<PreReqJSFailure, Unit> {
  val preReqJS =
    itemWithRegex.event?.find { it.listen == "prerequest" }?.script?.exec?.joinToString("\n")
  if (preReqJS.isNullOrBlank()) return Right(Unit)
  return runCatching(currentStep, PRE_REQ_JS) {
      runSandboxScript(
        script = preReqJS,
        target = ScriptTarget.PRE_REQUEST,
        request = itemWithRegex.request,
        response = null,
        scopes = scopes,
        capture = capture,
        scripts = scripts,
        step = currentStep,
      )
    }
    .mapLeft { PreReqJSFailure(it, currentStepReport.requestInfo!!.get()) }
}

@JvmSynthetic
internal fun executePostResJS(
  currentStep: Step,
  item: Item,
  currentStepReport: StepReport,
  scopes: PostmanVariableScopes,
  capture: StepScriptCapture,
  scripts: ScriptExecutor,
): Either<PostResJSFailure, Unit> {
  val postResJs = item.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  if (postResJs.isNullOrBlank()) return Right(Unit)
  return runCatching(currentStep, POST_RES_JS) {
      runSandboxScript(
        script = postResJs,
        target = ScriptTarget.TEST,
        request = item.request,
        response = currentStepReport.responseInfo!!.get().httpMsg,
        scopes = scopes,
        capture = capture,
        scripts = scripts,
        step = currentStep,
      )
    }
    .mapLeft {
      PostResJSFailure(
        it,
        currentStepReport.requestInfo!!.get(),
        currentStepReport.responseInfo!!.get(),
      )
    }
}

/**
 * Runs one script against the borrowed executor and immediately applies its three returned scope
 * diffs before control crosses into the next script/hook phase. The executor remains caller-owned.
 */
private fun runSandboxScript(
  script: String,
  target: ScriptTarget,
  request: Request,
  response: Response?,
  scopes: PostmanVariableScopes,
  capture: StepScriptCapture,
  scripts: ScriptExecutor,
  step: Step,
) {
  val beforeEnvironment = sandboxSafeEnv(scopes.environment.mutableEnv)
  val beforeCollectionVariables = sandboxSafeEnv(scopes.collectionVariables.mutableEnv)
  val beforeGlobals = sandboxSafeEnv(scopes.globals.mutableEnv)
  val context =
    PmExecutionContext(
      environment = PmScope("environment", beforeEnvironment, name = scopes.environmentName),
      globals = PmScope("globals", beforeGlobals),
      collectionVariables = PmScope("collectionVariables", beforeCollectionVariables),
      request = requestAsContextMap(request),
      response = if (response == null) null else responseAsContextMap(response),
    )
  val result = scripts.execute(script, target, context)
  result.error?.let { throw it }

  val environmentDiff = diffScopes(beforeEnvironment, result.environment)
  environmentDiff.produced.forEach { key ->
    scopes.environment.set(key, result.environment[key])
  }
  environmentDiff.unset.forEach { key -> scopes.environment.unset(key) }

  val collectionVariablesDiff = diffScopes(beforeCollectionVariables, result.collectionVariables)
  collectionVariablesDiff.produced.forEach { key ->
    scopes.collectionVariables.set(key, result.collectionVariables[key])
  }
  collectionVariablesDiff.unset.forEach { key -> scopes.collectionVariables.unset(key) }

  val globalsDiff = diffScopes(beforeGlobals, result.globals)
  globalsDiff.produced.forEach { key -> scopes.globals.set(key, result.globals[key]) }
  globalsDiff.unset.forEach { key -> scopes.globals.unset(key) }

  val phase = if (target == ScriptTarget.PRE_REQUEST) PRE_REQ_JS else POST_RES_JS
  capture.recordAssertions(
    step,
    result.assertions.map {
      PmTestAssertion(it.name, it.passed, it.skipped, it.error, phase)
    },
  )
  if (result.nextRequestSet) {
    capture.recordNextRequest(step, result.nextRequest, wasSet = true)
  }
  if (result.skipRequest) capture.recordSkipRequest(step)
}

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

private fun responseAsContextMap(response: Response): Map<String, Any?> =
  linkedMapOf(
    "code" to response.status.code,
    "status" to response.status.toString(),
    "body" to response.bodyString(),
  )

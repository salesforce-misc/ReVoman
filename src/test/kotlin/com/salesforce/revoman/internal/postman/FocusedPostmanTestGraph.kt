/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.runtime.LegacyRundownProgress
import com.salesforce.revoman.internal.runtime.legacyRundownProgress
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport

internal data class FocusedPostmanTestGraph(
  val scopes: PostmanVariableScopes,
  val progress: LegacyRundownProgress,
  val replacer: RegexReplacer,
)

internal fun focusedPostmanTestGraph(
  environmentValues: Map<String, Any?> = emptyMap(),
  collectionVariableValues: Map<String, Any?> = emptyMap(),
  globalValues: Map<String, Any?> = emptyMap(),
  customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator> = emptyMap(),
): FocusedPostmanTestGraph {
  val moshi = initMoshi()
  val environment = PostmanEnvironment(environmentValues.toMutableMap(), moshi)
  val collectionVariables = PostmanEnvironment(collectionVariableValues.toMutableMap(), moshi)
  val globals = PostmanEnvironment(globalValues.toMutableMap(), moshi)
  val scopes = postmanVariableScopes(environment, collectionVariables, globals, "test")
  val progress = legacyRundownProgress()
  val step = Step(index = "test", rawPMStep = Item(name = "test"))
  val report = StepReport(step = step, pmEnvSnapshot = environment)
  progress.currentReport = report
  progress.currentRequestName = "test"
  progress.rundown =
    Rundown(
      stepReports = listOf(report),
      mutableEnv = environment,
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 1,
      collectionVariables = collectionVariables,
      globals = globals,
    )
  return FocusedPostmanTestGraph(
    scopes,
    progress,
    regexReplacer(scopes, progress, customDynamicVariableGenerators),
  )
}

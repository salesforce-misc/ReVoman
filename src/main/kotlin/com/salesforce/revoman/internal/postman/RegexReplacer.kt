/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.postman.template.Auth.Bearer
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.StepReport

private const val VARIABLE_KEY = "variableKey"
val postManVariableRegex = "\\{\\{(?<$VARIABLE_KEY>[^{}]*?)}}".toRegex()

internal class RegexReplacer(
  private val env: MutableMap<String, Any?> = mutableMapOf(),
  private val customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator> =
    emptyMap(),
  private val dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
) {
  /**
   * <p>
   * Order of Variable resolution
   * <ul>
   * <li>Custom Dynamic Variables</li>
   * <li>Dynamic Variables</li>
   * <li>Dynamic Environment + Environment from file</li>
   * </ul>
   */
  internal fun replaceVariablesRecursively(
    stringWithRegexToReplace: String?,
    currentStepReport: StepReport,
    rundown: Rundown
  ): String? =
    stringWithRegexToReplace?.let {
      postManVariableRegex.replace(it) { matchResult ->
        val variableKey = matchResult.groups[VARIABLE_KEY]?.value!!
        customDynamicVariableGenerators[variableKey]
          ?.let { cdvg ->
            replaceVariablesRecursively(
              cdvg.generate(variableKey, currentStepReport, rundown),
              currentStepReport,
              rundown
            )
          }
          ?.also { value -> env[variableKey] = value }
          ?: replaceVariablesRecursively(
              dynamicVariableGenerator(variableKey),
              currentStepReport,
              rundown
            )
            ?.also { value -> env[variableKey] = value }
          ?: replaceVariablesRecursively(env[variableKey] as String?, currentStepReport, rundown)
          ?: matchResult.value
      }
    }

  internal fun replaceVariablesInPmItem(
    item: Item,
    currentStepReport: StepReport,
    rundown: Rundown
  ): Item =
    item.copy(
      request = replaceVariablesInRequest(item.request, currentStepReport, rundown),
      auth =
        item.auth?.copy(
          bearer =
            listOfNotNull(
              replaceVariablesInBearer(item.auth.bearer.firstOrNull(), currentStepReport, rundown)
            )
        )
    )

  private fun replaceVariablesInBearer(
    bearer: Bearer?,
    currentStepReport: StepReport,
    rundown: Rundown
  ): Bearer? =
    bearer?.copy(value = replaceVariablesRecursively(bearer.value, currentStepReport, rundown)!!)

  internal fun replaceVariablesInRequest(
    request: Request,
    currentStepReport: StepReport,
    rundown: Rundown
  ): Request =
    request.copy(
      header =
        request.header.map { header ->
          header.copy(
            key = replaceVariablesRecursively(header.key, currentStepReport, rundown) ?: header.key,
            value =
              replaceVariablesRecursively(header.value, currentStepReport, rundown) ?: header.value
          )
        },
      url =
        request.url.copy(
          raw =
            replaceVariablesRecursively(request.url.raw, currentStepReport, rundown)
              ?: request.url.raw
        ),
      body =
        request.body?.copy(
          raw =
            replaceVariablesRecursively(request.body.raw, currentStepReport, rundown)
              ?: request.body.raw
        )
    )

  internal fun replaceVariablesInEnv(
    currentStepReport: StepReport,
    rundown: Rundown
  ): Map<String, Any?> =
    env
      .toMap()
      .entries
      .associateBy(
        { replaceVariablesRecursively(it.key, currentStepReport, rundown)!! },
        {
          if (it.value is String?)
            replaceVariablesRecursively(it.value as String?, currentStepReport, rundown)
          else it.value
        }
      )
}

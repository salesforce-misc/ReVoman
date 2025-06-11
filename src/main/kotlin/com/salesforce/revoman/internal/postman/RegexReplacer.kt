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

private const val VARIABLE_KEY = "variableKey"
private val postManVariableRegex = "\\{\\{(?<$VARIABLE_KEY>[^{}]*?)}}".toRegex()

class RegexReplacer(
  private val customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator> =
    emptyMap(),
  private val dynamicVariableGenerator: (String, PostmanSDK) -> String? = ::dynamicVariableGenerator,
) {
  /**
   * ## Order of Variable resolution
   * - Custom Dynamic Variables
   * - Dynamic variables
   * - Environment built during execution
   * - Dynamic Environment supplied through config
   * - Postman Environment supplied as a file through config
   */
  internal fun replaceVariablesRecursively(stringWithRegex: String?, pm: PostmanSDK): String? =
    stringWithRegex?.let {
      postManVariableRegex.replace(it) { matchResult ->
        val variableKey = matchResult.groups[VARIABLE_KEY]?.value!!
        customDynamicVariableGenerators[variableKey]
          ?.let { cdvg ->
            replaceVariablesRecursively(
              cdvg.generate(variableKey, pm.currentStepReport, pm.rundown),
              pm,
            )
          }
          ?.also { value -> pm.environment[variableKey] = value }
          ?: replaceVariablesRecursively(dynamicVariableGenerator(variableKey, pm), pm)?.also {
            value ->
            pm.environment[variableKey] = value
          }
          ?: replaceVariablesRecursively(pm.getAsString(variableKey), pm)?.also { value ->
            pm.environment[variableKey] = value
          }
          ?: matchResult.value
      }
    }

  internal fun replaceVariablesInPmItem(item: Item, pm: PostmanSDK): Item =
    item.copy(request = replaceVariablesInRequestRecursively(item.request, pm))

  private fun replaceVariablesInBearer(bearer: Bearer?, pm: PostmanSDK): Bearer? =
    bearer?.copy(value = replaceVariablesRecursively(bearer.value, pm)!!)

  internal fun replaceVariablesInRequestRecursively(request: Request, pm: PostmanSDK): Request =
    request.copy(
      auth =
        request.auth?.copy(
          bearer = listOfNotNull(replaceVariablesInBearer(request.auth.bearer.firstOrNull(), pm))
        ),
      header =
        request.header.map { header ->
          header.copy(
            key = replaceVariablesRecursively(header.key, pm) ?: header.key,
            value = replaceVariablesRecursively(header.value, pm) ?: header.value,
          )
        },
      url =
        request.url.copy(raw = replaceVariablesRecursively(request.url.raw, pm) ?: request.url.raw),
      body =
        request.body?.copy(
          raw = replaceVariablesRecursively(request.body.raw, pm) ?: request.body.raw
        ),
    )

  internal fun replaceVariablesInEnv(pm: PostmanSDK): Map<String, Any?> =
    pm.environment
      .toMap()
      .entries
      .associateBy(
        { replaceVariablesRecursively(it.key, pm)!! },
        {
          if (it.value is String?) replaceVariablesRecursively(it.value as String?, pm)
          else it.value
        },
      )
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import com.salesforce.revoman.internal.postman.template.Auth.Bearer
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request

private const val VARIABLE_KEY = "variableKey"
private val postManVariableRegex = "\\{\\{(?<$VARIABLE_KEY>[^{}]*?)}}".toRegex()

class RegexReplacer(
  private val customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator> =
    emptyMap(),
  private val dynamicVariableGenerator: (String, PostmanSDK) -> String? = ::dynamicVariableGenerator,
  private val maxVariableResolutionDepth: Int = DEFAULT_MAX_RESOLUTION_DEPTH,
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
    stringWithRegex?.let { input ->
      var current = input
      var depth = 0
      while (depth < maxVariableResolutionDepth && postManVariableRegex.containsMatchIn(current)) {
        val replaced = replaceVariablesOnce(current, pm)
        if (replaced == current) {
          break
        }
        current = replaced
        depth++
      }
      if (depth >= maxVariableResolutionDepth && postManVariableRegex.containsMatchIn(current)) {
        logger.warn {
          "Max variable resolution depth reached ($maxVariableResolutionDepth). Leaving unresolved variables as-is."
        }
      }
      current
    }

  private fun replaceVariablesOnce(input: String, pm: PostmanSDK): String =
    postManVariableRegex.replace(input) { variable ->
      val variableKey = variable.groups[VARIABLE_KEY]?.value!!
      val resolvedValue =
        customDynamicVariableGenerators[variableKey]
          ?.generate(variableKey, pm.currentStepReport, pm.rundown)
          ?: dynamicVariableGenerator(variableKey, pm)
          ?: pm.environment.getAsString(variableKey)
      resolvedValue?.also { value -> setItBackInEnvironment(variableKey, value, pm) }
        ?: variable.value
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

  companion object {
    private const val DEFAULT_MAX_RESOLUTION_DEPTH = 10

    private fun setItBackInEnvironment(variableKey: String, value: String, pm: PostmanSDK) {
      val currentValue = pm.environment[variableKey]
      // * NOTE 20 Dec 2025 gopala.akshintala: Not doing `fromJson` for perf reasons.
      // One can always use `getTypedObj()` to deserialize
      val convertedValue: Any? =
        when (currentValue) {
          is Int -> value.toIntOrNull()
          is Long -> value.toLongOrNull()
          is Double -> value.toDoubleOrNull()
          is Float -> value.toFloatOrNull()
          is Boolean -> value.toBooleanStrictOrNull()
          else -> value
        }
      pm.environment[variableKey] = convertedValue ?: value
    }
  }
}

private val logger = KotlinLogging.logger {}

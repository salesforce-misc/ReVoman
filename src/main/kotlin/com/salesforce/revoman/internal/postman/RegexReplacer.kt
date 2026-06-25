/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.log.RevomanLog
import com.salesforce.revoman.internal.postman.template.Auth.Bearer
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request

private const val VARIABLE_KEY = "variableKey"
private val postManVariableRegex = "\\{\\{(?<$VARIABLE_KEY>[^{}]*?)}}".toRegex()

class RegexReplacer(
  private val customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator> =
    emptyMap(),
  private val dynamicVariableGenerator: (String, PostmanSDK) -> String? =
    ::dynamicVariableGenerator,
) {
  /**
   * ## Order of Variable resolution
   * - Custom Dynamic Variables
   * - Dynamic variables
   * - Postman variable scopes, by precedence (narrowest wins): `environment` ▸
   *   `collectionVariables` ▸ `globals`. Only the `environment` scope participates in the warm-run
   *   ledger (`recordConsumed`) and the type-coercing write-back (`setItBackInEnvironment`); a hit
   *   from `collectionVariables` or `globals` is resolved but left untouched in its own store.
   */
  internal fun replaceVariablesRecursively(stringWithRegex: String?, pm: PostmanSDK): String? =
    stringWithRegex?.let {
      postManVariableRegex.replace(it) { variable ->
        val variableKey = variable.groups[VARIABLE_KEY]?.value!!
        customDynamicVariableGenerators[variableKey]
          ?.let { cdvg ->
            replaceVariablesRecursively(
              cdvg.generate(variableKey, pm.currentStepReport, pm.rundown),
              pm,
            )
          }
          ?.also { value -> setItBackInEnvironment(variableKey, value, pm) }
          ?: replaceVariablesRecursively(dynamicVariableGenerator(variableKey, pm), pm)?.also {
            value ->
            setItBackInEnvironment(variableKey, value, pm)
          }
          ?: resolveFromScopes(variableKey, pm)
          ?: variable.value
      }
    }

  /**
   * Resolves [variableKey] across the three persistent Postman scopes by precedence (`environment`
   * ▸ `collectionVariables` ▸ `globals`), using containment so a scope that holds the key wins even
   * over a narrower scope that does not. The `environment` hit keeps its historical side effects
   * (ledger `recordConsumed` + type-coercing `setItBackInEnvironment`); `collectionVariables` and
   * `globals` hits are read-only — no ledger involvement, no write-back into their stores. Returns
   * `null` when no scope contains the key (caller falls back to the literal `{{key}}`).
   */
  private fun resolveFromScopes(variableKey: String, pm: PostmanSDK): String? =
    when {
      pm.environment.containsKey(variableKey) ->
        replaceVariablesRecursively(pm.environment.getAsString(variableKey), pm)?.also { value ->
          pm.environment.recordConsumed(variableKey)
          setItBackInEnvironment(variableKey, value, pm)
          RevomanLog.debug { "{{$variableKey}} resolved from scope 'environment'" }
        }
      pm.collectionVariables.containsKey(variableKey) ->
        replaceVariablesRecursively(pm.collectionVariables.getAsString(variableKey), pm)?.also {
          RevomanLog.debug { "{{$variableKey}} resolved from scope 'collectionVariables'" }
        }
      pm.globals.containsKey(variableKey) ->
        replaceVariablesRecursively(pm.globals.getAsString(variableKey), pm)?.also {
          RevomanLog.debug { "{{$variableKey}} resolved from scope 'globals'" }
        }
      else -> null
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

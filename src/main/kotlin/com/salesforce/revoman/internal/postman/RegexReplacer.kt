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
import com.salesforce.revoman.internal.runtime.RundownProgress

private const val VARIABLE_KEY = "variableKey"
private val postManVariableRegex = "\\{\\{(?<$VARIABLE_KEY>[^{}]*?)}}".toRegex()

internal class RegexReplacer(
  private val scopes: PostmanVariableScopes,
  private val progress: RundownProgress,
  private val customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator>,
) {
  @JvmSynthetic
  fun replaceVariablesRecursively(stringWithRegex: String?): String? =
    replaceVariablesRecursively(stringWithRegex, emptySet())

  @JvmSynthetic
  private fun replaceVariablesRecursively(
    stringWithRegex: String?,
    visitedKeys: Set<String>,
  ): String? = stringWithRegex?.let { value ->
    if (!value.contains("{{")) return@let value
    postManVariableRegex.replace(value) { variable ->
      val variableKey = variable.groups[VARIABLE_KEY]?.value!!
      if (variableKey in visitedKeys) {
        RevomanLog.warn {
          "Cyclic variable reference detected: $variableKey is part of a resolution chain. Leaving placeholder {{$variableKey}} unresolved."
        }
        return@replace variable.value
      }
      val nextVisitedKeys = visitedKeys + variableKey
      customDynamicVariableGenerators[variableKey]
        ?.let { generator ->
          replaceVariablesRecursively(
            generator.generate(variableKey, progress.currentReport, progress.rundown),
            nextVisitedKeys,
          )
        }
        ?.also { replacement -> setItBackInEnvironment(variableKey, replacement) }
        ?: replaceVariablesRecursively(
            dynamicVariableGenerator(variableKey, progress),
            nextVisitedKeys,
          )
          ?.also { replacement -> setItBackInEnvironment(variableKey, replacement) }
        ?: resolveFromScopes(variableKey, nextVisitedKeys)
        ?: variable.value
    }
  }

  @JvmSynthetic
  private fun resolveFromScopes(variableKey: String, visitedKeys: Set<String>): String? {
    val owner = scopes.ownerOf(variableKey) ?: return null
    return replaceVariablesRecursively(owner.getAsString(variableKey), visitedKeys)?.also {
      when {
        owner === scopes.environment -> {
          owner.recordConsumed(variableKey)
          setItBackInEnvironment(variableKey, it)
          RevomanLog.debug { "{{$variableKey}} resolved from scope 'environment'" }
        }
        owner === scopes.collectionVariables ->
          RevomanLog.debug { "{{$variableKey}} resolved from scope 'collectionVariables'" }
        else -> RevomanLog.debug { "{{$variableKey}} resolved from scope 'globals'" }
      }
    }
  }

  @JvmSynthetic
  fun replaceVariablesInPmItem(item: Item): Item =
    item.copy(request = replaceVariablesInRequestRecursively(item.request))

  @JvmSynthetic
  private fun replaceVariablesInBearer(bearer: Bearer?): Bearer? =
    bearer?.copy(value = replaceVariablesRecursively(bearer.value)!!)

  @JvmSynthetic
  fun replaceVariablesInRequestRecursively(request: Request): Request =
    request.copy(
      auth =
        request.auth?.copy(
          bearer = listOfNotNull(replaceVariablesInBearer(request.auth.bearer.firstOrNull()))
        ),
      header =
        request.header.map { header ->
          header.copy(
            key = replaceVariablesRecursively(header.key) ?: header.key,
            value = replaceVariablesRecursively(header.value) ?: header.value,
          )
        },
      url = request.url.copy(raw = replaceVariablesRecursively(request.url.raw) ?: request.url.raw),
      body =
        request.body?.copy(raw = replaceVariablesRecursively(request.body.raw) ?: request.body.raw),
    )

  @JvmSynthetic
  fun replaceVariablesInEnv(): Map<String, Any?> =
    scopes.environment.toMap().entries.associate { (key, value) ->
      val valueHasPlaceholder = value is String && value.contains("{{")
      if (!key.contains("{{") && !valueHasPlaceholder) {
        key to value
      } else {
        replaceVariablesRecursively(key)!! to
          (if (value is String?) replaceVariablesRecursively(value) else value)
      }
    }

  @JvmSynthetic
  private fun setItBackInEnvironment(variableKey: String, value: String) {
    val currentValue = scopes.environment[variableKey]
    val convertedValue: Any? =
      when (currentValue) {
        is Int -> value.toIntOrNull()
        is Long -> value.toLongOrNull()
        is Double -> value.toDoubleOrNull()
        is Float -> value.toFloatOrNull()
        is Boolean -> value.toBooleanStrictOrNull()
        else -> value
      }
    scopes.environment[variableKey] = convertedValue ?: value
  }
}

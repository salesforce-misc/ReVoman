/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.postman.template.Environment
import com.salesforce.revoman.internal.postman.template.Request

private const val VARIABLE_KEY = "variableKey"
val postManVariableRegex = "\\{\\{(?<$VARIABLE_KEY>[^{}]*?)}}".toRegex()

internal class RegexReplacer(
  private val env: MutableMap<String, Any?> = mutableMapOf(),
  private val customDynamicVariables: Map<String, (String) -> String> = emptyMap(),
  private val dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
) {

  /**
   * <p>
   * Order of Variable resolution
   * <ul>
   * <li>Custom Dynamic Variables</li>
   * <li>Dynamic Variables</li>
   * <li>Environment mixed with dynamic Environment</li>
   * </ul>
   */
  internal fun replaceRegexRecursively(s: String?): String? =
    s?.let {
      postManVariableRegex.replace(it) { matchResult ->
        val variableKey = matchResult.groups[VARIABLE_KEY]?.value!!
        customDynamicVariables[variableKey]
          ?.let { replaceRegexRecursively(it(variableKey)) }
          ?.also { env[variableKey] = it }
          ?: replaceRegexRecursively(dynamicVariableGenerator(variableKey))?.also {
            env[variableKey] = it
          }
          ?: replaceRegexRecursively(env[variableKey] as String?)
          ?: matchResult.value
      }
    }

  internal fun replaceRegex(request: Request): Request =
    request.copy(
      header =
        request.header.map { header ->
          header.copy(
            key = replaceRegexRecursively(header.key) ?: header.key,
            value = replaceRegexRecursively(header.value) ?: header.value
          )
        },
      url = request.url.copy(raw = replaceRegexRecursively(request.url.raw) ?: request.url.raw),
      body = request.body?.copy(raw = replaceRegexRecursively(request.body.raw) ?: request.body.raw)
    )

  fun replaceRegex(environment: Environment): Environment =
    environment.copy(
      values =
        environment.values.map { envValue ->
          envValue.copy(
            key = replaceRegexRecursively(envValue.key)!!,
            value = replaceRegexRecursively(envValue.value)
          )
        }
    )
}

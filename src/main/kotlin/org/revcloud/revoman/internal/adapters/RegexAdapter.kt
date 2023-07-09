package org.revcloud.revoman.internal.adapters

import com.squareup.moshi.FromJson
import org.revcloud.revoman.internal.postman.dynamicVariableGenerator

val postManVariableRegex = "\\{\\{(?<variableKey>[^{}]*?)}}".toRegex()

class RegexAdapter(
  private val env: MutableMap<String, String?>,
  private val customDynamicVariables: Map<String, (String) -> String>,
  private val dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
) {

  @FromJson fun fromJson(value: String): String? = replaceRegexRecursively(value)

  /**
   * Order of Variable resolution:
   * <ul>
   * <li>Custom Dynamic Variables</li>
   * <li>Dynamic Variables</li>
   * <li>Environment mixed with dynamic Environment</li>
   * </ul>
   */
  private fun replaceRegexRecursively(s: String?): String? =
    s?.let {
      postManVariableRegex.replace(it) { matchResult ->
        val variableKey = matchResult.groups["variableKey"]?.value!!
        customDynamicVariables[variableKey]
          ?.let { replaceRegexRecursively(it(variableKey)) }
          ?.also { env[variableKey] = it }
          ?: replaceRegexRecursively(dynamicVariableGenerator(variableKey))?.also {
            env[variableKey] = it
          }
            ?: replaceRegexRecursively(env[variableKey]) ?: matchResult.value
      }
    }
}

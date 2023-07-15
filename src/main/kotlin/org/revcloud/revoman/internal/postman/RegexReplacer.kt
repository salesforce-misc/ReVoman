package org.revcloud.revoman.internal.postman

import org.revcloud.revoman.internal.postman.state.Environment
import org.revcloud.revoman.internal.postman.state.Request

val postManVariableRegex = "\\{\\{(?<variableKey>[^{}]*?)}}".toRegex()

internal class RegexReplacer(
  private val env: MutableMap<String, String?> = mutableMapOf(),
  private val customDynamicVariables: Map<String, (String) -> String> = emptyMap(),
  private val dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
) {
  /**
   * Order of Variable resolution:
   * <ul>
   * <li>Custom Dynamic Variables</li>
   * <li>Dynamic Variables</li>
   * <li>Environment mixed with dynamic Environment</li>
   * </ul>
   */
  fun replaceRegexRecursively(s: String?): String? =
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

  fun replaceRegex(request: Request): Request =
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
          envValue.copy(value = replaceRegexRecursively(envValue.value))
        }
    )
}

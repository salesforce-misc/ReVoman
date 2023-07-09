package org.revcloud.revoman.internal.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import java.lang.reflect.Type
import org.revcloud.revoman.internal.postman.dynamicVariableGenerator

val postManVariableRegex = "\\{\\{(?<variableKey>[^{}]*?)}}".toRegex()

internal class RegexAdapterFactory(
  private val env: Map<String, String?>,
  private val customDynamicVariables: Map<String, (String) -> String>,
  private val dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
) : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    if (type != String::class.java) {
      return null
    }
    val stringAdapter = moshi.nextAdapter<String>(this, String::class.java, Util.NO_ANNOTATIONS)
    return object : JsonAdapter<String>() {
      override fun fromJson(reader: JsonReader): String? =
        replaceRegexRecursively(stringAdapter.fromJson(reader))

      private fun replaceRegexRecursively(s: String?): String? =
        s?.let {
          postManVariableRegex.replace(it) { matchResult ->
            val variableKey = matchResult.groups["variableKey"]?.value!!
            customDynamicVariables[variableKey]?.let { replaceRegexRecursively(it(variableKey)) }
              ?: replaceRegexRecursively(dynamicVariableGenerator(variableKey))
                ?: replaceRegexRecursively(env[variableKey]) ?: matchResult.value
          }
        }

      override fun toJson(writer: JsonWriter, value: String?) = stringAdapter.toJson(writer, value)
    }
  }
}

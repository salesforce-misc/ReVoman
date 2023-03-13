package org.revcloud.revoman.adapters.internal

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import org.revcloud.revoman.postman.dynamicVariableGenerator
import java.lang.reflect.Type

internal class RegexAdapterFactory(
  private val envMap: Map<String, String?>,
  private val dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
) : JsonAdapter.Factory {
  private val postManVariableRegex = "\\{\\{([^{}]*?)}}".toRegex()
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    if (type != String::class.java) {
      return null
    }
    val stringAdapter = moshi.nextAdapter<String>(this, String::class.java, Util.NO_ANNOTATIONS)
    return object : JsonAdapter<String>() {
      override fun fromJson(reader: JsonReader): String? =
        replaceRegexRecursively(stringAdapter.fromJson(reader))

      override fun toJson(writer: JsonWriter, value: String?) = stringAdapter.toJson(writer, value)
    }
  }

  private fun replaceRegexRecursively(s: String?): String? = s?.let {
    postManVariableRegex.replace(it) { matchResult ->
      val variableKey = matchResult.groupValues[1]
      replaceRegexRecursively(dynamicVariableGenerator(variableKey)) ?: replaceRegexRecursively(envMap[variableKey]) ?: variableKey
    }
  }
}

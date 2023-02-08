package org.revcloud.revoman.adapters.internal

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import org.revcloud.revoman.postman.dynamicVariables
import java.lang.reflect.Type

private val postManVariableRegex = "\\{\\{([^{}]*?)}}".toRegex()

internal class RegexAdapterFactory(val envMap: Map<String, String?>) : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    if (type != String::class.java) {
      return null
    }
    val stringAdapter = moshi.nextAdapter<String>(this, String::class.java, Util.NO_ANNOTATIONS)
    return object : JsonAdapter<String>() {
      override fun fromJson(reader: JsonReader): String? {
        val s = stringAdapter.fromJson(reader)
        return s?.let {
          postManVariableRegex.replace(s) { matchResult ->
            val variableKey = matchResult.groupValues[1]
            dynamicVariables(variableKey) ?: envMap[variableKey] ?: ""
          }
        }
      }

      override fun toJson(writer: JsonWriter, value: String?) {
        stringAdapter.toJson(writer, value)
      }
    }
  }
}

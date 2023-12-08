package com.salesforce.revoman.internal.factories

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Token.STRING
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.internal.Util
import java.lang.reflect.Type

internal class CaseInsensitiveEnumAdapter<T : Enum<T>>(val enumType: Class<T>) : JsonAdapter<T>() {
  private val nameStrings =
    enumType.getEnumConstants().map { Util.jsonName(it.name, enumType.getField(it.name)) }
  private val options = JsonReader.Options.of(*nameStrings.toTypedArray())

  override fun fromJson(reader: JsonReader): T {
    val index = reader.selectString(options)
    return if (index != -1) {
      enumType.getEnumConstants()[index]
    } else if (reader.peek() != STRING) {
      throw JsonDataException("Expected a string but was ${reader.peek()} at path ${reader.path}")
    } else {
      val value = reader.nextString()
      enumType.enumConstants.firstOrNull { it.name.compareTo(value, ignoreCase = true) == 0 }
        ?: throw JsonDataException(
          "Expected one of $nameStrings but was $value at path ${reader.path}"
        )
    }
  }

  override fun toJson(writer: JsonWriter, value: T?) {
    value?.also { writer.value(nameStrings[it.ordinal]) } ?: writer.nullValue()
  }

  companion object {
    @JvmField
    val FACTORY =
      object : Factory {
        override fun create(
          type: Type,
          annotations: Set<Annotation>,
          moshi: Moshi
        ): JsonAdapter<*>? {
          val rawType: Class<*> = Types.getRawType(type)
          if (!rawType.isEnum()) {
            return null
          }
          return CaseInsensitiveEnumAdapter(rawType as Class<out Enum<*>>)
        }
      }
  }
}


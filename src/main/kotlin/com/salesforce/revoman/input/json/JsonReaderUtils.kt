@file:JvmName("JsonReaderUtils")

package com.salesforce.revoman.input.json

import com.squareup.moshi.JsonReader
import java.util.function.BiConsumer
import org.springframework.beans.BeanUtils

fun nextString(reader: JsonReader): String = reader.nextString()

fun nextBoolean(reader: JsonReader): Boolean = reader.nextBoolean()

fun nextInt(reader: JsonReader): Int = reader.nextInt()

fun nextLong(reader: JsonReader): Long = reader.nextLong()

fun skipValue(reader: JsonReader) = reader.skipValue()

fun nextName(reader: JsonReader): String = reader.nextName()

fun <T> objR(mk: () -> T, reader: JsonReader, block: BiConsumer<T, String>): T =
  with(reader) {
    beginObject()
    val item = mk()
    while (hasNext()) {
      block.accept(item, nextName())
    }
    endObject()
    item
  }

fun <T> listR(mk: () -> T, reader: JsonReader, block: BiConsumer<T, String>): List<T?>? =
  reader.skipNullOr {
    val items = mutableListOf<T?>()
    beginArray()
    while (hasNext()) items += objR(mk, this, block)
    endArray()
    items
  }

fun JsonReader.anyMapR(): Map<String, Any?>? = skipNullOr {
  beginObject()
  val map = mutableMapOf<String, Any?>()
  while (hasNext()) map += nextName() to readJsonValue()
  endObject()
  map
}

private fun <T> JsonReader.skipNullOr(fn: JsonReader.() -> T): T? =
  if (peek() == JsonReader.Token.NULL) skipValue().let { null } else fn()

fun <T> JsonReader.readProps(type: Class<T>, bean: T, fieldName: String) {
  val propType: Class<*> = BeanUtils.findPropertyType(fieldName, type)
  val setter = { value: Any? ->
    BeanUtils.getPropertyDescriptor(type, fieldName)?.writeMethod?.invoke(bean, value)
  }
  when {
    propType.isEnum -> {
      val value = nextString()
      setter(propType.enumConstants.find { it.toString() == value })
    }
    propType.isPrimitive ->
      when (propType.name) {
        "int" -> setter(nextInt())
        "boolean" -> setter(nextBoolean())
        "long" -> setter(nextLong())
        "double" -> setter(nextDouble())
        else -> skipValue()
      }
    BeanUtils.isSimpleProperty(propType) ->
      when (propType) {
        String::class.javaObjectType -> setter(nextString())
        Integer::class.javaObjectType -> setter(nextInt())
        Boolean::class.javaObjectType -> setter(nextBoolean())
        Long::class.javaObjectType -> setter(nextLong())
        Double::class.javaObjectType -> setter(nextDouble())
        else -> skipValue()
      }
    else -> skipValue()
  }
}

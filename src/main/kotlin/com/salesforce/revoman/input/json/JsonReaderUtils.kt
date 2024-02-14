/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("JsonReaderUtils")

package com.salesforce.revoman.input.json

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import org.springframework.beans.BeanUtils

fun nextString(reader: JsonReader): String = reader.nextString()

fun nextBoolean(reader: JsonReader): Boolean = reader.nextBoolean()

fun nextInt(reader: JsonReader): Int = reader.nextInt()

fun nextLong(reader: JsonReader): Long = reader.nextLong()

fun skipValue(reader: JsonReader) = reader.skipValue()

fun nextName(reader: JsonReader): String = reader.nextName()

fun <T> objR(mk: () -> T, reader: JsonReader, block: NestedNodeReader<T>): T =
  with(reader) {
    beginObject()
    val item = mk()
    while (hasNext()) {
      block.read(item, nextName())
    }
    endObject()
    item
  }

fun <T> listR(mk: () -> T, reader: JsonReader, fn: NestedNodeReader<T>): List<T?>? =
  reader.skipNullOr {
    val items = mutableListOf<T?>()
    beginArray()
    while (hasNext()) items += objR(mk, this, fn)
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

fun <T> JsonReader.readProps(pojoType: Class<T>, bean: T, fieldName: String, moshi: Moshi) =
  skipNullOr {
    val propType: Class<*> = BeanUtils.findPropertyType(fieldName, pojoType)
    // * NOTE 15 Feb 2024 gopala.akshintala: Since data type info is lost with JSON, we cannot use
    // dynamicAdapter
    val delegate: JsonAdapter<out Any?> = moshi.adapter(propType)
    BeanUtils.getPropertyDescriptor(pojoType, fieldName)
      ?.writeMethod
      ?.invoke(bean, delegate.fromJson(this))
  }

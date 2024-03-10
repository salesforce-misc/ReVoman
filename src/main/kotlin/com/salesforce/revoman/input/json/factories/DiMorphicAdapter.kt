/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.factories

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Options
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.rawType
import java.lang.reflect.Type

class DiMorphicAdapter
private constructor(
  private val labelKey: String,
  private val successAdapter: Triple<Boolean, Type, JsonAdapter<Any>>,
  private val errorAdapter: Pair<Type, JsonAdapter<Any>>,
) : JsonAdapter<Any>() {
  override fun fromJson(reader: JsonReader): Any? {
    val peeked = reader.peekJson()
    val labelValue = peeked.use(::labelValue)
    val jsonAdapter =
      if (successAdapter.first == labelValue) successAdapter.third else errorAdapter.second
    return jsonAdapter.fromJson(reader)
  }

  private fun labelValue(reader: JsonReader): Boolean {
    reader.beginObject()
    while (reader.hasNext()) {
      if (reader.selectName(Options.of(labelKey)) == -1) {
        reader.skipName()
        reader.skipValue()
        continue
      }
      return reader.nextBoolean()
    }
    throw JsonDataException("Missing label for $labelKey")
  }

  override fun toJson(writer: JsonWriter, value: Any?) {
    val type: Class<*> = value!!.javaClass
    when (type) {
      successAdapter.second -> successAdapter.third.toJson(writer, value)
      errorAdapter.first -> errorAdapter.second.toJson(writer, value)
    }
  }

  companion object {
    @JvmStatic
    fun of(
      baseType: Type,
      labelKey: String,
      labelValueForSuccess: Boolean,
      successType: Type,
      errorType: Type
    ): Factory =
      object : Factory {
        override fun create(
          type: Type,
          annotations: Set<Annotation>,
          moshi: Moshi
        ): JsonAdapter<*>? {
          if (type.rawType != baseType || annotations.isNotEmpty()) {
            return null
          }
          return DiMorphicAdapter(
              labelKey,
              Triple(labelValueForSuccess, successType, moshi.adapter(successType)),
              errorType to moshi.adapter(errorType)
            )
            .nullSafe()
        }
      }
  }
}

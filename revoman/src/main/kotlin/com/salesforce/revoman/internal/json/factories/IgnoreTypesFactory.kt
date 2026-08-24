/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.factories

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type

internal class IgnoreTypesFactory(private val typesToIgnore: Set<Type>) : Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    return if (typesToIgnore.contains(type)) {
      object : JsonAdapter<Type>() {
        override fun fromJson(reader: JsonReader): Type? {
          reader.skipValue()
          return null
        }

        override fun toJson(writer: JsonWriter, value: Type?) {
          writer.nullValue()
        }
      }
    } else null
  }
}

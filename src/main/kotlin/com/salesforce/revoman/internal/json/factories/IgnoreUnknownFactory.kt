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
import com.squareup.moshi.Types
import java.lang.reflect.Type

internal class IgnoreUnknownFactory(private val typesToIgnore: Set<Class<out Any>>) : Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    val rawType: Class<*> = Types.getRawType(type)
    return if (typesToIgnore.contains(rawType)) {
      object : JsonAdapter<Type>() {
        override fun fromJson(reader: JsonReader): Type? = null

        override fun toJson(writer: JsonWriter, value: Type?) {
          // do nothing
        }
      }
    } else null
  }
}

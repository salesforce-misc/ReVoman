/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/
package org.revcloud.revoman.internal.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

internal class IgnoreUnknownFactory(private val typesToIgnore: Set<Class<out Any>>) :
  JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*> {
    val rawType = Types.getRawType(type)
    return if (typesToIgnore.contains(rawType)) {
      object : JsonAdapter<Type>() {
        override fun fromJson(reader: JsonReader): Type? {
          return null
        }

        override fun toJson(writer: JsonWriter, value: Type?) {
          // do nothing
        }
      }
    } else {
      moshi.nextAdapter<Any>(this, type, annotations)
    }
  }
}

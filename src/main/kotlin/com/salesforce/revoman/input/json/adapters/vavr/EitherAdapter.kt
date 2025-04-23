/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.vavr

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.rawType
import io.vavr.control.Either
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class EitherAdapter<L, R>(
  private val leftAdapter: JsonAdapter<L>,
  private val rightAdapter: JsonAdapter<R>,
) : JsonAdapter<Either<L, R>>() {

  // ! TODO 23 Apr 2025 gopala.akshintala: Test this
  override fun fromJson(reader: JsonReader): Either<L, R>? {
    return when (reader.peek()) {
      JsonReader.Token.NULL -> {
        reader.nextNull<Any>()
        null
      }
      else -> {
        // Try to parse as right value first
        try {
          val rightValue = rightAdapter.fromJson(reader)
          if (rightValue != null) {
            return Either.right(rightValue)
          }
        } catch (e: Exception) {
          // If parsing as right value fails, try parsing as left value
          reader.beginObject()
          if (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "left") {
              val leftValue = leftAdapter.fromJson(reader)
              reader.endObject()
              return Either.left(leftValue)
            }
          }
          reader.endObject()
          throw e
        }
        null
      }
    }
  }

  override fun toJson(writer: JsonWriter, value: Either<L, R>?) {
    if (value == null) {
      writer.nullValue()
      return
    }

    value.fold(
      { left ->
        writer.beginObject()
        writer.name("left")
        leftAdapter.toJson(writer, left)
        writer.endObject()
      },
      { right -> rightAdapter.toJson(writer, right) },
    )
  }

  companion object {
    val FACTORY =
      object : Factory {
        override fun create(
          type: Type,
          annotations: Set<Annotation>,
          moshi: Moshi,
        ): JsonAdapter<*>? {
          val rawType = type.rawType
          if (rawType != Either::class.java) {
            return null
          }
          val typeArguments = (type as ParameterizedType).actualTypeArguments
          val leftType = typeArguments[0]
          val rightType = typeArguments[1]
          val leftAdapter = moshi.adapter<Any>(leftType)
          val rightAdapter = moshi.adapter<Any>(rightType)
          return EitherAdapter(leftAdapter, rightAdapter).nullSafe()
        }
      }
  }
}

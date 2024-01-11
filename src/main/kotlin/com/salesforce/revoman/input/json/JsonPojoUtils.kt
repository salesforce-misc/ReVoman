/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("JsonPojoUtils")

package com.salesforce.revoman.input.json

import com.salesforce.revoman.input.bufferFileInResources
import com.salesforce.revoman.internal.json.buildMoshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import io.vavr.control.Either
import java.lang.reflect.Type

@JvmOverloads
fun <PojoT : Any> jsonFileToPojo(
  pojoType: Type,
  jsonFilePath: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
): PojoT? {
  val jsonAdapter =
    initMoshi<PojoT>(customAdapters, customAdaptersWithType, typesToIgnore, pojoType)
  return jsonAdapter.fromJson(bufferFileInResources(jsonFilePath))
}

inline fun <reified PojoT : Any> jsonFileToPojo(
  jsonFilePath: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
): PojoT? =
  jsonFileToPojo(
    PojoT::class.java,
    jsonFilePath,
    customAdapters,
    customAdaptersWithType,
    typesToIgnore
  )

@JvmOverloads
fun <PojoT : Any> jsonToPojo(
  pojoType: Type,
  jsonStr: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
): PojoT? {
  val jsonAdapter =
    initMoshi<PojoT>(customAdapters, customAdaptersWithType, typesToIgnore, pojoType)
  return jsonAdapter.fromJson(jsonStr)
}

inline fun <reified PojoT : Any> jsonToPojo(
  jsonStr: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
): PojoT? =
  jsonToPojo(PojoT::class.java, jsonStr, customAdapters, customAdaptersWithType, typesToIgnore)

@JvmOverloads
fun <PojoT : Any> pojoToJson(
  pojoType: Type,
  pojo: PojoT,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet(),
  indent: String? = "  "
): String? {
  val jsonAdapter =
    initMoshi<PojoT>(customAdapters, customAdaptersWithType, typesToIgnore, pojoType)
  return (indent?.let { jsonAdapter.indent(indent) } ?: jsonAdapter).toJson(pojo)
}

inline fun <reified PojoT : Any> pojoToJson(
  pojo: PojoT,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet(),
  indent: String? = "  "
): String? =
  pojoToJson(PojoT::class.java, pojo, customAdapters, customAdaptersWithType, typesToIgnore, indent)

@SuppressWarnings("kotlin:S3923")
private fun <PojoT : Any> initMoshi(
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet(),
  pojoType: Type
): JsonAdapter<PojoT> {
  val moshiBuilder = buildMoshi(customAdapters, customAdaptersWithType, typesToIgnore)
  return moshiBuilder.build().adapter(pojoType)
}

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
import org.immutables.value.Value

/**
 * Parses a JSON file and converts it to a POJO of type [PojoT].
 *
 * @param pojoType The type of the POJO to be created
 * @param jsonFilePath The path to the JSON file
 * @param customAdapters A list of custom JSON adapters to be used during the parsing process
 * @param customAdaptersWithType A map of custom JSON adapters with their respective types
 * @param skipTypes A set of types to be skipped during the parsing process
 * @param <PojoT> The type of the POJO to be created
 * @return the parsed POJO or null if the parsing fails
 */
@JvmOverloads
fun <PojoT : Any> jsonFileToPojo(
  pojoType: Type,
  jsonFilePath: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet()
): PojoT? {
  val jsonAdapter = initMoshi<PojoT>(customAdapters, customAdaptersWithType, skipTypes, pojoType)
  return jsonAdapter.fromJson(bufferFileInResources(jsonFilePath))
}

fun <PojoT : Any> jsonFileToPojo(jsonFile: JsonFile<PojoT>): PojoT? =
  jsonFileToPojo(
    jsonFile.pojoType(),
    jsonFile.jsonFilePath(),
    jsonFile.customAdapters(),
    jsonFile.customAdaptersWithType(),
    jsonFile.skipTypes()
  )

inline fun <reified PojoT : Any> jsonFileToPojo(
  jsonFilePath: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet()
): PojoT? =
  jsonFileToPojo(PojoT::class.java, jsonFilePath, customAdapters, customAdaptersWithType, skipTypes)

/**
 * A generic function that parses a JSON string into a POJO (Plain Old Java Object) of type PojoT.
 *
 * @param <PojoT> The type of the resulting POJO.
 * @param pojoType The class of the resulting POJO.
 * @param jsonStr The JSON string to be parsed.
 * @param customAdapters A list of custom adapters to be used during the parsing process.
 * @param customAdaptersWithType A map of custom adapters with their respective types.
 * @param skipTypes A set of classes to be skipped during the parsing process.
 * @return The parsed POJO of type PojoT.
 */
@JvmOverloads
fun <PojoT : Any> jsonToPojo(
  pojoType: Type,
  jsonStr: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet()
): PojoT? {
  val jsonAdapter = initMoshi<PojoT>(customAdapters, customAdaptersWithType, skipTypes, pojoType)
  return jsonAdapter.fromJson(jsonStr)
}

fun <PojoT : Any> jsonToPojo(jsonString: JsonString<PojoT>): PojoT? =
  jsonToPojo(
    jsonString.pojoType(),
    jsonString.jsonString(),
    jsonString.customAdapters(),
    jsonString.customAdaptersWithType(),
    jsonString.skipTypes()
  )

inline fun <reified PojoT : Any> jsonToPojo(
  jsonStr: String,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet()
): PojoT? =
  jsonToPojo(PojoT::class.java, jsonStr, customAdapters, customAdaptersWithType, skipTypes)

/**
 * Generate a JSON string from a POJO object.
 *
 * @param pojoType The type of the POJO object.
 * @param pojo The POJO object to be converted to JSON.
 * @param customAdapters A list of custom adapters for Moshi to use during the conversion.
 * @param customAdaptersWithType A map of custom adapters with their respective types.
 * @param skipTypes A set of classes to ignore during the conversion.
 * @param indent An optional string for pretty-printing the JSON output.
 * @param <PojoT> The type of the POJO object.
 * @return A JSON string or null if the input is null.
 */
@JvmOverloads
fun <PojoT : Any> pojoToJson(
  pojoType: Type,
  pojo: PojoT,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet(),
  indent: String? = "  "
): String? {
  val jsonAdapter = initMoshi<PojoT>(customAdapters, customAdaptersWithType, skipTypes, pojoType)
  return (indent?.let { jsonAdapter.indent(indent) } ?: jsonAdapter).toJson(pojo)
}

fun <PojoT : Any> pojoToJson(config: Pojo<PojoT>): String? =
  pojoToJson(
    config.pojoType(),
    config.pojo(),
    config.customAdapters(),
    config.customAdaptersWithType(),
    config.skipTypes(),
    config.indent()
  )

inline fun <reified PojoT : Any> pojoToJson(
  pojo: PojoT,
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet(),
  indent: String? = "  "
): String? =
  pojoToJson(PojoT::class.java, pojo, customAdapters, customAdaptersWithType, skipTypes, indent)

@SuppressWarnings("kotlin:S3923")
private fun <PojoT : Any> initMoshi(
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet(),
  pojoType: Type
): JsonAdapter<PojoT> {
  val moshiBuilder = buildMoshi(customAdapters, customAdaptersWithType, skipTypes)
  return moshiBuilder.build().adapter(pojoType)
}

@PojoConfig
@Value.Immutable
internal interface PojoDef<PojoT> {
  fun pojoType(): Type

  fun pojo(): PojoT

  fun customAdapters(): List<Any>

  fun customAdaptersWithType(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>>

  fun skipTypes(): Set<Class<out Any>>

  @Value.Default fun indent(): String = "  "
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Value.Style(
  typeImmutable = "*",
  typeAbstract = ["*Def"],
  builder = "marshall",
  build = "done",
  depluralize = true,
  add = "*",
  put = "*",
  with = "override*",
  visibility = Value.Style.ImplementationVisibility.PUBLIC,
)
private annotation class PojoConfig

internal interface JsonConfig<PojoT> {
  fun pojoType(): Type

  fun customAdapters(): List<Any>

  fun customAdaptersWithType(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>>

  fun skipTypes(): Set<Class<out Any>>
}

@ConfigForJson
@Value.Immutable
internal interface JsonFileDef<PojoT> : JsonConfig<PojoT> {
  fun jsonFilePath(): String
}

@ConfigForJson
@Value.Immutable
internal interface JsonStringDef<PojoT> : JsonConfig<PojoT> {
  fun jsonString(): String
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Value.Style(
  typeImmutable = "*",
  typeAbstract = ["*Def"],
  builder = "unmarshall",
  build = "done",
  depluralize = true,
  add = "*",
  put = "*",
  with = "override*",
  visibility = Value.Style.ImplementationVisibility.PUBLIC,
)
private annotation class ConfigForJson

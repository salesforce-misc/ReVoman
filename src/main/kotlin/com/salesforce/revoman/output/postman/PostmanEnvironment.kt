/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Environment.Companion.fromMap
import com.salesforce.revoman.output.report.Step
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.rawType
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either
import java.lang.reflect.Type

/** This is a Wrapper on `mutableEnv` map, providing some useful utilities */
data class PostmanEnvironment<ValueT : Any?>
@JvmOverloads
constructor(
  val mutableEnv: MutableMap<String, ValueT> = mutableMapOf(),
  @get:JvmName("moshiReVoman") val moshiReVoman: MoshiReVoman = initMoshi(),
) : MutableMap<String, ValueT> by mutableEnv {

  internal lateinit var currentStep: Step
  @JvmField
  val variableToSetStep: MutableMap<String, Step?> = mutableEnv.mapValues { null }.toMutableMap()

  @get:JvmName("immutableEnv") val immutableEnv: Map<String, ValueT> by lazy { mutableEnv.toMap() }

  @get:JvmName("postmanEnvJSONFormat")
  val postmanEnvJSONFormat: String by lazy {
    moshiReVoman.toPrettyJson(fromMap(mutableEnv, moshiReVoman))
  }

  @get:JvmName("envJson")
  val envJson: String by lazy {
    val environment =
      mutableEnv.map { (key, value) -> EnvEntry(key, value, variableToSetStep[key]?.name) }
    moshiReVoman.toPrettyJson(environment)
  }

  fun set(key: String, value: ValueT) {
    mutableEnv[key] = value
    variableToSetStep[key] = if (::currentStep.isInitialized) currentStep else null
    logger.info {
      "pm environment variable set in Step: $currentStep - key: $key, value: ${pprint(value)}"
    }
  }

  @Suppress("unused")
  fun unset(key: String) {
    mutableEnv.remove(key)
    variableToSetStep.remove(key)
    logger.info { "pm environment variable unset through JS in Step: $currentStep - key: $key" }
  }

  // ! TODO 24/06/23 gopala.akshintala: Support for Regex while querying environment

  fun getString(key: String?) = mutableEnv[key] as String?

  fun getInt(key: String?) = mutableEnv[key] as Int?

  // ! TODO 13/09/23 gopala.akshintala: Refactor code to remove duplication

  fun <T> mutableEnvCopyWithValuesOfType(type: Class<T>): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  inline fun <reified T> mutableEnvCopyWithValuesOfType(): PostmanEnvironment<T> =
    mutableEnvCopyWithValuesOfType(T::class.java)

  fun <T> mutableEnvCopyWithKeysStartingWith(
    type: Class<T>,
    vararg prefixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && prefixes.any { prefix -> it.key.startsWith(prefix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  inline fun <reified T> mutableEnvCopyWithKeysStartingWith(
    vararg prefixes: String
  ): PostmanEnvironment<T> = mutableEnvCopyWithKeysStartingWith(T::class.java, *prefixes)

  fun <T> mutableEnvCopyExcludingKeys(
    type: Class<T>,
    whiteListKeys: Set<String>,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && !whiteListKeys.contains(it.key) }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  fun <T> mutableEnvCopyWithKeysNotStartingWith(
    type: Class<T>,
    vararg prefixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && prefixes.all { suffix -> !it.key.startsWith(suffix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  @JvmOverloads
  fun <PojoT : Any> getTypedObj(
    key: String,
    targetType: Type,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ): PojoT? {
    val value = mutableEnv[key]
    return when {
      value == null -> null
      targetType.rawType.isPrimitive && targetType.rawType.isInstance(value) -> value
      targetType == String::class.java -> value
      else -> {
        moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
        when (value) {
          is String -> moshiReVoman.fromJson(value, targetType)
          else -> moshiReVoman.objToJsonStrToObj(value, targetType)
        }
      }
    }
      as PojoT?
  }

  @JvmOverloads
  inline fun <reified PojoT : Any> getObj(
    key: String,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ): PojoT? {
    val value = mutableEnv[key]
    return when {
      value == null -> null
      value is PojoT -> value
      PojoT::class == String::class -> value as PojoT
      else -> {
        moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
        when (value) {
          is String -> moshiReVoman.fromJson(value)
          else -> moshiReVoman.objToJsonStrToObj(value)
        }
      }
    }
  }

  fun <T> mutableEnvCopyWithKeysEndingWith(
    type: Class<T>,
    vararg suffixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  fun <T> mutableEnvCopyWithKeysNotEndingWith(
    type: Class<T>,
    vararg suffixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && suffixes.all { suffix -> !it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  fun <T> valuesForKeysStartingWith(type: Class<T>, prefix: String): Set<T> =
    mutableEnvCopyWithKeysStartingWith(type, prefix).mutableEnv.values.toSet()

  fun <T> valuesForKeysStartingWith(type: Class<T>, vararg prefixes: String): Set<T> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && prefixes.any { suffix -> it.key.startsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysNotStartingWith(type: Class<T>, vararg prefixes: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && prefixes.all { suffix -> !it.key.startsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysEndingWith(type: Class<T>, suffix: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && it.key.endsWith(suffix) }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysEndingWith(type: Class<T>, vararg suffixes: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysNotEndingWith(type: Class<T>, vararg suffixes: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && suffixes.all { suffix -> !it.key.endsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  @JsonClass(generateAdapter = true)
  data class EnvEntry(val key: String, val value: Any?, val lastSetStepName: String?)
}

private val logger = KotlinLogging.logger {}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.salesforce.revoman.internal.json.initMoshi
import com.salesforce.revoman.internal.postman.template.Environment.Companion.fromMap
import com.squareup.moshi.rawType
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Type
import org.http4k.format.ConfigurableMoshi

/** This is a Wrapper on `mutableEnv` map, providing some useful utilities */
data class PostmanEnvironment<ValueT : Any?>
@JvmOverloads
constructor(
  val mutableEnv: MutableMap<String, ValueT> = mutableMapOf(),
  val moshiReVoman: ConfigurableMoshi = initMoshi(),
) : MutableMap<String, ValueT> by mutableEnv {

  @get:JvmName("immutableEnv") val immutableEnv: Map<String, ValueT> by lazy { mutableEnv.toMap() }

  @get:JvmName("postmanEnvJSONFormat")
  val postmanEnvJSONFormat: String by lazy {
    moshiReVoman.prettify(moshiReVoman.asFormatString(fromMap(mutableEnv, moshiReVoman)))
  }

  fun set(key: String, value: ValueT) {
    mutableEnv[key] = value
    logger.info { "pm environment variable set - key: $key, value: ${pprint(value)}" }
  }

  @Suppress("unused")
  fun unset(key: String) {
    mutableEnv.remove(key)
    logger.info { "pm environment variable unset through JS - key: $key" }
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

  fun <T : Any> getTypedObj(key: String, objType: Type): T? {
    val value = mutableEnv[key]
    return when {
      value == null -> null
      objType.rawType.isInstance(value) -> value
      else -> moshiReVoman.asA(moshiReVoman.asFormatString(value as Any), objType.rawType.kotlin)
    }
      as T?
  }

  inline fun <reified T : Any> getObj(key: String): T? {
    val value = mutableEnv[key]
    return when (value) {
      null,
      is T -> value
      else -> moshiReVoman.asA(moshiReVoman.asFormatString(value as Any), T::class)
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
}

private val logger = KotlinLogging.logger {}

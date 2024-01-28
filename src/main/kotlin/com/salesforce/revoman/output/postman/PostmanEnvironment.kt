/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging

/** This is more like a value class (wrapper) on mutableEnv providing some useful utilities */
data class PostmanEnvironment<ValueT : Any?>(
  private val mutableEnv: MutableMap<String, ValueT> = mutableMapOf()
) : MutableMap<String, ValueT> by mutableEnv {

  @get:JvmName("immutableEnv")
  val immutableEnvironment: Map<String, ValueT> by lazy { mutableEnv.toMap() }

  fun set(key: String, value: ValueT) {
    mutableEnv[key] = value
    logger.info {
      "pm environment variable set through Tests JS - key : $key, value: ${pprint(value)}"
    }
  }

  @Suppress("unused")
  fun unset(key: String) {
    mutableEnv.remove(key)
    logger.info { "pm environment variable unset through Tests JS - key : $key" }
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
        .toMutableMap()
    )

  fun <T> mutableEnvCopyWithKeysStartingWith(
    type: Class<T>,
    vararg prefixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && prefixes.any { prefix -> it.key.startsWith(prefix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> mutableEnvCopyExcludingKeys(
    type: Class<T>,
    whiteListKeys: Set<String>
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && !whiteListKeys.contains(it.key) }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> mutableEnvCopyWithKeysNotStartingWith(
    type: Class<T>,
    vararg prefixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && prefixes.all { suffix -> !it.key.startsWith(suffix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> mutableEnvCopyWithKeysEndingWith(
    type: Class<T>,
    vararg suffixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> mutableEnvCopyWithKeysNotEndingWith(
    type: Class<T>,
    vararg suffixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && suffixes.all { suffix -> !it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
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

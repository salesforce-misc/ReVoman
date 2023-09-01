/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.output.postman

data class PostmanEnvironment<ValueT>(
  private val environment: MutableMap<String, ValueT> = mutableMapOf()
) : MutableMap<String, ValueT> by environment {
  fun set(key: String, value: ValueT) {
    environment[key] = value
  }

  @Suppress("unused")
  fun unset(key: String) {
    environment.remove(key)
  }

  // ! TODO 24/06/23 gopala.akshintala: Support for Regex while querying environment

  fun getString(key: String?) = environment[key] as String?

  fun getInt(key: String?) = environment[key] as Int?

  fun <T> valuesForKeysStartingWith(type: Class<T>, prefix: String): MutableCollection<T> =
    envCopyWithKeysStartingWith(type, prefix).environment.values

  fun <T> envCopyWithKeysStartingWith(
    type: Class<T>,
    vararg prefixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      environment
        .filter {
          type.isInstance(it.value) && prefixes.any { prefix -> it.key.startsWith(prefix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> envCopyExcludingKeys(type: Class<T>, whiteListKeys: Set<String>): PostmanEnvironment<T> =
    PostmanEnvironment(
      environment
        .filter { type.isInstance(it.value) && !whiteListKeys.contains(it.key) }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> envCopyWithKeysNotStartingWith(
    type: Class<T>,
    vararg prefixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      environment
        .filter {
          type.isInstance(it.value) && prefixes.any { suffix -> !it.key.startsWith(suffix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> envCopyWithKeysEndingWith(
    type: Class<T>,
    vararg suffixes: String
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      environment
        .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap()
    )

  fun <T> valuesForKeysStartingWith(type: Class<T>, vararg prefixes: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { type.isInstance(it.value) && prefixes.any { suffix -> it.key.startsWith(suffix) } }
      .map { type.cast(it.value) }
      .toList()

  fun <T> valuesForKeysNotStartingWith(type: Class<T>, vararg prefixes: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { type.isInstance(it.value) && prefixes.any { suffix -> !it.key.startsWith(suffix) } }
      .map { type.cast(it.value) }
      .toList()

  fun <T> valuesForKeysEndingWith(type: Class<T>, suffix: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { type.isInstance(it.value) && it.key.endsWith(suffix) }
      .map { type.cast(it.value) }
      .toList()

  fun <T> valuesForKeysEndingWith(type: Class<T>, vararg suffixes: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
      .map { type.cast(it.value) }
      .toList()

  fun <T> valuesForKeysNotEndingWith(type: Class<T>, vararg suffixes: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { type.isInstance(it.value) && suffixes.any { suffix -> !it.key.endsWith(suffix) } }
      .map { type.cast(it.value) }
      .toList()
}

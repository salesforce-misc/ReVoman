/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.postman

data class PostmanEnvironment(
  private val environment: MutableMap<String, Any?> = mutableMapOf()
) : MutableMap<String, Any?> by environment {
  fun set(key: String, value: Any?) {
    environment[key] = value
  }

  @Suppress("unused")
  fun unset(key: String) {
    environment.remove(key)
  }

  // ! TODO 24/06/23 gopala.akshintala: Support for Regex while quering environment
  
  fun getString(key: String?) = environment[key] as String?
  
  fun getInt(key: String?) = environment[key] as Int?

  fun <T> getValuesForKeysStartingWith(type: Class<T>, prefix: String): List<T?> =
    environment.entries.asSequence().filter { it.key.startsWith(prefix) }.map { type.cast(it.value) }.toList()

  fun <T> getValuesForKeysStartingWith(type: Class<T>, vararg prefixes: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { prefixes.any { suffix -> it.key.startsWith(suffix) } }
      .map { type.cast(it.value) }
      .toList()

  fun <T> getValuesForKeysEndingWith(type: Class<T>, suffix: String): List<T?> =
    environment.entries.asSequence().filter { it.key.endsWith(suffix) }.map { type.cast(it.value) }.toList()

  fun <T> getValuesForKeysEndingWith(type: Class<T>, vararg suffixes: String): List<T?> =
    environment.entries
      .asSequence()
      .filter { suffixes.any { suffix -> it.key.endsWith(suffix) } }
      .map { type.cast(it.value) }
      .toList()
}

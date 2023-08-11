/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/
package org.revcloud.revoman.postman

data class PostmanEnvironment(
  private val environment: MutableMap<String, String?> = mutableMapOf()
) : MutableMap<String, String?> by environment {
  fun set(key: String, value: String?) {
    environment[key] = value
  }

  @Suppress("unused")
  fun unset(key: String) {
    environment.remove(key)
  }

  // ! TODO 24/06/23 gopala.akshintala: Support for Regex while fetching environment

  fun getValuesForKeysStartingWith(prefix: String): List<String?> =
    environment.entries.asSequence().filter { it.key.startsWith(prefix) }.map { it.value }.toList()

  fun getValuesForKeysStartingWith(vararg prefixes: String): List<String?> =
    environment.entries
      .asSequence()
      .filter { prefixes.any { suffix -> it.key.startsWith(suffix) } }
      .map { it.value }
      .toList()

  fun getValuesForKeysEndingWith(suffix: String): List<String?> =
    environment.entries.asSequence().filter { it.key.endsWith(suffix) }.map { it.value }.toList()

  fun getValuesForKeysEndingWith(vararg suffixes: String): List<String?> =
    environment.entries
      .asSequence()
      .filter { suffixes.any { suffix -> it.key.endsWith(suffix) } }
      .map { it.value }
      .toList()
}

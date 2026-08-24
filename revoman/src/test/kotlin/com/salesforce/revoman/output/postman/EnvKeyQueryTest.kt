/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Coverage for the key/value query methods that had no other caller in the project:
 * [PostmanEnvironment.mutableEnvCopyWithValuesOfType] (reified),
 * [PostmanEnvironment.mutableEnvCopyWithKeysNotStartingWith],
 * [PostmanEnvironment.valuesForKeysNotStartingWith], [PostmanEnvironment.valuesForKeysEndingWith]
 * (vararg) and [PostmanEnvironment.valuesForKeysNotEndingWith].
 */
class EnvKeyQueryTest {
  private val env =
    PostmanEnvironment<Any?>(
      mutableMapOf(
        "saId1" to "a",
        "saId2" to "b",
        "userId" to "c",
        "accountName" to "d",
        "num" to 1,
      )
    )

  @Test
  fun `reified mutableEnvCopyWithValuesOfType retains only values of that type`() {
    assertThat(env.mutableEnvCopyWithValuesOfType<String>())
      .containsExactlyEntriesIn(
        mapOf("saId1" to "a", "saId2" to "b", "userId" to "c", "accountName" to "d")
      )
    assertThat(env.mutableEnvCopyWithValuesOfType<Int>())
      .containsExactlyEntriesIn(mapOf("num" to 1))
  }

  @Test
  fun `mutableEnvCopyWithKeysNotStartingWith drops keys starting with any prefix`() {
    assertThat(env.mutableEnvCopyWithKeysNotStartingWith(String::class.java, "saId", "user"))
      .containsExactlyEntriesIn(mapOf("accountName" to "d"))
  }

  @Test
  fun `mutableEnvCopyWithKeysNotStartingWith with no prefixes retains all typed entries`() {
    assertThat(env.mutableEnvCopyWithKeysNotStartingWith(String::class.java))
      .containsExactlyEntriesIn(
        mapOf("saId1" to "a", "saId2" to "b", "userId" to "c", "accountName" to "d")
      )
  }

  @Test
  fun `valuesForKeysNotStartingWith returns values of keys not starting with any prefix`() {
    assertThat(env.valuesForKeysNotStartingWith(String::class.java, "saId", "user"))
      .containsExactly("d")
  }

  @Test
  fun `valuesForKeysNotStartingWith filters by type`() {
    assertThat(env.valuesForKeysNotStartingWith(Integer::class.java, "saId")).containsExactly(1)
  }

  @Test
  fun `valuesForKeysEndingWith vararg matches any suffix`() {
    assertThat(env.valuesForKeysEndingWith(String::class.java, "Id1", "Name"))
      .containsExactly("a", "d")
  }

  @Test
  fun `valuesForKeysEndingWith vararg no match is empty`() {
    assertThat(env.valuesForKeysEndingWith(String::class.java, "zzz")).isEmpty()
  }

  @Test
  fun `valuesForKeysNotEndingWith excludes values whose keys end with any suffix`() {
    assertThat(env.valuesForKeysNotEndingWith(String::class.java, "Id1", "Id2"))
      .containsExactly("c", "d")
  }

  @Test
  fun `valuesForKeysNotEndingWith with no suffixes returns all typed values`() {
    assertThat(env.valuesForKeysNotEndingWith(String::class.java))
      .containsExactly("a", "b", "c", "d")
  }
}

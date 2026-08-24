/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.google.common.truth.Truth.assertThat
import java.util.regex.PatternSyntaxException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KeysMatchingRegexTest {
  private val env =
    PostmanEnvironment<Any?>(
      mutableMapOf(
        "saId1" to "a",
        "saId2" to "b",
        "my_saId" to "c",
        "other" to "d",
        "num1" to 1,
        "num2" to 2,
      )
    )

  @Test
  fun `valuesForKeysMatching partial matches anywhere in the key`() {
    // bare pattern is a contains-match: hits both prefixed and infixed keys
    assertThat(env.valuesForKeysMatching(String::class.java, "saId")).containsExactly("a", "b", "c")
  }

  @Test
  fun `valuesForKeysMatching anchored prefix`() {
    assertThat(env.valuesForKeysMatching(String::class.java, "^saId")).containsExactly("a", "b")
  }

  @Test
  fun `valuesForKeysMatching anchored suffix`() {
    assertThat(env.valuesForKeysMatching(String::class.java, "saId$")).containsExactly("c")
  }

  @Test
  fun `valuesForKeysMatching exact anchored match`() {
    assertThat(env.valuesForKeysMatching(String::class.java, "^saId1$")).containsExactly("a")
  }

  @Test
  fun `valuesForKeysMatching filters by type`() {
    assertThat(env.valuesForKeysMatching(Integer::class.java, "num")).containsExactly(1, 2)
  }

  @Test
  fun `valuesForKeysMatching OR across multiple patterns`() {
    assertThat(env.valuesForKeysMatching(String::class.java, "^saId1$", "^other$"))
      .containsExactly("a", "d")
  }

  @Test
  fun `valuesForKeysMatching no match is empty`() {
    assertThat(env.valuesForKeysMatching(String::class.java, "zzz")).isEmpty()
  }

  @Test
  fun `valuesForKeysMatching empty patterns is empty`() {
    assertThat(env.valuesForKeysMatching(String::class.java)).isEmpty()
  }

  @Test
  fun `valuesForKeysNotMatching excludes keys matching any pattern`() {
    assertThat(env.valuesForKeysNotMatching(String::class.java, "saId")).containsExactly("d")
  }

  @Test
  fun `valuesForKeysNotMatching empty patterns returns all typed values`() {
    assertThat(env.valuesForKeysNotMatching(String::class.java)).containsExactly("a", "b", "c", "d")
  }

  @Test
  fun `mutableEnvCopyWithKeysMatching retains only matching entries`() {
    assertThat(env.mutableEnvCopyWithKeysMatching(String::class.java, "^saId"))
      .containsExactlyEntriesIn(mapOf("saId1" to "a", "saId2" to "b"))
  }

  @Test
  fun `mutableEnvCopyWithKeysNotMatching drops matching entries`() {
    assertThat(env.mutableEnvCopyWithKeysNotMatching(String::class.java, "saId"))
      .containsExactlyEntriesIn(mapOf("other" to "d"))
  }

  @Test
  fun `mutableEnvCopyWithKeysMatching filters by type`() {
    assertThat(env.mutableEnvCopyWithKeysMatching(Integer::class.java, "num"))
      .containsExactlyEntriesIn(mapOf("num1" to 1, "num2" to 2))
  }

  @Test
  fun `invalid regex surfaces PatternSyntaxException`() {
    assertThrows<PatternSyntaxException> { env.valuesForKeysMatching(String::class.java, "[") }
  }
}

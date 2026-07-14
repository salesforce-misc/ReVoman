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

class ValuesForKeysStartingWithTest {
  private val env =
    PostmanEnvironment<Any?>(
      mutableMapOf("saId1" to "a", "saId2" to "b", "other" to "c", "num" to 1)
    )

  @Test
  fun `single prefix returns typed values for matching keys`() {
    assertThat(env.valuesForKeysStartingWith(String::class.java, "saId")).containsExactly("a", "b")
  }

  @Test
  fun `single prefix filters by type`() {
    assertThat(env.valuesForKeysStartingWith(Integer::class.java, "num")).containsExactly(1)
  }

  @Test
  fun `single prefix no match is empty`() {
    assertThat(env.valuesForKeysStartingWith(String::class.java, "zzz")).isEmpty()
  }
}

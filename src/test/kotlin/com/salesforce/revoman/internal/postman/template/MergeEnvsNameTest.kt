/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MergeEnvsNameTest {
  @Test
  fun `mergeEnvs reads the environment name from a v2 json env file`() {
    val merged =
      mergeEnvs(
        setOf("pm-templates/mini-env.postman_environment.json"),
        emptyList(),
        emptyMap(),
      )
    merged.name shouldBe "Pokemon"
    merged.values["baseUrl"] shouldBe "https://pokeapi.co/api/v2"
  }

  @Test
  fun `mergeEnvs name is null when there is no env source`() {
    val merged = mergeEnvs(emptySet(), emptyList(), mapOf("k" to "v"))
    merged.name shouldBe null
    merged.values["k"] shouldBe "v"
  }
}

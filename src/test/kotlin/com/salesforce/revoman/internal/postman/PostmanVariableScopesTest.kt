/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PostmanVariableScopesTest {
  @Test
  fun `factory retains the three peer stores and the initial environment name`() {
    val environment = environment(mapOf("environment" to "e"))
    val collectionVariables = environment(mapOf("collection" to "c"))
    val globals = environment(mapOf("global" to "g"))

    val scopes =
      postmanVariableScopes(environment, collectionVariables, globals, environmentName = "Pokemon")

    scopes.environment shouldBe environment
    scopes.collectionVariables shouldBe collectionVariables
    scopes.globals shouldBe globals
    scopes.environmentName shouldBe "Pokemon"
    scopes.environmentName = "Salesforce"
    scopes.environmentName shouldBe "Salesforce"
  }

  @Test
  fun `resolve and owner follow environment then collection then globals precedence`() {
    val environment = environment(mapOf("same" to "env"))
    val collectionVariables = environment(mapOf("same" to "collection", "collection" to "c"))
    val globals = environment(mapOf("same" to "global", "collection" to "g", "global" to "g"))
    val scopes = postmanVariableScopes(environment, collectionVariables, globals, null)

    scopes.resolve("same") shouldBe "env"
    scopes.ownerOf("same") shouldBe environment
    scopes.resolve("collection") shouldBe "c"
    scopes.ownerOf("collection") shouldBe collectionVariables
    scopes.resolve("global") shouldBe "g"
    scopes.ownerOf("global") shouldBe globals
    scopes.resolve("missing").shouldBeNull()
    scopes.ownerOf("missing").shouldBeNull()
  }

  @Test
  fun `contains distinguishes a present null from an absent key`() {
    val globals = environment(mapOf("nullable" to null))
    val scopes = postmanVariableScopes(environment(), environment(), globals, null)

    scopes.contains("nullable") shouldBe true
    scopes.resolve("nullable").shouldBeNull()
    scopes.ownerOf("nullable") shouldBe globals
    scopes.contains("missing") shouldBe false
  }

  private fun environment(values: Map<String, Any?> = emptyMap()): PostmanEnvironment<Any?> =
    PostmanEnvironment(values.toMutableMap(), initMoshi())
}

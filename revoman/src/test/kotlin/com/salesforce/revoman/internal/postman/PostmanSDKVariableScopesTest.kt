/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Exhaustive coverage of the three persistent Postman variable scopes on [PostmanSDK] —
 * `environment`, `collectionVariables`, `globals` — and the aggregate `pm.variables` read view that
 * resolves across them by Postman precedence (narrowest wins): `environment` ▸
 * `collectionVariables` ▸ `globals`.
 */
class PostmanSDKVariableScopesTest {
  private fun sdk() = PostmanSDK(initMoshi())

  // ------------------------------------------------------------------ globals store

  @Test
  fun `globals store round-trips set and toMap`() {
    val pm = sdk()
    pm.globals.set("g", "1")
    pm.globals.toMap() shouldContainExactly mapOf("g" to "1")
  }

  @Test
  fun `globals store supports unset`() {
    val pm = sdk()
    pm.globals.set("g", "1")
    pm.globals.unset("g")
    pm.globals.toMap().shouldBeEmpty()
  }

  @Test
  fun `globals is dormant for the ledger - set captures no produced keys`() {
    val pm = sdk()
    // No `currentStep` is ever set on the globals instance, so producedKeysFor is empty for any
    // step
    // the test could query. We assert the store mutated but the ledger stayed silent by confirming
    // globals has the key while it never threw on the unstepped instance (would throw if it touched
    // an uninitialized lateinit currentStep).
    pm.globals.set("g", "1")
    pm.globals["g"] shouldBe "1"
  }

  @Test
  fun `globals and collectionVariables and environment are independent stores`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    pm.environment["k"] shouldBe "env"
    pm.collectionVariables["k"] shouldBe "cv"
    pm.globals["k"] shouldBe "glob"
  }

  // ------------------------------------------------------------------ precedence matrix (get)

  @Test
  fun `get resolves env-only key from environment`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.variables.get("k") shouldBe "env"
  }

  @Test
  fun `get resolves cv-only key from collectionVariables`() {
    val pm = sdk()
    pm.collectionVariables.set("k", "cv")
    pm.variables.get("k") shouldBe "cv"
  }

  @Test
  fun `get resolves globals-only key from globals`() {
    val pm = sdk()
    pm.globals.set("k", "glob")
    pm.variables.get("k") shouldBe "glob"
  }

  @Test
  fun `get prefers environment over collectionVariables`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.collectionVariables.set("k", "cv")
    pm.variables.get("k") shouldBe "env"
  }

  @Test
  fun `get prefers environment over globals`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.globals.set("k", "glob")
    pm.variables.get("k") shouldBe "env"
  }

  @Test
  fun `get prefers collectionVariables over globals`() {
    val pm = sdk()
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    pm.variables.get("k") shouldBe "cv"
  }

  @Test
  fun `get prefers environment when present in all three`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    pm.variables.get("k") shouldBe "env"
  }

  @Test
  fun `get returns null when no scope has the key`() {
    val pm = sdk()
    pm.variables.get("missing").shouldBeNull()
  }

  @Test
  fun `get returns null value when scope holds an explicit null (presence not value)`() {
    val pm = sdk()
    pm.globals.set("k", null)
    pm.variables.get("k").shouldBeNull()
    pm.variables.has("k") shouldBe true
  }

  // ------------------------------------------------------------------ precedence matrix (has)

  @Test
  fun `has is true for env-only key`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.variables.has("k") shouldBe true
  }

  @Test
  fun `has is true for cv-only key`() {
    val pm = sdk()
    pm.collectionVariables.set("k", "cv")
    pm.variables.has("k") shouldBe true
  }

  @Test
  fun `has is true for globals-only key`() {
    val pm = sdk()
    pm.globals.set("k", "glob")
    pm.variables.has("k") shouldBe true
  }

  @Test
  fun `has is false when no scope has the key`() {
    val pm = sdk()
    pm.variables.has("missing") shouldBe false
  }

  // ------------------------------------------------------------------ pm.variables.set routing
  // (D1)

  @Test
  fun `set routes to environment scope only`() {
    val pm = sdk()
    pm.currentStepReport = mockk()
    pm.variables.set("k", "v")
    pm.environment["k"] shouldBe "v"
    pm.collectionVariables.containsKey("k") shouldBe false
    pm.globals.containsKey("k") shouldBe false
  }

  // ------------------------------------------------------------------ helper-level coverage

  @Test
  fun `resolveScopedValue follows precedence directly`() {
    val pm = sdk()
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    pm.resolveScopedValue("k") shouldBe "cv"
    pm.resolveScopedValue("absent").shouldBeNull()
  }

  @Test
  fun `hasScopedValue follows precedence directly`() {
    val pm = sdk()
    pm.globals.set("k", "glob")
    pm.hasScopedValue("k") shouldBe true
    pm.hasScopedValue("absent") shouldBe false
  }

  @Test
  fun `scopeForKey returns the owning scope by precedence`() {
    val pm = sdk()
    pm.environment.set("k", "env")
    pm.globals.set("k", "glob")
    pm.scopeForKey("k") shouldBe pm.environment
    pm.collectionVariables.set("only", "cv")
    pm.scopeForKey("only") shouldBe pm.collectionVariables
    pm.scopeForKey("absent").shouldBeNull()
  }
}

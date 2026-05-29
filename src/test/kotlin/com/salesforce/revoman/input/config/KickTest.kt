/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.KickDef.Companion.overlay
import com.salesforce.revoman.output.ExeType.HTTP_STATUS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KickTest {
  @Test
  fun `'haltOnAnyFailureExceptForSteps' should be null when 'haltOnAnyFailure' is set to True`() {
    shouldThrow<IllegalArgumentException> {
      Kick.configure()
        .haltOnAnyFailure(true)
        .haltOnFailureOfTypeExcept(HTTP_STATUS) { _, _ -> true }
        .off()
    }
  }

  @Test
  fun `overlay applies later overlays over base, last wins`() {
    val base = mapOf("a" to "base-a", "shared" to "base-shared")
    val mid = mapOf("b" to "mid-b", "shared" to "mid-shared")
    val top = mapOf("c" to "top-c", "shared" to "top-shared")
    overlay<String, String>(base, mid, top) shouldContainExactly
      mapOf("a" to "base-a", "b" to "mid-b", "c" to "top-c", "shared" to "top-shared")
  }

  @Test
  fun `overlay with no overlays returns base contents`() {
    val base = mapOf("a" to "1", "b" to "2")
    overlay<String, String>(base) shouldContainExactly mapOf("a" to "1", "b" to "2")
  }

  @Test
  fun `overlay base is the floor - overlay value wins on key clash`() {
    val base = mapOf("accessToken" to "admin-token")
    val creds = mapOf("accessToken" to "persona-token")
    overlay<String, String>(base, creds)["accessToken"] shouldBe "persona-token"
  }

  @Test
  fun `overlay with three layers sharing a key - final overlay wins (the revUpAs pattern)`() {
    val env = mapOf("accessToken" to "admin")
    val dynamicEnvironment = mapOf("accessToken" to "config-override")
    val creds = mapOf("accessToken" to "persona")
    overlay<String, String>(env, dynamicEnvironment, creds)["accessToken"] shouldBe "persona"
  }
}

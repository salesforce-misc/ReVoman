/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RunbookContractTest {
  private fun step(
    consumes: Set<String> = emptySet(),
    produces: Map<String, String?> = emptyMap(),
  ) =
    RunbookStep(
      intent = "s",
      phase = Phase.ACT,
      kick = Kick.configure().templatePath("pm-templates/v3/cf-stop").off(),
      consumes = consumes,
      produces = produces,
      underTest = false,
      assertAfter = null,
    )

  private fun env(vararg entries: Pair<String, Any?>) =
    PostmanEnvironment<Any?>(mutableMapOf(*entries))

  @Test
  fun `consumes passes when all declared keys present, ignoring extras`() {
    val missing = checkConsumes(step(consumes = setOf("authToken")), setOf("authToken", "extra"))
    assertThat(missing).isEmpty()
  }

  @Test
  fun `consumes reports only the missing declared keys`() {
    val missing = checkConsumes(step(consumes = setOf("authToken", "userId")), setOf("authToken"))
    assertThat(missing).containsExactly("userId")
  }

  @Test
  fun `produces key-only passes when present`() {
    val v = checkProduces(step(produces = mapOf("accountId" to null)), env("accountId" to "001"))
    assertThat(v.isEmpty()).isTrue()
  }

  @Test
  fun `produces key-only reports missing key`() {
    val v = checkProduces(step(produces = mapOf("accountId" to null)), env())
    assertThat(v.missingProduced).containsExactly("accountId")
  }

  @Test
  fun `produces key to value reports mismatch, passes on match`() {
    val ok =
      checkProduces(step(produces = mapOf("status" to "Success")), env("status" to "Success"))
    assertThat(ok.isEmpty()).isTrue()
    val bad = checkProduces(step(produces = mapOf("status" to "Success")), env("status" to "Error"))
    assertThat(bad.valueMismatches).containsKey("status")
    assertThat(bad.valueMismatches["status"]).isEqualTo("Success" to "Error")
  }

  @Test
  fun `empty declarations are always satisfied`() {
    assertThat(checkConsumes(step(), setOf("x"))).isEmpty()
    assertThat(checkProduces(step(), env("x" to 1)).isEmpty()).isTrue()
  }
}

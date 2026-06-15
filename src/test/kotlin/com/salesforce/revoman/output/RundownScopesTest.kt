/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Confirms [Rundown] exposes the `collectionVariables` and `globals` scopes as peers of
 * [Rundown.mutableEnv] (the environment scope), with backward-compatible empty defaults.
 */
class RundownScopesTest {
  @Test
  fun `default construction leaves collectionVariables and globals empty`() {
    val rundown =
      Rundown(
        mutableEnv = PostmanEnvironment(),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 0,
      )
    rundown.collectionVariables.toMap().shouldBeEmpty()
    rundown.globals.toMap().shouldBeEmpty()
  }

  @Test
  fun `carries the supplied collectionVariables and globals scopes`() {
    val cv = PostmanEnvironment<Any?>(mutableMapOf("c" to "cv"))
    val globals = PostmanEnvironment<Any?>(mutableMapOf("g" to "glob"))
    val rundown =
      Rundown(
        mutableEnv = PostmanEnvironment(mutableMapOf("e" to "env")),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 0,
        collectionVariables = cv,
        globals = globals,
      )
    rundown.collectionVariables["c"] shouldBe "cv"
    rundown.globals["g"] shouldBe "glob"
    // mutableEnv stays the environment scope, untouched by the new peers.
    rundown.mutableEnv["e"] shouldBe "env"
    rundown.mutableEnv.containsKey("c") shouldBe false
    rundown.mutableEnv.containsKey("g") shouldBe false
  }
}

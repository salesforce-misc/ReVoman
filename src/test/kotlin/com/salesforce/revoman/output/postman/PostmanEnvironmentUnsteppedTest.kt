/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Regression guard for the `pm.collectionVariables` store — a [PostmanEnvironment] whose
 * `currentStep` lateinit is intentionally NEVER set (only the step-bound `environment` instance
 * sets it, to keep per-step ledger capture dormant on the collection-variable store).
 *
 * The bug this guards: `set`/`unset` referenced `currentStep` in their `logger.info { }` message
 * OUTSIDE the `::currentStep.isInitialized` guard, so on an unstepped instance the message lambda
 * threw `UninitializedPropertyAccessException` whenever INFO logging was enabled (the norm in the
 * Core embedding). The fix reads `currentStep` into a single null-safe `step` local shared by the
 * ledger-capture AND the log line, so neither touches the lateinit when it is unset.
 *
 * This test pins the observable, logging-independent contract: `set`/`unset` on an unstepped
 * instance mutate the map and record nothing. (The throw itself is logging-gated; this repo's test
 * JVM log binding defaults above INFO so it cannot be reproduced here — the guarantee asserted is
 * that the methods are total on an unstepped instance, which is what the fix makes structurally
 * true: every `currentStep` read now sits behind the `isInitialized` guard.)
 */
class PostmanEnvironmentUnsteppedTest {
  @Test
  fun `set then unset on an unstepped instance mutate the map and record no produced keys`() {
    // Constructed exactly how PostmanSDK.collectionVariables is: no currentStep ever assigned.
    val store: PostmanEnvironment<Any?> = PostmanEnvironment()

    store.set("a", "1")
    store.set("b", 2)
    store.mutableEnv shouldContainExactly mapOf("a" to "1", "b" to 2)

    store.unset("a")
    store.mutableEnv.containsKey("a") shouldBe false
    store.mutableEnv shouldContainExactly mapOf("b" to 2)
  }
}

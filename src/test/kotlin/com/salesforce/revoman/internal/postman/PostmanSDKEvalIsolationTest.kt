/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A2 guard for the JSEvaluator Context (peer of the sandbox Context). The Engine is shared; the
 * Context is per-PostmanSDK. Two instances must NOT see each other's guest globals.
 */
class PostmanSDKEvalIsolationTest {
  @Test
  fun `two PostmanSDK instances sharing the Engine do not leak JS globals`() {
    val pm1 = PostmanSDK(initMoshi())
    pm1.evaluateJS("globalThis.__leak = 'from-pm1'; 1")
    val pm2 = PostmanSDK(initMoshi())
    pm2.evaluateJS("typeof globalThis.__leak").asString() shouldBe "undefined"
  }
}

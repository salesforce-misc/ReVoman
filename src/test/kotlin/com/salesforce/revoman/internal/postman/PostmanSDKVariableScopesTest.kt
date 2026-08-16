/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PostmanSDKVariableScopesTest {
  @Test
  fun `transitional facade retains the exact focused scopes instance`() {
    val graph = focusedPostmanTestGraph(environmentValues = mapOf("seed" to "value"))
    val capture = stepScriptCapture()
    val pm = postmanSDK(graph.scopes, capture, graph.progress, graph.replacer)

    pm.scopes shouldBe graph.scopes
    pm.scopes.resolve("seed") shouldBe "value"
  }
}

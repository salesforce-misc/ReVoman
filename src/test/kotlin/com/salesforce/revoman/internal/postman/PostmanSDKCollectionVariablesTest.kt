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

class PostmanSDKCollectionVariablesTest {
  @Test
  fun `transitional facade retains capture and regex replacer wiring`() {
    val graph = focusedPostmanTestGraph(collectionVariableValues = mapOf("collection" to "v"))
    val capture = stepScriptCapture()
    val pm = postmanSDK(graph.scopes, capture, graph.progress, graph.replacer)

    pm.capture shouldBe capture
    pm.regexReplacer shouldBe graph.replacer
    pm.scopes.collectionVariables["collection"] shouldBe "v"
  }
}

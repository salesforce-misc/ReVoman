/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.junit.jupiter.api.Test

class TemplateRequestTest {
  @Test
  fun `toHttpRequestSafe falls back on invalid method`() {
    val request = Request(method = "NOT_A_METHOD", url = Url("https://example.com"))
    val httpRequest = request.toHttpRequestSafe(null)
    httpRequest.method shouldBe Method.GET
    httpRequest.uri.toString() shouldBe "https://example.com"
  }
}

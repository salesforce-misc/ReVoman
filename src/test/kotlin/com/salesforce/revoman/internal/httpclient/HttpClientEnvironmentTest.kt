/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.httpclient

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HttpClientEnvironmentTest {
  @Test
  fun `merges env files with dynamic overrides`() {
    val envJson =
      """
      {
        "dev": {
          "baseUrl": "https://example.org",
          "limit": 3
        }
      }
      """
        .trimIndent()
    val merged =
      HttpClientEnvironment.mergeEnvs(
        emptySet(),
        listOf(envJson.byteInputStream()),
        mapOf("limit" to 5),
      )
    merged["baseUrl"] shouldBe "https://example.org"
    merged["limit"] shouldBe 5
  }
}

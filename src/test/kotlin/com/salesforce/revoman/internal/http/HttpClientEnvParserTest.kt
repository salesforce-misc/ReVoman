/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HttpClientEnvParserTest {

  @Test
  fun `parse environment with named env selection`() {
    val envJson =
      """
      {
        "dev": {
          "baseUrl": "https://dev.example.com",
          "token": "dev-token"
        },
        "prod": {
          "baseUrl": "https://prod.example.com",
          "token": "prod-token"
        }
      }
      """
        .trimIndent()

    val env = HttpClientEnvParser.parseEnv(envJson, "prod")
    env shouldContainExactly mapOf("baseUrl" to "https://prod.example.com", "token" to "prod-token")
  }

  @Test
  fun `parse environment with default first env`() {
    val envJson =
      """
      {
        "dev": {
          "baseUrl": "https://dev.example.com"
        },
        "prod": {
          "baseUrl": "https://prod.example.com"
        }
      }
      """
        .trimIndent()

    val env = HttpClientEnvParser.parseEnv(envJson)
    env["baseUrl"] shouldBe "https://dev.example.com"
  }

  @Test
  fun `parse environment with missing env name`() {
    val envJson =
      """
      {
        "dev": {
          "baseUrl": "https://dev.example.com"
        }
      }
      """
        .trimIndent()

    val env = HttpClientEnvParser.parseEnv(envJson, "nonexistent")
    env.shouldBeEmpty()
  }

  @Test
  fun `parse empty environment file`() {
    val env = HttpClientEnvParser.parseEnv("{}")
    env.shouldBeEmpty()
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.mock

import com.google.common.truth.Truth.assertThat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MockCpqServerTest {
  private lateinit var server: MockCpqServer
  private var port: Int = 0
  private val http: HttpClient = HttpClient.newHttpClient()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    port = server.start()
  }

  @AfterEach fun tearDown() = server.stop()

  private fun post(path: String, json: String): Pair<Int, String> {
    val resp =
      http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build(),
        BodyHandlers.ofString(),
      )
    return resp.statusCode() to resp.body()
  }

  @Test
  fun `configure then price then quote chain succeeds`() {
    val (cfgStatus, cfgBody) = post("/configure", """{"productCode":"SKU-1","quantity":2}""")
    assertThat(cfgStatus).isEqualTo(200)
    assertThat(cfgBody).contains("configId")

    val configId = Regex(""""configId"\s*:\s*"([^"]+)"""").find(cfgBody)!!.groupValues[1]
    val (prcStatus, prcBody) = post("/price", """{"configId":"$configId"}""")
    assertThat(prcStatus).isEqualTo(200)
    assertThat(prcBody).contains("priceId")
    assertThat(prcBody).contains("total")

    val priceId = Regex(""""priceId"\s*:\s*"([^"]+)"""").find(prcBody)!!.groupValues[1]
    val (qotStatus, qotBody) = post("/quote", """{"priceId":"$priceId"}""")
    assertThat(qotStatus).isEqualTo(200)
    assertThat(qotBody).contains("quoteId")
    assertThat(qotBody).contains("DRAFT")
  }

  @Test
  fun `price with unknown configId is rejected`() {
    val (status, body) = post("/price", """{"configId":"nope"}""")
    assertThat(status).isEqualTo(400)
    assertThat(body).contains("error")
  }
}

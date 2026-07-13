/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.step
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RunbookExeE2ETest {
  private val collection = "pm-templates/v3/cf-ledger-jump"

  private fun kick(seed: Map<String, Any?> = emptyMap()) =
    Kick.configure()
      .templatePath(collection)
      .dynamicEnvironment("baseUrl", baseUrl)
      .let { seed.entries.fold(it) { k, (key, value) -> k.dynamicEnvironment(key, value) } }
      .insecureHttp(true)
      .off()

  @Test
  fun `runbook threads env across steps like the multi-kick fold`() {
    val rr =
      ReVoman.revUp(
        Runbook("thread") {
          step {
            intent = "seed"
            phase = Phase.SETUP
            kick = kick(mapOf("count" to 42))
          }
          step {
            intent = "use"
            phase = Phase.ACT
            kick = kick()
          }
        }
      )
    assertThat(rr).hasSize(2)
    assertThat(rr[1].mutableEnv["count"]).isEqualTo(42)
    assertThat(rr.stepFor("use")).isNotNull()
  }

  @Test
  fun `consumes breach halts at the step`() {
    val ex =
      assertThrows<AssertionError> {
        ReVoman.revUp(
          Runbook {
            step {
              intent = "needs token"
              phase = Phase.ACT
              kick = kick()
              consumes("authToken")
            }
          }
        )
      }
    assertThat(ex).hasMessageThat().contains("authToken")
  }

  @Test
  fun `produces value mismatch halts at the step`() {
    val ex =
      assertThrows<AssertionError> {
        ReVoman.revUp(
          Runbook {
            step {
              intent = "wrong value"
              phase = Phase.ACT
              kick = kick(mapOf("count" to 42))
              produces("count" to "999")
            }
          }
        )
      }
    assertThat(ex).hasMessageThat().contains("count")
  }

  @Test
  fun `assertAfter runs and can pass`() {
    val rr =
      ReVoman.revUp(
        Runbook {
          step {
            intent = "assert"
            phase = Phase.ACT
            kick = kick(mapOf("count" to 42))
            assertAfter { _, env -> assertThat(env["count"]).isEqualTo(42) }
          }
        }
      )
    assertThat(rr).hasSize(1)
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll @JvmStatic fun stopServer() = server.stop(0)
  }
}

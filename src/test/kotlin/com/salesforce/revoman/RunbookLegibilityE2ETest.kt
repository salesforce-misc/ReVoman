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
import com.salesforce.revoman.output.log.ConsoleRunLogSink
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RunbookLegibilityE2ETest {
  private fun kick(seed: Map<String, Any?> = emptyMap()) =
    Kick.configure()
      .templatePath("pm-templates/v3/cf-ledger-jump")
      .dynamicEnvironment("baseUrl", baseUrl)
      .let { seed.entries.fold(it) { k, (key, value) -> k.dynamicEnvironment(key, value) } }
      .runLogSink(ConsoleRunLogSink.DEFAULT)
      .insecureHttp(true)
      .off()

  @Test
  fun `a runbook reads as a story, guards its handoffs, and renders a view`() {
    val rundown =
      ReVoman.revUp(
        Runbook("legibility demo") {
          step {
            intent = "seed session token"
            phase = Phase.SETUP
            kick = kick(mapOf("authToken" to "tok-123"))
            produces("authToken")
          }
          step {
            intent = "act under test"
            phase = Phase.ACT
            kick = kick(mapOf("count" to 7))
            underTest()
            consumes("authToken")
            produces("count" to "7")
            assertAfter { _, env -> assertThat(env["count"]).isEqualTo(7) }
          }
        }
      )

    // Executes and threads env across steps.
    assertThat(rundown).hasSize(2)
    assertThat(rundown[1].mutableEnv["authToken"]).isEqualTo("tok-123")
    // Step pairing accessible by intent.
    assertThat(rundown.stepFor("act under test")).isNotNull()
    // Generated view surfaces the story.
    val md = rundown.toMarkdown()
    assertThat(md).contains("seed session token")
    assertThat(md).contains("act under test")
    assertThat(md).contains("count=7")
    assertThat(rundown.toMermaid()).startsWith("sequenceDiagram")
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

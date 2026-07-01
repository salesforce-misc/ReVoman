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
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * E2E proving the multi-kick [ReVoman.revUp] fold threads the FULL environment — values of every
 * type, not just [String] — from one kick into the next. Network-free: a loopback [HttpServer]
 * answers the `{{baseUrl}}` steps so the run completes without real I/O. The env values under test
 * are seeded via `dynamicEnvironment`, so they land in `rundown.mutableEnv` regardless of step
 * outcome; the assertions are purely about what kick N+1 inherits from kick N.
 */
class MultiKickEnvTypesE2ETest {
  private val collection = "pm-templates/v3/cf-ledger-jump"

  private fun kick(seed: Map<String, Any?> = emptyMap()) =
    Kick.configure()
      .templatePath(collection)
      .dynamicEnvironment("baseUrl", baseUrl)
      .let { seed.entries.fold(it) { k, (key, value) -> k.dynamicEnvironment(key, value) } }
      .insecureHttp(true)
      .off()

  @Test
  fun `non-String env value produced by a kick is inherited typed by the next kick`() {
    // kick 1 seeds a non-String value (Int) into its own dynamicEnvironment; kick 2 has no seed.
    // The fold must carry `count` into kick 2's env AS AN Int — not stringified, not dropped.
    val rundowns = ReVoman.revUp(listOf(kick(mapOf("count" to 42)), kick()))

    assertThat(rundowns).hasSize(2)
    // Regression guard: kick 1 itself sees the typed value (baseline the fold must preserve).
    assertThat(rundowns[0].mutableEnv["count"]).isEqualTo(42)
    // The fix under test: kick 2 inherits the SAME Int, not a "42" String and not null.
    assertThat(rundowns[1].mutableEnv["count"]).isEqualTo(42)
    assertThat(rundowns[1].mutableEnv["count"]).isInstanceOf(Integer::class.java)
  }

  @Test
  fun `String env value still threads across kicks`() {
    val rundowns = ReVoman.revUp(listOf(kick(mapOf("token" to "abc")), kick()))

    assertThat(rundowns).hasSize(2)
    assertThat(rundowns[1].mutableEnv["token"]).isEqualTo("abc")
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

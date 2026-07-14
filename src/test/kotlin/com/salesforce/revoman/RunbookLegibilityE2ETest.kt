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
import com.salesforce.revoman.input.config.runLogSink
import com.salesforce.revoman.input.config.step
import com.salesforce.revoman.output.log.ConsoleRunLogSink
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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

  @Test
  fun `runbook-scope sink captures coarse events and nests child kick events`() {
    // REGRESSION: prior to the stacking RunLogContext fix, coarse runbook events (PhaseEntered,
    // RunbookStepStarted/Finished, RunbookContractFailed) were dropped because the executor emitted
    // them BETWEEN revUp(kick) calls, when RunLogContext.current() was null. This test installs a
    // sink at RUNBOOK scope (NOT at kick scope) and asserts the captured output contains, in order,
    // a phase rule, a step-open bracket, at least one child request gutter line, and a step-close
    // bracket. The fix: RunLogContext.install() now stacks via restore(), and executeRunbook()
    // installs the runbook sink around the whole loop + threads it into each kick so child events
    // nest coherently.
    val capturedOut = ByteArrayOutputStream()
    val capturingSink = ConsoleRunLogSink(PrintStream(capturedOut))

    val rundown =
      ReVoman.revUp(
        Runbook("grouped log demo") {
          runLogSink = capturingSink
          step {
            intent = "first step"
            phase = Phase.SETUP
            kick = kick()
          }
          step {
            intent = "second step"
            phase = Phase.ACT
            kick = kick()
          }
        }
      )

    assertThat(rundown).hasSize(2)
    val output = capturedOut.toString(Charsets.UTF_8)

    // Assert coarse events + nested child events appear in order.
    assertThat(output).contains("━━ SETUP") // phase rule
    assertThat(output).containsMatch("┌ [▶◆]") // step-open bracket (▶ or ◆)
    assertThat(output).containsMatch("│ [·\\s]") // child request gutter line (│ · or │   )
    assertThat(output).containsMatch("└ [✔✘]") // step-close bracket (✔ or ✘)
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

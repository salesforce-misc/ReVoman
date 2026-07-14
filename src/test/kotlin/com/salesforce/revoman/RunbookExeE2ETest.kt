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
import com.salesforce.revoman.input.config.haltOnStepFailure
import com.salesforce.revoman.input.config.runLogSink
import com.salesforce.revoman.input.config.step
import com.salesforce.revoman.output.log.ConsoleRunLogSink
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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

  /** A one-step collection with a hardcoded URL — [name] selects the loopback handler. */
  private fun soloKick(name: String) =
    Kick.configure()
      .templatePath("pm-templates/v3/$name")
      .dynamicEnvironment("baseUrl", baseUrl)
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

  @Test
  fun `assertAfter throw halts and the downstream step never runs`() {
    countHits.set(0)
    val ex =
      assertThrows<AssertionError> {
        ReVoman.revUp(
          Runbook {
            step {
              intent = "step 1 asserts and blows up"
              phase = Phase.ACT
              kick = soloKick("single-ok")
              assertAfter { _, _ -> throw AssertionError("boom") }
            }
            step {
              intent = "step 2 should never run"
              phase = Phase.ACT
              kick = soloKick("single-count")
            }
          }
        )
      }
    assertThat(ex).hasMessageThat().contains("boom")
    // Downstream step's collection was never dispatched (I1: halt aborts the fold).
    assertThat(countHits.get()).isEqualTo(0)
  }

  @Test
  fun `underlying step failure halts by default and the downstream step never runs`() {
    countHits.set(0)
    val ex =
      assertThrows<AssertionError> {
        ReVoman.revUp(
          Runbook {
            step {
              intent = "hits a 500"
              phase = Phase.ACT
              kick = soloKick("single-fail")
            }
            step {
              intent = "downstream must not run"
              phase = Phase.ACT
              kick = soloKick("single-count")
            }
          }
        )
      }
    assertThat(ex).hasMessageThat().contains("hits a 500")
    assertThat(ex).hasMessageThat().contains("unsuccessful")
    assertThat(countHits.get()).isEqualTo(0)
  }

  @Test
  fun `underlying step failure with haltOnStepFailure false continues and marks the step FAILED`() {
    countHits.set(0)
    val capturedOut = ByteArrayOutputStream()
    val capturingSink = ConsoleRunLogSink(PrintStream(capturedOut))
    val rr =
      ReVoman.revUp(
        Runbook("resilient") {
          runLogSink = capturingSink
          haltOnStepFailure = false
          step {
            intent = "hits a 500 but we carry on"
            phase = Phase.ACT
            kick = soloKick("single-fail")
          }
          step {
            intent = "downstream still runs"
            phase = Phase.ACT
            kick = soloKick("single-count")
          }
        }
      )
    // Did NOT throw; both steps are captured and the downstream step actually ran.
    assertThat(rr).hasSize(2)
    assertThat(countHits.get()).isEqualTo(1)
    val output = capturedOut.toString(Charsets.UTF_8)
    // The failing step closed with a FAILED bracket (✘), not SUCCESS.
    assertThat(output).containsMatch("└ ✘ hits a 500 but we carry on")
  }

  @Test
  fun `produces mismatch emits RunbookContractFailed and a FAILED close through the sink`() {
    val capturedOut = ByteArrayOutputStream()
    val capturingSink = ConsoleRunLogSink(PrintStream(capturedOut))
    assertThrows<AssertionError> {
      ReVoman.revUp(
        Runbook("contract") {
          runLogSink = capturingSink
          step {
            intent = "produces wrong value"
            phase = Phase.ACT
            kick = kick(mapOf("count" to 42))
            produces("count" to "999")
          }
        }
      )
    }
    val output = capturedOut.toString(Charsets.UTF_8)
    // The CONTRACT detail line surfaced the mismatch AND the step closed FAILED (FIX 2).
    assertThat(output).contains("⚠ CONTRACT")
    assertThat(output).contains("value mismatch")
    assertThat(output).containsMatch("└ ✘ produces wrong value")
  }

  @Test
  fun `consecutive same-phase steps emit exactly one phase rule`() {
    val capturedOut = ByteArrayOutputStream()
    val capturingSink = ConsoleRunLogSink(PrintStream(capturedOut))
    ReVoman.revUp(
      Runbook("phase dedup") {
        runLogSink = capturingSink
        step {
          intent = "setup a"
          phase = Phase.SETUP
          kick = kick()
        }
        step {
          intent = "setup b"
          phase = Phase.SETUP
          kick = kick()
        }
        step {
          intent = "act c"
          phase = Phase.ACT
          kick = kick()
        }
      }
    )
    val output = capturedOut.toString(Charsets.UTF_8)
    assertThat(output.split("━━ SETUP").size - 1).isEqualTo(1)
    assertThat(output.split("━━ ACT").size - 1).isEqualTo(1)
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val countHits = AtomicInteger(0)

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.createContext("/fail") { exchange ->
        val body = "{\"error\":\"boom\"}".toByteArray()
        exchange.sendResponseHeaders(500, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.createContext("/count") { exchange ->
        countHits.incrementAndGet()
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll @JvmStatic fun stopServer() = server.stop(0)
  }

  @BeforeEach
  fun resetCounter() {
    countHits.set(0)
  }
}

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
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerSnapshot
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * E2E for control-flow ledger behavior: proves a conditional jump disables the ledger warm-path
 * from the divergence point onward.
 *
 * Network-free by design: a JDK [HttpServer] bound to loopback detects whether the warm run
 * dispatches real HTTP or skips via ledger. The cold run learns the real step hashes and paths. The
 * warm run is constructed with a ledger snapshot that could skip BOTH steps (p1 is in the linear
 * prefix before the jump; p3 is the jump target). The proof: p1 remains ledger-skipped
 * (pre-divergence), while p3 dispatches FRESH via HTTP despite a matching ledger entry (the jump
 * target resets the warm-path from that point onward). This structurally proves control-flow
 * overrides the ledger's determinism at the divergence.
 */
class ControlFlowLedgerE2ETest {
  private val collection = "pm-templates/v3/cf-ledger-jump"

  private fun kick(snap: LedgerSnapshot? = null) =
    Kick.configure()
      .templatePath(collection)
      .dynamicEnvironment("baseUrl", baseUrl)
      .insecureHttp(true)
      .let { if (snap != null) it.ledger(snap) else it }
      .off()

  @Test
  fun `linear prefix still ledger-skips but post-jump steps dispatch fresh`() {
    // Cold run to learn real paths + hashes.
    val cold = ReVoman.revUp(kick())
    val p1 = cold.stepReports.first { it.step.name == "p1" }.step
    val p3 = cold.reportsForStepName("p3").last().step

    // Build a ledger that COULD skip both p1 and p3.
    val snap =
      LedgerSnapshot(
        orgId = null,
        steps =
          mapOf(
            p1.path to LedgerEntry(setOf("p1key"), p1.sourceHash),
            p3.path to LedgerEntry(setOf("p3key"), p3.sourceHash),
          ),
        values = mapOf("p1key" to "P1V", "p3key" to "P3V"),
      )

    val p1Before = hits["/p1"]?.get() ?: 0
    val p3Before = hits["/p3"]?.get() ?: 0
    val warm = ReVoman.revUp(kick(snap))

    // p1 is BEFORE the jump => ledger-skipped (no HTTP).
    assertThat(hits["/p1"]?.get() ?: 0).isEqualTo(p1Before)
    assertThat(warm.reportForStepName("p1")!!.isLedgerSkipped).isTrue()

    // p3 is the jump TARGET (control diverged) => dispatched fresh despite a matching entry.
    assertThat(warm.reportForStepName("p3")!!.isLedgerSkipped).isFalse()
    assertThat(warm.reportForStepName("p3")!!.responseInfo).isNotNull()
    assertThat(hits["/p3"]?.get() ?: 0).isGreaterThan(p3Before)
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        hits.computeIfAbsent(exchange.requestURI.path) { AtomicInteger(0) }.incrementAndGet()
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

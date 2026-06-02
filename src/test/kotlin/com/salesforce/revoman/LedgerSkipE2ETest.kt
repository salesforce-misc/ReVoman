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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * E2E for the warm-path centerpiece in [ReVoman.executeStepsSerially]: ledger-skip + inject, and
 * [com.salesforce.revoman.output.Rundown.learnedLedger] emission.
 *
 * Network-free by design: a JDK [HttpServer] is bound to loopback on an ephemeral port in
 * [BeforeAll] and torn down in [AfterAll] — no internet dependency, so this runs in an isolated CI
 * sandbox. The collection URL is templated `{{baseUrl}}/...` and resolved against `baseUrl`
 * injected via `dynamicEnvironment`. The single-step fixture `pm-templates/v3/ledger-skip` is a
 * plain GET that PRODUCES nothing on its own (the loopback server just returns 200) — which is what
 * lets the warm run prove the skip: a hash-matching, produced-keys-present ledger entry makes that
 * one step's HTTP dispatch get SKIPPED, so the warm run makes ZERO requests to the server.
 */
class LedgerSkipE2ETest {
  private val collection = "pm-templates/v3/ledger-skip"

  private fun kick(snap: LedgerSnapshot? = null, vararg env: Pair<String, Any?>): Kick {
    var builder =
      Kick.configure()
        .templatePath(collection)
        .dynamicEnvironment("baseUrl", baseUrl)
        .insecureHttp(true)
    env.forEach { (k, v) -> builder = builder.dynamicEnvironment(k, v) }
    if (snap != null) builder = builder.ledger(snap)
    return builder.off()
  }

  @Test
  fun `cold run hits the loopback server and emits learnedLedger only from producing steps`() {
    val hitsBefore = serverHits.get()
    val rundown = ReVoman.revUp(kick())
    // Cold run dispatched real (loopback) HTTP for the one step.
    assertThat(serverHits.get()).isEqualTo(hitsBefore + 1)
    // The step calls no pm.environment.set, so nothing is produced -> empty learnedLedger.
    // Proves the extraction filters to producing steps only (no spurious entries).
    assertThat(rundown.learnedLedger).isEmpty()
    assertThat(rundown.stepReports).hasSize(1)
  }

  @Test
  fun `warm run with matching ledger skips the producing step and injects the ledgered value`() {
    // Cold run to learn the real step path + sourceHash (v3 loader computes a real sha256).
    val cold = ReVoman.revUp(kick())
    val firstStep = cold.stepReports.first().step
    val stepPath = firstStep.path
    val hash = firstStep.sourceHash
    assertThat(hash).isNotEmpty()

    val producedKey = "ledgeredKey"
    val snap =
      LedgerSnapshot(
        orgId = null,
        steps = mapOf(stepPath to LedgerEntry(setOf(producedKey), hash)),
        values = mapOf(producedKey to "LEDGERED_VALUE"),
      )

    // The skip predicate requires the produced keys to ALREADY be in env (env-superset). In the
    // real warm flow `revUp` seeds the ledger snapshot's `values` into the env up front (as the
    // lowest-precedence floor), which is what satisfies that precondition — no manual pre-seed. We
    // pass ONLY the snapshot and assert the skip branch injected the authoritative ledgered value
    // (proving the seeding + inject ran, not the HTTP-producing step).
    val hitsBefore = serverHits.get()
    val warm = ReVoman.revUp(kick(snap)) // NO producedKey pre-seed — ledger.values must seed it

    // The single step was skipped -> ZERO requests reached the loopback server. This structurally
    // proves "skipped, not run", independent of the report shape.
    assertThat(serverHits.get()).isEqualTo(hitsBefore)
    // Injected value survives (overwrote the placeholder) so downstream steps could resolve it.
    assertThat(warm.mutableEnv.getAsString(producedKey)).isEqualTo("LEDGERED_VALUE")

    // The skipped step is RECORDED (not absent) in the report list, and its SHAPE proves no HTTP
    // ran: a ledgerSkipped report carries neither a requestInfo nor a responseInfo.
    val skipped = warm.reportForStepName(stepPath)!!
    assertThat(skipped.requestInfo).isNull()
    assertThat(skipped.responseInfo).isNull()
    assertThat(warm.stepReports).hasSize(1)

    // The warm run re-emits the skipped step's entry into learnedLedger: the reused produced keys
    // carried forward against the step's CURRENT sourceHash (so the ledger can be refreshed).
    assertThat(warm.learnedLedger).containsExactly(stepPath, LedgerEntry(setOf(producedKey), hash))
  }

  companion object {
    private lateinit var server: HttpServer
    private val serverHits = AtomicInteger(0)
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        serverHits.incrementAndGet()
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll
    @JvmStatic
    fun stopServer() {
      server.stop(0)
    }
  }
}

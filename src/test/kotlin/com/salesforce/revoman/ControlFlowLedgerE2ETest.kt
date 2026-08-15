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
import com.salesforce.revoman.testing.http.MockHttpServer
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * E2E for control-flow ledger behavior: proves a conditional jump disables the ledger warm-path
 * from the divergence point onward.
 *
 * External-network-free by design: a shared [MockHttpServer] bound to loopback detects whether the warm
 * run dispatches real HTTP or skips via ledger. The cold run learns the real step hashes and paths.
 * The warm run is constructed with a ledger snapshot that could skip BOTH steps (p1 is in the
 * linear prefix before the jump; p3 is the jump target). The proof: p1 remains ledger-skipped
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

    val p1Before = fixture.requests().count { it.path == "/p1" }
    val p3Before = fixture.requests().count { it.path == "/p3" }
    val warm = ReVoman.revUp(kick(snap))

    // p1 is BEFORE the jump => ledger-skipped (no HTTP).
    assertThat(fixture.requests().count { it.path == "/p1" }).isEqualTo(p1Before)
    assertThat(warm.reportForStepName("p1")!!.isLedgerSkipped).isTrue()

    // p3 is the jump TARGET (control diverged) => dispatched fresh despite a matching entry.
    assertThat(warm.reportForStepName("p3")!!.isLedgerSkipped).isFalse()
    assertThat(warm.reportForStepName("p3")!!.responseInfo).isNotNull()
    assertThat(fixture.requests().count { it.path == "/p3" }).isGreaterThan(p3Before)
  }

  @Test
  fun `unresolved jump keeps ledger warm-path for subsequent steps`() {
    // Collection cf-unresolved: step a jumps to 'does-not-exist' (unresolved), then b runs.
    val unresolvedKick =
      Kick.configure()
        .templatePath("pm-templates/v3/cf-unresolved")
        .dynamicEnvironment("baseUrl", baseUrl)
        .insecureHttp(true)

    // Cold run to learn real paths + hashes.
    val cold = ReVoman.revUp(unresolvedKick.off())
    val aStep = cold.reportForStepName("a")!!.step
    val bStep = cold.reportForStepName("b")!!.step

    // Build a ledger that COULD skip both a and b.
    val snap =
      LedgerSnapshot(
        orgId = null,
        steps =
          mapOf(
            aStep.path to LedgerEntry(setOf("akey"), aStep.sourceHash),
            bStep.path to LedgerEntry(setOf("bkey"), bStep.sourceHash),
          ),
        values = mapOf("akey" to "AV", "bkey" to "BV"),
      )

    val aBefore = fixture.requests().count { it.path == "/a" }
    val bBefore = fixture.requests().count { it.path == "/b" }
    val warm = ReVoman.revUp(unresolvedKick.ledger(snap).off())

    // a is ledger-skipped (matching entry).
    assertThat(fixture.requests().count { it.path == "/a" }).isEqualTo(aBefore)
    assertThat(warm.reportForStepName("a")!!.isLedgerSkipped).isTrue()

    // The jump in a is UNRESOLVED => linear continue to b WITHOUT latching bypassLedger.
    // b MUST remain ledger-skipped (the warm-path was NOT disabled).
    assertThat(fixture.requests().count { it.path == "/b" }).isEqualTo(bBefore)
    assertThat(warm.reportForStepName("b")!!.isLedgerSkipped).isTrue()
  }

  companion object {
    private lateinit var fixture: MockHttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      fixture = MockHttpServer.start { Response(OK).body("{}") }
      baseUrl = fixture.baseUrl
    }

    @AfterAll @JvmStatic fun stopServer() = fixture.close()
  }
}

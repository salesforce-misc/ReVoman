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

  @Test
  fun `warm skip re-emits the reused entry's consumed (provenance survives warm runs)`() {
    // Cold run to learn the real step path + sourceHash.
    val cold = ReVoman.revUp(kick())
    val firstStep = cold.stepReports.first().step
    val stepPath = firstStep.path
    val hash = firstStep.sourceHash

    val producedKey = "ledgeredKey"
    // The ledgered entry carries CONSUMED provenance (as a cold run would have recorded).
    val snap =
      LedgerSnapshot(
        orgId = null,
        steps =
          mapOf(stepPath to LedgerEntry(setOf(producedKey), hash, consumed = setOf("seedKey"))),
        values = mapOf(producedKey to "LEDGERED_VALUE"),
      )
    val warm = ReVoman.revUp(kick(snap))
    // Skipped step re-emits the entry; consumed must NOT be silently dropped, else a warm-run
    // LedgerStore.merge (last-write-wins putAll) would erase the provenance on every loop.
    assertThat(warm.learnedLedger[stepPath]!!.consumed).containsExactly("seedKey")
  }

  @Test
  fun `a var set in a PostStepHook attributes to the triggering step's produces`() {
    // The user's design position: a var set inside a step-qualified hook belongs to that step's
    // ledger entry. PostStepHook fires INSIDE the step fold (currentStep still the triggering
    // step), so a hook .set() must be captured as produced BY that step.
    val kick =
      Kick.configure()
        .templatePath(collection) // single GET step, produces nothing on its own
        .dynamicEnvironment("baseUrl", baseUrl)
        .insecureHttp(true)
        .hooks(
          com.salesforce.revoman.input.config.HookConfig.post(
            com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName("produce-id")
          ) { stepReport, rundown ->
            rundown.mutableEnv.set("hookProducedId", "H1")
          }
        )
        .off()
    val rundown = ReVoman.revUp(kick)
    val stepPath = rundown.stepReports.first().step.path
    assertThat(rundown.learnedLedger[stepPath]!!.produces).contains("hookProducedId")
  }

  @Test
  fun `a var set in a collection-level PostExeHook does NOT leak into any step's learned ledger`() {
    // The collection-level PostExeHook fires in the OUTER kick-fold (ReVoman.kt:85), AFTER each
    // kick's learnedLedger is already frozen (:151). So a var it sets cannot retroactively attach
    // to any step's ledger entry. This LOCKS that a PostExeHook write is excluded from the
    // persisted ledger (not mis-attributed to a stale step) — the safe outcome.
    val kick =
      Kick.configure()
        .templatePath(collection)
        .dynamicEnvironment("baseUrl", baseUrl)
        .insecureHttp(true)
        .off()
    val rundowns =
      ReVoman.revUp(
        listOf(kick),
        { currentRundown, _ -> currentRundown.mutableEnv.set("postExeHookId", "PEH1") },
      )
    val learned = rundowns.single().learnedLedger
    // No step entry claims to produce the PostExeHook-set key.
    assertThat(learned.values.flatMap { it.produces }).doesNotContain("postExeHookId")
  }

  @Test
  fun `a PostStepHook index-set write is NOT captured as produced (only set() is)`() {
    // Capture contract: only pm.environment.set(...) records a produced key. The delegated
    // index-set (mutableEnv[k]=) is intentionally bypassed — that is the SAME bypass the warm-skip
    // inject relies on (ReVoman.kt:187 injects via index-set so reused keys are not re-recorded as
    // produced). A hook that writes via index-set is therefore invisible to the ledger BY DESIGN;
    // hook producers must use .set() to be ledgered.
    val kick =
      Kick.configure()
        .templatePath(collection)
        .dynamicEnvironment("baseUrl", baseUrl)
        .insecureHttp(true)
        .hooks(
          com.salesforce.revoman.input.config.HookConfig.post(
            com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName("produce-id")
          ) { _, rundown ->
            rundown.mutableEnv["indexSetId"] = "IDX1" // index-set, NOT .set()
          }
        )
        .off()
    val rundown = ReVoman.revUp(kick)
    // The key IS in the env (the write happened)...
    assertThat(rundown.mutableEnv.getAsString("indexSetId")).isEqualTo("IDX1")
    // ...but no step claims to PRODUCE it (index-set is not captured).
    assertThat(rundown.learnedLedger.values.flatMap { it.produces }).doesNotContain("indexSetId")
  }

  @Test
  fun `learnedLedger entry carries the keys a producing step consumed (provenance)`() {
    // A step that reads {{seedKey}} (consumed) in its URL AND sets producedId (produced). The
    // learned entry must record BOTH: produces for skip, consumed for the provenance graph.
    val kick =
      Kick.configure()
        .templatePath("pm-templates/v3/ledger-consume")
        .dynamicEnvironment("baseUrl", baseUrl)
        .dynamicEnvironment("seedKey", "SEED")
        .insecureHttp(true)
        .off()
    val rundown = ReVoman.revUp(kick)
    val stepPath = rundown.stepReports.first().step.path
    val entry = rundown.learnedLedger[stepPath]!!
    assertThat(entry.produces).containsExactly("producedId")
    assertThat(entry.consumed).contains("seedKey")
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

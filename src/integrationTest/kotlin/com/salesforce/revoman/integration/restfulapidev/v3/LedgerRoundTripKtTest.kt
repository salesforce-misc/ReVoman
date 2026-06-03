/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.restfulapidev.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerSnapshot
import org.junit.jupiter.api.Test

/**
 * End-to-end validation of the ledger warm-skip feature against the REAL public restful-api.dev API
 * (no auth, free). Companion to the network-free [com.salesforce.revoman.LedgerSkipE2ETest] (which
 * uses a JDK loopback server): this one proves the same cold→warm→skip cycle survives a real HTTP
 * round-trip and a real V3-loaded `sourceHash`.
 *
 * Uses the **V3** collection `pm-templates/v3/restful-api.dev` on purpose: `Step.sourceHash` is
 * computed only at V3 load (`V3Loader` → sha256 of each `.request.yaml`), so V3 steps carry a real,
 * non-empty hash. The ledger-skip predicate
 * ([com.salesforce.revoman.internal.exe.ledgerSkipDecision]) gates on non-empty hashes on BOTH
 * sides, so only a V3 collection can demonstrate a genuine end-to-end skip. (The V2 JSON variant's
 * steps would have `sourceHash == ""`, which can never match → no skip.)
 *
 * Producer step: `add-object` (POST /objects) whose `afterResponse` script does
 * `pm.environment.set("objId"/"productName"/"data", ...)`. Downstream `update-object` (PATCH) and
 * `get-object-by-id` (GET) consume `{{objId}}`.
 */
class LedgerRoundTripKtTest {

  /**
   * Cold run = LEARN: a real POST to restful-api.dev produces keys → they land in learnedLedger.
   */
  @Test
  fun `cold run learns producer keys from a real API call`() {
    val cold = revUp()
    // The real API succeeded for all 4 steps (no skip yet — EMPTY ledger).
    assertThat(cold.firstUnsuccessfulStepReport).isNull()
    assertThat(cold.stepReports).hasSize(4)

    // learnedLedger is keyed on Step.path; the add-object entry carries the produced keys.
    val producerEntry = cold.stepReports.first { it.envVars.produced.contains("objId") }
    val learnedForProducer = cold.learnedLedger[producerEntry.step.path]
    assertThat(learnedForProducer).isNotNull()
    assertThat(learnedForProducer!!.produces).containsAtLeast("objId", "productName")
    // V3 load computed a real, non-empty sourceHash — the precondition for warm-skip to ever fire.
    assertThat(learnedForProducer.hash).isNotEmpty()
    assertThat(learnedForProducer.hash).isEqualTo(producerEntry.step.sourceHash)
  }

  /**
   * Warm run = SKIP + INJECT: with a ledger entry matching the producer's real path + sourceHash
   * and its produced keys, the producer step's HTTP dispatch is SKIPPED and the ledgered values are
   * injected into the env instead.
   *
   * HONEST CAVEAT against a real API: because `add-object` is skipped, `objId` comes from the
   * ledger (a fixed, non-server id). The downstream PATCH/GET then call the REAL
   * api.restful-api.dev with that ledgered id, which legitimately 404s (no such server-side
   * object). That's fine — it does NOT undermine what we're validating (the PRODUCER step was
   * skipped, no HTTP, value injected). So we deliberately do NOT assert
   * `firstUnsuccessfulStepReport == null` on the warm run; we assert only the producer-step skip
   * properties.
   */
  @Test
  fun `warm run with ledger skips the producer step yet injects the ledgered value`() {
    // 1. Cold run to obtain the producer step's REAL path + sourceHash (no hand-crafting).
    val cold = revUp()
    val producer = cold.stepReports.first { it.envVars.produced.contains("objId") }
    val stepPath = producer.step.path
    val hash = producer.step.sourceHash
    // V3 → real sha256. (If this were ever empty, the skip predicate would refuse to fire.)
    assertThat(hash).isNotEmpty()

    // 2. Warm run: ledger says objId/productName already produced (with the REAL hash). The library
    // seeds `ledger.values` into the env as the lowest-precedence floor, which satisfies the
    // skip predicate's env-superset precondition — we do NOT pre-seed manually.
    val ledgeredId = "ledgered-obj-id"
    val snap =
      LedgerSnapshot(
        orgId = null,
        steps = mapOf(stepPath to LedgerEntry(setOf("objId", "productName"), hash)),
        values = mapOf("objId" to ledgeredId, "productName" to "Ledgered Widget"),
      )
    val warm = revUp(snap)

    // The producer step was SKIPPED: recorded (not absent) but with NO HTTP req/resp info.
    val skipped = warm.reportForStepName(stepPath)!!
    assertThat(skipped.requestInfo).isNull()
    assertThat(skipped.responseInfo).isNull()
    assertThat(skipped.envVars.produced).containsAtLeast("objId", "productName")

    // The ledgered value was injected into the env at skip time (so downstream {{objId}} resolves
    // to it). We assert on the skipped step's pmEnvSnapshot — the env AS OF the skip+inject — NOT
    // the final live env: against the REAL API the downstream PATCH (called with the ledgered id)
    // 404s, and its `afterResponse` does pm.environment.set("objId", responseJson.id), clobbering
    // the final live `objId` with the 404 body's (null) id. The snapshot is immune to that and is
    // the authoritative proof that the inject ran.
    assertThat(skipped.pmEnvSnapshot.getAsString("objId")).isEqualTo(ledgeredId)

    // The warm run re-emits the skipped step's entry into learnedLedger against its current hash.
    assertThat(warm.learnedLedger[stepPath])
      .isEqualTo(LedgerEntry(setOf("objId", "productName"), hash))
  }

  private fun revUp(ledger: LedgerSnapshot? = null): com.salesforce.revoman.output.Rundown {
    var builder =
      Kick.configure()
        .templatePath(PM_COLLECTION_PATH)
        .environmentPath(PM_ENVIRONMENT_PATH)
        .nodeModulesPath("js")
    if (ledger != null) builder = builder.ledger(ledger)
    return ReVoman.revUp(builder.off())
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/restful-api.dev"
    private const val PM_ENVIRONMENT_PATH =
      "pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml"
  }
}

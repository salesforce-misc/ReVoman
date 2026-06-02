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
import org.junit.jupiter.api.Test

/**
 * E2E for the warm-path centerpiece in [ReVoman.executeStepsSerially]: ledger-skip + inject, and
 * [com.salesforce.revoman.output.Rundown.learnedLedger] emission.
 *
 * Fixture: `pm-templates/v3/flat` — 3 v3 steps (`a`, `b`, `c`) each a plain GET to example.com with
 * no `pm.environment.set(...)`, so none of them PRODUCE env keys. That's exactly what makes the
 * skip-path test offline: a ledgered, hash-matching, produced-keys-present entry makes the
 * producing step's HTTP dispatch get SKIPPED — no network. We bootstrap the step's
 * `path`/`sourceHash` from a cold run's step reports (the v3 loader computes a real sha256
 * fingerprint).
 */
class LedgerSkipE2ETest {
  private val collection = "pm-templates/v3/flat"

  @Test
  fun `cold run emits a learnedLedger built only from producing steps`() {
    val rundown = ReVoman.revUp(Kick.configure().templatePath(collection).insecureHttp(true).off())
    // None of the flat steps call pm.environment.set, so nothing is produced -> empty
    // learnedLedger.
    // This proves the extraction filters to producing steps only (no spurious entries).
    assertThat(rundown.learnedLedger).isEmpty()
    assertThat(rundown.stepReports).hasSize(3)
  }

  @Test
  fun `warm run with matching ledger skips the producing step and injects the ledgered value`() {
    // Cold run to learn the real step path + sourceHash (offline-safe: assertions don't need HTTP).
    val cold = ReVoman.revUp(Kick.configure().templatePath(collection).insecureHttp(true).off())
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
    // real warm flow the ledger file's `values` are imported into the postman env up front; here we
    // simulate that precondition with a placeholder, then assert the skip branch OVERWRITES it with
    // the authoritative ledgered value (proving the inject ran, not the HTTP-producing step).
    val warm =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(collection)
          .dynamicEnvironment(producedKey, "PLACEHOLDER")
          .ledger(snap)
          .insecureHttp(true)
          .off()
      )

    // Injected value survives (overwrote the placeholder) so downstream steps could resolve it.
    assertThat(warm.mutableEnv.getAsString(producedKey)).isEqualTo("LEDGERED_VALUE")
    // The skipped step is RECORDED (not absent) in the report list.
    assertThat(warm.reportForStepName(stepPath)).isNotNull()
    assertThat(warm.stepReports).hasSize(3)
    // The warm run re-emits the skipped step's entry into learnedLedger: the reused produced keys
    // carried forward against the step's CURRENT sourceHash (so the ledger can be refreshed). The
    // index-set injection did NOT add spurious produced keys for the other (HTTP-run) steps.
    assertThat(warm.learnedLedger).containsExactly(stepPath, LedgerEntry(setOf(producedKey), hash))
  }
}

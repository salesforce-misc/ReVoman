/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.output.RunbookRundown
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerSnapshot

class KotlinLedgerAndRunbookApiFixture {
  fun ledger(): LedgerSnapshot =
    LedgerSnapshot(
      "org",
      mapOf("collection/request" to LedgerEntry(setOf("token"), "hash", setOf("seed"))),
      mapOf("token" to "value"),
    )

  fun runbook(kick: Kick): Runbook =
    Runbook.configure().name("consumer").step("create", Phase.ACT, kick).off()

  fun attachLedger(builder: Kick.Builder): Kick = builder.ledger(ledger()).off()

  fun listSurface(rundown: RunbookRundown): Triple<Int, Rundown, Long> =
    Triple(rundown.size, rundown[0], rundown.stream().count())
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer;

import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.config.Phase;
import com.salesforce.revoman.input.config.Runbook;
import com.salesforce.revoman.output.RunbookRundown;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.ledger.LedgerEntry;
import com.salesforce.revoman.output.ledger.LedgerSnapshot;
import java.util.Map;
import java.util.Set;

public final class JavaLedgerAndRunbookApiFixture {
  public LedgerSnapshot ledger() {
    LedgerEntry entry = new LedgerEntry(Set.of("token"), "hash", Set.of("seed"));
    return new LedgerSnapshot("org", Map.of("collection/request", entry), Map.of("token", "value"));
  }

  public Runbook runbook(Kick kick) {
    return Runbook.configure().name("consumer").step("create", Phase.ACT, kick).off();
  }

  public Kick attachLedger(Kick.Builder builder) {
    return builder.ledger(ledger()).off();
  }

  public long listSurface(RunbookRundown rundown) {
    int size = rundown.size();
    Rundown first = rundown.get(0);
    return size + first.stepReports.size() + rundown.stream().count();
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerFile
import org.junit.jupiter.api.Test

class V3YamlLedgerTest {
  @Test
  fun `readLedger parses values and x-revoman-ledger sibling`() {
    val yaml =
      """
      name: ledger-00Dxx
      values:
        - {key: saId1, value: '08p1', enabled: true}
      x-revoman-ledger:
        orgId: 00Dxx
        steps:
          "fixtures|>sa<|||create-sa|||>":
            produces: [saId1]
            hash: abc
      """
        .trimIndent()
    val f = V3YamlReader.readLedger(yaml)
    assertThat(f.values).containsEntry("saId1", "08p1")
    assertThat(f.orgId).isEqualTo("00Dxx")
    assertThat(f.steps["fixtures|>sa<|||create-sa|||>"])
      .isEqualTo(LedgerEntry(produces = setOf("saId1"), hash = "abc"))
  }

  @Test
  fun `readLedger parses consumed list (provenance), absent consumed yields empty`() {
    val yaml =
      """
      name: ledger-00Dxx
      values:
        - {key: schedulingPolicyId, value: '0Sp1', enabled: true}
      x-revoman-ledger:
        orgId: 00Dxx
        steps:
          "policies|>create<|||create-policy|||>":
            produces: [schedulingPolicyId]
            consumed: [ruleId, resourceId]
            hash: abc
          "fixtures|>sa<|||create-sa|||>":
            produces: [saId1]
            hash: def
      """
        .trimIndent()
    val f = V3YamlReader.readLedger(yaml)
    assertThat(f.steps["policies|>create<|||create-policy|||>"]!!.consumed)
      .containsExactly("ruleId", "resourceId")
    // A step with no `consumed:` key parses to an empty set, not null.
    assertThat(f.steps["fixtures|>sa<|||create-sa|||>"]!!.consumed).isEmpty()
  }

  @Test
  fun `writer output is re-readable AND parses as a plain v3 env (values only)`() {
    val file =
      LedgerFile(
        name = "ledger-00Dxx",
        values = mapOf("saId1" to "08p1"),
        orgId = "00Dxx",
        steps =
          mapOf(
            "fixtures|>sa<|||create-sa|||>" to
              LedgerEntry(setOf("saId1"), "abc", consumed = setOf("policyId"))
          ),
      )
    val dumped = V3YamlWriter.dump(file)
    // Round-trips as a ledger (incl. consumed provenance)
    assertThat(V3YamlReader.readLedger(dumped)).isEqualTo(file)
    // And still parses as a plain postman env (sibling ignored, values intact)
    val asEnv = V3YamlReader.readEnv(dumped)
    assertThat(asEnv.values.map { it.key }).contains("saId1")
  }

  @Test
  fun `readLedger tolerates a file with no x-revoman-ledger sibling (plain env)`() {
    val yaml =
      """
      name: plain
      values:
        - {key: foo, value: bar, enabled: true}
      """
        .trimIndent()
    val f = V3YamlReader.readLedger(yaml)
    assertThat(f.values).containsEntry("foo", "bar")
    assertThat(f.orgId).isNull()
    assertThat(f.steps).isEmpty()
  }
}

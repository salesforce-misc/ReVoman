/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.ledger

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LedgerTest {
  @Test
  fun `LedgerEntry holds produces and hash`() {
    val e = LedgerEntry(produces = setOf("saId1"), hash = "abc")
    assertThat(e.produces).containsExactly("saId1")
    assertThat(e.hash).isEqualTo("abc")
  }

  @Test
  fun `empty snapshot has no steps and no values`() {
    val s = LedgerSnapshot(orgId = null, steps = emptyMap(), values = emptyMap())
    assertThat(s.steps).isEmpty()
    assertThat(s.values).isEmpty()
  }

  @Test
  fun `LedgerSnapshot EMPTY constant is empty`() {
    assertThat(LedgerSnapshot.EMPTY.steps).isEmpty()
    assertThat(LedgerSnapshot.EMPTY.values).isEmpty()
    assertThat(LedgerSnapshot.EMPTY.orgId).isNull()
  }

  @Test
  fun `LedgerFile toSnapshot copies orgId steps and values`() {
    val file =
      LedgerFile(
        name = "ledger-00Dxx",
        values = mapOf("saId1" to "08p1"),
        orgId = "00Dxx",
        steps = mapOf("fixtures|>sa<|||create-sa|||>" to LedgerEntry(setOf("saId1"), "abc")),
      )
    val snap = file.toSnapshot()
    assertThat(snap.orgId).isEqualTo("00Dxx")
    assertThat(snap.steps).containsKey("fixtures|>sa<|||create-sa|||>")
    assertThat(snap.values).containsEntry("saId1", "08p1")
  }

  @Test
  fun `LedgerFile EMPTY is empty`() {
    assertThat(LedgerFile.EMPTY.steps).isEmpty()
    assertThat(LedgerFile.EMPTY.values).isEmpty()
    assertThat(LedgerFile.EMPTY.orgId).isNull()
  }
}

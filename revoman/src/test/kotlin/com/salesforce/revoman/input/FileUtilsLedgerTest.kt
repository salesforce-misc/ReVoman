/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerFile
import java.nio.file.Files
import org.junit.jupiter.api.Test

class FileUtilsLedgerTest {
  @Test
  fun `write then read round-trips a LedgerFile via absolute path`() {
    val tmp = Files.createTempFile("ledger", ".environment.yaml").toAbsolutePath().toString()
    val file =
      LedgerFile(
        name = "ledger-00Dxx",
        values = mapOf("saId1" to "08p1"),
        orgId = "00Dxx",
        steps = mapOf("fixtures|>sa<|||create-sa|||>" to LedgerEntry(setOf("saId1"), "abc")),
      )
    writeLedgerYaml(tmp, file)
    assertThat(readLedgerYaml(tmp)).isEqualTo(file)
  }
}

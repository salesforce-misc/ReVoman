/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class StepReportSkipTest {
  @Test
  fun `requestSkipped report is successful, has no req or resp, and is flagged isRequestSkipped`() {
    val step = loadAnyStep()
    val sr = StepReport.requestSkipped(step, PostmanEnvironment(), iteration = 2)
    assertThat(sr.isSuccessful).isTrue()
    assertThat(sr.requestInfo).isNull()
    assertThat(sr.responseInfo).isNull()
    assertThat(sr.isRequestSkipped).isTrue()
    assertThat(sr.isLedgerSkipped).isFalse()
    assertThat(sr.iteration).isEqualTo(2)
  }

  @Test
  fun `ledgerSkipped report is not isRequestSkipped`() {
    val step = loadAnyStep()
    val sr = StepReport.ledgerSkipped(step, setOf("k"), PostmanEnvironment())
    assertThat(sr.isLedgerSkipped).isTrue()
    assertThat(sr.isRequestSkipped).isFalse()
  }

  private fun loadAnyStep(): Step = Step(index = "1", rawPMStep = Item(name = "test"))
}

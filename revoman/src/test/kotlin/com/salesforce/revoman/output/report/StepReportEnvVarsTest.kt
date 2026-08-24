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

class StepReportEnvVarsTest {
  @Test
  fun `envVars defaults to empty when not set`() {
    val step = Step(index = "1", rawPMStep = Item(name = "s"))
    val report =
      StepReport(step = step, pmEnvSnapshot = PostmanEnvironment(), envVars = StepEnvVars())
    assertThat(report.envVars.produced).isEmpty()
    assertThat(report.envVars.consumed).isEmpty()
  }

  @Test
  fun `envVars carries provided produced and consumed`() {
    val step = Step(index = "1", rawPMStep = Item(name = "s"))
    val report =
      StepReport(
        step = step,
        pmEnvSnapshot = PostmanEnvironment(),
        envVars = StepEnvVars(produced = setOf("saId1"), consumed = setOf("policyId")),
      )
    assertThat(report.envVars.produced).containsExactly("saId1")
    assertThat(report.envVars.consumed).containsExactly("policyId")
  }
}

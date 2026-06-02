/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StepEnvVarsTest {
  @Test
  fun `defaults to empty produced and consumed`() {
    val v = StepEnvVars()
    assertThat(v.produced).isEmpty()
    assertThat(v.consumed).isEmpty()
  }

  @Test
  fun `holds produced and consumed sets`() {
    val v = StepEnvVars(produced = setOf("saId1"), consumed = setOf("policyId"))
    assertThat(v.produced).containsExactly("saId1")
    assertThat(v.consumed).containsExactly("policyId")
  }
}

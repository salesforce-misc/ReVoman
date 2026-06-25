/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RundownStopReasonTest {
  @Test
  fun `stopReason defaults to COMPLETED`() {
    val rundown =
      Rundown(
        stepReports = emptyList(),
        mutableEnv = PostmanEnvironment(),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 0,
      )
    assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
  }
}

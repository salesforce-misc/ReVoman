/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.apigee.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class ApigeeKtTest {
  @Test
  fun testExecuteApigeeV3Collection() {
    val rundown =
      ReVoman.revUp(
        Kick.configure().templatePath("pm-templates/v3/Apigee").nodeModulesPath("js").off()
      )
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    assertThat(rundown.stepReports).hasSize(1)
    assertThat(rundown.mutableEnv["city"]).isNotNull()
  }
}

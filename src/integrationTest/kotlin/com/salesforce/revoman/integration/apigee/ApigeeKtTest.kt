/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.apigee

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class ApigeeKtTest {
  @Test
  fun `xml2js apigee`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure().templatePath(PM_COLLECTION_PATH).nodeModulesRelativePath("js").off()
      )
    assertThat(rundown.stepReports).hasSize(1)
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    assertThat(rundown.mutableEnv["city"]).isEqualTo("San Jose")
  }

  companion object {
    private const val PM_COLLECTION_PATH =
      "pm-templates/apigee/apigee.postman_collection.json"
  }
}

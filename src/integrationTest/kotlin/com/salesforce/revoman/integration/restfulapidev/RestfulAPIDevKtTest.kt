/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.restfulapidev

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class RestfulAPIDevKtTest {
  @Test
  fun `execute restful-api dev pm collection`() {
    val rundown =
      ReVoman.revUp(
        // <1>
        Kick.configure()
          .templatePath(PM_COLLECTION_PATH) // <2>
          .environmentPath(PM_ENVIRONMENT_PATH) // <3>
          .nodeModulesPath("js")
          .off()
      )
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    assertThat(rundown.stepReports).hasSize(4)
  }

  companion object {
    private const val PM_COLLECTION_PATH =
      "pm-templates/restfulapidev/restful-api.dev.postman_collection.json"
    private const val PM_ENVIRONMENT_PATH =
      "pm-templates/restfulapidev/restful-api.dev.postman_environment.json"
  }
}

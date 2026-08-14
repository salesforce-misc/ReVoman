/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.restfulapidev.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.integration.testsupport.DeterministicMockApiServer
import org.junit.jupiter.api.Test

class RestfulAPIDevKtTest {
  @Test
  fun testExecuteRestfulApiDevV3Collection() {
    DeterministicMockApiServer.start().use { api ->
      val rundown =
        ReVoman.revUp(
          Kick.configure()
            .templatePath(PM_COLLECTION_PATH)
            .environmentPath(PM_ENVIRONMENT_PATH)
            .dynamicEnvironment("baseUrl", api.baseUrl)
            .nodeModulesPath("js")
            .off()
        )
      assertThat(rundown.firstUnsuccessfulStepReport).isNull()
      assertThat(rundown.stepReports).hasSize(4)
      assertThat(api.requestSignatures())
        .containsExactly(
          "GET /objects",
          "POST /objects",
          "PATCH /objects/local-object-1",
          "GET /objects/local-object-1",
        )
        .inOrder()
    }
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/restful-api.dev"
    private const val PM_ENVIRONMENT_PATH =
      "pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml"
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.pokemon.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class PokemonKtTest {
  @Test
  fun testExecutePokemonV3Collection() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(PM_COLLECTION_PATH)
          .environmentPath(PM_ENVIRONMENT_PATH)
          .nodeModulesPath("js")
          .dynamicEnvironment(mapOf("offset" to "0", "limit" to "1"))
          .off()
      )
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    assertThat(rundown.stepReports.size).isAtLeast(4)
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/pokemon"
    private const val PM_ENVIRONMENT_PATH = "pm-templates/v3/pokemon/Pokemon.environment.yaml"
  }
}

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

class PokemonV2VsV3EquivalenceKtTest {
  @Test
  fun testV2AndV3PokemonProduceIdenticalEnvAndStepCount() {
    val dynEnv = mapOf("offset" to "0", "limit" to "1")
    val v2 =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v2/pokemon/pokemon.postman_collection.json")
          .environmentPath("pm-templates/v2/pokemon/pokemon.postman_environment.json")
          .nodeModulesPath("js")
          .dynamicEnvironment(dynEnv)
          .off()
      )
    val v3 =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/pokemon")
          .environmentPath("pm-templates/v3/pokemon/Pokemon.environment.yaml")
          .nodeModulesPath("js")
          .dynamicEnvironment(dynEnv)
          .off()
      )
    assertThat(v3.stepReports.size).isEqualTo(v2.stepReports.size)
    val keysToCompare =
      listOf("baseUrl", "id", "pokemonName", "color", "gender", "ability", "nature")
    for (key in keysToCompare) {
      assertThat(v3.mutableEnv[key]).isEqualTo(v2.mutableEnv[key])
    }
  }
}

/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon.v3;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PokemonV2VsV3EquivalenceTest {
  @Test
  void v2AndV3PokemonProduceIdenticalEnvAndStepCount() {
    final Map<String, Object> dyn = Map.of("offset", "0", "limit", "1");
    final Rundown v2 =
        ReVoman.revUp(
            Kick.configure()
                .templatePath("pm-templates/v2/pokemon/pokemon.postman_collection.json")
                .environmentPath("pm-templates/v2/pokemon/pokemon.postman_environment.json")
                .nodeModulesPath("js")
                .dynamicEnvironment(dyn)
                .off());
    final Rundown v3 =
        ReVoman.revUp(
            Kick.configure()
                .templatePath("pm-templates/v3/pokemon")
                .environmentPath("pm-templates/v3/pokemon/Pokemon.environment.yaml")
                .nodeModulesPath("js")
                .dynamicEnvironment(dyn)
                .off());
    assertThat(v3.stepReports.size()).isEqualTo(v2.stepReports.size());
    final List<String> keys =
        List.of("baseUrl", "id", "pokemonName", "color", "gender", "ability", "nature");
    for (final String k : keys) {
      assertThat(v3.mutableEnv.get(k)).isEqualTo(v2.mutableEnv.get(k));
    }
  }
}

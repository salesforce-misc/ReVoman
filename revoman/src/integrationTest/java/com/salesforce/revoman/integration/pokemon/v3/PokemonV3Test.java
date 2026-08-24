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
import java.util.Map;
import org.junit.jupiter.api.Test;

class PokemonV3Test {
  private static final String PM_COLLECTION_PATH = "pm-templates/v3/pokemon";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v3/pokemon/Pokemon.environment.yaml";

  @Test
  void executePokemonV3CollectionFromJava() {
    final Kick config =
        Kick.configure()
            .templatePath(PM_COLLECTION_PATH)
            .environmentPath(PM_ENVIRONMENT_PATH)
            .nodeModulesPath("js")
            .dynamicEnvironment(Map.of("offset", "0", "limit", "1"))
            .off();
    final Rundown rundown = ReVoman.revUp(config);
    assertThat(rundown.firstUnsuccessfulStepReport()).isNull();
    assertThat(rundown.stepReports.size()).isAtLeast(4);
  }
}

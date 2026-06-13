/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.report.PmTestAssertion;
import com.salesforce.revoman.output.report.StepReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the script-only `pm` APIs through a real ReVoman run against free live
 * APIs (pokeapi.co GET + restful-api.dev POST/PUT). Verifies that variables, environment(.name),
 * request/response, test/expect, collectionVariables, and setNextRequest all surface on the
 * Rundown.
 */
class PokemonSandboxApiTest {
  private static final String PM_COLLECTION_PATH =
      "pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json";

  @Test
  @DisplayName("script-only pm APIs surface end-to-end")
  void pmSandboxApisEndToEnd() {
    // tag::pm-sandbox-revup[]
    final Rundown rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(PM_COLLECTION_PATH)
                .environmentPath(PM_ENVIRONMENT_PATH)
                .nodeModulesPath("js")
                .off());
    // end::pm-sandbox-revup[]

    // No step failed (HTTP + all scripts ran without thrown error).
    assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull();
    assertThat(rundown.stepReports).hasSize(5);

    // --- pm.environment / pm.test / pm.expect / pm.response.* (all-pokemon) ---
    final StepReport allPokemon = rundown.reportForStepName("all-pokemon");
    assertThat(allPokemon).isNotNull();
    assertThat(allPokemon.pmTestAssertions).isNotEmpty();
    assertThat(allPokemon.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();
    assertThat(rundown.mutableEnv).containsKey("pokemonName");
    assertThat(rundown.mutableEnv).containsKey("objId"); // add-object POST set this for the PUT

    // tag::pm-sandbox-asserts[]
    // --- pm.collectionVariables set in step 1, read in steps 2-3 (cross-step) ---
    final StepReport byName = rundown.reportForStepName("pokemon-by-name");
    assertThat(byName).isNotNull();
    assertThat(byName.pmTestAssertions).isNotEmpty();
    assertThat(byName.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();

    // --- pm.execution.setNextRequest: CAPTURED (not executed — Phase 2 reorders) ---
    // ReVoman still runs steps linearly; we assert only that the directive was surfaced.
    assertThat(byName.nextRequest).isEqualTo("pokemon-species");
    // Proof it was NOT executed: pokemon-species still ran in linear order after pokemon-by-name.
    assertThat(rundown.reportForStepName("pokemon-species")).isNotNull();
    // Guard the crown-jewel cross-step collectionVariable proof against silently vanishing:
    // a failing pm.test is DATA (passed=false), and allMatch on an empty list passes vacuously, so
    // assert this step actually produced assertions.
    assertThat(rundown.reportForStepName("pokemon-species").pmTestAssertions).isNotEmpty();
    // end::pm-sandbox-asserts[]

    // --- pm.request.body via restful-api.dev PUT ---
    final StepReport update = rundown.reportForStepName("update-object");
    assertThat(update).isNotNull();
    assertThat(update.pmTestAssertions).isNotEmpty();
    assertThat(update.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();

    // --- Every assertion across the whole run passed ---
    final List<PmTestAssertion> all =
        rundown.stepReports.stream().flatMap(s -> s.pmTestAssertions.stream()).toList();
    assertThat(all).isNotEmpty();
    assertThat(all.stream().allMatch(a -> a.passed)).isTrue();
  }
}

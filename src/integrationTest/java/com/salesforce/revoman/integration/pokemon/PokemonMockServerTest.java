/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import java.util.Map;
import org.http4k.core.Response;
import org.http4k.core.Status;
import org.junit.jupiter.api.Test;

class PokemonMockServerTest {

  private static final String PM_COLLECTION_PATH =
      "pm-templates/pokemon/pokemon.postman_collection.json";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/pokemon/pokemon.postman_environment.json";

  @Test
  void pokemonWithMockServer() {
    final var rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(PM_COLLECTION_PATH)
                .environmentPath(PM_ENVIRONMENT_PATH)
                .dynamicEnvironment(Map.of("offset", 0, "limit", 3))
                .responseConfig(
                    unmarshallResponse(afterStepName("all-pokemon"), PokemonTest.AllPokemon.class))
                .httpHandler(
                    request -> {
                      final var path = request.getUri().getPath();
                      if (path.contains("/pokemon-color")) {
                        return Response.create(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(COLOR_JSON);
                      } else if (path.contains("/pokemon")) {
                        return Response.create(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(ALL_POKEMON_JSON);
                      } else if (path.contains("/gender")) {
                        return Response.create(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(GENDER_JSON);
                      } else if (path.contains("/ability")) {
                        return Response.create(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(ABILITY_JSON);
                      } else if (path.contains("/nature")) {
                        return Response.create(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(NATURE_JSON);
                      }
                      return Response.create(Status.NOT_FOUND);
                    })
                .off());

    assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull();
    assertThat(rundown.stepReports).hasSize(5);
    // Verify environment variables set by post-response JS scripts
    assertThat(rundown.mutableEnv).containsEntry("pokemonName", "bulbasaur");
    assertThat(rundown.mutableEnv).containsEntry("id", "1");
    assertThat(rundown.mutableEnv).containsEntry("color", "black");
    assertThat(rundown.mutableEnv).containsEntry("gender", "female");
    assertThat(rundown.mutableEnv).containsEntry("ability", "stench");
    assertThat(rundown.mutableEnv).containsEntry("nature", "hardy");
    // Verify response unmarshalling works with mock responses
    final var allPokemon =
        rundown
            .reportForStepName("all-pokemon")
            .responseInfo
            .get()
            .<PokemonTest.AllPokemon>getTypedTxnObj();
    assertThat(allPokemon.results()).hasSize(3);
    assertThat(allPokemon.results().get(0).name()).isEqualTo("bulbasaur");
  }

  private static final String ALL_POKEMON_JSON =
      """
      {
        "count": 1302,
        "next": "https://pokeapi.co/api/v2/pokemon?offset=3&limit=3",
        "previous": null,
        "results": [
          {"name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon/1/"},
          {"name": "ivysaur", "url": "https://pokeapi.co/api/v2/pokemon/2/"},
          {"name": "venusaur", "url": "https://pokeapi.co/api/v2/pokemon/3/"}
        ]
      }
      """;

  private static final String COLOR_JSON =
      """
      {
        "id": 1,
        "name": "black",
        "names": [{"language": {"name": "ja-Hrkt", "url": "https://pokeapi.co/api/v2/language/1/"}, "name": "\\u304f\\u308d\\u3044"}],
        "pokemon_species": [{"name": "snorlax", "url": "https://pokeapi.co/api/v2/pokemon-species/143/"}]
      }
      """;

  private static final String GENDER_JSON =
      """
      {"id": 1, "name": "female"}
      """;

  private static final String ABILITY_JSON =
      """
      {"id": 1, "name": "stench"}
      """;

  private static final String NATURE_JSON =
      """
      {"id": 1, "name": "hardy"}
      """;
}

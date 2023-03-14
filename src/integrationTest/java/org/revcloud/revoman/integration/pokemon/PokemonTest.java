package org.revcloud.revoman.integration.pokemon;

import static org.revcloud.revoman.input.SuccessConfig.validateIfSuccess;

import com.salesforce.vador.config.ValidationConfig;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;

class PokemonTest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void pokemon() {
    final var offset = 0;
    final var limit = 1;
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/pokemon.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of(
        "offset", String.valueOf(offset),
        "limit", String.valueOf(limit)
    );
    final var pokemonResultsValidationConfig =
        ValidationConfig.<Results, String>toValidate()
            .withValidator(
                results -> results.getResults().size() == limit ? "Good" : "Bad", "Good");
    
    final var rundown = ReVoman.revUp(Kick.configure()
        .templatePath(pmCollectionPath)
        .environmentPath(pmEnvironmentPath)
        .stepNameToSuccessConfig(Map.of("all-Pokemon", validateIfSuccess(Results.class, pokemonResultsValidationConfig)))
        .dynamicEnvironment(dynamicEnvironment)
        .off());

    Assertions.assertThat(rundown.stepNameToReport).hasSize(5);
    Assertions.assertThat(rundown.environment)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "offset", String.valueOf(offset),
                "limit", String.valueOf(limit),
                "baseUrl", "https://pokeapi.co/api/v2",
                "id", "1",
                "pokemonName", "bulbasaur",
                "color", "black",
                "gender", "female",
                "ability", "stench",
                "nature", "hardy"));
  }
}

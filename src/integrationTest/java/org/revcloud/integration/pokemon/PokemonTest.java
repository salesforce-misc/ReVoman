package org.revcloud.integration.pokemon;

import com.salesforce.vador.config.ValidationConfig;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.revcloud.ReVoman;
import org.revcloud.input.Kick;

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
    final var kickOffConfig =
        Kick.configure()
            .templatePath(pmCollectionPath)
            .environmentPath(pmEnvironmentPath)
            .stepNameToSuccessType(Map.of("all-pokemon", Results.class))
            .stepNameToValidationConfig(Map.of("all-Pokemon", pokemonResultsValidationConfig))
            .dynamicEnvironment(dynamicEnvironment)
            .off();
    final var rundown = ReVoman.revUp(kickOffConfig);

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

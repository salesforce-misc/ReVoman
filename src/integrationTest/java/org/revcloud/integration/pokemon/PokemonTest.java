package org.revcloud.integration.pokemon;

import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.revcloud.ReVoman;
import org.revcloud.input.Kick;
import org.revcloud.vador.config.ValidationConfig;

class PokemonTest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void pokemon() {
    final var limit = 10;
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/Pokemon.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/Pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of("limit", String.valueOf(limit));
    final var pokemonResultsValidationConfig =
        ValidationConfig.<Results, String>toValidate()
            .withValidator(
                results -> results.getResults().size() == limit ? "Good" : "Bad", "Good");
    final var kickOffConfig =
        Kick.configure()
            .templatePath(pmCollectionPath)
            .environmentPath(pmEnvironmentPath)
            .stepNameToSuccessType(Map.of("Pokemon", Abilities.class))
            .stepNameToValidationConfig(Map.of("All Pokemon", pokemonResultsValidationConfig))
            .dynamicEnvironment(dynamicEnvironment)
            .off();
    final var rundown = ReVoman.revUp(kickOffConfig);

    Assertions.assertThat(rundown.stepNameToReport).hasSize(2);
    Assertions.assertThat(rundown.environment)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "limit", String.valueOf(limit),
                "baseUrl", "https://pokeapi.co/api/v2",
                "pokemon", "bulbasaur"));
  }
}

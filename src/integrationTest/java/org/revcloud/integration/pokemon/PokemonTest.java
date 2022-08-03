package org.revcloud.integration.pokemon;

import java.util.Map;
import kotlin.Pair;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.revcloud.ReVoman;
import org.revcloud.input.Kick;
import org.revcloud.vader.runner.config.ValidationConfig;

class PokemonTest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void pokemon() {
    final var allPokemonItemName = "All Pokemon";
    final var limit = 10;
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/Pokemon.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/Pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of("limit", String.valueOf(limit));
    final var pokemonResultsValidationConfig =
        ValidationConfig.<Results, String>toValidate()
            .withValidator(results -> results.getResults().size() == limit ? "Good" : "Bad", "Good")
            .prepare();
    final var kickOffConfig =
        Kick.configure()
            .templatePath(pmCollectionPath)
            .environmentPath(pmEnvironmentPath)
            .itemNameToSuccessType(
                Map.of(
                    allPokemonItemName,
                    new Pair<>(Results.class, pokemonResultsValidationConfig),
                    "Pokemon",
                    new Pair<>(Abilities.class, null)))
            .dynamicEnvironment(dynamicEnvironment)
            .off();
    final var pokemon = ReVoman.revUp(kickOffConfig);

    Assertions.assertThat(pokemon.itemNameToResponseWithType).hasSize(2);
    Assertions.assertThat(pokemon.environment)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "limit", String.valueOf(limit),
                "baseUrl", "https://pokeapi.co/api/v2",
                "pokemon", "bulbasaur"));
  }
}

package org.revcloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReVomanTest {
  @Test
  void lasso() {
    final var itemToOutputType =
        Map.of(
            "All Pokemon", Results.class,
            "Pokemon", Abilities.class);
    final var limit = 10;
    final var pmCollectionPath = "src/test/resources/Pokemon.postman_collection.json";
    final var pmEnvironmentPath = "src/test/resources/Pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of("limit", String.valueOf(limit));
    final var pokemon =
        ReVoman.lasso(pmCollectionPath, pmEnvironmentPath, itemToOutputType, dynamicEnvironment);

    assertThat(pokemon.itemNameToResponseWithType).hasSize(2);
    final Class<?> allPokemonResultType =
        pokemon.itemNameToResponseWithType.get("All Pokemon").getSecond();
    assertThat(allPokemonResultType).isEqualTo(itemToOutputType.get("All Pokemon"));

    final var allPokemonResult = pokemon.itemNameToResponseWithType.get("All Pokemon").getFirst();
    assertThat(allPokemonResult).isInstanceOf(itemToOutputType.get("All Pokemon"));
    assertThat(((Results) allPokemonResult).results).hasSize(limit);

    assertThat(pokemon.environment)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "limit", String.valueOf(limit),
                "baseUrl", "https://pokeapi.co/api/v2",
                "pokemon", "bulbasaur"));
  }

  public record Pokemon(String name) {}

  public record Results(List<Pokemon> results) {}

  public record Ability(String name) {}

  public record AbilityWrapper(Ability ability) {}

  public record Abilities(List<AbilityWrapper> abilities) {}
}

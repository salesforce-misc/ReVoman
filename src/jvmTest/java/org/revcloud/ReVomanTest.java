package org.revcloud;

import org.junit.jupiter.api.Test;
import org.revcloud.response.types.salesforce.Graphs;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.DynamicEnvironmentKeys.BEARER_TOKEN;

class ReVomanTest {
  private static final String TEST_RESOURCES_PATH = "src/jvmTest/resources/";

  @Test
  void revUp() {
    final var allPokemonItemName = "All Pokemon";
    final var itemNameToOutputType =
        Map.of(
            allPokemonItemName, Results.class,
            "Pokemon", Abilities.class);
    final var limit = 10;
    final var pmCollectionPath = TEST_RESOURCES_PATH + "Pokemon.postman_collection.json";
    final var pmEnvironmentPath = TEST_RESOURCES_PATH + "Pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of("limit", String.valueOf(limit));
    final var pokemon =
        ReVoman.revUp(pmCollectionPath, pmEnvironmentPath, itemNameToOutputType, dynamicEnvironment);

    assertThat(pokemon.itemNameToResponseWithType).hasSize(2);
    final Class<?> allPokemonResultType =
        pokemon.itemNameToResponseWithType.get(allPokemonItemName).getSecond();
    assertThat(allPokemonResultType).isEqualTo(itemNameToOutputType.get(allPokemonItemName));

    final var allPokemonResult = pokemon.itemNameToResponseWithType.get(allPokemonItemName).getFirst();
    assertThat(allPokemonResult).isInstanceOf(itemNameToOutputType.get(allPokemonItemName));
    assertThat(((Results) allPokemonResult).results).hasSize(limit);

    assertThat(pokemon.environment)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "limit", String.valueOf(limit),
                "baseUrl", "https://pokeapi.co/api/v2",
                "pokemon", "bulbasaur"));
  }

  @Test
  void supernova() {
    final var pmCollectionPath = TEST_RESOURCES_PATH + "ReVoman.postman_collection.json";
    final var pmEnvironmentPath = TEST_RESOURCES_PATH + "ReVoman.postman_environment.json";
    final var dynamicEnvironment = Map.of(
        BEARER_TOKEN,
        "00DRN0000009wGZ!ARMAQBmxEA4c.FCbQxG3jNUpoPxMesoAiFtxbnhFqqKVJPTrgTaAwDDHS.MMAoqdVIAdkT9e2FLwO.fZ4lfcgrcAV6BpBF5S");
    final var setupGraphMinimal = "setup-graph-minimal";
    final var itemNameToOutputType = Map.of(setupGraphMinimal, Graphs.class);
    final var pokemon = ReVoman.revUp(pmCollectionPath, pmEnvironmentPath, itemNameToOutputType, dynamicEnvironment);
    assertThat(pokemon.itemNameToResponseWithType).hasSize(1);
    final Class<?> graphResponseType = pokemon.itemNameToResponseWithType.get(setupGraphMinimal).getSecond();
    assertThat(graphResponseType).isEqualTo(itemNameToOutputType.get(setupGraphMinimal));
    assertThat(pokemon.environment).containsKeys(
        "orderId", "billingTreatmentId", "billingTreatmentItemId", "orderItem1Id",
        "orderItem2Id", "orderItem3Id", "orderItem4Id");
  }

  public record Pokemon(String name) {}

  public record Results(List<Pokemon> results) {}

  public record Ability(String name) {}
  public record AbilityWrapper(Ability ability) {}

  public record Abilities(List<AbilityWrapper> abilities) {}
}

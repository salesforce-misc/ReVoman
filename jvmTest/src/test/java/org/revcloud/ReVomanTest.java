package org.revcloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.revcloud.input.Kick;
import org.revcloud.response.types.salesforce.Graphs;

class ReVomanTest {
  private static final String TEST_RESOURCES_PATH = "src/test/resources/";

  @Test
  void pokemon() {
    final var allPokemonItemName = "All Pokemon";
    final var itemNameToOutputType =
        Map.of(allPokemonItemName, Results.class, "Pokemon", Abilities.class);
    final var limit = 10;
    final var pmCollectionPath = TEST_RESOURCES_PATH + "Pokemon.postman_collection.json";
    final var pmEnvironmentPath = TEST_RESOURCES_PATH + "Pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of("limit", String.valueOf(limit));
    final var kickOffConfig =
        Kick.configure()
            .templatePath(pmCollectionPath)
            .environmentPath(pmEnvironmentPath)
            .itemNameToOutputType(itemNameToOutputType)
            .dynamicEnvironment(dynamicEnvironment)
            .off();
    final var pokemon = ReVoman.revUp(kickOffConfig);

    assertThat(pokemon.itemNameToResponseWithType).hasSize(2);
    final Class<?> allPokemonResultType =
        pokemon.itemNameToResponseWithType.get(allPokemonItemName).getSecond();
    assertThat(allPokemonResultType).isEqualTo(itemNameToOutputType.get(allPokemonItemName));

    final var allPokemonResult =
        pokemon.itemNameToResponseWithType.get(allPokemonItemName).getFirst();
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
  void revUp() {
    final var pmCollectionPath = TEST_RESOURCES_PATH + "ReVoman.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH + "ReVoman (UTest - Linux).postman_environment.json";
    final var orderOrderItemGraphReqName = "order-orderItem";
    final var itemNameToOutputType = Map.of(orderOrderItemGraphReqName, Graphs.class);
    final var rundown =
        ReVoman.revUp(pmCollectionPath, pmEnvironmentPath, "accessToken", itemNameToOutputType);

    // ! TODO gopala.akshintala 09/05/22: Pass these assertions as Vader configs.
    assertThat(rundown.itemNameToResponseWithType).hasSize(20);

    final Class<?> graphsResponseType =
        rundown.itemNameToResponseWithType.get(orderOrderItemGraphReqName).getSecond();
    assertThat(graphsResponseType).isEqualTo(itemNameToOutputType.get(orderOrderItemGraphReqName));
    final var graphsResponse =
        (Graphs) rundown.itemNameToResponseWithType.get(orderOrderItemGraphReqName).getFirst();
    assertThat(graphsResponse.getGraphs()).hasSize(1);
    assertThat(graphsResponse.getGraphs().get(0).isSuccessful()).isTrue();

    assertThat(rundown.environment)
        .containsKeys(
            "orderId",
            "billingTreatmentId",
            "billingTreatmentItemId",
            "orderItem1Id",
            "orderItem2Id",
            "orderItem3Id",
            "orderItem4Id");

    final var billingScheduleItemName = "billing-schedule";
    final var bsResponse =
        (Map<String, List<Map<String, ?>>>)
            rundown.itemNameToResponseWithType.get(billingScheduleItemName).getFirst();
    assertThat(bsResponse).containsOnlyKeys("billingScheduleResultsList");
    assertThat(bsResponse.get("billingScheduleResultsList")).hasSize(1);
    assertThat(((Boolean) bsResponse.get("billingScheduleResultsList").get(0).get("success")))
        .isTrue();
  }

  public record Pokemon(String name) {}

  public record Results(List<Pokemon> results) {}

  public record Ability(String name) {}

  public record AbilityWrapper(Ability ability) {}

  public record Abilities(List<AbilityWrapper> abilities) {}
}

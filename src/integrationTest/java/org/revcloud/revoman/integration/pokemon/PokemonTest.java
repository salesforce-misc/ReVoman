package org.revcloud.revoman.integration.pokemon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.revcloud.revoman.input.InputUtils.post;
import static org.revcloud.revoman.input.InputUtils.pre;
import static org.revcloud.revoman.input.SuccessConfig.validateIfSuccess;

import com.salesforce.vador.config.ValidationConfig;
import com.salesforce.vador.types.Validator;
import java.util.Map;
import java.util.function.Consumer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.revcloud.revoman.output.Rundown;

class PokemonTest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void pokemon() {
    final var offset = 0;
    final var limit = 3;
    final var newLimit = 1;
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/pokemon.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH + "pm-templates/pokemon/pokemon.postman_environment.json";
    final var dynamicEnvironment = Map.of(
        "offset", String.valueOf(offset),
        "limit", String.valueOf(limit)
    );
    //noinspection Convert2Lambda
    final var resultSizeValidator = Mockito.spy(new Validator<Results, String>() {
        @Override
        public String apply(Results results) {
            return results.getResults().size() == newLimit ? "Good" : "Bad";
        }
    });
    final var pokemonResultsValidationConfig =
        ValidationConfig.<Results, String>toValidate()
            .withValidator(resultSizeValidator, "Good");
    //noinspection Convert2Lambda
    final var preHook = Mockito.spy(new Consumer<Rundown>() {
        @Override
        public void accept(Rundown rundown) {
            rundown.environment.set("limit", String.valueOf(newLimit));
        }
    });
    //noinspection Convert2Lambda
    final var postHook = Mockito.spy(new Consumer<Rundown>() {
        @Override
        public void accept(Rundown rundown) {
            Assertions.assertThat(rundown.environment).containsEntry("limit", String.valueOf(newLimit));
            Assertions.assertThat(rundown.environment).containsEntry("pokemonName", "bulbasaur");
        }
    });
    final var pokeRundown = ReVoman.revUp(Kick.configure()
        .templatePath(pmCollectionPath)
        .environmentPath(pmEnvironmentPath)
        .hooks(Map.of(
            pre("all-pokemon"), preHook,
            post("all-pokemon"), postHook))
        .stepNameToSuccessConfig("all-pokemon", validateIfSuccess(Results.class, pokemonResultsValidationConfig))
        .dynamicEnvironment(dynamicEnvironment)
        .off());

    Mockito.verify(resultSizeValidator, times(1)).apply(any());
    Mockito.verify(preHook, times(1)).accept(any());
    Mockito.verify(postHook, times(1)).accept(any());
    Assertions.assertThat(pokeRundown.stepNameToReport).hasSize(5);
    Assertions.assertThat(pokeRundown.environment)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "offset", String.valueOf(offset),
                "limit", String.valueOf(newLimit),
                "baseUrl", "https://pokeapi.co/api/v2",
                "id", "1",
                "pokemonName", "bulbasaur",
                "color", "black",
                "gender", "female",
                "ability", "stench",
                "nature", "hardy"));
  }
  
}

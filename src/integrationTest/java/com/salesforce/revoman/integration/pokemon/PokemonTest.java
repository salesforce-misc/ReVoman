/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or http://www.apache.org/licenses/LICENSE-2.0
 ******************************************************************************/

package com.salesforce.revoman.integration.pokemon;

import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.HookConfig.pre;
import static com.salesforce.revoman.input.config.ResponseConfig.validateIfSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook;
import com.salesforce.revoman.input.config.HookConfig.Hook.PreHook;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick;
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.report.StepReport;
import com.salesforce.revoman.output.report.TxInfo;
import com.salesforce.vador.config.ValidationConfig;
import com.salesforce.vador.types.Validator;
import java.util.Map;
import org.http4k.core.Request;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PokemonTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(PokemonTest.class);

  private static final PreTxnStepPick preLogHookPick =
      (ignore1, requestInfo, ignore2) -> requestInfo.containsHeader("preLog", "true");
  private static final PostTxnStepPick postLogHookPick =
      (ignore, stepReport, rundown) ->
          stepReport
              .getRequestInfo()
              .map(r -> r.containsHeader("postLog", "true"))
              .getOrElse(false);

  @Test
  void pokemon() {
    final var offset = 0;
    final var limit = 3;
    final var newLimit = 1;
    final var pmCollectionPath = "pm-templates/pokemon/pokemon.postman_collection.json";
    final var pmEnvironmentPath = "pm-templates/pokemon/pokemon.postman_environment.json";
    final var dynamicEnvironment =
        Map.of(
            "offset", String.valueOf(offset),
            "limit", String.valueOf(limit));
    //noinspection Convert2Lambda
    final var resultSizeValidator =
        Mockito.spy(
            new Validator<Results, String>() {
              @Override
              public String apply(Results results) {
                return results.getResults().size() == newLimit ? "Good" : "Bad";
              }
            });
    final var pokemonResultsValidationConfig =
        ValidationConfig.<Results, String>toValidate()
            .withValidator(resultSizeValidator, "Good")
            .prepare();
    //noinspection Convert2Lambda
    final var preLogHook =
        Mockito.spy(
            new PreHook() {
              @Override
              public void accept(
                  @NotNull String stepName,
                  @NotNull TxInfo<Request> requestInfo,
                  @NotNull Rundown rundown) {
                LOGGER.info("Picked `preLogHook` for stepName: {}", stepName);
              }
            });
    //noinspection Convert2Lambda
    final var postLogHook =
        Mockito.spy(
            new PostHook() {
              @Override
              public void accept(
                  @NotNull String currentStepName,
                  @NotNull StepReport currentStepReport,
                  @NotNull Rundown rundown) {
                LOGGER.info("Picked `postLogHook` for stepName: {}", currentStepName);
              }
            });
    //noinspection Convert2Lambda
    final var preHook =
        Mockito.spy(
            new PreHook() {
              @Override
              public void accept(
                  @NotNull String stepName,
                  @NotNull TxInfo<Request> requestInfo,
                  @NotNull Rundown rundown) {
                rundown.mutableEnv.set("limit", String.valueOf(newLimit));
              }
            });
    //noinspection Convert2Lambda
    final var postHook =
        Mockito.spy(
            new PostHook() {
              @Override
              public void accept(
                  @NotNull String ignore1, @NotNull StepReport ignore2, @NotNull Rundown rundown) {
                assertThat(rundown.mutableEnv).containsEntry("limit", String.valueOf(newLimit));
                assertThat(rundown.mutableEnv).containsEntry("pokemonName", "bulbasaur");
              }
            });
    final var pokeRundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(pmCollectionPath)
                .environmentPath(pmEnvironmentPath)
                .hooks(
                    pre("all-pokemon", preHook),
                    post("all-pokemon", postHook),
                    pre(preLogHookPick, preLogHook),
                    post(postLogHookPick, postLogHook))
                .responseConfig(
                    validateIfSuccess("all-pokemon", Results.class, pokemonResultsValidationConfig))
                .dynamicEnvironment(dynamicEnvironment)
                .haltOnAnyFailure(true)
                .off());

    Mockito.verify(resultSizeValidator, times(1)).apply(any());
    Mockito.verify(preHook, times(1)).accept(any(), any(), any());
    Mockito.verify(postHook, times(1)).accept(anyString(), any(), any());
    Mockito.verify(preLogHook, times(1)).accept(anyString(), any(), any());
    Mockito.verify(postLogHook, times(1)).accept(anyString(), any(), any());
    assertThat(pokeRundown.stepNameToReport).hasSize(5);
    assertThat(pokeRundown.mutableEnv)
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

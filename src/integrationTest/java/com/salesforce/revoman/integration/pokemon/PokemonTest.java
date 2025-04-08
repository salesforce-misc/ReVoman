/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.HookConfig.pre;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingURIPathOfAny;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName;
import static com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.beforeStepContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.beforeStepName;
import static org.assertj.vavr.api.VavrAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.HookConfig.StepHook.PostStepHook;
import com.salesforce.revoman.input.config.HookConfig.StepHook.PreStepHook;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.adapters.IDAdapter;
import com.salesforce.revoman.integration.core.pq.connect.response.ID;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.report.Step;
import com.salesforce.revoman.output.report.StepReport;
import com.salesforce.revoman.output.report.TxnInfo;
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure;
import com.squareup.moshi.Json;
import java.util.List;
import java.util.Map;
import org.http4k.core.Request;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PokemonTest {

	private static final String PM_COLLECTION_PATH =
			"pm-templates/pokemon/pokemon.postman_collection.json";
	private static final String PM_ENVIRONMENT_PATH =
			"pm-templates/pokemon/pokemon.postman_environment.json";
	private static final int LIMIT = 3;
	private static final int OFFSET = 0;
	private static final Logger LOGGER = LoggerFactory.getLogger(PokemonTest.class);
	private static final RuntimeException RUNTIME_EXCEPTION =
			new RuntimeException("This won't interrupt the execution as `haltOnAnyFailure` is not set");

	@Test
	void pokemon() {
		final var newLimit = 1;
		final var dynamicEnvironment =
				Map.of(
						"offset", String.valueOf(OFFSET),
						"limit", String.valueOf(LIMIT));
		@SuppressWarnings("Convert2Lambda")
		final var preLogHook =
				Mockito.spy(
						new PreStepHook() {
							@Override
							public void accept(
									@NotNull Step currentStep,
									@NotNull TxnInfo<Request> ignore1,
									@NotNull Rundown ignore2) {
								LOGGER.info("Picked `preLogHook` before stepName: {}", currentStep);
							}
						});
		@SuppressWarnings("Convert2Lambda")
		final var postLogHook =
				Mockito.spy(
						new PostStepHook() {
							@Override
							public void accept(@NotNull StepReport stepReport, @NotNull Rundown ignore) {
								LOGGER.info("Picked `postLogHook` after stepName: {}", stepReport.step.displayName);
								throw RUNTIME_EXCEPTION;
							}
						});
		@SuppressWarnings("Convert2Lambda")
		final var preStepHookBeforeStepName =
				Mockito.spy(
						new PreStepHook() {
							@Override
							public void accept(
									@NotNull Step ignore1,
									@NotNull TxnInfo<Request> ignore2,
									@NotNull Rundown rundown) {
								rundown.mutableEnv.set("limit", String.valueOf(newLimit));
							}
						});
		@SuppressWarnings("Convert2Lambda")
		final var postStepHookAfterStepName =
				Mockito.spy(
						new PostStepHook() {
							@Override
							public void accept(@NotNull StepReport stepReport, @NotNull Rundown rundown) {
								assertThat(rundown.mutableEnv).containsEntry("limit", String.valueOf(newLimit));
								final var results =
										stepReport.responseInfo.get().<AllPokemon>getTypedTxnObj().results;
								assertThat(results.size()).isEqualTo(newLimit);
							}
						});
		@SuppressWarnings("Convert2Lambda")
		final var postStepHookAfterURIPath =
				Mockito.spy(
						new PostStepHook() {
							@Override
							public void accept(@NotNull StepReport stepReport, @NotNull Rundown rundown) {
								LOGGER.info(
										"Picked `postStepHookAfterURIPath` after stepName: {} with raw URI: {}",
										stepReport.step.displayName,
										stepReport.step.rawPMStep.getRequest().url);
								final var id =
										stepReport
												.responseInfo
												.map(
														ri ->
																ri.<Color>getTypedTxnObj(Color.class, List.of(new IDAdapter())).id)
												.getOrNull();
								assertThat(id.id()).isEqualTo(rundown.mutableEnv.get("id"));
							}
						});
		final var pokeRundown =
				ReVoman.revUp(
						Kick.configure()
								.templatePath(PM_COLLECTION_PATH)
								.environmentPath(PM_ENVIRONMENT_PATH)
								.responseConfig(unmarshallResponse(afterStepName("all-pokemon"), AllPokemon.class))
								.hooks(
										pre(beforeStepName("all-pokemon"), preStepHookBeforeStepName),
										post(afterStepName("all-pokemon"), postStepHookAfterStepName),
										post(
												afterStepContainingURIPathOfAny("pokemon-color"), postStepHookAfterURIPath),
										pre(beforeStepContainingHeader("preLog"), preLogHook),
										post(afterStepContainingHeader("postLog"), postLogHook))
								.dynamicEnvironment(dynamicEnvironment)
								.off());

		final var postHookFailure = pokeRundown.firstUnIgnoredUnsuccessfulStepReport().failure;
		assertThat(postHookFailure).containsLeftInstanceOf(PostStepHookFailure.class);
		assertThat(postHookFailure.getLeft().getFailure()).isEqualTo(RUNTIME_EXCEPTION);
		assertThat(pokeRundown.stepReports).hasSize(5);
		assertThat(pokeRundown.mutableEnv)
				.containsExactlyEntriesIn(
						Map.of(
								"offset", String.valueOf(OFFSET),
								"limit", String.valueOf(newLimit),
								"baseUrl", "https://pokeapi.co/api/v2",
								"id", "1",
								"pokemonName", "bulbasaur",
								"color", "black",
								"gender", "female",
								"ability", "stench",
								"nature", "hardy"));
		Mockito.verify(preStepHookBeforeStepName, times(1)).accept(any(), any(), any());
		Mockito.verify(postStepHookAfterStepName, times(1)).accept(any(), any());
		Mockito.verify(postStepHookAfterURIPath, times(1)).accept(any(), any());
		Mockito.verify(preLogHook, times(1)).accept(any(), any(), any());
		Mockito.verify(postLogHook, times(1)).accept(any(), any());
	}

	public record AllPokemon(int count, String next, String previous, List<Result> results) {
		public record Result(String name, String url) {}
	}

	public record Color(
			ID id,
			String name,
			List<Name> names,
			@Json(name = "pokemon_species") List<PokemonSpecies> pokemonSpecies) {
		public record Name(Language language, String name) {
			public record Language(String name, String url) {}
		}

		public record PokemonSpecies(String name, String url) {}
	}
}

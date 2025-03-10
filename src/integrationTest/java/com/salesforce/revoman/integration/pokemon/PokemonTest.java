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
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.report.Step;
import com.salesforce.revoman.output.report.StepReport;
import com.salesforce.revoman.output.report.TxnInfo;
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure;
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
		//noinspection Convert2Lambda
		final var preLogHook =
				Mockito.spy(
						new PreStepHook() {
							@Override
							public void accept(
									@NotNull Step currentStep,
									@NotNull TxnInfo<Request> requestInfo,
									@NotNull Rundown rundown) {
								LOGGER.info("Picked `preLogHook` before stepName: {}", currentStep);
							}
						});
		//noinspection Convert2Lambda
		final var postLogHook =
				Mockito.spy(
						new PostStepHook() {
							@Override
							public void accept(@NotNull StepReport stepReport, @NotNull Rundown rundown) {
								LOGGER.info("Picked `postLogHook` after stepName: {}", stepReport.step.displayName);
								throw RUNTIME_EXCEPTION;
							}
						});
		//noinspection Convert2Lambda
		final var preHook =
				Mockito.spy(
						new PreStepHook() {
							@Override
							public void accept(
									@NotNull Step currentStep,
									@NotNull TxnInfo<Request> requestInfo,
									@NotNull Rundown rundown) {
								rundown.mutableEnv.set("limit", String.valueOf(newLimit));
							}
						});
		//noinspection Convert2Lambda
		final var postHook =
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
		//noinspection Convert2Lambda
		final var postHookAfterURIPath =
				Mockito.spy(
						new PostStepHook() {
							@Override
							public void accept(@NotNull StepReport stepReport, @NotNull Rundown rundown) {
								LOGGER.info(
										"Picked `postHookAfterURIPath` after stepName: {} with raw URI: {}",
										stepReport.step.displayName,
										stepReport.step.rawPMStep.getRequest().url);
							}
						});
		final var pokeRundown =
				ReVoman.revUp(
						Kick.configure()
								.templatePath(PM_COLLECTION_PATH)
								.environmentPath(PM_ENVIRONMENT_PATH)
								.responseConfig(unmarshallResponse(afterStepName("all-pokemon"), AllPokemon.class))
								.hooks(
										pre(beforeStepName("all-pokemon"), preHook),
										post(afterStepName("all-pokemon"), postHook),
										post(afterStepContainingURIPathOfAny("nature"), postHookAfterURIPath),
										pre(beforeStepContainingHeader("preLog"), preLogHook),
										post(afterStepContainingHeader("postLog"), postLogHook))
								.dynamicEnvironment(dynamicEnvironment)
								.off());
    
		assertThat(pokeRundown.firstUnIgnoredUnsuccessfulStepReport().failure)
				.containsOnLeft(new PostStepHookFailure(RUNTIME_EXCEPTION));
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
		Mockito.verify(preHook, times(1)).accept(any(), any(), any());
		Mockito.verify(postHook, times(1)).accept(any(), any());
		Mockito.verify(postHookAfterURIPath, times(1)).accept(any(), any());
		Mockito.verify(preLogHook, times(1)).accept(any(), any(), any());
		Mockito.verify(postLogHook, times(1)).accept(any(), any());
	}

	public record AllPokemon(int count, String next, String previous, List<Result> results) {
		public record Result(String name, String url) {}
	}
}

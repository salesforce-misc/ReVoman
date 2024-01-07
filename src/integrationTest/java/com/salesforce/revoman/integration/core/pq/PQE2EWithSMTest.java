/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq;

import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.HookConfig.pre;
import static com.salesforce.revoman.input.config.RequestConfig.unmarshallRequest;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallSuccessResponse;
import static com.salesforce.revoman.input.config.ResponseConfig.validateIfFailed;
import static com.salesforce.revoman.input.config.ResponseConfig.validateIfSuccess;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterAllStepsContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterAllStepsInFolder;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterAllStepsWithURIPathEndingWith;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName;
import static com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.beforeAllStepsWithURIPathEndingWith;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.pq.connect.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.output.postman.PostmanEnvironment;
import com.salesforce.vador.config.ValidationConfig;
import java.util.List;
import java.util.Map;
import kotlin.random.Random;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ----------------------------------------~~~~~ NOTE ~~~~~-----------------------------------------
 * This is only a sample to demo a full-blown test. You may not be able to execute this, as it needs
 * a specific server setup.
 *
 * <p>TODO: Add a mock server setup for this test.
 */
class PQE2EWithSMTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(PQE2EWithSMTest.class);
  private static final List<String> PQ_TEMPLATE_PATHS =
      List.of(
          "pm-templates/pq/user-creation-and-setup-pq.postman_collection.json",
          "pm-templates/pq/pre-salesRep.postman_collection.json",
          "pm-templates/pq/pq-sm.postman_collection.json");
  private static final String PQ_PATH = "commerce/quotes/actions/place";
  private static final ValidationConfig<PlaceQuoteOutputRepresentation, String>
      VALIDATE_PQ_SUCCESS =
          ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
              .withValidator(
                  (resp -> {
                    LOGGER.info("🦾Validating PQ response for Success");
                    return Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "sync-failure";
                  }),
                  "success")
              .prepare();
  private static final ValidationConfig<PlaceQuoteOutputRepresentation, String>
      VALIDATE_PQ_SYNC_ERROR =
          ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
              .withValidator(
                  (resp -> {
                    LOGGER.info("🦾Validating PQ response for Failure");
                    return Boolean.FALSE.equals(resp.getSuccess()) ? "sync-failure" : "success";
                  }),
                  "sync-failure")
              .prepare();

  @Test
  void revUpPQ() {
    // tag::pq-e2e-with-revoman-config-demo[]
    final var pqRundown =
        ReVoman.revUp( // <1>
            Kick.configure()
                .templatePaths(PQ_TEMPLATE_PATHS) // <2>
                .environmentPath("pm-templates/pq/pq-env.postman_environment.json") // <3>
                .dynamicEnvironment( // <4>
                    Map.of(
                        "$quoteFieldsToQuery", "LineItemCount, CalculationStatus",
                        "$qliFieldsToQuery", "Id, Product2Id",
                        "$qlrFieldsToQuery", "Id, QuoteId, MainQuoteLineId, AssociatedQuoteLineId"))
                .customDynamicVariable( // <5>
                    "$quantity", ignore -> String.valueOf(Random.Default.nextInt(10) + 1))
                .haltOnAnyFailureExcept(afterAllStepsContainingHeader("ignoreForFailure")) // <6>
                .requestConfig( // <7>
                    unmarshallRequest(
                        beforeAllStepsWithURIPathEndingWith(PQ_PATH),
                        PlaceQuoteInputRepresentation.class,
                        adapter(PlaceQuoteInputRepresentation.class)))
                .hooks( // <8>
                    pre(
                        beforeAllStepsWithURIPathEndingWith(PQ_PATH),
                        (step, requestInfo, rundown) -> {
                          final var pqInputRep =
                              requestInfo.<PlaceQuoteInputRepresentation>getTypedTxObj();
                          assertThat(pqInputRep).isNotNull();
                          if ("pq-create: qli+qlr (skip-pricing)"
                              .equals(pqInputRep.getGraph().getGraphId())) {
                            LOGGER.info("Skip pricing for step: {}", step);
                            rundown.mutableEnv.set("$pricingPref", PricingPref.Skip.toString());
                          } else {
                            rundown.mutableEnv.set("$pricingPref", PricingPref.System.toString());
                          }
                        }),
                    post(
                        afterStepName("query-quote-and-related-records"),
                        (ignore, rundown) -> assertAfterPQCreate(rundown.mutableEnv)),
                    post(
                        afterAllStepsWithURIPathEndingWith(PQ_PATH),
                        (stepReport, ignore) -> {
                          LOGGER.info(
                              "Waiting in PostHook for Step: {} for the Quote to get processed",
                              stepReport.step.displayName);
                          // ! CAUTION 10/09/23 gopala.akshintala: This test can be flaky until
                          // polling is implemented
                          Thread.sleep(5000);
                        }))
                .responseConfig( // <9>
                    unmarshallSuccessResponse(
                        afterStepName("quote-related-records"), CompositeResponse.class), // <9.1>
                    validateIfSuccess( // <9.2>
                        afterAllStepsWithURIPathEndingWith(PQ_PATH),
                        PlaceQuoteOutputRepresentation.class,
                        VALIDATE_PQ_SUCCESS),
                    validateIfFailed(
                        afterAllStepsInFolder("errors|>sync"),
                        PlaceQuoteOutputRepresentation.class,
                        VALIDATE_PQ_SYNC_ERROR))
                .insecureHttp(true) // <10>
                .off()); // Kick-off
    assertThat(pqRundown.firstUnIgnoredUnsuccessfulStepReportInOrder()).isNull();
    assertThat(pqRundown.mutableEnv)
        .containsAllEntriesOf(
            Map.of(
                "quoteCalculationStatusForSkipPricing", PricingPref.Skip.completeStatus,
                "quoteCalculationStatus", PricingPref.System.completeStatus,
                "quoteCalculationStatusAfterAllUpdates", PricingPref.System.completeStatus));
    // end::pq-e2e-with-revoman-config-demo[]
  }

  static void assertAfterPQCreate(PostmanEnvironment<Object> env) {
    // Quote: LineItemCount, quoteCalculationStatus
    assertThat(env).containsEntry("lineItemCount", 10);
    assertThat(env)
        .containsEntry(
            "quoteCalculationStatus",
            PricingPref.valueOf(env.getString("$pricingPref")).completeStatus);
    // QLIs: Product2Id
    final var productIdsFromEnv = env.valuesForKeysEndingWith(String.class, "ProductId");
    final var productIdsFromCreatedQLIs =
        env.valuesForKeysStartingWith(String.class, "productForQLI");
    assertThat(productIdsFromCreatedQLIs).containsAll(productIdsFromEnv);
    // QLRs: QuoteId
    final var quoteIdFromQLRs = env.valuesForKeysStartingWith(String.class, "quoteForQLR");
    assertThat(quoteIdFromQLRs).containsOnly(env.getString("quoteId"));
  }

  // end::pq-e2e-with-revoman-demo[]

  private enum PricingPref {
    Force("CompletedWithTax"),
    Skip("CompletedWithoutPricing"),
    System("CompletedWithTax");

    final String completeStatus;

    PricingPref(String completeStatus) {
      this.completeStatus = completeStatus;
    }
  }
}

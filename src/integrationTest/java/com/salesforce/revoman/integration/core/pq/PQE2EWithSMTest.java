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
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterAllStepsContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterAllStepsWithURIPathEndingWith;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName;
import static com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.beforeAllStepsWithURIPathEndingWith;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.pq.connect.request.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.integration.core.pq.connect.response.PlaceQuoteOutputRepresentation;
import com.salesforce.revoman.output.postman.PostmanEnvironment;
import com.salesforce.revoman.output.report.StepReport;
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
  private static final String PQ_ENV_PATH = "pm-templates/pq/pq-env.postman_environment.json";
  private static final String PQ_URI_PATH = "commerce/quotes/actions/place";
  private static final String IS_SYNC_HEADER = "isSync";

  @Test
  void revUpPQ() {
    // tag::pq-e2e-with-revoman-config-demo[]
    final var pqRundown =
        ReVoman.revUp( // <1>
            Kick.configure()
                .templatePaths(PQ_TEMPLATE_PATHS) // <2>
                .environmentPath(PQ_ENV_PATH) // <3>
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
                        beforeAllStepsWithURIPathEndingWith(PQ_URI_PATH),
                        PlaceQuoteInputRepresentation.class,
                        adapter(PlaceQuoteInputRepresentation.class)))
                .hooks( // <8>
                    pre(
                        beforeAllStepsWithURIPathEndingWith(PQ_URI_PATH),
                        (step, requestInfo, rundown) -> {
                          if (requestInfo.containsHeader(IS_SYNC_HEADER)) {
                            LOGGER.info("Skip pricing for Sync step: {}", step);
                            rundown.mutableEnv.set("$pricingPref", PricingPref.Skip.toString());
                          } else {
                            rundown.mutableEnv.set("$pricingPref", PricingPref.System.toString());
                          }
                        }),
                    post(
                        afterAllStepsWithURIPathEndingWith(PQ_URI_PATH),
                        (stepReport, ignore) -> {
                          validatePQResponse(stepReport); // <9>
                          LOGGER.info(
                              "Waiting in PostHook of the Step: {}, for the Quote's Asynchronous processing to finish",
                              stepReport.step.displayName);
                          // ! CAUTION 10/09/23 gopala.akshintala: This can be flaky until
                          // polling is implemented
                          Thread.sleep(5000);
                        }),
                    post(
                        afterStepName("query-quote-and-related-records"),
                        (ignore, rundown) -> assertAfterPQCreate(rundown.mutableEnv)))
                .responseConfig( // <10>
                    unmarshallResponse(
                        afterAllStepsWithURIPathEndingWith(PQ_URI_PATH),
                        PlaceQuoteOutputRepresentation.class))
                .insecureHttp(true) // <11>
                .off()); // Kick-off
    assertThat(pqRundown.firstUnIgnoredUnsuccessfulStepReport()).isNull(); // <12>
    assertThat(pqRundown.mutableEnv)
        .containsAllEntriesOf(
            Map.of(
                "quoteCalculationStatusForSkipPricing", PricingPref.Skip.completeStatus,
                "quoteCalculationStatus", PricingPref.System.completeStatus,
                "quoteCalculationStatusAfterAllUpdates", PricingPref.System.completeStatus));
    // end::pq-e2e-with-revoman-config-demo[]
  }

  private static void validatePQResponse(StepReport stepReport) {
    final var successRespProp =
        stepReport.responseInfo.get().<PlaceQuoteOutputRepresentation>getTypedTxnObj().getSuccess();
    assertThat(successRespProp).isEqualTo(!stepReport.step.isInFolder("error|>sync"));
  }

  private static void assertAfterPQCreate(PostmanEnvironment<Object> env) {
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

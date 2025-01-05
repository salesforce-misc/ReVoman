/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.HookConfig.pre;
import static com.salesforce.revoman.input.config.RequestConfig.unmarshallRequest;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingURIPathOfAny;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepName;
import static com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.beforeStepContainingURIPathOfAny;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static com.salesforce.revoman.output.ExeType.HTTP_STATUS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse;
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.ErrorGraph;
import com.salesforce.revoman.integration.core.pq.adapters.IDAdapter;
import com.salesforce.revoman.integration.core.pq.connect.request.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.integration.core.pq.connect.response.PlaceQuoteOutputRepresentation;
import com.salesforce.revoman.output.postman.PostmanEnvironment;
import com.salesforce.revoman.output.report.StepReport;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kotlin.random.Random;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ----------------------------------------~~~~~ NOTE ~~~~~-----------------------------------------
 * This is only a sample to demo a full-blown test. You may not be able to execute this, as it needs
 * a specific server setup. If you are a Salesforce core developer, it takes less than 5 minutes to
 * setup an SM org,
 *
 * <p>- Follow these instructions: <a href="http://sfdc.co/sm-org-setup">SM Org setup</a>. - Replace
 * baseUrl of your server, username and password of the org admin here: <a
 * href="///resources/pm-templates/pq/pq-env.postman_environment.json">pq-env.postman_environment.json</a>
 *
 * <p>- TODO: Add a mock server setup for this test.
 */
class PQE2EWithSMTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(PQE2EWithSMTest.class);
  private static final List<String> PQ_TEMPLATE_PATHS =
      List.of(
          "pm-templates/pq/[sm] user-creation-with-ps-and-setup-pq.postman_collection.json",
          "pm-templates/pq/pre-salesRep.postman_collection.json",
          "pm-templates/pq/[sm] pq.postman_collection.json");
  private static final String PQ_ENV_PATH = "pm-templates/pq/pq-env.postman_environment.json";
  private static final String PQ_URI_PATH = "commerce/quotes/actions/place";
  private static final String COMPOSITE_GRAPH_URI_PATH = "composite/graph";
  private static final String IS_SYNC_HEADER = "isSync";
  private static final String SYNC_ERROR_FOLDER_NAME = "errors|>sync";

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
                .customDynamicVariableGenerator( // <5>
                    "$unitPrice",
                    (ignore1, ignore2, ignore3) -> String.valueOf(Random.Default.nextInt(999) + 1))
                .nodeModulesRelativePath("js") // <6>
                .haltOnFailureOfTypeExcept(
                    HTTP_STATUS, afterStepContainingHeader("ignoreHTTPStatusUnsuccessful")) // <7>
                .requestConfig( // <8>
                    unmarshallRequest(
                        beforeStepContainingURIPathOfAny(PQ_URI_PATH),
                        PlaceQuoteInputRepresentation.class,
                        adapter(PlaceQuoteInputRepresentation.class)))
                .responseConfig( // <9>
                    unmarshallResponse(
                        afterStepContainingURIPathOfAny(PQ_URI_PATH),
                        PlaceQuoteOutputRepresentation.class),
                    unmarshallResponse(
                        afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
                        CompositeGraphResponse.class,
                        CompositeGraphResponse.ADAPTER))
                .hooks( // <10>
                    pre(
                        beforeStepContainingURIPathOfAny(PQ_URI_PATH),
                        (step, requestInfo, rundown) -> {
                          if (requestInfo.containsHeader(IS_SYNC_HEADER)) {
                            LOGGER.info("This is a Sync step: {}", step);
                          }
                        }),
                    post(
                        afterStepContainingURIPathOfAny(PQ_URI_PATH),
                        (stepReport, ignore) -> {
                          validatePQResponse(stepReport); // <11>
                          final var isSyncStep =
                              stepReport.responseInfo.get().containsHeader(IS_SYNC_HEADER);
                          if (!isSyncStep) {
                            LOGGER.info(
                                "Waiting in PostHook of the Async Step: {}, for the Quote's Asynchronous processing to finish",
                                stepReport.step);
                            // ! CAUTION 10/09/23 gopala.akshintala: This can be flaky until
                            // polling is implemented
                            Thread.sleep(5000);
                          }
                        }),
                    post(
                        afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
                        (stepReport, ignore) -> validateCompositeGraphResponse(stepReport)),
                    post(
                        afterStepName("query-quote-and-related-records"),
                        (ignore, rundown) -> assertAfterPQCreate(rundown.mutableEnv)))
                .globalCustomTypeAdapter(new IDAdapter()) // <12>
                .insecureHttp(true) // <13>
                .off()); // Kick-off
    assertThat(pqRundown.firstUnIgnoredUnsuccessfulStepReport()).isNull(); // <14>
    assertThat(pqRundown.mutableEnv)
        .containsAtLeastEntriesIn(
            Map.of(
                "quoteCalculationStatusForSkipPricing", PricingPref.Skip.completeStatus,
                "quoteCalculationStatus", PricingPref.System.completeStatus,
                "quoteCalculationStatusAfterAllUpdates", PricingPref.System.completeStatus));
    // end::pq-e2e-with-revoman-config-demo[]
  }

  private static void validatePQResponse(StepReport stepReport) {
    final var pqInputRep =
        stepReport.responseInfo.get().<PlaceQuoteOutputRepresentation>getTypedTxnObj();
    final var successRespProp = pqInputRep.getSuccess();
    final var isStepExpectedToFail = stepReport.step.isInFolder(SYNC_ERROR_FOLDER_NAME);
    assertThat(successRespProp).isEqualTo(!isStepExpectedToFail);
  }

  private static void validateCompositeGraphResponse(StepReport stepReport) {
    final var responseTxnInfo = stepReport.responseInfo.get();
    final var graphResp =
        responseTxnInfo.<CompositeGraphResponse>getTypedTxnObj().getGraphs().get(0);
    assertTrue(
        graphResp.isSuccessful(),
        () -> {
          final var firstErrorResponseBody = ((ErrorGraph) graphResp).firstErrorResponseBody;
          return String.format(
              "Unsuccessful Composite Graph response%n{%n  first errorCode: %s%n  first errorMessage: %s%n}%n%s",
              firstErrorResponseBody.getErrorCode(),
              firstErrorResponseBody.getMessage(),
              responseTxnInfo.httpMsg.toMessage());
        });
  }

  private static void assertAfterPQCreate(PostmanEnvironment<Object> env) {
    // Quote: LineItemCount, quoteCalculationStatus
    assertThat(env).containsEntry("lineItemCount", 10);
    final var pricingPrefFromEnv = env.getString("$pricingPref");
    final var actualCompleteStatus =
        Arrays.stream(PricingPref.values())
            .filter(e -> e.name().equalsIgnoreCase(pricingPrefFromEnv))
            .findFirst()
            .map(e -> e.completeStatus);
    assertThat(env).containsEntry("quoteCalculationStatus", actualCompleteStatus.get());
    // QLIs: Product2Id
    final var productIdsFromEnv = env.valuesForKeysEndingWith(String.class, "ProductId");
    final var productIdsFromCreatedQLIs =
        env.valuesForKeysStartingWith(String.class, "productForQLI");
    assertThat(productIdsFromCreatedQLIs).containsAtLeastElementsIn(productIdsFromEnv);
    // QLRs: QuoteId
    final var quoteIdFromQLRs = env.valuesForKeysStartingWith(String.class, "quoteForQLR");
    assertThat(quoteIdFromQLRs).containsExactly(env.getString("quoteId"));
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

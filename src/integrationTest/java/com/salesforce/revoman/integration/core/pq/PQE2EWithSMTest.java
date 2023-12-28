/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or http://www.apache.org/licenses/LICENSE-2.0
 ******************************************************************************/

package com.salesforce.revoman.integration.core.pq;

import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.HookConfig.pre;
import static com.salesforce.revoman.input.config.RequestConfig.unmarshallRequest;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallSuccessResponse;
import static com.salesforce.revoman.input.config.ResponseConfig.validateIfFailed;
import static com.salesforce.revoman.input.config.ResponseConfig.validateIfSuccess;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static com.salesforce.revoman.output.report.TxInfo.getPath;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick;
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick;
import com.salesforce.revoman.integration.core.pq.connect.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.output.report.TxInfo;
import com.salesforce.vador.config.ValidationConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final String PQ_PATH = "commerce/quotes/actions/place";
  private static final Set<String> FAILURE_STEPS =
      Set.of("pq-create-without-quote (sync-error)", "pq-update-invalid-method (sync-error)");
  private static final Set<String> STEPS_TO_IGNORE_FOR_FAILURE =
      Set.of(
          "MockTaxAdapter",
          "TaxEngineProvider",
          "Proration Policy",
          "OneTime PSM",
          "Evergreen PSM",
          "Termed PSM",
          "ProductRelationshipType",
          "pq-create-without-quote (sync-error)",
          "pq-update-invalid-method (sync-error)",
          "pq-create-invalid-ql-field");
  private static final List<String> PQ_TEMPLATE_PATHS =
      List.of(
          "pm-templates/pq/user-creation-and-setup-pq.postman_collection.json",
          "pm-templates/pq/pre-salesRep.postman_collection.json",
          "pm-templates/pq/pq-sm.postman_collection.json");
  private static final PreTxnStepPick PRE_TXN_PICK_FOR_ASYNC_STEPS =
      (ignore1, requestInfo, ignore2) -> PQ_PATH.equalsIgnoreCase(getPath(requestInfo));
  private static final PostTxnStepPick POST_TXN_PICK_FOR_ASYNC_STEPS =
      (stepName, stepReport, rundown) ->
          stepReport.getRequestInfo().map(TxInfo::getPath).contains(PQ_PATH);

  @Test
  void revUpPQ() {
    final var validatePQSuccessResponse =
        ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
            .withValidator(
                (resp -> Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "sync-failure"),
                "success")
            .prepare();
    final var validatePQErrorResponse =
        ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
            .withValidator(
                (resp -> Boolean.FALSE.equals(resp.getSuccess()) ? "sync-failure" : "success"),
                "sync-failure")
            .prepare();
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
                .requestConfig( // <6>
                    unmarshallRequest(
                        PRE_TXN_PICK_FOR_ASYNC_STEPS,
                        PlaceQuoteInputRepresentation.class,
                        adapter(PlaceQuoteInputRepresentation.class)))
                .haltOnAnyFailureExceptForSteps(STEPS_TO_IGNORE_FOR_FAILURE) // <7>
                .hooks( // <8>
                    pre(
                        PRE_TXN_PICK_FOR_ASYNC_STEPS,
                        (stepName, requestInfo, rundown) -> {
                          final var pqInputRep =
                              requestInfo.<PlaceQuoteInputRepresentation>getTypedTxObj();
                          assertThat(pqInputRep).isNotNull();
                          if ("pq-create: qli+qlr (skip-pricing)"
                              .equals(pqInputRep.getGraph().getGraphId())) {
                            LOGGER.info("Skip pricing for step: {}", stepName);
                            rundown.mutableEnv.set("$pricingPref", PricingPref.Skip.toString());
                          } else {
                            rundown.mutableEnv.set("$pricingPref", PricingPref.System.toString());
                          }
                        }),
                    post(
                        "query-quote-and-related-records",
                        (ignore1, ignore2, rundown) -> assertAfterPQCreate(rundown)),
                    post(
                        POST_TXN_PICK_FOR_ASYNC_STEPS,
                        (stepName, ignore, rundown) -> {
                          LOGGER.info(
                              "Waiting in PostHook for Step: {} for the Quote to get processed",
                              stepName);
                          // ! CAUTION 10/09/23 gopala.akshintala: This test can be flaky until
                          // polling is implemented
                          Thread.sleep(5000);
                        }))
                .responseConfig( // <9>
                    unmarshallSuccessResponse(
                        "quote-related-records", CompositeResponse.class), // <9.1>
                    validateIfSuccess( // <9.2>
                        POST_TXN_PICK_FOR_ASYNC_STEPS,
                        PlaceQuoteOutputRepresentation.class,
                        validatePQSuccessResponse),
                    validateIfFailed(
                        FAILURE_STEPS,
                        PlaceQuoteOutputRepresentation.class,
                        validatePQErrorResponse))
                .insecureHttp(true) // <10>
                .off()); // Kick-off
    assertThat(pqRundown.firstUnIgnoredUnsuccessfulStepNameToReportInOrder()).isNull();
    assertThat(pqRundown.mutableEnv)
        .containsAllEntriesOf(
            Map.of(
                "quoteCalculationStatusForSkipPricing", PricingPref.Skip.completeStatus,
                "quoteCalculationStatus", PricingPref.System.completeStatus,
                "quoteCalculationStatusAfterAllUpdates", PricingPref.System.completeStatus));
    // end::pq-e2e-with-revoman-config-demo[]
  }

  static void assertAfterPQCreate(Rundown pqCreateQLIQLR) {
    final var env = pqCreateQLIQLR.mutableEnv;
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
    // QLRs: QuoteId, MainQuoteLineId, AssociatedQuoteLineId
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

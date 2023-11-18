/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/

package com.salesforce.revoman.integration.core.pq;

import static com.salesforce.revoman.input.HookConfig.post;
import static com.salesforce.revoman.input.HookConfig.pre;
import static com.salesforce.revoman.input.RequestConfig.unmarshallRequest;
import static com.salesforce.revoman.input.ResponseConfig.unmarshallSuccessResponse;
import static com.salesforce.revoman.input.ResponseConfig.validateIfFailed;
import static com.salesforce.revoman.input.ResponseConfig.validateIfSuccess;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.Kick;
import com.salesforce.revoman.integration.core.pq.connect.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.output.Rundown;
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
class PQE2ETest {
  private static final Logger LOGGER = LoggerFactory.getLogger(PQE2ETest.class);
  private static final Set<String> ASYNC_STEP_NAMES =
      Set.of(
          "pq-create: qli+qlr (skip-pricing)",
          "pq-create: qli+qlr",
          "pq-update: qli(post+patch+delete) + qlr",
          "pq-update: qli(post+patch)",
          "pq-update: qli(all post)",
          "pq-update: qli+qlr(all post)",
          "pq-update: qli(all patch)",
          "pq-update: qli(all delete)",
          "pq-update: qli(post+delete)");
  private static final Set<String> FAILURE_STEP_NAMES =
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
          "pm-templates/pq/pq-with-rc.postman_collection.json");

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
                        ASYNC_STEP_NAMES,
                        PlaceQuoteInputRepresentation.class,
                        adapter(PlaceQuoteInputRepresentation.class)))
                .haltOnAnyFailureExceptForSteps(STEPS_TO_IGNORE_FOR_FAILURE) // <7>
                .hooks( // <8>
                    pre(
                        ASYNC_STEP_NAMES,
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
                    post("query-quote-and-related-records", PQE2ETest::assertAfterPQCreate),
                    post(
                        ASYNC_STEP_NAMES,
                        (stepName, rundown) -> {
                          LOGGER.info(
                              "Waiting after Step: {} for the Quote: {} to get processed",
                              stepName,
                              rundown.mutableEnv.getString("quoteId"));
                          // ! CAUTION 10/09/23 gopala.akshintala: This test can be flaky until
                          // polling is implemented
                          Thread.sleep(20000);
                        }))
                .responseConfig( // <9>
                    unmarshallSuccessResponse(
                        "quote-related-records", CompositeResponse.class), // <9.1>
                    validateIfSuccess( // <9.2>
                        ASYNC_STEP_NAMES,
                        PlaceQuoteOutputRepresentation.class,
                        validatePQSuccessResponse),
                    validateIfFailed(
                        FAILURE_STEP_NAMES,
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

  static void assertAfterPQCreate(String stepName, Rundown pqCreateQLIQLR) {
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
    assertThat(env.valuesForKeysStartingWith(String.class, "mainQuoteLine+associatedQuoteLine"))
        .containsOnly(
            env.get("qliCreated1Id") + "-" + env.get("qliCreated2Id"),
            env.get("qliCreated1Id") + "-" + env.get("qliCreated3Id"),
            env.get("qliCreated1Id") + "-" + env.get("qliCreated4Id"));
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

/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/

package com.salesforce.revoman.integration.core.pq;

import static com.salesforce.revoman.input.HookConfig.post;
import static com.salesforce.revoman.input.HookConfig.pre;
import static com.salesforce.revoman.input.ResponseConfig.unmarshallSuccessResponse;
import static com.salesforce.revoman.input.ResponseConfig.validateIfSuccess;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.Kick;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.vador.config.ValidationConfig;
import io.vavr.control.Try;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import kotlin.collections.MapsKt;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PQE2ETest {

  private static final Logger LOGGER = LoggerFactory.getLogger(PQE2ETest.class);

  // tag::pq-e2e-with-revoman-demo[]

  /**
   * PQ E2E Flow
   *
   * <ul>
   *   <li>pq-create: qli+qlr
   *   <li>query-quote-and-related-records
   *   <li>pq-update: qli(post+patch+delete)
   *   <li>pq-update: qli(post+patch)
   *   <li>pq-update: qli(all post)
   *   <li>pq-update: qli+qlr(all post
   *   <li>pq-update: qli(all patch)
   *   <li>pq-update: qli(all delete)
   *   <li>pq-update: qli(post+delete)
   * </ul>
   */
  @Test
  void revUpPQ() {
    final var pqRespValidationConfig =
        ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
            .withValidator(
                (resp -> Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "PQ failed"),
                "success");
    // ! TODO 24/06/23 gopala.akshintala: Need fully qualified names as POST and GET reside next to
    // ! each-other. Improve this using Regex
    final var unsuccessfulStepsException =
        Set.of(
            "POST: setup|>tax-setup|>MockTaxAdapter",
            "POST: setup|>tax-setup|>TaxEngineProvider",
            "POST: setup|>product-setup|>pre|>Proration Policy",
            "POST: setup|>product-setup|>OneTime|>OneTime PSM",
            "POST: setup|>product-setup|>Evergreen|>Evergreen PSM",
            "POST: setup|>product-setup|>Termed|>Termed PSM",
            "POST: setup|>bundle-setup|>ProductRelationshipType");
    // tag::pq-e2e-with-revoman-config-demo[]
    final var pqRunDown =
        ReVoman.revUp( // <1>
            Kick.configure()
                .templatePath("pm-templates/pq/pq (rc).postman_collection.json") // <2>
                .environmentPath("pm-templates/pq/pq-env.postman_environment.json") // <3>
                .dynamicEnvironment( // <4>
                    Map.of(
                        "$quoteFieldsToQuery", "LineItemCount, CalculationStatus",
                        "$qliFieldsToQuery", "Id, Product2Id",
                        "$qlrFieldsToQuery", "Id, QuoteId, MainQuoteLineId, AssociatedQuoteLineId"))
                .customDynamicVariable( // <5>
                    "$pricingPref",
                    ignore ->
                        PricingPref.values()[new Random().nextInt(PricingPref.values().length)]
                            .name())
                .hooks( // <6>
                    post(
                        "password-reset",
                        rundown ->
                            LOGGER.info(
                                "Step count executed including this step: "
                                    + rundown.stepNameToReport.size())),
                    pre(
                        "pq-create-with-bundles",
                        rundown ->
                            LOGGER.info(
                                "Step count executed before this step: "
                                    + rundown.stepNameToReport.size())),
                    post("query-quote-and-related-records", PQE2ETest::assertAfterPQCreate),
                    post(
                        Set.of(
                            "pq-create: qli+qlr",
                            "pq-update: qli(post+patch+ delete)",
                            "pq-update: qli(post+patch)",
                            "pq-update: qli(all post)",
                            "pq-update: qli+qlr(all post)",
                            "pq-update: qli(all patch)",
                            "pq-update: qli(all delete)",
                            "pq-update: qli(post+delete)"),
                        rundown -> {
                          LOGGER.info(
                              "Waiting for the Quote: {} to get processed",
                              rundown.environment.getString("quoteId"));
                          Try.run(() -> Thread.sleep(10000));
                        }))
                .haltOnAnyFailureExceptForSteps(unsuccessfulStepsException) // <7>
                .responseConfig(
                    unmarshallSuccessResponse( // <8>
                        "quote-related-records", CompositeResponse.class),
                    validateIfSuccess( // <9>
                        "pq-create-with-bundles",
                        PlaceQuoteOutputRepresentation.class,
                        pqRespValidationConfig))
                .insecureHttp(true) // <10>
                .off()); // Kick-off
    // end::pq-e2e-with-revoman-config-demo[]
    MapsKt.filterKeys(
            pqRunDown.stepNameToReport, stepName -> !unsuccessfulStepsException.contains(stepName))
        .values()
        .forEach(
            stepReport ->
                assertThat(stepReport.isSuccessful())
                    .as(
                        String.format(
                            "***** REQUEST:%s\n***** RESPONSE:%s",
                            stepReport.getRequestData().toMessage(),
                            (stepReport.getResponseData() != null)
                                ? stepReport.getResponseData().toMessage()
                                : "empty"))
                    .isTrue());
    assertThat(pqRunDown.environment.get("quoteCalculationStatus"))
        .isEqualTo(PricingPref.valueOf(pqRunDown.environment.getString("$pricingPref")).completeStatus);
  }
  
  static void assertAfterPQCreate(Rundown pqCreate_qli_qlr) {
    final var environment = pqCreate_qli_qlr.environment;
    // Quote: LineItemCount, quoteCalculationStatus
    assertThat(environment.getInt("lineItemCount")).isEqualTo(10);
    assertThat(environment.get("quoteCalculationStatus"))
        .isEqualTo(
            PricingPref.valueOf(environment.getString("$pricingPref"))
                .completeStatus);
    // QLIs: Product2Id
    final var productIdsFromEnv =
        environment.getValuesForKeysEndingWith(String.class, "ProductId");
    final var productIdsFromCreatedQLIs =
        environment.getValuesForKeysStartingWith(String.class, "productForQLI");
    assertThat(productIdsFromCreatedQLIs).containsAll(productIdsFromEnv);
    // QLRs: QuoteId, MainQuoteLineId, AssociatedQuoteLineId
    final var quoteIdFromQLRs =
        environment.getValuesForKeysStartingWith(String.class, "quoteForQLR");
    assertThat(quoteIdFromQLRs).containsOnly(environment.getString("quoteId"));
    assertThat(
        environment.getValuesForKeysStartingWith(String.class, "mainQuoteLine+associatedQuoteLine"))
        .containsOnly(
            environment.get("qliCreated1Id")
                + "-"
                + environment.get("qliCreated2Id"),
            environment.get("qliCreated1Id")
                + "-"
                + environment.get("qliCreated3Id"),
            environment.get("qliCreated1Id")
                + "-"
                + environment.get("qliCreated4Id"));
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

/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/

package org.revcloud.revoman.integration.core.pq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.revoman.input.HookConfig.post;
import static org.revcloud.revoman.input.HookConfig.pre;
import static org.revcloud.revoman.input.ResponseConfig.unmarshallSuccessResponse;
import static org.revcloud.revoman.input.ResponseConfig.validateIfSuccess;

import com.salesforce.vador.config.ValidationConfig;
import io.vavr.control.Try;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import kotlin.collections.MapsKt;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PQE2ETest {

  private static final Logger LOGGER = LoggerFactory.getLogger(PQE2ETest.class);

  // tag::pq-e2e-with-revoman-demo[]
  @Test
  void revUpPQ() {
    final var pqRespValidationConfig =
        ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
            .withValidator(
                (resp -> Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "PQ failed"),
                "success");
    // ! TODO 24/06/23 gopala.akshintala: Need fully qualified names as POST and GET reside next to
    // each-other. Improve this using Regex
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
    final var pqApiCreateWithBundles =
        ReVoman.revUp( // <1>
            Kick.configure()
                .templatePath("pm-templates/pq/pq-api-create.postman_collection.json") // <2>
                .environmentPath("pm-templates/pq/pq-env.postman_environment.json") // <3>
                .dynamicEnvironment(
                    Map.of( // <4>
                        "$quoteFieldsToQuery", "CalculationStatus",
                        "$qliFieldsToQuery", "Id, Product2Id",
                        "$qlrFieldsToQuery", "Id, QuoteId, MainQuoteLineId, AssociatedQuoteLineId"))
                .customDynamicVariable(
                    "$pricingPref",
                    ignore ->
                        PricingPref.values()[new Random().nextInt(PricingPref.values().length)]
                            .name()) // <5>
                .hooks(
                    List.of( // <6>
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
                                        + rundown.stepNameToReport.size()))))
                .haltOnAnyFailureExceptForSteps(unsuccessfulStepsException) // <7>
                .responseConfig(
                    List.of(
                        unmarshallSuccessResponse("quote-related-records", CompositeResponse.class), // <8>
                            validateIfSuccess(
                                "pq-create-with-bundles",
                                PlaceQuoteOutputRepresentation.class,
                                pqRespValidationConfig))) // <9>
                .insecureHttp(true) // <10>
                .off()); // Kick-off
    // end::pq-e2e-with-revoman-config-demo[]
    MapsKt.filterKeys(
            pqApiCreateWithBundles.stepNameToReport,
            stepName -> !unsuccessfulStepsException.contains(stepName))
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
    // Assert Product2Id For QLIs
    final var productIdsFromEnv =
        pqApiCreateWithBundles.environment.getValuesForKeysEndingWith("ProductId");
    final var productIdsFromCreatedQLIs =
        pqApiCreateWithBundles.environment.getValuesForKeysStartingWith("productForQLI");
    assertThat(productIdsFromCreatedQLIs).containsAll(productIdsFromEnv);
    // Assert QuoteId on QLRs
    final var quoteIdFromQLRs =
        pqApiCreateWithBundles.environment.getValuesForKeysStartingWith("quoteForQLR");
    assertThat(quoteIdFromQLRs).containsOnly(pqApiCreateWithBundles.environment.get("quoteId"));
    // Assert MainQuoteLineId, AssociatedQuoteLineId on QLRs
    assertThat(
            pqApiCreateWithBundles.environment.getValuesForKeysStartingWith(
                "mainQuoteLineForQLR", "associatedQuoteLineForQLR"))
        .containsOnly(
            pqApiCreateWithBundles.environment.get("qliCreated1Id"),
            pqApiCreateWithBundles.environment.get("qliCreated4Id"));
    Try.run(
        () -> {
          Thread.sleep(10000); // The below check has to wait for an async process to complete
          assertThat(pqApiCreateWithBundles.environment.get("quoteCalculationStatus"))
              .isEqualTo(
                  PricingPref.valueOf(pqApiCreateWithBundles.environment.get("$pricingPref"))
                      .completeStatus);
        });
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

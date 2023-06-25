package org.revcloud.revoman.integration.core.pq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.revoman.input.SuccessConfig.successType;
import static org.revcloud.revoman.input.SuccessConfig.validateIfSuccess;
import static org.revcloud.revoman.integration.TestConstantsKt.TEST_RESOURCES_PATH;

import com.salesforce.vador.config.ValidationConfig;
import java.util.Map;
import java.util.Set;
import kotlin.collections.MapsKt;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.revcloud.revoman.response.types.salesforce.CompositeResponse;

class PQE2ETest {

  @Test
  void revUpPQ() { 
    final var pqRespValidationConfig = ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
        .withValidator((resp -> Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "PQ failed"), "success");
    final var unsuccessfulStepsException = Set.of("POST: setup|>tax-setup|>MockTaxAdapter", "POST: setup|>tax-setup|>TaxEngineProvider", "POST: setup|>product-setup|>pre|>Proration Policy", "POST: setup|>product-setup|>OneTime|>OneTime PSM", "POST: setup|>product-setup|>Evergreen|>Evergreen PSM", "POST: setup|>product-setup|>Termed|>Termed PSM", "POST: setup|>bundle-setup|>ProductRelationshipType");
    final var pqApiCreateWithBundles = ReVoman.revUp(
        Kick.configure()
            .insecureHttp(true)
            // ! TODO 24/06/23 gopala.akshintala: Need fully qualified names as POST and GET reside next to each-other. Improve this using Regex 
            .haltOnAnyFailureExceptForSteps(unsuccessfulStepsException)
            .templatePath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-api-create.postman_collection.json")
            .environmentPath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-env.postman_environment.json")
            .customDynamicVariables(Map.of(
                "$qliFieldsToQuery", ignore -> "Id, Product2Id",
                "$qlrFieldsToQuery", ignore -> "Id, QuoteId, MainQuoteLineId, AssociatedQuoteLineId"))
            .stepNameToSuccessConfig(Map.of(
                "pq-create-with-bundles", validateIfSuccess(PlaceQuoteOutputRepresentation.class, pqRespValidationConfig),
                "quote-related-records", successType(CompositeResponse.class))).off());
    MapsKt.filterKeys(pqApiCreateWithBundles.stepNameToReport, stepName -> !unsuccessfulStepsException.contains(stepName)).values().forEach(stepReport ->
        assertThat(stepReport.isSuccessful())
            .as(String.format("***** REQUEST:%s\n***** RESPONSE:%s", stepReport.getRequestData().toMessage(), (stepReport.getResponseData() != null) ? stepReport.getResponseData().toMessage() : "empty"))
            .isTrue());
    // Assert Product2Id For QLIs
    final var productIdsFromEnv = pqApiCreateWithBundles.environment.getValuesForKeysEndingWith("ProductId");
    final var productIdsFromCreatedQLIs = pqApiCreateWithBundles.environment.getValuesForKeysStartingWith("productForQLI");
    assertThat(productIdsFromCreatedQLIs).containsAll(productIdsFromEnv);
    // Assert QuoteId on QLRs
    final var quoteIdFromQLRs = pqApiCreateWithBundles.environment.getValuesForKeysStartingWith("quoteForQLR");
    assertThat(quoteIdFromQLRs).containsOnly(pqApiCreateWithBundles.environment.get("quoteId"));
    // Assert MainQuoteLineId, AssociatedQuoteLineId on QLRs
    assertThat(pqApiCreateWithBundles.environment.getValuesForKeysStartingWith("mainQuoteLineForQLR", "associatedQuoteLineForQLR"))
        .containsOnly(pqApiCreateWithBundles.environment.get("qliCreated1Id"), pqApiCreateWithBundles.environment.get("qliCreated4Id"));
  }
}

package org.revcloud.revoman.integration.core.pq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.revoman.input.SuccessConfig.successType;
import static org.revcloud.revoman.input.SuccessConfig.validateIfSuccess;
import static org.revcloud.revoman.integration.TestConstantsKt.TEST_RESOURCES_PATH;

import com.salesforce.vador.config.ValidationConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.revcloud.revoman.response.types.salesforce.CompositeResponse;

class PQE2ETest {

  @Test
  void revUpPQ() { 
    final var pqRespValidationConfig = ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
        .withValidator((resp -> Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "PQ failed"), "success");
    final var pqCreateWithBundlesApi = ReVoman.revUp(
        Kick.configure()
            .insecureHttp(true)
            .haltOnAnyFailure(true)
            .templatePath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-api-create.postman_collection.json")
            .environmentPath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-env.postman_environment.json")
            .skipStep("quote-related-records")
            .stepNameToSuccessConfig(Map.of(
                "pq-create-with-bundles", validateIfSuccess(PlaceQuoteOutputRepresentation.class, pqRespValidationConfig)))
            .bearerTokenKey("accessToken").off());
    pqCreateWithBundlesApi.stepNameToReport.values().forEach(stepReport ->
        assertThat(stepReport.isSuccessful())
            .as(String.format("***** REQUEST:%s\n***** RESPONSE:%s", stepReport.getRequestData().toMessage(), (stepReport.getResponseData() != null) ? stepReport.getResponseData().toMessage() : "empty"))
            .isTrue());
  }
}

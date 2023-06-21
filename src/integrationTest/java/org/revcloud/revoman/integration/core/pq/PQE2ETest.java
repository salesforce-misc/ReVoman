package org.revcloud.revoman.integration.core.pq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.revoman.input.SuccessConfig.validateIfSuccess;
import static org.revcloud.revoman.integration.TestConstantsKt.TEST_RESOURCES_PATH;

import com.salesforce.vador.config.ValidationConfig;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;

class PQE2ETest {

  @Test
  void revUpPQ() {
/*    final var userCreationRundown = ReVoman.revUp(
        Kick.configure()
            .insecureHttp(true)
            .templatePath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-user-creation.postman_collection.json")
            .environmentPath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-env.postman_environment.json")
            .bearerTokenKey("accessToken").off());*/
    final var pqSetup = ReVoman.revUp(
        Kick.configure()
            .insecureHttp(true)
            .templatePath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-setup.postman_collection.json")
            .environmentPath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-env.postman_environment.json")
            //.dynamicEnvironment(userCreationRundown.environment)
            .bearerTokenKey("accessToken").off());
    final var pqRespValidationConfig = ValidationConfig.<PlaceQuoteOutputRepresentation, String>toValidate()
        .withValidator((resp -> Boolean.TRUE.equals(resp.getSuccess()) ? "success" : "PQ failed"), "success");
    final var pqApi = ReVoman.revUp(
        Kick.configure()
            .insecureHttp(true)
            .haltOnAnyFailure(true)
            .templatePath(TEST_RESOURCES_PATH + "pm-templates/pq/pq-api-create.postman_collection.json")
            .dynamicEnvironment(pqSetup.environment)
            .stepNameToSuccessConfig(Map.of(
                "pq-create", validateIfSuccess(PlaceQuoteOutputRepresentation.class, pqRespValidationConfig),
                "pq-update", validateIfSuccess(PlaceQuoteOutputRepresentation.class, pqRespValidationConfig),
                "pq-create-with-bundles", validateIfSuccess(PlaceQuoteOutputRepresentation.class, pqRespValidationConfig)))
            .bearerTokenKey("accessToken").off());
    pqApi.stepNameToReport.values().forEach(stepReport ->
        assertThat(stepReport.isSuccessful())
            .as(String.format("***** REQUEST:%s\n***** RESPONSE:%s", stepReport.getRequestData().toMessage(), (stepReport.getResponseData() != null) ? stepReport.getResponseData().toMessage() : "empty"))
            .isTrue());
  }
}

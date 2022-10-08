package org.revcloud.integration.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.vador.config.ValidationConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.revcloud.ReVoman;
import org.revcloud.input.Kick;
import org.revcloud.output.StepReport;
import org.revcloud.response.types.salesforce.Graph;
import org.revcloud.response.types.salesforce.Graphs;

class BillingScheduleE2ETest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void revUp() {
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/revoman/ReVoman.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH
            + "pm-templates/revoman/ReVoman (UTest - Linux).postman_environment.json";
    // ! TODO gopala.akshintala 01/08/22: Detect Batch and apply for each Graph
    final var setupGraphsValidationConfig =
        ValidationConfig.<Graphs, String>toValidate()
            .withValidator(
                (graphs ->
                    graphs.getGraphs().stream().allMatch(Graph::isSuccessful)
                        ? "Success"
                        : "setup-graph (once) Failed"),
                "Success");
    final var bsValidationConfig =
        ValidationConfig.<BillingScheduleListOutputRepresentation, String>toValidate()
            .withValidator(
                (bsLOR ->
                    bsLOR.getBillingScheduleResultsList().stream()
                            .allMatch(BillingScheduleOutputRepresentation::getSuccess)
                        ? "Success"
                        : "BS Failed"),
                "Success");
    final var rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(pmCollectionPath)
                .environmentPath(pmEnvironmentPath)
                .bearerTokenKey("accessToken")
                .stepNameToSuccessType(
                    Map.of("billing-schedule", BillingScheduleListOutputRepresentation.class))
                .stepNameToValidationConfig(
                    Map.of(
                        "setup-graph (once)", setupGraphsValidationConfig,
                        "billing-schedule", bsValidationConfig))
                .off());
    assertThat(rundown.environment)
        .containsKeys(
            "orderId",
            "billingTreatmentId",
            "billingTreatmentItemId",
            "orderItem1Id",
            "orderItem2Id",
            "orderItem3Id",
            "orderItem4Id");
    assertThat(rundown.stepNameToReport.values()).allMatch(StepReport::isSuccessful);
  }
}

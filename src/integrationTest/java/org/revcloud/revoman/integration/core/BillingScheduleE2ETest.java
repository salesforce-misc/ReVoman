package org.revcloud.revoman.integration.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.revoman.input.SuccessConfig.validateIfSuccess;

import com.salesforce.vador.config.ValidationConfig;
import java.util.List;
import java.util.Map;
import com.squareup.moshi.Types;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.revcloud.revoman.input.SuccessConfig;
import org.revcloud.revoman.output.StepReport;
import org.revcloud.revoman.response.types.salesforce.Graph;
import org.revcloud.revoman.response.types.salesforce.Graphs;

class BillingScheduleE2ETest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void revUp() {
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/revoman/reVoman.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH
            + "pm-templates/revoman/reVoman (UTest - Linux).postman_environment.json";
    final var orderItem2BSIASuccessType = Types.newParameterizedType(List.class, OrderItemToBSIAResponse.class);
    final var orderItem2BSIAValidationConfig = 
        ValidationConfig.<List<OrderItemToBSIAResponse>, String>toValidate()
        .withValidator((resp -> Boolean.TRUE.equals(resp.get(0).isSuccess()) ? "success" : " OrderItem2BS IA failed"), "success");
    final var rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(pmCollectionPath)
                .environmentPath(pmEnvironmentPath)
                .bearerTokenKey("accessToken")
                .stepNameToSuccessConfig(Map.of(
                        "OrderItem2BS IA", validateIfSuccess(orderItem2BSIASuccessType, orderItem2BSIAValidationConfig)))
                .off());
    assertThat(rundown.stepNameToReport.values()).allMatch(StepReport::isSuccessful);
  }
}

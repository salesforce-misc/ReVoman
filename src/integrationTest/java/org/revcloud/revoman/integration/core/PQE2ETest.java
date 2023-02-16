package org.revcloud.revoman.integration.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.revcloud.revoman.output.StepReport;

class PQE2ETest {
  private static final String TEST_RESOURCES_PATH = "src/integrationTest/resources/";

  @Test
  void revUp() {
    final var pmCollectionPath =
        TEST_RESOURCES_PATH + "pm-templates/pq/pq.postman_collection.json";
    final var pmEnvironmentPath =
        TEST_RESOURCES_PATH
            + "pm-templates/pq/pq (UTest - Linux).postman_environment.json";
    final var rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(pmCollectionPath)
                .environmentPath(pmEnvironmentPath)
                .bearerTokenKey("accessToken")
                .off());
    //assertThat(rundown.stepNameToReport.values()).allMatch(StepReport::isSuccessful);
  }
}

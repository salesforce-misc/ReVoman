/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.restfulapidev;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.testsupport.DeterministicMockApi;
import com.salesforce.revoman.testing.http.MockHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestfulAPIDevTest {
  private static final String PM_COLLECTION_PATH =
      "pm-templates/v2/restfulapidev/restful-api.dev.postman_collection.json";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v2/restfulapidev/restful-api.dev.postman_environment.json";

  // tag::revoman-simple-demo[]
  @Test
  @DisplayName("restful-api.dev")
  void restfulApiDev() {
    final var api = new DeterministicMockApi();
    try (final var server = MockHttpServer.start(api)) {
      final var rundown =
          ReVoman.revUp( // <1>
              Kick.configure()
                  .templatePath(PM_COLLECTION_PATH) // <2>
                  .environmentPath(PM_ENVIRONMENT_PATH) // <3>
                  .dynamicEnvironment("baseUrl", server.getBaseUrl())
                  .nodeModulesPath("js")
                  .off());
      assertThat(rundown.firstUnsuccessfulStepReport()).isNull(); // <4>
      assertThat(rundown.stepReports).hasSize(4); // <5>
      assertThat(
              rundown.stepReports.stream()
                  .map(report -> report.requestInfo.get().httpMsg.getUri().toString())
                  .toList())
          .containsExactly(
              server.getBaseUrl() + "/objects",
              server.getBaseUrl() + "/objects",
              server.getBaseUrl() + "/objects/local-object-1",
              server.getBaseUrl() + "/objects/local-object-1")
          .inOrder();
      assertThat(
              server.requests().stream()
                  .map(request -> request.getMethod() + " " + request.getPath())
                  .toList())
          .containsExactly(
              "GET /objects",
              "POST /objects",
              "PATCH /objects/local-object-1",
              "GET /objects/local-object-1")
          .inOrder();
    }
  }
  // end::revoman-simple-demo[]
}

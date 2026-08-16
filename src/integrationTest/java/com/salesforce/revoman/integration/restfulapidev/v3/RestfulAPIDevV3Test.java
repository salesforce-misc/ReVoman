/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.restfulapidev.v3;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.testsupport.DeterministicMockApi;
import com.salesforce.revoman.output.Rundown;
import com.salesforce.revoman.testing.http.MockHttpServer;
import org.junit.jupiter.api.Test;

class RestfulAPIDevV3Test {
  private static final String PM_COLLECTION_PATH = "pm-templates/v3/restful-api.dev";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml";

  @Test
  void executeRestfulApiDevV3CollectionFromJava() {
    final var api = new DeterministicMockApi();
    try (final var server = MockHttpServer.start(api)) {
      final Rundown rundown =
          ReVoman.revUp(
              Kick.configure()
                  .templatePath(PM_COLLECTION_PATH)
                  .environmentPath(PM_ENVIRONMENT_PATH)
                  .dynamicEnvironment("baseUrl", server.getBaseUrl())
                  .nodeModulesPath("js")
                  .off());
      assertThat(rundown.firstUnsuccessfulStepReport()).isNull();
      assertThat(rundown.stepReports.size()).isEqualTo(4);
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
}

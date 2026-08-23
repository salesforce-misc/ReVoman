/*
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http;

import static com.google.common.truth.Truth.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.http4k.core.Response;
import org.http4k.core.Status;
import org.junit.jupiter.api.Test;

class MockHttpServerJavaContractTest {
  @Test
  void javaLambdaAndTryWithResourcesUseThePublicServer() throws Exception {
    try (var server = MockHttpServer.start(request -> Response.create(Status.OK).body("java"))) {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/java")).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("java");
      assertThat(server.requests()).hasSize(1);
      assertThat(server.requests().getFirst().getPath()).isEqualTo("/java");
    }
  }

  @SuppressWarnings("DataFlowIssue")
  @Test
  void javaNullResponseBecomesAnEmpty500() throws Exception {
    try (var server = MockHttpServer.start(request -> null)) {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/null")).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).isEmpty();
      assertThat(server.requests()).hasSize(1);
    }
  }
}

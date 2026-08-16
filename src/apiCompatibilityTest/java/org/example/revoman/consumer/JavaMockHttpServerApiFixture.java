/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer;

import com.salesforce.revoman.testing.http.MockHttpHandler;
import com.salesforce.revoman.testing.http.MockHttpServer;
import com.salesforce.revoman.testing.http.RecordedHttpRequest;
import com.salesforce.revoman.testing.http.RecordedNameValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.http4k.core.Method;
import org.http4k.core.Response;
import org.http4k.core.Status;

public final class JavaMockHttpServerApiFixture {
  static void consumeMockHttpServerFromJava() throws Exception {
    MockHttpHandler checkedHandler =
        request -> {
          throw new IOException("checked handler contract");
        };
    try (MockHttpServer server =
        MockHttpServer.start(request -> Response.create(Status.OK).body("ok"))) {
      String baseUrl = server.getBaseUrl();
      List<RecordedHttpRequest> requests = server.requests();
      for (RecordedHttpRequest request : requests) {
        Method method = request.getMethod();
        String path = request.getPath();
        List<RecordedNameValue> query = request.getQueryParameters();
        List<RecordedNameValue> headers = request.getHeaders();
        byte[] bytes = request.bodyBytes();
        String utf8 = request.bodyString();
        String utf16 = request.bodyString(StandardCharsets.UTF_16);
      }
      RecordedNameValue value = new RecordedNameValue("flag", null);
      String name = value.name();
      String nullableValue = value.value();
    }
    MockHttpServer checkedServer = MockHttpServer.start(checkedHandler);
    checkedServer.close();
  }
}

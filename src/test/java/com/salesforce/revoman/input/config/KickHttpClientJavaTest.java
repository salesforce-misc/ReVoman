/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.config;

import static com.google.common.truth.Truth.assertThat;

import org.http4k.core.Response;
import org.http4k.core.Status;
import org.junit.jupiter.api.Test;

class KickHttpClientJavaTest {
  @Test
  void javaLambdaSetsHttpClient() {
    final var kick =
        Kick.configure()
            .templatePath("x")
            .httpClient(req -> Response.create(Status.OK).body("{}"))
            .off();
    assertThat(kick.httpClient()).isNotNull();
    assertThat(kick.insecureHttp()).isFalse();
  }
}

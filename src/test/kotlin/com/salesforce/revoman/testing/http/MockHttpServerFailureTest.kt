/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.exe.prepareHttpClient
import java.io.IOException
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.junit.jupiter.api.Test

class MockHttpServerFailureTest {
  @Test
  fun `handler exceptions return empty 500, still record the request, and close cleanly`() {
    val first = IOException("first handler failure")
    val second = IllegalStateException("second handler failure")
    MockHttpServer.start { request ->
        if (request.uri.path == "/first") throw first else throw second
      }
      .use { server ->
        val client = prepareHttpClient(insecureHttp = false)

        val firstResponse = client(Request(GET, "${server.baseUrl}/first"))
        val secondResponse = client(Request(GET, "${server.baseUrl}/second"))

        assertThat(firstResponse.status).isEqualTo(INTERNAL_SERVER_ERROR)
        assertThat(firstResponse.bodyString()).isEmpty()
        assertThat(secondResponse.status).isEqualTo(INTERNAL_SERVER_ERROR)
        assertThat(secondResponse.bodyString()).isEmpty()
        assertThat(server.requests().map { it.path }).containsExactly("/first", "/second").inOrder()
      }
  }

  @Test
  fun `explicit handler 500 response passes through`() {
    MockHttpServer.start { Response(INTERNAL_SERVER_ERROR).body("intentional") }
      .use { server ->
        val response =
          prepareHttpClient(insecureHttp = false)(Request(GET, "${server.baseUrl}/intentional"))

        assertThat(response.status).isEqualTo(INTERNAL_SERVER_ERROR)
        assertThat(response.bodyString()).isEqualTo("intentional")
      }
  }
}

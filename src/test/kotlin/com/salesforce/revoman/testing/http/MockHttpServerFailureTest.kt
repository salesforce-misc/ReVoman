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
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.http4k.core.Body
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockHttpServerFailureTest {
  @Test
  fun `handler exceptions return empty 500 and close reports capture order`() {
    val first = IOException("first handler failure")
    val second = IllegalStateException("second handler failure")
    val server = MockHttpServer.start { request ->
      if (request.uri.path == "/first") throw first else throw second
    }
    val client = prepareHttpClient(insecureHttp = false)

    val firstResponse = client(Request(GET, "${server.baseUrl}/first"))
    val secondResponse = client(Request(GET, "${server.baseUrl}/second"))

    assertThat(firstResponse.status).isEqualTo(INTERNAL_SERVER_ERROR)
    assertThat(firstResponse.bodyString()).isEmpty()
    assertThat(secondResponse.status).isEqualTo(INTERNAL_SERVER_ERROR)
    assertThat(secondResponse.bodyString()).isEmpty()
    assertThat(server.requests().map { it.path }).containsExactly("/first", "/second").inOrder()
    val failure = assertThrows<IllegalStateException> { server.close() }
    assertThat(failure).hasMessageThat().contains("2 mock HTTP handler failures")
    assertThat(failure.cause).isSameInstanceAs(first)
    assertThat(failure.suppressed.asList()).containsExactly(second).inOrder()
    server.close()
  }

  @Test
  fun `explicit handler 500 response passes through without deferred failure`() {
    MockHttpServer.start { Response(INTERNAL_SERVER_ERROR).body("intentional") }
      .use { server ->
        val response =
          prepareHttpClient(insecureHttp = false)(Request(GET, "${server.baseUrl}/intentional"))

        assertThat(response.status).isEqualTo(INTERNAL_SERVER_ERROR)
        assertThat(response.bodyString()).isEqualTo("intentional")
      }
  }

  @Test
  fun `capture failure becomes empty 500 without recording a request or handler failure`() {
    val captureFailure = IOException("capture failed")
    val request = mockk<Request>()
    val (recordingHandler, server) = MockHttpServer.recordingHandlerForTest { Response(OK) }
    every { request.method } returns GET
    every { request.uri } returns Uri.of("/capture")
    every { request.body } throws captureFailure

    val response = recordingHandler(request)

    assertThat(response.status).isEqualTo(INTERNAL_SERVER_ERROR)
    assertThat(response.bodyString()).isEmpty()
    assertThat(server.requests()).isEmpty()
    server.close()
  }

  @Test
  fun `replay failure retains complete request but does not invoke handler or record handler failure`() {
    val replayFailure = IOException("replay failed")
    val request = mockk<Request>()
    val handlerCalled = AtomicBoolean()
    val (recordingHandler, server) =
      MockHttpServer.recordingHandlerForTest {
        handlerCalled.set(true)
        Response(OK)
      }
    every { request.method } returns GET
    every { request.uri } returns Uri.of("/replay")
    every { request.headers } returns emptyList()
    every { request.body } returns Body(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
    every { request.body(any<Body>()) } throws replayFailure

    val response = recordingHandler(request)

    assertThat(response.status).isEqualTo(INTERNAL_SERVER_ERROR)
    assertThat(response.bodyString()).isEmpty()
    assertThat(server.requests().map { it.path }).containsExactly("/replay")
    assertThat(handlerCalled.get()).isFalse()
    server.close()
  }

  @Test
  fun `handler errors escape without entering the failure ledger`() {
    val error = AssertionError("handler assertion")
    val (recordingHandler, server) = MockHttpServer.recordingHandlerForTest { throw error }

    val thrown = assertThrows<AssertionError> { recordingHandler(Request(GET, "/error")) }

    assertThat(thrown).isSameInstanceAs(error)
    server.close()
  }
}

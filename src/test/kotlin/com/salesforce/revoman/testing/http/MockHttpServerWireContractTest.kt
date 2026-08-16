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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.http4k.core.Body
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.CREATED
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockHttpServerWireContractTest {
  @Test
  fun `real wire preserves request and response on exact ephemeral IPv4 loopback`() {
    val requestBody = byteArrayOf(0, 1, 2, 127, -1)
    val handlerBody = AtomicReference<ByteArray>()
    MockHttpServer.start { request ->
        handlerBody.set(request.body.stream.readBytes())
        Response(CREATED).header("X-Reply", "first").header("X-Reply", "second").body("accepted")
      }
      .use { server ->
        MockHttpServer.start { Response(OK) }
          .use { second ->
            val firstUri = URI.create(server.baseUrl)
            val secondUri = URI.create(second.baseUrl)
            assertThat(firstUri.scheme).isEqualTo("http")
            assertThat(firstUri.host).isEqualTo("127.0.0.1")
            assertThat(firstUri.port).isGreaterThan(0)
            assertThat(secondUri.port).isNotEqualTo(firstUri.port)
          }

        val response =
          prepareHttpClient(insecureHttp = false)(
            Request(POST, "${server.baseUrl}/wire?term=hello%20world&tag=a&tag=b&flag&empty=")
              .header("X-Repeat", "first")
              .header("X-Repeat", "second")
              .body(Body(ByteBuffer.wrap(requestBody)))
          )

        assertThat(response.status).isEqualTo(CREATED)
        assertThat(response.headerValues("X-Reply")).containsExactly("first", "second").inOrder()
        assertThat(response.bodyString()).isEqualTo("accepted")
        val recorded = server.requests().single()
        assertThat(recorded.method).isEqualTo(POST)
        assertThat(recorded.path).isEqualTo("/wire")
        assertThat(recorded.queryParameters)
          .containsExactly(
            RecordedNameValue("term", "hello world"),
            RecordedNameValue("tag", "a"),
            RecordedNameValue("tag", "b"),
            RecordedNameValue("flag", null),
            RecordedNameValue("empty", ""),
          )
          .inOrder()
        assertThat(
            recorded.headers
              .filter { it.name.equals("X-Repeat", ignoreCase = true) }
              .mapNotNull(RecordedNameValue::value)
          )
          .containsExactly("first", "second")
          .inOrder()
        assertThat(recorded.bodyBytes()).isEqualTo(requestBody)
        assertThat(handlerBody.get()).isEqualTo(requestBody)
      }
  }

  @Test
  fun `close is idempotent and leaves stable observations readable`() {
    val client = prepareHttpClient(insecureHttp = false)
    val server = MockHttpServer.start { Response(OK) }
    val closedUrl = server.baseUrl
    val closedAddress = InetSocketAddress("127.0.0.1", URI.create(closedUrl).port)

    client(Request(POST, "$closedUrl/evidence"))
    val lastSnapshot = server.requests()
    server.close()
    server.close()

    assertThat(server.baseUrl).isEqualTo(closedUrl)
    assertThat(server.requests()).isEqualTo(lastSnapshot)
    assertThrows<IOException> {
      Socket().use { socket -> socket.connect(closedAddress, SOCKET_CONNECT_TIMEOUT_MILLIS) }
    }
  }

  @Test
  fun `close interrupts cooperative in-flight virtual worker before completing`() {
    val handlerStarted = CountDownLatch(1)
    val handlerInterrupted = CountDownLatch(1)
    val allowHandlerExit = CountDownLatch(1)
    val releaseHandlerForCleanup = CountDownLatch(1)
    val handlerExited = CountDownLatch(1)
    val workerThread = AtomicReference<Thread>()
    val closeInvoked = CountDownLatch(1)
    val coordinator = Executors.newFixedThreadPool(2)
    val server = MockHttpServer.start {
      workerThread.set(Thread.currentThread())
      handlerStarted.countDown()
      try {
        releaseHandlerForCleanup.await()
      } catch (_: InterruptedException) {
        handlerInterrupted.countDown()
        allowHandlerExit.await()
      } finally {
        handlerExited.countDown()
      }
      Response(OK)
    }
    val address = InetSocketAddress("127.0.0.1", URI.create(server.baseUrl).port)
    var requestFuture: Future<*>? = null
    var closeFuture: Future<*>? = null

    try {
      val client = prepareHttpClient(insecureHttp = false)
      requestFuture =
        coordinator.submit<Response> { client(Request(GET, "${server.baseUrl}/hold")) }
      assertThat(handlerStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      assertThat(workerThread.get().isVirtual).isTrue()

      closeFuture =
        coordinator.submit<Unit> {
          closeInvoked.countDown()
          server.close()
        }
      assertThat(closeInvoked.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      assertCloseStillWaiting(closeFuture)
      awaitListenerRefusal(address)

      assertThat(handlerInterrupted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      assertThat(handlerExited.count).isEqualTo(1L)
      assertThat(workerThread.get().isAlive).isTrue()
      assertCloseStillWaiting(closeFuture)

      allowHandlerExit.countDown()
      assertThat(handlerExited.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      closeFuture.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      assertThat(workerThread.get().isAlive).isFalse()
    } finally {
      releaseHandlerForCleanup.countDown()
      allowHandlerExit.countDown()
      awaitFutureQuietly(closeFuture)
      runCatching(server::close)
      requestFuture?.cancel(true)
      awaitFutureQuietly(requestFuture)
      coordinator.shutdownNow()
      check(coordinator.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Mock server wire-contract test coordinator did not terminate"
      }
    }
  }

  private fun assertCloseStillWaiting(closeFuture: Future<*>) {
    assertThrows<TimeoutException> {
      closeFuture.get(CLOSE_STILL_WAITING_MILLIS, TimeUnit.MILLISECONDS)
    }
  }

  private fun awaitListenerRefusal(address: InetSocketAddress) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      try {
        Socket().use { socket -> socket.connect(address, SOCKET_CONNECT_TIMEOUT_MILLIS) }
      } catch (_: IOException) {
        return
      }
      Thread.onSpinWait()
    }
    throw AssertionError("Mock HTTP listener still accepted connections after close began")
  }

  private fun awaitFutureQuietly(future: Future<*>?) {
    if (future == null) return
    try {
      future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: ExecutionException) {
      // Cleanup only needs the task to settle; the test body owns behavioral assertions.
    } catch (_: TimeoutException) {
      future.cancel(true)
    }
  }

  private companion object {
    const val SOCKET_CONNECT_TIMEOUT_MILLIS = 500
    const val CLOSE_STILL_WAITING_MILLIS = 250L
    const val TEST_TIMEOUT_SECONDS = 7L
  }
}

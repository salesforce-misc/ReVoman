/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testsupport

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.exe.prepareHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Method.POST
import org.http4k.core.Parameter
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.ACCEPTED
import org.http4k.core.Status.Companion.CREATED
import org.http4k.core.Status.Companion.OK
import org.http4k.core.queries
import org.http4k.server.HttpExchangeHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RecordedHttpRequest(
  val method: Method,
  val path: String,
  val queries: List<Parameter>,
  val headers: List<Parameter>,
  val body: ByteArray,
) {
  fun headerValues(name: String): List<String> =
    headers
      .asSequence()
      .filter { (headerName) -> headerName.equals(name, ignoreCase = true) }
      .mapNotNull { (_, value) -> value }
      .toList()
}

internal class LoopbackHttpFixture
private constructor(
  private val server: com.sun.net.httpserver.HttpServer,
  private val executor: ExecutorService,
  private val recordedRequests: ConcurrentLinkedQueue<RecordedHttpRequest>,
) : AutoCloseable {
  private val closed = AtomicBoolean()

  val address: InetSocketAddress
    get() = server.address

  val port: Int
    get() = address.port

  val baseUrl: String
    get() = "http://$LOOPBACK_ADDRESS:$port"

  fun requests(path: String? = null): List<RecordedHttpRequest> =
    recordedRequests.toList().let { requests ->
      path?.let { expectedPath -> requests.filter { it.path == expectedPath } } ?: requests
    }

  fun hitCount(path: String? = null): Int = requests(path).size

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    server.stop(0)
    executor.shutdown()
    if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      executor.shutdownNow()
      check(executor.awaitTermination(EXECUTOR_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "loopback HTTP fixture worker did not stop"
      }
    }
  }

  companion object {
    private const val LOOPBACK_ADDRESS = "127.0.0.1"
    private const val EXECUTOR_STOP_TIMEOUT_SECONDS = 5L
    private val fixtureIds = AtomicInteger()

    fun start(handler: HttpHandler): LoopbackHttpFixture {
      val recordedRequests = ConcurrentLinkedQueue<RecordedHttpRequest>()
      val fixtureId = fixtureIds.incrementAndGet()
      val workerIds = AtomicInteger()
      val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "revoman-loopback-http-$fixtureId-${workerIds.incrementAndGet()}").apply {
          isDaemon = false
        }
      }
      val server =
        com.sun.net.httpserver.HttpServer.create(InetSocketAddress(LOOPBACK_ADDRESS, 0), 0).apply {
          createContext(
            "/",
            HttpExchangeHandler { request ->
              val (recorded, replayable) = request.recordedAndReplayable()
              recordedRequests.add(recorded)
              handler(replayable)
            },
          )
          this.executor = executor
        }
      return runCatching {
          server.start()
          LoopbackHttpFixture(server, executor, recordedRequests)
        }
        .getOrElse { failure ->
          server.stop(0)
          executor.shutdownNow()
          throw failure
        }
    }

    private fun Request.recordedAndReplayable(): Pair<RecordedHttpRequest, Request> {
      val buffer = body.payload.asReadOnlyBuffer()
      val bytes = ByteArray(buffer.remaining()).also(buffer::get)
      return RecordedHttpRequest(
        method,
        uri.path,
        uri.queries(),
        headers.toList(),
        bytes.copyOf(),
      ) to body(Body(ByteBuffer.wrap(bytes)))
    }
  }
}

class LoopbackHttpFixtureContractTest {

  @Test
  fun `real wire preserves request and response semantics on an ephemeral loopback port`() {
    val requestBody = byteArrayOf(0, 1, 2, 127, -1)
    val handlerThreadName = AtomicReference<String>()
    val handlerBody = AtomicReference<ByteArray>()
    lateinit var closedAddress: InetSocketAddress
    lateinit var closedWorkerThreadName: String

    LoopbackHttpFixture.start { request ->
        handlerThreadName.set(Thread.currentThread().name)
        handlerBody.set(request.body.stream.readBytes())
        Response(CREATED).header("X-Reply", "first").header("X-Reply", "second").body("accepted")
      }
      .use { fixture ->
        LoopbackHttpFixture.start { Response(OK) }
          .use { secondFixture ->
            assertThat(fixture.address.address.hostAddress).isEqualTo("127.0.0.1")
            assertThat(fixture.address.address.isLoopbackAddress).isTrue()
            assertThat(fixture.port).isGreaterThan(0)
            assertThat(secondFixture.port).isNotEqualTo(fixture.port)
          }

        val response =
          prepareHttpClient(insecureHttp = false)(
            Request(
                POST,
                "${fixture.baseUrl}/wire?term=hello%20world&tag=alpha&tag=beta",
              )
              .header("X-Repeat", "first")
              .header("X-Repeat", "second")
              .body(Body(ByteBuffer.wrap(requestBody)))
          )

        assertThat(response.status).isEqualTo(CREATED)
        assertThat(response.headerValues("X-Reply")).containsExactly("first", "second").inOrder()
        assertThat(response.bodyString()).isEqualTo("accepted")

        val recorded = fixture.requests("/wire").single()
        assertThat(recorded.method).isEqualTo(POST)
        assertThat(recorded.queries)
          .containsExactly("term" to "hello world", "tag" to "alpha", "tag" to "beta")
          .inOrder()
        assertThat(recorded.headerValues("X-Repeat")).containsExactly("first", "second").inOrder()
        assertThat(recorded.body).isEqualTo(requestBody)
        assertThat(handlerBody.get()).isEqualTo(requestBody)
        assertThat(fixture.hitCount()).isEqualTo(1)
        assertThat(fixture.hitCount("/wire")).isEqualTo(1)
        assertThat(handlerThreadName.get()).isNotEqualTo(Thread.currentThread().name)

        closedAddress = fixture.address
        closedWorkerThreadName = handlerThreadName.get()
      }

    assertThrows<IOException> {
      Socket().use { socket -> socket.connect(closedAddress, SOCKET_CONNECT_TIMEOUT_MILLIS) }
    }
    assertThat(
        Thread.getAllStackTraces().keys.any {
          it.name == closedWorkerThreadName && it.isAlive && !it.isDaemon
        }
      )
      .isFalse()
  }

  @Test
  fun `hit counts distinguish routes and total real requests`() {
    LoopbackHttpFixture.start { request ->
        when (request.uri.path) {
          "/accepted" -> Response(ACCEPTED)
          else -> Response(OK)
        }
      }
      .use { fixture ->
        val client = prepareHttpClient(insecureHttp = false)
        assertThat(client(Request(POST, "${fixture.baseUrl}/accepted")).status).isEqualTo(ACCEPTED)
        assertThat(client(Request(POST, "${fixture.baseUrl}/ok")).status).isEqualTo(OK)
        assertThat(client(Request(POST, "${fixture.baseUrl}/accepted")).status).isEqualTo(ACCEPTED)

        assertThat(fixture.hitCount()).isEqualTo(3)
        assertThat(fixture.hitCount("/accepted")).isEqualTo(2)
        assertThat(fixture.hitCount("/ok")).isEqualTo(1)
        assertThat(fixture.requests().map { it.path })
          .containsExactly("/accepted", "/ok", "/accepted")
          .inOrder()
      }
  }

  private companion object {
    const val SOCKET_CONNECT_TIMEOUT_MILLIS = 500
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.queries
import org.http4k.server.Http4kServer
import org.http4k.server.HttpExchangeHandler
import org.http4k.server.ServerConfig
import org.http4k.server.asServer

/**
 * Loopback HTTP server for tests and examples that must not hit the public network.
 *
 * Binds an ephemeral `127.0.0.1` port, records each request for later inspection, and runs handlers
 * on virtual threads. Mutable handler state must be thread-safe.
 */
class MockHttpServer
private constructor(
  val baseUrl: String,
  private val requestSnapshot: () -> List<RecordedHttpRequest>,
  private val stop: () -> Unit,
) : AutoCloseable {
  fun requests(): List<RecordedHttpRequest> = requestSnapshot()

  override fun close(): Unit = stop()

  companion object {
    private val logger = KotlinLogging.logger {}

    @JvmStatic
    // A failed start must still release the executor; HttpServer.start() and http4k can throw
    // checked or unchecked failures.
    @Suppress("TooGenericExceptionCaught")
    fun start(handler: MockHttpHandler): MockHttpServer {
      val ledger = ConcurrentLinkedQueue<RecordedHttpRequest>()
      val executor =
        Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("revoman-mock-http-", 0).factory()
        )
      val server = recordingHandler(handler, ledger).asServer(LoopbackSunHttpLoom(executor))
      try {
        server.start()
      } catch (failure: Throwable) {
        executor.shutdownNow()
        throw failure
      }
      val closed = AtomicBoolean()
      return MockHttpServer(
        "http://127.0.0.1:${server.port()}",
        { java.util.List.copyOf(ledger) },
      ) {
        if (closed.compareAndSet(false, true)) {
          server.stop()
        }
      }
    }

    // Java handlers and user mocks can throw anything; the listener must answer 500, not die.
    @Suppress("TooGenericExceptionCaught")
    private fun recordingHandler(
      handler: MockHttpHandler,
      ledger: ConcurrentLinkedQueue<RecordedHttpRequest>,
    ): HttpHandler = { request ->
      val replayable = capture(request, ledger)
      if (replayable == null) {
        Response(INTERNAL_SERVER_ERROR)
      } else {
        try {
          @Suppress("USELESS_ELVIS") // Java callers can still return null.
          handler.handle(replayable) ?: Response(INTERNAL_SERVER_ERROR)
        } catch (failure: Exception) {
          logger.error(failure) {
            "Mock HTTP handler failed for ${request.method} ${request.uri.path}"
          }
          Response(INTERNAL_SERVER_ERROR)
        }
      }
    }

    // Body/header capture must not take down the listener; a failed capture is an empty 500.
    @Suppress("TooGenericExceptionCaught")
    private fun capture(
      request: Request,
      ledger: ConcurrentLinkedQueue<RecordedHttpRequest>,
    ): Request? =
      try {
        val buffer = request.body.payload.asReadOnlyBuffer()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        ledger.add(
          RecordedHttpRequest.create(
            request.method,
            request.uri.path,
            request.uri.queries().map { (name, value) -> RecordedNameValue(name, value) },
            request.headers.map { (name, value) -> RecordedNameValue(name, value) },
            bytes,
          )
        )
        request.body(Body(ByteBuffer.wrap(bytes)))
      } catch (failure: Exception) {
        logger.error(failure) {
          "Mock HTTP request capture failed for ${request.method} ${request.uri.path}"
        }
        null
      }
  }
}

/** http4k `SunHttpLoom` bound to IPv4 loopback instead of all interfaces. */
private class LoopbackSunHttpLoom(private val executor: ExecutorService) : ServerConfig {
  override val stopMode = ServerConfig.StopMode.Immediate

  override fun toServer(http: HttpHandler): Http4kServer {
    val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    return object : Http4kServer {
      override fun start(): Http4kServer = apply {
        httpServer.createContext("/", HttpExchangeHandler(http))
        httpServer.executor = executor
        httpServer.start()
      }

      override fun stop(): Http4kServer = apply {
        httpServer.stop(0)
        executor.shutdownNow()
      }

      override fun port(): Int = httpServer.address.port
    }
  }
}

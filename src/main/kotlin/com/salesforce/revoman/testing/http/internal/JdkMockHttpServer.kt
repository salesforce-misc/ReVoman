/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http.internal

import com.salesforce.revoman.testing.http.MockHttpHandler
import com.salesforce.revoman.testing.http.RecordedHttpRequest
import com.salesforce.revoman.testing.http.RecordedNameValue
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.queries
import org.http4k.server.HttpExchangeHandler

private val logger = KotlinLogging.logger {}

/**
 * Creates the JDK-backed mock server resources while retaining injectable factories for lifecycle
 * tests. The default listener is deliberately exact IPv4 loopback and its exchange work runs on
 * named virtual threads.
 */
internal class MockHttpServerStarter(
  private val executorFactory: () -> ExecutorService = {
    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("revoman-mock-http-", 0).factory())
  },
  private val serverFactory: (InetSocketAddress) -> HttpServer = { address ->
    HttpServer.create(address, 0)
  },
) {
  /**
   * Starts a fully initialized listener or releases every partially acquired resource on failure.
   */
  fun start(handler: MockHttpHandler): MockHttpServerLifecycle {
    var executor: ExecutorService? = null
    var server: HttpServer? = null
    try {
      val ledger = RequestLedger()
      val ownedExecutor = executorFactory().also { executor = it }
      val ownedServer = serverFactory(InetSocketAddress("127.0.0.1", 0)).also { server = it }
      ownedServer.createContext("/", HttpExchangeHandler(recordingHandler(handler, ledger)))
      ownedServer.executor = ownedExecutor
      ownedServer.start()
      val address = ownedServer.address
      check(address.address is Inet4Address) {
        "Mock HTTP server must bind IPv4 loopback, got ${address.address}"
      }
      check(address.address.hostAddress == "127.0.0.1") {
        "Mock HTTP server must bind 127.0.0.1, got ${address.address.hostAddress}"
      }
      check(address.port > 0) { "Mock HTTP server must select a positive port" }
      val baseUrl = "http://127.0.0.1:${address.port}"
      logger.debug { "Started mock HTTP server at $baseUrl" }
      return MockHttpServerLifecycle(baseUrl, ownedServer, ownedExecutor, ledger)
    } catch (failure: Throwable) {
      val startupFailure = IllegalStateException("Failed to start mock HTTP server", failure)
      server?.let { ownedServer ->
        try {
          ownedServer.stop(0)
        } catch (cleanupFailure: Throwable) {
          startupFailure.addSuppressed(cleanupFailure)
        }
      }
      executor?.let { ownedExecutor ->
        try {
          ownedExecutor.shutdownNow()
        } catch (cleanupFailure: Throwable) {
          startupFailure.addSuppressed(cleanupFailure)
        }
      }
      throw startupFailure
    }
  }
}

/** Captures a request before handing its independent replayable copy to the user handler. */
private fun recordingHandler(handler: MockHttpHandler, ledger: RequestLedger): HttpHandler =
  { request ->
    val (recorded, replayable) = request.capture()
    ledger.publish(recorded)
    handler.handle(replayable)
  }

/**
 * Materializes one request body and produces immutable evidence plus a separately readable body.
 */
private fun Request.capture(): Pair<RecordedHttpRequest, Request> {
  val buffer = body.payload.asReadOnlyBuffer()
  val bytes = ByteArray(buffer.remaining()).also(buffer::get)
  val recorded =
    RecordedHttpRequest.create(
      method,
      uri.path,
      uri.queries().map { (name, value) -> RecordedNameValue(name, value) },
      headers.map { (name, value) -> RecordedNameValue(name, value) },
      bytes,
    )
  return recorded to body(Body(ByteBuffer.wrap(bytes)))
}

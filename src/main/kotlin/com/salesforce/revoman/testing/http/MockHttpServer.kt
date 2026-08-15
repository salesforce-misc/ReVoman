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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.queries
import org.http4k.server.HttpExchangeHandler

/**
 * Buffered, real-wire mock HTTP infrastructure for consumer tests and examples.
 *
 * A server listens only on an ephemeral `127.0.0.1` port and exposes that stable origin through
 * [baseUrl]. Its handler can run concurrently on server-owned Java virtual threads; mutable handler
 * state and blocking work therefore remain the caller's thread-safe, interruption-cooperative
 * responsibility. This server is test-only loopback infrastructure, not an internet-facing server.
 */
class MockHttpServer
private constructor(
  /** Stable loopback origin without a trailing slash, readable before and after [close]. */
  val baseUrl: String,
  private val requestSnapshot: () -> List<RecordedHttpRequest>,
  private val closeServer: () -> Unit,
) : AutoCloseable {
  /** Returns an unmodifiable point-in-time request snapshot in capture order. */
  fun requests(): List<RecordedHttpRequest> = requestSnapshot()

  /** Stops the listener and its owned handler workers. Repeated calls are harmless. */
  override fun close(): Unit = closeServer()

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val WORKER_TERMINATION_TIMEOUT_SECONDS = 5L

    /** Starts an exact IPv4-loopback mock server for [handler]. */
    @JvmStatic
    fun start(handler: MockHttpHandler): MockHttpServer =
      MockHttpServerStarter().start(handler).asFacade()

    /** Injects transport factories without exposing the private starter or lifecycle types. */
    @JvmSynthetic
    internal fun startForTest(
      handler: MockHttpHandler,
      executorFactory: () -> ExecutorService,
      serverFactory: (InetSocketAddress) -> HttpServer,
    ): MockHttpServer =
      MockHttpServerStarter(executorFactory, serverFactory).start(handler).asFacade()

    /** Builds a lifecycle facade for deterministic resource-shutdown tests. */
    @JvmSynthetic
    internal fun lifecycleForTest(
      baseUrl: String,
      server: HttpServer,
      executor: ExecutorService,
      handlerFailures: List<Pair<Long, Exception>>,
    ): MockHttpServer {
      val ledger = RequestLedger()
      handlerFailures.forEach { (ordinal, failure) ->
        ledger.recordHandlerFailure(ordinal, failure)
      }
      return MockHttpServerLifecycle(baseUrl, server, executor, ledger).asFacade()
    }

    /** Builds a direct recording boundary plus observable facade for focused failure tests. */
    @JvmSynthetic
    internal fun recordingHandlerForTest(
      handler: MockHttpHandler
    ): Pair<HttpHandler, MockHttpServer> {
      val ledger = RequestLedger()
      val closeStarted = AtomicBoolean()
      val server =
        MockHttpServer("http://127.0.0.1:0", ledger::requests) {
          if (closeStarted.compareAndSet(false, true)) {
            ledger.aggregateCloseFailure(emptyList())?.let { throw it }
          }
        }
      return recordingHandler(handler, ledger) to server
    }

    /** Creates and transactionally starts the owned JDK transport resources. */
    private class MockHttpServerStarter(
      private val executorFactory: () -> ExecutorService = {
        Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("revoman-mock-http-", 0).factory()
        )
      },
      private val serverFactory: (InetSocketAddress) -> HttpServer = { address ->
        HttpServer.create(address, 0)
      },
    ) {
      /**
       * Rolls back every resource acquired before a startup failure, including JVM-level errors.
       */
      @Suppress("TooGenericExceptionCaught")
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
            suppressCleanupFailure(startupFailure) { ownedServer.stop(0) }
          }
          var interrupted = false
          executor?.let { ownedExecutor ->
            suppressCleanupFailure(startupFailure) { ownedExecutor.shutdownNow() }
            try {
              if (
                !ownedExecutor.awaitTermination(
                  WORKER_TERMINATION_TIMEOUT_SECONDS,
                  TimeUnit.SECONDS,
                )
              ) {
                startupFailure.addSuppressed(
                  IllegalStateException(
                    "Mock HTTP server startup cleanup did not stop within 5 seconds"
                  )
                )
              }
            } catch (cleanupFailure: Throwable) {
              if (cleanupFailure is InterruptedException) interrupted = true
              startupFailure.addSuppressed(cleanupFailure)
            }
          }
          if (interrupted) Thread.currentThread().interrupt()
          throw startupFailure
        }
      }
    }

    /** Retains every cleanup failure without preventing the remaining rollback steps. */
    @Suppress("TooGenericExceptionCaught")
    private fun suppressCleanupFailure(failure: Throwable, cleanup: () -> Unit) {
      try {
        cleanup()
      } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
      }
    }

    /**
     * Captures a request before handing its independent replayable copy to the user handler.
     *
     * This boundary deliberately catches [Exception], rather than [Throwable], so every [Error]
     * escapes the server task unchanged. The nullable local also defends against Java handler
     * implementations returning null despite the Kotlin non-null signature.
     */
    @Suppress("TooGenericExceptionCaught", "RedundantNullableReturnType")
    private fun recordingHandler(handler: MockHttpHandler, ledger: RequestLedger): HttpHandler =
      { request ->
        val capture = captureRequestOrNull(request, ledger)
        if (capture == null) {
          Response(INTERNAL_SERVER_ERROR)
        } else {
          val (ordinal, replayable) = capture
          try {
            val response: Response? = handler.handle(replayable)
            response ?: throw NullPointerException("MockHttpHandler returned null")
          } catch (failure: Exception) {
            logger.error(failure) {
              "Mock HTTP handler failed for ${request.method} ${request.uri.path}"
            }
            ledger.recordHandlerFailure(ordinal, failure)
            Response(INTERNAL_SERVER_ERROR)
          }
        }
      }

    /** Materializes one request body into immutable evidence and a replayable body. */
    @Suppress("TooGenericExceptionCaught")
    private fun captureRequestOrNull(
      request: Request,
      ledger: RequestLedger,
    ): Pair<Long, Request>? {
      val method = request.method
      val path = request.uri.path
      return try {
        val buffer = request.body.payload.asReadOnlyBuffer()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        val recorded =
          RecordedHttpRequest.create(
            method,
            path,
            request.uri.queries().map { (name, value) -> RecordedNameValue(name, value) },
            request.headers.map { (name, value) -> RecordedNameValue(name, value) },
            bytes,
          )
        val ordinal = ledger.publish(recorded)
        ordinal to request.body(Body(ByteBuffer.wrap(bytes)))
      } catch (failure: Exception) {
        logger.error(failure) { "Mock HTTP request capture failed for $method $path" }
        null
      }
    }

    /** Synchronizes complete request publication, capture ordering, and immutable snapshots. */
    private class RequestLedger {
      private val lock = ReentrantLock()
      private var nextOrdinal = 0L
      private val records = mutableListOf<Pair<Long, RecordedHttpRequest>>()
      private val failures = mutableListOf<HandlerFailure>()

      fun publish(request: RecordedHttpRequest): Long = lock.withLock {
        val ordinal = nextOrdinal++
        records += ordinal to request
        ordinal
      }

      fun recordHandlerFailure(ordinal: Long, failure: Exception): Unit = lock.withLock {
        failures += HandlerFailure(ordinal, failure)
      }

      fun requests(): List<RecordedHttpRequest> = lock.withLock {
        java.util.List.copyOf(records.map { it.second })
      }

      private fun handlerFailures(): List<HandlerFailure> = lock.withLock {
        java.util.List.copyOf(failures.sortedBy(HandlerFailure::ordinal))
      }

      fun aggregateCloseFailure(shutdownFailures: List<Throwable>): IllegalStateException? {
        val handlerFailures = handlerFailures().map(HandlerFailure::failure)
        val orderedFailures = handlerFailures + shutdownFailures
        if (orderedFailures.isEmpty()) return null
        val message =
          if (handlerFailures.isEmpty()) {
            "Mock HTTP server shutdown failed"
          } else {
            "${handlerFailures.size} mock HTTP handler failures"
          }
        return IllegalStateException(message, orderedFailures.first()).apply {
          orderedFailures.drop(1).forEach(::addSuppressed)
        }
      }
    }

    private data class HandlerFailure(val ordinal: Long, val failure: Exception)

    /** Owns listener, workers, request ledger, and first-closer coordination. */
    private class MockHttpServerLifecycle(
      val baseUrl: String,
      private val server: HttpServer,
      private val executor: ExecutorService,
      private val ledger: RequestLedger,
    ) {
      private val closeStarted = AtomicBoolean()
      private val closeFinished = CountDownLatch(1)

      fun requests(): List<RecordedHttpRequest> = ledger.requests()

      fun close() {
        if (!closeStarted.compareAndSet(false, true)) {
          awaitFirstCloser()
          return
        }
        val shutdownFailures = mutableListOf<Throwable>()
        var interrupted = false
        try {
          attempt(shutdownFailures) { server.stop(0) }
          logger.debug { "Stopped mock HTTP server at $baseUrl" }
          attempt(shutdownFailures) { executor.shutdown() }
          val firstAwait = awaitTermination(shutdownFailures)
          interrupted = firstAwait.interrupted
          if (!firstAwait.terminated) {
            attempt(shutdownFailures) { executor.shutdownNow() }
            val secondAwait = awaitTermination(shutdownFailures)
            interrupted = interrupted || secondAwait.interrupted
            if (!secondAwait.terminated) {
              shutdownFailures +=
                IllegalStateException("Mock HTTP handler work did not stop within 10 seconds")
            }
          }
          ledger.aggregateCloseFailure(shutdownFailures)?.let { throw it }
        } finally {
          closeFinished.countDown()
          if (interrupted) Thread.currentThread().interrupt()
        }
      }

      /** Retains any executor failure so close can still attempt forced shutdown. */
      @Suppress("TooGenericExceptionCaught")
      private fun awaitTermination(failures: MutableList<Throwable>): AwaitResult =
        try {
          AwaitResult(
            terminated =
              executor.awaitTermination(WORKER_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            interrupted = false,
          )
        } catch (failure: Throwable) {
          failures += failure
          AwaitResult(terminated = false, interrupted = failure is InterruptedException)
        }

      private fun awaitFirstCloser() {
        var interrupted = false
        while (true) {
          try {
            closeFinished.await()
            break
          } catch (_: InterruptedException) {
            interrupted = true
          }
        }
        if (interrupted) Thread.currentThread().interrupt()
      }
    }

    private fun MockHttpServerLifecycle.asFacade(): MockHttpServer =
      MockHttpServer(baseUrl, ::requests, ::close)

    private data class AwaitResult(val terminated: Boolean, val interrupted: Boolean)

    /** Retains any cleanup failure so later close operations are still attempted. */
    @Suppress("TooGenericExceptionCaught")
    private fun attempt(failures: MutableList<Throwable>, action: () -> Unit) {
      try {
        action()
      } catch (failure: Throwable) {
        failures += failure
      }
    }
  }
}

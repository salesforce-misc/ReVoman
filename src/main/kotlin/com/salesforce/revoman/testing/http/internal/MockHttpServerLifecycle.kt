/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http.internal

import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

internal const val WORKER_TERMINATION_TIMEOUT_SECONDS = 5L

/**
 * Owns the listener and workers for buffered, real-wire, test-only IPv4 loopback infrastructure.
 *
 * The public facade caches [baseUrl], so the stable origin remains readable after closure. The
 * request ledger remains readable too, as an unmodifiable point-in-time capture-order snapshot.
 */
internal class MockHttpServerLifecycle(
  val baseUrl: String,
  private val server: HttpServer,
  private val executor: ExecutorService,
  private val ledger: RequestLedger,
) {
  private val closeStarted = AtomicBoolean()
  private val closeFinished = CountDownLatch(1)

  /** Returns an unmodifiable point-in-time snapshot of complete captures in capture order. */
  fun requests() = ledger.requests()

  /**
   * Stops the listener and owned workers once.
   *
   * Handlers run concurrently on virtual threads. Callers own mutable handler state and blocking
   * handler work must be thread-safe and interruption-cooperative.
   */
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

private data class AwaitResult(val terminated: Boolean, val interrupted: Boolean)

private inline fun attempt(failures: MutableList<Throwable>, action: () -> Unit) {
  try {
    action()
  } catch (failure: Throwable) {
    failures += failure
  }
}

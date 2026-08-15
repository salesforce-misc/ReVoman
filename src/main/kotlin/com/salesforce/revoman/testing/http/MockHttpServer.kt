/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import com.salesforce.revoman.testing.http.internal.MockHttpServerLifecycle
import com.salesforce.revoman.testing.http.internal.MockHttpServerStarter

/**
 * Buffered, real-wire mock HTTP infrastructure for consumer tests and examples.
 *
 * A server listens only on an ephemeral `127.0.0.1` port and exposes that stable origin through
 * [baseUrl]. Its handler can run concurrently on server-owned Java virtual threads; mutable handler
 * state and blocking work therefore remain the caller's thread-safe, interruption-cooperative
 * responsibility. This server is test-only loopback infrastructure, not an internet-facing server.
 */
class MockHttpServer private constructor(private val lifecycle: MockHttpServerLifecycle) :
  AutoCloseable {
  /** Stable loopback origin without a trailing slash, readable before and after [close]. */
  val baseUrl: String = lifecycle.baseUrl

  /** Returns an unmodifiable point-in-time request snapshot in capture order. */
  fun requests(): List<RecordedHttpRequest> = lifecycle.requests()

  /** Stops the listener and its owned handler workers. Repeated calls are harmless. */
  override fun close(): Unit = lifecycle.close()

  companion object {
    /** Starts an exact IPv4-loopback mock server for [handler]. */
    @JvmStatic
    fun start(handler: MockHttpHandler): MockHttpServer =
      MockHttpServer(MockHttpServerStarter().start(handler))
  }
}

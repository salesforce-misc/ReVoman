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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockHttpServerConcurrencyTest {
  @Test
  fun `requests snapshots are stable and unmodifiable`() {
    MockHttpServer.start { Response(OK) }
      .use { server ->
        val client = prepareHttpClient(insecureHttp = false)
        client(Request(GET, "${server.baseUrl}/first"))
        val firstSnapshot = server.requests()
        client(Request(GET, "${server.baseUrl}/second"))

        assertThat(firstSnapshot.map { it.path }).containsExactly("/first")
        assertThat(server.requests().map { it.path }).containsExactly("/first", "/second").inOrder()
        assertThrows<UnsupportedOperationException> { (firstSnapshot as MutableList).clear() }
      }
  }

  @Test
  fun `handlers overlap on distinct virtual threads after requests are captured`() {
    val firstEntered = CountDownLatch(1)
    val entered = CountDownLatch(2)
    val release = CountDownLatch(1)
    val threadIds = ConcurrentLinkedQueue<Long>()
    val virtualThreads = ConcurrentLinkedQueue<Boolean>()
    MockHttpServer.start { request ->
        threadIds.add(Thread.currentThread().threadId())
        virtualThreads.add(Thread.currentThread().isVirtual)
        if (request.uri.path == "/first") firstEntered.countDown()
        entered.countDown()
        check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          "Timed out waiting to release handler"
        }
        Response(OK).body(request.uri.path)
      }
      .use { server ->
        Executors.newFixedThreadPool(2).use { clients ->
          val client = prepareHttpClient(insecureHttp = false)
          lateinit var first: Future<String>
          lateinit var second: Future<String>
          try {
            first =
              clients.submit<String> {
                client(Request(GET, "${server.baseUrl}/first")).bodyString()
              }
            assertThat(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            second =
              clients.submit<String> {
                client(Request(GET, "${server.baseUrl}/second")).bodyString()
              }
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(threadIds.distinct()).hasSize(2)
            assertThat(virtualThreads).containsExactly(true, true)
            assertThat(server.requests().map { it.path })
              .containsExactly("/first", "/second")
              .inOrder()
          } finally {
            release.countDown()
          }
          assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/first")
          assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/second")
        }
      }
  }

  @Test
  fun `capture order is established before concurrent handlers complete`() {
    val firstEntered = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val release = CountDownLatch(1)
    MockHttpServer.start { request ->
        when (request.uri.path) {
          "/first" -> firstEntered.countDown()
          "/second" -> secondEntered.countDown()
        }
        check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          "Timed out waiting to release handler"
        }
        Response(OK)
      }
      .use { server ->
        Executors.newFixedThreadPool(2).use { clients ->
          val client = prepareHttpClient(insecureHttp = false)
          lateinit var first: Future<Response>
          lateinit var second: Future<Response>
          try {
            first = clients.submit<Response> { client(Request(GET, "${server.baseUrl}/first")) }
            assertThat(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            second = clients.submit<Response> { client(Request(GET, "${server.baseUrl}/second")) }
            assertThat(secondEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(server.requests().map { it.path })
              .containsExactly("/first", "/second")
              .inOrder()
          } finally {
            release.countDown()
          }
          first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
          second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
      }
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5L
  }
}

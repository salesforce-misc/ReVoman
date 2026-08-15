/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockHttpServerStartupTest {
  @Test
  fun `executor factory failure creates no server and remains the cause`() {
    val original = IOException("executor factory failed")
    val serverCreated = AtomicBoolean()
    val failure =
      assertThrows<IllegalStateException> {
        MockHttpServer.startForTest(
          handler = { Response(OK) },
          executorFactory = { throw original },
          serverFactory = {
            serverCreated.set(true)
            ControllableHttpServer(InetSocketAddress(ipv4(127, 0, 0, 1), TEST_PORT))
          },
        )
      }

    assertThat(failure).hasMessageThat().isEqualTo("Failed to start mock HTTP server")
    assertThat(failure.cause).isSameInstanceAs(original)
    assertThat(failure.suppressed).isEmpty()
    assertThat(serverCreated.get()).isFalse()
  }

  @Test
  fun `server factory failure shuts down and awaits the executor`() {
    val original = IOException("server factory failed")
    val executor = configuredExecutor()
    val failure =
      assertThrows<IllegalStateException> {
        MockHttpServer.startForTest(
          handler = { Response(OK) },
          executorFactory = { executor },
          serverFactory = { throw original },
        )
      }

    assertThat(failure.cause).isSameInstanceAs(original)
    verifyOrder {
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `context creation failure stops the server then shuts down and awaits the executor`() {
    val fixture = StartupFixture()
    val original = IllegalArgumentException("context failed")
    fixture.server.createContextFailure = original

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).isSameInstanceAs(original)
    fixture.verifyRollback()
  }

  @Test
  fun `executor assignment failure stops the server then shuts down and awaits the executor`() {
    val fixture = StartupFixture()
    val original = IllegalStateException("executor assignment failed")
    fixture.server.executorAssignmentFailure = original

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).isSameInstanceAs(original)
    fixture.verifyRollback()
  }

  @Test
  fun `start failure stops the server then shuts down and awaits the executor`() {
    val fixture = StartupFixture()
    val original = IOException("start failed")
    fixture.server.startFailure = original

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).isSameInstanceAs(original)
    fixture.verifyRollback()
  }

  @Test
  fun `wildcard actual address is rejected after start and all resources are cleaned`() {
    val fixture = StartupFixture(InetSocketAddress(ipv4(0, 0, 0, 0), TEST_PORT))

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).hasMessageThat().contains("127.0.0.1")
    assertThat(fixture.server.startCalls).isEqualTo(1)
    fixture.verifyRollback()
  }

  @Test
  fun `IPv6 loopback actual address is rejected after start and all resources are cleaned`() {
    val fixture = StartupFixture(InetSocketAddress(InetAddress.getByName("::1"), TEST_PORT))

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).hasMessageThat().contains("IPv4 loopback")
    assertThat(fixture.server.startCalls).isEqualTo(1)
    fixture.verifyRollback()
  }

  @Test
  fun `zero actual port is rejected after start and all resources are cleaned`() {
    val fixture = StartupFixture(InetSocketAddress(ipv4(127, 0, 0, 1), 0))

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).hasMessageThat().contains("positive port")
    assertThat(fixture.server.startCalls).isEqualTo(1)
    fixture.verifyRollback()
  }

  @Test
  fun `startup cleanup failures are suppressed after the original in cleanup order`() {
    val fixture = StartupFixture()
    val original = IOException("start failed")
    val stopFailure = IllegalStateException("stop failed")
    val shutdownFailure = IllegalArgumentException("shutdownNow failed")
    val awaitFailure = UnsupportedOperationException("await failed")
    fixture.server.startFailure = original
    fixture.server.stopFailure = stopFailure
    every { fixture.executor.shutdownNow() } throws shutdownFailure
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } throws awaitFailure

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).isSameInstanceAs(original)
    assertThat(failure.suppressed.asList())
      .containsExactly(stopFailure, shutdownFailure, awaitFailure)
      .inOrder()
    fixture.verifyRollback()
  }

  @Test
  fun `startup cleanup timeout is retained without replacing the original cause`() {
    val fixture = StartupFixture()
    val original = IOException("start failed")
    fixture.server.startFailure = original
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } returns false

    val failure = assertThrows<IllegalStateException> { fixture.start() }

    assertThat(failure.cause).isSameInstanceAs(original)
    assertThat(failure.suppressed).hasLength(1)
    assertThat(failure.suppressed.single()).hasMessageThat().contains("5 seconds")
  }

  @Test
  fun `successful startup uses exact requested loopback and stable actual base URL`() {
    val requestedAddress = AtomicReference<InetSocketAddress>()
    val fixture = StartupFixture()
    val lifecycle =
      MockHttpServer.startForTest(
        handler = { Response(OK) },
        executorFactory = { fixture.executor },
        serverFactory = { address ->
          requestedAddress.set(address)
          fixture.server
        },
      )

    assertThat(requestedAddress.get().address.hostAddress).isEqualTo("127.0.0.1")
    assertThat(requestedAddress.get().port).isEqualTo(0)
    assertThat(lifecycle.baseUrl).isEqualTo("http://127.0.0.1:$TEST_PORT")
    assertThat(fixture.server.stopCalls).isEqualTo(0)
    verify(exactly = 0) { fixture.executor.shutdownNow() }
  }

  private class StartupFixture(
    address: InetSocketAddress = InetSocketAddress(ipv4(127, 0, 0, 1), TEST_PORT)
  ) {
    val executor = configuredExecutor()
    val server = ControllableHttpServer(address)

    fun start(): MockHttpServer =
      MockHttpServer.startForTest({ Response(OK) }, { executor }, { server })

    fun verifyRollback() {
      assertThat(server.stopCalls).isEqualTo(1)
      verifyOrder {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
      }
    }
  }

  private companion object {
    const val TEST_PORT = 43210

    fun configuredExecutor(): ExecutorService =
      mockk<ExecutorService>(relaxed = true).also { executor ->
        every { executor.shutdown() } just Runs
        every { executor.shutdownNow() } returns emptyList()
        every { executor.awaitTermination(5, TimeUnit.SECONDS) } returns true
      }

    fun ipv4(first: Int, second: Int, third: Int, fourth: Int): InetAddress =
      InetAddress.getByAddress(
        byteArrayOf(first.toByte(), second.toByte(), third.toByte(), fourth.toByte())
      )
  }
}

internal class ControllableHttpServer(private val actualAddress: InetSocketAddress) : HttpServer() {
  var createContextFailure: Throwable? = null
  var executorAssignmentFailure: Throwable? = null
  var startFailure: Throwable? = null
  var stopFailure: Throwable? = null
  var startCalls = 0
    private set

  var stopCalls = 0
    private set

  private var configuredExecutor: Executor? = null

  override fun bind(address: InetSocketAddress, backlog: Int) = Unit

  override fun start() {
    startCalls++
    startFailure?.let { throw it }
  }

  override fun setExecutor(executor: Executor?) {
    executorAssignmentFailure?.let { throw it }
    configuredExecutor = executor
  }

  override fun getExecutor(): Executor? = configuredExecutor

  override fun stop(delay: Int) {
    stopCalls++
    stopFailure?.let { throw it }
  }

  override fun createContext(path: String, handler: HttpHandler): HttpContext {
    createContextFailure?.let { throw it }
    return ControllableHttpContext(path, this, handler)
  }

  override fun createContext(path: String): HttpContext = ControllableHttpContext(path, this, null)

  override fun removeContext(path: String) = Unit

  override fun removeContext(context: HttpContext) = Unit

  override fun getAddress(): InetSocketAddress = actualAddress
}

private class ControllableHttpContext(
  private val contextPath: String,
  private val owner: HttpServer,
  private var contextHandler: HttpHandler?,
) : HttpContext() {
  private var contextAuthenticator: com.sun.net.httpserver.Authenticator? = null

  override fun getHandler(): HttpHandler? = contextHandler

  override fun setHandler(handler: HttpHandler) {
    contextHandler = handler
  }

  override fun getPath(): String = contextPath

  override fun getServer(): HttpServer = owner

  override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()

  override fun getFilters(): MutableList<com.sun.net.httpserver.Filter> = mutableListOf()

  override fun setAuthenticator(
    authenticator: com.sun.net.httpserver.Authenticator?
  ): com.sun.net.httpserver.Authenticator? = contextAuthenticator.also {
    contextAuthenticator = authenticator
  }

  override fun getAuthenticator(): com.sun.net.httpserver.Authenticator? = contextAuthenticator
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.testing.http.internal.MockHttpServerLifecycle
import com.salesforce.revoman.testing.http.internal.RequestLedger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockHttpServerLifecycleTest {
  @Test
  fun `two sequential closes stop and shut down resources once`() {
    val fixture = LifecycleFixture()

    fixture.lifecycle.close()
    fixture.lifecycle.close()

    assertThat(fixture.server.stopCalls).isEqualTo(1)
    verify(exactly = 1) { fixture.executor.shutdown() }
    verify(exactly = 1) { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) }
  }

  @Test
  fun `concurrent loser waits for failing winner and returns without its aggregate`() {
    val fixture = LifecycleFixture()
    val winnerEnteredAwait = CountDownLatch(1)
    val releaseWinner = CountDownLatch(1)
    val loserEntered = CountDownLatch(1)
    val handlerFailure = IOException("handler failed")
    fixture.ledger.recordHandlerFailure(0, handlerFailure)
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } answers
      {
        winnerEnteredAwait.countDown()
        check(releaseWinner.await(5, TimeUnit.SECONDS)) { "Timed out releasing winning closer" }
        true
      }

    Executors.newFixedThreadPool(2).use { closers ->
      val winner = closers.submit<Unit> { fixture.lifecycle.close() }
      assertThat(winnerEnteredAwait.await(5, TimeUnit.SECONDS)).isTrue()
      val loser =
        closers.submit<Unit> {
          loserEntered.countDown()
          fixture.lifecycle.close()
        }
      try {
        assertThat(loserEntered.await(5, TimeUnit.SECONDS)).isTrue()
        assertThrows<TimeoutException> { loser.get(250, TimeUnit.MILLISECONDS) }
      } finally {
        releaseWinner.countDown()
      }

      val winnerFailure = assertThrows<ExecutionException> { winner.get(5, TimeUnit.SECONDS) }
      assertThat(winnerFailure.cause).isInstanceOf(IllegalStateException::class.java)
      assertThat(winnerFailure.cause?.cause).isSameInstanceAs(handlerFailure)
      loser.get(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `interrupted concurrent loser remains behind winner and restores its interrupt flag`() {
    val fixture = LifecycleFixture()
    val winnerEnteredAwait = CountDownLatch(1)
    val releaseWinner = CountDownLatch(1)
    val loserReady = CountDownLatch(1)
    val loserMayClose = AtomicBoolean()
    val loserReturned = CountDownLatch(1)
    val loserInterruptRestored = AtomicBoolean()
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } answers
      {
        winnerEnteredAwait.countDown()
        check(releaseWinner.await(5, TimeUnit.SECONDS)) { "Timed out releasing winning closer" }
        true
      }
    Executors.newSingleThreadExecutor().use { winnerExecutor ->
      val winner = winnerExecutor.submit<Unit> { fixture.lifecycle.close() }
      assertThat(winnerEnteredAwait.await(5, TimeUnit.SECONDS)).isTrue()
      val loser =
        Thread.ofPlatform().name("mock-http-close-loser").unstarted {
          loserReady.countDown()
          while (!loserMayClose.get()) Thread.onSpinWait()
          fixture.lifecycle.close()
          loserInterruptRestored.set(Thread.currentThread().isInterrupted)
          loserReturned.countDown()
        }

      try {
        loser.start()
        assertThat(loserReady.await(5, TimeUnit.SECONDS)).isTrue()
        loser.interrupt()
        loserMayClose.set(true)
        awaitThreadState(loser, Thread.State.WAITING)
        assertThat(loserReturned.count).isEqualTo(1)
      } finally {
        releaseWinner.countDown()
        loser.join(TimeUnit.SECONDS.toMillis(5))
      }

      winner.get(5, TimeUnit.SECONDS)
      assertThat(loserReturned.count).isEqualTo(0)
      assertThat(loser.isAlive).isFalse()
      assertThat(loserInterruptRestored.get()).isTrue()
    }
  }

  @Test
  fun `first timeout interrupts workers and performs a second five second await`() {
    val fixture = LifecycleFixture()
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } returnsMany listOf(false, true)

    fixture.lifecycle.close()

    verifyOrder {
      fixture.executor.shutdown()
      fixture.executor.awaitTermination(5, TimeUnit.SECONDS)
      fixture.executor.shutdownNow()
      fixture.executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `second timeout reports the complete ten second shutdown bound`() {
    val fixture = LifecycleFixture()
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } returns false

    val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

    assertThat(failure).hasMessageThat().isEqualTo("Mock HTTP server shutdown failed")
    assertThat(failure.cause)
      .hasMessageThat()
      .isEqualTo("Mock HTTP handler work did not stop within 10 seconds")
  }

  @Test
  fun `interrupted await escalates cleanup restores the winner flag and throws its aggregate`() {
    val fixture = LifecycleFixture()
    val interruption = InterruptedException("await interrupted")
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } throws
      interruption andThen
      true

    try {
      val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

      assertThat(failure.cause).isSameInstanceAs(interruption)
      verify(exactly = 1) { fixture.executor.shutdownNow() }
      verify(exactly = 2) { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) }
      assertThat(Thread.currentThread().isInterrupted).isTrue()
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `stop failure does not prevent executor cleanup`() {
    val fixture = LifecycleFixture()
    val stopFailure = IllegalStateException("stop failed")
    fixture.server.stopFailure = stopFailure

    val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

    assertThat(failure.cause).isSameInstanceAs(stopFailure)
    assertThat(fixture.server.stopCalls).isEqualTo(1)
    verifyOrder {
      fixture.executor.shutdown()
      fixture.executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `shutdown failure does not prevent either await`() {
    val fixture = LifecycleFixture()
    val shutdownFailure = IllegalStateException("shutdown failed")
    every { fixture.executor.shutdown() } throws shutdownFailure

    val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

    assertThat(failure.cause).isSameInstanceAs(shutdownFailure)
    verify(exactly = 1) { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) }
  }

  @Test
  fun `shutdownNow failure does not prevent the second await`() {
    val fixture = LifecycleFixture()
    val shutdownNowFailure = IllegalStateException("shutdownNow failed")
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } returnsMany listOf(false, true)
    every { fixture.executor.shutdownNow() } throws shutdownNowFailure

    val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

    assertThat(failure.cause).isSameInstanceAs(shutdownNowFailure)
    verify(exactly = 2) { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) }
  }

  @Test
  fun `unexpected await failure still escalates and performs the second await`() {
    val fixture = LifecycleFixture()
    val awaitFailure = IllegalStateException("await failed")
    every { fixture.executor.awaitTermination(5, TimeUnit.SECONDS) } throws
      awaitFailure andThen
      true

    val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

    assertThat(failure.cause).isSameInstanceAs(awaitFailure)
    verifyOrder {
      fixture.executor.awaitTermination(5, TimeUnit.SECONDS)
      fixture.executor.shutdownNow()
      fixture.executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `handler failures remain primary and shutdown follows later handler failures`() {
    val fixture = LifecycleFixture()
    val firstHandler = IOException("first handler")
    val laterHandler = IllegalArgumentException("later handler")
    val shutdownFailure = IllegalStateException("stop failed")
    fixture.ledger.recordHandlerFailure(2, laterHandler)
    fixture.ledger.recordHandlerFailure(1, firstHandler)
    fixture.server.stopFailure = shutdownFailure

    val failure = assertThrows<IllegalStateException> { fixture.lifecycle.close() }

    assertThat(failure).hasMessageThat().isEqualTo("2 mock HTTP handler failures")
    assertThat(failure.cause).isSameInstanceAs(firstHandler)
    assertThat(failure.suppressed.asList()).containsExactly(laterHandler, shutdownFailure).inOrder()
  }

  private class LifecycleFixture {
    val server = ControllableHttpServer(java.net.InetSocketAddress("127.0.0.1", 43210))
    val executor = mockk<ExecutorService>(relaxed = true)
    val ledger = RequestLedger()
    val lifecycle = MockHttpServerLifecycle("http://127.0.0.1:43210", server, executor, ledger)

    init {
      every { executor.shutdown() } just Runs
      every { executor.shutdownNow() } returns emptyList()
      every { executor.awaitTermination(5, TimeUnit.SECONDS) } returns true
    }
  }

  private companion object {
    fun awaitThreadState(thread: Thread, expected: Thread.State) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (thread.state != expected && System.nanoTime() < deadline) Thread.onSpinWait()
      assertThat(thread.state).isEqualTo(expected)
    }
  }
}

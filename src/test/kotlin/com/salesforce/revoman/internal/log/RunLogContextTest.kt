/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RunLogContextTest {

  private class RecordingSink : RunLogSink {
    val lines = mutableListOf<Pair<LogLevel, String>>()
    val events = mutableListOf<StepEvent>()
    var closed = false

    override fun line(level: LogLevel, message: String) {
      lines += level to message
    }

    override fun event(event: StepEvent) {
      events += event
    }

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `current is null and not active when no sink is bound`() {
    RunLogContext.current() shouldBe null
    RunLogContext.hasActiveSink() shouldBe false
  }

  @Test
  fun `RevomanLog is a no-op when no sink is bound`() {
    RevomanLog.info { "no sink" }
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `RevomanLog tees to the sink bound by where`() {
    val sink = RecordingSink()
    RunLogContext.where(sink) {
      RevomanLog.debug { "tracing" }
      RevomanLog.info { "hello" }
      RevomanLog.warn { "careful" }
      RevomanLog.error { "boom" }
    }
    sink.lines shouldBe
      listOf(
        LogLevel.DEBUG to "tracing",
        LogLevel.INFO to "hello",
        LogLevel.WARN to "careful",
        LogLevel.ERROR to "boom",
      )
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `NoOp is bound but not active`() {
    RunLogContext.where(RunLogSink.NoOp) {
      RunLogContext.current() shouldBe RunLogSink.NoOp
      RunLogContext.hasActiveSink() shouldBe false
    }
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `a real sink is current and active inside where`() {
    val sink = RecordingSink()
    RunLogContext.where(sink) {
      RunLogContext.current() shouldBe sink
      RunLogContext.hasActiveSink() shouldBe true
    }
    RunLogContext.current() shouldBe null
    sink.closed shouldBe false
  }

  @Test
  fun `nested where restores the outer sink when the inner frame exits`() {
    val outer = RecordingSink()
    val inner = RecordingSink()
    RunLogContext.where(outer) {
      RunLogContext.current() shouldBe outer
      RunLogContext.where(inner) { RunLogContext.current() shouldBe inner }
      RunLogContext.current() shouldBe outer
    }
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `binding cannot leak after where returns — forgotten restore is impossible`() {
    val sink = RecordingSink()
    RunLogContext.where(sink) { RunLogContext.current() shouldBe sink }
    // No restore() call exists; the where frame IS the lifetime.
    RunLogContext.current() shouldBe null
    RunLogContext.hasActiveSink() shouldBe false
  }

  @Test
  fun `where unbinds even when the block throws`() {
    val sink = RecordingSink()
    shouldThrow<IllegalStateException> {
      RunLogContext.where(sink) { error("boom") }
    }
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `inner where throw restores the outer sink`() {
    val outer = RecordingSink()
    val inner = RecordingSink()
    RunLogContext.where(outer) {
      shouldThrow<IllegalStateException> {
        RunLogContext.where(inner) { error("boom") }
      }
      RunLogContext.current() shouldBe outer
    }
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `where returns the block result`() {
    val sink = RecordingSink()
    RunLogContext.where(sink) { 42 } shouldBe 42
  }

  @Test
  fun `RevomanLog swallows a throwing sink on the hot path`() {
    val sink =
      object : RunLogSink {
        override fun line(level: LogLevel, message: String) = error("line boom")

        override fun event(event: StepEvent) = error("event boom")

        override fun close() = error("close boom")
      }
    RunLogContext.where(sink) {
      RevomanLog.info { "hello" }
      RevomanLog.event(StepEvent.PhaseEntered(Phase.SETUP))
    }
  }

  @Test
  fun `install restore and remove do not exist — binding is structural`() {
    val names = RunLogContext.javaClass.declaredMethods.map { it.name }.toSet()
    names.contains("install") shouldBe false
    names.contains("restore") shouldBe false
    names.contains("remove") shouldBe false
    names.contains("where") shouldBe true
  }
}

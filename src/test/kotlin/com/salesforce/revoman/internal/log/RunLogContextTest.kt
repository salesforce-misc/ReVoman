/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
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
  fun `current is null off-run`() {
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `RevomanLog tees to installed sink`() {
    val sink = RecordingSink()
    RunLogContext.install(sink)
    try {
      RevomanLog.debug { "tracing" }
      RevomanLog.info { "hello" }
      RevomanLog.warn { "careful" }
    } finally {
      RunLogContext.remove()
    }
    sink.lines shouldBe
      listOf(LogLevel.DEBUG to "tracing", LogLevel.INFO to "hello", LogLevel.WARN to "careful")
  }

  @Test
  fun `remove clears the context`() {
    RunLogContext.install(RecordingSink())
    RunLogContext.remove()
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `RevomanLog is a no-op when no sink installed`() {
    RevomanLog.info { "no sink" }
    RunLogContext.current() shouldBe null
  }

  @Test
  fun `install returns previous sink and restore stacks across nesting`() {
    val outer = RecordingSink()
    val inner = RecordingSink()
    // Outer run installs on an empty context — previous is null.
    val beforeOuter = RunLogContext.install(outer)
    try {
      beforeOuter shouldBe null
      RunLogContext.current() shouldBe outer
      // Nested run (e.g. a runbook step's kick) installs its own sink, capturing the outer.
      val beforeInner = RunLogContext.install(inner)
      try {
        beforeInner shouldBe outer
        RunLogContext.current() shouldBe inner
      } finally {
        // Restoring the captured sink brings the OUTER sink back — the old bare `remove()` would
        // instead have wiped it, leaving `current()` null for the rest of the outer run.
        RunLogContext.restore(beforeInner)
      }
      RunLogContext.current() shouldBe outer
    } finally {
      RunLogContext.restore(beforeOuter)
    }
    // restore(null) clears the context, matching the standalone (no outer sink) path.
    RunLogContext.current() shouldBe null
  }
}

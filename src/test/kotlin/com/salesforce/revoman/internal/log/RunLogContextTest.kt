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
      RevomanLog.info { "hello" }
      RevomanLog.warn { "careful" }
    } finally {
      RunLogContext.remove()
    }
    sink.lines shouldBe listOf(LogLevel.INFO to "hello", LogLevel.WARN to "careful")
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
}

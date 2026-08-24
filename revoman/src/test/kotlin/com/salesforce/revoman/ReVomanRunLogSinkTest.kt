/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

/**
 * Pins that [ReVoman.revUp] binds the kick's sink for the call stack only (ScopedValue `where`),
 * never close()s a caller-owned sink, and restores an outer sink when a nested kick returns.
 */
class ReVomanRunLogSinkTest {

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

  private fun kick(sink: RunLogSink = RunLogSink.NoOp) =
    Kick.configure()
      .templatePath("pm-templates/v3/single-ok")
      .dynamicEnvironment("baseUrl", "http://whisper.invalid")
      .httpClient { Response(OK).body("{}") }
      .runLogSink(sink)
      .off()

  @Test
  fun `default NoOp kick leaves no sink bound after revUp`() {
    ReVoman.revUp(kick())
    RunLogContext.current() shouldBe null
    RunLogContext.hasActiveSink() shouldBe false
  }

  @Test
  fun `revUp unbinds the sink and does not close it`() {
    val sink = RecordingSink()
    ReVoman.revUp(kick(sink))
    RunLogContext.current() shouldBe null
    sink.closed shouldBe false
    (sink.lines.isNotEmpty() || sink.events.isNotEmpty()) shouldBe true
  }

  @Test
  fun `nested revUp restores the outer sink when the child kick returns`() {
    val outer = RecordingSink()
    val inner = RecordingSink()
    RunLogContext.where(outer) {
      RunLogContext.current() shouldBe outer
      ReVoman.revUp(kick(inner))
      RunLogContext.current() shouldBe outer
    }
    RunLogContext.current() shouldBe null
    outer.closed shouldBe false
    inner.closed shouldBe false
    inner.events shouldNotBe emptyList<StepEvent>()
  }
}

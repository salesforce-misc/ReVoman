/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CompositeRunLogSinkTest {

  private class Recording : RunLogSink {
    val events = mutableListOf<StepEvent>()
    var closed = false

    override fun line(level: LogLevel, message: String) {}

    override fun event(event: StepEvent) {
      events.add(event)
    }

    override fun close() {
      closed = true
    }
  }

  private class Exploding : RunLogSink {
    override fun line(level: LogLevel, message: String) = error("boom-line")

    override fun event(event: StepEvent) = error("boom-event")

    override fun close() = error("boom-close")
  }

  private val evt = StepEvent.RequestSkipped("s")

  @Test
  fun `fans event and close out to every delegate`() {
    val a = Recording()
    val b = Recording()
    val composite = CompositeRunLogSink.of(a, b)
    composite.event(evt)
    composite.close()
    a.events shouldBe listOf(evt)
    b.events shouldBe listOf(evt)
    a.closed shouldBe true
    b.closed shouldBe true
  }

  @Test
  fun `one throwing delegate does not stop the others or fail the call`() {
    val good = Recording()
    val composite = CompositeRunLogSink.of(Exploding(), good)
    // must not throw
    composite.event(evt)
    composite.line(LogLevel.INFO, "x")
    composite.close()
    good.events shouldBe listOf(evt)
    good.closed shouldBe true
  }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SandboxEventLoopTest {
  @Test
  fun `immediate tasks run FIFO`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<Int>()
    loop.schedule({ order.add(1) }, 0)
    loop.schedule({ order.add(2) }, 0)
    loop.run()
    order shouldContainExactly listOf(1, 2)
  }

  @Test
  fun `timed tasks run in virtual-time order regardless of insertion order`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<String>()
    loop.schedule({ order.add("late") }, 50)
    loop.schedule({ order.add("early") }, 10)
    loop.run()
    order shouldContainExactly listOf("early", "late")
  }

  @Test
  fun `equal-delay timers fire in registration order`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<Int>()
    (1..10).forEach { n -> loop.schedule({ order.add(n) }, 10) }
    loop.run()
    order shouldContainExactly (1..10).toList()
  }

  @Test
  fun `clear cancels a pending timed task`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<String>()
    val id = loop.schedule({ order.add("cancelled") }, 10)
    loop.schedule({ order.add("kept") }, 20)
    loop.clear(id)
    loop.run()
    order shouldContainExactly listOf("kept")
  }

  @Test
  fun `nested scheduling drains fully`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<Int>()
    loop.schedule(
      {
        order.add(1)
        loop.schedule({ order.add(2) }, 0)
      },
      0,
    )
    loop.run()
    order shouldContainExactly listOf(1, 2)
  }

  @Test
  fun `runaway loop throws after backstop`() {
    val loop = SandboxEventLoop()
    var thrown = false
    try {
      lateinit var reschedule: () -> Unit
      reschedule = { loop.schedule({ reschedule() }, 0) }
      reschedule()
      loop.run()
    } catch (e: IllegalStateException) {
      thrown = true
    }
    thrown shouldBe true
  }
}

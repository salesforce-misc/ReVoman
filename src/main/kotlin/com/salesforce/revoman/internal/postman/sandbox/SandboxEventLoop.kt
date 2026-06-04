/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import java.util.PriorityQueue

/**
 * A minimal single-threaded event loop backing the sandbox's `setTimeout`/`setImmediate`/
 * `setInterval` globals (GraalJS provides none natively). Uses virtual time: timed tasks fire in
 * delay order, not wall-clock — deterministic and instant in tests. Confined to the calling thread.
 */
internal class SandboxEventLoop {
  private val ready: ArrayDeque<Runnable> = ArrayDeque()
  private val timers: PriorityQueue<LongArray> = PriorityQueue(compareBy { it[0] })
  private val timerFns: MutableMap<Long, Runnable> = HashMap()
  private var seq: Long = 1
  private var virtualNow: Long = 0

  fun schedule(task: Runnable, delayMs: Long): Long {
    val id = seq++
    if (delayMs <= 0) {
      ready.addLast(Runnable { timerFns.remove(id); task.run() })
    } else {
      timers.add(longArrayOf(virtualNow + delayMs, id))
      timerFns[id] = task
    }
    return id
  }

  fun clear(id: Long) {
    timerFns.remove(id)
  }

  /** Drains ready tasks first, then timed tasks in virtual-time order, until nothing remains. */
  fun run() {
    var guard = 0
    while (true) {
      check(++guard <= RUNAWAY_BACKSTOP) { "sandbox event loop runaway (> $RUNAWAY_BACKSTOP iterations)" }
      ready.removeFirstOrNull()?.let { it.run(); continue }
      val next = timers.poll() ?: break
      val fn = timerFns.remove(next[1]) ?: continue // cancelled
      virtualNow = maxOf(virtualNow, next[0])
      fn.run()
    }
  }

  private companion object {
    const val RUNAWAY_BACKSTOP = 5_000_000
  }
}

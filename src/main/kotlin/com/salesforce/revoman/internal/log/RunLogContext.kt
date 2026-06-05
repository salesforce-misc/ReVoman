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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Holds the [RunLogSink] active for the current [com.salesforce.revoman.ReVoman.revUp] run. A
 * ThreadLocal because a run executes its steps serially on one thread; [install] at the start of a
 * run, [remove] in its `finally`. [current] is `null` outside a run (the default, NoOp-equivalent
 * path), so non-instrumented callers pay nothing.
 *
 * **Callers MUST guarantee [remove] runs (e.g. in a `finally`)** — a skipped remove leaks the sink
 * across thread-pool reuse, mis-routing a later unrelated run's logs into a stale sink.
 */
internal object RunLogContext {
  private val holder = ThreadLocal<RunLogSink?>()

  fun install(sink: RunLogSink) = holder.set(sink)

  fun current(): RunLogSink? = holder.get()

  fun remove() = holder.remove()
}

/**
 * Tee facade over the module logger. Each call logs to KotlinLogging EXACTLY as before (so
 * `suppressed.log` is unchanged) AND, when a run sink is installed, mirrors the same message to it
 * live. The lambda is evaluated at most once even when both sinks are active.
 */
internal object RevomanLog {
  @PublishedApi internal val logger = KotlinLogging.logger {}

  inline fun info(crossinline msg: () -> String) = tee(LogLevel.INFO, msg)

  inline fun warn(crossinline msg: () -> String) = tee(LogLevel.WARN, msg)

  inline fun error(crossinline msg: () -> String) = tee(LogLevel.ERROR, msg)

  fun event(event: StepEvent) {
    RunLogContext.current()?.let { runCatching { it.event(event) } }
  }

  inline fun tee(level: LogLevel, crossinline msg: () -> String) {
    val sink = RunLogContext.current()
    if (sink == null) {
      // Fast path: no run sink — keep KotlinLogging's lazy lambda (msg() not evaluated when the
      // level is disabled), zero extra allocation.
      when (level) {
        LogLevel.INFO -> logger.info { msg() }
        LogLevel.WARN -> logger.warn { msg() }
        LogLevel.ERROR -> logger.error { msg() }
      }
      return
    }
    // Sink present: evaluate the message ONCE, reuse for both the logger and the sink.
    val rendered = msg()
    when (level) {
      LogLevel.INFO -> logger.info { rendered }
      LogLevel.WARN -> logger.warn { rendered }
      LogLevel.ERROR -> logger.error { rendered }
    }
    runCatching { sink.line(level, rendered) }
  }
}

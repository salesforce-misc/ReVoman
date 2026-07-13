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
 * ThreadLocal because a run executes its steps serially on one thread. [install] the sink at the
 * start of a run and [restore] the returned previous sink in its `finally`; this install/restore
 * pair STACKS, so a nested `revUp` (e.g. a runbook driving per-step kicks) does not wipe the outer
 * run's sink. [current] is `null` outside any run (the default, NoOp-equivalent path), so
 * non-instrumented callers pay nothing. [remove] remains for callers that unconditionally clear.
 *
 * **Callers MUST guarantee [restore] (or [remove]) runs (e.g. in a `finally`)** — a skipped restore
 * leaks the sink across thread-pool reuse, mis-routing a later unrelated run's logs into a stale
 * sink.
 */
internal object RunLogContext {
  private val holder = ThreadLocal<RunLogSink?>()

  /**
   * Installs [sink] as current, returning the previously-installed sink (or null) so a nested
   * caller can restore it — makes install/restore stack correctly across nested revUp calls.
   */
  fun install(sink: RunLogSink): RunLogSink? {
    val previous = holder.get()
    holder.set(sink)
    return previous
  }

  fun current(): RunLogSink? = holder.get()

  /**
   * True only when a NON-NoOp sink is installed for the current run. The emit site uses this to
   * SKIP eagerly rendering the (potentially large) HTTP request/response + env-value maps when no
   * real consumer will read them — so the default no-sink path (every library consumer that does
   * not set `runLogSink`) pays zero rendering cost, exactly as before this capture existed.
   */
  fun hasActiveSink(): Boolean {
    val sink = holder.get()
    return sink != null && sink !== RunLogSink.NoOp
  }

  /** Restores a sink captured from [install]; null means "no sink was active", i.e. remove. */
  fun restore(previous: RunLogSink?) {
    if (previous == null) holder.remove() else holder.set(previous)
  }

  fun remove() = holder.remove()
}

/**
 * Tee facade over the module logger. Each call logs to KotlinLogging EXACTLY as before (so
 * `suppressed.log` is unchanged) AND, when a run sink is installed, mirrors the same message to it
 * live. The lambda is evaluated at most once even when both sinks are active.
 */
internal object RevomanLog {
  @PublishedApi internal val logger = KotlinLogging.logger {}

  inline fun debug(crossinline msg: () -> String) = tee(LogLevel.DEBUG, msg)

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
        LogLevel.DEBUG -> logger.debug { msg() }
        LogLevel.INFO -> logger.info { msg() }
        LogLevel.WARN -> logger.warn { msg() }
        LogLevel.ERROR -> logger.error { msg() }
      }
      return
    }
    // Sink present: evaluate the message ONCE, reuse for both the logger and the sink.
    val rendered = msg()
    when (level) {
      LogLevel.DEBUG -> logger.debug { rendered }
      LogLevel.INFO -> logger.info { rendered }
      LogLevel.WARN -> logger.warn { rendered }
      LogLevel.ERROR -> logger.error { rendered }
    }
    runCatching { sink.line(level, rendered) }
  }
}

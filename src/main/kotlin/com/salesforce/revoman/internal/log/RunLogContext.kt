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
 * [ScopedValue] (JEP 506) because the binding's lifetime is the call stack, not the thread: bind
 * with [where] so a nested `revUp` (e.g. a runbook driving per-step kicks) restores the outer sink
 * when the inner frame exits. [current] is `null` outside any [where] (the default, NoOp-equivalent
 * path), so non-instrumented callers pay nothing.
 *
 * Forgotten restore is impossible — there is no install/restore/remove API; the sink cannot outlive
 * the [where] frame, even if the block throws. ReVoman never [RunLogSink.close]s the sink; the
 * caller owns lifecycle.
 */
internal object RunLogContext {
  private val logger = KotlinLogging.logger {}
  private val SINK: ScopedValue<RunLogSink> = ScopedValue.newInstance()

  /**
   * Binds [sink] for the dynamic extent of [block] and restores the previous binding (or unbound)
   * when [block] returns or throws. Nested [where] frames stack.
   */
  fun <T> where(sink: RunLogSink, block: () -> T): T {
    if (sink !== RunLogSink.NoOp) {
      logger.debug {
        val nested = if (SINK.isBound) " (nested over ${SINK.get()::class.simpleName})" else ""
        "binding run-log sink ${sink::class.simpleName}$nested"
      }
    }
    return ScopedValue.where(SINK, sink).call<T, Throwable> { block() }
  }

  fun current(): RunLogSink? = if (SINK.isBound) SINK.get() else null

  /**
   * True only when a NON-NoOp sink is bound for the current run. The emit site uses this to SKIP
   * eagerly rendering the (potentially large) HTTP request/response + env-value maps when no real
   * consumer will read them — so the default no-sink path (every library consumer that does not set
   * `runLogSink`) pays zero rendering cost, exactly as before this capture existed. Unbound and
   * [RunLogSink.NoOp] are both inactive.
   */
  fun hasActiveSink(): Boolean = current().let { it != null && it !== RunLogSink.NoOp }
}

/**
 * Tee facade over the module logger. Each call logs to KotlinLogging EXACTLY as before (so
 * `suppressed.log` is unchanged) AND, when a run sink is bound, mirrors the same message to it
 * live. The lambda is evaluated at most once even when both sinks are active.
 */
internal object RevomanLog {
  @PublishedApi internal val logger = KotlinLogging.logger {}

  inline fun debug(crossinline msg: () -> String) = tee(LogLevel.DEBUG, msg)

  inline fun info(crossinline msg: () -> String) = tee(LogLevel.INFO, msg)

  inline fun warn(crossinline msg: () -> String) = tee(LogLevel.WARN, msg)

  inline fun error(crossinline msg: () -> String) = tee(LogLevel.ERROR, msg)

  fun event(event: StepEvent) {
    RunLogContext.current()?.let {
      // Keep the no-throw contract (a sink MUST NOT fail the hot execution path), but leave a
      // breadcrumb so a rendering bug in the sink isn't completely invisible.
      runCatching { it.event(event) }
        .onFailure { t -> logger.debug { "run-log sink event failed (ignored): $t" } }
    }
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
    // No-throw contract with a breadcrumb (see [event]).
    runCatching { sink.line(level, rendered) }
      .onFailure { t -> logger.debug { "run-log sink line failed (ignored): $t" } }
  }
}

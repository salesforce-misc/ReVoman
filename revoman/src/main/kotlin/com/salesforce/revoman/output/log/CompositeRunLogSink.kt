/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A [RunLogSink] that fans every call out to each of its [delegates], so one run can drive several
 * sinks at once (e.g. a [FileRunLogSink] for the text log AND a [DiagramRunLogSink] for the
 * diagram). Each delegate call is guarded independently: a throwing delegate is swallowed (logged
 * once) and NEVER stops the remaining delegates or fails the run, honoring the [RunLogSink]
 * never-throw contract. The CONSUMER composes this (it owns the concrete sink handles); the library
 * never returns a composite from its own factories.
 */
@Suppress("TooGenericExceptionCaught")
class CompositeRunLogSink(private val delegates: List<RunLogSink>) : RunLogSink {

  override fun line(level: LogLevel, message: String) = delegates.forEach {
    guard { it.line(level, message) }
  }

  override fun event(event: StepEvent) = delegates.forEach { guard { it.event(event) } }

  override fun close() = delegates.forEach { guard { it.close() } }

  private inline fun guard(block: () -> Unit) =
    runCatching(block)
      .onFailure { logger.debug { "CompositeRunLogSink delegate failed (ignored): $it" } }
      .let {}

  companion object {
    /**
     * Compose [sinks] into one fan-out sink. Filters out [RunLogSink.NoOp] delegates (they add
     * nothing); returns the sole real sink directly when only one remains, and [RunLogSink.NoOp]
     * when none do — so the composite is only allocated when it actually fans out.
     */
    @JvmStatic
    fun of(vararg sinks: RunLogSink): RunLogSink {
      val real = sinks.filter { it !== RunLogSink.NoOp }
      return when (real.size) {
        0 -> RunLogSink.NoOp
        1 -> real.first()
        else -> CompositeRunLogSink(real.toList())
      }
    }
  }
}

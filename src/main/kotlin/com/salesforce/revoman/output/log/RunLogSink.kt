/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/** Severity of a teed [RunLogSink.line], mirroring the KotlinLogging level it was emitted at. */
enum class LogLevel {
  DEBUG,
  INFO,
  WARN,
  ERROR,
}

/**
 * A per-run destination for ReVoman's internal narration. ReVoman tees each high-value internal log
 * line and step-boundary event to the active sink IN ADDITION to its normal KotlinLogging output —
 * it never redirects. The sink is installed per [com.salesforce.revoman.ReVoman.revUp] run and
 * removed when that run ends, so it is single-threaded for its lifetime.
 *
 * Reusable by ANY consumer: supply one via [com.salesforce.revoman.input.config.Kick]'s
 * `runLogSink`. The default is [NoOp] (zero overhead, the library behaves exactly as before).
 *
 * Implementations MUST NOT throw from [line]/[event]/[close] in a way that fails the run — ReVoman
 * invokes them on the hot execution path. Swallow your own I/O errors.
 */
interface RunLogSink {
  /** A single narration line at [level]. */
  fun line(level: LogLevel, message: String)

  /** A structured step-boundary [event]. */
  fun event(event: StepEvent)

  /**
   * Flush and release resources. ReVoman NEVER calls this — the CALLER owns the sink's lifecycle
   * and must close it. One caller-supplied sink may span many
   * [com.salesforce.revoman.ReVoman.revUp] runs (e.g. setup + body + cleanup), so ReVoman only
   * borrows it per run and never closes it.
   */
  fun close()

  /** The default no-op sink: the library behaves exactly as it did before this seam existed. */
  object NoOp : RunLogSink {
    override fun line(level: LogLevel, message: String) {}

    override fun event(event: StepEvent) {}

    override fun close() {}
  }
}

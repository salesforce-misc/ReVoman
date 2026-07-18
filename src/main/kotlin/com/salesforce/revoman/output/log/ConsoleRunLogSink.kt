/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.PrintStream

private val logger = KotlinLogging.logger {}

/**
 * A built-in [RunLogSink] that renders the structured [StepEvent] stream to a [PrintStream]
 * (default [System.out]) — the console companion to [RunLogSink.NoOp]. Wire it into any
 * [com.salesforce.revoman.input.config.Kick] via `runLogSink(ConsoleRunLogSink.DEFAULT)` (the
 * shared [System.out] instance) or `runLogSink(ConsoleRunLogSink(myStream))` for a custom stream,
 * to tee each step's boundary event (and, for a finished step, its full HTTP request/response) to
 * stdout so the exchange shows up in a JUnit/Gradle log.
 *
 * [line] is intentionally a no-op: ReVoman already emits every teed narration line via
 * KotlinLogging, so re-printing it here would only duplicate that output. [close] is a no-op too —
 * per the [RunLogSink] contract the CALLER owns the sink's lifecycle, so this sink never closes a
 * stream it did not open (e.g. [System.out]).
 *
 * All rendering is guarded so an I/O error on [out] is swallowed rather than propagated onto
 * ReVoman's hot execution path, honoring the "MUST NOT throw" contract on [RunLogSink].
 */
class ConsoleRunLogSink(private val out: PrintStream = System.out) : RunLogSink {

  /**
   * No-op: KotlinLogging already emits teed narration lines; printing them here would duplicate.
   */
  override fun line(level: LogLevel, message: String) {}

  companion object {
    /**
     * Shared, reusable instance writing to [System.out]. Reference this from a
     * [com.salesforce.revoman.input.config.Kick]'s `runLogSink` instead of allocating a new
     * [ConsoleRunLogSink] per Kick — the sink is stateless (its [out] is `final`, [line]/[close]
     * are no-ops), so a single instance is safe to share across every Kick and revUp run.
     */
    @JvmField val DEFAULT: ConsoleRunLogSink = ConsoleRunLogSink()
  }

  override fun event(event: StepEvent) {
    // Honor the "MUST NOT throw" contract, but leave a breadcrumb so a render/IO bug isn't
    // invisible. All grammar lives in RunLogRenderer — the single source shared with every sink.
    runCatching { out.print(RunLogRenderer.render(event)) }
      .onFailure { logger.debug { "run-log sink render failed (ignored): $it" } }
  }

  /**
   * No-op: the caller owns the sink's lifecycle and this sink never closes a stream it borrowed.
   */
  override fun close() {}
}

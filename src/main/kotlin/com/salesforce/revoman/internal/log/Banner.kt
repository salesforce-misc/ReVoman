/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Prints a one-per-JVM startup banner and a one-per-JVM shutdown "star us" CTA for ReṼoman.
 *
 * On by default, suppressible via the `revoman.banner` system property (wins) or the
 * `REVOMAN_BANNER` env var — `off`/`false`/`0`/`no` silence everything. The banner fires on the
 * FIRST [onRunStart] of the JVM; the CTA is emitted from a shutdown hook registered on that same
 * first call and printed only if at least one run happened.
 *
 * All output routes through [RevomanLog] as ONE multi-line event so log config is honored and the
 * ASCII art is not line-prefixed. Never throws into the run — any failure degrades to a debug
 * breadcrumb. [emitForTest] and the `getProp`/`getEnv` seams keep this unit-testable without a real
 * logger, shutdown hook, or process env.
 */
internal object Banner {
  private val printed = AtomicBoolean(false)
  private val hookRegistered = AtomicBoolean(false)
  private val runCount = AtomicLong(0)
  private val stepCount = AtomicLong(0)

  /** Test seam: where a rendered block is emitted. Defaults to the real logger. */
  internal var emitForTest: (String) -> Unit = { RevomanLog.info { it } }

  private val enabled: Boolean by lazy {
    bannerEnabled(System::getProperty, System::getenv)
  }

  /** Jar manifest `Implementation-Version`; empty when run from loose classes (tests/dev). */
  private val version: String =
    Banner::class.java.`package`?.implementationVersion?.let { "v$it" } ?: ""

  /**
   * Print the banner on the first call of the JVM, register the shutdown-hook CTA (both once), and
   * bump the run counter. Entirely a no-op when suppressed. Never throws.
   */
  fun onRunStart() {
    if (!enabled) return
    runCatching {
        if (printed.compareAndSet(false, true)) {
          emitForTest(bannerText())
        }
        if (hookRegistered.compareAndSet(false, true)) {
          registerShutdownHook()
        }
        runCount.incrementAndGet()
      }
      .onFailure { RevomanLog.logger.debug { "banner onRunStart failed (ignored): $it" } }
  }

  /** Add [steps] to the JVM-wide step tally that the CTA reports. No-op when suppressed. */
  fun recordSteps(steps: Int) {
    if (!enabled) return
    stepCount.addAndGet(steps.toLong())
  }

  /** Registers the shutdown hook that prints the CTA if ≥1 run happened. Isolated for override. */
  private fun registerShutdownHook() {
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          runCatching {
              val runs = runCount.get()
              if (runs > 0) emitForTest(ctaText(runs, stepCount.get()))
            }
            .onFailure { RevomanLog.logger.debug { "banner shutdown CTA failed (ignored): $it" } }
        }
      )
  }

  /** Pure: resolve the on/off decision from the two sources with property-wins precedence. */
  fun bannerEnabled(getProp: (String) -> String?, getEnv: (String) -> String?): Boolean {
    val raw = getProp("revoman.banner") ?: getEnv("REVOMAN_BANNER") ?: return true
    return raw.trim().lowercase() !in OFF_VALUES
  }

  /** Pure: the figlet banner block. Block letters spell "ReVoman"; caption carries the wordmark. */
  fun bannerText(): String {
    val tail = if (version.isEmpty()) "" else " · $version"
    return buildString {
      appendLine()
      appendLine("   ____     __     __")
      appendLine("  |  _ \\ ___\\ \\   / /__  _ __ ___   __ _ _ __")
      appendLine("  | |_) / _ \\\\ \\ / / _ \\| '_ ` _ \\ / _` | '_ \\")
      appendLine("  |  _ <  __/ \\ V / (_) | | | | | | (_| | | | |")
      appendLine("  |_| \\_\\___|  \\_/ \\___/|_| |_| |_|\\__,_|_| |_|")
      appendLine("  ReṼoman · API Orchestration Engine for the JVM$tail")
      append("  ► docs sfdc.co/revoman-docs   ★ star github.com/salesforce-misc/ReVoman")
    }
  }

  /** Pure: the shutdown star CTA, reporting JVM-wide totals with correct pluralization. */
  fun ctaText(runs: Long, steps: Long): String {
    val stepWord = if (steps == 1L) "step" else "steps"
    val runWord = if (runs == 1L) "run" else "runs"
    val rule = "─".repeat(58)
    return buildString {
      appendLine()
      appendLine("  $rule")
      appendLine("  ReṼoman ran $steps $stepWord across $runs $runWord. Useful?")
      appendLine("  ⭐ Star it → github.com/salesforce-misc/ReVoman")
      append("  $rule")
    }
  }

  // ---- test-only seams ------------------------------------------------------
  internal fun resetForTest() {
    printed.set(false)
    hookRegistered.set(false)
    runCount.set(0)
    stepCount.set(0)
    emitForTest = { RevomanLog.info { it } }
  }

  internal fun runCountForTest(): Long = runCount.get()

  internal fun stepCountForTest(): Long = stepCount.get()

  private val OFF_VALUES = setOf("off", "false", "0", "no")
}

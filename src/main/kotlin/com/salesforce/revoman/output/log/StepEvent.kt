/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/**
 * Per-step outcome surfaced to a [RunLogSink]; mirrors a
 * [com.salesforce.revoman.output.report.StepReport].
 */
enum class Outcome {
  SUCCESS,
  FAILED,
  SKIPPED,
}

/**
 * Structured step-boundary events teed to the active [RunLogSink], so a consumer can render either
 * human text or machine-parseable records without scraping free-text log lines.
 */
sealed interface StepEvent {
  val path: String

  data class StepStarted(override val path: String, val name: String) : StepEvent

  data class StepFinished(
    override val path: String,
    val httpStatus: Int?,
    val produced: Set<String>,
    val consumed: Set<String>,
    val tookMs: Long,
    val outcome: Outcome,
    /** Full HTTP request as `\n`-normalized wire text, JSON body pretty-printed; null if absent. */
    val requestMsg: String? = null,
    /**
     * Full HTTP response as `\n`-normalized wire text, JSON body pretty-printed; null on failure.
     */
    val responseMsg: String? = null,
    /** Produced env keys mapped to their post-step values (`toString()`); empty if none. */
    val producedValues: Map<String, String?> = emptyMap(),
    /** Consumed env keys mapped to their post-step values (`toString()`); empty if none. */
    val consumedValues: Map<String, String?> = emptyMap(),
  ) : StepEvent

  data class LedgerSkipped(override val path: String, val reused: Set<String>) : StepEvent

  /** A pre-request `skipRequest()` skipped this step's HTTP dispatch. */
  data class RequestSkipped(override val path: String) : StepEvent

  /** `setNextRequest(name)` jumped from [path] to [toPath]. */
  data class Jumped(override val path: String, val toPath: String) : StepEvent

  /** The run stopped at [path] for [reason] (e.g. `setNextRequest(null)` or loop budget). */
  data class RunStopped(override val path: String, val reason: String) : StepEvent

  /** A jump loop exceeded the per-run execution [budget] at [path]. */
  data class LoopBudgetExceeded(override val path: String, val budget: Int) : StepEvent
}

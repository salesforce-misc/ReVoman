/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase

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
    /** HTTP method of this step's request (e.g. "GET"); null when the step made no request. */
    val method: String? = null,
    /**
     * Request host[:port] — the diagram actor for this step; null when the step made no request.
     */
    val host: String? = null,
    /** Request URI path; null when the step made no request. */
    val requestPath: String? = null,
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

  /** A runbook phase boundary opened. Coarse event bracketing the per-request events below it. */
  data class PhaseEntered(val phase: Phase) : StepEvent {
    override val path: String = phase.name
  }

  /** A runbook step (one whole collection/[com.salesforce.revoman.input.config.Kick]) opened. */
  data class RunbookStepStarted(
    override val path: String,
    val intent: String,
    val phase: Phase,
    val consumes: Set<String>,
    val underTest: Boolean,
  ) : StepEvent

  /** A runbook step finished with [outcome]; [produced] maps declared produced keys to values. */
  data class RunbookStepFinished(
    override val path: String,
    val intent: String,
    val outcome: Outcome,
    val produced: Map<String, String?>,
    val tookMs: Long,
  ) : StepEvent

  /** A runbook step breached its data-flow contract. */
  data class RunbookContractFailed(
    override val path: String,
    val intent: String,
    val missingConsumed: Set<String>,
    val missingProduced: Set<String>,
    val valueMismatches: Map<String, Pair<String?, String?>>,
  ) : StepEvent
}

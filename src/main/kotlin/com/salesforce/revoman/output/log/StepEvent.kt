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
  ) : StepEvent

  data class LedgerSkipped(override val path: String, val reused: Set<String>) : StepEvent
}

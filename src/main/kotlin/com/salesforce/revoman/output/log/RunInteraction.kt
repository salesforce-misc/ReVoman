/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/**
 * One HTTP interaction in a run, the unit a [DiagramRunLogSink] accumulates and [DiagramRenderer]
 * plots. [from] is always `"User"` — ReVoman is the black-box client, so the honest topology is
 * `User -> each distinct host`. [phase]/[intent]/[underTest] carry the enclosing runbook context
 * the sink tracks as it streams events; they are null for a plain (non-runbook) run.
 *
 * @param seq zero-based order of this interaction within the run
 * @param to request host[:port] — the diagram actor for this call
 * @param requestPath request URI path
 * @param status HTTP status code; null on a request that never got a response
 * @param produced env keys this step produced (for data-flow edges)
 * @param consumed env keys this step consumed (for data-flow edges)
 */
data class RunInteraction(
  val seq: Int,
  val from: String,
  val to: String,
  val method: String,
  val requestPath: String,
  val status: Int?,
  val tookMs: Long,
  val outcome: Outcome,
  val produced: Set<String>,
  val consumed: Set<String>,
  val phase: String?,
  val intent: String?,
  val underTest: Boolean,
)

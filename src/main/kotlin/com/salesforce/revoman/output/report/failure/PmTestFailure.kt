/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.PmTestAssertion

/**
 * A failing `pm.test(...)` assertion modeled as an [ExeFailure] so it feeds the same
 * failure/halt/ignore machinery as every other failure. [failedAssertions] are the `passed=false`,
 * non-skipped assertions of this phase; [failure] is a synthesized [AssertionError] whose message
 * concatenates their names + chai errors (mirrors how `PollingFailure` synthesizes its Throwable).
 */
sealed class PmTestFailure : ExeFailure() {
  abstract val failedAssertions: List<PmTestAssertion>

  data class PreReqJsTestFailure(
    override val failure: AssertionError,
    override val failedAssertions: List<PmTestAssertion>,
  ) : PmTestFailure() {
    override val exeType = PRE_REQ_JS
  }

  data class PostResJsTestFailure(
    override val failure: AssertionError,
    override val failedAssertions: List<PmTestAssertion>,
  ) : PmTestFailure() {
    override val exeType = POST_RES_JS
  }
}

private fun message(failed: List<PmTestAssertion>): String =
  failed.joinToString("; ") { "${it.name}: ${it.error ?: "assertion failed"}" }

/**
 * Groups the FAILED (`passed=false`, non-skipped) assertions by phase into 0–2 [PmTestFailure]
 * entries, pre-request first then test (the order a step's scripts run). `skipped` assertions never
 * contribute.
 */
fun buildPmTestFailures(assertions: List<PmTestAssertion>): List<PmTestFailure> {
  val failed = assertions.filter { !it.passed && !it.skipped }
  val preReq = failed.filter { it.exeType == PRE_REQ_JS }
  val postRes = failed.filter { it.exeType == POST_RES_JS }
  return listOfNotNull(
    preReq
      .takeIf { it.isNotEmpty() }
      ?.let {
        PmTestFailure.PreReqJsTestFailure(AssertionError(message(it)), it)
      },
    postRes
      .takeIf { it.isNotEmpty() }
      ?.let {
        PmTestFailure.PostResJsTestFailure(AssertionError(message(it)), it)
      },
  )
}

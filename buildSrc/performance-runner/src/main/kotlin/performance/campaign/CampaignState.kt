/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

import java.nio.file.Path
import java.util.UUID
import performance.capture.CaptureOutcome
import performance.model.CaptureIdentity

/** Runner-owned campaign and performance-session identifiers. */
@ConsistentCopyVisibility
internal data class SessionIdentity private constructor(
  val campaignId: String,
  val performanceSessionId: String,
) {
  init {
    require(SAFE_ID.matches(campaignId))
    require(SAFE_ID.matches(performanceSessionId))
  }

  companion object {
    fun create(campaignId: String): SessionIdentity =
      SessionIdentity(campaignId, "session-${UUID.randomUUID()}")

    internal fun fixed(campaignId: String, performanceSessionId: String): SessionIdentity =
      SessionIdentity(campaignId, performanceSessionId)

    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
  }
}

internal enum class CampaignStatus {
  QUALIFIED,
  INVALID,
  CALIBRATION_EXHAUSTED,
}

internal enum class CampaignFailure {
  PRECONDITION_FAILED,
  RECEIPT_INVALID,
  CAPTURE_INVALID,
  CAPTURE_CONTAMINATED,
  SESSION_DURATION_EXCEEDED,
  CALIBRATION_INVALID,
  CALIBRATION_EXHAUSTED,
}

internal data class CampaignCapture(
  val attemptId: String,
  val role: CaptureRole,
  val forks: Int,
  val identity: CaptureIdentity,
  val operationRoot: Path,
  val preconditioningReceipt: PreconditioningReceipt,
  val outcome: CaptureOutcome,
  val selected: Boolean,
)

/** Explicit A2/B binding for Task 9's mandatory recomputation. */
internal data class ProvisionalCandidateSelection(
  val baselineA2CaptureId: String,
  val candidateBCaptureId: String,
)

internal data class CampaignProvisionalOutcome(
  val status: CampaignStatus,
  val captures: List<CampaignCapture>,
  val preconditioningReceipts: List<PreconditioningReceipt>,
  val selectedA1: CampaignCapture? = null,
  val selectedA2: CampaignCapture? = null,
  val candidate: CampaignCapture? = null,
  val comparisonSelection: ProvisionalCandidateSelection? = null,
  val reason: CampaignFailure? = null,
)

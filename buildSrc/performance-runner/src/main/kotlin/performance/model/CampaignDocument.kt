/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.model

import performance.hash.Sha256
import performance.runner.RunnerExit

internal data class CampaignDocument(
  val campaignId: String,
  val performanceSessionId: String,
  val attempts: List<CampaignAttemptRecord>,
  val selectedAttemptId: String?,
  val candidate: CampaignCandidateRecord?,
  val qualification: CampaignQualificationRecord,
  val implementation: CampaignImplementationRecord,
  val status: CampaignStatus,
)

internal enum class CampaignStatus {
  QUALIFIED,
  EXHAUSTED,
}

internal data class CampaignAttemptRecord(
  val attemptId: String,
  val forks: Int,
  val a1: CampaignCaptureRecord,
  val a2: CampaignCaptureRecord,
  val b: CampaignCaptureRecord?,
  val receipts: List<CampaignReceiptRecord>,
  val calibrationPath: String,
  val calibrationBundleSha256: Sha256,
  val calibrationPassed: Boolean,
)

internal data class CampaignCaptureRecord(
  val role: String,
  val path: String,
  val captureId: String,
  val processRunId: String,
  val sequence: Int,
  val captureSha256: Sha256,
  val bundleSha256: Sha256,
)

internal data class CampaignReceiptRecord(
  val role: String,
  val distribution: String,
  val manifestSha256: Sha256,
  val files: List<CampaignFileFact>,
  val settleMillis: Long,
  val sequence: Long,
)

internal data class CampaignFileFact(
  val path: String,
  val byteLength: Long,
  val sha256: Sha256,
)

internal data class CampaignCandidateRecord(
  val path: String,
  val bundleSha256: Sha256,
  val policySha256: Sha256?,
  val policyOutcome: String,
  val exit: RunnerExit,
)

internal data class CampaignQualificationRecord(
  val policySha256: Sha256,
  val preflight: HostDocumentRef,
  val watcher: HostDocumentRef,
  val postflight: HostDocumentRef,
  val restoration: HostDocumentRef,
)

internal data class CampaignImplementationRecord(
  val runnerSha256: Sha256,
  val protocolSha256: Sha256,
  val adapterSha256: Sha256,
  val comparatorSha256: Sha256,
  val rendererSha256: Sha256,
)

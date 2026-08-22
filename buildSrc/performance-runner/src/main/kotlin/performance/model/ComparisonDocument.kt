/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.model

import performance.compare.CellIdentity
import performance.compare.CompatibilityFailure
import performance.compare.ComparisonCompatibility
import performance.compare.ComparisonExecutionIdentity
import performance.compare.ComparisonKind
import performance.compare.ComparisonStrength
import performance.compare.DirectionOutcome
import performance.compare.PolicyOutcome
import performance.compare.RatioEstimate
import performance.hash.Sha256

/** A strict in-memory comparison report. Task 9 owns its eventual atomic publication. */
internal sealed interface ComparisonReportDocument {
  val schemaVersion: String
  val kind: ComparisonKind
  val strength: ComparisonStrength
  val compatibility: ComparisonCompatibility
  val compatibilityReasons: List<CompatibilityFailure>
  val implementation: ComparisonExecutionIdentity
}

/** Complete compatible comparison evidence with per-cell estimates. */
internal data class ComparisonDocument(
  override val schemaVersion: String,
  override val kind: ComparisonKind,
  override val strength: ComparisonStrength,
  override val compatibility: ComparisonCompatibility,
  override val compatibilityReasons: List<CompatibilityFailure>,
  val baseline: ComparisonCaptureRef,
  val candidate: ComparisonCaptureRef,
  override val implementation: ComparisonExecutionIdentity,
  val cells: List<ComparisonCellResult>,
  val calibration: ComparisonCalibrationRef?,
  val policy: ComparisonPolicyResult,
) : ComparisonReportDocument

/** Fail-closed incompatibility report; it deliberately has no estimate-bearing cell field. */
internal data class IncompatibleComparisonDocument(
  override val schemaVersion: String,
  override val kind: ComparisonKind,
  override val strength: ComparisonStrength,
  override val compatibility: ComparisonCompatibility,
  override val compatibilityReasons: List<CompatibilityFailure>,
  override val implementation: ComparisonExecutionIdentity,
) : ComparisonReportDocument

internal data class ComparisonCaptureRef(
  val captureId: String,
  val captureSha256: Sha256,
  val bundleSha256: Sha256,
  val treatmentGitSha: String,
  val productionSha256: Sha256,
)

internal data class ComparisonCellResult(
  val identity: CellIdentity,
  val estimate: RatioEstimate,
  val direction: DirectionOutcome,
  val policy: PolicyOutcome,
)

internal data class ComparisonCalibrationRef(
  val evidenceSha256: Sha256?,
  val a1CaptureId: String,
  val a2CaptureId: String,
  val bCaptureId: String?,
  val passed: Boolean,
)

internal data class ComparisonPolicyResult(
  val sha256: Sha256?,
  val maximumRegressionBudget: Double?,
  val maximumCandidateBaselineRatio: Double?,
  val outcome: PolicyOutcome,
)

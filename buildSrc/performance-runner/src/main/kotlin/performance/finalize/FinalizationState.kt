/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import java.nio.file.Path
import performance.hash.Sha256
import performance.runner.RunnerExit

internal data class DiagnosticFinalizationRequest(
  val sourceRoot: Path,
  val artifactParent: Path,
  val runToken: String,
  val terminal: RunnerExit = RunnerExit.SUCCESS,
  val profiler: ProfilerFinalizationEvidence? = null,
)

internal data class StandaloneComparisonFinalizationRequest(
  val sourceRoot: Path,
  val artifactParent: Path,
  val runToken: String,
  val terminal: RunnerExit = RunnerExit.SUCCESS,
)

internal data class CampaignFinalizationRequest(
  val sourceRoot: Path,
  val artifactParent: Path,
  val runToken: String,
  val terminal: RunnerExit = RunnerExit.SUCCESS,
)

internal data class FreezeFinalizationRequest(
  val sourceRoot: Path,
  val artifactParent: Path,
  val runToken: String,
  val terminal: RunnerExit = RunnerExit.SUCCESS,
)

internal data class InvalidFinalizationRequest(
  val artifactParent: Path,
  val runToken: String,
  val failure: FinalizationFailure,
)

internal data class ProfilerFinalizationEvidence(
  val operationRoot: Path,
  val intentPath: Path,
  val completionPath: Path,
  val provisionalCaptureSha256: Sha256,
)

internal enum class FinalizationFailure(val exit: RunnerExit) {
  INPUT_OR_PROTOCOL_INVALID(RunnerExit.INPUT_OR_PREFLIGHT_INVALID),
  MEASUREMENT_INVALID(RunnerExit.MEASUREMENT_INVALID),
  COMPARISON_INCOMPATIBLE(RunnerExit.INCOMPATIBLE),
  CALIBRATION_FAILED(RunnerExit.CALIBRATION_FAILED),
  POLICY_FAILED(RunnerExit.POLICY_FAILED),
  POLICY_INCONCLUSIVE(RunnerExit.POLICY_INCONCLUSIVE),
  INTERNAL_ERROR(RunnerExit.INTERNAL_OR_PUBLICATION_FAILED),
  RECOVERY_UNSAFE(RunnerExit.INTERNAL_OR_PUBLICATION_FAILED),
}

internal sealed interface FinalizationOutcome {
  data class Published(val root: Path, val exit: RunnerExit) : FinalizationOutcome

  data class Rejected(val exit: RunnerExit) : FinalizationOutcome
}

internal enum class FinalizationTransition {
  RESERVATION_VERIFIED,
  STAGING_CREATED,
  SOURCE_COPIED,
  MANIFEST_VERIFIED,
  STAGING_DURABLE,
  BEFORE_PUBLICATION,
  AFTER_PUBLICATION,
  TARGET_VERIFIED,
  RESERVATION_REMOVED,
}

internal fun interface FinalizationCheckpoint {
  fun reached(transition: FinalizationTransition)
}

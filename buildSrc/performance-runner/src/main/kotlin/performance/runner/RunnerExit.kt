/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import java.nio.file.Path

/** Stable terminal exit codes for every performance-runner command. */
enum class RunnerExit(val code: Int) {
  SUCCESS(0),
  INPUT_OR_PREFLIGHT_INVALID(2),
  MEASUREMENT_INVALID(3),
  INCOMPATIBLE(4),
  CALIBRATION_FAILED(5),
  POLICY_FAILED(6),
  POLICY_INCONCLUSIVE(7),
  INTERNAL_OR_PUBLICATION_FAILED(8),
}

/** The terminal state of a runner command and its optional published artifact. */
data class RunnerOutcome(
  val exit: RunnerExit,
  val publishedArtifact: Path?,
)

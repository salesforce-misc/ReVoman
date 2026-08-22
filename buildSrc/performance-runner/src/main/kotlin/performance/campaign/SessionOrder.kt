/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

internal enum class CaptureRole(val shortName: String) {
  BASELINE_A1("A1"),
  BASELINE_A2("A2"),
  CANDIDATE_B("B"),
}

internal object SessionOrder {
  val selected: List<CaptureRole> =
    listOf(
      CaptureRole.BASELINE_A1,
      CaptureRole.BASELINE_A2,
      CaptureRole.CANDIDATE_B,
    )
}

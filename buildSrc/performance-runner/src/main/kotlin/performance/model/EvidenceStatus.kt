/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.model

/** Whether an evidence bundle satisfied every structural and semantic validity rule. */
internal enum class EvidenceStatus {
  VALID,
  INVALID,
}

/** The strongest interpretation a capture is permitted to support. */
internal enum class EvidenceStrength {
  CANARY,
  DIAGNOSTIC,
  CANONICAL,
}

/** Evidence strengths available before sealing and host qualification complete. */
internal enum class ProvisionalEvidenceStrength {
  CANARY,
  DIAGNOSTIC,
}

/** Final capture reasons, including the sole claim-bearing campaign qualification. */
internal enum class FinalOutcomeReason {
  BOUNDED_DIAGNOSTIC,
  CONTROLLED_MAC_CAMPAIGN_QUALIFIED,
  GITHUB_HOSTED,
  INVALID_MEASUREMENT,
  PROFILER_DIAGNOSTIC,
  QUALIFICATION_FAILED,
  STRUCTURAL_CANARY,
}

/** Nonclaim reasons available to unpublished canary and diagnostic captures. */
internal enum class ProvisionalOutcomeReason {
  BOUNDED_DIAGNOSTIC,
  GITHUB_HOSTED,
  INVALID_MEASUREMENT,
  PROFILER_DIAGNOSTIC,
  QUALIFICATION_FAILED,
  STRUCTURAL_CANARY,
}

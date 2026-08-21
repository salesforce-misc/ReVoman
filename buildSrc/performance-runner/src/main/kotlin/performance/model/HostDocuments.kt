/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.model

import performance.hash.Sha256

/** A privacy-safe relative reference to a validated host-side document. */
data class HostDocumentRef(
  val path: String,
  val sha256: Sha256,
)

/** Qualification evidence with substrate-specific required fields. */
sealed interface QualificationEvidence {
  val policyHash: Sha256

  /** Full same-session controlled-Mac campaign qualification. */
  data class ControlledMacCampaign(
    override val policyHash: Sha256,
    val preflight: HostDocumentRef,
    val watcher: HostDocumentRef,
    val postflight: HostDocumentRef,
    val restoration: HostDocumentRef,
    val cleanupPassed: Boolean,
  ) : QualificationEvidence

  /** Bounded controlled-Mac qualification that cannot claim campaign-only fields. */
  data class ControlledMacBoundedDiagnostic(
    override val policyHash: Sha256,
    val preflight: HostDocumentRef,
    val watcher: HostDocumentRef,
    val postflight: HostDocumentRef,
    val restoration: HostDocumentRef,
    val campaignFieldsInapplicableReason: String,
  ) : QualificationEvidence

  /** Hosted qualification whose Mac-only fields are explicitly inapplicable. */
  data class GithubHosted(
    override val policyHash: Sha256,
    val setup: HostDocumentRef,
    val cleanup: HostDocumentRef,
    val macFieldsInapplicableReason: String,
  ) : QualificationEvidence
}

/** The normalized outcome of one host qualification check. */
enum class HostCheckStatus {
  PASS,
  FAIL,
}

/** Normalized host memory-pressure observation. */
enum class MemoryPressureState {
  NORMAL,
  WARN,
  CRITICAL,
}

/** Normalized macOS thermal-pressure observation. */
enum class ThermalState {
  NOMINAL,
  FAIR,
  SERIOUS,
  CRITICAL,
}

/** Whether the controlled host is attached to AC or running on battery. */
enum class PowerState {
  AC,
  BATTERY,
}

/** Normative pre/post host state bound to exact container and runtime identities. */
data class HostSnapshot(
  val cpuLoadPercent: Double,
  val cpuIdlePercent: Double,
  val memoryPressure: MemoryPressureState,
  val swapBytes: Long,
  val pageOuts: Long,
  val thermalState: ThermalState,
  val powerState: PowerState,
  val containerFingerprintSha256: Sha256,
  val runtimeFingerprintSha256: Sha256,
)

/** Sanitized preflight observations made before timing begins. */
data class PreflightDocument(
  val observedAtUtc: String,
  val operationId: String,
  val policySha256: Sha256,
  val adapterSha256: Sha256,
  val lockAcquired: Boolean,
  val architecture: String,
  val checks: Map<String, HostCheckStatus>,
  val snapshot: HostSnapshot,
  val userIdleMillis: Long,
)

/** One fixed-cadence privacy-safe watcher observation. */
data class WatcherObservation(
  val observedAtUtc: String,
  val cpuLoadPercent: Double,
  val memoryPressure: MemoryPressureState,
  val swapBytes: Long,
  val pageOuts: Long,
  val thermalState: ThermalState,
  val powerState: PowerState,
  val containerFingerprintSha256: Sha256,
  val runtimeFingerprintSha256: Sha256,
  val event: String,
)

/** Host watcher completeness and bounded observations. */
data class WatcherDocument(
  val startedAtUtc: String,
  val completedAtUtc: String,
  val policySha256: Sha256,
  val cadenceMillis: Long,
  val expectedSamples: Int,
  val observedSamples: Int,
  val terminalState: String,
  val observations: List<WatcherObservation>,
)

/** Sanitized host and child-process checks recorded after timing. */
data class PostflightDocument(
  val observedAtUtc: String,
  val policySha256: Sha256,
  val processExit: Int,
  val checks: Map<String, HostCheckStatus>,
  val snapshot: HostSnapshot,
)

/** Cleanup and allowlisted host-state restoration result. */
data class RestorationDocument(
  val observedAtUtc: String,
  val policySha256: Sha256,
  val cleanupPassed: Boolean,
  val restoredState: String,
  val lockReleaseReady: Boolean,
)

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.model

import java.math.BigDecimal
import performance.hash.Sha256

/** A complete typed final performance-capture document. */
internal data class CaptureDocument(
  val schemaVersion: String,
  val benchmarkProtocolVersion: String,
  val identity: EvidenceIdentity,
  val outcome: CaptureOutcome,
  val provenance: ProvenanceRoles,
  val protocol: ProtocolIdentity,
  val artifacts: CaptureArtifacts,
  val toolchain: ToolchainIdentity,
  val runtime: RuntimeIdentity,
  val qualification: QualificationEvidence,
  val logging: LoggingProfileIdentity,
  val profile: CaptureProfileIdentity,
  val cells: List<CaptureCell>,
  val profilerSummary: ProfilerSummaryRef? = null,
)

/** Typed unpublished capture material that can never carry canonical strength or bundle hashes. */
internal data class ProvisionalCaptureDocument(
  val schemaVersion: String,
  val benchmarkProtocolVersion: String,
  val identity: CaptureIdentity,
  val outcome: ProvisionalCaptureOutcome,
  val provenance: ProvenanceRoles,
  val protocol: ProtocolIdentity,
  val artifacts: CaptureArtifacts,
  val toolchain: ToolchainIdentity,
  val runtime: RuntimeIdentity,
  val logging: LoggingProfileIdentity,
  val profile: CaptureProfileIdentity,
  val cells: List<CaptureCell>,
  val rawProfilerInputSha256: Sha256? = null,
)

/** Capture validity, strength, timing, process exit, and immutable claim reasons. */
internal data class CaptureOutcome(
  val status: EvidenceStatus,
  val strength: EvidenceStrength,
  val claimEligibilityReasons: List<FinalOutcomeReason>,
  val startedAtUtc: String,
  val completedAtUtc: String,
  val processExit: Int,
)

/** Unpublished outcome that cannot represent canonical or campaign-qualified evidence. */
internal data class ProvisionalCaptureOutcome(
  val status: EvidenceStatus,
  val strength: ProvisionalEvidenceStrength,
  val reasons: List<ProvisionalOutcomeReason>,
  val startedAtUtc: String,
  val completedAtUtc: String,
  val processExit: Int,
)

/** Build and evidence-code versions needed to interpret the captured bytes. */
internal data class ToolchainIdentity(
  val gradleVersion: String,
  val jmhPluginVersion: String,
  val jmhCoreVersion: String,
  val kotlinCompilerVersion: String,
  val schemaVersion: String,
  val sanitizerVersion: String,
)

/** Exact JDK identity without a host path. */
internal data class JdkIdentity(
  val binarySha256: Sha256,
  val vendor: String,
  val version: String,
  val jvmArguments: List<String>,
)

/** Immutable platform image identity. */
internal data class OciIdentity(
  val imageReference: String,
  val platformManifestDigest: String,
  val configDigest: String,
)

/** Container-visible Linux identity. */
internal data class LinuxIdentity(
  val os: String,
  val kernel: String,
  val architecture: String,
)

/** Frozen CPU, memory, swap, and process limits. */
internal data class RuntimeLimits(
  val cpuSet: String,
  val memoryBytes: Long,
  val memorySwapBytes: Long,
  val pidLimit: Int,
)

/** Container storage identity and explicit writable mount tokens. */
internal data class StorageIdentity(
  val distributionSource: String,
  val writableMounts: List<String>,
)

/** Network isolation and immutable image-pull behavior. */
internal data class NetworkIdentity(
  val mode: String,
  val pullPolicy: String,
)

/** Container privilege and filesystem security settings. */
internal data class SecurityIdentity(
  val user: String,
  val readOnlyRoot: Boolean,
  val noNewPrivileges: Boolean,
  val capabilities: List<String>,
)

/** Substrate fields differ exactly between controlled Mac and hosted Linux. */
internal sealed interface SubstrateIdentity {
  /** Docker Desktop running on the controlled Mac. */
  data class ControlledMac(
    val macosVersion: String,
    val macosBuild: String,
    val hardwareModelClass: String,
    val dockerDesktopVersion: String,
    val dockerEngineVersion: String,
    val vmResources: AdvertisedResources,
  ) : SubstrateIdentity

  /** GitHub-hosted ARM runner used only for structural or diagnostic evidence. */
  data class GithubHosted(
    val runnerLabel: String,
    val runnerImageVersion: String,
    val kernel: String,
    val dockerEngineVersion: String,
    val advertisedResources: AdvertisedResources,
  ) : SubstrateIdentity
}

/** CPU and memory advertised by the surrounding substrate. */
internal data class AdvertisedResources(
  val cpus: Int,
  val memoryBytes: Long,
)

/** Full measured runtime and privacy-safe host identity. */
internal data class RuntimeIdentity(
  val jdk: JdkIdentity,
  val oci: OciIdentity,
  val linux: LinuxIdentity,
  val limits: RuntimeLimits,
  val storage: StorageIdentity,
  val network: NetworkIdentity,
  val security: SecurityIdentity,
  val environment: Map<String, String>,
  val hostId: String,
  val substrate: SubstrateIdentity,
)

/** Benchmark-only logging configuration identity. */
internal data class LoggingProfileIdentity(
  val profile: String,
  val configurationSha256: Sha256,
)

/** Frozen capture profile and selected profiler variant. */
internal data class CaptureProfileIdentity(
  val family: String,
  val identity: String,
  val variantSha256: Sha256,
  val forks: Int,
  val warmupIterations: Int,
  val measurementIterations: Int,
  val profiler: String,
)

/** Authoritative JMH row reference for one exact cell. */
internal data class JmhResultRowRef(
  val jsonPointer: String,
  val sha256: Sha256,
)

/** Primary metric semantics used by the later comparator. */
internal data class PrimaryMetricIdentity(
  val name: String,
  val direction: String,
)

/** Declared raw-sample dimensions for one cell. */
internal data class SampleDimensions(
  val forks: Int,
  val measurementIterations: Int,
  val samplesPerFork: Int,
)

/** Derived per-fork summary that semantic validation must recompute. */
internal data class ForkSummary(
  val fork: Int,
  val sampleCount: Int,
  val score: BigDecimal,
)

/** One benchmark plus its exact parameters and authoritative observation reference. */
internal data class CaptureCell(
  val benchmark: String,
  val parameters: Map<String, String>,
  val mode: String,
  val unit: String,
  val threads: Int,
  val batchSize: Int,
  val primaryMetric: PrimaryMetricIdentity,
  val jmhResultRow: JmhResultRowRef,
  val sampleDimensions: SampleDimensions,
  val derivedForkSummaries: List<ForkSummary>,
)

/** Optional validated profiler summary and its raw-input binding. */
internal data class ProfilerSummaryRef(
  val path: String,
  val sha256: Sha256,
  val rawInputSha256: Sha256,
  val variantSha256: Sha256,
)

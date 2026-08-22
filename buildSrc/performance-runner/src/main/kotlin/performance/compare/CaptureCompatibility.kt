/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

internal enum class ComparisonKind {
  CALIBRATION,
  CANDIDATE,
}

internal enum class ComparisonStrength {
  DIAGNOSTIC,
}

internal enum class ComparisonCompatibility {
  COMPATIBLE,
  INCOMPATIBLE,
}

internal enum class DirectionOutcome {
  IMPROVEMENT,
  REGRESSION,
  INCONCLUSIVE,
}

internal enum class PolicyOutcome {
  NOT_ENFORCED,
  PASS,
  FAIL,
  INCONCLUSIVE,
}

internal enum class CompatibilityFailure {
  BUNDLE_UNSEALED,
  BUNDLE_SCHEMA_INVALID,
  BUNDLE_CHECKSUM_INVALID,
  BUNDLE_MANIFEST_MISMATCH,
  SAME_CAPTURE,
  IDENTITY_ORDER_INVALID,
  CAPTURE_INVALID,
  PROFILER_PRESENT,
  CELL_SET_MISMATCH,
  CELL_IDENTITY_MISMATCH,
  INVALID_PRIMARY_SAMPLE,
  UNDERSAMPLED_CELL,
  SAMPLE_DIMENSION_MISMATCH,
  DERIVED_SUMMARY_MISMATCH,
  PROTOCOL_MISMATCH,
  QUALIFICATION_POLICY_MISMATCH,
  EXECUTING_IDENTITY_MISMATCH,
  TOOLCHAIN_MISMATCH,
  RUNTIME_MISMATCH,
  QUALIFICATION_KIND_MISMATCH,
  LOGGING_MISMATCH,
  PROFILE_MISMATCH,
  IMMUTABLE_HARNESS_MISMATCH,
  DEPENDENCY_MISMATCH,
  ARTIFACT_MISMATCH,
  CALIBRATION_TREATMENT_MISMATCH,
  CALIBRATION_DISTRIBUTION_MISMATCH,
  CANDIDATE_TREATMENT_NOT_DISTINCT,
  CANDIDATE_PRODUCTION_NOT_DISTINCT,
  CANDIDATE_DELTA_INVALID,
  CALIBRATION_EVIDENCE_MISSING,
  CALIBRATION_EVIDENCE_INVALID,
  CALIBRATION_EVIDENCE_MISMATCH,
}

/** Every executing identity is derived from one freshly validated frozen runner distribution. */
internal data class ComparisonExecutionIdentity(
  val runnerSha256: Sha256,
  val protocolSha256: Sha256,
  val adapterSha256: Sha256,
  val expectedCellsSha256: Sha256,
  val captureSchemaSha256: Sha256,
  val comparisonSchemaSha256: Sha256,
  val bootstrapVectorSha256: Sha256,
  val comparatorSha256: Sha256,
  val rendererSha256: Sha256,
  val qualificationPolicySha256: Sha256,
)

@ConsistentCopyVisibility
internal data class RegressionPolicy private constructor(
  val maximumRegressionBudget: Double,
  val sha256: Sha256,
) {
  init {
    require(maximumRegressionBudget.isFinite() && maximumRegressionBudget >= 0.0)
  }

  companion object {
    fun parse(canonicalBytes: ByteArray): RegressionPolicy {
      val violations =
        EvidenceSchemaValidator().validate(SchemaKind.REGRESSION_POLICY, canonicalBytes)
      require(violations.isEmpty()) {
        "invalid regression policy: ${violations.joinToString { it.keyword }}"
      }
      val document = CanonicalJson.parseStrict(canonicalBytes).asObject()
      require(CanonicalJson.encode(document).contentEquals(canonicalBytes)) {
        "regression policy must use strict canonical bytes"
      }
      return RegressionPolicy(
        maximumRegressionBudget = document.get("maximumRegressionBudget").asDouble(),
        sha256 = Sha256.digest(canonicalBytes),
      )
    }
  }
}

/** The public comparison seam accepts locations and policy only; proofs are verifier-owned. */
internal data class ComparisonRequest(
  val runnerDistribution: Path,
  val kind: ComparisonKind,
  val baseline: Path,
  val candidate: Path,
  val calibration: Path? = null,
  val regressionPolicy: RegressionPolicy? = null,
)

internal object CaptureCompatibility {
  fun validate(
    request: ComparisonRequest,
    baseline: CaptureBundleVerifier.Projection,
    candidate: CaptureBundleVerifier.Projection,
    execution: ComparisonExecutionIdentity,
    distribution: DistributionProjection,
  ): List<CompatibilityFailure> {
    val failures = mutableListOf<CompatibilityFailure>()
    validateDistinctIdentity(baseline, candidate, failures)
    validateCapture(baseline, failures)
    validateCapture(candidate, failures)
    validateCommon(baseline, candidate, execution, distribution, failures)
    when (request.kind) {
      ComparisonKind.CALIBRATION -> validateCalibration(request, baseline, candidate, failures)
      ComparisonKind.CANDIDATE -> validateCandidate(baseline, candidate, failures)
    }
    return failures.distinct().sortedBy(Enum<*>::name)
  }

  private fun validateDistinctIdentity(
    baseline: CaptureBundleVerifier.Projection,
    candidate: CaptureBundleVerifier.Projection,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (
      baseline.identity.captureId == candidate.identity.captureId ||
        baseline.identity.processRunId == candidate.identity.processRunId
    ) {
      failures += CompatibilityFailure.SAME_CAPTURE
    }
  }

  private fun validateCapture(
    capture: CaptureBundleVerifier.Projection,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (
      capture.outcomeStatus != "valid" ||
        capture.processExit != 0 ||
        capture.completedAt.isBefore(capture.startedAt)
    ) {
      failures += CompatibilityFailure.CAPTURE_INVALID
    }
    if (capture.profilerSummaryPresent || capture.profile.profiler != "none") {
      failures += CompatibilityFailure.PROFILER_PRESENT
    }
    if (capture.samples.keys != capture.cells.toSet()) {
      failures += CompatibilityFailure.CELL_SET_MISMATCH
    }
    if (capture.samples.values.any { forkSamples -> forkSamples.size < 10 }) {
      failures += CompatibilityFailure.UNDERSAMPLED_CELL
    }
    if (
      !capture.provenance.treatment.treeClean ||
        !capture.provenance.immutableHarness.treeClean ||
        !capture.provenance.distributionFreezer.treeClean ||
        !capture.provenance.captureRunner.treeClean
    ) {
      failures += CompatibilityFailure.CAPTURE_INVALID
    }
  }

  private fun validateCommon(
    baseline: CaptureBundleVerifier.Projection,
    candidate: CaptureBundleVerifier.Projection,
    execution: ComparisonExecutionIdentity,
    distribution: DistributionProjection,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (
      baseline.schemaVersion != candidate.schemaVersion ||
        baseline.benchmarkProtocolVersion != candidate.benchmarkProtocolVersion ||
        baseline.protocol != candidate.protocol ||
        baseline.protocol.benchmarkSourceSha256 != distribution.benchmarkSourceSha256 ||
        baseline.protocol.workloadTreeSha256 != distribution.workloadTreeSha256
    ) {
      failures += CompatibilityFailure.PROTOCOL_MISMATCH
    }
    if (
      baseline.protocol.qualificationPolicySha256 !=
        candidate.protocol.qualificationPolicySha256 ||
        baseline.protocol.qualificationPolicySha256 != execution.qualificationPolicySha256 ||
        baseline.qualificationPolicySha256 != baseline.protocol.qualificationPolicySha256 ||
        candidate.qualificationPolicySha256 != candidate.protocol.qualificationPolicySha256 ||
        distribution.qualificationPolicies[baseline.qualificationKind] !=
          execution.qualificationPolicySha256
    ) {
      failures += CompatibilityFailure.QUALIFICATION_POLICY_MISMATCH
    }
    if (
      listOf(baseline, candidate).any { capture ->
        capture.protocol.benchmarkProtocolSha256 != execution.protocolSha256 ||
          capture.protocol.hostAdapterSha256 != execution.adapterSha256 ||
          capture.protocol.schemaSha256 != execution.captureSchemaSha256 ||
          capture.protocol.comparatorSha256 != execution.comparatorSha256 ||
          capture.protocol.rendererSha256 != execution.rendererSha256
      }
    ) {
      failures += CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH
    }
    if (baseline.toolchain != candidate.toolchain || !baseline.toolchain.matches(distribution)) {
      failures += CompatibilityFailure.TOOLCHAIN_MISMATCH
    }
    if (
      baseline.runtime != candidate.runtime ||
        !baseline.runtime.matches(distribution, baseline.profile)
    ) {
      failures += CompatibilityFailure.RUNTIME_MISMATCH
    }
    if (baseline.qualificationKind != candidate.qualificationKind) {
      failures += CompatibilityFailure.QUALIFICATION_KIND_MISMATCH
    }
    if (
        baseline.logging != candidate.logging ||
        baseline.logging.profile != "benchmark-noop" ||
        baseline.logging.configurationSha256 != distribution.loggingConfigurationSha256
    ) {
      failures += CompatibilityFailure.LOGGING_MISMATCH
    }
    if (baseline.profile != candidate.profile || !baseline.profile.matches(distribution)) {
      failures += CompatibilityFailure.PROFILE_MISMATCH
    }
    if (baseline.cells != candidate.cells || !baseline.cellsMatchExpected(distribution)) {
      failures += CompatibilityFailure.CELL_IDENTITY_MISMATCH
    }
    if (baseline.provenance.immutableHarness != candidate.provenance.immutableHarness) {
      failures += CompatibilityFailure.IMMUTABLE_HARNESS_MISMATCH
    }
    if (
      baseline.provenance.treatment != distribution.provenance.treatment ||
        baseline.provenance.immutableHarness != distribution.provenance.immutableHarness ||
        baseline.provenance.distributionFreezer != distribution.provenance.distributionFreezer ||
        candidate.provenance.immutableHarness != distribution.provenance.immutableHarness
    ) {
      failures += CompatibilityFailure.ARTIFACT_MISMATCH
    }
    if (baseline.artifacts.dependencies != candidate.artifacts.dependencies) {
      failures += CompatibilityFailure.DEPENDENCY_MISMATCH
    }
    if (
      baseline.artifacts.benchmark != candidate.artifacts.benchmark ||
        baseline.artifacts.executingRunner != candidate.artifacts.executingRunner ||
        baseline.artifacts.orderedRunnerClasspath != candidate.artifacts.orderedRunnerClasspath ||
        !baseline.artifacts.matchesBaselineDistribution(distribution) ||
        !candidate.artifacts.matchesCandidateProjection(distribution)
    ) {
      failures += CompatibilityFailure.ARTIFACT_MISMATCH
    }
  }

  private fun validateCalibration(
    request: ComparisonRequest,
    baseline: CaptureBundleVerifier.Projection,
    candidate: CaptureBundleVerifier.Projection,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (request.calibration != null) failures += CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH
    if (baseline.provenance.treatment != candidate.provenance.treatment) {
      failures += CompatibilityFailure.CALIBRATION_TREATMENT_MISMATCH
    }
    if (
      baseline.artifacts.production != candidate.artifacts.production ||
        baseline.artifacts.distribution != candidate.artifacts.distribution ||
        baseline.artifacts.orderedClasspath != candidate.artifacts.orderedClasspath ||
        baseline.provenance.distributionFreezer != candidate.provenance.distributionFreezer ||
        baseline.provenance.captureRunner != candidate.provenance.captureRunner
    ) {
      failures += CompatibilityFailure.CALIBRATION_DISTRIBUTION_MISMATCH
    }
    if (
      baseline.identity.performanceSessionId != candidate.identity.performanceSessionId ||
        candidate.identity.sessionSequence != baseline.identity.sessionSequence + 1 ||
        candidate.startedAt.isBefore(baseline.completedAt) ||
        Duration.between(baseline.startedAt, candidate.completedAt) > MAX_SESSION_DURATION
    ) {
      failures += CompatibilityFailure.IDENTITY_ORDER_INVALID
    }
  }

  private fun validateCandidate(
    baseline: CaptureBundleVerifier.Projection,
    candidate: CaptureBundleVerifier.Projection,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (baseline.provenance.treatment.gitSha == candidate.provenance.treatment.gitSha) {
      failures += CompatibilityFailure.CANDIDATE_TREATMENT_NOT_DISTINCT
    }
    if (baseline.artifacts.production.sha256 == candidate.artifacts.production.sha256) {
      failures += CompatibilityFailure.CANDIDATE_PRODUCTION_NOT_DISTINCT
    }
    if (
      !baseline.provenance.treatment.treeClean ||
        !candidate.provenance.treatment.treeClean ||
        !baseline.provenance.distributionFreezer.treeClean ||
        !candidate.provenance.distributionFreezer.treeClean ||
        !baseline.provenance.captureRunner.treeClean ||
        !candidate.provenance.captureRunner.treeClean ||
        baseline.artifacts.distribution.path != candidate.artifacts.distribution.path ||
        baseline.artifacts.distribution.sha256 == candidate.artifacts.distribution.sha256 ||
        !validCandidateClasspathDelta(
          baseline.artifacts.orderedClasspath,
          candidate.artifacts.orderedClasspath,
          baseline.artifacts.production,
          candidate.artifacts.production,
        )
    ) {
      failures += CompatibilityFailure.CANDIDATE_DELTA_INVALID
    }
  }

  private fun validCandidateClasspathDelta(
    baseline: List<ArtifactProjection>,
    candidate: List<ArtifactProjection>,
    baselineProduction: ArtifactProjection,
    candidateProduction: ArtifactProjection,
  ): Boolean {
    if (
      baseline.size != candidate.size ||
        baselineProduction.path != candidateProduction.path ||
        baseline.count { it.path == baselineProduction.path } != 1 ||
        candidate.count { it.path == candidateProduction.path } != 1
    ) {
      return false
    }
    return baseline.zip(candidate).all { (left, right) ->
      left.path == right.path &&
        if (left.path == baselineProduction.path) {
          left == baselineProduction && right == candidateProduction
        } else {
          left == right
        }
    }
  }

  private val MAX_SESSION_DURATION = Duration.ofHours(2)
}

/** Frozen A/A predicate shared without exposing the sealed comparison boundary. */
internal object CalibrationQualification {
  fun passes(point: Double, lower: Double, upper: Double): Boolean {
    require(
      point.isFinite() && lower.isFinite() && upper.isFinite() && lower > 0.0 && upper >= lower,
    )
    val width = BigDecimal.valueOf(upper).subtract(BigDecimal.valueOf(lower))
    return lower <= UNITY &&
      upper >= UNITY &&
      point in POINT_MIN..POINT_MAX &&
      width <= BigDecimal.valueOf(MAX_WIDTH)
  }

  private const val UNITY = 1.0
  private const val POINT_MIN = 0.95
  private const val POINT_MAX = 1.05
  private const val MAX_WIDTH = 0.10
}

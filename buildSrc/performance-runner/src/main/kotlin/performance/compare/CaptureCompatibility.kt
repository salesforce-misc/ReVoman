/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Path
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.ArtifactIdentity
import performance.model.CaptureCell
import performance.model.CaptureDocument
import performance.model.EvidenceStatus
import performance.model.GitProvenance
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

enum class ComparisonKind {
  CALIBRATION,
  CANDIDATE,
}

enum class ComparisonStrength {
  DIAGNOSTIC,
}

enum class ComparisonCompatibility {
  COMPATIBLE,
  INCOMPATIBLE,
}

enum class DirectionOutcome {
  IMPROVEMENT,
  REGRESSION,
  INCONCLUSIVE,
}

enum class PolicyOutcome {
  NOT_ENFORCED,
  PASS,
  FAIL,
  INCONCLUSIVE,
}

enum class CompatibilityFailure {
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

enum class BundleVerificationFailure(
  val compatibilityFailure: CompatibilityFailure,
) {
  UNSEALED(CompatibilityFailure.BUNDLE_UNSEALED),
  SCHEMA_INVALID(CompatibilityFailure.BUNDLE_SCHEMA_INVALID),
  CHECKSUM_INVALID(CompatibilityFailure.BUNDLE_CHECKSUM_INVALID),
}

data class CaptureBundleManifest(
  val treatment: GitProvenance,
  val production: ArtifactIdentity,
  val distribution: ArtifactIdentity,
  val captureSha256: Sha256,
)

/** Proof boundary: provisional captures have no subtype and cannot reach [CaptureComparator]. */
sealed interface CaptureBundleProof {
  val root: Path

  /** Constructed by the module's verifier after schema, checksum, and sample binding succeed. */
  @ConsistentCopyVisibility
  data class Verified internal constructor(
    override val root: Path,
    val document: CaptureDocument,
    val captureSha256: Sha256,
    val bundleSha256: Sha256,
    val manifest: CaptureBundleManifest,
    val samples: Map<CellIdentity, List<ForkSamples>>,
  ) : CaptureBundleProof

  @ConsistentCopyVisibility
  data class Rejected internal constructor(
    override val root: Path,
    val failures: List<BundleVerificationFailure>,
  ) : CaptureBundleProof

  companion object {
    internal fun rejected(root: Path, failures: List<BundleVerificationFailure>): Rejected =
      Rejected(root.toAbsolutePath().normalize(), failures.distinct())
  }
}

data class ComparisonExecutionIdentity(
  val comparatorSha256: Sha256,
  val rendererSha256: Sha256,
  val schemaSha256: Sha256,
  val bootstrapVectorSha256: Sha256,
  val qualificationPolicySha256: Sha256,
)

sealed interface CalibrationComparisonProof {
  val root: Path
}

/** Constructed only after a passing A1/A2 comparison bundle is schema/checksum verified. */
@ConsistentCopyVisibility
data class VerifiedCalibrationComparison internal constructor(
  override val root: Path,
  val sha256: Sha256,
  val a1CaptureId: String,
  val a2CaptureId: String,
  val bCaptureId: String,
  val performanceSessionId: String,
  val a1Sequence: Int,
  val a2Sequence: Int,
  val bSequence: Int,
  val passingCells: Set<CellIdentity>,
) : CalibrationComparisonProof

@ConsistentCopyVisibility
data class RejectedCalibrationComparison internal constructor(
  override val root: Path,
  val failures: List<BundleVerificationFailure>,
) : CalibrationComparisonProof

/** Constructed only after verifying the selected runner distribution and embedded identities. */
@ConsistentCopyVisibility
data class VerifiedComparisonExecution internal constructor(
  val identity: ComparisonExecutionIdentity,
)

@ConsistentCopyVisibility
data class RegressionPolicy private constructor(
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
      return RegressionPolicy(
        maximumRegressionBudget = document.get("maximumRegressionBudget").asDouble(),
        sha256 = Sha256.digest(canonicalBytes),
      )
    }
  }
}

data class ComparisonRequest(
  val kind: ComparisonKind,
  val baseline: CaptureBundleProof,
  val candidate: CaptureBundleProof,
  val execution: VerifiedComparisonExecution,
  val calibration: CalibrationComparisonProof? = null,
  val regressionPolicy: RegressionPolicy? = null,
)

internal object CaptureCompatibility {
  fun validate(request: ComparisonRequest): List<CompatibilityFailure> {
    val rejected =
      listOf(request.baseline, request.candidate)
        .filterIsInstance<CaptureBundleProof.Rejected>()
        .flatMap { proof -> proof.failures.map(BundleVerificationFailure::compatibilityFailure) }
    if (rejected.isNotEmpty()) return rejected.distinct().sortedBy(Enum<*>::name)
    if (request.calibration is RejectedCalibrationComparison) {
      return listOf(CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID)
    }
    val baseline = request.baseline as CaptureBundleProof.Verified
    val candidate = request.candidate as CaptureBundleProof.Verified
    val failures = mutableListOf<CompatibilityFailure>()
    validateOwnBundle(baseline, failures)
    validateOwnBundle(candidate, failures)
    validateDistinctIdentity(baseline, candidate, failures)
    validateCapture(baseline, failures)
    validateCapture(candidate, failures)
    validateCommon(request, baseline, candidate, failures)
    when (request.kind) {
      ComparisonKind.CALIBRATION -> validateCalibration(request, baseline, candidate, failures)
      ComparisonKind.CANDIDATE -> validateCandidate(request, baseline, candidate, failures)
    }
    return failures.distinct().sortedBy(Enum<*>::name)
  }

  private fun validateOwnBundle(
    capture: CaptureBundleProof.Verified,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (
      capture.manifest.captureSha256 != capture.captureSha256 ||
        capture.manifest.treatment != capture.document.provenance.treatment ||
        capture.manifest.production != capture.document.artifacts.production ||
        capture.manifest.distribution != capture.document.artifacts.distribution
    ) {
      failures += CompatibilityFailure.BUNDLE_MANIFEST_MISMATCH
    }
  }

  private fun validateDistinctIdentity(
    baseline: CaptureBundleProof.Verified,
    candidate: CaptureBundleProof.Verified,
    failures: MutableList<CompatibilityFailure>,
  ) {
    if (
      baseline.root == candidate.root ||
        baseline.document.identity.captureId == candidate.document.identity.captureId ||
        baseline.document.identity.processRunId == candidate.document.identity.processRunId
    ) {
      failures += CompatibilityFailure.SAME_CAPTURE
    }
  }

  private fun validateCapture(
    capture: CaptureBundleProof.Verified,
    failures: MutableList<CompatibilityFailure>,
  ) {
    val document = capture.document
    if (document.outcome.status != EvidenceStatus.VALID || document.outcome.processExit != 0) {
      failures += CompatibilityFailure.CAPTURE_INVALID
    }
    if (document.profilerSummary != null || document.profile.profiler != "none") {
      failures += CompatibilityFailure.PROFILER_PRESENT
    }
    val declared = document.cells.map { cellIdentity(document, it) }
    if (declared.distinct().size != declared.size || capture.samples.keys != declared.toSet()) {
      failures += CompatibilityFailure.CELL_SET_MISMATCH
      return
    }
    document.cells.zip(declared).forEach { (cell, identity) ->
      val forks = capture.samples.getValue(identity)
      if (forks.size < MINIMUM_FORKS) failures += CompatibilityFailure.UNDERSAMPLED_CELL
      val dimensionsMismatch =
        cell.sampleDimensions.forks != document.profile.forks ||
          forks.size != cell.sampleDimensions.forks ||
          cell.sampleDimensions.measurementIterations != document.profile.measurementIterations ||
          cell.sampleDimensions.samplesPerFork != document.profile.measurementIterations ||
          forks.any { it.measurements.size != cell.sampleDimensions.samplesPerFork }
      if (dimensionsMismatch) {
        failures += CompatibilityFailure.SAMPLE_DIMENSION_MISMATCH
      }
      val samplesInvalid =
        forks.flatMap(ForkSamples::measurements).any { !it.isFinite() || it <= 0.0 }
      if (samplesInvalid) {
        failures += CompatibilityFailure.INVALID_PRIMARY_SAMPLE
      } else if (!dimensionsMismatch && !summariesMatch(cell, forks)) {
        failures += CompatibilityFailure.DERIVED_SUMMARY_MISMATCH
      }
    }
  }

  private fun summariesMatch(cell: CaptureCell, forks: List<ForkSamples>): Boolean {
    if (cell.derivedForkSummaries.size != forks.size) return false
    return cell.derivedForkSummaries.zip(forks).withIndex().all { (index, pair) ->
      val (summary, samples) = pair
      val mean =
        samples.measurements
          .map(BigDecimal::valueOf)
          .reduce(BigDecimal::add)
          .divide(BigDecimal(samples.measurements.size), MathContext.DECIMAL128)
      summary.fork == index + 1 &&
        summary.sampleCount == samples.measurements.size &&
        summary.score.compareTo(mean) == 0
    }
  }

  private fun validateCommon(
    request: ComparisonRequest,
    baseline: CaptureBundleProof.Verified,
    candidate: CaptureBundleProof.Verified,
    failures: MutableList<CompatibilityFailure>,
  ) {
    val left = baseline.document
    val right = candidate.document
    if (
      left.schemaVersion != right.schemaVersion ||
        left.benchmarkProtocolVersion != right.benchmarkProtocolVersion ||
        left.protocol != right.protocol
    ) {
      failures += CompatibilityFailure.PROTOCOL_MISMATCH
    }
    if (
      left.protocol.qualificationPolicySha256 != right.protocol.qualificationPolicySha256 ||
        left.protocol.qualificationPolicySha256 !=
          request.execution.identity.qualificationPolicySha256 ||
        left.protocol.qualificationPolicySha256 != left.qualification.policyHash ||
        right.protocol.qualificationPolicySha256 != right.qualification.policyHash
    ) {
      failures += CompatibilityFailure.QUALIFICATION_POLICY_MISMATCH
    }
    if (
      listOf(left, right).any {
        it.protocol.comparatorSha256 != request.execution.identity.comparatorSha256 ||
          it.protocol.rendererSha256 != request.execution.identity.rendererSha256 ||
          it.protocol.schemaSha256 != request.execution.identity.schemaSha256
      }
    ) {
      failures += CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH
    }
    if (left.toolchain != right.toolchain) failures += CompatibilityFailure.TOOLCHAIN_MISMATCH
    if (left.runtime != right.runtime) {
      failures += CompatibilityFailure.RUNTIME_MISMATCH
    }
    if (qualificationKind(left.qualification) != qualificationKind(right.qualification)) {
      failures += CompatibilityFailure.QUALIFICATION_KIND_MISMATCH
    }
    if (left.logging != right.logging) failures += CompatibilityFailure.LOGGING_MISMATCH
    if (left.profile != right.profile) failures += CompatibilityFailure.PROFILE_MISMATCH
    if (left.cells.map { cellIdentity(left, it) } != right.cells.map { cellIdentity(right, it) }) {
      failures += CompatibilityFailure.CELL_IDENTITY_MISMATCH
    }
    if (
      left.provenance.immutableHarness != right.provenance.immutableHarness ||
        request.kind == ComparisonKind.CALIBRATION &&
          left.provenance.captureRunner != right.provenance.captureRunner
    ) {
      failures += CompatibilityFailure.IMMUTABLE_HARNESS_MISMATCH
    }
    if (left.artifacts.dependencies != right.artifacts.dependencies) {
      failures += CompatibilityFailure.DEPENDENCY_MISMATCH
    }
    if (
      left.artifacts.benchmark != right.artifacts.benchmark ||
        left.artifacts.executingRunner != right.artifacts.executingRunner ||
        left.artifacts.orderedRunnerClasspath != right.artifacts.orderedRunnerClasspath
    ) {
      failures += CompatibilityFailure.ARTIFACT_MISMATCH
    }
  }

  private fun validateCalibration(
    request: ComparisonRequest,
    baseline: CaptureBundleProof.Verified,
    candidate: CaptureBundleProof.Verified,
    failures: MutableList<CompatibilityFailure>,
  ) {
    val left = baseline.document
    val right = candidate.document
    if (request.calibration != null) failures += CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH
    if (left.provenance.treatment != right.provenance.treatment) {
      failures += CompatibilityFailure.CALIBRATION_TREATMENT_MISMATCH
    }
    if (
      left.artifacts.production != right.artifacts.production ||
        left.artifacts.distribution != right.artifacts.distribution ||
        left.artifacts.orderedClasspath != right.artifacts.orderedClasspath ||
        left.provenance.distributionFreezer != right.provenance.distributionFreezer
    ) {
      failures += CompatibilityFailure.CALIBRATION_DISTRIBUTION_MISMATCH
    }
    if (
      left.identity.performanceSessionId != right.identity.performanceSessionId ||
        right.identity.sessionSequence != left.identity.sessionSequence + 1
    ) {
      failures += CompatibilityFailure.IDENTITY_ORDER_INVALID
    }
  }

  private fun validateCandidate(
    request: ComparisonRequest,
    baseline: CaptureBundleProof.Verified,
    candidate: CaptureBundleProof.Verified,
    failures: MutableList<CompatibilityFailure>,
  ) {
    val left = baseline.document
    val right = candidate.document
    if (left.provenance.treatment.gitSha == right.provenance.treatment.gitSha) {
      failures += CompatibilityFailure.CANDIDATE_TREATMENT_NOT_DISTINCT
    }
    if (left.artifacts.production.sha256 == right.artifacts.production.sha256) {
      failures += CompatibilityFailure.CANDIDATE_PRODUCTION_NOT_DISTINCT
    }
    if (
      !left.provenance.treatment.treeClean ||
        !right.provenance.treatment.treeClean ||
        !left.provenance.distributionFreezer.treeClean ||
        !right.provenance.distributionFreezer.treeClean ||
        !left.provenance.captureRunner.treeClean ||
        !right.provenance.captureRunner.treeClean ||
        left.artifacts.distribution.path != right.artifacts.distribution.path ||
        left.artifacts.distribution.sha256 == right.artifacts.distribution.sha256 ||
        !validCandidateClasspathDelta(
        left.artifacts.orderedClasspath,
        right.artifacts.orderedClasspath,
        left.artifacts.production,
        right.artifacts.production,
      )
    ) {
      failures += CompatibilityFailure.CANDIDATE_DELTA_INVALID
    }
    val calibration = request.calibration as? VerifiedCalibrationComparison
    if (calibration == null) {
      failures += CompatibilityFailure.CALIBRATION_EVIDENCE_MISSING
      return
    }
    val requiredCells = left.cells.map { cellIdentity(left, it) }.toSet()
    if (
      calibration.a2CaptureId != left.identity.captureId ||
        calibration.bCaptureId != right.identity.captureId ||
        calibration.performanceSessionId != left.identity.performanceSessionId ||
        calibration.performanceSessionId != right.identity.performanceSessionId ||
        calibration.a2Sequence != left.identity.sessionSequence ||
        calibration.bSequence != right.identity.sessionSequence ||
        calibration.a2Sequence != calibration.a1Sequence + 1 ||
        calibration.bSequence != calibration.a2Sequence + 1 ||
        calibration.passingCells != requiredCells
    ) {
      failures += CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH
    }
  }

  private fun validCandidateClasspathDelta(
    baseline: List<ArtifactIdentity>,
    candidate: List<ArtifactIdentity>,
    baselineProduction: ArtifactIdentity,
    candidateProduction: ArtifactIdentity,
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

  internal fun cellIdentity(document: CaptureDocument, cell: CaptureCell): CellIdentity =
    CellIdentity(
      benchmark = cell.benchmark,
      profile = document.profile.family,
      parameters = cell.parameters,
      mode = cell.mode,
      unit = cell.unit,
      threads = cell.threads,
      batchSize = cell.batchSize,
      primaryMetric = cell.primaryMetric.name,
      direction =
        when (cell.primaryMetric.direction) {
          "lowerIsBetter" -> "lower-is-better"
          else -> cell.primaryMetric.direction
        },
    )

  private fun qualificationKind(qualification: performance.model.QualificationEvidence): String =
    when (qualification) {
      is performance.model.QualificationEvidence.ControlledMacCampaign -> "controlledMacCampaign"
      is performance.model.QualificationEvidence.ControlledMacBoundedDiagnostic ->
        "controlledMacBoundedDiagnostic"
      is performance.model.QualificationEvidence.GithubHosted -> "githubHosted"
    }

  private const val MINIMUM_FORKS = 10
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

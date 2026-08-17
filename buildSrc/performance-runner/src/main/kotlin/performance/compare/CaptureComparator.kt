/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.util.HexFormat
import performance.model.ComparisonCalibrationRef
import performance.model.ComparisonCaptureRef
import performance.model.ComparisonCellResult
import performance.model.ComparisonDocument
import performance.model.ComparisonPolicyResult
import performance.model.IncompatibleComparisonDocument
import performance.runner.RunnerExit

sealed interface ComparisonComputation {
  class Completed(
    val document: ComparisonDocument,
    val jsonBytes: ByteArray,
    val markdownBytes: ByteArray,
    val exit: RunnerExit,
  ) : ComparisonComputation

  class Incompatible(
    val document: IncompatibleComparisonDocument,
    val jsonBytes: ByteArray,
    val markdownBytes: ByteArray,
    val reasons: List<CompatibilityFailure>,
    val estimatesCreated: Boolean = false,
    val exit: RunnerExit = RunnerExit.INCOMPATIBLE,
  ) : ComparisonComputation

  data class InternalFailure(
    val exit: RunnerExit = RunnerExit.INTERNAL_OR_PUBLICATION_FAILED,
  ) : ComparisonComputation
}

/** Compatibility-first comparison over checksum/schema-validated sealed captures only. */
class CaptureComparator(
  private val renderer: ComparisonRenderer = ComparisonRenderer(),
) {
  fun compare(request: ComparisonRequest): ComparisonComputation =
    runCatching {
      val failures = CaptureCompatibility.validate(request)
      if (failures.isNotEmpty()) {
        val document =
          IncompatibleComparisonDocument(
            schemaVersion = COMPARISON_SCHEMA_VERSION,
            kind = request.kind,
            strength = ComparisonStrength.DIAGNOSTIC,
            compatibility = ComparisonCompatibility.INCOMPATIBLE,
            compatibilityReasons = failures,
            implementation = request.execution.identity,
          )
        val rendered = renderer.render(document)
        return@runCatching ComparisonComputation.Incompatible(
          document = document,
          jsonBytes = rendered.json,
          markdownBytes = rendered.markdown,
          reasons = failures,
        )
      }
      val baseline = request.baseline as CaptureBundleProof.Verified
      val candidate = request.candidate as CaptureBundleProof.Verified
      val cells =
        baseline.samples.keys.sortedBy { identity -> identity.canonicalBytes().toHex() }.map {
          identity ->
          val estimate =
            BootstrapV1.estimate(
              baseline.document.identity.captureId,
              candidate.document.identity.captureId,
              cell = identity,
              baseline = baseline.samples.getValue(identity),
              candidate = candidate.samples.getValue(identity),
            )
          ComparisonCellResult(
            identity = identity,
            estimate = estimate,
            direction = direction(estimate.lower95Ratio, estimate.upper95Ratio),
            policy = policy(estimate, request.regressionPolicy),
          )
        }
      val calibrationPassed =
        request.kind == ComparisonKind.CALIBRATION && cells.all(::calibrationCellPassed)
      val overallPolicy = overallPolicy(cells, request.regressionPolicy)
      val document =
        ComparisonDocument(
          schemaVersion = COMPARISON_SCHEMA_VERSION,
          kind = request.kind,
          strength = ComparisonStrength.DIAGNOSTIC,
          compatibility = ComparisonCompatibility.COMPATIBLE,
          compatibilityReasons = emptyList(),
          baseline = captureRef(baseline),
          candidate = captureRef(candidate),
          implementation = request.execution.identity,
          cells = cells,
          calibration = calibrationRef(request, calibrationPassed),
          policy =
            ComparisonPolicyResult(
              sha256 = request.regressionPolicy?.sha256,
              maximumRegressionBudget = request.regressionPolicy?.maximumRegressionBudget,
              maximumCandidateBaselineRatio =
                request.regressionPolicy?.let { UNITY + it.maximumRegressionBudget },
              outcome = overallPolicy,
            ),
        )
      val rendered = renderer.render(document)
      ComparisonComputation.Completed(
        document = document,
        jsonBytes = rendered.json,
        markdownBytes = rendered.markdown,
        exit = exit(request.kind, calibrationPassed, overallPolicy, request.regressionPolicy),
      )
    }
      .getOrElse { ComparisonComputation.InternalFailure() }

  private fun calibrationRef(
    request: ComparisonRequest,
    calibrationPassed: Boolean,
  ): ComparisonCalibrationRef =
    if (request.kind == ComparisonKind.CALIBRATION) {
      val baseline = request.baseline as CaptureBundleProof.Verified
      val candidate = request.candidate as CaptureBundleProof.Verified
      ComparisonCalibrationRef(
        evidenceSha256 = null,
        a1CaptureId = baseline.document.identity.captureId,
        a2CaptureId = candidate.document.identity.captureId,
        bCaptureId = null,
        passed = calibrationPassed,
      )
    } else {
      val calibration = checkNotNull(request.calibration) as VerifiedCalibrationComparison
      ComparisonCalibrationRef(
        evidenceSha256 = calibration.sha256,
        a1CaptureId = calibration.a1CaptureId,
        a2CaptureId = calibration.a2CaptureId,
        bCaptureId = calibration.bCaptureId,
        passed = true,
      )
    }

  private fun captureRef(capture: CaptureBundleProof.Verified): ComparisonCaptureRef =
    ComparisonCaptureRef(
      captureId = capture.document.identity.captureId,
      captureSha256 = capture.captureSha256,
      bundleSha256 = capture.bundleSha256,
      treatmentGitSha = capture.document.provenance.treatment.gitSha,
      productionSha256 = capture.document.artifacts.production.sha256,
    )

  private fun calibrationCellPassed(cell: ComparisonCellResult): Boolean =
    CalibrationQualification.passes(
      cell.estimate.pointRatio,
      cell.estimate.lower95Ratio,
      cell.estimate.upper95Ratio,
    )

  private fun policy(
    estimate: RatioEstimate,
    regressionPolicy: RegressionPolicy?,
  ): PolicyOutcome =
    regressionPolicy?.let {
      policyForTesting(
        estimate.lower95Ratio,
        estimate.upper95Ratio,
        it.maximumRegressionBudget,
      )
    } ?: PolicyOutcome.NOT_ENFORCED

  private fun overallPolicy(
    cells: List<ComparisonCellResult>,
    regressionPolicy: RegressionPolicy?,
  ): PolicyOutcome =
    if (regressionPolicy == null) {
      PolicyOutcome.NOT_ENFORCED
    } else {
      aggregatePolicyForTesting(cells.map(ComparisonCellResult::policy))
    }

  private fun exit(
    kind: ComparisonKind,
    calibrationPassed: Boolean,
    policy: PolicyOutcome,
    regressionPolicy: RegressionPolicy?,
  ): RunnerExit =
    when {
      kind == ComparisonKind.CALIBRATION && !calibrationPassed -> RunnerExit.CALIBRATION_FAILED
      regressionPolicy == null || policy == PolicyOutcome.PASS -> RunnerExit.SUCCESS
      policy == PolicyOutcome.FAIL -> RunnerExit.POLICY_FAILED
      else -> RunnerExit.POLICY_INCONCLUSIVE
    }

  companion object {
    internal fun directionForTesting(lower: Double, upper: Double): DirectionOutcome =
      direction(lower, upper)

    internal fun policyForTesting(
      lower: Double,
      upper: Double,
      maximumRegressionBudget: Double,
    ): PolicyOutcome {
      require(
        lower.isFinite() &&
          upper.isFinite() &&
          lower > 0.0 &&
          upper >= lower &&
          maximumRegressionBudget.isFinite() &&
          maximumRegressionBudget >= 0.0
      )
      val threshold = UNITY + maximumRegressionBudget
      return when {
        upper <= threshold -> PolicyOutcome.PASS
        lower > threshold -> PolicyOutcome.FAIL
        else -> PolicyOutcome.INCONCLUSIVE
      }
    }

    internal fun aggregatePolicyForTesting(outcomes: List<PolicyOutcome>): PolicyOutcome {
      require(outcomes.isNotEmpty() && outcomes.none { it == PolicyOutcome.NOT_ENFORCED })
      return when {
        outcomes.any { it == PolicyOutcome.FAIL } -> PolicyOutcome.FAIL
        outcomes.any { it == PolicyOutcome.INCONCLUSIVE } -> PolicyOutcome.INCONCLUSIVE
        else -> PolicyOutcome.PASS
      }
    }

    private fun direction(lower: Double, upper: Double): DirectionOutcome {
      require(lower.isFinite() && upper.isFinite() && lower > 0.0 && upper >= lower)
      return when {
        upper < UNITY -> DirectionOutcome.IMPROVEMENT
        lower > UNITY -> DirectionOutcome.REGRESSION
        else -> DirectionOutcome.INCONCLUSIVE
      }
    }

    private const val COMPARISON_SCHEMA_VERSION = "comparison-v1"
    private const val UNITY = 1.0
  }
}

private fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.HexFormat
import java.util.jar.JarFile
import performance.hash.Sha256
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

  data class InputFailure(
    val exit: RunnerExit = RunnerExit.INPUT_OR_PREFLIGHT_INVALID,
  ) : ComparisonComputation

  data class InternalFailure(
    val exit: RunnerExit = RunnerExit.INTERNAL_OR_PUBLICATION_FAILED,
  ) : ComparisonComputation
}

/** Sole production seam for verification and comparison of sealed capture paths. */
class CaptureComparator private constructor(
  private val renderer: ComparisonRenderer = ComparisonRenderer(),
  private val executingRunnerObservation: ExecutingRunnerObservation?,
) {
  constructor(renderer: ComparisonRenderer = ComparisonRenderer()) :
    this(renderer, ExecutingRunnerObservation.current())

  fun compare(request: ComparisonRequest): ComparisonComputation =
    runCatching {
      when (
        val verification =
          ComparisonInputVerifier.verify(request) { distribution ->
            executingRunnerObservation?.matches(distribution) == true
          }
      ) {
        ComparisonInputVerifier.Result.InputFailure -> ComparisonComputation.InputFailure()
        is ComparisonInputVerifier.Result.Incompatible -> {
        val document =
          IncompatibleComparisonDocument(
            schemaVersion = COMPARISON_SCHEMA_VERSION,
            kind = request.kind,
            strength = ComparisonStrength.DIAGNOSTIC,
            compatibility = ComparisonCompatibility.INCOMPATIBLE,
              compatibilityReasons = verification.reasons,
              implementation = verification.execution,
          )
        val rendered = renderer.render(document)
          ComparisonComputation.Incompatible(
          document = document,
          jsonBytes = rendered.json,
          markdownBytes = rendered.markdown,
            reasons = verification.reasons,
        )
      }
        is ComparisonInputVerifier.Result.Compatible -> compareVerified(request, verification)
      }
    }
      .getOrElse { ComparisonComputation.InternalFailure() }

  private fun compareVerified(
    request: ComparisonRequest,
    verification: ComparisonInputVerifier.Result.Compatible,
  ): ComparisonComputation.Completed {
      val baseline = verification.baseline
      val candidate = verification.candidate
      val cells =
        baseline.samples.keys.sortedBy { identity -> identity.canonicalBytes().toHex() }.map {
          identity ->
          val estimate =
            BootstrapV1.estimate(
              baseline.identity.captureId,
              candidate.identity.captureId,
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
          implementation = verification.execution,
          cells = cells,
          calibration = calibrationRef(request, verification, calibrationPassed),
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
      return ComparisonComputation.Completed(
        document = document,
        jsonBytes = rendered.json,
        markdownBytes = rendered.markdown,
        exit = exit(request.kind, calibrationPassed, overallPolicy, request.regressionPolicy),
      )
  }

  private fun calibrationRef(
    request: ComparisonRequest,
    verification: ComparisonInputVerifier.Result.Compatible,
    calibrationPassed: Boolean,
  ): ComparisonCalibrationRef =
    if (request.kind == ComparisonKind.CALIBRATION) {
      val baseline = verification.baseline
      val candidate = verification.candidate
      ComparisonCalibrationRef(
        evidenceSha256 = null,
        a1CaptureId = baseline.identity.captureId,
        a2CaptureId = candidate.identity.captureId,
        bCaptureId = null,
        passed = calibrationPassed,
      )
    } else {
      val calibration = checkNotNull(verification.calibration)
      ComparisonCalibrationRef(
        evidenceSha256 = calibration.bundleSha256,
        a1CaptureId = calibration.a1CaptureId,
        a2CaptureId = calibration.a2CaptureId,
        bCaptureId = verification.candidate.identity.captureId,
        passed = true,
      )
    }

  private fun captureRef(capture: CaptureBundleVerifier.Projection): ComparisonCaptureRef =
    ComparisonCaptureRef(
      captureId = capture.identity.captureId,
      captureSha256 = capture.captureSha256,
      bundleSha256 = capture.bundleSha256,
      treatmentGitSha = capture.provenance.treatment.gitSha,
      productionSha256 = capture.artifacts.production.sha256,
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
    @JvmSynthetic
    internal fun forTest(
      comparatorCodeSource: Path,
      effectiveClasspath: List<Path>,
      fileCodeSource: Boolean = true,
      systemApplicationClassLoader: Boolean = true,
      renderer: ComparisonRenderer = ComparisonRenderer(),
    ): CaptureComparator =
      CaptureComparator(
        renderer,
        ExecutingRunnerObservation(
          comparatorCodeSource = comparatorCodeSource,
          effectiveClasspath = effectiveClasspath.toList(),
          fileCodeSource = fileCodeSource,
          systemApplicationClassLoader = systemApplicationClassLoader,
        ),
      )

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

private data class ExecutingRunnerObservation(
  val comparatorCodeSource: Path,
  val effectiveClasspath: List<Path>,
  val fileCodeSource: Boolean,
  val systemApplicationClassLoader: Boolean,
) {
  fun matches(distribution: DistributionProjection): Boolean =
    runCatching {
        val expected = distribution.verifiedRunnerClasspath
        val actual = effectiveClasspath
        fileCodeSource &&
          systemApplicationClassLoader &&
          isStrictJarPath(comparatorCodeSource) &&
          actual.isNotEmpty() &&
          expected.all(::isStrictJarPath) &&
          actual.distinct().size == actual.size &&
          actual.all(::isStrictJarPath) &&
          comparatorCodeSource == expected.firstOrNull() &&
          actual == expected &&
          actual.map(Sha256::digest) == distribution.runnerClasspath.map(ArtifactProjection::sha256)
      }
      .getOrDefault(false)

  companion object {
    fun current(): ExecutingRunnerObservation? =
      runCatching {
          val codeSourceUrl =
            checkNotNull(CaptureComparator::class.java.protectionDomain.codeSource).location
          ExecutingRunnerObservation(
            comparatorCodeSource = Path.of(codeSourceUrl.toURI()),
            effectiveClasspath =
              checkNotNull(System.getProperty("java.class.path"))
                .split(java.io.File.pathSeparatorChar)
                .map(Path::of),
            fileCodeSource = codeSourceUrl.protocol == "file",
            systemApplicationClassLoader =
              CaptureComparator::class.java.classLoader === ClassLoader.getSystemClassLoader(),
          )
        }
        .getOrNull()
  }
}

private fun isStrictJarPath(path: Path): Boolean =
  path.isAbsolute &&
    path == path.normalize() &&
    '*' !in path.toString() &&
    !hasSymbolicLinkComponent(path) &&
    Files.isRegularFile(path, NOFOLLOW_LINKS) &&
    !Files.isSymbolicLink(path) &&
    path.fileName.toString().endsWith(".jar") &&
    runCatching { JarFile(path.toFile()).use { true } }.getOrDefault(false)

private fun hasSymbolicLinkComponent(path: Path): Boolean {
  var current = path.root ?: return true
  path.forEach { component ->
    current = current.resolve(component)
    if (Files.isSymbolicLink(current)) return true
  }
  return false
}

private fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)

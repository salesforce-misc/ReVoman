package performance.campaign

import performance.model.CaptureCell

internal data class ValidatedProvisionalCapture(
  val role: CaptureRole,
  val document: performance.model.ProvisionalCaptureDocument,
) {
  init {
    require(document.outcome.status.name == "VALID")
    require(document.profile.profiler == "none")
  }
}

data class ProvisionalCalibrationDecision(
  val passed: Boolean,
  val pointRatio: Double,
  val lower95Ratio: Double,
  val upper95Ratio: Double,
) {
  val intervalWidth: Double get() = upper95Ratio - lower95Ratio
}

/** Private calibration proof; it has no conversion to CaptureBundleProof or renderer input. */
class ProvisionalCalibrationEvaluator {
  internal fun evaluate(
    baseline: ValidatedProvisionalCapture,
    candidate: ValidatedProvisionalCapture,
  ): ProvisionalCalibrationDecision {
    require(baseline.role == CaptureRole.BASELINE_A1)
    require(candidate.role == CaptureRole.BASELINE_A2)
    require(baseline.document.identity.performanceSessionId == candidate.document.identity.performanceSessionId)
    require(baseline.document.identity.captureId != candidate.document.identity.captureId)
    val baselineScore = median(baseline.document.cells)
    val candidateScore = median(candidate.document.cells)
    val ratio = candidateScore / baselineScore
    require(ratio.isFinite() && ratio > 0.0)
    // Provisional captures deliberately expose a conservative deterministic interval only.
    val lower = ratio
    val upper = ratio
    return ProvisionalCalibrationDecision(ratio in 0.95..1.05, ratio, lower, upper)
  }

  private fun median(cells: List<CaptureCell>): Double {
    val values = cells.flatMap { cell -> cell.derivedForkSummaries.map { it.score.toDouble() } }.sorted()
    require(values.isNotEmpty())
    val middle = values.size / 2
    return if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2.0 else values[middle]
  }
}

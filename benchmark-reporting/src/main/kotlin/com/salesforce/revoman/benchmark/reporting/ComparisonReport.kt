package com.salesforce.revoman.benchmark.reporting

import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

internal data class ComparisonRow(
  val benchmark: String,
  val parameters: String,
  val mode: String,
  val threads: Int,
  val unit: String,
  val baselineScore: Double,
  val baselineError: Double,
  val baselineLower: Double,
  val candidateScore: Double,
  val candidateError: Double,
  val candidateUpper: Double,
  val deltaPercent: Double,
  val passed: Boolean,
)

internal fun compareFrames(
  baseline: DataFrame<NormalizedJmhRowSchema>,
  candidate: DataFrame<NormalizedJmhRowSchema>,
): List<ComparisonRow> {
  val baselineRows = baseline.toJmhRows().associateBy { it.key }
  val candidateRows = candidate.toJmhRows().associateBy { it.key }
  require(baselineRows.keys == candidateRows.keys) {
    "Baseline and candidate benchmark keys differ"
  }
  return baselineRows.keys.sorted().map { key ->
    val before = baselineRows.getValue(key)
    val after = candidateRows.getValue(key)
    val baselineLower = before.score - before.scoreError
    val candidateUpper = after.score + after.scoreError
    ComparisonRow(
      benchmark = key.benchmark,
      parameters = key.parameters,
      mode = key.mode,
      threads = key.threads,
      unit = key.unit,
      baselineScore = before.score,
      baselineError = before.scoreError,
      baselineLower = baselineLower,
      candidateScore = after.score,
      candidateError = after.scoreError,
      candidateUpper = candidateUpper,
      deltaPercent = ((after.score - before.score) / before.score) * 100.0,
      passed = candidateUpper < baselineLower,
    )
  }
}

internal fun comparisonFrame(rows: List<ComparisonRow>): DataFrame<ComparisonRowSchema> =
  dataFrameOf(
      "benchmark" to rows.map { it.benchmark },
      "parameters" to rows.map { it.parameters },
      "mode" to rows.map { it.mode },
      "threads" to rows.map { it.threads },
      "unit" to rows.map { it.unit },
      "baselineScore" to rows.map { it.baselineScore },
      "baselineError99_9" to rows.map { it.baselineError },
      "baselineLower" to rows.map { it.baselineLower },
      "candidateScore" to rows.map { it.candidateScore },
      "candidateError99_9" to rows.map { it.candidateError },
      "candidateUpper" to rows.map { it.candidateUpper },
      "deltaPercent" to rows.map { it.deltaPercent },
      "passed" to rows.map { it.passed },
    )
    .cast()

package com.salesforce.revoman.benchmark.reporting

import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

@Suppress("PropertyName", "VariableNaming")
@DataSchema
interface NormalizedJmhRowSchema {
  val benchmark: String
  val parameters: String
  val mode: String
  val threads: Int
  val samples: Int
  val score: Double
  val scoreError99_9: Double
  val unit: String
}

@Suppress("PropertyName", "VariableNaming")
@DataSchema
interface ComparisonRowSchema {
  val benchmark: String
  val parameters: String
  val mode: String
  val threads: Int
  val unit: String
  val baselineScore: Double
  val baselineError99_9: Double
  val baselineLower: Double
  val candidateScore: Double
  val candidateError99_9: Double
  val candidateUpper: Double
  val deltaPercent: Double
  val passed: Boolean
}

@Suppress("PropertyName", "VariableNaming")
@DataSchema
interface ScorecardRowSchema {
  val journey: String
  val workload: String
  val score: Double
  val scoreError99_9: Double
  val unit: String
}

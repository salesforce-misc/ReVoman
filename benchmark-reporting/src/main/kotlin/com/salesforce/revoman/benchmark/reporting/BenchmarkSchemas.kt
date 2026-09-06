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

@Suppress("PropertyName", "VariableNaming")
@DataSchema
interface ProfilerAttributionRowSchema {
  val frequency: String
  val operation: String
  val startTime: String
  val durationNs: Long
  val selfDurationNs: Long
  val javaThreadId: Long
  val completion: String
  val result: String
  val failurePhase: String
  val failureClass: String
  val stopReason: String
  val sourceCount: Int
  val providedStepCount: Int
  val executedStepCount: Int
  val initialEnvironmentEntries: Int
  val finalEnvironmentEntries: Int
  val position: Int
  val iteration: Int
  val environmentEntriesAtStart: Int
  val environmentEntriesAtEnd: Int
  val preRequestScriptLineCount: Int
  val postResponseScriptLineCount: Int
  val preStepHookCount: Int
  val postStepHookCount: Int
  val httpStatus: Int
  val requestBytes: Long
  val responseBytes: Long
  val profileEvent: String
  val sampleCount: Long
  val sampleWeight: Long
  val sampleWeightUnit: String
}

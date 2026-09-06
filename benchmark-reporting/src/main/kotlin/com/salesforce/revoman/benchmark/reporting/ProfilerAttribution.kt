package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

internal data class SemanticMetadata(
  val completion: String = "",
  val result: String = "",
  val failurePhase: String = "",
  val failureClass: String = "",
  val stopReason: String = "",
  val sourceCount: Int = -1,
  val providedStepCount: Int = -1,
  val executedStepCount: Int = -1,
  val initialEnvironmentEntries: Int = -1,
  val finalEnvironmentEntries: Int = -1,
  val position: Int = -1,
  val iteration: Int = -1,
  val environmentEntriesAtStart: Int = -1,
  val environmentEntriesAtEnd: Int = -1,
  val preRequestScriptLineCount: Int = -1,
  val postResponseScriptLineCount: Int = -1,
  val preStepHookCount: Int = -1,
  val postStepHookCount: Int = -1,
  val httpStatus: Int = -1,
  val requestBytes: Long = -1,
  val responseBytes: Long = -1,
)

internal data class SemanticInterval(
  val frequency: String,
  val operation: String,
  val startTime: Instant,
  val endTime: Instant,
  val javaThreadId: Long,
  val metadata: SemanticMetadata = SemanticMetadata(),
) {
  init {
    require(!endTime.isBefore(startTime)) { "Semantic interval ends before it starts" }
  }

  val durationNs: Long
    get() = Duration.between(startTime, endTime).toNanos()
}

internal data class ProfilerSample(
  val startTime: Instant,
  val javaThreadId: Long,
  val count: Long,
  val weight: Long,
) {
  init {
    require(count > 0) { "Profiler sample count must be positive" }
    require(weight >= 0) { "Profiler sample weight must not be negative" }
  }
}

internal data class ProfilerAttributionRow(
  val frequency: String,
  val operation: String,
  val startTime: String,
  val durationNs: Long,
  val selfDurationNs: Long,
  val javaThreadId: Long,
  val completion: String,
  val result: String,
  val failurePhase: String,
  val failureClass: String,
  val stopReason: String,
  val sourceCount: Int,
  val providedStepCount: Int,
  val executedStepCount: Int,
  val initialEnvironmentEntries: Int,
  val finalEnvironmentEntries: Int,
  val position: Int,
  val iteration: Int,
  val environmentEntriesAtStart: Int,
  val environmentEntriesAtEnd: Int,
  val preRequestScriptLineCount: Int,
  val postResponseScriptLineCount: Int,
  val preStepHookCount: Int,
  val postStepHookCount: Int,
  val httpStatus: Int,
  val requestBytes: Long,
  val responseBytes: Long,
  val profileEvent: String,
  val sampleCount: Long,
  val sampleWeight: Long,
  val sampleWeightUnit: String,
)

internal fun createProfilerAttribution(
  profileEvent: String,
  sampleRecording: Path,
  semanticRecording: Path,
): DataFrame<ProfilerAttributionRowSchema> =
  profilerAttributionFrame(
    attributeProfilerSamples(
      profileEvent,
      readSemanticIntervals(semanticRecording),
      readProfilerSamples(profileEvent, sampleRecording),
    )
  )

internal fun attributeProfilerSamples(
  profileEvent: String,
  intervals: List<SemanticInterval>,
  samples: List<ProfilerSample>,
): List<ProfilerAttributionRow> {
  require(intervals.isNotEmpty()) { "Semantic recording contains no ReVoman performance events" }
  require(samples.isNotEmpty()) { "$profileEvent recording contains no profiler samples" }

  val indexedIntervals =
    intervals
      .sortedWith(
        compareBy(SemanticInterval::startTime)
          .thenByDescending(SemanticInterval::endTime)
          .thenBy(SemanticInterval::operation)
      )
      .mapIndexed(::IndexedInterval)
  val sampleTotals = Array(indexedIntervals.size) { SampleTotals() }
  val unmatched = SampleTotals()
  val byThread = indexedIntervals.groupBy { it.interval.javaThreadId }

  samples.groupBy(ProfilerSample::javaThreadId).forEach { (threadId, threadSamples) ->
    val candidates = byThread[threadId].orEmpty()
    var nextCandidate = 0
    val active = mutableListOf<IndexedInterval>()
    threadSamples.sortedBy(ProfilerSample::startTime).forEach { sample ->
      while (
        nextCandidate < candidates.size &&
          !candidates[nextCandidate].interval.startTime.isAfter(sample.startTime)
      ) {
        active += candidates[nextCandidate++]
      }
      active.removeAll { indexed -> indexed.interval.endTime.isBefore(sample.startTime) }
      val owner =
        active
          .asSequence()
          .filter { indexed -> !indexed.interval.endTime.isBefore(sample.startTime) }
          .minWithOrNull(
            compareBy<IndexedInterval> { it.interval.durationNs }
              .thenByDescending { it.interval.startTime }
          )
      (owner?.let { sampleTotals[it.index] } ?: unmatched).add(sample)
    }
  }

  val children = directChildren(indexedIntervals)
  val unit = sampleWeightUnit(profileEvent)
  val rows = indexedIntervals.map { indexed ->
    val interval = indexed.interval
    interval.toRow(
      profileEvent = profileEvent,
      unit = unit,
      selfDurationNs = selfDuration(interval, children[indexed.index].orEmpty()),
      totals = sampleTotals[indexed.index],
    )
  }
  return if (unmatched.count == 0L) rows else rows + unmatchedRow(profileEvent, unit, unmatched)
}

internal fun profilerAttributionFrame(
  rows: List<ProfilerAttributionRow>
): DataFrame<ProfilerAttributionRowSchema> =
  dataFrameOf(
      "frequency" to rows.map { it.frequency },
      "operation" to rows.map { it.operation },
      "startTime" to rows.map { it.startTime },
      "durationNs" to rows.map { it.durationNs },
      "selfDurationNs" to rows.map { it.selfDurationNs },
      "javaThreadId" to rows.map { it.javaThreadId },
      "completion" to rows.map { it.completion },
      "result" to rows.map { it.result },
      "failurePhase" to rows.map { it.failurePhase },
      "failureClass" to rows.map { it.failureClass },
      "stopReason" to rows.map { it.stopReason },
      "sourceCount" to rows.map { it.sourceCount },
      "providedStepCount" to rows.map { it.providedStepCount },
      "executedStepCount" to rows.map { it.executedStepCount },
      "initialEnvironmentEntries" to rows.map { it.initialEnvironmentEntries },
      "finalEnvironmentEntries" to rows.map { it.finalEnvironmentEntries },
      "position" to rows.map { it.position },
      "iteration" to rows.map { it.iteration },
      "environmentEntriesAtStart" to rows.map { it.environmentEntriesAtStart },
      "environmentEntriesAtEnd" to rows.map { it.environmentEntriesAtEnd },
      "preRequestScriptLineCount" to rows.map { it.preRequestScriptLineCount },
      "postResponseScriptLineCount" to rows.map { it.postResponseScriptLineCount },
      "preStepHookCount" to rows.map { it.preStepHookCount },
      "postStepHookCount" to rows.map { it.postStepHookCount },
      "httpStatus" to rows.map { it.httpStatus },
      "requestBytes" to rows.map { it.requestBytes },
      "responseBytes" to rows.map { it.responseBytes },
      "profileEvent" to rows.map { it.profileEvent },
      "sampleCount" to rows.map { it.sampleCount },
      "sampleWeight" to rows.map { it.sampleWeight },
      "sampleWeightUnit" to rows.map { it.sampleWeightUnit },
    )
    .cast()

private data class IndexedInterval(val index: Int, val interval: SemanticInterval)

private class SampleTotals(var count: Long = 0, var weight: Long = 0) {
  fun add(sample: ProfilerSample) {
    count = Math.addExact(count, sample.count)
    weight = Math.addExact(weight, sample.weight)
  }
}

private fun directChildren(intervals: List<IndexedInterval>): Map<Int, List<SemanticInterval>> {
  val children = mutableMapOf<Int, MutableList<SemanticInterval>>()
  intervals
    .groupBy { it.interval.javaThreadId }
    .values
    .forEach { threadIntervals ->
      val stack = mutableListOf<IndexedInterval>()
      threadIntervals.forEach { current ->
        stack.removeAll { candidate ->
          !candidate.interval.endTime.isAfter(current.interval.startTime)
        }
        val parent = stack.asReversed().firstOrNull { candidate -> candidate.contains(current) }
        parent?.let { children.getOrPut(it.index, ::mutableListOf) += current.interval }
        stack += current
      }
    }
  return children
}

private fun IndexedInterval.contains(child: IndexedInterval): Boolean =
  index != child.index &&
    !interval.startTime.isAfter(child.interval.startTime) &&
    !interval.endTime.isBefore(child.interval.endTime)

private fun selfDuration(parent: SemanticInterval, children: List<SemanticInterval>): Long {
  if (children.isEmpty()) return parent.durationNs
  val sortedChildren = children.sortedBy(SemanticInterval::startTime)
  var covered = 0L
  var coveredStart = sortedChildren.first().startTime
  var coveredEnd = sortedChildren.first().endTime
  sortedChildren.drop(1).forEach { child ->
    if (child.startTime.isAfter(coveredEnd)) {
      covered = Math.addExact(covered, Duration.between(coveredStart, coveredEnd).toNanos())
      coveredStart = child.startTime
      coveredEnd = child.endTime
    } else if (child.endTime.isAfter(coveredEnd)) {
      coveredEnd = child.endTime
    }
  }
  covered = Math.addExact(covered, Duration.between(coveredStart, coveredEnd).toNanos())
  return parent.durationNs - covered.coerceAtMost(parent.durationNs)
}

private fun SemanticInterval.toRow(
  profileEvent: String,
  unit: String,
  selfDurationNs: Long,
  totals: SampleTotals,
): ProfilerAttributionRow =
  ProfilerAttributionRow(
    frequency,
    operation,
    startTime.toString(),
    durationNs,
    selfDurationNs,
    javaThreadId,
    metadata.completion,
    metadata.result,
    metadata.failurePhase,
    metadata.failureClass,
    metadata.stopReason,
    metadata.sourceCount,
    metadata.providedStepCount,
    metadata.executedStepCount,
    metadata.initialEnvironmentEntries,
    metadata.finalEnvironmentEntries,
    metadata.position,
    metadata.iteration,
    metadata.environmentEntriesAtStart,
    metadata.environmentEntriesAtEnd,
    metadata.preRequestScriptLineCount,
    metadata.postResponseScriptLineCount,
    metadata.preStepHookCount,
    metadata.postStepHookCount,
    metadata.httpStatus,
    metadata.requestBytes,
    metadata.responseBytes,
    profileEvent,
    totals.count,
    totals.weight,
    unit,
  )

private fun unmatchedRow(
  profileEvent: String,
  unit: String,
  totals: SampleTotals,
): ProfilerAttributionRow =
  SemanticInterval(
      frequency = "PROFILE",
      operation = "UNATTRIBUTED",
      startTime = Instant.EPOCH,
      endTime = Instant.EPOCH,
      javaThreadId = -1,
    )
    .toRow(profileEvent, unit, 0, totals)

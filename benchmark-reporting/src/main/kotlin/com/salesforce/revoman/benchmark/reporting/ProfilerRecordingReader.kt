package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile

internal fun readSemanticIntervals(recording: Path): List<SemanticInterval> =
  RecordingFile(recording).use { events ->
    generateSequence { events.takeIf(RecordingFile::hasMoreEvents)?.readEvent() }
      .mapNotNull(::semanticInterval)
      .toList()
  }

internal fun readProfilerSamples(profileEvent: String, recording: Path): List<ProfilerSample> =
  RecordingFile(recording).use { events ->
    generateSequence { events.takeIf(RecordingFile::hasMoreEvents)?.readEvent() }
      .flatMap { event -> profilerSamples(profileEvent, event) }
      .toList()
  }

internal fun sampleWeightUnit(profileEvent: String): String =
  when (profileEvent) {
    "cpu" -> "samples"
    "alloc" -> "bytes"
    "lock",
    "wall" -> "nanoseconds"
    else -> error("Unsupported profiler event: $profileEvent")
  }

private fun semanticInterval(event: RecordedEvent): SemanticInterval? =
  when (event.eventType.name) {
    OPERATION_EVENT ->
      SemanticInterval(
        frequency = event.getString("frequency"),
        operation = event.getString("kind"),
        startTime = event.startTime,
        endTime = event.endTime,
        javaThreadId = event.getThread("eventThread").javaThreadId,
        metadata = event.commonMetadata(),
      )
    KICK_EVENT ->
      SemanticInterval(
        frequency = "COLLECTION",
        operation = "KICK",
        startTime = event.startTime,
        endTime = event.endTime,
        javaThreadId = event.getThread("eventThread").javaThreadId,
        metadata =
          event
            .commonMetadata()
            .copy(
              sourceCount = event.getInt("sourceCount"),
              providedStepCount = event.getInt("providedStepCount"),
              executedStepCount = event.getInt("executedStepCount"),
              initialEnvironmentEntries = event.getInt("initialEnvironmentEntries"),
              finalEnvironmentEntries = event.getInt("finalEnvironmentEntries"),
              stopReason = event.getString("stopReason"),
            ),
      )
    STEP_EVENT ->
      SemanticInterval(
        frequency = "STEP",
        operation = "STEP",
        startTime = event.startTime,
        endTime = event.endTime,
        javaThreadId = event.getThread("eventThread").javaThreadId,
        metadata =
          event
            .commonMetadata()
            .copy(
              result = event.getString("result"),
              failurePhase = event.getString("failurePhase"),
              position = event.getInt("position"),
              iteration = event.getInt("iteration"),
              environmentEntriesAtStart = event.getInt("environmentEntriesAtStart"),
              environmentEntriesAtEnd = event.getInt("environmentEntriesAtEnd"),
              preRequestScriptLineCount = event.getInt("preRequestScriptLineCount"),
              postResponseScriptLineCount = event.getInt("postResponseScriptLineCount"),
              preStepHookCount = event.getInt("preStepHookCount"),
              postStepHookCount = event.getInt("postStepHookCount"),
              httpStatus = event.getInt("httpStatus"),
              requestBytes = event.getLong("requestBytes"),
              responseBytes = event.getLong("responseBytes"),
            ),
      )
    else -> null
  }

private fun RecordedEvent.commonMetadata(): SemanticMetadata =
  SemanticMetadata(
    completion = getString("completion"),
    failureClass = getClass("failureClass")?.name.orEmpty(),
  )

private fun profilerSamples(
  profileEvent: String,
  event: RecordedEvent,
): Sequence<ProfilerSample> =
  when (profileEvent) {
    "cpu" ->
      listOfNotNull(
          event
            .takeIf { it.eventType.name == "jdk.ExecutionSample" }
            ?.toSample(
              threadField = "sampledThread",
              count = 1,
              weight = 1,
            )
        )
        .asSequence()
    "alloc" ->
      listOfNotNull(
          when (event.eventType.name) {
            "jdk.ObjectAllocationInNewTLAB" ->
              event.toSample("eventThread", count = 1, weight = event.getLong("tlabSize"))
            "jdk.ObjectAllocationOutsideTLAB" ->
              event.toSample("eventThread", count = 1, weight = event.getLong("allocationSize"))
            else -> null
          }
        )
        .asSequence()
    "lock" ->
      listOfNotNull(
          event
            .takeIf { it.eventType.name in LOCK_EVENTS }
            ?.toSample("eventThread", count = 1, weight = event.duration.toNanos())
        )
        .asSequence()
    "wall" ->
      event
        .takeIf { it.eventType.name == "profiler.WallClockSample" }
        ?.wallClockSamples()
        .orEmpty()
        .asSequence()
    else -> error("Unsupported profiler event: $profileEvent")
  }

private fun RecordedEvent.wallClockSamples(): List<ProfilerSample> {
  val count = getInt("samples")
  require(count > 0) { "Wall-clock sample count must be positive" }
  val timeSpanNs = getDuration("timeSpan").toNanos()
  return List(count) { index ->
    ProfilerSample(
      startTime = startTime.plusNanos(sampleOffset(timeSpanNs, index, count)),
      javaThreadId = getThread("sampledThread").javaThreadId,
      count = 1,
      weight = WALL_SAMPLE_INTERVAL_NS,
    )
  }
}

private fun sampleOffset(timeSpanNs: Long, index: Int, count: Int): Long {
  if (count == 1) return 0
  val intervals = count - 1L
  val evenPart = Math.multiplyExact(timeSpanNs / intervals, index.toLong())
  val remainderPart = (timeSpanNs % intervals) * index / intervals
  return Math.addExact(evenPart, remainderPart)
}

private fun RecordedEvent.toSample(
  threadField: String,
  count: Long,
  weight: Long,
): ProfilerSample = ProfilerSample(startTime, getThread(threadField).javaThreadId, count, weight)

private const val OPERATION_EVENT = "com.salesforce.revoman.performance.Operation"
private const val KICK_EVENT = "com.salesforce.revoman.performance.Kick"
private const val STEP_EVENT = "com.salesforce.revoman.performance.Step"
private val LOCK_EVENTS = setOf("jdk.JavaMonitorEnter", "jdk.ThreadPark")

package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import jdk.jfr.Event
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.Timespan

class ProfilerAttributionTest :
  StringSpec({
    "samples belong to the deepest same-thread interval and unmatched work stays visible" {
      val intervals =
        listOf(
          interval("RUN", "MULTI_KICK", 0, 100),
          interval("STEP", "STEP", 10, 80),
          interval("STEP", "HTTP_EXCHANGE", 20, 40),
        )
      val samples =
        listOf(
          sample(5, weight = 2),
          sample(15, weight = 3),
          sample(25, weight = 5),
          sample(25, threadId = 99, weight = 7),
        )

      val rows = attributeProfilerSamples("cpu", intervals, samples)

      rows.map(ProfilerAttributionRow::operation) shouldContainExactly
        listOf("MULTI_KICK", "STEP", "HTTP_EXCHANGE", "UNATTRIBUTED")
      rows.map(ProfilerAttributionRow::selfDurationNs) shouldContainExactly
        listOf(30L, 50L, 20L, 0L)
      rows.map(ProfilerAttributionRow::sampleCount) shouldContainExactly listOf(1L, 1L, 1L, 1L)
      rows.map(ProfilerAttributionRow::sampleWeight) shouldContainExactly listOf(2L, 3L, 5L, 7L)
    }

    "attribution is rendered through the DataFrame reporting path" {
      val rows =
        attributeProfilerSamples(
          "wall",
          listOf(interval("COLLECTION", "SEQUENCE_EXECUTE", 0, 100)),
          listOf(sample(50, count = 4, weight = 12_000)),
        )

      val frame = profilerAttributionFrame(rows)

      frame.columnNames() shouldContainExactly
        listOf(
          "frequency",
          "operation",
          "startTime",
          "durationNs",
          "selfDurationNs",
          "javaThreadId",
          "completion",
          "result",
          "failurePhase",
          "failureClass",
          "stopReason",
          "sourceCount",
          "providedStepCount",
          "executedStepCount",
          "initialEnvironmentEntries",
          "finalEnvironmentEntries",
          "position",
          "iteration",
          "environmentEntriesAtStart",
          "environmentEntriesAtEnd",
          "preRequestScriptLineCount",
          "postResponseScriptLineCount",
          "preStepHookCount",
          "postStepHookCount",
          "httpStatus",
          "requestBytes",
          "responseBytes",
          "profileEvent",
          "sampleCount",
          "sampleWeight",
          "sampleWeightUnit",
        )
      renderProfilerAttributionCsv(frame) shouldStartWith
        "frequency,operation,startTime,durationNs,selfDurationNs,javaThreadId,"
      rows.single().sampleCount shouldBe 4
      rows.single().sampleWeightUnit shouldBe "nanoseconds"
    }

    "reader recognizes the exact async-profiler event schema for every supported mode" {
      val recording = Files.createTempFile("profiler-samples-", ".jfr")
      try {
        Files.write(recording, testProfilerRecordingBytes)

        listOf("cpu", "alloc", "lock").forEach { profileEvent ->
          readProfilerSamples(profileEvent, recording).isNotEmpty() shouldBe true
        }
        val wallSamples = readProfilerSamples("wall", recording)
        wallSamples.size shouldBe 2
        wallSamples.map(ProfilerSample::weight) shouldContainExactly
          listOf(WALL_SAMPLE_INTERVAL_NS, WALL_SAMPLE_INTERVAL_NS)
        Duration.between(wallSamples.first().startTime, wallSamples.last().startTime)
          .toNanos() shouldBe WALL_SAMPLE_INTERVAL_NS
      } finally {
        Files.deleteIfExists(recording)
      }
    }

    "reader preserves fixed semantic fields without recording user data" {
      val recording = Files.createTempFile("semantic-events-", ".jfr")
      try {
        Files.write(recording, testSemanticRecordingBytes)

        val interval = readSemanticIntervals(recording).single()

        interval.frequency shouldBe "STEP"
        interval.operation shouldBe "HTTP_EXCHANGE"
        interval.metadata.completion shouldBe "RETURNED"
        interval.metadata.failureClass shouldBe ""
      } finally {
        Files.deleteIfExists(recording)
      }
    }

    "attribution rejects recordings without either half of the correlation" {
      shouldThrow<IllegalArgumentException> {
          attributeProfilerSamples("cpu", emptyList(), listOf(sample(1, weight = 1)))
        }
        .message shouldBe "Semantic recording contains no ReVoman performance events"

      shouldThrow<IllegalArgumentException> {
          attributeProfilerSamples("cpu", listOf(interval("RUN", "MULTI_KICK", 0, 2)), emptyList())
        }
        .message shouldBe "cpu recording contains no profiler samples"
    }
  })

private val ORIGIN: Instant = Instant.parse("2026-09-04T00:00:00Z")

private fun interval(
  frequency: String,
  operation: String,
  startNanos: Long,
  endNanos: Long,
  threadId: Long = 37,
): SemanticInterval =
  SemanticInterval(
    frequency = frequency,
    operation = operation,
    startTime = ORIGIN.plusNanos(startNanos),
    endTime = ORIGIN.plusNanos(endNanos),
    javaThreadId = threadId,
  )

private fun sample(
  atNanos: Long,
  threadId: Long = 37,
  count: Long = 1,
  weight: Long,
): ProfilerSample =
  ProfilerSample(
    startTime = ORIGIN.plusNanos(atNanos),
    javaThreadId = threadId,
    count = count,
    weight = weight,
  )

internal val testProfilerRecordingBytes: ByteArray by lazy {
  recordingBytes("profiler-attribution-samples-") { recording ->
    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1))
    recording.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO)
    recording.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO)
    recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO)
    recording.enable(TEST_WALL_EVENT)
    recording.start()

    createRecordedContention()
    val retained = List(20_000) { ByteArray(128) }
    val busyUntil = System.nanoTime() + Duration.ofMillis(250).toNanos()
    var checksum = 0L
    while (System.nanoTime() < busyUntil) {
      checksum = checksum xor retained[(checksum and 0x3fff).toInt()].size.toLong()
    }
    TestWallClockSample().also { event ->
      event.sampledThread = Thread.currentThread()
      event.samples = 2
      event.timeSpan = WALL_SAMPLE_INTERVAL_NS
      event.commit()
    }
    check(checksum >= 0 && retained.isNotEmpty())
  }
}

internal val testSemanticRecordingBytes: ByteArray by lazy {
  recordingBytes("profiler-attribution-semantics-") { recording ->
    recording.enable(TEST_OPERATION_EVENT)
    recording.start()
    TestOperationEvent().also { event ->
      event.frequency = "STEP"
      event.kind = "HTTP_EXCHANGE"
      event.completion = "RETURNED"
      event.begin()
      Thread.onSpinWait()
      event.commit()
    }
  }
}

private fun recordingBytes(prefix: String, configure: (Recording) -> Unit): ByteArray {
  val path = Files.createTempFile(prefix, ".jfr")
  try {
    Recording().use { recording ->
      configure(recording)
      recording.stop()
      recording.dump(path)
    }
    return Files.readAllBytes(path)
  } finally {
    Files.deleteIfExists(path)
  }
}

private fun createRecordedContention() {
  val monitor = Any()
  val started = CountDownLatch(1)
  val contender: Thread
  synchronized(monitor) {
    contender =
      Thread.ofPlatform().start {
        started.countDown()
        synchronized(monitor) { Thread.onSpinWait() }
      }
    started.await()
    Thread.sleep(20)
  }
  contender.join()
}

@Name(TEST_OPERATION_EVENT)
private class TestOperationEvent : Event() {
  @JvmField var frequency: String = ""
  @JvmField var kind: String = ""
  @JvmField var completion: String = ""
  @JvmField var failureClass: Class<*>? = null
}

@Name(TEST_WALL_EVENT)
private class TestWallClockSample : Event() {
  @JvmField var sampledThread: Thread? = null
  @JvmField var samples: Int = 0
  @JvmField @field:Timespan(Timespan.NANOSECONDS) var timeSpan: Long = 0
}

private const val TEST_OPERATION_EVENT = "com.salesforce.revoman.performance.Operation"
private const val TEST_WALL_EVENT = "profiler.WallClockSample"

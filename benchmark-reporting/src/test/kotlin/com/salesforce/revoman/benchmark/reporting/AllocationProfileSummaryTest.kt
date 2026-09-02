package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Duration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.io.path.writeBytes

class AllocationProfileSummaryTest :
  StringSpec({
    "allocation summary groups both event kinds and orders by allocated bytes" {
      renderAllocationByClassSummary(
        sequenceOf(
          AllocationSample(AllocationEventType.NEW_TLAB, "sample.Small", 8),
          AllocationSample(AllocationEventType.OUTSIDE_TLAB, "sample.Large", 64),
          AllocationSample(AllocationEventType.NEW_TLAB, "sample.Small", 16),
        )
      ) shouldContain "sample.Large\t1\t64\nsample.Small\t2\t24"
    }

    "allocation summary rejects recordings without allocation events" {
      shouldThrow<IllegalArgumentException> {
        renderAllocationByClassSummary(emptySequence())
      }
    }

    "allocation summary reads JDK TLAB allocation events from a real recording" {
      val recordingPath = Files.createTempFile("allocation-summary-", ".jfr")
      try {
        recordingPath.writeBytes(testAllocationRecordingBytes)

        val samples = readAllocationSamples(recordingPath)
        val summary = renderAllocationByClassSummary(samples.asSequence())
        val recordedAllocations =
          RecordingFile.readAllEvents(recordingPath).filter { event ->
            AllocationEventType.fromJfrName(event.eventType.name) != null
          }

        samples.map(AllocationSample::eventType).distinct() shouldContainExactlyInAnyOrder
          AllocationEventType.entries
        recordedAllocations.zip(samples).forEach { (event, sample) ->
          sample.allocatedBytes shouldBe
            when (sample.eventType) {
              AllocationEventType.NEW_TLAB -> event.getLong("tlabSize")
              AllocationEventType.OUTSIDE_TLAB -> event.getLong("allocationSize")
            }
        }
        summary shouldContain "Allocation by Class"
        summary shouldContain "byte[]"
        summary shouldContain "java.lang.Object[]"
        summary shouldNotContain "ObjectAllocationSample"
      } finally {
        Files.deleteIfExists(recordingPath)
      }
    }
  })

internal val testAllocationRecordingBytes: ByteArray by lazy {
  val recordingPath = Files.createTempFile("allocation-summary-fixture-", ".jfr")
  try {
    Recording().use { recording ->
      recording.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO)
      recording.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO)
      recording.start()
      val retained = ArrayList<ByteArray>()
      repeat(100_000) { retained += ByteArray(128) }
      val outsideTlab = arrayOfNulls<Any>(1_000_000)
      recording.stop()
      recording.dump(recordingPath)
      retained.size shouldBe 100_000
      outsideTlab.size shouldBe 1_000_000
    }
    Files.readAllBytes(recordingPath)
  } finally {
    Files.deleteIfExists(recordingPath)
  }
}

package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile

internal data class AllocationSample(
  val eventType: AllocationEventType,
  val className: String,
  val allocatedBytes: Long,
) {
  init {
    require(className.isNotBlank()) { "Allocation class name must not be blank" }
    require(allocatedBytes >= 0) { "Allocated bytes must not be negative" }
  }
}

internal enum class AllocationEventType(val jfrName: String) {
  NEW_TLAB("jdk.ObjectAllocationInNewTLAB"),
  OUTSIDE_TLAB("jdk.ObjectAllocationOutsideTLAB");

  companion object {
    fun fromJfrName(name: String): AllocationEventType? = entries.firstOrNull { it.jfrName == name }
  }
}

private data class AllocationTotal(
  val count: Long,
  val allocatedBytes: Long,
)

internal fun allocationByClassSummary(recording: Path): String =
  renderAllocationByClassSummary(readAllocationSamples(recording).asSequence())

internal fun readAllocationSamples(recording: Path): List<AllocationSample> =
  RecordingFile(recording).use { events ->
    generateSequence { events.takeIf(RecordingFile::hasMoreEvents)?.readEvent() }
      .mapNotNull(::allocationSample)
      .toList()
  }

internal fun renderAllocationByClassSummary(samples: Sequence<AllocationSample>): String {
  val totals =
    samples.groupingBy(AllocationSample::className).fold(AllocationTotal(0, 0)) { total, sample ->
      AllocationTotal(
        count = Math.addExact(total.count, 1),
        allocatedBytes = Math.addExact(total.allocatedBytes, sample.allocatedBytes),
      )
    }
  require(totals.isNotEmpty()) {
    "Allocation recording contains no NewTLAB or OutsideTLAB allocation events"
  }
  return buildString {
    appendLine("Allocation by Class")
    appendLine()
    appendLine("Class\tAllocations\tAllocated bytes")
    totals.entries
      .sortedWith(
        compareByDescending<Map.Entry<String, AllocationTotal>> { it.value.allocatedBytes }
          .thenBy(Map.Entry<String, AllocationTotal>::key)
      )
      .forEach { (className, total) ->
        appendLine("$className\t${total.count}\t${total.allocatedBytes}")
      }
  }
}

private fun allocationSample(event: RecordedEvent): AllocationSample? =
  AllocationEventType.fromJfrName(event.eventType.name)?.let { eventType ->
    AllocationSample(
      eventType = eventType,
      className = readableClassName(event.getClass("objectClass").name),
      allocatedBytes = allocationWeight(event, eventType),
    )
  }

private fun allocationWeight(event: RecordedEvent, eventType: AllocationEventType): Long =
  when (eventType) {
    AllocationEventType.NEW_TLAB -> event.getLong("tlabSize")
    AllocationEventType.OUTSIDE_TLAB -> event.getLong("allocationSize")
  }

private fun readableClassName(className: String): String {
  val dimensions = className.takeWhile { character -> character == '[' }.length
  if (dimensions == 0) return className
  val elementName =
    when (val descriptor = className.substring(dimensions)) {
      "B" -> "byte"
      "C" -> "char"
      "D" -> "double"
      "F" -> "float"
      "I" -> "int"
      "J" -> "long"
      "S" -> "short"
      "Z" -> "boolean"
      else -> descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    }
  return elementName + "[]".repeat(dimensions)
}

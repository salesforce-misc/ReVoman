package com.salesforce.revoman.benchmark

internal class PreparedCollection(
  val bytes: ByteArray,
  val expectedStepCount: Int,
) {
  fun validate() {
    val json = bytes.decodeToString()
    check(json.startsWith("{\"item\":["))
    check(json.countOccurrences("\"name\":\"step-") == expectedStepCount)
    check(json.endsWith("],\"auth\":null}"))
  }
}

private fun String.countOccurrences(value: String): Int =
  windowedSequence(value.length).count { it == value }

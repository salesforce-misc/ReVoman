package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

internal data class JmhRow(
  val benchmark: String,
  val parameters: String,
  val mode: String,
  val threads: Int,
  val samples: Int,
  val score: Double,
  val scoreError: Double,
  val unit: String,
) {
  val key: ComparisonKey = ComparisonKey(benchmark, parameters, mode, threads, unit)
}

internal data class ComparisonKey(
  val benchmark: String,
  val parameters: String,
  val mode: String,
  val threads: Int,
  val unit: String,
) : Comparable<ComparisonKey> {
  override fun compareTo(other: ComparisonKey): Int =
    compareValuesBy(
      this,
      other,
      { it.benchmark },
      { it.parameters },
      { it.mode },
      { it.threads },
      { it.unit },
    )
}

internal fun readJmh(path: Path, selector: Regex): DataFrame<NormalizedJmhRowSchema> {
  require(Files.isRegularFile(path) && Files.isReadable(path)) { "CSV is not readable: $path" }
  val records = parseCsv(Files.readString(path))
  require(records.isNotEmpty()) { "CSV is empty: $path" }
  val header = records.first()
  require(header.distinct().size == header.size) { "CSV contains duplicate columns: $path" }
  val required =
    listOf("Benchmark", "Mode", "Threads", "Samples", "Score", "Score Error (99.9%)", "Unit")
  require(header.containsAll(required)) { "CSV is missing required columns: $path" }
  val indices = header.withIndex().associate { it.value to it.index }
  val parameterColumns =
    header.filter { it.startsWith("Param: ") }.sorted().map { it to indices.getValue(it) }
  val rows =
    records.drop(1).mapIndexedNotNull { rowIndex, values ->
      require(values.size == header.size) { "Malformed CSV row ${rowIndex + 2}: $path" }
      val benchmark = values[indices.getValue("Benchmark")]
      if (!selector.matches(benchmark)) {
        null
      } else {
        val threads = values[indices.getValue("Threads")].strictInt("Threads", rowIndex)
        val samples = values[indices.getValue("Samples")].strictInt("Samples", rowIndex)
        require(threads > 0 && samples > 0) { "Threads and Samples must be positive" }
        val score = values[indices.getValue("Score")].strictDouble("Score", rowIndex)
        val error =
          values[indices.getValue("Score Error (99.9%)")].strictDouble(
            "Score Error (99.9%)",
            rowIndex,
          )
        require(error >= 0.0) { "Score Error (99.9%) must not be negative" }
        val unit = values[indices.getValue("Unit")]
        require(unit.isNotBlank()) { "Unit must not be blank" }
        JmhRow(
          benchmark = benchmark,
          parameters = normalizeParameters(parameterColumns, values),
          mode = values[indices.getValue("Mode")].also { require(it.isNotBlank()) },
          threads = threads,
          samples = samples,
          score = score,
          scoreError = error,
          unit = unit,
        )
      }
    }
  require(rows.isNotEmpty()) { "No benchmark rows matched the selector" }
  require(rows.map { it.key }.distinct().size == rows.size) {
    "CSV contains duplicate benchmark keys"
  }
  return normalizedFrame(rows)
}

private fun String.strictInt(column: String, rowIndex: Int): Int =
  toIntOrNull() ?: error("Malformed $column at CSV row ${rowIndex + 2}")

private fun String.strictDouble(column: String, rowIndex: Int): Double =
  (toDoubleOrNull() ?: error("Malformed $column at CSV row ${rowIndex + 2}")).also {
    require(it.isFinite()) { "$column must be finite at CSV row ${rowIndex + 2}" }
  }

private fun normalizeParameters(columns: List<Pair<String, Int>>, values: List<String>): String =
  columns.joinToString(";") { (header, index) ->
    val name = header.removePrefix("Param: ")
    val value = values[index]
    "${name.length}:$name=${value.length}:$value"
  }

private fun normalizedFrame(rows: List<JmhRow>): DataFrame<NormalizedJmhRowSchema> =
  dataFrameOf(
      "benchmark" to rows.map { it.benchmark },
      "parameters" to rows.map { it.parameters },
      "mode" to rows.map { it.mode },
      "threads" to rows.map { it.threads },
      "samples" to rows.map { it.samples },
      "score" to rows.map { it.score },
      "scoreError99_9" to rows.map { it.scoreError },
      "unit" to rows.map { it.unit },
    )
    .cast()

internal fun DataFrame<NormalizedJmhRowSchema>.toJmhRows(): List<JmhRow> =
  iterator()
    .asSequence()
    .map { row ->
      JmhRow(
        benchmark = row["benchmark"] as String,
        parameters = row["parameters"] as String,
        mode = row["mode"] as String,
        threads = row["threads"] as Int,
        samples = row["samples"] as Int,
        score = row["score"] as Double,
        scoreError = row["scoreError99_9"] as Double,
        unit = row["unit"] as String,
      )
    }
    .toList()

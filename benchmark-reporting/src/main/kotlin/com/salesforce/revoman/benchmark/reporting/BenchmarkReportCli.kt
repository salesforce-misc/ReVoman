package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

data class ReportRequest(val manifest: Path)

object BenchmarkReportCli {
  private const val ARGUMENT_COUNT = 3

  fun run(args: Array<String>): Int =
    runCatching {
        require(args.size == ARGUMENT_COUNT && args[0] == "compare" && args[1] == "--manifest") {
          "Usage: compare --manifest <path>"
        }
        compare(ReportRequest(Path.of(args[2])))
      }
      .getOrElse {
        System.err.println("benchmark-reporting: ${it.message}")
        2
      }

  private fun compare(request: ReportRequest): Int {
    val manifest = request.manifest.toAbsolutePath().normalize()
    require(Files.isRegularFile(manifest)) { "Manifest is not readable: $manifest" }
    val runDir = requireNotNull(manifest.parent) { "Manifest must have a parent directory" }
    val manifestJson = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
    val raw = requireNotNull(manifestJson["raw"]?.jsonObject) { "Manifest is missing raw paths" }
    val baselinePath = resolve(runDir, raw.getValue("baseline").jsonPrimitive.content)
    val candidatePath = resolve(runDir, raw.getValue("candidate").jsonPrimitive.content)
    val selector =
      requireNotNull(manifestJson["benchmarkSelector"]?.jsonPrimitive?.content) {
          "Manifest is missing benchmarkSelector"
        }
        .toRegex()

    val baseline = readJmh(baselinePath, selector)
    val candidate = readJmh(candidatePath, selector)
    val comparisons = compareFrames(baseline, candidate)
    return if (comparisons.all { it.passed }) {
      publish(runDir, comparisonFrame(comparisons))
      0
    } else {
      1
    }
  }
}

private data class JmhRow(
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

private data class ComparisonKey(
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

private data class ComparisonRow(
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

private fun resolve(base: Path, value: String): Path =
  Path.of(value).let { if (it.isAbsolute) it.normalize() else base.resolve(it).normalize() }

private fun readJmh(path: Path, selector: Regex): DataFrame<NormalizedJmhRowSchema> {
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

private fun compareFrames(
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

private fun DataFrame<NormalizedJmhRowSchema>.toJmhRows(): List<JmhRow> =
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

private fun comparisonFrame(rows: List<ComparisonRow>): DataFrame<ComparisonRowSchema> =
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

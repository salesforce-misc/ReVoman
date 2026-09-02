package com.salesforce.revoman.benchmark.reporting

import java.util.Locale
import org.jetbrains.kotlinx.dataframe.DataFrame

internal fun renderCsv(frame: DataFrame<ComparisonRowSchema>): String {
  val headers = frame.columnNames()
  return buildString {
    appendLine(headers.joinToString(",", transform = ::csvField))
    frame.iterator().forEach { row ->
      appendLine(headers.joinToString(",") { csvField(row[it].toString()) })
    }
  }
}

internal fun renderMarkdown(frame: DataFrame<ComparisonRowSchema>): String = buildString {
  appendLine("# Benchmark comparison")
  appendLine()
  appendLine("| Benchmark | Parameters | Baseline lower | Candidate upper | Delta | Result |")
  appendLine("| --- | --- | ---: | ---: | ---: | --- |")
  frame.iterator().forEach { row ->
    val result = if (row["passed"] as Boolean) "PASS" else "FAIL"
    appendLine(
      "| ${row["benchmark"]} | ${row["parameters"]} | ${row["baselineLower"]} | " +
        "${row["candidateUpper"]} | ${row["deltaPercent"]}% | $result |"
    )
  }
}

internal fun renderScorecardCsv(frame: DataFrame<ScorecardRowSchema>): String {
  val headers = frame.columnNames()
  return buildString {
    appendLine(headers.joinToString(",", transform = ::csvField))
    frame.iterator().forEach { row ->
      appendLine(
        headers.joinToString(",") { header ->
          val value = row[header]
          csvField(if (value is Double) value.scorecardNumber() else value.toString())
        }
      )
    }
  }
}

internal fun renderScorecardMarkdown(frame: DataFrame<ScorecardRowSchema>): String = buildString {
  appendLine("# Consumer performance scorecard")
  appendLine()
  appendLine("| Journey | Workload | Score | 99.9% error | Unit |")
  appendLine("| --- | --- | ---: | ---: | --- |")
  frame.iterator().forEach { row ->
    appendLine(
      "| ${row["journey"]} | ${row["workload"]} | " +
        "${(row["score"] as Double).scorecardNumber()} | " +
        "${(row["scoreError99_9"] as Double).scorecardNumber()} | ${row["unit"]} |"
    )
  }
}

internal fun renderScorecardAsciiDoc(
  studyId: String,
  runId: String,
  frame: DataFrame<ScorecardRowSchema>,
): String = buildString {
  appendLine(":scorecard-study-id: $studyId")
  appendLine(":scorecard-run-id: $runId")
  appendLine()
  appendLine("[cols=\"3,4,1,1,1\",options=\"header\"]")
  appendLine("|===")
  appendLine("|Journey |Workload |Score |99.9% error |Unit")
  frame.iterator().forEach { row ->
    appendLine(
      "|${row["journey"]} |${row["workload"]} |" +
        "${(row["score"] as Double).scorecardNumber()} |" +
        "${(row["scoreError99_9"] as Double).scorecardNumber()} |${row["unit"]}"
    )
  }
  appendLine("|===")
}

private fun Double.scorecardNumber(): String = String.format(Locale.ROOT, "%.17g", this)

private fun csvField(value: String): String =
  if (value.any(::requiresCsvQuoting)) {
    "\"${value.replace("\"", "\"\"")}\""
  } else {
    value
  }

private fun requiresCsvQuoting(character: Char): Boolean =
  character == ',' || character == '"' || character == '\n' || character == '\r'

@Suppress("CyclomaticComplexMethod", "ComplexCondition")
internal fun parseCsv(text: String): List<List<String>> {
  val records = mutableListOf<List<String>>()
  var record = mutableListOf<String>()
  val field = StringBuilder()
  var quoted = false
  var quoteClosed = false
  var index = 0
  while (index < text.length) {
    val current = text[index]
    when {
      quoted && current == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
        field.append('"')
        index++
      }
      quoted && current == '"' -> {
        quoted = false
        quoteClosed = true
      }
      !quoted && current == '"' -> {
        require(field.isEmpty() && !quoteClosed) { "Malformed CSV quote" }
        quoted = true
      }
      !quoted && current == ',' -> {
        record += field.toString()
        field.clear()
        quoteClosed = false
      }
      !quoted && (current == '\n' || current == '\r') -> {
        if (current == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
        record += field.toString()
        field.clear()
        if (record.any(String::isNotEmpty)) records += record
        record = mutableListOf()
        quoteClosed = false
      }
      quoteClosed -> error("Malformed CSV characters after closing quote")
      else -> field.append(current)
    }
    index++
  }
  require(!quoted) { "Malformed CSV quote" }
  if (field.isNotEmpty() || record.isNotEmpty() || quoteClosed) {
    record += field.toString()
    records += record
  }
  return records
}

package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ReportRequest(val manifest: Path)

object BenchmarkReportCli {
  private const val ARGUMENT_COUNT = 3

  fun run(args: Array<String>): Int =
    runCatching {
        require(args.size == ARGUMENT_COUNT && args[1] == "--manifest") {
          "Usage: <compare|scorecard> --manifest <path>"
        }
        val request = ReportRequest(Path.of(args[2]))
        when (args[0]) {
          "compare" -> compare(request)
          "scorecard" -> scorecard(request)
          else -> error("Usage: <compare|scorecard> --manifest <path>")
        }
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
      publishComparison(runDir, comparisonFrame(comparisons))
      0
    } else {
      1
    }
  }

  private fun scorecard(request: ReportRequest): Int {
    val document = buildScorecard(request.manifest)
    publishScorecard(document.runDir, document)
    return 0
  }
}

internal fun resolve(base: Path, value: String): Path =
  Path.of(value).let { if (it.isAbsolute) it.normalize() else base.resolve(it).normalize() }

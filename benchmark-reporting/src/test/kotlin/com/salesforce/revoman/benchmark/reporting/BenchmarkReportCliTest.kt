package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class BenchmarkReportCliTest :
  StringSpec({
    "disjoint confidence intervals publish sorted comparison and report" {
      withRun(
        resource("baseline.csv"),
        resource("candidate.csv"),
      ) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 0

        val rows = runDir.resolve("comparison.csv").readLines()
        rows.drop(1).map { it.substringBefore(',') }.shouldBeSorted()
        rows[1] shouldContain "9.5"
        rows[1] shouldContain "8.5"
        rows[1] shouldContain "true"
        runDir.resolve("report.md").readText() shouldContain "PASS"
      }
    }

    "overlapping intervals return one and preserve generated outputs" {
      withRun(singleCsv(score = "10", error = "1"), singleCsv(score = "9", error = "1")) {
        runDir,
        manifest ->
        val comparison = runDir.resolve("comparison.csv").also { it.writeText("old comparison") }
        val report = runDir.resolve("report.md").also { it.writeText("old report") }

        BenchmarkReportCli.run(compare(manifest)) shouldBe 1
        comparison.readText() shouldBe "old comparison"
        report.readText() shouldBe "old report"
      }
    }

    "different benchmark keys are invalid and publish nothing" {
      withRun(
        singleCsv(benchmark = "baseline.Benchmark.run"),
        singleCsv(benchmark = "candidate.Benchmark.run"),
      ) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 2
        runDir.resolve("comparison.csv").exists() shouldBe false
        runDir.resolve("report.md").exists() shouldBe false
      }
    }

    "parameter columns normalize by sorted parameter name" {
      val baseline =
        csv(
          headers = listOf("Param: size", "Param: flavor"),
          values = listOf("10", "vanilla"),
          score = "10",
          error = "0.5",
        )
      val candidate =
        csv(
          headers = listOf("Param: flavor", "Param: size"),
          values = listOf("vanilla", "10"),
          score = "8",
          error = "0.5",
        )

      withRun(baseline, candidate) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 0
        runDir.resolve("comparison.csv").readText() shouldContain "6:flavor=7:vanilla;4:size=2:10"
      }
    }

    "malformed numeric input is invalid" {
      withRun(singleCsv(score = "ten"), singleCsv()) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 2
        generatedOutputs(runDir) shouldBe emptyList()
      }
    }

    "characters after a closing quote are invalid CSV" {
      val malformed = singleCsv().replace("example.Benchmark.run", "\"example.Benchmark.run\"junk")
      withRun(malformed, malformed) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 2
        generatedOutputs(runDir) shouldBe emptyList()
      }
    }

    "non-finite numeric input is invalid" {
      listOf("NaN", "Infinity", "-Infinity").forEach { value ->
        withRun(singleCsv(score = value), singleCsv()) { runDir, manifest ->
          BenchmarkReportCli.run(compare(manifest)) shouldBe 2
          generatedOutputs(runDir) shouldBe emptyList()
        }
      }
    }

    "negative confidence error is invalid" {
      withRun(singleCsv(error = "-0.1"), singleCsv()) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 2
        generatedOutputs(runDir) shouldBe emptyList()
      }
    }

    "missing confidence error column or value is invalid" {
      val missingColumn =
        "Benchmark,Mode,Threads,Samples,Score,Unit\n" +
          "example.Benchmark.run,avgt,1,100,10,ms/op\n"
      listOf(missingColumn, singleCsv(error = "")).forEach { baseline ->
        withRun(baseline, singleCsv()) { runDir, manifest ->
          BenchmarkReportCli.run(compare(manifest)) shouldBe 2
          generatedOutputs(runDir) shouldBe emptyList()
        }
      }
    }

    "duplicate normalized keys are invalid" {
      val duplicate =
        singleCsv() + singleCsv().lineSequence().filter(String::isNotBlank).last() + "\n"
      withRun(duplicate, singleCsv()) { runDir, manifest ->
        BenchmarkReportCli.run(compare(manifest)) shouldBe 2
        generatedOutputs(runDir) shouldBe emptyList()
      }
    }

    "partial publication failure restores prior outputs and removes temporary directories" {
      val parent = Files.createTempDirectory("benchmark-report-publication-test-")
      try {
        val runDir = parent.resolve("run").createDirectories()
        val comparison = runDir.resolve("comparison.csv").also { it.writeText("old comparison") }
        val report = runDir.resolve("report.md").also { it.writeText("old report") }
        var moveCount = 0

        shouldThrow<IOException> {
          publish(runDir, passingComparisonFrame()) { source, target ->
            moveCount++
            if (moveCount == 4) throw IOException("fail after first output installation")
            Files.move(source, target, REPLACE_EXISTING)
          }
        }

        moveCount shouldBe 6
        comparison.readText() shouldBe "old comparison"
        report.readText() shouldBe "old report"
        runDir.parent.listDirectoryEntries(".benchmark-report-*").filter { it.exists() } shouldBe
          emptyList()
      } finally {
        parent.deleteRecursively()
      }
    }
  })

private fun compare(manifest: Path): Array<String> =
  arrayOf("compare", "--manifest", manifest.toAbsolutePath().toString())

private fun generatedOutputs(runDir: Path): List<Path> =
  listOf(runDir.resolve("comparison.csv"), runDir.resolve("report.md")).filter { it.exists() }

private fun passingComparisonFrame(): DataFrame<ComparisonRowSchema> =
  dataFrameOf(
      "benchmark" to listOf("example.Benchmark.run"),
      "parameters" to listOf(""),
      "mode" to listOf("avgt"),
      "threads" to listOf(1),
      "unit" to listOf("ms/op"),
      "baselineScore" to listOf(10.0),
      "baselineError99_9" to listOf(0.5),
      "baselineLower" to listOf(9.5),
      "candidateScore" to listOf(8.0),
      "candidateError99_9" to listOf(0.5),
      "candidateUpper" to listOf(8.5),
      "deltaPercent" to listOf(-20.0),
      "passed" to listOf(true),
    )
    .cast()

private fun resource(name: String): String =
  checkNotNull(BenchmarkReportCliTest::class.java.getResource("/jmh/$name")).readText()

private fun singleCsv(
  benchmark: String = "example.Benchmark.run",
  score: String = "10",
  error: String = "0.5",
): String = csv(emptyList(), emptyList(), score, error, benchmark)

private fun csv(
  headers: List<String>,
  values: List<String>,
  score: String,
  error: String,
  benchmark: String = "example.Benchmark.run",
): String {
  val fixedHeaders =
    listOf("Benchmark", "Mode", "Threads", "Samples", "Score", "Score Error (99.9%)", "Unit")
  val fixedValues = listOf(benchmark, "avgt", "1", "100", score, error, "ms/op")
  return (fixedHeaders + headers).joinToString(",") +
    "\n" +
    (fixedValues + values).joinToString(",") +
    "\n"
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private inline fun withRun(
  baseline: String,
  candidate: String,
  assertion: (Path, Path) -> Unit,
) {
  val parent = Files.createTempDirectory("benchmark-report-test-")
  try {
    val runDir = parent.resolve("run").createDirectories()
    val rawDir = runDir.resolve("raw").createDirectories()
    rawDir.resolve("baseline.csv").writeText(baseline)
    rawDir.resolve("candidate.csv").writeText(candidate)
    val manifest =
      runDir.resolve("manifest.json").also {
        it.writeText(
          """
          {
            "benchmarkSelector": ".*",
            "raw": {
              "baseline": "raw/baseline.csv",
              "candidate": "raw/candidate.csv"
            }
          }
          """
            .trimIndent()
        )
      }
    assertion(runDir, manifest)
  } finally {
    parent.deleteRecursively()
  }
}

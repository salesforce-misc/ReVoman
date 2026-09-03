package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ScorecardReportCliTest :
  StringSpec({
    "valid eight-row scorecard publishes all absolute reports in consumer order" {
      withScorecardRun { runDir, manifest ->
        BenchmarkReportCli.run(scorecard(manifest)) shouldBe 0

        runDir.generatedScorecardOutputs().map { it.fileName.toString() } shouldContainExactly
          listOf("performance-scorecard.adoc", "report.md", "scorecard.csv")
        val scorecard = runDir.resolve("scorecard.csv").readLines()
        scorecard.first() shouldBe "journey,workload,score,scoreError99_9,unit"
        scorecard.drop(1).map { it.substringBefore(',') } shouldContainExactly
          expectedRows.map { it.second }
        val sourceValues =
          parseCsv(scorecardCsv()).drop(1).map { it[4].toDouble() to it[5].toDouble() }
        parseCsv(runDir.resolve("scorecard.csv").readText()).drop(1).map {
          it[2].toDouble() to it[3].toDouble()
        } shouldContainExactly sourceValues
        runDir.resolve("report.md").readText() shouldContain
          "| Journey | Workload | Score | 99.9% error | Unit |"
        val asciidoc = runDir.resolve("performance-scorecard.adoc").readText()
        asciidoc shouldContain ":scorecard-study-id: consumer-performance-scorecard"
        asciidoc shouldContain ":scorecard-run-id: 20260902T010203Z"
      }
    }

    "CSV input order does not alter consumer-facing order" {
      val records = parseCsv(scorecardCsv())
      val reversed = (listOf(records.first()) + records.drop(1).reversed()).toCsv()
      withScorecardRun(csv = reversed) { runDir, manifest ->
        BenchmarkReportCli.run(scorecard(manifest)) shouldBe 0
        runDir.resolve("scorecard.csv").readLines().drop(1).map {
          it.substringBefore(',')
        } shouldContainExactly expectedRows.map { it.second }
      }
    }

    "missing expected CSV row is invalid and publishes nothing" {
      val lines = scorecardCsv().lineSequence().filter(String::isNotBlank).toList()
      withScorecardRun(csv = lines.dropLast(1).joinToString("\n")) { runDir, manifest ->
        assertInvalidScorecard(runDir, manifest)
      }
    }

    "duplicate CSV row is invalid and publishes nothing" {
      val lines = scorecardCsv().lineSequence().filter(String::isNotBlank).toList()
      withScorecardRun(csv = (lines + lines.last()).joinToString("\n")) { runDir, manifest ->
        assertInvalidScorecard(runDir, manifest)
      }
    }

    "unexpected CSV row is invalid and publishes nothing" {
      val lines = scorecardCsv().lineSequence().filter(String::isNotBlank).toList()
      val unexpected = lines.last().replace(expectedRows.last().first, "unexpected.Benchmark.run")
      withScorecardRun(csv = (lines + unexpected).joinToString("\n")) { runDir, manifest ->
        assertInvalidScorecard(runDir, manifest)
      }
    }

    "selector mismatch is invalid and publishes nothing" {
      withScorecardRun(manifestText = validManifest(selector = ".*")) { runDir, manifest ->
        assertInvalidScorecard(runDir, manifest)
      }
    }

    "manifest scorecard descriptors must match the fixed eight rows" {
      withScorecardRun(
        manifestText = validManifest().replace("Postman V2 collection", "Changed journey")
      ) { runDir, manifest ->
        assertInvalidScorecard(runDir, manifest)
      }
    }

    listOf(
        Triple("Mode", "thrpt", "wrong mode"),
        Triple("Threads", "2", "wrong threads"),
        Triple("Samples", "99", "wrong samples"),
        Triple("Unit", "ops/s", "wrong unit"),
      )
      .forEach { (column, value, case) ->
        "$case is invalid and publishes nothing" {
          withScorecardRun(csv = scorecardCsv().replaceCsvCell(column, value)) { runDir, manifest ->
            assertInvalidScorecard(runDir, manifest)
          }
        }
      }

    listOf(
        Triple("Score", "invalid", "malformed score"),
        Triple("Score", "NaN", "non-finite score"),
        Triple("Score", "-0.1", "negative score"),
        Triple("Score Error (99.9%)", "invalid", "malformed error"),
        Triple("Score Error (99.9%)", "Infinity", "non-finite error"),
        Triple("Score Error (99.9%)", "-0.1", "negative error"),
      )
      .forEach { (column, value, case) ->
        "$case is invalid and publishes nothing" {
          withScorecardRun(csv = scorecardCsv().replaceCsvCell(column, value)) { runDir, manifest ->
            assertInvalidScorecard(runDir, manifest)
          }
        }
      }

    listOf("Score Error" to "renamed", "" to "absent").forEach { (replacement, case) ->
      "$case 99.9-percent error data is invalid and publishes nothing" {
        withScorecardRun(csv = scorecardCsv().replaceFirst("Score Error (99.9%)", replacement)) {
          runDir,
          manifest ->
          assertInvalidScorecard(runDir, manifest)
        }
      }
    }

    listOf(
        Triple("\"forks\": 5", "\"forks\": 4", "forks"),
        Triple("\"warmups\": 10", "\"warmups\": 9", "warmups"),
        Triple("\"measurements\": 20", "\"measurements\": 19", "measurements"),
        Triple("\"iterationSeconds\": 1", "\"iterationSeconds\": 2", "iteration duration"),
        Triple("\"confidence\": 99.9", "\"confidence\": 99.0", "confidence"),
      )
      .forEach { (expected, replacement, case) ->
        "wrong $case metadata is invalid and publishes nothing" {
          withScorecardRun(manifestText = validManifest().replace(expected, replacement)) {
            runDir,
            manifest ->
            assertInvalidScorecard(runDir, manifest)
          }
        }
      }

    listOf("revision", "cpuAffinity", "libraryVersion", "dependencyFingerprint").forEach { field ->
      "missing $field metadata is invalid and publishes nothing" {
        withScorecardRun(manifestText = validManifest().withoutLineContaining("\"$field\"")) {
          runDir,
          manifest ->
          assertInvalidScorecard(runDir, manifest)
        }
      }
    }

    listOf("runner", "launcher", "inherited", "gradleDaemon", "jmh").forEach { identity ->
      "missing $identity Java identity is invalid and publishes nothing" {
        withScorecardRun(manifestText = validManifest().withoutJavaIdentity(identity)) {
          runDir,
          manifest ->
          assertInvalidScorecard(runDir, manifest)
        }
      }

      "$identity Java feature other than 25 is invalid and publishes nothing" {
        val line = validManifest().lineSequence().first { it.contains("\"$identity\"") }
        withScorecardRun(manifestText = validManifest().replace(line, line.replace("25", "21"))) {
          runDir,
          manifest ->
          assertInvalidScorecard(runDir, manifest)
        }
      }
    }

    listOf(1, 2).forEach { failedInstallation ->
      "move-then-throw after new installation $failedInstallation removes every scorecard output" {
        val parent = Files.createTempDirectory("benchmark-scorecard-new-publication-test-")
        try {
          val runDir = parent.resolve("run").createDirectories()
          var installations = 0

          shouldThrow<IOException> {
            publishScorecard(runDir, passingScorecardDocument(runDir)) { source, target ->
              Files.move(source, target, REPLACE_EXISTING)
              installations++
              if (installations == failedInstallation) {
                throw IOException("injected after installation")
              }
            }
          }

          runDir.generatedScorecardOutputs() shouldBe emptyList()
          parent.listDirectoryEntries(".benchmark-report-*") shouldBe emptyList()
        } finally {
          parent.deleteRecursively()
        }
      }
    }

    listOf(1, 2).forEach { failedInstallation ->
      "move-then-throw after replacement $failedInstallation restores every prior scorecard output" {
        val parent = Files.createTempDirectory("benchmark-scorecard-replacement-test-")
        try {
          val runDir = parent.resolve("run").createDirectories()
          val outputs =
            listOf("scorecard.csv", "report.md", "performance-scorecard.adoc").associateWith { name
              ->
              runDir.resolve(name).also { it.writeText("old $name") }
            }
          var installations = 0

          shouldThrow<IOException> {
            publishScorecard(runDir, passingScorecardDocument(runDir)) { source, target ->
              Files.move(source, target, REPLACE_EXISTING)
              if (target.parent == runDir && ++installations == failedInstallation) {
                throw IOException("injected after replacement")
              }
            }
          }

          outputs.forEach { (name, path) -> path.readText() shouldBe "old $name" }
          parent.listDirectoryEntries(".benchmark-report-*") shouldBe emptyList()
        } finally {
          parent.deleteRecursively()
        }
      }
    }

    "publisher rejects a nested output name" {
      val parent = Files.createTempDirectory("benchmark-scorecard-output-name-test-")
      try {
        val runDir = parent.resolve("run").createDirectories()
        shouldThrow<IllegalArgumentException> {
          publishFiles(runDir, mapOf("nested/output.txt" to "content"))
        }
      } finally {
        parent.deleteRecursively()
      }
    }

    "publisher rejects an absolute output name" {
      val parent = Files.createTempDirectory("benchmark-scorecard-output-name-test-")
      try {
        val runDir = parent.resolve("run").createDirectories()
        shouldThrow<IllegalArgumentException> {
          publishFiles(runDir, mapOf(parent.resolve("absolute-output.txt").toString() to "content"))
        }
      } finally {
        parent.deleteRecursively()
      }
    }
  })

private const val SCORECARD_SELECTOR =
  "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\..*$"

private val expectedRows =
  listOf(
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.postmanV2TenStepRevUp",
      "Postman V2 collection",
      "10-step script-free revUp",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.postmanV2TenStepScriptedRevUp",
      "Script-bearing Postman V2 collection",
      "10-step scripted revUp",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepRevUp",
      "ReVoman V3 collection",
      "10-step script-free revUp",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3HundredStepRevUp",
      "Large V3 collection",
      "100-step script-free revUp",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepScriptedRevUp",
      "Script-bearing V3 collection",
      "10-step scripted revUp",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeKickEnvironmentHandoff",
      "Three-kick workflow",
      "Three 10-step kicks with environment handoff",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeStepRunbookWithContracts",
      "Contracted runbook",
      "Three-step runbook with contracts",
    ),
    Triple(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.verboseHundredStepRundownJson",
      "Verbose result rendering",
      "100-step rundown to verbose JSON",
    ),
  )

private fun scorecard(manifest: Path): Array<String> =
  arrayOf("scorecard", "--manifest", manifest.toAbsolutePath().toString())

private fun Path.generatedScorecardOutputs(): List<Path> =
  listDirectoryEntries()
    .filter { it.fileName.toString() !in setOf("manifest.json", "raw") }
    .sorted()

private fun scorecardCsv(): String =
  checkNotNull(ScorecardReportCliTest::class.java.getResource("/jmh/consumer-scorecard.csv"))
    .readText()

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private inline fun withScorecardRun(
  csv: String = scorecardCsv(),
  manifestText: String = validManifest(),
  assertion: (Path, Path) -> Unit,
) {
  val parent = Files.createTempDirectory("benchmark-scorecard-test-")
  try {
    val runDir = parent.resolve("run").createDirectories()
    runDir.resolve("raw").createDirectories().resolve("results.csv").writeText(csv)
    val manifest = runDir.resolve("manifest.json").also { it.writeText(manifestText) }
    assertion(runDir, manifest)
  } finally {
    parent.deleteRecursively()
  }
}

private fun validManifest(selector: String = SCORECARD_SELECTOR): String {
  val jsonSelector = selector.replace("\\", "\\\\")
  val rows =
    expectedRows.joinToString(",\n") { (benchmark, journey, workload) ->
      """{"benchmark":"$benchmark","journey":"$journey","workload":"$workload"}"""
    }
  return """
    {
      "schemaVersion": 1,
      "studyId": "consumer-performance-scorecard",
      "runId": "20260902T010203Z",
      "libraryVersion": "0.1.0",
      "revision": "0123456789abcdef0123456789abcdef01234567",
      "benchmarkSelector": "$jsonSelector",
      "expectedRows": [$rows],
      "profile": {
        "mode": "avgt",
        "unit": "ms/op",
        "threads": 1,
        "forks": 5,
        "warmups": 10,
        "measurements": 20,
        "iterationSeconds": 1,
        "confidence": 99.9
      },
      "dependencyFingerprint": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "cpuAffinity": {"logicalCpuList": "0,1,2,3"},
      "javaIdentities": {
        "runner": {"feature": 25, "identity": "OpenJDK 25 runner"},
        "launcher": {"feature": 25, "identity": "OpenJDK 25 launcher"},
        "inherited": {"feature": 25, "identity": "OpenJDK 25 inherited"},
        "gradleDaemon": {"feature": 25, "identity": "OpenJDK 25 daemon"},
        "jmh": {"feature": 25, "identity": "OpenJDK 25 JMH"}
      },
      "raw": {"results": "raw/results.csv"}
    }
  """
    .trimIndent()
}

private fun assertInvalidScorecard(runDir: Path, manifest: Path) {
  BenchmarkReportCli.run(scorecard(manifest)) shouldBe 2
  runDir.generatedScorecardOutputs() shouldBe emptyList()
}

private fun String.replaceCsvCell(column: String, value: String): String {
  val records = parseCsv(this)
  val index = records.first().indexOf(column)
  return records
    .mapIndexed { rowIndex, row ->
      if (rowIndex == 1) row.toMutableList().also { it[index] = value } else row
    }
    .toCsv()
}

private fun List<List<String>>.toCsv(): String =
  joinToString("\n", postfix = "\n") { it.joinToString(",") }

private fun String.withoutLineContaining(fragment: String): String =
  lineSequence().filterNot { it.contains(fragment) }.joinToString("\n")

private fun String.withoutJavaIdentity(name: String): String {
  val lines = lineSequence().toMutableList()
  val removedIndex = lines.indexOfFirst { it.contains("\"$name\"") }
  val removedLastEntry = !lines[removedIndex].trimEnd().endsWith(',')
  lines.removeAt(removedIndex)
  if (removedLastEntry) lines[removedIndex - 1] = lines[removedIndex - 1].removeSuffix(",")
  return lines.joinToString("\n")
}

private fun passingScorecardDocument(runDir: Path): ScorecardDocument =
  ScorecardDocument(
    runDir = runDir,
    studyId = "consumer-performance-scorecard",
    runId = "20260902T010203Z",
    frame =
      dataFrameOf(
          "journey" to listOf("Postman V2 collection"),
          "workload" to listOf("10-step script-free revUp"),
          "score" to listOf(0.1),
          "scoreError99_9" to listOf(0.01),
          "unit" to listOf("ms/op"),
        )
        .cast(),
  )

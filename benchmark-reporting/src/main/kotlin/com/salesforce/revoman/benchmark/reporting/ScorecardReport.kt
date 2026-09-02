package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

internal data class ExpectedScorecardRow(
  val benchmark: String,
  val journey: String,
  val workload: String,
)

internal data class ScorecardProfile(
  val mode: String,
  val unit: String,
  val threads: Int,
  val forks: Int,
  val warmups: Int,
  val measurements: Int,
  val iterationSeconds: Int,
  val confidence: Double,
)

internal data class ScorecardDocument(
  val runDir: Path,
  val studyId: String,
  val runId: String,
  val frame: DataFrame<ScorecardRowSchema>,
)

private const val SCORECARD_SELECTOR =
  "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\..*$"
private const val SCORECARD_STUDY_ID = "consumer-performance-scorecard"
private const val EXPECTED_JAVA_FEATURE = 25
private const val EXPECTED_SAMPLES = 100

private val expectedProfile = ScorecardProfile("avgt", "ms/op", 1, 5, 10, 20, 1, 99.9)

private val scorecardRows =
  listOf(
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.postmanV2TenStepRevUp",
      "Postman V2 collection",
      "10-step script-free revUp",
    ),
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepRevUp",
      "ReVoman V3 collection",
      "10-step script-free revUp",
    ),
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3HundredStepRevUp",
      "Large V3 collection",
      "100-step script-free revUp",
    ),
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepScriptedRevUp",
      "Script-bearing V3 collection",
      "10-step scripted revUp",
    ),
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeKickEnvironmentHandoff",
      "Three-kick workflow",
      "Three 10-step kicks with environment handoff",
    ),
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeStepRunbookWithContracts",
      "Contracted runbook",
      "Three-step runbook with contracts",
    ),
    ExpectedScorecardRow(
      "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.verboseHundredStepRundownJson",
      "Verbose result rendering",
      "100-step rundown to verbose JSON",
    ),
  )

internal fun buildScorecard(manifestPath: Path): ScorecardDocument {
  val manifest = manifestPath.toAbsolutePath().normalize()
  require(Files.isRegularFile(manifest) && Files.isReadable(manifest)) {
    "Manifest is not readable: $manifest"
  }
  val runDir = requireNotNull(manifest.parent) { "Manifest must have a parent directory" }
  val json = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
  require(json.getValue("schemaVersion").jsonPrimitive.content.toInt() == 1)
  require(json.getValue("studyId").jsonPrimitive.content == SCORECARD_STUDY_ID)
  require(json.getValue("runId").jsonPrimitive.content.isNotBlank())
  require(json.getValue("benchmarkSelector").jsonPrimitive.content == SCORECARD_SELECTOR) {
    "Scorecard benchmarkSelector does not match the fixed selector"
  }
  require(json.expectedRows() == scorecardRows) {
    "Manifest expectedRows do not match the fixed scorecard descriptors"
  }
  require(json.getValue("profile").jsonObject.scorecardProfile() == expectedProfile) {
    "Manifest profile does not match the fixed scorecard profile"
  }
  require(json.getValue("libraryVersion").jsonPrimitive.content.isNotBlank()) {
    "libraryVersion must not be blank"
  }
  require(json.getValue("revision").jsonPrimitive.content.isNotBlank()) {
    "revision must not be blank"
  }
  require(json.getValue("dependencyFingerprint").jsonPrimitive.content.isNotBlank()) {
    "dependencyFingerprint must not be blank"
  }
  require(
    json
      .getValue("cpuAffinity")
      .jsonObject
      .getValue("logicalCpuList")
      .jsonPrimitive
      .content
      .isNotBlank()
  ) {
    "CPU affinity must not be blank"
  }
  val javaIdentities = json.getValue("javaIdentities").jsonObject
  require(javaIdentities.keys == setOf("runner", "launcher", "inherited", "gradleDaemon", "jmh")) {
    "Manifest must record every Java identity"
  }
  javaIdentities.forEach { (name, element) ->
    val identity = element.jsonObject
    require(identity.getValue("feature").jsonPrimitive.content.toInt() == EXPECTED_JAVA_FEATURE) {
      "$name Java feature must be 25"
    }
    require(identity.getValue("identity").jsonPrimitive.content.isNotBlank()) {
      "$name Java identity must not be blank"
    }
  }
  val resultsPath =
    resolve(runDir, json.getValue("raw").jsonObject.getValue("results").jsonPrimitive.content)
  val measuredRows = readJmh(resultsPath, Regex(".*")).toJmhRows()
  require(
    scorecardRows.all { expected -> measuredRows.any { it.benchmark == expected.benchmark } }
  ) {
    "CSV is missing an expected scorecard benchmark"
  }
  require(measuredRows.map(JmhRow::benchmark).distinct().size == measuredRows.size) {
    "CSV contains a duplicate scorecard benchmark"
  }
  require(measuredRows.all { row -> scorecardRows.any { it.benchmark == row.benchmark } }) {
    "CSV contains an unexpected scorecard benchmark"
  }
  require(measuredRows.all { it.mode == expectedProfile.mode }) { "Scorecard mode must be avgt" }
  require(measuredRows.all { it.threads == expectedProfile.threads }) {
    "Scorecard threads must be one"
  }
  require(measuredRows.all { it.samples == EXPECTED_SAMPLES }) {
    "Scorecard samples must be 100"
  }
  require(measuredRows.all { it.unit == expectedProfile.unit }) {
    "Scorecard unit must be ms/op"
  }
  require(measuredRows.all { it.parameters.isEmpty() }) {
    "Scorecard benchmarks must not have parameters"
  }
  require(measuredRows.all { it.score >= 0.0 }) { "Scorecard score must not be negative" }
  val measured = measuredRows.associateBy(JmhRow::benchmark)
  val rows = scorecardRows.map { expected -> measured.getValue(expected.benchmark) }
  return ScorecardDocument(
    runDir,
    json.getValue("studyId").jsonPrimitive.content,
    json.getValue("runId").jsonPrimitive.content,
    dataFrameOf(
        "journey" to scorecardRows.map { it.journey },
        "workload" to scorecardRows.map { it.workload },
        "score" to rows.map { it.score },
        "scoreError99_9" to rows.map { it.scoreError },
        "unit" to rows.map { it.unit },
      )
      .cast(),
  )
}

private fun JsonObject.expectedRows(): List<ExpectedScorecardRow> =
  getValue("expectedRows").jsonArray.map { element ->
    val row = element.jsonObject
    ExpectedScorecardRow(
      benchmark = row.getValue("benchmark").jsonPrimitive.content,
      journey = row.getValue("journey").jsonPrimitive.content,
      workload = row.getValue("workload").jsonPrimitive.content,
    )
  }

private fun JsonObject.scorecardProfile(): ScorecardProfile =
  ScorecardProfile(
    mode = getValue("mode").jsonPrimitive.content,
    unit = getValue("unit").jsonPrimitive.content,
    threads = getValue("threads").jsonPrimitive.content.toInt(),
    forks = getValue("forks").jsonPrimitive.content.toInt(),
    warmups = getValue("warmups").jsonPrimitive.content.toInt(),
    measurements = getValue("measurements").jsonPrimitive.content.toInt(),
    iterationSeconds = getValue("iterationSeconds").jsonPrimitive.content.toInt(),
    confidence = getValue("confidence").jsonPrimitive.content.toDouble(),
  )

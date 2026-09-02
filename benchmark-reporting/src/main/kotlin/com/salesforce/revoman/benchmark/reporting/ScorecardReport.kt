package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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

private const val SCORECARD_STUDY_ID = "consumer-performance-scorecard"
private const val SCORECARD_SELECTOR =
  "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\..*$"
private const val EXPECTED_SAMPLES = 100
private const val EXPECTED_JAVA_FEATURE = 25

private val expectedProfile =
  ScorecardProfile(
    mode = "avgt",
    unit = "ms/op",
    threads = 1,
    forks = 5,
    warmups = 10,
    measurements = 20,
    iterationSeconds = 1,
    confidence = 99.9,
  )

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
  require(json.requiredInt("schemaVersion") == 1) { "Unsupported manifest schemaVersion" }
  val studyId = json.requiredString("studyId")
  require(studyId == SCORECARD_STUDY_ID) { "Unexpected scorecard studyId" }
  val runId = json.requiredString("runId")
  require(runId.isNotBlank()) { "runId must not be blank" }
  require(json.requiredString("libraryVersion").isNotBlank()) { "libraryVersion must not be blank" }
  require(json.requiredString("revision").isNotBlank()) { "revision must not be blank" }
  require(json.requiredString("dependencyFingerprint").isNotBlank()) {
    "dependencyFingerprint must not be blank"
  }
  require(json.requiredString("benchmarkSelector") == SCORECARD_SELECTOR) {
    "Scorecard benchmarkSelector does not match the fixed selector"
  }
  require(json.expectedRows() == scorecardRows) {
    "Manifest expectedRows do not match the scorecard"
  }
  require(json.requiredObject("profile").scorecardProfile() == expectedProfile) {
    "Manifest profile does not match the fixed scorecard profile"
  }
  require(json.requiredObject("cpuAffinity").requiredString("logicalCpuList").isNotBlank()) {
    "CPU affinity must not be blank"
  }
  json.validateJavaIdentities()

  val raw = json.requiredObject("raw")
  val results = resolve(runDir, raw.requiredString("results"))
  val measured = readJmh(results, Regex(".*")).toJmhRows()
  validateMeasurements(measured)
  val measuredByBenchmark = measured.associateBy(JmhRow::benchmark)
  val frame =
    dataFrameOf(
        "journey" to scorecardRows.map { it.journey },
        "workload" to scorecardRows.map { it.workload },
        "score" to scorecardRows.map { measuredByBenchmark.getValue(it.benchmark).score },
        "scoreError99_9" to
          scorecardRows.map { measuredByBenchmark.getValue(it.benchmark).scoreError },
        "unit" to scorecardRows.map { measuredByBenchmark.getValue(it.benchmark).unit },
      )
      .cast<ScorecardRowSchema>()
  return ScorecardDocument(runDir, studyId, runId, frame)
}

private fun validateMeasurements(rows: List<JmhRow>) {
  val measuredBenchmarks = rows.map(JmhRow::benchmark)
  val expectedBenchmarks = scorecardRows.map(ExpectedScorecardRow::benchmark)
  require(
    measuredBenchmarks.size == expectedBenchmarks.size &&
      measuredBenchmarks.toSet() == expectedBenchmarks.toSet()
  ) {
    "CSV rows do not match the scorecard benchmarks"
  }
  require(rows.all { SCORECARD_SELECTOR.toRegex().matches(it.benchmark) }) {
    "CSV benchmark does not match the scorecard selector"
  }
  require(rows.all { it.parameters.isEmpty() }) { "Scorecard benchmarks must not have parameters" }
  require(rows.all { it.mode == expectedProfile.mode }) { "Scorecard mode must be avgt" }
  require(rows.all { it.threads == expectedProfile.threads }) { "Scorecard threads must be one" }
  require(rows.all { it.samples == EXPECTED_SAMPLES }) { "Scorecard samples must be 100" }
  require(rows.all { it.unit == expectedProfile.unit }) { "Scorecard unit must be ms/op" }
  require(rows.all { it.score >= 0.0 && it.scoreError >= 0.0 }) {
    "Scorecard numeric data must not be negative"
  }
}

private fun JsonObject.expectedRows(): List<ExpectedScorecardRow> =
  requiredArray("expectedRows").map { element ->
    val row = element.jsonObject
    ExpectedScorecardRow(
      benchmark = row.requiredString("benchmark"),
      journey = row.requiredString("journey"),
      workload = row.requiredString("workload"),
    )
  }

private fun JsonObject.scorecardProfile(): ScorecardProfile =
  ScorecardProfile(
    mode = requiredString("mode"),
    unit = requiredString("unit"),
    threads = requiredInt("threads"),
    forks = requiredInt("forks"),
    warmups = requiredInt("warmups"),
    measurements = requiredInt("measurements"),
    iterationSeconds = requiredInt("iterationSeconds"),
    confidence = requiredDouble("confidence"),
  )

private fun JsonObject.validateJavaIdentities() {
  val identities = requiredObject("javaIdentities")
  val expectedNames = setOf("runner", "launcher", "inherited", "gradleDaemon", "jmh")
  require(identities.keys == expectedNames) { "Manifest must record every Java identity" }
  identities.forEach { (name, element) ->
    val identity = element.jsonObject
    require(identity.requiredInt("feature") == EXPECTED_JAVA_FEATURE) {
      "$name Java feature must be 25"
    }
    require(identity.requiredString("identity").isNotBlank()) { "$name Java identity is blank" }
  }
}

private fun JsonObject.requiredObject(name: String): JsonObject =
  requireNotNull(this[name]) { "Manifest is missing $name" }.jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray =
  requireNotNull(this[name]) { "Manifest is missing $name" }.jsonArray

private fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]) { "Manifest is missing $name" }.jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int =
  requireNotNull(this[name]) { "Manifest is missing $name" }.jsonPrimitive.intOrNull
    ?: error("Manifest $name must be an integer")

private fun JsonObject.requiredDouble(name: String): Double =
  requireNotNull(this[name]) { "Manifest is missing $name" }
    .jsonPrimitive
    .doubleOrNull
    ?.also {
      require(it.isFinite()) { "Manifest $name must be finite" }
    } ?: error("Manifest $name must be numeric")

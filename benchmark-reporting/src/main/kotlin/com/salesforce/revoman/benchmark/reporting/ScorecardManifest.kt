package com.salesforce.revoman.benchmark.reporting

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val ENVIRONMENT_PATH = "environment/run.json"
internal const val RESULTS_PATH = "raw/results.csv"

internal data class ProfilerFact(
  val method: String,
  val event: String,
  val view: String,
  val recording: String,
  val summary: String,
)

internal fun scorecardManifest(
  preflight: ScorecardPreflight,
  runId: String,
  startedAt: Instant,
  completedAt: Instant,
  affinity: CpuAffinity,
  finalCommand: List<String>,
  fingerprint: String,
  profilerFacts: List<ProfilerFact>,
): String =
  buildJsonObject {
      put("schemaVersion", 1)
      put("studyId", SCORECARD_STUDY_ID)
      put("runId", runId)
      put("libraryVersion", preflight.libraryVersion)
      put("revision", preflight.revision)
      put("benchmarkSelector", SCORECARD_SELECTOR)
      put("expectedRows", expectedRowsJson())
      put("command", buildJsonArray { finalCommand.forEach(::add) })
      put("profile", scorecardProfileJson())
      put("dependencyFingerprint", fingerprint)
      put("startedAt", startedAt.toString())
      put("completedAt", completedAt.toString())
      put("cpuAffinity", buildJsonObject { put("logicalCpuList", affinity.logicalCpuList) })
      put("javaIdentities", javaIdentitiesJson(preflight.javaIdentities))
      put("runtimeValidation", runtimeValidationJson(preflight.runtimeValidation))
      put("allowedDirtyPaths", allowedDirtyPathsJson(preflight))
      put("environment", ENVIRONMENT_PATH)
      put("raw", rawEvidenceJson(profilerFacts))
      put("scorecard", "scorecard.csv")
      put("report", "report.md")
      put("asciidoc", "performance-scorecard.adoc")
      put("profilerFacts", profilerFactsJson(profilerFacts))
      put("optimizationHypotheses", buildJsonArray {})
    }
    .toString()

private fun expectedRowsJson(): JsonArray = buildJsonArray {
  expectedScorecardRows.forEach { row ->
    add(
      buildJsonObject {
        put("benchmark", row.benchmark)
        put("journey", row.journey)
        put("workload", row.workload)
      }
    )
  }
}

private fun scorecardProfileJson(): JsonObject = buildJsonObject {
  put("mode", expectedScorecardProfile.mode)
  put("unit", expectedScorecardProfile.unit)
  put("threads", expectedScorecardProfile.threads)
  put("forks", expectedScorecardProfile.forks)
  put("warmups", expectedScorecardProfile.warmups)
  put("measurements", expectedScorecardProfile.measurements)
  put("iterationSeconds", expectedScorecardProfile.iterationSeconds)
  put("confidence", expectedScorecardProfile.confidence)
}

private fun javaIdentitiesJson(identities: Map<String, JavaIdentity>): JsonObject =
  buildJsonObject {
    identities.forEach { (name, identity) ->
      put(
        name,
        buildJsonObject {
          put("feature", identity.feature)
          put("identity", identity.identity)
        },
      )
    }
  }

private fun runtimeValidationJson(validation: RuntimeValidation): JsonObject = buildJsonObject {
  put("revision", validation.revision)
  put("timestamp", validation.timestamp)
  put("methods", buildJsonArray { validation.methods.forEach(::add) })
  put(
    "debugger",
    buildJsonObject {
      put("tool", validation.debuggerTool)
      put("session", validation.debuggerSession)
    },
  )
  put(
    "assertions",
    buildJsonObject { validation.assertions.forEach { (name, passed) -> put(name, passed) } },
  )
}

private fun allowedDirtyPathsJson(preflight: ScorecardPreflight): JsonArray = buildJsonArray {
  preflight.allowedDirtyPaths
    .map(preflight.projectRoot::relativize)
    .map(Path::toString)
    .sorted()
    .forEach(::add)
}

private fun rawEvidenceJson(profilerFacts: List<ProfilerFact>): JsonObject = buildJsonObject {
  put("results", RESULTS_PATH)
  put(
    "profiles",
    buildJsonArray {
      profilerFacts.forEach { fact ->
        add(fact.recording)
        add(fact.summary)
      }
    },
  )
}

private fun profilerFactsJson(profilerFacts: List<ProfilerFact>): JsonArray = buildJsonArray {
  profilerFacts.forEach { fact ->
    add(
      buildJsonObject {
        put("method", fact.method)
        put("event", fact.event)
        put("view", fact.view)
        put("recording", fact.recording)
        put("summary", fact.summary)
      }
    )
  }
}

internal fun dependencyFingerprint(projectRoot: Path, benchmarkJar: Path): String {
  val jar = benchmarkJar.toAbsolutePath().normalize()
  require(jar.startsWith(projectRoot)) { "Benchmark JAR must be inside the project root" }
  return dependencyFingerprint(
    mapOf(
      "gradle/libs.versions.toml" to projectRoot.resolve("gradle/libs.versions.toml"),
      "gradle/wrapper/gradle-wrapper.properties" to
        projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties"),
      projectRoot.relativize(jar).toString() to jar,
    )
  )
}

internal fun dependencyFingerprint(inputs: Map<String, Path>): String {
  require(inputs.isNotEmpty()) { "Dependency fingerprint requires inputs" }
  val digest = MessageDigest.getInstance("SHA-256")
  inputs.toSortedMap().forEach { (relativePath, path) ->
    require(relativePath.isNotBlank() && !Path.of(relativePath).isAbsolute) {
      "Dependency fingerprint paths must be relative"
    }
    val bytes = Files.readAllBytes(path)
    val pathBytes = relativePath.replace('\\', '/').toByteArray(Charsets.UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
    digest.update(pathBytes)
    digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
    digest.update(bytes)
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

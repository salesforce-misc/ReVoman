package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun captureScorecardEnvironment(
  host: ScorecardHost,
  projectRoot: Path,
  runId: String,
  startedAt: Instant,
  completedAt: Instant,
  affinity: CpuAffinity,
  javaIdentities: Map<String, JavaIdentity>,
  hygiene: ProcessHygiene = inspectProcessHygiene(host, projectRoot, affinity.logicalCpus.toSet()),
): String =
  environmentJson(
      runId,
      startedAt,
      completedAt,
      affinity,
      javaIdentities,
      hygiene,
      captureEnvironmentFacts(host, projectRoot, affinity),
    )
    .toString()

private data class EnvironmentFacts(
  val kernel: String,
  val cpuModel: String,
  val totalMemoryKiB: Long,
  val loadFields: List<String>,
  val processCounts: List<String>,
  val governors: Map<Int, String>,
  val gradleVersion: String,
)

private fun captureEnvironmentFacts(
  host: ScorecardHost,
  projectRoot: Path,
  affinity: CpuAffinity,
): EnvironmentFacts {
  val kernel = host.executeReadOnly(listOf("uname", "-srvm"), projectRoot)
  require(kernel.exitCode == 0 && kernel.stdout.isNotBlank()) { "Kernel probe failed" }
  val cpuModel =
    host
      .readText(Path.of("/proc/cpuinfo"))
      .lineSequence()
      .firstOrNull { it.substringBefore(':').trim() in setOf("model name", "Hardware") }
      ?.substringAfter(':')
      ?.trim()
      .orEmpty()
  require(cpuModel.isNotBlank()) { "CPU model is unavailable" }
  val totalMemory =
    host
      .readText(Path.of("/proc/meminfo"))
      .lineSequence()
      .firstOrNull { it.startsWith("MemTotal:") }
      ?.substringAfter(':')
      ?.trim()
      ?.substringBefore(' ')
      ?.toLongOrNull()
  requireNotNull(totalMemory) { "Total memory is unavailable" }
  val loadFields = host.readText(Path.of("/proc/loadavg")).trim().split(Regex("\\s+"))
  require(loadFields.size >= MINIMUM_LOAD_FIELDS) { "Load-average data is malformed" }
  val processCounts = loadFields[LOAD_PROCESS_COUNT_INDEX].split('/')
  require(processCounts.size == PROCESS_COUNT_FIELDS) {
    "Load-average process counts are malformed"
  }
  val governors =
    affinity.logicalCpus.associateWith { cpu ->
      val path = CPU_ROOT.resolve("cpu$cpu/cpufreq/scaling_governor")
      if (host.isRegularFile(path) && host.isReadable(path)) host.readText(path).trim()
      else "unavailable"
    }
  val gradleVersion =
    gradleVersion(host.readText(projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties")))
  return EnvironmentFacts(
    kernel.stdout.trim(),
    cpuModel,
    totalMemory,
    loadFields,
    processCounts,
    governors,
    gradleVersion,
  )
}

private fun environmentJson(
  runId: String,
  startedAt: Instant,
  completedAt: Instant,
  affinity: CpuAffinity,
  javaIdentities: Map<String, JavaIdentity>,
  hygiene: ProcessHygiene,
  facts: EnvironmentFacts,
): JsonObject = buildJsonObject {
  put("schemaVersion", 1)
  put("studyId", SCORECARD_STUDY_ID)
  put("runId", runId)
  put("startedAt", startedAt.toString())
  put("completedAt", completedAt.toString())
  put("kernel", facts.kernel)
  put("cpu", cpuJson(affinity, facts.cpuModel))
  put("cpuAffinity", buildJsonObject { put("logicalCpuList", affinity.logicalCpuList) })
  put("memory", buildJsonObject { put("totalKiB", facts.totalMemoryKiB) })
  put("load", loadJson(facts.loadFields, facts.processCounts))
  put("governor", governorsJson(facts.governors))
  put("javaIdentities", environmentJavaIdentitiesJson(javaIdentities))
  put("gradleVersion", facts.gradleVersion)
  put("processHygiene", processHygieneJson(hygiene))
}

private fun cpuJson(affinity: CpuAffinity, cpuModel: String): JsonObject = buildJsonObject {
  put("model", cpuModel)
  put("allowedLogicalCpus", affinity.allowedCpus.sorted().joinToString(","))
  put("onlineLogicalCpus", affinity.onlineCpus.sorted().joinToString(","))
  put(
    "physicalSiblingGroups",
    buildJsonArray {
      affinity.siblingGroups.forEach { group ->
        add(buildJsonArray { group.sorted().forEach(::add) })
      }
    },
  )
}

private fun loadJson(loadFields: List<String>, processCounts: List<String>): JsonObject =
  buildJsonObject {
    put("oneMinute", loadFields[0])
    put("fiveMinutes", loadFields[1])
    put("fifteenMinutes", loadFields[2])
    put("runnableProcesses", processCounts[0].toInt())
    put("totalProcesses", processCounts[1].toInt())
  }

private fun governorsJson(governors: Map<Int, String>): JsonArray = buildJsonArray {
  governors.forEach { (cpu, governor) ->
    add(
      buildJsonObject {
        put("logicalCpu", cpu)
        put("value", governor)
      }
    )
  }
}

private fun environmentJavaIdentitiesJson(identities: Map<String, JavaIdentity>): JsonObject =
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

private fun processHygieneJson(hygiene: ProcessHygiene): JsonObject = buildJsonObject {
  put("machineIdleClaim", hygiene.machineIdleClaim)
  put(
    "observations",
    buildJsonArray {
      hygiene.observations.forEach { observation ->
        add(
          buildJsonObject {
            put("pid", observation.pid)
            put("command", observation.command)
            put("category", observation.category)
            put("decision", observation.decision)
          }
        )
      }
    },
  )
}

private fun gradleVersion(wrapperProperties: String): String =
  Regex("gradle-([0-9][0-9A-Za-z.-]*)-(?:bin|all)\\.zip")
    .find(wrapperProperties)
    ?.groupValues
    ?.get(1) ?: error("Gradle wrapper version is unavailable")

private const val MINIMUM_LOAD_FIELDS = 4
private const val LOAD_PROCESS_COUNT_INDEX = 3
private const val PROCESS_COUNT_FIELDS = 2

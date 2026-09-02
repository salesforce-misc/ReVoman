package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

internal interface ScorecardHost {
  val clock: Clock
  val currentProcessId: Long
  val runnerJavaFeature: Int
  val runnerJavaIdentity: String
  val privateMachineIdentity: PrivateMachineIdentity

  fun environmentVariable(name: String): String?

  fun readText(path: Path): String

  fun list(directory: Path, glob: String): List<Path>

  fun isRegularFile(path: Path): Boolean

  fun isReadable(path: Path): Boolean

  fun isExecutable(path: Path): Boolean

  fun executeReadOnly(command: List<String>, workingDirectory: Path): ProcessResult
}

internal class SystemScorecardHost(override val clock: Clock = Clock.systemUTC()) : ScorecardHost {
  override val currentProcessId: Long = ProcessHandle.current().pid()
  override val runnerJavaFeature: Int = Runtime.version().feature()
  override val runnerJavaIdentity: String =
    listOf(
        System.getProperty("java.runtime.name"),
        System.getProperty("java.runtime.version"),
        System.getProperty("java.vm.name"),
        System.getProperty("java.vendor"),
      )
      .filterNot(String?::isNullOrBlank)
      .joinToString("; ")
  override val privateMachineIdentity: PrivateMachineIdentity by lazy {
    PrivateMachineIdentity(
      System.getProperty("user.name").orEmpty(),
      System.getProperty("user.home").orEmpty(),
      runCatching { Files.readString(Path.of("/proc/sys/kernel/hostname")).trim() }
        .getOrDefault(""),
    )
  }

  override fun environmentVariable(name: String): String? = System.getenv(name)

  override fun readText(path: Path): String = Files.readString(path)

  override fun list(directory: Path, glob: String): List<Path> =
    Files.newDirectoryStream(directory, glob).use { entries -> entries.toList() }

  override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

  override fun isReadable(path: Path): Boolean = Files.isReadable(path)

  override fun isExecutable(path: Path): Boolean = Files.isExecutable(path)

  override fun executeReadOnly(command: List<String>, workingDirectory: Path): ProcessResult =
    executeProcess(command, workingDirectory)
}

internal data class CpuAffinity(
  val logicalCpus: List<Int>,
  val logicalCpuList: String,
  val allowedCpus: Set<Int>,
  val onlineCpus: Set<Int>,
  val siblingGroups: List<Set<Int>>,
)

internal data class ProcessObservation(
  val pid: Long,
  val command: String,
  val category: String,
  val decision: String,
)

internal data class ProcessHygiene(
  val observations: List<ProcessObservation>,
  val machineIdleClaim: Boolean,
)

private data class ListedProcess(
  val pid: Long,
  val parentPid: Long,
  val command: String,
)

private data class ProcessClassification(
  val category: String,
  val decision: String,
)

internal val CPU_ROOT: Path = Path.of("/sys/devices/system/cpu")

internal fun parseCpuList(value: String): Set<Int> =
  value
    .trim()
    .split(',')
    .asSequence()
    .filter(String::isNotBlank)
    .flatMap { token ->
      val endpoints = token.trim().split('-')
      require(endpoints.size in 1..2) { "Malformed CPU list" }
      val first = endpoints.first().toIntOrNull()
      requireNotNull(first) { "Malformed CPU list" }
      val last = endpoints.last().toIntOrNull()
      requireNotNull(last) { "Malformed CPU list" }
      require(first >= 0 && last >= first) { "Malformed CPU list" }
      (first..last).asSequence()
    }
    .toCollection(sortedSetOf())

internal fun selectScorecardAffinity(host: ScorecardHost): CpuAffinity {
  val allowed =
    host
      .readText(Path.of("/proc/self/status"))
      .lineSequence()
      .firstOrNull { it.startsWith("Cpus_allowed_list:") }
      ?.substringAfter(':')
      ?.let(::parseCpuList) ?: error("Process status does not contain Cpus_allowed_list")
  val online = parseCpuList(host.readText(CPU_ROOT.resolve("online")))
  val usable = allowed intersect online
  require(usable.isNotEmpty()) { "No online CPU is allowed for the scorecard process" }
  val groupsByCpu =
    host
      .list(CPU_ROOT, "cpu[0-9]*")
      .asSequence()
      .mapNotNull { cpuDirectory ->
        cpuDirectory.fileName.toString().removePrefix("cpu").toIntOrNull()?.let { cpu ->
          cpu to cpuDirectory.resolve("topology/thread_siblings_list")
        }
      }
      .filter { (cpu, path) -> cpu in usable && host.isRegularFile(path) && host.isReadable(path) }
      .associate { (cpu, path) -> cpu to (parseCpuList(host.readText(path)) intersect usable) }
  val siblingGroups =
    usable
      .asSequence()
      .map { cpu -> groupsByCpu[cpu].orEmpty().ifEmpty { setOf(cpu) } }
      .distinct()
      .sortedBy { it.min() }
      .toList()
  val logicalCpus = siblingGroups.map { it.min() }
  return CpuAffinity(
    logicalCpus,
    logicalCpus.joinToString(","),
    allowed,
    online,
    siblingGroups,
  )
}

internal fun inspectProcessHygiene(
  host: ScorecardHost,
  projectRoot: Path,
  selectedCpus: Set<Int>,
): ProcessHygiene {
  val ps = host.executeReadOnly(listOf("ps", "-eo", "pid=,ppid=,comm="), projectRoot)
  require(ps.exitCode == 0) { "Process-hygiene ps probe failed" }
  val jps = host.executeReadOnly(listOf("jps", "-l"), projectRoot)
  require(jps.exitCode == 0) { "Process-hygiene jps probe failed" }
  val javaMainByPid =
    jps.stdout
      .lineSequence()
      .mapNotNull { line ->
        val trimmed = line.trim()
        trimmed.substringBefore(' ').toLongOrNull()?.let { it to trimmed.substringAfter(' ', "") }
      }
      .toMap()
  val processes =
    ps.stdout
      .lineSequence()
      .mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 3)
        if (fields.size == 3) {
          fields[0].toLongOrNull()?.let { pid ->
            fields[1].toLongOrNull()?.let { parentPid ->
              ListedProcess(pid, parentPid, fields[2])
            }
          }
        } else {
          null
        }
      }
      .associateBy(ListedProcess::pid)
  val orchestratorPids = ancestorPids(host.currentProcessId, processes)
  val observations =
    processes.values
      .asSequence()
      .mapNotNull { process ->
        classifyProcess(
          host,
          process.pid,
          process.command,
          javaMainByPid[process.pid],
          selectedCpus,
          orchestratorPids,
        )
      }
      .sortedBy(ProcessObservation::pid)
      .toList()
  val blocker = observations.firstOrNull { it.decision == "reject" }
  require(blocker == null) {
    "Active ${blocker?.category} process ${blocker?.pid} must be stopped before measurement"
  }
  return ProcessHygiene(observations, machineIdleClaim = observations.isEmpty())
}

private tailrec fun ancestorPids(
  processId: Long,
  processes: Map<Long, ListedProcess>,
  ancestors: Set<Long> = emptySet(),
): Set<Long> {
  val parent = processes[processId]?.parentPid
  return if (parent == null || parent <= 1 || parent in ancestors) {
    ancestors
  } else {
    ancestorPids(parent, processes, ancestors + parent)
  }
}

private fun classifyProcess(
  host: ScorecardHost,
  pid: Long,
  command: String,
  javaMain: String?,
  selectedCpus: Set<Int>,
  orchestratorPids: Set<Long>,
): ProcessObservation? {
  val classification =
    processClassification(host, pid, command, javaMain, selectedCpus, orchestratorPids)
      ?: return null
  return ProcessObservation(pid, command, classification.category, classification.decision)
}

private fun processClassification(
  host: ScorecardHost,
  pid: Long,
  command: String,
  javaMain: String?,
  selectedCpus: Set<Int>,
  orchestratorPids: Set<Long>,
): ProcessClassification? {
  val normalizedCommand = command.lowercase()
  val text = "$command ${javaMain.orEmpty()}".lowercase()
  val isGradle = containsAny(text, "gradledaemon", "gradle daemon")
  return rejectedProcessClassification(
    host,
    pid,
    command,
    selectedCpus,
    orchestratorPids,
    normalizedCommand,
    text,
    isGradle,
  ) ?: recordedProcessClassification(normalizedCommand, text)
}

private fun rejectedProcessClassification(
  host: ScorecardHost,
  pid: Long,
  command: String,
  selectedCpus: Set<Int>,
  orchestratorPids: Set<Long>,
  normalizedCommand: String,
  text: String,
  isGradle: Boolean,
): ProcessClassification? =
  when {
    containsAny(text, "idea", "intellij") -> ProcessClassification("IntelliJ", "reject")
    isGradle && pid in orchestratorPids ->
      ProcessClassification("Gradle daemon", "allow-orchestrator")
    isGradle -> ProcessClassification("Gradle daemon", "reject")
    containsAny(text, "kotlindaemon", "kotlincompiledaemon") ->
      ProcessClassification("Kotlin daemon", "reject")
    containsAny(text, "org.openjdk.jmh") || normalizedCommand == "jmh" ->
      ProcessClassification("JMH", "reject")
    isProfiler(command) && profilerOverlaps(host, pid, selectedCpus) ->
      ProcessClassification("profiler", "reject")
    else -> null
  }

private fun recordedProcessClassification(
  normalizedCommand: String,
  text: String,
): ProcessClassification? =
  when {
    containsAny(text, "codex") -> ProcessClassification("Codex", "record-exclude-from-idle")
    containsAny(text, "gnome-shell", "plasmashell") ->
      ProcessClassification("desktop shell", "record-exclude-from-idle")
    "nx" in normalizedCommand || "nomachine" in text ->
      ProcessClassification("remote desktop", "record-exclude-from-idle")
    normalizedCommand in setOf("gdb", "lldb", "jdb") -> ProcessClassification("debugger", "record")
    else -> null
  }

private fun containsAny(value: String, vararg candidates: String): Boolean =
  candidates.any(value::contains)

private fun isProfiler(command: String): Boolean =
  command.lowercase() in setOf("asprof", "async-profiler", "perf", "profiler")

private fun profilerOverlaps(host: ScorecardHost, pid: Long, selectedCpus: Set<Int>): Boolean {
  val status = Path.of("/proc/$pid/status")
  val affinity =
    if (host.isRegularFile(status) && host.isReadable(status)) {
      host
        .readText(status)
        .lineSequence()
        .firstOrNull { it.startsWith("Cpus_allowed_list:") }
        ?.substringAfter(':')
        ?.let(::parseCpuList)
    } else {
      null
    }
  return affinity?.any(selectedCpus::contains) ?: true
}

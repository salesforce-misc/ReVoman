package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ScorecardEnvironmentTest :
  StringSpec({
    "CPU-list parser supports disjoint singletons and ranges" {
      parseCpuList("0-3,8,10-11") shouldContainExactly setOf(0, 1, 2, 3, 8, 10, 11)
    }

    "affinity selects the lowest allowed online CPU from every physical sibling group" {
      val host = environmentHost()

      val affinity = selectScorecardAffinity(host)

      affinity.logicalCpus shouldContainExactly listOf(0, 2, 6)
      affinity.logicalCpuList shouldBe "0,2,6"
      affinity.allowedCpus shouldContainExactly setOf(0, 1, 2, 3, 6, 7)
      affinity.onlineCpus shouldContainExactly setOf(0, 1, 2, 6, 7)
      affinity.siblingGroups shouldContainExactly listOf(setOf(0, 1), setOf(2), setOf(6, 7))
    }

    "environment JSON contains only the evidence allowlist" {
      val root = Files.createTempDirectory("scorecard-environment-test-")
      try {
        val wrapper = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        Files.createDirectories(wrapper.parent)
        Files.writeString(
          wrapper,
          "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
        )
        val host = environmentHost(wrapper = wrapper)
        val affinity = selectScorecardAffinity(host)
        val jsonText =
          captureScorecardEnvironment(
            host = host,
            projectRoot = root,
            runId = "20260902T010203Z",
            startedAt = Instant.parse("2026-09-02T01:02:03Z"),
            completedAt = Instant.parse("2026-09-02T01:42:03Z"),
            affinity = affinity,
            javaIdentities = javaIdentities(),
          )
        val json = Json.parseToJsonElement(jsonText).jsonObject

        json.keys shouldContainExactly
          setOf(
            "schemaVersion",
            "studyId",
            "runId",
            "startedAt",
            "completedAt",
            "kernel",
            "cpu",
            "cpuAffinity",
            "memory",
            "load",
            "governor",
            "javaIdentities",
            "gradleVersion",
            "processHygiene",
          )
        json.getValue("kernel").jsonPrimitive.content shouldBe "Linux 6.17.0 x86_64 GNU/Linux"
        json.getValue("cpu").jsonObject.getValue("model").jsonPrimitive.content shouldBe
          "Test CPU 9000"
        json
          .getValue("cpuAffinity")
          .jsonObject
          .getValue("logicalCpuList")
          .jsonPrimitive
          .content shouldBe "0,2,6"
        json.getValue("memory").jsonObject.getValue("totalKiB").jsonPrimitive.content shouldBe
          "32768000"
        json.getValue("gradleVersion").jsonPrimitive.content shouldBe "9.7.1"
        json
          .getValue("processHygiene")
          .jsonObject
          .getValue("machineIdleClaim")
          .jsonPrimitive
          .content shouldBe "false"
        json.getValue("processHygiene").jsonObject.getValue("observations").jsonArray.map {
          it.jsonObject.getValue("command").jsonPrimitive.content
        } shouldContainExactly listOf("codex", "gnome-shell", "nxserver")

        listOf(
            "host-name.example",
            "alice",
            "/home/alice",
            "SECRET_TOKEN",
            "credential-value",
            "--password=credential-value",
          )
          .forEach(jsonText::shouldNotContain)
      } finally {
        root.toFile().deleteRecursively()
      }
    }

    "process hygiene rejects active IntelliJ Gradle Kotlin JMH and selected-CPU profilers" {
      listOf(
          environmentHost(ps = "101 1 idea\n", jps = "") to "IntelliJ",
          environmentHost(
            ps = "102 1 java\n",
            jps = "102 org.gradle.launcher.daemon.bootstrap.GradleDaemon\n",
          ) to "Gradle daemon",
          environmentHost(
            ps = "103 1 java\n",
            jps = "103 org.jetbrains.kotlin.daemon.KotlinCompileDaemon\n",
          ) to "Kotlin daemon",
          environmentHost(ps = "104 1 java\n", jps = "104 org.openjdk.jmh.Main\n") to "JMH",
          environmentHost(
            ps = "105 1 asprof\n",
            jps = "",
            extraFiles = mapOf(Path.of("/proc/105/status") to "Cpus_allowed_list:\t2-3\n"),
          ) to "profiler",
        )
        .forEach { (host, expected) ->
          shouldThrow<IllegalArgumentException> {
              inspectProcessHygiene(host, Path.of("/project"), setOf(0, 2, 6))
            }
            .message shouldContain expected
        }
    }

    "the Gradle daemon orchestrating this runner is recorded rather than rejected" {
      val host =
        environmentHost(
          ps = "999 102 java\n102 1 java\n",
          jps = "102 org.gradle.launcher.daemon.bootstrap.GradleDaemon\n",
        )

      val hygiene = inspectProcessHygiene(host, Path.of("/project"), setOf(0, 2, 6))

      hygiene.observations.single().category shouldBe "Gradle daemon"
      hygiene.observations.single().decision shouldBe "allow-orchestrator"
      hygiene.machineIdleClaim shouldBe false
    }
  })

private fun javaIdentities(): Map<String, JavaIdentity> =
  linkedMapOf(
    "runner" to JavaIdentity(25, "OpenJDK 25 runner"),
    "launcher" to JavaIdentity(25, "OpenJDK 25 launcher"),
    "inherited" to JavaIdentity(25, "OpenJDK 25 inherited"),
    "gradleDaemon" to JavaIdentity(25, "OpenJDK 25 daemon"),
    "jmh" to JavaIdentity(25, "OpenJDK 25 JMH"),
  )

private fun environmentHost(
  ps: String = "11 1 codex\n12 1 gnome-shell\n13 1 nxserver\n",
  jps: String = "",
  wrapper: Path = Path.of("/project/gradle/wrapper/gradle-wrapper.properties"),
  extraFiles: Map<Path, String> = emptyMap(),
): EnvironmentHost {
  val cpuFiles =
    (0..7).associate { cpu ->
      val siblings =
        when (cpu) {
          0,
          1 -> "0-1\n"
          2,
          3 -> "2-3\n"
          4,
          5 -> "4-5\n"
          else -> "6-7\n"
        }
      Path.of("/sys/devices/system/cpu/cpu$cpu/topology/thread_siblings_list") to siblings
    }
  val governors =
    listOf(0, 2, 6).associate { cpu ->
      Path.of("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor") to "performance\n"
    }
  return EnvironmentHost(
    files =
      cpuFiles +
        governors +
        mapOf(
          Path.of("/proc/self/status") to "Name:\tjava\nCpus_allowed_list:\t0-3,6-7\n",
          Path.of("/sys/devices/system/cpu/online") to "0-2,6-7\n",
          Path.of("/proc/cpuinfo") to "processor: 0\nmodel name: Test CPU 9000\n",
          Path.of("/proc/meminfo") to "MemTotal:       32768000 kB\nMemFree: 1 kB\n",
          Path.of("/proc/loadavg") to "0.10 0.20 0.30 2/100 1234\n",
          wrapper to
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
        ) +
        extraFiles,
    ps = ps,
    jps = jps,
  )
}

private data class EnvironmentHost(
  val files: Map<Path, String>,
  val ps: String,
  val jps: String,
) : ScorecardHost {
  override val clock: Clock = Clock.fixed(Instant.parse("2026-09-02T01:02:03Z"), ZoneOffset.UTC)
  override val currentProcessId: Long = 999
  override val runnerJavaFeature: Int = 25
  override val runnerJavaIdentity: String = "OpenJDK 25 runner"

  override fun environmentVariable(name: String): String? =
    error("Environment capture must not read arbitrary variable $name")

  override fun readText(path: Path): String = files.getValue(path)

  override fun list(directory: Path, glob: String): List<Path> =
    if (directory == Path.of("/sys/devices/system/cpu") && glob == "cpu[0-9]*") {
      files.keys
        .filter { it.fileName.toString() == "thread_siblings_list" }
        .map { it.parent.parent }
    } else {
      emptyList()
    }

  override fun isRegularFile(path: Path): Boolean = path in files

  override fun isReadable(path: Path): Boolean = path in files

  override fun isExecutable(path: Path): Boolean = false

  override fun executeReadOnly(command: List<String>, workingDirectory: Path): ProcessResult =
    when (command) {
      listOf("uname", "-srvm") -> ProcessResult(0, "Linux 6.17.0 x86_64 GNU/Linux\n", "")
      listOf("ps", "-eo", "pid=,comm="),
      listOf("ps", "-eo", "pid=,ppid=,comm=") -> ProcessResult(0, ps, "")
      listOf("jps", "-l") -> ProcessResult(0, jps, "")
      else -> error("Unexpected command: $command")
    }
}

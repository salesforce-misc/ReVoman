/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import performance.json.CanonicalJson
import performance.support.FakeHost

class MacQualificationContractTest :
  FunSpec(
    {
      test("the checked-in controlled Mac policy encodes the exact current pmset thermal text") {
        FakeHost().use { host ->
          host.readCheckedInStringArray(
              "config/performance/policies/m4max-docker-linux-arm64-v1.json",
              "thermalTextStates",
            )
            .shouldContainExactly(livePmsetThermalTextStates)
        }
      }

      test("the qualification schema requires the exact current pmset thermal text") {
        FakeHost().use { host ->
          host.readCheckedInStringArray(
              "config/performance/policies/qualification-policy-v1.schema.json",
              "properties",
              "thermalTextStates",
              "const",
            )
            .shouldContainExactly(livePmsetThermalTextStates)
        }
      }

      test("the exact current pmset thermal text is accepted") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "live-pmset-thermal-text").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PMSET_THERMAL_STATE" to livePmsetThermalTextStates.joinToString("\n"),
                ),
            )

          withClue("stderr=${result.standardError}; commands=${result.commands}") {
            result.exitCode shouldBe 0
          }
        }
      }

      rejectedLivePmsetThermalTextFixtures.forEach { fixture ->
        test("exact current pmset thermal text rejects ${fixture.description}") {
          FakeHost().use { host ->
            host.writeCurrentControlledMacPolicy(livePmsetThermalTextStates)
            val result =
              host.invoke(
                *captureCommand(host, "live-pmset-${fixture.token}").toTypedArray(),
                environment = mapOf("FAKE_PMSET_THERMAL_STATE" to fixture.output),
              )

            withClue("stderr=${result.standardError}; commands=${result.commands}") {
              result.exitCode shouldBe 2
              result.standardError shouldContain "QUALIFICATION_FAILED"
              result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
            }
          }
        }
      }

      test("the checked-in controlled Mac policy accepts the current unprivileged host facts") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "qualified-mac").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_DOCKER_CLIENT_VERSION" to "0.0.0-client-must-be-ignored",
                  "FAKE_DOCKER_SERVER_PLATFORM_NAME" to "Docker Desktop 4.87.0 (236836)",
                  "FAKE_DOCKER_ENGINE_VERSION" to "29.7.2",
                  "FAKE_DOCKER_OPERATING_SYSTEM" to "Docker Desktop",
                  "FAKE_DOCKER_KERNEL" to "7.0.12-linuxkit",
                  "FAKE_DOCKER_CPU_COUNT" to "16",
                  "FAKE_DOCKER_MEMORY_BYTES" to "8318709760",
                  "FAKE_DOCKER_ARCHITECTURE" to "aarch64",
                ),
            )
          val commandNames = result.commands.mapNotNull(List<String>::firstOrNull)

          withClue("stderr=${result.standardError}; commands=${result.commands}") {
            result.exitCode shouldBe 0
          }
          val dockerVersion =
            result.commands.single { command ->
              command.firstOrNull() == "docker" && "version" in command
            }
          commandNames shouldContainAll
            listOf("pmset", "tmutil", "memory_pressure", "vm_stat", "ioreg", "ps", "sysctl")
          dockerVersion.any { "Server.Platform.Name" in it } shouldBe true
          dockerVersion.none { "Client.Version" in it } shouldBe true
          result.commands.any { command ->
            command.firstOrNull() == "sysctl" &&
              command.takeLast(2) == listOf("-n", "kern.memorystatus_vm_pressure_level")
          } shouldBe true
          result.commands
            .filter { command ->
              command.firstOrNull() == "docker" &&
                ("run" in command || ("volume" in command && "create" in command))
            }
            .forEach { command ->
              command.any { it == "dev.revoman.performance.operation=qualified-mac" } shouldBe true
              command.any { it == "dev.revoman.performance.profile=m4max-docker-linux-arm64-v1" } shouldBe
                true
            }
          result.commands.flatten().none { it in listOf("sudo", "dzdo", "osascript") } shouldBe true
        }
      }

      test("a pre-existing idle exact live software update path is accepted") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "idle-software-update-daemon").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PROCESS_LIST" to liveSoftwareUpdateCommand,
                  "FAKE_PROCESS_DETAIL_ROW" to
                    liveSoftwareUpdateRow(cpuPercent = "0.0", memoryPercent = "0.0"),
                ),
            )

          withClue("stderr=${result.standardError}; commands=${result.commands}") {
            result.exitCode shouldBe 0
          }
        }
      }

      test("a pre-existing active exact live software update path fails in preflight before timing") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "active-software-update-during-preflight").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PROCESS_LIST" to liveSoftwareUpdateCommand,
                  "FAKE_PROCESS_DETAIL_ROW" to
                    liveSoftwareUpdateRow(cpuPercent = "100", memoryPercent = "0.0"),
                  "FAKE_PROCESS_DETAIL_ROW_WHILE_TIMED" to
                    liveSoftwareUpdateRow(cpuPercent = "0.0", memoryPercent = "0.0"),
                ),
            )

          withClue("stderr=${result.standardError}; commands=${result.commands}") {
            result.exitCode shouldBe 2
            result.standardError shouldContain "QUALIFICATION_FAILED"
            result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
            result.commands.count { command ->
              command.firstOrNull() == "ps" && "pid=" in command && "%cpu=" in command
            } shouldBe 4
          }
        }
      }

      test("ordinary processes remain allowed while existing VM and backup work remain rejected") {
        val fixtures =
          listOf(
            Triple("ordinary-process", mapOf("FAKE_PROCESS_LIST" to "/usr/bin/Finder"), 0),
            Triple("existing-vm", mapOf("FAKE_PROCESS_LIST" to "/opt/homebrew/bin/colima"), 2),
            Triple("active-backup", mapOf("FAKE_BACKUP_RUNNING" to "1"), 2),
          )

        fixtures.forEach { (token, environment, expectedExit) ->
          FakeHost().use { host ->
            val result =
              host.invoke(
                *captureCommand(host, token).toTypedArray(),
                environment = environment,
              )

            withClue("token=$token; stderr=${result.standardError}; commands=${result.commands}") {
              result.exitCode shouldBe expectedExit
            }
          }
        }
      }

      ordinaryProcessResourceFixtures.forEach { fixture ->
        test("ordinary process resource monitoring ${fixture.description}") {
          FakeHost().use { host ->
            val result =
              host.invoke(
                *captureCommand(host, "ordinary-resource-${fixture.token}").toTypedArray(),
                environment =
                  mapOf(
                    "FAKE_SEQUENCED_PROCESS_ROWS" to
                      (listOf(ordinaryProcessRow(memoryPercent = "1")) + fixture.samples)
                        .joinToString("\n"),
                  ),
                functionOverrides = sequencedProcessRowsOverrides,
              )

            withClue("stderr=${result.standardError}; commands=${result.commands}") {
              result.exitCode shouldBe fixture.expectedExit
              result.commands.any { "dev.revoman.performance.phase=timed" in it } shouldBe
                (fixture.expectedExit == 0)
              if (fixture.expectedExit != 0) {
                result.standardError shouldContain "QUALIFICATION_FAILED"
              }
            }
          }
        }
      }

      test("Docker Desktop identity comes from the daemon platform rather than the client") {
        FakeHost().use { host ->
          host.writeCurrentControlledMacPolicy()
          val result =
            host.invoke(
              *captureCommand(host, "server-platform-identity").toTypedArray(),
              environment = mapOf("FAKE_DOCKER_CLIENT_VERSION" to "4.45.0"),
            )

          result.exitCode shouldBe 0
        }
      }

      malformedDockerDaemonIdentityFixtures.forEach { fixture ->
        test("Docker daemon identity rejects ${fixture.description}") {
          FakeHost().use { host ->
            host.writeCurrentControlledMacPolicy()
            val result =
              host.invoke(
                *captureCommand(host, "malformed-daemon-${fixture.token}").toTypedArray(),
                environment = mapOf("FAKE_DOCKER_SERVER_IDENTITY_OUTPUT" to fixture.output),
              )

            result.exitCode shouldBe 2
            result.standardError shouldContain "QUALIFICATION_FAILED"
            result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
          }
        }
      }

      rejectedCurrentFactFixtures.forEach { fixture ->
        test("controlled Mac qualification rejects ${fixture.description}") {
          FakeHost().use { host ->
            host.writeCurrentControlledMacPolicy()
            val result =
              host.invoke(
                *captureCommand(host, "rejected-${fixture.token}").toTypedArray(),
                environment = fixture.environment,
              )

            result.exitCode shouldBe 2
            result.standardError shouldContain "QUALIFICATION_FAILED"
            result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
          }
        }
      }

      test("every text thermal state configured by policy is accepted when present") {
        FakeHost().use { host ->
          val configuredStates =
            listOf(
              "Fixture thermal state is nominal",
              "Fixture performance state is nominal",
              "Fixture CPU power state is nominal",
            )
          host.writeCurrentControlledMacPolicy(configuredStates)
          val result =
            host.invoke(
              *captureCommand(host, "policy-thermal-exact").toTypedArray(),
              environment = mapOf("FAKE_PMSET_THERMAL_STATE" to configuredStates.joinToString("\n")),
            )

          result.exitCode shouldBe 0
        }
      }

      test("a missing text thermal state configured by policy fails closed") {
        FakeHost().use { host ->
          val configuredStates =
            listOf(
              "Fixture thermal state is nominal",
              "Fixture performance state is nominal",
              "Fixture CPU power state is nominal",
            )
          host.writeCurrentControlledMacPolicy(configuredStates)
          val result =
            host.invoke(
              *captureCommand(host, "policy-thermal-missing").toTypedArray(),
              environment =
                mapOf("FAKE_PMSET_THERMAL_STATE" to configuredStates.dropLast(1).joinToString("\n")),
            )

          result.exitCode shouldBe 2
        }
      }

      test("the exact numeric thermal fallback remains nominal") {
        FakeHost().use { host ->
          host.writeCurrentControlledMacPolicy()
          val result =
            host.invoke(
              *captureCommand(host, "numeric-thermal").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PMSET_THERMAL_STATE" to
                    "CPU_Speed_Limit=100\nScheduler_Limit=100\nSpeed_Limited_Processes=0",
                ),
            )

          result.exitCode shouldBe 0
        }
      }

      test("host id is opaque and cannot select the GitHub substrate on a Mac") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(
                  host,
                  "opaque-host-id",
                  hostId = "github-hosted-arm64-v1",
                )
                .toTypedArray(),
            )

          result.exitCode shouldBe 0
          result.commands.filter { it.firstOrNull() == "docker" }.forEach { invocation ->
            invocation.take(3) shouldBe listOf("docker", "--context", "desktop-linux")
          }
        }
      }

      test("a failed Mac qualification stops before timing and finalizes only INVALID evidence") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "thermal-failure").toTypedArray(),
              environment = mapOf("FAKE_PMSET_THERMAL_STATE" to "CPU_Speed_Limit=80"),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
          Files.exists(host.outputPath("thermal-failure")) shouldBe false
          Files.exists(host.artifactRoot.resolve("INVALID-thermal-failure/INVALID/reason")) shouldBe true
        }
      }

      test("unsafe inherited JVM state fails before Docker and is never forwarded") {
        FakeHost().use { host ->
          val marker = "task10-jvm-marker"
          val result =
            host.invoke(
              *captureCommand(host, "unsafe-environment").toTypedArray(),
              environment = mapOf("JAVA_TOOL_OPTIONS" to marker),
            )

          result.exitCode shouldBe 2
          result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
          result.commands.flatten().none { marker in it } shouldBe true
        }
      }

      test("GitHub qualification uses declared hosted fields and never invokes Mac probes") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "github-hosted").toTypedArray(),
              environment = mapOf("FAKE_SYSTEM_NAME" to "Linux"),
            )

          result.exitCode shouldBe 0
          result.commands
            .filter { command ->
              command.firstOrNull() in
                setOf("caffeinate", "ioreg", "memory_pressure", "pmset", "sysctl", "tmutil", "vm_stat")
            }
            .shouldBeEmpty()
          result.commands.filter { it.firstOrNull() == "docker" }.forEach { invocation ->
            ("--context" in invocation) shouldBe false
          }
        }
      }

      test("GitHub qualification accepts usable memory above its advertised minimum") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "github-hosted-reserved-memory").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_SYSTEM_NAME" to "Linux",
                  "FAKE_DOCKER_MEMORY_BYTES" to "16722042880",
                ),
            )

          withClue("stderr=${result.standardError}; commands=${result.commands}") {
            result.exitCode shouldBe 0
          }
        }
      }

      test("GitHub qualification rejects usable memory below its advertised minimum") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "github-hosted-insufficient-memory").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_SYSTEM_NAME" to "Linux",
                  "FAKE_DOCKER_MEMORY_BYTES" to "15999999999",
                ),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
        }
      }

      test("GitHub qualification rejects malformed usable memory without a shell diagnostic") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "github-hosted-malformed-memory").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_SYSTEM_NAME" to "Linux",
                  "FAKE_DOCKER_MEMORY_BYTES" to "999999999999999999999999999999999999999999",
                ),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          result.standardError shouldNotContain "integer expression expected"
          result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
        }
      }

      test("host qualification commands cannot be substituted through PATH") {
        FakeHost().use { host ->
          val hostileBin = host.repositoryRoot.resolve("hostile-bin").also { it.createDirectories() }
          hostileBin.resolve("uname").also { command ->
            command.writeText("#!/bin/sh\nprintf '%s\\n' Spoofed\n")
            command.toFile().setExecutable(true, true)
          }
          val result =
            host.invokeFunction(
              "adapter_system_name",
              environment = mapOf("PATH" to hostileBin.toString()),
              useFakeHostTools = false,
            )

          result.exitCode shouldBe 0
          result.standardOutput.trim() shouldNotBe "Spoofed"
        }
      }

      test("policy bounds preparation direct campaign and finalization Docker phases") {
        FakeHost().use { host ->
          val timeoutLog = host.repositoryRoot.resolve("timeout-phases")
          val overrides =
            """
            adapter_run_bounded_command() {
              timeout_millis="${'$'}1"
              shift
              printf '%s|%s\n' "${'$'}timeout_millis" "${'$'}*" >>"${'$'}ADAPTER_REPO_ROOT/timeout-phases"
              "${'$'}@"
            }
            """.trimIndent()
          val capture =
            host.invoke(
              *captureCommand(host, "bounded-capture").toTypedArray(),
              functionOverrides = overrides,
            )
          val baseline = host.frozenDistribution("bounded-campaign-baseline")
          val candidate = host.frozenDistribution("bounded-campaign-candidate")
          val campaign =
            host.invoke(
              "campaign",
              "--profile",
              "warm",
              "--host-id",
              "opaque-host",
              "--baseline-distribution",
              baseline.toString(),
              "--candidate-distribution",
              candidate.toString(),
              "--output",
              host.output("bounded-campaign"),
              functionOverrides = overrides,
            )
          val phases = timeoutLog.takeIf(Files::exists)?.let { path -> Files.readAllLines(path) }.orEmpty()

          capture.exitCode shouldBe 0
          campaign.exitCode shouldBe 0
          phases.any { line ->
            line.startsWith("900000|") && "dev.revoman.performance.phase=preparation" in line
          } shouldBe true
          phases.any { line ->
            line.startsWith("600000|") && "dev.revoman.performance.phase=timed" in line
          } shouldBe true
          phases.any { line ->
            line.startsWith("7200000|") && "dev.revoman.performance.phase=timed" in line
          } shouldBe true
          phases.any { line ->
            line.startsWith("900000|") && "dev.revoman.performance.phase=finalizer" in line
          } shouldBe true
        }
      }
    },
  )

private fun captureCommand(
  host: FakeHost,
  token: String,
  hostId: String = "opaque-host",
): List<String> =
  listOf(
    "capture",
    "--profile",
    "warm",
    "--forks",
    "10",
    "--host-id",
    hostId,
    "--session-id",
    "session-1",
    "--sequence",
    "1",
    "--distribution",
    host.frozenDistribution("$token-distribution").toString(),
    "--output",
    host.output(token),
  )

private const val liveSoftwareUpdateCommand =
  "/System/Library/CoreServices/Software Update.app/Contents/Resources/softwareupdated"

private fun liveSoftwareUpdateRow(cpuPercent: String, memoryPercent: String): String =
  "696 Mon Aug 18 00:00:00 2026 $cpuPercent $memoryPercent $liveSoftwareUpdateCommand"

private val sequencedProcessRowsOverrides =
  """
  adapter_process_status() {
    case " ${'$'}* " in
      *" -A -o pid= -o lstart= -o %cpu= -o %mem= -o comm= "*)
        sequence_index_file="${'$'}ADAPTER_REPO_ROOT/.fake-process-sequence-index"
        sequence_index=0
        if [ -f "${'$'}sequence_index_file" ]; then
          IFS= read -r sequence_index <"${'$'}sequence_index_file" || return 1
        fi
        sequence_index=${'$'}((sequence_index + 1))
        printf '%s\n' "${'$'}sequence_index" >"${'$'}sequence_index_file" || return 1
        process_row=$(/usr/bin/sed -n "${'$'}{sequence_index}p" <<<"${'$'}FAKE_SEQUENCED_PROCESS_ROWS")
        if [ -z "${'$'}process_row" ]; then
          process_row='77 Mon Aug 18 00:00:00 2026 1 1 /usr/bin/ordinary-task'
        fi
        printf '%s\n' "${'$'}process_row"
        ;;
      *) command ps "${'$'}@" ;;
    esac
  }
  """.trimIndent()

private fun ordinaryProcessRow(memoryPercent: String): String =
  "77 Mon Aug 18 00:00:00 2026 1 $memoryPercent /usr/bin/ordinary-task"

private data class OrdinaryProcessResourceFixture(
  val token: String,
  val description: String,
  val samples: List<String>,
  val expectedExit: Int,
)

private val ordinaryProcessResourceFixtures =
  listOf(
    OrdinaryProcessResourceFixture(
      token = "isolated-excursions",
      description = "allows isolated one-sample excursions and resets each breach",
      samples =
        listOf(
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "1"),
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "1"),
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "1"),
        ),
      expectedExit = 0,
    ),
    OrdinaryProcessResourceFixture(
      token = "two-sample-excursion",
      description = "allows a two-sample excursion and resets before the next breach",
      samples =
        listOf(
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "1"),
          ordinaryProcessRow(memoryPercent = "30"),
        ),
      expectedExit = 0,
    ),
    OrdinaryProcessResourceFixture(
      token = "sustained-excursion",
      description = "rejects a sustained three-sample excursion",
      samples =
        listOf(
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "30"),
          ordinaryProcessRow(memoryPercent = "30"),
        ),
      expectedExit = 2,
    ),
  )

private data class RejectedCurrentFactFixture(
  val token: String,
  val description: String,
  val environment: Map<String, String>,
)

private data class MalformedDockerDaemonIdentityFixture(
  val token: String,
  val description: String,
  val output: String,
)

private data class RejectedLivePmsetThermalTextFixture(
  val token: String,
  val description: String,
  val output: String,
)

private const val currentDockerDaemonIdentity = "Docker Desktop 4.87.0 (236836)|29.7.2"

private val malformedDockerDaemonIdentityFixtures =
  listOf(
    MalformedDockerDaemonIdentityFixture(
      "trailing-delimiter",
      "a trailing delimiter",
      "$currentDockerDaemonIdentity|\n",
    ),
    MalformedDockerDaemonIdentityFixture(
      "extra-field",
      "an extra field",
      "$currentDockerDaemonIdentity|unexpected\n",
    ),
    MalformedDockerDaemonIdentityFixture(
      "carriage-return",
      "an embedded carriage return",
      "$currentDockerDaemonIdentity\r\n",
    ),
    MalformedDockerDaemonIdentityFixture(
      "line-feed",
      "an embedded line feed",
      "$currentDockerDaemonIdentity\nunexpected\n",
    ),
    MalformedDockerDaemonIdentityFixture(
      "missing-delimiter",
      "a missing delimiter",
      "Docker Desktop 4.87.0 (236836) 29.7.2\n",
    ),
    MalformedDockerDaemonIdentityFixture("empty-platform", "an empty platform", "|29.7.2\n"),
    MalformedDockerDaemonIdentityFixture(
      "empty-version",
      "an empty server version",
      "Docker Desktop 4.87.0 (236836)|\n",
    ),
  )

private val livePmsetThermalTextStates =
  listOf(
    "Note: No thermal warning level has been recorded",
    "Note: No performance warning level has been recorded",
    "Note: No CPU power status has been recorded",
  )

private val rejectedLivePmsetThermalTextFixtures =
  listOf(
    RejectedLivePmsetThermalTextFixture(
      token = "missing-state",
      description = "a missing state",
      output = livePmsetThermalTextStates.dropLast(1).joinToString("\n"),
    ),
    RejectedLivePmsetThermalTextFixture(
      token = "extra-state",
      description = "an extra state",
      output = (livePmsetThermalTextStates + "Note: Unexpected thermal state").joinToString("\n"),
    ),
    RejectedLivePmsetThermalTextFixture(
      token = "malformed-prefix",
      description = "a malformed prefix",
      output =
        listOf(
            livePmsetThermalTextStates[0].removePrefix("Note: "),
            livePmsetThermalTextStates[1],
            livePmsetThermalTextStates[2],
          )
          .joinToString("\n"),
    ),
  )

private val rejectedCurrentFactFixtures =
  listOf(
    RejectedCurrentFactFixture("stale-macos", "a stale macOS version", mapOf("FAKE_MACOS_VERSION" to "26.6.1")),
    RejectedCurrentFactFixture("wrong-build", "a wrong macOS build", mapOf("FAKE_MACOS_BUILD" to "25G90")),
    RejectedCurrentFactFixture("malformed-macos", "a malformed macOS version", mapOf("FAKE_MACOS_VERSION" to "26.6.2-beta")),
    RejectedCurrentFactFixture(
      "stale-desktop",
      "a stale Docker Desktop daemon platform",
      mapOf("FAKE_DOCKER_SERVER_PLATFORM_NAME" to "Docker Desktop 4.45.0 (190000)"),
    ),
    RejectedCurrentFactFixture(
      "wrong-desktop",
      "a wrong Docker daemon platform",
      mapOf("FAKE_DOCKER_SERVER_PLATFORM_NAME" to "Docker Engine 4.87.0 (236836)"),
    ),
    RejectedCurrentFactFixture(
      "malformed-desktop",
      "a malformed Docker Desktop daemon platform",
      mapOf("FAKE_DOCKER_SERVER_PLATFORM_NAME" to "Docker Desktop 4.87.0"),
    ),
    RejectedCurrentFactFixture("stale-engine", "a stale Docker engine", mapOf("FAKE_DOCKER_ENGINE_VERSION" to "28.3.3")),
    RejectedCurrentFactFixture("wrong-kernel", "a wrong LinuxKit kernel", mapOf("FAKE_DOCKER_KERNEL" to "6.11.0-linuxkit")),
    RejectedCurrentFactFixture("wrong-cpu-count", "a wrong VM CPU count", mapOf("FAKE_DOCKER_CPU_COUNT" to "15")),
    RejectedCurrentFactFixture("malformed-cpu-count", "a malformed VM CPU count", mapOf("FAKE_DOCKER_CPU_COUNT" to "sixteen")),
    RejectedCurrentFactFixture("stale-memory", "a stale VM MemTotal", mapOf("FAKE_DOCKER_MEMORY_BYTES" to "8589934592")),
    RejectedCurrentFactFixture("malformed-memory", "a malformed VM MemTotal", mapOf("FAKE_DOCKER_MEMORY_BYTES" to "eight-gib")),
    RejectedCurrentFactFixture("wrong-architecture", "a wrong VM architecture", mapOf("FAKE_DOCKER_ARCHITECTURE" to "x86_64")),
    RejectedCurrentFactFixture("memory-warning", "memory pressure warning state", mapOf("FAKE_MEMORY_PRESSURE_LEVEL" to "2")),
    RejectedCurrentFactFixture("memory-critical", "memory pressure critical state", mapOf("FAKE_MEMORY_PRESSURE_LEVEL" to "4")),
    RejectedCurrentFactFixture("memory-unknown", "an unknown memory pressure state", mapOf("FAKE_MEMORY_PRESSURE_LEVEL" to "3")),
    RejectedCurrentFactFixture("memory-malformed", "a malformed memory pressure state", mapOf("FAKE_MEMORY_PRESSURE_LEVEL" to "normal")),
    RejectedCurrentFactFixture(
      "numeric-scheduler",
      "a degraded numeric scheduler limit",
      mapOf(
        "FAKE_PMSET_THERMAL_STATE" to
          "CPU_Speed_Limit=100\nScheduler_Limit=99\nSpeed_Limited_Processes=0",
      ),
    ),
    RejectedCurrentFactFixture(
      "numeric-limited-process",
      "a numeric speed-limited process",
      mapOf(
        "FAKE_PMSET_THERMAL_STATE" to
          "CPU_Speed_Limit=100\nScheduler_Limit=100\nSpeed_Limited_Processes=1",
      ),
    ),
    RejectedCurrentFactFixture(
      "numeric-prefix",
      "a malformed numeric thermal prefix",
      mapOf(
        "FAKE_PMSET_THERMAL_STATE" to
          "CPU_Speed_Limit=1000\nScheduler_Limit=100\nSpeed_Limited_Processes=0",
      ),
    ),
  )

private fun FakeHost.writeCurrentControlledMacPolicy(
  thermalTextStates: List<String> = livePmsetThermalTextStates,
) {
  val policy = repositoryRoot.resolve("config/performance/policies/m4max-docker-linux-arm64-v1.json")
  var contents = policy.readText()
  mapOf(
      "macosVersion" to "26.6.2",
      "macosBuild" to "25G83",
      "dockerDesktopVersion" to "4.87.0",
      "dockerEngineVersion" to "29.7.2",
      "linuxKitKernel" to "7.0.12-linuxkit",
    )
    .forEach { (key, value) ->
      val pattern = Regex("""(?m)^(\s*"$key":\s*)"[^"]*"(,?)$""")
      check(pattern.findAll(contents).count() == 1) { "missing unique string policy field $key" }
      contents = pattern.replace(contents, "${'$'}1\"$value\"${'$'}2")
    }
  mapOf("vmCpuCount" to "16", "vmMemoryBytes" to "8318709760").forEach { (key, value) ->
    val pattern = Regex("""(?m)^(\s*"$key":\s*)[^,]+(,?)$""")
    check(pattern.findAll(contents).count() == 1) { "missing unique numeric policy field $key" }
    contents = pattern.replace(contents, "${'$'}1$value${'$'}2")
  }
  val arrayStart = contents.indexOf("  \"thermalTextStates\": [")
  val arrayEnd = contents.indexOf("\n  ],", startIndex = arrayStart)
  check(arrayStart >= 0 && arrayEnd >= 0) { "missing thermalTextStates policy array" }
  val renderedStates =
    "  \"thermalTextStates\": [\n" +
      thermalTextStates.joinToString(",\n") { state -> "    \"$state\"" } +
      "\n  ],"
  contents = contents.replaceRange(arrayStart, arrayEnd + "\n  ],".length, renderedStates)
  policy.writeText(contents)
}

private fun FakeHost.readCheckedInStringArray(
  relativePath: String,
  vararg fields: String,
): List<String> {
  var node = CanonicalJson.parseStrict(Files.readAllBytes(repositoryRoot.resolve(relativePath)))
  fields.forEach { field -> node = requireNotNull(node.get(field)) { "missing JSON field $field" } }
  return node.asArray().iterator().asSequence().map { element -> element.asString() }.toList()
}

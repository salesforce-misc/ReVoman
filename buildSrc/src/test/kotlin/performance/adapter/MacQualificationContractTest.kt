/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import performance.support.FakeHost

class MacQualificationContractTest :
  FunSpec(
    {
      test("the controlled Mac policy drives unprivileged preflight and labels every Docker object") {
        FakeHost().use { host ->
          val result = host.invoke(*captureCommand(host, "qualified-mac").toTypedArray())
          val commandNames = result.commands.mapNotNull(List<String>::firstOrNull)

          result.exitCode shouldBe 0
          commandNames shouldContainAll
            listOf("pmset", "tmutil", "memory_pressure", "vm_stat", "ioreg", "ps", "sysctl")
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

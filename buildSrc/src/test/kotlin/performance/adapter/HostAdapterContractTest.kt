/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import performance.support.FakeHost

class HostAdapterContractTest :
  FunSpec(
    {
      test("the packaged adapter is executable and invokes main only when executed") {
        FakeHost().use { host ->
          val packaged = host.sourceRoot.resolve("scripts/performance/run")

          Files.isExecutable(packaged) shouldBe true
          Files.getPosixFilePermissions(packaged) shouldContainAll
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
          host.invokePackaged("unknown").exitCode shouldBe 2
        }
      }

      test("the five public commands are recognized and remain unavailable at this checkpoint") {
        FakeHost().use { host ->
          publicCommands(host).forEach { command ->
            val result = host.invoke(*command.arguments.toTypedArray())

            result.exitCode shouldBe 2
            result.standardError shouldContain "COMMAND_NOT_AVAILABLE"
            result.commands.filter { it.firstOrNull() == "docker" }.shouldNotBeEmpty()
            Files.readString(host.outputPath(command.name).resolve("adapter-failure.json")) shouldContain
              "\"failureCode\":\"INTERNAL_ERROR\""
          }
        }
      }

      mapOf(
          "unknown command" to listOf("unknown"),
          "unknown flag" to listOf(
            "freeze",
            "--treatment-source",
            "inputs/treatment",
            "--unknown",
            "value",
            "--output",
            "build/performance/unknown-flag",
          ),
          "duplicate flag" to listOf(
            "freeze",
            "--treatment-source",
            "inputs/treatment",
            "--output",
            "build/performance/first",
            "--output",
            "build/performance/second",
          ),
        )
        .forEach { (condition, arguments) ->
          test("$condition is an input error before Docker") {
            FakeHost().use { host ->
              val result = host.invoke(*arguments.toTypedArray())

              result.exitCode shouldBe 2
              result.standardError shouldContain "ARGUMENT_INVALID"
              result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
            }
          }
        }

      listOf(
          listOf("cold", "10", "1", null),
          listOf("warm", "20", "2", "gc"),
          listOf("warm", "40", "3", "jfr"),
        )
        .forEach { (profile, forks, sequence, profiler) ->
          test("capture accepts $profile $forks-fork sequence $sequence profiler ${profiler ?: "none"}") {
            FakeHost().use { host ->
              val distribution = host.frozenDistribution("capture-$profile-$forks-$sequence")
              val arguments =
                mutableListOf(
                  "capture",
                  "--profile",
                  profile!!,
                  "--forks",
                  forks!!,
                  "--host-id",
                  "host-1",
                  "--session-id",
                  "session-1",
                  "--sequence",
                  sequence!!,
                  "--distribution",
                  distribution.toString(),
                )
              profiler?.let { arguments += listOf("--diagnostic-profiler", it) }
              arguments += listOf("--output", host.output("capture-$profile-$forks-$sequence"))

              val result = host.invoke(*arguments.toTypedArray())

              result.standardError shouldContain "COMMAND_NOT_AVAILABLE"
              result.standardError shouldNotContain "ARGUMENT_INVALID"
            }
          }
        }

      mapOf(
          "profiler on cold capture" to listOf("cold", "10", "1", "gc"),
          "unsupported fork count" to listOf("warm", "11", "1", null),
          "nonpositive sequence" to listOf("warm", "10", "0", null),
          "unknown profiler" to listOf("warm", "10", "1", "async"),
        )
        .forEach { (condition, values) ->
          test("capture rejects $condition") {
            FakeHost().use { host ->
              val (profile, forks, sequence, profiler) = values
              val distribution = host.frozenDistribution("invalid-capture-$condition")
              val arguments =
                mutableListOf(
                  "capture",
                  "--profile",
                  profile!!,
                  "--forks",
                  forks!!,
                  "--host-id",
                  "host-1",
                  "--session-id",
                  "session-1",
                  "--sequence",
                  sequence!!,
                  "--distribution",
                  distribution.toString(),
                )
              profiler?.let { arguments += listOf("--diagnostic-profiler", it) }
              arguments += listOf("--output", host.output("invalid-capture"))

              val result = host.invoke(*arguments.toTypedArray())

              result.exitCode shouldBe 2
              result.standardError shouldContain "ARGUMENT_INVALID"
              result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
            }
          }
        }

      test("Mac Docker calls always select desktop-linux explicitly") {
        FakeHost().use { host ->
          val command = publicCommands(host).first { it.name == "canary" }
          val result = host.invoke(*command.arguments.toTypedArray())

          result.commands.filter { it.firstOrNull() == "docker" }.forEach { invocation ->
            invocation.take(3) shouldBe listOf("docker", "--context", "desktop-linux")
          }
          val imageInspect =
            result.commands
              .single { invocation -> "image" in invocation && "inspect" in invocation }
              .joinToString("\n")
          imageInspect shouldContain "{{println \"PLATFORM\" .Os .Architecture .Variant}}"
          imageInspect shouldContain "{{range .RepoDigests}}{{println \"REPO\" .}}{{end}}"
          imageInspect shouldContain "{{with .Descriptor}}{{println \"DESCRIPTOR\" .digest}}{{end}}"
          val commandLog = result.commands.flatten().joinToString("\n")
          commandLog shouldContain "/usr/bin/sha256sum /opt/java/openjdk/bin/java"
          commandLog shouldNotContain "/usr/bin/head"
        }
      }

      test("runtime identity accepts portable approved shapes and rejects contradictory identities") {
        listOf(
            mapOf(
              "FAKE_DOCKER_IMAGE_ID" to RUNTIME_MANIFEST,
              "FAKE_DOCKER_DESCRIPTOR_DIGEST" to RUNTIME_MANIFEST,
            ),
            mapOf(
              "FAKE_DOCKER_IMAGE_ID" to RUNTIME_CONFIG,
              "FAKE_DOCKER_DESCRIPTOR_DIGEST" to "",
              "FAKE_DOCKER_REPO_DIGEST" to "docker.io/library/eclipse-temurin@$RUNTIME_MANIFEST",
            ),
          )
          .forEach { environment ->
            FakeHost().use { host ->
              val command = publicCommands(host).first { it.name == "canary" }
              val result = host.invoke(*command.arguments.toTypedArray(), environment = environment)

              result.exitCode shouldBe 2
              result.standardError shouldContain "COMMAND_NOT_AVAILABLE"
              result.standardError shouldNotContain "IMAGE_UNAVAILABLE"
              result.commands.any { invocation -> "volume" in invocation && "create" in invocation } shouldBe
                true
            }
          }

        listOf(
            mapOf(
              "FAKE_DOCKER_IMAGE_ID" to "sha256:${"1".repeat(64)}",
              "FAKE_DOCKER_DESCRIPTOR_DIGEST" to RUNTIME_MANIFEST,
            ),
            mapOf(
              "FAKE_DOCKER_IMAGE_ID" to RUNTIME_CONFIG,
              "FAKE_DOCKER_DESCRIPTOR_DIGEST" to "",
              "FAKE_DOCKER_REPO_DIGEST" to "docker.io/library/eclipse-temurin@sha256:${"2".repeat(64)}",
            ),
            mapOf(
              "FAKE_DOCKER_IMAGE_ID" to RUNTIME_MANIFEST,
              "FAKE_DOCKER_DESCRIPTOR_DIGEST" to RUNTIME_MANIFEST,
              "FAKE_DOCKER_CONFIG_DIGEST" to "sha256:${"3".repeat(64)}",
            ),
          )
          .forEach { environment ->
            FakeHost().use { host ->
              val command = publicCommands(host).first { it.name == "canary" }
              val result = host.invoke(*command.arguments.toTypedArray(), environment = environment)

              result.exitCode shouldBe 2
              result.standardError shouldContain "IMAGE_UNAVAILABLE"
              result.commands.none { invocation -> "volume" in invocation && "create" in invocation } shouldBe
                true
            }
          }
      }

      test("offline phase construction applies the frozen security and mount contract") {
        FakeHost().use { host ->
          val distribution = host.frozenDistribution("secured-capture")
          val result =
            host.invoke(
              "capture",
              "--profile",
              "warm",
              "--forks",
              "10",
              "--host-id",
              "host-1",
              "--session-id",
              "session-1",
              "--sequence",
              "1",
              "--distribution",
              distribution.toString(),
              "--diagnostic-profiler",
              "jfr",
              "--output",
              host.output("secured-capture"),
            )
          val dockerRuns = result.commands.filter(::isDockerRun)

          phase(dockerRuns, "volume-initializer").also { initializer ->
            initializer shouldContainAll
              listOf(
                "--network",
                "none",
                "--pull=never",
                "--read-only",
                "--user",
                "0:0",
                "--cap-drop",
                "ALL",
                "--cap-add",
                "CHOWN",
              )
            writableHostBinds(initializer).shouldBeEmpty()
          }
          listOf("preparation", "timed", "scrubber", "finalizer").forEach { name ->
            val invocation = phase(dockerRuns, name)
            invocation shouldContainAll listOf("--network", "none", "--pull=never")
          }
          listOf("timed", "scrubber", "finalizer").forEach { name ->
            phase(dockerRuns, name) shouldContainAll
              listOf(
                "--read-only",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--cpuset-cpus",
                "0-3",
                "--memory",
                "6g",
                "--memory-swap",
                "6g",
                "--pids-limit",
                "512",
                "--user",
                "10001:10001",
              )
          }
          listOf("timed", "finalizer").forEach { name ->
            val invocation = phase(dockerRuns, name)
            invocation.contains(
              "REVOMAN_JAVA_SHA256=1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b",
            ) shouldBe true
            invocation.last().split("/usr/bin/sha256sum /opt/java/openjdk/bin/java").size shouldBe 3
          }
          writableHostBinds(phase(dockerRuns, "timed")).shouldBeEmpty()
          writableHostBinds(phase(dockerRuns, "scrubber")).shouldBeEmpty()
          writableHostBinds(phase(dockerRuns, "finalizer")).size shouldBe 1
          result.commands.flatten().any { argument ->
            argument.contains("docker.sock") || argument.contains("/home/")
          } shouldBe false
        }
      }

      test("supported commands never invoke host Java Gradle or privilege helpers") {
        FakeHost().use { host ->
          publicCommands(host).forEach { publicCommand ->
            host
              .invoke(*publicCommand.arguments.toTypedArray())
              .commands
              .filter { command ->
                command.firstOrNull() in setOf("java", "gradle", "sudo", "dzdo", "osascript")
              }
              .shouldBeEmpty()
          }
        }
      }

      test("all five commands reject a dirty relevant tree before Docker") {
        FakeHost().use { host ->
          publicCommands(host).forEach { command ->
            val result =
              host.invoke(
                *command.arguments.toTypedArray(),
                environment = mapOf("FAKE_GIT_STATUS" to " M scripts/performance/run\n"),
              )

            result.exitCode shouldBe 2
            result.standardError shouldContain "SOURCE_DIRTY"
            result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
            result.commands.single { invocation -> invocation.take(2) == listOf("git", "status") } shouldContainAll
              listOf(
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle.properties",
                "gradlew",
                "gradlew.bat",
              )
          }
        }
      }

      test("initial freeze requires a clean full SHA before Docker") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *publicCommands(host).first { it.name == "freeze" }.arguments.toTypedArray(),
              environment = mapOf("FAKE_GIT_SHA" to "0123456"),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "SOURCE_IDENTITY_INVALID"
          result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
        }
      }

      test("candidate freeze and every evidence command reject adapter mismatch before Docker") {
        FakeHost().use { host ->
          val mismatch = host.frozenDistribution("mismatch", matchingAdapter = false)
          val commands =
            listOf(
              PublicCommand(
                "freeze",
                listOf(
                  "freeze",
                  "--treatment-source",
                  host.treatmentSource().toString(),
                  "--harness-from",
                  mismatch.toString(),
                  "--output",
                  host.output("mismatched-freeze"),
                ),
              ),
              PublicCommand(
                "canary",
                listOf(
                  "canary",
                  "--distribution",
                  mismatch.toString(),
                  "--host-id",
                  "host-1",
                  "--output",
                  host.output("mismatched-canary"),
                ),
              ),
              PublicCommand(
                "campaign",
                listOf(
                  "campaign",
                  "--profile",
                  "cold",
                  "--host-id",
                  "host-1",
                  "--baseline-distribution",
                  mismatch.toString(),
                  "--candidate-distribution",
                  host.frozenDistribution("matching-candidate").toString(),
                  "--output",
                  host.output("mismatched-campaign"),
                ),
              ),
              PublicCommand(
                "capture",
                listOf(
                  "capture",
                  "--profile",
                  "cold",
                  "--forks",
                  "10",
                  "--host-id",
                  "host-1",
                  "--session-id",
                  "session-1",
                  "--sequence",
                  "1",
                  "--distribution",
                  mismatch.toString(),
                  "--output",
                  host.output("mismatched-capture"),
                ),
              ),
              PublicCommand(
                "compare",
                listOf(
                  "compare",
                  "--kind",
                  "calibration",
                  "--runner-distribution",
                  mismatch.toString(),
                  "--baseline",
                  host.inputDirectory("baseline-capture").toString(),
                  "--candidate",
                  host.inputDirectory("candidate-capture").toString(),
                  "--output",
                  host.output("mismatched-compare"),
                ),
              ),
            )

          commands.forEach { command ->
            val result = host.invoke(*command.arguments.toTypedArray())
            result.exitCode shouldBe 2
            result.standardError shouldContain "ADAPTER_MISMATCH"
            result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
            val output = command.arguments[command.arguments.indexOf("--output") + 1]
            Files.readString(host.repositoryRoot.resolve(output).resolve("adapter-failure.json")) shouldContain
              "\"failureCode\":\"ADAPTER_MISMATCH\""
          }
        }
      }

      test("provenance reads only the canonical distribution protocol adapter") {
        FakeHost().use { host ->
          val canonical =
            host.frozenDistribution("canonical-provenance", canonicalAdapterPath = true)
          val canonicalResult =
            host.invoke(
              "canary",
              "--distribution",
              canonical.toString(),
              "--host-id",
              "host-1",
              "--output",
              host.output("canonical-provenance"),
            )

          canonicalResult.exitCode shouldBe 2
          canonicalResult.standardError shouldContain "COMMAND_NOT_AVAILABLE"
          canonicalResult.standardError shouldNotContain "ADAPTER_MISMATCH"
          canonicalResult.commands.filter { command -> command.firstOrNull() == "docker" }.shouldNotBeEmpty()

          val obsolete =
            host.frozenDistribution("obsolete-provenance", canonicalAdapterPath = false)
          val obsoleteResult =
            host.invoke(
              "canary",
              "--distribution",
              obsolete.toString(),
              "--host-id",
              "host-1",
              "--output",
              host.output("obsolete-provenance"),
            )

          obsoleteResult.exitCode shouldBe 2
          obsoleteResult.standardError shouldContain "ADAPTER_MISMATCH"
          obsoleteResult.commands.filter { command -> command.firstOrNull() == "docker" }.shouldBeEmpty()
        }
      }

      test("daemon volumes require stable generated names and exact label proof before use or removal") {
        FakeHost().use { host ->
          val distribution = host.frozenDistribution("owned-volumes")
          val result =
            host.invoke(
              "canary",
              "--distribution",
              distribution.toString(),
              "--host-id",
              "host-1",
              "--output",
              host.output("owned-volumes"),
            )
          val creates = dockerVolumeCommands(result.commands, "create")
          val inspects = dockerVolumeCommands(result.commands, "inspect")
          val removals = dockerVolumeCommands(result.commands, "rm")

          creates shouldBe
            listOf(
              listOf(
                "docker",
                "--context",
                "desktop-linux",
                "volume",
                "create",
                "--label",
                "dev.revoman.performance.owner=revoman",
                "--label",
                "dev.revoman.performance.token=owned-volumes",
              ),
              listOf(
                "docker",
                "--context",
                "desktop-linux",
                "volume",
                "create",
                "--label",
                "dev.revoman.performance.owner=revoman",
                "--label",
                "dev.revoman.performance.token=owned-volumes",
              ),
            )
          inspects.size shouldBe 4
          removals.size shouldBe 2
          val initializerIndex =
            result.commands.indexOfFirst { command ->
              "dev.revoman.performance.phase=volume-initializer" in command
            }
          inspects.take(2).all { inspection ->
            result.commands.indexOf(inspection) < initializerIndex
          } shouldBe true
          removals.forEach { removal ->
            val removalIndex = result.commands.indexOf(removal)
            result.commands[removalIndex - 1].also { inspection ->
              dockerVolumeCommands(listOf(inspection), "inspect").size shouldBe 1
              inspection.last() shouldBe removal.last()
            }
          }
        }

        listOf(
            mapOf("FAKE_DOCKER_VOLUME_COLLISION" to "1") to Pair(0, 1),
            mapOf("FAKE_DOCKER_VOLUME_CREATE_OUTPUT" to "../unsafe") to Pair(0, 0),
            mapOf("FAKE_DOCKER_VOLUME_INITIAL_LABELS" to "someone-else|stale-token") to Pair(0, 0),
            mapOf("FAKE_DOCKER_VOLUME_CLEANUP_LABELS" to "someone-else|stale-token") to Pair(1, 0),
          )
          .forEach { (environment, expected) ->
            FakeHost().use { host ->
              val distribution = host.frozenDistribution("untrusted-volumes")
              val result =
                host.invoke(
                  "canary",
                  "--distribution",
                  distribution.toString(),
                  "--host-id",
                  "host-1",
                  "--output",
                  host.output("untrusted-volumes"),
                  environment = environment,
                )

              result.exitCode shouldBe 2
              result.standardError shouldContain "INTERNAL_ERROR"
              result.commands
                .count { command -> "dev.revoman.performance.phase=volume-initializer" in command } shouldBe
                expected.first
              dockerVolumeCommands(result.commands, "rm").size shouldBe expected.second
            }
          }
      }
    },
  )

private data class PublicCommand(
  val name: String,
  val arguments: List<String>,
)

private const val RUNTIME_MANIFEST =
  "sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e"
private const val RUNTIME_CONFIG =
  "sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c"

private fun publicCommands(host: FakeHost): List<PublicCommand> {
  val distribution = host.frozenDistribution("distribution")
  val candidateDistribution = host.frozenDistribution("candidate-distribution")
  return listOf(
    PublicCommand(
      "freeze",
      listOf(
        "freeze",
        "--treatment-source",
        host.treatmentSource().toString(),
        "--output",
        host.output("freeze"),
      ),
    ),
    PublicCommand(
      "canary",
      listOf(
        "canary",
        "--distribution",
        distribution.toString(),
        "--host-id",
        "host-1",
        "--output",
        host.output("canary"),
      ),
    ),
    PublicCommand(
      "campaign",
      listOf(
        "campaign",
        "--profile",
        "cold",
        "--host-id",
        "host-1",
        "--baseline-distribution",
        distribution.toString(),
        "--candidate-distribution",
        candidateDistribution.toString(),
        "--output",
        host.output("campaign"),
      ),
    ),
    PublicCommand(
      "capture",
      listOf(
        "capture",
        "--profile",
        "cold",
        "--forks",
        "10",
        "--host-id",
        "host-1",
        "--session-id",
        "session-1",
        "--sequence",
        "1",
        "--distribution",
        distribution.toString(),
        "--output",
        host.output("capture"),
      ),
    ),
    PublicCommand(
      "compare",
      listOf(
        "compare",
        "--kind",
        "calibration",
        "--runner-distribution",
        distribution.toString(),
        "--baseline",
        host.inputDirectory("baseline").toString(),
        "--candidate",
        host.inputDirectory("candidate").toString(),
        "--output",
        host.output("compare"),
      ),
    ),
  )
}

private fun isDockerRun(command: List<String>): Boolean = command.firstOrNull() == "docker" && "run" in command

private fun dockerVolumeCommands(
  commands: List<List<String>>,
  action: String,
): List<List<String>> =
  commands.filter { command ->
    command.firstOrNull() == "docker" &&
      command.windowed(2).any { arguments -> arguments == listOf("volume", action) }
  }

private fun phase(
  commands: List<List<String>>,
  name: String,
): List<String> = commands.single { command -> "dev.revoman.performance.phase=$name" in command }

private fun writableHostBinds(command: List<String>): List<String> =
  command.filter { argument ->
    argument.startsWith("type=bind,") && !argument.contains(",readonly")
  }

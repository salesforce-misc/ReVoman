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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import performance.support.FakeHost

class PrivateRuntimeBindingContractTest :
  FunSpec(
    {
      runtimeCommands().forEach { command ->
        test("${command.name} receives the canonical controlled Mac runtime binding before timing") {
          FakeHost().use { host ->
            val result = host.invoke(*command.arguments(host).toTypedArray())
            val binding = host.repositoryRoot.resolve(PRIVATE_RUNTIME_FIXTURE)
            val observedHash = host.repositoryRoot.resolve(TIMED_RUNTIME_HASH_FIXTURE)
            val bindingPhases = result.commands.filter { RUNTIME_BINDING_PHASE in it }
            val settleCommands = result.commands.filter { it == listOf("sleep", "60000") }
            val watcherCommands = result.commands.filter { it.firstOrNull() == "caffeinate" }
            val timedPhases = result.commands.filter { TIMED_PHASE in it }

            withClue("stderr=${result.standardError}; commands=${result.commands}") {
              result.exitCode shouldBe 0
              bindingPhases.size shouldBe 1
              settleCommands.size shouldBe 1
              watcherCommands.size shouldBe 1
              timedPhases.size shouldBe 1
              (result.commands.indexOf(bindingPhases.single()) <
                result.commands.indexOf(settleCommands.single())) shouldBe true
              (result.commands.indexOf(settleCommands.single()) <
                result.commands.indexOf(watcherCommands.single())) shouldBe true
              (result.commands.indexOf(watcherCommands.single()) <
                result.commands.indexOf(timedPhases.single())) shouldBe true
              Files.exists(binding) shouldBe true
              Files.readAllBytes(binding) shouldBe CONTROLLED_MAC_BINDING
              Files.exists(observedHash) shouldBe true
              Files.readString(observedHash) shouldBe "$CONTROLLED_MAC_BINDING_SHA256\n"
              bindingPhases.single().any { it == "REVOMAN_PRIVATE_RUNTIME_SHA256=$CONTROLLED_MAC_BINDING_SHA256" } shouldBe
                true
              bindingPhases.single().last() shouldContain "/etc/os-release"
              bindingPhases.single().last() shouldContain "uname -r"
            }
          }
        }
      }

      test("failed preflight never creates or exposes a private runtime binding") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *runtimeCommands().first().arguments(host).toTypedArray(),
              environment = mapOf("FAKE_MACOS_VERSION" to "26.6.1"),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          result.commands.none { RUNTIME_BINDING_PHASE in it } shouldBe true
          result.commands.none { TIMED_PHASE in it } shouldBe true
          Files.exists(host.repositoryRoot.resolve(PRIVATE_RUNTIME_FIXTURE)) shouldBe false
          Files.exists(host.repositoryRoot.resolve(TIMED_RUNTIME_HASH_FIXTURE)) shouldBe false
        }
      }

      mapOf(
          "missing" to "missing",
          "malformed" to "malformed",
          "substrate mismatch" to "substrate-mismatch",
        )
        .forEach { (description, mutation) ->
          test("timing fails closed when the private runtime binding is $description") {
            FakeHost().use { host ->
              val result =
                host.invoke(
                  *runtimeCommands().first().arguments(host).toTypedArray(),
                  environment = mapOf("FAKE_PRIVATE_RUNTIME_BINDING_MUTATION" to mutation),
                )

              withClue("stderr=${result.standardError}; commands=${result.commands}") {
                result.exitCode shouldBe 2
                result.standardError shouldContain "INTERNAL_ERROR"
                result.commands.count { RUNTIME_BINDING_PHASE in it } shouldBe 1
                result.commands.count { TIMED_PHASE in it } shouldBe 1
                Files.exists(host.outputPath("canary")) shouldBe false
                Files.exists(host.artifactRoot.resolve("INVALID-canary/INVALID/reason")) shouldBe true
                Files.exists(host.repositoryRoot.resolve(TIMED_RUNTIME_HASH_FIXTURE)) shouldBe false
              }
            }
          }
        }

      test("GitHub timing receives the canonical hosted runtime binding") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *runtimeCommands().first().arguments(host).toTypedArray(),
              environment =
                mapOf(
                  "FAKE_SYSTEM_NAME" to "Linux",
                  "ImageVersion" to "runner-image_v1+rev.2",
                ),
            )
          val binding = host.repositoryRoot.resolve(PRIVATE_RUNTIME_FIXTURE)

          withClue("stderr=${result.standardError}; commands=${result.commands}") {
            result.exitCode shouldBe 0
            Files.exists(binding) shouldBe true
            Files.readAllBytes(binding) shouldBe GITHUB_HOSTED_BINDING
            Files.readString(host.repositoryRoot.resolve(TIMED_RUNTIME_HASH_FIXTURE)) shouldBe
              "$GITHUB_HOSTED_BINDING_SHA256\n"
          }
        }
      }
    },
  )

private data class RuntimeCommand(
  val name: String,
  val arguments: (FakeHost) -> List<String>,
)

private fun runtimeCommands(): List<RuntimeCommand> =
  listOf(
    RuntimeCommand("canary") { host ->
      listOf(
        "canary",
        "--distribution",
        host.frozenDistribution("canary-distribution").toString(),
        "--host-id",
        "opaque-host",
        "--output",
        host.output("canary"),
      )
    },
    RuntimeCommand("capture") { host ->
      listOf(
        "capture",
        "--profile",
        "warm",
        "--forks",
        "10",
        "--host-id",
        "opaque-host",
        "--session-id",
        "session-1",
        "--sequence",
        "1",
        "--distribution",
        host.frozenDistribution("capture-distribution").toString(),
        "--output",
        host.output("capture"),
      )
    },
    RuntimeCommand("campaign") { host ->
      listOf(
        "campaign",
        "--profile",
        "warm",
        "--host-id",
        "opaque-host",
        "--baseline-distribution",
        host.frozenDistribution("campaign-baseline").toString(),
        "--candidate-distribution",
        host.frozenDistribution("campaign-candidate").toString(),
        "--output",
        host.output("campaign"),
      )
    },
  )

private const val PRIVATE_RUNTIME_FIXTURE = ".fake-private-runtime.json"
private const val TIMED_RUNTIME_HASH_FIXTURE = ".fake-timed-private-runtime.sha256"
private const val RUNTIME_BINDING_PHASE = "dev.revoman.performance.phase=runtime-binding"
private const val TIMED_PHASE = "dev.revoman.performance.phase=timed"
private const val CONTROLLED_MAC_BINDING_SHA256 =
  "dae983f685e800b4ab6170ea53d5a8819c7f25979f516a6eaf498efc433ba55a"
private const val GITHUB_HOSTED_BINDING_SHA256 =
  "ca37ab563eb3ff72906b1b74f6eaab6522c0d38579c37bdb1d225ab255e28c1f"
private val CONTROLLED_MAC_BINDING =
  ("""{"linux":{"architecture":"arm64","kernel":"7.0.12-linuxkit","os":"Ubuntu 24.04.4 LTS"},""" +
      """"schemaVersion":"private-runtime-binding-v1","substrate":{"dockerDesktopVersion":"4.87.0",""" +
      """"dockerEngineVersion":"29.7.2","hardwareModelClass":"Mac16,5","kind":"controlledMac",""" +
      """"macosBuild":"25G83","macosVersion":"26.6.2","vmResources":{"cpus":16,""" +
      """"memoryBytes":8318709760}}}""")
    .encodeToByteArray()
private val GITHUB_HOSTED_BINDING =
  ("""{"linux":{"architecture":"arm64","kernel":"6.11.0","os":"Ubuntu 24.04.4 LTS"},""" +
      """"schemaVersion":"private-runtime-binding-v1","substrate":{"advertisedResources":{"cpus":4,""" +
      """"memoryBytes":17179869184},"dockerEngineVersion":"29.7.2","kernel":"6.11.0",""" +
      """"kind":"githubHosted","runnerImageVersion":"runner-image_v1+rev.2",""" +
      """"runnerLabel":"ubuntu-24.04-arm"}}""")
    .encodeToByteArray()

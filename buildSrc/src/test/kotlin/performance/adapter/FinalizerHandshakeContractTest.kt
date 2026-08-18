/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import performance.support.FakeHost

/** The adapter verifies identity and hands all writes/publication to the frozen runner finalizer. */
class FinalizerHandshakeContractTest :
  FunSpec(
    {
      listOf("canary", "capture", "compare", "campaign").forEach { commandName ->
        test("$commandName finalizer receives read-only operation plus one reserved parent") {
          FakeHost().use { host ->
            val command = evidenceCommand(host, commandName)
            val result = host.invoke(*command.toTypedArray())
            val finalizer = withClue(invocationClue(result)) {
              result.commands.filter(::isDockerRun).single { invocation ->
                "dev.revoman.performance.phase=finalizer" in invocation
              }
            }
            val shell = finalizer.last()

            finalizer shouldContainAll
              listOf(
                "--network",
                "none",
                "--pull=never",
                "type=volume,src=${volumeName(finalizer, "/operation")},dst=/operation,readonly",
                "type=bind,src=${host.artifactRoot.toRealPath()},dst=/artifacts",
              )
            shell shouldContain "\"\$runner\" \"\$REVOMAN_FINALIZER_COMMAND\""
            shell shouldContain "--source \"\$finalizer_source\""
            shell shouldContain "--artifact-parent /artifacts"
            shell shouldContain "--run-token \"\$REVOMAN_RUN_TOKEN\""
            shell shouldContain "--terminal \"\$runner_status\""
            shell shouldNotContain "/usr/bin/mv -nT"
            shell shouldNotContain "/usr/bin/rm -f \"\$token_file\""
            shell shouldNotContain "/usr/bin/tar"
            finalizer.count { it.endsWith(",dst=/artifacts") } shouldBe 1
          }
        }
      }

      test("freeze publication is owned by the packaged finalize-freeze command") {
        FakeHost().use { host ->
          val distribution = host.treatmentSource("freeze-treatment")
          val baseline = host.frozenDistribution("freeze-baseline")
          val result =
            host.invoke(
              "freeze",
              "--treatment-source",
              distribution.toString(),
              "--harness-from",
              baseline.toString(),
              "--output",
              host.output("freeze"),
            )
          val finalizer = result.commands.single { command ->
            command.firstOrNull() == "docker" &&
              "dev.revoman.performance.phase=finalizer" in command
          }
          val shell = finalizer.last()

          shell shouldContain "\"\$runner\" \"\$REVOMAN_FINALIZER_COMMAND\""
          shell shouldContain "--source \"\$finalizer_source\""
          finalizer shouldContainAll listOf("REVOMAN_FINALIZER_COMMAND=finalize-freeze")
          shell shouldNotContain "/usr/bin/mv -nT"
          shell shouldNotContain "/usr/bin/tar"
          shell shouldNotContain "/usr/bin/rm -f \"\$token_file\""
        }
      }

      test("frozen recovery runs after verification and before the current reservation") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *evidenceCommand(host, "capture").toTypedArray(),
              functionOverrides =
                """
                adapter_write_reservation() {
                  test -f "${'$'}ADAPTER_REPO_ROOT/.fake-recovery-complete" || return 1
                  (umask 077 && printf '%s\n' "${'$'}2" >"${'$'}1")
                }
                """.trimIndent(),
            )
          val dockerRuns = result.commands.filter(::isDockerRun)
          val verificationIndex =
            dockerRuns.indexOfFirst { "dev.revoman.performance.phase=finalizer-verification" in it }
          val recoveryIndex =
            dockerRuns.indexOfFirst { "dev.revoman.performance.phase=recovery" in it }
          val timedIndex = dockerRuns.indexOfFirst { "dev.revoman.performance.phase=timed" in it }
          (recoveryIndex >= 0) shouldBe true
          val recovery = dockerRuns[recoveryIndex]
          val shell = recovery.last()

          (verificationIndex < recoveryIndex) shouldBe true
          (recoveryIndex < timedIndex) shouldBe true
          recovery shouldContainAll
            listOf(
              "--network",
              "none",
              "--pull=never",
              "type=volume,src=${volumeName(recovery, "/operation")},dst=/operation,readonly",
              "type=volume,src=${volumeName(recovery, "/inputs")},dst=/inputs,readonly",
              "type=bind,src=${host.artifactRoot.toRealPath()},dst=/artifacts",
            )
          shell shouldContain "\"\$runner\" recover"
          shell shouldContain "--artifact-root /artifacts"
          shell shouldContain "--run-token \"\$REVOMAN_RUN_TOKEN\""
          shell shouldContain "--operation-input /operation/provisional"
        }
      }

      test("runner-owned publication reports exit eight for a late destination and preserves it") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *evidenceCommand(host, "capture").toTypedArray(),
              environment = mapOf("FAKE_DOCKER_FINALIZER_BOUNDARY" to "late-directory"),
            )

          result.exitCode shouldBe 8
          result.standardError shouldContain "INTERNAL_OR_PUBLICATION_FAILED"
          withClue(
            host.repositoryRoot
              .resolve(".fake-publication-helper.log")
              .takeIf(Files::exists)
              ?.let(Files::readString)
              .orEmpty(),
          ) {
            Files.readString(host.outputPath("capture").resolve("foreign.txt")) shouldBe "keep"
          }
        }
      }

      test("finalizer verification failure occurs before reservation and publishes no artifact") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *evidenceCommand(host, "capture").toTypedArray(),
              functionOverrides = "adapter_run_finalizer_verification() { return 1; }",
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "FINALIZER_UNAVAILABLE"
          Files.exists(host.outputPath("capture")) shouldBe false
          Files.list(host.artifactRoot).use { entries ->
            entries.noneMatch { it.fileName.toString().contains("reservation") }
          } shouldBe true
        }
      }

      test("profiler scrubber finishes before finalizer and never receives the host bind") {
        FakeHost().use { host ->
          val result = host.invoke(*evidenceCommand(host, "capture", profiler = "jfr").toTypedArray())
          val dockerRuns = result.commands.filter(::isDockerRun)
          val scrubberIndex = dockerRuns.indexOfFirst { "dev.revoman.performance.phase=scrubber" in it }
          val finalizerIndex = dockerRuns.indexOfFirst { "dev.revoman.performance.phase=finalizer" in it }
          val scrubber = dockerRuns[scrubberIndex]
          val shell = scrubber.last()
          (scrubberIndex < finalizerIndex) shouldBe true
          scrubber.none { it.endsWith(",dst=/artifacts") } shouldBe true
          scrubber shouldContainAll
            listOf(
              "--network",
              "none",
              "--pull=never",
              "type=volume,src=${volumeName(scrubber, "/operation")},dst=/operation",
              "type=volume,src=${volumeName(scrubber, "/inputs")},dst=/inputs,readonly",
            )
          shell shouldContain "\"\$runner\" scrub-profiler"
          shell shouldContain "--raw /operation/provisional/profile.jfr"
          shell shouldContain "--summary /operation/provisional/profiler-summary.json"
          shell shouldContain "--intent /operation/provisional/profiler-scrub.intent.json"
          shell shouldContain "--completion /operation/provisional/profiler-scrub.complete.json"
        }
      }

      mapOf(
          "capture" to "finalize-diagnostic",
          "compare" to "finalize-standalone-comparison",
          "campaign" to "finalize-campaign",
        )
        .forEach { (commandName, finalizerName) ->
          test("$commandName invokes its real operation and $finalizerName") {
            FakeHost().use { host ->
              val result = host.invoke(*evidenceCommand(host, commandName).toTypedArray())
              val dockerRuns = result.commands.filter(::isDockerRun)
              val operation =
                dockerRuns.single { invocation ->
                  "dev.revoman.performance.phase=timed" in invocation
                }
              val finalizer =
                dockerRuns.single { invocation ->
                  "dev.revoman.performance.phase=finalizer" in invocation
                }

              operation.last() shouldContain "set -- $commandName"
              operation.last() shouldContain "\"\$runner\" \"\$@\""
              finalizer shouldContainAll listOf("REVOMAN_FINALIZER_COMMAND=$finalizerName")
            }
          }
        }

      test("fake Docker records finalization without manufacturing public evidence") {
        FakeHost().use { host ->
          val runToken = "no-synthetic-evidence"
          val reservation =
            Files.createDirectory(host.artifactRoot.resolve(".$runToken.reservation"))
          Files.writeString(reservation.resolve("token"), "$runToken\n")
          Files.writeString(host.repositoryRoot.resolve(".fake-finalizer-verified"), "verified\n")

          val result =
            host.invokeFakeDocker(
              "run",
              "--mount",
              "type=bind,src=${host.artifactRoot},dst=/artifacts",
              "--env",
              "REVOMAN_COMMAND=capture",
              "--env",
              "REVOMAN_FINALIZER_COMMAND=finalize-diagnostic",
              "--env",
              "REVOMAN_FAILURE_CODE=NONE",
              "--env",
              "REVOMAN_RUN_TOKEN=$runToken",
              "image",
              "command",
            )

          result.exitCode shouldBe 8
          Files.exists(host.outputPath(runToken)) shouldBe false
          Files.readString(reservation.resolve("token")) shouldBe "$runToken\n"
        }
      }
    },
  )

private fun evidenceCommand(
  host: FakeHost,
  name: String,
  profiler: String? = null,
): List<String> {
  val distribution = host.frozenDistribution("$name-distribution")
  return when (name) {
    "canary" ->
      listOf(
        "canary",
        "--distribution",
        distribution.toString(),
        "--host-id",
        "m4max-docker-canary-v1",
        "--output",
        host.output(name),
      )
    "capture" ->
      buildList {
        addAll(
          listOf(
            "capture",
            "--profile",
            "warm",
            "--forks",
            "10",
            "--host-id",
            "m4max-docker-linux-arm64-v1",
            "--session-id",
            "session-1",
            "--sequence",
            "1",
            "--distribution",
            distribution.toString(),
          ),
        )
        profiler?.let { addAll(listOf("--diagnostic-profiler", it)) }
        addAll(listOf("--output", host.output(name)))
      }
    "compare" -> {
      val baseline = host.inputDirectory("baseline").also { Files.writeString(it.resolve("capture.json"), "{}") }
      val candidate = host.inputDirectory("candidate").also { Files.writeString(it.resolve("capture.json"), "{}") }
      listOf(
        "compare",
        "--kind",
        "candidate",
        "--runner-distribution",
        distribution.toString(),
        "--baseline",
        baseline.toString(),
        "--candidate",
        candidate.toString(),
        "--output",
        host.output(name),
      )
    }
    "campaign" ->
      listOf(
        "campaign",
        "--profile",
        "warm",
        "--host-id",
        "m4max-docker-linux-arm64-v1",
        "--baseline-distribution",
        distribution.toString(),
        "--candidate-distribution",
        distribution.toString(),
        "--output",
        host.output(name),
      )
    else -> error("unsupported command")
  }
}

private fun isDockerRun(command: List<String>): Boolean =
  command.firstOrNull() == "docker" && "run" in command

private fun invocationClue(result: performance.support.AdapterInvocation): String =
  "exit=${result.exitCode} stderr=${result.standardError} commands=${result.commands}"

private fun volumeName(command: List<String>, destination: String): String =
  command
    .single { it.startsWith("type=volume,src=") && it.contains("dst=$destination") }
    .substringAfter("type=volume,src=")
    .substringBefore(",dst=")

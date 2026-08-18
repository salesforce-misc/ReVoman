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

      test("freeze omits qualification input while timed finalizers retain it") {
        FakeHost().use { host ->
          val freezeResult =
            host.invoke(
              "freeze",
              "--treatment-source",
              host.treatmentSource("freeze-qualification-treatment").toString(),
              "--harness-from",
              host.frozenDistribution("freeze-qualification-baseline").toString(),
              "--output",
              host.output("freeze-qualification"),
            )
          val invocations =
            mapOf(
              "freeze" to freezeResult,
              "canary" to host.invoke(*evidenceCommand(host, "canary").toTypedArray()),
              "capture" to host.invoke(*evidenceCommand(host, "capture").toTypedArray()),
              "campaign" to host.invoke(*evidenceCommand(host, "campaign").toTypedArray()),
            )
          val expectedArguments =
            mapOf(
              "freeze" to
                listOf(
                  "finalize-freeze",
                  "--source",
                  "/operation/provisional/distribution",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "freeze-qualification",
                  "--terminal",
                  "0",
                ),
              "canary" to
                listOf(
                  "finalize-diagnostic",
                  "--source",
                  "/operation/provisional",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "canary",
                  "--terminal",
                  "0",
                  "--operation-state",
                  "/operation/state",
                  "--qualification-root",
                  "/qualification",
                ),
              "capture" to
                listOf(
                  "finalize-diagnostic",
                  "--source",
                  "/operation/provisional",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "capture",
                  "--terminal",
                  "0",
                  "--operation-state",
                  "/operation/state",
                  "--qualification-root",
                  "/qualification",
                ),
              "campaign" to
                listOf(
                  "finalize-campaign",
                  "--source",
                  "/operation/provisional",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "campaign",
                  "--terminal",
                  "0",
                ),
            )

          invocations.forEach { (commandName, result) ->
            withClue(invocationClue(result)) { result.exitCode shouldBe 0 }
            val finalizer =
              result.commands.filter(::isDockerRun).single { invocation ->
                "dev.revoman.performance.phase=finalizer" in invocation
              }
            val qualificationMounts =
              finalizer.filter { argument ->
                argument.startsWith("type=bind,src=") &&
                  argument.endsWith(",dst=/qualification,readonly")
              }

            if (commandName == "freeze") {
              qualificationMounts shouldBe emptyList()
            } else {
              qualificationMounts.size shouldBe 1
              qualificationMounts.single().substringAfter("type=bind,src=").substringBefore(",dst=").isNotBlank() shouldBe true
            }
            executeFinalizerDispatch(host, finalizer) shouldBe expectedArguments.getValue(commandName)
          }
        }
      }

      test("finalizer runner arguments match the command-specific CLI contracts") {
        FakeHost().use { host ->
          val freezeDistribution = host.treatmentSource("freeze-argv-treatment")
          val freezeBaseline = host.frozenDistribution("freeze-argv-baseline")
          val invocations =
            mapOf(
              "freeze" to
                host.invoke(
                  "freeze",
                  "--treatment-source",
                  freezeDistribution.toString(),
                  "--harness-from",
                  freezeBaseline.toString(),
                  "--output",
                  host.output("freeze-argv"),
                ),
              "campaign" to host.invoke(*evidenceCommand(host, "campaign").toTypedArray()),
              "capture" to host.invoke(*evidenceCommand(host, "capture").toTypedArray()),
            )

          val expectedArguments =
            mapOf(
              "freeze" to
                listOf(
                  "finalize-freeze",
                  "--source",
                  "/operation/provisional/distribution",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "freeze-argv",
                  "--terminal",
                  "0",
                ),
              "campaign" to
                listOf(
                  "finalize-campaign",
                  "--source",
                  "/operation/provisional",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "campaign",
                  "--terminal",
                  "0",
                ),
              "capture" to
                listOf(
                  "finalize-diagnostic",
                  "--source",
                  "/operation/provisional",
                  "--artifact-parent",
                  "/artifacts",
                  "--run-token",
                  "capture",
                  "--terminal",
                  "0",
                  "--operation-state",
                  "/operation/state",
                  "--qualification-root",
                  "/qualification",
                ),
            )

          invocations.forEach { (commandName, result) ->
            val finalizer = withClue(invocationClue(result)) {
              result.commands.filter(::isDockerRun).single { invocation ->
                "dev.revoman.performance.phase=finalizer" in invocation
              }
            }

            executeFinalizerDispatch(host, finalizer) shouldBe expectedArguments.getValue(commandName)
          }
        }
      }

      test("freeze bypasses the timed host lifecycle and finalizes with terminal zero") {
        FakeHost().use { host ->
          val lifecycleEvents = host.repositoryRoot.resolve("timed-lifecycle-events")
          val overrides =
            """
            record_timed_lifecycle() {
              printf '%s:%s\n' "${'$'}ADAPTER_COMMAND" "${'$'}1" >>"${'$'}ADAPTER_REPO_ROOT/timed-lifecycle-events"
              [ "${'$'}ADAPTER_COMMAND" != freeze ]
            }
            eval "${'$'}(declare -f adapter_run_preflight | /usr/bin/sed '1s/adapter_run_preflight/adapter_run_preflight_impl/')"
            eval "${'$'}(declare -f adapter_start_controller_children | /usr/bin/sed '1s/adapter_start_controller_children/adapter_start_controller_children_impl/')"
            eval "${'$'}(declare -f adapter_stop_and_join_controller_children | /usr/bin/sed '1s/adapter_stop_and_join_controller_children/adapter_stop_and_join_controller_children_impl/')"
            eval "${'$'}(declare -f adapter_write_watcher_document | /usr/bin/sed '1s/adapter_write_watcher_document/adapter_write_watcher_document_impl/')"
            eval "${'$'}(declare -f adapter_write_postflight | /usr/bin/sed '1s/adapter_write_postflight/adapter_write_postflight_impl/')"
            eval "${'$'}(declare -f adapter_cleanup_host_state | /usr/bin/sed '1s/adapter_cleanup_host_state/adapter_cleanup_host_state_impl/')"
            eval "${'$'}(declare -f adapter_write_restoration | /usr/bin/sed '1s/adapter_write_restoration/adapter_write_restoration_impl/')"
            adapter_run_preflight() {
              record_timed_lifecycle preflight || return 1
              adapter_run_preflight_impl "${'$'}@"
            }
            adapter_start_controller_children() {
              record_timed_lifecycle controller-start || return 1
              adapter_start_controller_children_impl "${'$'}@"
            }
            adapter_stop_and_join_controller_children() {
              record_timed_lifecycle controller-stop || return 1
              adapter_stop_and_join_controller_children_impl "${'$'}@"
            }
            adapter_write_watcher_document() {
              record_timed_lifecycle watcher || return 1
              adapter_write_watcher_document_impl "${'$'}@"
            }
            adapter_write_postflight() {
              record_timed_lifecycle postflight || return 1
              adapter_write_postflight_impl "${'$'}@"
            }
            adapter_cleanup_host_state() {
              record_timed_lifecycle host-cleanup || return 1
              adapter_cleanup_host_state_impl "${'$'}@"
            }
            adapter_write_restoration() {
              record_timed_lifecycle restoration || return 1
              adapter_write_restoration_impl "${'$'}@"
            }
            """.trimIndent()
          val freezeResult =
            host.invoke(
              "freeze",
              "--treatment-source",
              host.treatmentSource("freeze-lifecycle-treatment").toString(),
              "--harness-from",
              host.frozenDistribution("freeze-lifecycle-baseline").toString(),
              "--output",
              host.output("freeze-lifecycle"),
              functionOverrides = overrides,
            )

          withClue(invocationClue(freezeResult)) { freezeResult.exitCode shouldBe 0 }
          Files.exists(lifecycleEvents) shouldBe false
          val freezeFinalizer =
            freezeResult.commands.filter(::isDockerRun).single { invocation ->
              "dev.revoman.performance.phase=finalizer" in invocation
            }
          executeFinalizerDispatch(host, freezeFinalizer) shouldBe
            listOf(
              "finalize-freeze",
              "--source",
              "/operation/provisional/distribution",
              "--artifact-parent",
              "/artifacts",
              "--run-token",
              "freeze-lifecycle",
              "--terminal",
              "0",
            )

          val expectedTimedLifecycle =
            listOf(
              "preflight",
              "controller-start",
              "controller-stop",
              "watcher",
              "postflight",
              "host-cleanup",
              "restoration",
            )
          listOf("canary", "capture", "campaign").forEach { commandName ->
            val result =
              host.invoke(
                *evidenceCommand(host, commandName).toTypedArray(),
                functionOverrides = overrides,
              )
            withClue(invocationClue(result)) { result.exitCode shouldBe 0 }
            Files.readAllLines(lifecycleEvents)
              .filter { event -> event.startsWith("$commandName:") }
              .map { event -> event.substringAfter(':') } shouldContainAll expectedTimedLifecycle
          }
        }
      }

      test("volume initializer establishes recovery authority before verification and reservation") {
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
          val initializerIndex =
            dockerRuns.indexOfFirst { "dev.revoman.performance.phase=volume-initializer" in it }
          val verificationIndex =
            dockerRuns.indexOfFirst { "dev.revoman.performance.phase=finalizer-verification" in it }
          val recoveryIndex =
            dockerRuns.indexOfFirst { "dev.revoman.performance.phase=recovery" in it }
          val timedIndex = dockerRuns.indexOfFirst { "dev.revoman.performance.phase=timed" in it }
          (initializerIndex >= 0) shouldBe true
          (recoveryIndex >= 0) shouldBe true
          val initializer = dockerRuns[initializerIndex]
          val recovery = dockerRuns[recoveryIndex]
          val initializerShell = initializer.last()
          val shell = recovery.last()

          (initializerIndex < verificationIndex) shouldBe true
          (verificationIndex < recoveryIndex) shouldBe true
          (recoveryIndex < timedIndex) shouldBe true
          initializer shouldContainAll
            listOf(
              "type=volume,src=${volumeName(initializer, "/operation")},dst=/operation",
            )
          initializer.contains(
            "type=volume,src=${volumeName(initializer, "/operation")},dst=/operation,readonly",
          ) shouldBe false
          initializerShell shouldContain "/operation/provisional"
          recovery shouldContainAll
            listOf(
              "--network",
              "none",
              "--pull=never",
              "type=volume,src=${volumeName(recovery, "/operation")},dst=/operation,readonly",
              "type=volume,src=${volumeName(recovery, "/inputs")},dst=/inputs,readonly",
              "type=bind,src=${host.artifactRoot.toRealPath()},dst=/artifacts",
            )
          volumeName(initializer, "/operation") shouldBe volumeName(recovery, "/operation")
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

private fun executeFinalizerDispatch(
  host: FakeHost,
  invocation: List<String>,
): List<String> {
  val argumentLog = host.repositoryRoot.resolve(".fake-finalizer-arguments")
  val runner = host.repositoryRoot.resolve("fake-finalizer-runner")
  Files.writeString(runner, "#!/bin/sh\nprintf '%s\\n' \"\$@\" >\"\$FINALIZER_ARGUMENT_LOG\"\n")
  runner.toFile().setExecutable(true, true)

  val dispatch =
    invocation
      .last()
      .substringAfter("runner_status=0\n", missingDelimiterValue = "")
      .substringBefore("case \"\$finalizer_status\" in", missingDelimiterValue = "")
  dispatch.isNotBlank() shouldBe true
  val environment =
    invocation
      .windowed(2)
      .filter { (flag, _) -> flag == "--env" }
      .map { (_, assignment) -> assignment.substringBefore('=') to assignment.substringAfter('=') }
      .toMap()

  val process =
    ProcessBuilder("/bin/sh", "-c", "runner=\"\$TEST_FINALIZER_RUNNER\"\n$dispatch")
      .directory(host.repositoryRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment().putAll(environment)
        environment()["FINALIZER_ARGUMENT_LOG"] = argumentLog.toString()
        environment()["TEST_FINALIZER_RUNNER"] = runner.toString()
      }
      .start()
  val output = process.inputStream.bufferedReader().readText()
  withClue(output) { process.waitFor() shouldBe 0 }
  return Files.readAllLines(argumentLog)
}

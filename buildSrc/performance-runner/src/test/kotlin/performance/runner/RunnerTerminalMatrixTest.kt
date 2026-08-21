/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import performance.cli.runMain
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.support.CaptureBundleFixture
import performance.support.DistributionFixture
import tools.jackson.databind.node.JsonNodeFactory

/** Stable CLI terminal mapping, including the exit-eight publication supersession rule. */
class RunnerTerminalMatrixTest :
  FunSpec(
    {
      val rows =
        listOf(
          TerminalRow("valid", RunnerExit.SUCCESS, "VALID"),
          TerminalRow("input", RunnerExit.INPUT_OR_PREFLIGHT_INVALID, "NONE"),
          TerminalRow("measurement", RunnerExit.MEASUREMENT_INVALID, "INVALID"),
          TerminalRow("incompatible", RunnerExit.INCOMPATIBLE, "INVALID"),
          TerminalRow("calibration", RunnerExit.CALIBRATION_FAILED, "INVALID"),
          TerminalRow("policy-pass", RunnerExit.SUCCESS, "VALID"),
          TerminalRow("policy-fail", RunnerExit.POLICY_FAILED, "VALID"),
          TerminalRow("policy-inconclusive", RunnerExit.POLICY_INCONCLUSIVE, "VALID"),
          TerminalRow("publication", RunnerExit.INTERNAL_OR_PUBLICATION_FAILED, "RECOVERABLE"),
        )

      rows.forEach { row ->
        test("packaged finalizer preserves ${row.name} terminal ${row.exit.code}") {
          val artifact = Files.createTempDirectory("runner-terminal-${row.name}-").toRealPath()
          Files.writeString(artifact.resolve("state"), row.artifactState)
          var observed: RunnerCommand? = null
          val dependencies =
            RunnerDependencies.forTest(
              writeStandardError = {},
              executeCommand = { command ->
                observed = command
                RunnerOutcome(row.exit, artifact)
              },
            )

          runMain(
            listOf(
              "finalize-diagnostic",
              "--source",
              "private/source",
              "--artifact-parent",
              "build/artifacts",
              "--run-token",
              "run-1",
              "--terminal",
              row.exit.code.toString(),
            ),
            dependencies,
          ) shouldBe row.exit.code
          observed shouldBe
            RunnerCommand.FinalizeDiagnostic(
              listOf(
                "--source",
                "private/source",
                "--artifact-parent",
                "build/artifacts",
                "--run-token",
                "run-1",
                "--terminal",
                row.exit.code.toString(),
              ),
            )
          Files.readString(artifact.resolve("state")) shouldBe row.artifactState
        }
      }

      test("publication failure supersedes every earlier stable terminal") {
        RunnerExit.entries.filterNot { it == RunnerExit.INTERNAL_OR_PUBLICATION_FAILED }.forEach { prior ->
          val dependencies =
            RunnerDependencies.forTest(
              writeStandardError = {},
              executeCommand = {
                RunnerOutcome(RunnerExit.INTERNAL_OR_PUBLICATION_FAILED, null)
              },
            )

          runMain(
            listOf(
              "finalize-campaign",
              "--source",
              "private/campaign",
              "--artifact-parent",
              "build/artifacts",
              "--run-token",
              "campaign-1",
              "--terminal",
              prior.code.toString(),
            ),
            dependencies,
          ) shouldBe RunnerExit.INTERNAL_OR_PUBLICATION_FAILED.code
        }
      }

      test("recovery and profiler commands require complete explicit path and hash handshakes") {
        val failures = mutableListOf<String>()
        val dependencies = RunnerDependencies(writeStandardError = failures::add)

        runMain(listOf("recover"), dependencies) shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
        runMain(listOf("scrub-profiler"), dependencies) shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
        runMain(listOf("finalize-diagnostic"), dependencies) shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
        runMain(listOf("finalize-campaign"), dependencies) shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
        failures.size shouldBe 4
      }

      test("diagnostic finalization rejects a partial profiler handshake before delegation") {
        var observed: RunnerCommand? = null
        val dependencies =
          RunnerDependencies.forTest(
            writeStandardError = {},
            executeCommand = { command ->
              observed = command
              RunnerOutcome(RunnerExit.SUCCESS, null)
            },
          )

        runMain(
          listOf(
            "finalize-diagnostic",
            "--source",
            "private/source",
            "--artifact-parent",
            "build/artifacts",
            "--run-token",
            "run-1",
            "--terminal",
            "0",
            "--operation-root",
            "private/operation",
          ),
          dependencies,
        ) shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
        observed shouldBe null
      }

      test("the command execution seam is absent from public JVM constructors") {
        RunnerDependencies::class.java.constructors.none { constructor ->
          constructor.parameterTypes.count { type ->
            type.name == "kotlin.jvm.functions.Function1"
          } >= 3
        } shouldBe true
      }

      test("packaged profiler command delegates to the accepted scrubber contract") {
        val operation = Files.createTempDirectory("runner-profiler-command-").toRealPath()
        val raw = operation.resolve("raw.jfr")
        Files.writeString(raw, "not-a-jfr")
        val exit =
          runMain(
            listOf(
              "scrub-profiler",
              "--capture-id",
              "capture-1",
              "--provisional-capture-sha256",
              "a".repeat(64),
              "--raw-input-sha256",
              Sha256.digest(raw).hex,
              "--variant-sha256",
              "b".repeat(64),
              "--settings-sha256",
              "c".repeat(64),
              "--raw",
              raw.toString(),
              "--summary",
              operation.resolve("profiler-summary.json").toString(),
              "--intent",
              operation.resolve("profiler-scrub.intent.json").toString(),
              "--completion",
              operation.resolve("profiler-scrub.complete.json").toString(),
            ),
            RunnerDependencies.system(),
          )

        exit shouldBe RunnerExit.MEASUREMENT_INVALID.code
        Files.readString(raw) shouldBe "not-a-jfr"
        Files.exists(operation.resolve("profiler-summary.json")) shouldBe false
        Files.exists(operation.resolve("profiler-scrub.intent.json")) shouldBe false
        Files.exists(operation.resolve("profiler-scrub.complete.json")) shouldBe false
      }

      test("packaged finalize-freeze command reaches the runner-owned finalizer") {
        var observed: RunnerCommand? = null
        val dependencies =
          RunnerDependencies.forTest(
            writeStandardError = {},
            executeCommand = { command ->
              observed = command
              RunnerOutcome(RunnerExit.SUCCESS, null)
            },
          )
        val arguments =
          listOf(
            "finalize-freeze",
            "--source",
            "/operation/provisional/distribution",
            "--artifact-parent",
            "/artifacts",
            "--run-token",
            "freeze-1",
            "--terminal",
            "0",
          )

        runMain(arguments, dependencies) shouldBe RunnerExit.SUCCESS.code
        observed shouldBe RunnerCommand.FinalizeFreeze(arguments.drop(1))
      }

      mapOf(
          "capture" to
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
              "missing-distribution",
              "--output",
              "capture-output",
            ),
          "compare" to
            listOf(
              "compare",
              "--kind",
              "calibration",
              "--runner-distribution",
              "missing-distribution",
              "--baseline",
              "missing-baseline",
              "--candidate",
              "missing-candidate",
              "--output",
              "compare-output",
            ),
          "campaign" to
            listOf(
              "campaign",
              "--profile",
              "cold",
              "--host-id",
              "host-1",
              "--baseline-distribution",
              "missing-baseline",
              "--candidate-distribution",
              "missing-candidate",
              "--output",
              "campaign-output",
            ),
        )
        .forEach { (name, arguments) ->
          test("installed runner sends complete $name arguments to its production handler") {
            val result = invokeInstalledRunner(arguments)

            result.exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
            result.standardError shouldContain "DISTRIBUTION_INVALID"
          }
        }

      test("installed runner sends standalone comparison finalization to its production handler") {
        val root = Files.createTempDirectory("runner-standalone-finalizer-").toRealPath()
        val result =
          invokeInstalledRunner(
            listOf(
              "finalize-standalone-comparison",
              "--source",
              root.resolve("missing-source").toString(),
              "--artifact-parent",
              root.toString(),
              "--run-token",
              "comparison-1",
              "--terminal",
              "0",
            ),
          )

        result.exit shouldBe RunnerExit.INCOMPATIBLE.code
        result.standardError shouldBe ""
      }

      test("installed capture reaches CaptureRunner with a verified distribution") {
        val distribution = DistributionFixture.create()
        try {
          distribution.prepareSuccessfulOperationProtocol()
          val operation = Files.createTempDirectory("runner-real-capture-").toRealPath()
          writePrivateRuntimeBinding(operation, "controlledMac")
          val result =
            invokeInstalledRunner(
              listOf(
                "capture",
                "--profile",
                "cold",
                "--forks",
                "10",
                "--host-id",
                "m4max-docker-linux-arm64-v1",
                "--session-id",
                "session-1",
                "--sequence",
                "1",
                "--distribution",
                distribution.root.toString(),
                "--output",
                "provisional",
              ),
              operation,
              frozenRuntimeOverlay = true,
            )

          result.exit shouldBe RunnerExit.SUCCESS.code
          Files.isDirectory(operation.resolve("provisional")) shouldBe true
          Files.isRegularFile(operation.resolve("state/capture-provisional.json")) shouldBe true
        } finally {
          distribution.close()
        }
      }

      test("installed compare reaches CaptureComparator with a verified runner distribution") {
        val distribution = DistributionFixture.create()
        try {
          distribution.prepareComparisonProtocol()
          val installLib = Path.of(checkNotNull(System.getProperty(INSTALL_DIST_LIB_PROPERTY)))
          val runnerClasspath = distribution.installRunnerClasspath(installLib)
          val operation = Files.createTempDirectory("runner-real-compare-").toRealPath()
          val baseline = CaptureBundleFixture.create(distribution, captureId = "a1")
          val candidate =
            CaptureBundleFixture.create(
              distribution,
              captureId = "a2",
              processRunId = "process-a2",
              sequence = 2,
              startedAtUtc = "2026-08-17T00:01:00Z",
              completedAtUtc = "2026-08-17T00:02:00Z",
            )
          try {
            val result =
              invokeInstalledRunner(
                listOf(
                  "compare",
                  "--kind",
                  "calibration",
                  "--runner-distribution",
                  distribution.root.toString(),
                  "--baseline",
                  baseline.root.toString(),
                  "--candidate",
                  candidate.root.toString(),
                  "--output",
                  "provisional",
                ),
                operation,
                runnerClasspath = runnerClasspath,
              )

            result.exit shouldBe RunnerExit.SUCCESS.code
            result.standardError shouldNotContain "COMMAND_NOT_AVAILABLE"
            result.standardError shouldNotContain "DISTRIBUTION_INVALID"
            Files.exists(operation.resolve("provisional/comparison.json")) shouldBe true
          } finally {
            candidate.close()
            baseline.close()
          }
        } finally {
          distribution.close()
        }
      }

      test("installed campaign reaches CampaignRunner with verified distributions") {
        val baseline = DistributionFixture.create()
        val candidate = DistributionFixture.create()
        try {
            baseline.prepareSuccessfulOperationProtocol()
            candidate.prepareSuccessfulOperationProtocol()
            val operation = Files.createTempDirectory("runner-real-campaign-").toRealPath()
            writePrivateRuntimeBinding(operation, "controlledMac")
            val result =
              invokeInstalledRunner(
                listOf(
                  "campaign",
                  "--profile",
                  "cold",
                  "--host-id",
                  "m4max-docker-linux-arm64-v1",
                  "--baseline-distribution",
                  baseline.root.toString(),
                  "--candidate-distribution",
                  candidate.root.toString(),
                  "--output",
                  "provisional",
                ),
                operation,
                frozenRuntimeOverlay = true,
              )

            result.exit shouldBe RunnerExit.SUCCESS.code
            Files.isDirectory(operation.resolve("provisional")) shouldBe true
            Files.isRegularFile(operation.resolve("state/campaign-provisional.json")) shouldBe true
        } finally {
          candidate.close()
          baseline.close()
        }
      }

      test("installed capture derives github substrate from trusted private state") {
        val distribution = DistributionFixture.create()
        try {
          distribution.prepareOperationProtocol()
          val operation = Files.createTempDirectory("runner-github-capture-").toRealPath()
          writePrivateRuntimeBinding(operation, "githubHosted")

          val result =
            invokeInstalledRunner(
              listOf(
                "capture",
                "--profile",
                "cold",
                "--forks",
                "10",
                "--host-id",
                "github-hosted-arm64-canary-v1",
                "--session-id",
                "session-1",
                "--sequence",
                "1",
                "--distribution",
                distribution.root.toString(),
                "--output",
                "provisional",
              ),
              operation,
            )

          result.exit shouldBe RunnerExit.MEASUREMENT_INVALID.code
          Files.isDirectory(operation.resolve("provisional")) shouldBe true
        } finally {
          distribution.close()
        }
      }

      test("installed capture rejects an unsupported private substrate without fallback") {
        val distribution = DistributionFixture.create()
        try {
          distribution.prepareOperationProtocol()
          val operation = Files.createTempDirectory("runner-invalid-substrate-").toRealPath()
          writePrivateRuntimeBinding(operation, "unknown")

          val result =
            invokeInstalledRunner(
              listOf(
                "capture",
                "--profile",
                "cold",
                "--forks",
                "10",
                "--host-id",
                "opaque-user-alias",
                "--session-id",
                "session-1",
                "--sequence",
                "1",
                "--distribution",
                distribution.root.toString(),
                "--output",
                "provisional",
              ),
              operation,
            )

          result.exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
          Files.exists(operation.resolve("provisional")) shouldBe false
        } finally {
          distribution.close()
        }
      }
    },
  )

private data class TerminalRow(
  val name: String,
  val exit: RunnerExit,
  val artifactState: String,
)

private data class InstalledRunnerResult(
  val exit: Int,
  val standardError: String,
)

private fun invokeInstalledRunner(
  arguments: List<String>,
  workingDirectory: Path? = null,
  frozenRuntimeOverlay: Boolean = false,
  runnerClasspath: List<Path>? = null,
): InstalledRunnerResult {
  val installLib = Path.of(checkNotNull(System.getProperty(INSTALL_DIST_LIB_PROPERTY)))
  val installRoot = installLib.parent
  val standardError = Files.createTempFile("installed-runner-", ".stderr")
  val currentJava = Path.of(checkNotNull(ProcessHandle.current().info().command().orElse(null)))
  val command =
    if (runnerClasspath != null) {
      listOf(
        currentJava.toString(),
        "-cp",
        runnerClasspath.joinToString(java.io.File.pathSeparator),
        "performance.cli.PerformanceRunnerMainKt",
      ) + arguments
    } else if (frozenRuntimeOverlay) {
      val overlay = frozenRuntimeSchemaOverlay(currentJava)
      val classpath =
        buildList {
          add(overlay.toString())
          Files.list(installLib).use { entries ->
            addAll(entries.sorted().map(Path::toString).toList())
          }
        }.joinToString(java.io.File.pathSeparator)
      listOf(currentJava.toString(), "-cp", classpath, "performance.cli.PerformanceRunnerMainKt") +
        arguments
    } else {
      listOf(installRoot.resolve("bin/performance-runner").toString()) + arguments
    }
  val builder = ProcessBuilder(command)
  builder.environment()["JAVA_HOME"] = currentJava.parent.parent.toString()
  workingDirectory?.let { builder.directory(it.toFile()) }
  val process =
    builder
      .redirectError(standardError.toFile())
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
  val exit = process.waitFor()
  return InstalledRunnerResult(exit, Files.readString(standardError))
}

private fun frozenRuntimeSchemaOverlay(currentJava: Path): Path {
  val pinned = "1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b"
  val actual = Sha256.digest(Files.readAllBytes(currentJava)).hex
  val resource = "performance/protocol/schemas/capture-v1.schema.json"
  val bytes =
    checkNotNull(RunnerTerminalMatrixTest::class.java.getResourceAsStream("/$resource"))
      .use { it.readAllBytes() }
      .decodeToString()
      .replace(pinned, actual)
      .encodeToByteArray()
  val overlay = Files.createTempFile("frozen-runtime-schema-", ".jar")
  DistributionFixture.writeJar(overlay, mapOf(resource to bytes))
  return overlay
}

private fun writePrivateRuntimeBinding(
  operationRoot: Path,
  kind: String,
) {
  val state = Files.createDirectory(operationRoot.resolve("state"))
  val document =
    JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", "private-runtime-binding-v1")
      set(
        "linux",
        JsonNodeFactory.instance.objectNode().apply {
          put("architecture", "arm64")
          put("kernel", "6.11.0")
          put("os", "linux")
        },
      )
      set(
        "substrate",
        JsonNodeFactory.instance.objectNode().apply {
          put("kind", kind)
          when (kind) {
            "controlledMac" -> {
              put("dockerDesktopVersion", "4.44.3")
              put("dockerEngineVersion", "28.3.3")
              put("hardwareModelClass", "Mac16,8")
              put("macosBuild", "24G90")
              put("macosVersion", "15.6.1")
              set("vmResources", advertisedResources())
            }
            "githubHosted" -> {
              set("advertisedResources", advertisedResources())
              put("dockerEngineVersion", "28.3.3")
              put("kernel", "6.11.0")
              put("runnerImageVersion", "20260817.1")
              put("runnerLabel", "ubuntu-24.04-arm")
            }
          }
        },
      )
    }
  Files.write(state.resolve("private-runtime.json"), CanonicalJson.encode(document))
}

private fun advertisedResources() =
  JsonNodeFactory.instance.objectNode().apply {
    put("cpus", 4)
    put("memoryBytes", 6442450944L)
  }

private const val INSTALL_DIST_LIB_PROPERTY = "performance.runner.install-dist-lib"

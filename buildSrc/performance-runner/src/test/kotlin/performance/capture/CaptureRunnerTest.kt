/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import performance.hash.Sha256
import performance.model.CaptureIdentity
import performance.model.EvidenceStatus
import performance.model.ProvisionalEvidenceStrength
import performance.process.ProcessExecutor
import performance.process.ProcessResult
import performance.process.ProcessInvocation
import performance.runner.RunnerExit
import performance.support.DistributionFixture.Companion.EXPECTED_BENCHMARK

class CaptureRunnerTest :
  FunSpec(
    {
      test("valid JMH output yields exact provisional cells and an explicit classpath invocation") {
        withVerifiedDistribution { fixture, distribution ->
          val executor = RecordingProcessExecutor { spec ->
            Files.write(spec.resultPath, validJmhBytes())
            ProcessResult(0)
          }
          val root = fixture.root.resolveSibling("capture-valid")

          val outcome =
            CaptureRunner(executor)
              .capture(CaptureRequest(distribution, testProfile(distribution), identity(), root))
              .shouldBeInstanceOf<CaptureOutcome.Provisional>()

          outcome.document.schemaVersion shouldBe "capture-provisional-v1"
          outcome.document.outcome.status shouldBe EvidenceStatus.VALID
          outcome.document.outcome.strength shouldBe ProvisionalEvidenceStrength.CANARY
          outcome.document.cells.map { it.benchmark } shouldContainExactly listOf(EXPECTED_BENCHMARK)
          outcome.document.cells.single().derivedForkSummaries.single().score.toPlainString() shouldBe
            "1.25"
          outcome.document.artifacts.rawJmhInputSha256 shouldBe Sha256.digest(validJmhBytes())
          outcome.document.javaClass.declaredFields.map { it.name }.contains("qualification") shouldBe
            false
          outcome.document.javaClass.declaredFields.map { it.name }.contains("checksums") shouldBe
            false

          val spec = executor.specs.single()
          spec.executable shouldBe distribution.metadata.classpath.javaRuntime.executable
          spec.classpath shouldContainExactly distribution.benchmarkClasspath
          spec.classpath.joinToString(":") shouldNotContain "*"
          spec.arguments.windowed(2) shouldContain listOf("-foe", "true")
          spec.arguments.windowed(2) shouldContain listOf("-rf", "json")
          spec.arguments.windowed(2) shouldContain listOf("-f", "1")
          spec.arguments.windowed(2) shouldContain listOf("-wi", "0")
          spec.arguments.windowed(2) shouldContain listOf("-i", "1")
          spec.arguments.windowed(2) shouldContain listOf("-bs", "1")
          spec.arguments.windowed(2) shouldContain listOf("-t", "1")
          spec.arguments.windowed(2) shouldContain listOf("-bm", "ss")
          spec.arguments.windowed(2) shouldContain listOf("-tu", "ms")
          spec.arguments.windowed(2) shouldContain listOf("-p", "scenario=fixture")
          spec.arguments.windowed(2) shouldContain
            listOf("-jvm", distribution.metadata.classpath.javaRuntime.executable.toString())
          spec.arguments.contains("-jvmArgsAppend") shouldBe false
          spec.arguments.windowed(2).single { it.first() == "-jvmArgs" }.last() shouldBe
            testProfile(distribution).jvmArguments.joinToString(" ")
          Files.exists(root.resolve("jmh-result.json")) shouldBe true
          Files.exists(root.resolve("checksums.sha256")) shouldBe false
        }
      }

      test("derived nonfinite JMH statistics are discarded while primary observations remain strict") {
        withVerifiedDistribution { fixture, distribution ->
          val raw = resource("valid-derived-nonfinite.json")
          val executor = RecordingProcessExecutor { spec ->
            Files.write(spec.resultPath, raw)
            ProcessResult(0)
          }
          val root = fixture.root.resolveSibling("capture-nonfinite-derived")

          val outcome =
            CaptureRunner(executor)
              .capture(CaptureRequest(distribution, testProfile(distribution), identity(), root))
              .shouldBeInstanceOf<CaptureOutcome.Provisional>()

          outcome.document.artifacts.rawJmhInputSha256 shouldBe Sha256.digest(raw)
          Files.readString(root.resolve("jmh-result.json")) shouldNotContain "NaN"
          outcome.document.cells.single().derivedForkSummaries.single().score.toPlainString() shouldBe
            "1.25"
        }
      }

      test("the complete malformed and contaminated result matrix fails with measurement exit 3") {
        withVerifiedDistribution { fixture, distribution ->
          val valid = validJmhBytes().decodeToString()
          val validRow = valid.trim().removePrefix("[").removeSuffix("]")
          val extraRow =
            validRow.replace(EXPECTED_BENCHMARK, EXTRA_BENCHMARK).replace(
              "\"scenario\":\"fixture\"",
              "\"scenario\":\"extra\"",
            )
          val cases =
            listOf(
              FailureCase("nonzero child exit", CaptureFailure.CHILD_PROCESS_FAILED) { _, _ ->
                ProcessResult(17)
              },
              FailureCase("missing result", CaptureFailure.RESULT_MISSING) { _, _ -> ProcessResult(0) },
              FailureCase("empty result", CaptureFailure.RESULT_EMPTY) { spec, _ ->
                Files.write(spec.resultPath, byteArrayOf())
                ProcessResult(0)
              },
              FailureCase("header-only result", CaptureFailure.RESULT_HEADER_ONLY) { spec, _ ->
                Files.writeString(spec.resultPath, "benchmark,score,scoreUnit\n")
                ProcessResult(0)
              },
              FailureCase("malformed JSON", CaptureFailure.RESULT_MALFORMED) { spec, _ ->
                Files.writeString(spec.resultPath, "[{\"benchmark\":")
                ProcessResult(0)
              },
              FailureCase("zero rows", CaptureFailure.RESULT_HAS_ZERO_ROWS) { spec, _ ->
                Files.writeString(spec.resultPath, "[]")
                ProcessResult(0)
              },
              FailureCase("duplicate row", CaptureFailure.DUPLICATE_RESULT_ROW) { spec, _ ->
                Files.writeString(spec.resultPath, "[$validRow,$validRow]")
                ProcessResult(0)
              },
              FailureCase("missing declared row", CaptureFailure.MISSING_RESULT_ROW) { spec, _ ->
                Files.writeString(spec.resultPath, "[$extraRow]")
                ProcessResult(0)
              },
              FailureCase("extra undeclared row", CaptureFailure.EXTRA_RESULT_ROW) { spec, _ ->
                Files.writeString(spec.resultPath, "[$validRow,$extraRow]")
                ProcessResult(0)
              },
              FailureCase("zero primary observation", CaptureFailure.NONPOSITIVE_PRIMARY_OBSERVATION) {
                  spec,
                  _ ->
                Files.write(spec.resultPath, validJmhBytes(rawObservation = "0"))
                ProcessResult(0)
              },
              FailureCase(
                "negative primary observation",
                CaptureFailure.NONPOSITIVE_PRIMARY_OBSERVATION,
              ) { spec, _ ->
                Files.write(spec.resultPath, validJmhBytes(rawObservation = "-0.01"))
                ProcessResult(0)
              },
              FailureCase("nonfinite primary observation", CaptureFailure.NONFINITE_PRIMARY_OBSERVATION) {
                  spec,
                  _ ->
                Files.write(spec.resultPath, validJmhBytes(rawObservation = "NaN"))
                ProcessResult(0)
              },
              signalFailure(
                "SimpleLogger fallback",
                "org.apache.logging.log4j.simple.SimpleLogger",
                CaptureFailure.LOG4J_FALLBACK,
              ),
              signalFailure(
                "Log4j provider failure",
                "ERROR StatusLogger Unable to locate a logging implementation",
                CaptureFailure.LOG4J_PROVIDER_FAILED,
              ),
              signalFailure(
                "Graal packaging failure",
                "No language and polyglot implementation was found",
                CaptureFailure.GRAAL_PACKAGING_FAILED,
              ),
              signalFailure(
                "scenario invariant failure",
                "REVOMAN_SCENARIO_INVARIANT_FAILED",
                CaptureFailure.SCENARIO_INVARIANT_FAILED,
              ),
              signalFailure(
                "teardown failure",
                "REVOMAN_TEARDOWN_FAILED",
                CaptureFailure.TEARDOWN_FAILED,
              ),
            )

          cases.forEachIndexed { index, case ->
            val executor = RecordingProcessExecutor { spec -> case.run(spec, validJmhBytes()) }
            val root = fixture.root.resolveSibling("capture-invalid-$index")
            val outcome =
              CaptureRunner(executor)
                .capture(CaptureRequest(distribution, testProfile(distribution), identity(index), root))
                .shouldBeInstanceOf<CaptureOutcome.Invalid>()

            withClue(case.name) {
              outcome.reasons shouldContain case.expected
              outcome.exit shouldBe RunnerExit.MEASUREMENT_INVALID
              outcome.toString() shouldNotContain fixture.root.toString()
              outcome.toString() shouldNotContain "StatusLogger"
            }
          }
        }
      }

      test("profile identity protocol distribution and output mismatches reject before launch") {
        withVerifiedDistribution { fixture, distribution ->
          val mismatches =
            listOf(
              "profile" to
                (testProfile(distribution).copy(threads = 2) to
                  identity() to CaptureFailure.PROFILE_MISMATCH),
              "identity" to
                (testProfile(distribution) to
                  identity().copy(sessionSequence = 0) to CaptureFailure.IDENTITY_MISMATCH),
              "protocol" to
                (testProfile(distribution, protocolSha256 = Sha256.parse("b".repeat(64))) to
                  identity() to CaptureFailure.PROTOCOL_MISMATCH),
              "distribution" to
                (testProfile(
                    distribution,
                    expectedCells =
                      ExpectedCells(listOf(ExpectedCell(EXTRA_BENCHMARK, emptyMap()))),
                  ) to
                  identity() to CaptureFailure.DISTRIBUTION_MISMATCH),
            )

          mismatches.forEachIndexed { index, (name, requestParts) ->
            val (profileAndIdentity, expected) = requestParts
            val (profile, requestIdentity) = profileAndIdentity
            val executor = RecordingProcessExecutor { error("must not launch for $name mismatch") }
            val root = fixture.root.resolveSibling("preflight-$index")

            val outcome =
              CaptureRunner(executor)
                .capture(CaptureRequest(distribution, profile, requestIdentity, root))
                .shouldBeInstanceOf<CaptureOutcome.Invalid>()

            withClue(name) {
              outcome.reasons shouldContain expected
              outcome.exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID
              executor.specs.shouldBeEmpty()
              Files.exists(root) shouldBe false
            }
          }

          val existing = fixture.root.resolveSibling("preexisting-output")
          Files.createDirectories(existing)
          Files.writeString(existing.resolve("stale.json"), "stale")
          val executor = RecordingProcessExecutor { error("must not launch for stale output") }
          val staleOutcome =
            CaptureRunner(executor)
              .capture(
                CaptureRequest(distribution, testProfile(distribution), identity(99), existing),
              )
              .shouldBeInstanceOf<CaptureOutcome.Invalid>()
          staleOutcome.reasons shouldContain CaptureFailure.OUTPUT_PATH_INVALID
          staleOutcome.exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID
          executor.specs.shouldBeEmpty()
          Files.readString(existing.resolve("stale.json")) shouldBe "stale"
        }
      }

      test("post-process log sanitization rejects symlinks without following them") {
        withVerifiedDistribution { fixture, distribution ->
          val protected = fixture.root.resolveSibling("protected-log-target")
          Files.writeString(protected, "must-remain-unchanged")
          val executor = RecordingProcessExecutor { spec ->
            Files.write(spec.resultPath, validJmhBytes())
            Files.delete(spec.stderrPath)
            Files.createSymbolicLink(spec.stderrPath, protected)
            ProcessResult(0)
          }

          val outcome =
            CaptureRunner(executor)
              .capture(
                CaptureRequest(
                  distribution,
                  testProfile(distribution),
                  identity(101),
                  fixture.root.resolveSibling("capture-log-symlink"),
                ),
              )
              .shouldBeInstanceOf<CaptureOutcome.Invalid>()

          outcome.reasons shouldContain CaptureFailure.LOG_OUTPUT_INVALID
          outcome.exit shouldBe RunnerExit.MEASUREMENT_INVALID
          Files.readString(protected) shouldBe "must-remain-unchanged"
        }
      }

      test("JFR capture remains provisional and retains the fsynced raw recording and hash") {
        withVerifiedDistribution { fixture, distribution ->
          val profile =
            testProfile(
              distribution,
              family = CaptureProfileFamily.WARM,
              profiler = DiagnosticProfiler.JFR,
            )
          val rawJfr = "private-jfr-recording".encodeToByteArray()
          val root = fixture.root.resolveSibling("capture-jfr")
          val executor = RecordingProcessExecutor { spec ->
            Files.write(
              spec.resultPath,
              validJmhBytes(forks = 10, warmupIterations = 5, measurementIterations = 10),
            )
            val rawProfilerPath = checkNotNull(spec.rawProfilerPath)
            rawProfilerPath shouldBe root.resolve("profile.jfr")
            Files.createDirectories(rawProfilerPath.parent)
            Files.write(rawProfilerPath, rawJfr)
            writeJfrAggregateMarker(root, 10, rawJfr)
            Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
            ProcessResult(0)
          }

          val document =
            CaptureRunner(executor)
              .capture(CaptureRequest(distribution, profile, identity(), root))
              .shouldBeInstanceOf<CaptureOutcome.Provisional>()
              .document

          document.outcome.strength shouldBe ProvisionalEvidenceStrength.DIAGNOSTIC
          document.rawProfilerInputSha256 shouldBe Sha256.digest(rawJfr)
          Files.readAllBytes(checkNotNull(executor.specs.single().rawProfilerPath)) shouldBe rawJfr
          Files.exists(root.resolve(".jfr-aggregate.json")) shouldBe false
          Files.exists(root.resolve(".jfr-aggregate.lock")) shouldBe false
          Files.exists(root.resolve("profiler-summary.json")) shouldBe false
          executor.specs.single().arguments.windowed(2) shouldContain
            listOf(
              "-prof",
              "jfr:dir=$root;configName=profile;debugNonSafePoints=true;stackDepth=1024;postProcessor=$JFR_FORK_ACCUMULATOR;verbose=false",
            )
        }
      }

      test("JFR capture rejects an incomplete or contaminated fork aggregate") {
        withVerifiedDistribution { fixture, distribution ->
          val profile =
            testProfile(
              distribution,
              family = CaptureProfileFamily.WARM,
              profiler = DiagnosticProfiler.JFR,
            )
          val cases =
            listOf<(Path) -> Unit>(
              { root ->
                Files.write(root.resolve("profile.jfr"), byteArrayOf(1))
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 9, raw)
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 10, raw, sha256 = "0".repeat(64))
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 10, raw, byteLength = 2)
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 4_294_967_306L, raw)
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 10, raw, byteLength = "18446744073709551617")
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, "\"10\"", raw)
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 10, raw, byteLength = "\"1\"")
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
              },
              { root ->
                val raw = byteArrayOf(1)
                Files.write(root.resolve("profile.jfr"), raw)
                writeJfrAggregateMarker(root, 10, raw)
                Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
                Files.createDirectories(root.resolve("leftover"))
                Files.write(root.resolve("leftover/profile.jfr"), byteArrayOf(2))
              },
            )

          cases.forEachIndexed { index, arrange ->
            val executor = RecordingProcessExecutor { spec ->
              Files.write(
                spec.resultPath,
                validJmhBytes(forks = 10, warmupIterations = 5, measurementIterations = 10),
              )
              arrange(spec.workingDirectory)
              ProcessResult(0)
            }
            val outcome =
              CaptureRunner(executor)
                .capture(
                  CaptureRequest(
                    distribution,
                    profile,
                    identity(200 + index),
                    fixture.root.resolveSibling("capture-jfr-invalid-$index"),
                  ),
                )
                .shouldBeInstanceOf<CaptureOutcome.Invalid>()

            outcome.reasons shouldContain CaptureFailure.PROFILER_DATA_INVALID
            outcome.exit shouldBe RunnerExit.MEASUREMENT_INVALID
          }
        }
      }

      test("JFR aggregate replacement after binding fails closed and retains both inputs") {
        withVerifiedDistribution { fixture, distribution ->
          val profile =
            testProfile(
              distribution,
              family = CaptureProfileFamily.WARM,
              profiler = DiagnosticProfiler.JFR,
            )
          val root = fixture.root.resolveSibling("capture-jfr-race")
          val originalBytes = "original-jfr".encodeToByteArray()
          val replacementBytes = "replacement-jfr".encodeToByteArray()
          val executor = RecordingProcessExecutor { spec ->
            Files.write(
              spec.resultPath,
              validJmhBytes(forks = 10, warmupIterations = 5, measurementIterations = 10),
            )
            Files.write(root.resolve("profile.jfr"), originalBytes)
            writeJfrAggregateMarker(root, 10, originalBytes)
            Files.writeString(root.resolve(".jfr-aggregate.lock"), "")
            ProcessResult(0)
          }
          val runner =
            CaptureRunner(
              processExecutor = executor,
              jfrHooks =
                JfrCaptureHooks(
                  afterBindingBeforeHash = {
                    Files.move(root.resolve("profile.jfr"), root.resolve("original.jfr"))
                    Files.write(root.resolve("profile.jfr"), replacementBytes)
                  },
                ),
            )

          val outcome =
            runner
              .capture(CaptureRequest(distribution, profile, identity(300), root))
              .shouldBeInstanceOf<CaptureOutcome.Invalid>()

          outcome.reasons shouldContain CaptureFailure.PROFILER_DATA_INVALID
          Files.readAllBytes(root.resolve("profile.jfr")) shouldBe replacementBytes
          Files.readAllBytes(root.resolve("original.jfr")) shouldBe originalBytes
          Files.exists(root.resolve(".jfr-aggregate.json")) shouldBe true
          Files.exists(root.resolve(".jfr-aggregate.lock")) shouldBe true
          Files.exists(root.resolve(".jfr-capture-input.bound")) shouldBe false
        }
      }
    },
  )

private data class FailureCase(
  val name: String,
  val expected: CaptureFailure,
  val run: (ProcessInvocation, ByteArray) -> ProcessResult,
)

private fun signalFailure(name: String, signal: String, expected: CaptureFailure): FailureCase =
  FailureCase(name, expected) { spec, valid ->
    Files.write(spec.resultPath, valid)
    Files.writeString(spec.stderrPath, signal)
    ProcessResult(0)
  }

private class RecordingProcessExecutor(
  private val action: (ProcessInvocation) -> ProcessResult,
) : ProcessExecutor {
  val specs = mutableListOf<ProcessInvocation>()

  override fun execute(spec: ProcessInvocation): ProcessResult {
    specs += spec
    return action(spec)
  }
}

private fun identity(index: Int = 1): CaptureIdentity =
  CaptureIdentity(
    captureId = "capture-$index",
    processRunId = "process-$index",
    performanceSessionId = "session-$index",
    sessionSequence = index + 1,
  )

private fun resource(name: String): ByteArray =
  checkNotNull(CaptureRunnerTest::class.java.getResourceAsStream("/performance/jmh/$name")) {
    "missing JMH fixture $name"
  }.use { it.readAllBytes() }

private fun writeJfrAggregateMarker(
  root: Path,
  completedForks: Any,
  raw: ByteArray,
  byteLength: Any = raw.size,
  sha256: String = Sha256.digest(raw).hex,
) {
  Files.writeString(
    root.resolve(".jfr-aggregate.json"),
    """{"byteLength":$byteLength,"completedForks":$completedForks,"schemaVersion":"jfr-fork-aggregate-v1","sha256":"$sha256"}
""",
  )
}

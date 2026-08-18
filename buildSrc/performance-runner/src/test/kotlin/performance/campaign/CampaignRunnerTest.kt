/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import performance.capture.CaptureFailure
import performance.capture.CaptureOutcome
import performance.capture.CaptureProfile
import performance.capture.CaptureProfileFamily
import performance.capture.CaptureRequest
import performance.capture.CaptureRunner
import performance.capture.DiagnosticProfiler
import performance.capture.testProfile
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidator
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.model.ProvisionalCaptureDocument
import performance.process.ProcessExecutor
import performance.process.ProcessResult
import performance.process.ProcessInvocation
import performance.support.DistributionFixture
import performance.support.DistributionFixture.Companion.EXPECTED_BENCHMARK

class CampaignRunnerTest :
  FunSpec(
    {
      test("a ten-fork miss creates a fresh twenty-fork pair and uses its A2 for B") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val scripts =
            listOf(
              CaptureScript.constant(10.0),
              CaptureScript.constant(20.0),
              CaptureScript.constant(10.0),
              CaptureScript.constant(10.0),
              CaptureScript.constant(9.0),
            )
          val outcome = fixture.run(scripts, ledger)

          outcome.status shouldBe CampaignStatus.QUALIFIED
          outcome.captures.map { it.role to it.forks } shouldContainExactly
            listOf(
              CaptureRole.BASELINE_A1 to 10,
              CaptureRole.BASELINE_A2 to 10,
              CaptureRole.BASELINE_A1 to 20,
              CaptureRole.BASELINE_A2 to 20,
              CaptureRole.CANDIDATE_B to 20,
            )
          outcome.preconditioningReceipts.map(PreconditioningReceipt::role) shouldContainExactly
            outcome.captures.map(CampaignCapture::role)
          outcome.captures.map(CampaignCapture::preconditioningReceipt) shouldContainExactly
            outcome.preconditioningReceipts
          outcome.preconditioningReceipts.all { receipt ->
            receipt.files.isNotEmpty() && receipt.settleDuration == Duration.ofSeconds(10)
          } shouldBe true
          ledger shouldContainExactly
            listOf("P(A1)", "A1", "P(A2)", "A2", "P(A1)", "A1", "P(A2)", "A2", "P(B)", "B")
          outcome.selectedA1?.forks shouldBe 20
          outcome.selectedA2?.forks shouldBe 20
          outcome.candidate?.forks shouldBe 20
          outcome.comparisonSelection?.baselineA2CaptureId shouldBe
            outcome.selectedA2?.identity?.captureId
          outcome.comparisonSelection?.candidateBCaptureId shouldBe
            outcome.candidate?.identity?.captureId
          outcome.comparisonSelection?.baselineA2CaptureId shouldBeNot
            outcome.selectedA1?.identity?.captureId
        }
      }

      test("a twenty-fork miss creates a fresh forty-fork pair before B") {
        withCampaignFixture { fixture ->
          val outcome =
            fixture.run(
              scripts =
                listOf(
                  CaptureScript.constant(10.0),
                  CaptureScript.constant(20.0),
                  CaptureScript.constant(10.0),
                  CaptureScript.constant(20.0),
                  CaptureScript.constant(10.0),
                  CaptureScript.constant(10.0),
                  CaptureScript.constant(9.0),
                ),
            )

          outcome.status shouldBe CampaignStatus.QUALIFIED
          outcome.captures.map(CampaignCapture::forks) shouldContainExactly
            listOf(10, 10, 20, 20, 40, 40, 40)
          outcome.selectedA2?.identity?.sessionSequence shouldBe 6
          outcome.candidate?.identity?.sessionSequence shouldBe 7
        }
      }

      test("a forty-fork miss exhausts calibration and never launches B") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome =
            fixture.run(
              scripts =
                List(3) { listOf(CaptureScript.constant(10.0), CaptureScript.constant(20.0)) }
                  .flatten(),
              ledger = ledger,
            )

          outcome.status shouldBe CampaignStatus.CALIBRATION_EXHAUSTED
          outcome.captures.map(CampaignCapture::role) shouldContainExactly
            listOf(
              CaptureRole.BASELINE_A1,
              CaptureRole.BASELINE_A2,
              CaptureRole.BASELINE_A1,
              CaptureRole.BASELINE_A2,
              CaptureRole.BASELINE_A1,
              CaptureRole.BASELINE_A2,
            )
          outcome.candidate shouldBe null
          outcome.comparisonSelection shouldBe null
          ledger.none { it == "P(B)" || it == "B" } shouldBe true
        }
      }

      test("the first passing pair launches exactly one candidate") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome =
            fixture.run(
              listOf(
                CaptureScript.constant(10.0),
                CaptureScript.constant(10.0),
                CaptureScript.constant(9.0),
              ),
              ledger,
            )

          ledger shouldContainExactly listOf("P(A1)", "A1", "P(A2)", "A2", "P(B)", "B")
          outcome.captures.count { it.role == CaptureRole.CANDIDATE_B } shouldBe 1
        }
      }

      test("selected A1 A2 and B are consecutive in one generated session") {
        withCampaignFixture { fixture ->
          val outcome =
            fixture.run(
              listOf(
                CaptureScript.constant(10.0),
                CaptureScript.constant(20.0),
                CaptureScript.constant(10.0),
                CaptureScript.constant(10.0),
                CaptureScript.constant(9.0),
              ),
            )
          val selected = listOf(outcome.selectedA1, outcome.selectedA2, outcome.candidate).map(::checkNotNull)

          selected.map { it.identity.sessionSequence } shouldContainExactly listOf(3, 4, 5)
          selected.map { it.identity.performanceSessionId }.distinct().size shouldBe 1
          outcome.captures.map { it.identity.captureId }.distinct().size shouldBe outcome.captures.size
          outcome.captures.map { it.identity.processRunId }.distinct().size shouldBe outcome.captures.size
          outcome.captures.map(CampaignCapture::attemptId).distinct() shouldContainExactly
            listOf("warm-10-1", "warm-20-2")
          outcome.captures.take(2).all { !it.selected } shouldBe true
        }
      }

      test("a point-preserving wide interval escalates instead of authorizing B") {
        withCampaignFixture { fixture ->
          val wide = CaptureScript(List(5) { 5.0 } + List(5) { 15.0 })
          val outcome =
            fixture.run(
              listOf(
                CaptureScript.constant(10.0),
                wide,
                CaptureScript.constant(10.0),
                CaptureScript.constant(10.0),
                CaptureScript.constant(9.0),
              ),
            )

          outcome.selectedA1?.forks shouldBe 20
        }
      }

      test("calibration uses raw per-fork medians rather than persisted arithmetic means") {
        withCampaignFixture { fixture ->
          val baseline = CaptureScript(List(10) { 10.0 }, observations = List(9) { 1.0 } + 91.0)
          val candidate = CaptureScript(List(10) { 10.0 }, observations = List(9) { 2.0 } + 82.0)
          val outcome =
            fixture.run(
              listOf(
                baseline,
                candidate,
                CaptureScript.constant(10.0),
                CaptureScript.constant(10.0),
                CaptureScript.constant(9.0),
              ),
            )

          outcome.selectedA1?.forks shouldBe 20
        }
      }

      test("an invalid capture stops the state machine immediately") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome = fixture.run(listOf(CaptureScript.invalid()), ledger)

          outcome.status shouldBe CampaignStatus.INVALID
          ledger shouldContainExactly listOf("P(A1)", "A1")
          outcome.reason shouldBe CampaignFailure.CAPTURE_INVALID
        }
      }

      test("a contaminated provisional capture stops the state machine immediately") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome =
            fixture.run(
              listOf(CaptureScript.constant(10.0, contaminateProfiler = true)),
              ledger,
            )

          outcome.status shouldBe CampaignStatus.INVALID
          ledger shouldContainExactly listOf("P(A1)", "A1")
          outcome.reason shouldBe CampaignFailure.CAPTURE_CONTAMINATED
        }
      }

      test("a profile-mismatched provisional capture stops the state machine immediately") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome =
            fixture.run(
              listOf(CaptureScript.constant(10.0, contaminateProfile = true)),
              ledger,
            )

          outcome.status shouldBe CampaignStatus.INVALID
          ledger shouldContainExactly listOf("P(A1)", "A1")
          outcome.reason shouldBe CampaignFailure.CAPTURE_CONTAMINATED
        }
      }

      test("an artifact-mismatched provisional capture stops the state machine immediately") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome =
            fixture.run(
              listOf(CaptureScript.constant(10.0, contaminateArtifacts = true)),
              ledger,
            )

          outcome.status shouldBe CampaignStatus.INVALID
          ledger shouldContainExactly listOf("P(A1)", "A1")
          outcome.reason shouldBe CampaignFailure.CAPTURE_CONTAMINATED
        }
      }

      test("a precondition failure stops before the matching capture") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          val outcome = fixture.run(
            scripts = listOf(CaptureScript.constant(10.0)),
            ledger = ledger,
            receiptMutation = { _, _ -> error("precondition failed") },
          )

          outcome.status shouldBe CampaignStatus.INVALID
          ledger shouldContainExactly listOf("P(A1)")
          outcome.reason shouldBe CampaignFailure.PRECONDITION_FAILED
        }
      }

      test("every invalid receipt stops the state machine before its fork") {
        val mutations =
          listOf<(CampaignFixture, PreconditioningReceipt) -> PreconditioningReceipt>(
            { _, receipt -> receipt.copy(files = receipt.files.drop(1)) },
            { _, receipt -> receipt.copy(files = receipt.files.reversed()) },
            { fixture, receipt -> fixture.corruptBaselineAfterReceipt().let { receipt } },
            { _, receipt -> receipt.copy(role = CaptureRole.CANDIDATE_B) },
            { fixture, receipt ->
              receipt.copy(distributionRoot = fixture.root.resolveSibling("wrong-root"))
            },
            { _, receipt -> receipt.copy(settleDuration = Duration.ofSeconds(9)) },
            { _, receipt -> receipt.copy(sequence = receipt.sequence + 7) },
          )
        mutations.forEach { mutation ->
          withCampaignFixture { fixture ->
            val ledger = mutableListOf<String>()
            val outcome =
              fixture.run(
                scripts = emptyList(),
                ledger = ledger,
                receiptMutation = { receipt, _ -> mutation(fixture, receipt) },
              )

            outcome.status shouldBe CampaignStatus.INVALID
            outcome.reason shouldBe CampaignFailure.RECEIPT_INVALID
            outcome.captures shouldBe emptyList()
            outcome.preconditioningReceipts shouldBe emptyList()
            ledger shouldContainExactly listOf("P(A1)")
          }
        }
      }

      test("an observed duration breach remains invalid after the clock moves backward") {
        withCampaignFixture { fixture ->
          val clock =
            ScriptedClock(
              listOf(
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH.plus(Duration.ofHours(2)).plusSeconds(1),
                Instant.EPOCH,
              ),
            )
          val ledger = mutableListOf<String>()
          val outcome = fixture.run(listOf(CaptureScript.constant(10.0)), ledger, clock = clock)

          outcome.status shouldBe CampaignStatus.INVALID
          outcome.reason shouldBe CampaignFailure.SESSION_DURATION_EXCEEDED
          ledger shouldContainExactly listOf("P(A1)", "A1")
        }
      }

      test("profile ladder keys must equal the profile fork counts") {
        withCampaignFixture { fixture ->
          shouldThrow<IllegalArgumentException> { fixture.mismatchedForkFamily() }
        }
      }

      test("a duration breach before preconditioning launches nothing") {
        withCampaignFixture { fixture ->
          val clock =
            ScriptedClock(
              listOf(Instant.EPOCH, Instant.EPOCH.plus(Duration.ofHours(2)).plusSeconds(1)),
            )
          val ledger = mutableListOf<String>()
          val outcome = fixture.run(emptyList(), ledger, clock = clock)

          outcome.status shouldBe CampaignStatus.INVALID
          outcome.reason shouldBe CampaignFailure.SESSION_DURATION_EXCEEDED
          ledger shouldBe emptyList()
        }
      }

      test("a duration breach after preconditioning stops before capture") {
        withCampaignFixture { fixture ->
          val clock =
            ScriptedClock(
              listOf(
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH.plus(Duration.ofHours(2)).plusSeconds(1),
              ),
            )
          val ledger = mutableListOf<String>()
          val outcome = fixture.run(emptyList(), ledger, clock = clock)

          outcome.status shouldBe CampaignStatus.INVALID
          outcome.reason shouldBe CampaignFailure.SESSION_DURATION_EXCEEDED
          ledger shouldContainExactly listOf("P(A1)")
        }
      }

      test("a diagnostic profiler family is rejected before preconditioning") {
        withCampaignFixture { fixture ->
          val ledger = mutableListOf<String>()
          shouldThrow<IllegalArgumentException> {
            fixture.profileFamily(DiagnosticProfiler.GC)
          }
          ledger shouldBe emptyList()
        }
      }

      test("the approved CaptureRunner constructor executes real captures") {
        withCampaignFixture { fixture ->
          val scripts = ArrayDeque(listOf(CaptureScript.constant(10.0), CaptureScript.constant(10.0), CaptureScript.constant(9.0)))
          var processCalls = 0
          val executor = ProcessExecutor { spec ->
            processCalls += 1
            writeJmhResult(spec, scripts.removeFirst())
          }
          val clock = MutableClock()
          val preconditioner = DefaultRolePreconditioner(sleeper = {})
          val runner =
            CampaignRunner(
              captureRunner = CaptureRunner(executor, clock),
              calibrationEvaluator = ProvisionalCalibrationEvaluator(),
              rolePreconditioner = preconditioner,
              clock = clock,
            )

          val outcome = runner.run(fixture.request())

          outcome.status shouldBe CampaignStatus.QUALIFIED
          processCalls shouldBe 3
        }
      }

      test("a campaign runner is explicitly single-use") {
        withCampaignFixture { fixture ->
          val scripts =
            ArrayDeque(
              listOf(
                CaptureScript.constant(10.0),
                CaptureScript.constant(10.0),
                CaptureScript.constant(9.0),
              ),
            )
          val clock = MutableClock()
          val runner =
            CampaignRunner(
              captureRunner =
                CaptureRunner(
                  ProcessExecutor { spec -> writeJmhResult(spec, scripts.removeFirst()) },
                  clock,
                ),
              calibrationEvaluator = ProvisionalCalibrationEvaluator(),
              rolePreconditioner = DefaultRolePreconditioner(sleeper = {}),
              clock = clock,
            )

          runner.run(fixture.request()).status shouldBe CampaignStatus.QUALIFIED
          val secondRoot = Files.createDirectory(fixture.root.resolve("second"))
          shouldThrow<IllegalStateException> {
            runner.run(
              fixture.request(
                provisionalRoot = secondRoot,
                session = SessionIdentity.fixed("campaign-second", "session-second"),
              ),
            )
          }
        }
      }
    },
  )

private data class CaptureScript(
  val forkValues: List<Double>,
  val observations: List<Double>? = null,
  val exitCode: Int = 0,
  val contaminateProfiler: Boolean = false,
  val contaminateProfile: Boolean = false,
  val contaminateArtifacts: Boolean = false,
) {
  companion object {
    fun constant(
      value: Double,
      contaminateProfiler: Boolean = false,
      contaminateProfile: Boolean = false,
      contaminateArtifacts: Boolean = false,
    ): CaptureScript =
      CaptureScript(
        forkValues = List(40) { value },
        contaminateProfiler = contaminateProfiler,
        contaminateProfile = contaminateProfile,
        contaminateArtifacts = contaminateArtifacts,
      )

    fun invalid(): CaptureScript = CaptureScript(emptyList(), exitCode = 17)
  }
}

private class CampaignFixture(
  private val baselineFixture: DistributionFixture,
  private val candidateFixture: DistributionFixture,
  val baseline: VerifiedDistribution,
  val candidate: VerifiedDistribution,
  val root: Path,
) {
  fun profileFamily(profiler: DiagnosticProfiler = DiagnosticProfiler.NONE): ProfileFamily =
    ProfileFamily.create(
      family = CaptureProfileFamily.WARM,
      baselineProfiles = profiles(baseline, profiler),
      candidateProfiles = profiles(candidate, profiler),
    )

  fun mismatchedForkFamily(): ProfileFamily {
    val baselineProfiles = profiles(baseline, DiagnosticProfiler.NONE).toMutableMap()
    val candidateProfiles = profiles(candidate, DiagnosticProfiler.NONE).toMutableMap()
    baselineProfiles[10] = baselineProfiles.getValue(20)
    candidateProfiles[10] = candidateProfiles.getValue(20)
    return ProfileFamily.create(
      family = CaptureProfileFamily.WARM,
      baselineProfiles = baselineProfiles,
      candidateProfiles = candidateProfiles,
    )
  }

  fun request(
    provisionalRoot: Path = root,
    session: SessionIdentity = SessionIdentity.fixed("campaign-test", "session-test"),
  ): CampaignRequest =
    CampaignRequest(
      baseline = baseline,
      candidate = candidate,
      profileFamily = profileFamily(),
      session = session,
      provisionalRoot = provisionalRoot,
      regressionPolicy = null,
    )

  fun corruptBaselineAfterReceipt() {
    baselineFixture.writeWithoutResealing(DistributionFixture.PRODUCTION_JAR, byteArrayOf(9))
  }

  fun run(
    scripts: List<CaptureScript>,
    ledger: MutableList<String> = mutableListOf(),
    clock: Clock = MutableClock(),
    receiptMutation: (PreconditioningReceipt, Int) -> PreconditioningReceipt = { receipt, _ -> receipt },
  ): CampaignProvisionalOutcome {
    val queue = ArrayDeque(scripts)
    val delegate = DefaultRolePreconditioner(sleeper = {})
    var receiptIndex = 0
    val preconditioner = RolePreconditioner { role, distribution ->
      ledger += "P(${role.shortName})"
      receiptMutation(delegate.prepare(role, distribution), receiptIndex++)
    }
    val port = CampaignCapturePort { role, request ->
      ledger += role.shortName
      val script = queue.removeFirst()
      if (script.exitCode != 0) {
        CaptureOutcome.Invalid(listOf(CaptureFailure.CHILD_PROCESS_FAILED))
      } else {
        val outcome =
          CaptureRunner(
              ProcessExecutor { spec -> writeJmhResult(spec, script) },
              clock,
            )
            .capture(request)
        when {
          script.contaminateProfiler && outcome is CaptureOutcome.Provisional ->
            CaptureOutcome.Provisional(
              outcome.document.copy(
                profile = outcome.document.profile.copy(profiler = DiagnosticProfiler.GC.id),
              ),
            )
          script.contaminateProfile && outcome is CaptureOutcome.Provisional ->
            CaptureOutcome.Provisional(
              outcome.document.copy(
                profile = outcome.document.profile.copy(identity = "wrong-profile"),
              ),
            )
          script.contaminateArtifacts && outcome is CaptureOutcome.Provisional ->
            CaptureOutcome.Provisional(
              outcome.document.copy(
                artifacts =
                  outcome.document.artifacts.copy(
                    production =
                      outcome.document.artifacts.production.copy(
                        sha256 = Sha256.parse("f".repeat(64)),
                      ),
                  ),
              ),
            )
          else -> outcome
        }
      }
    }
    return CampaignRunner.forTesting(
        capturePort = port,
        calibrationEvaluator = ProvisionalCalibrationEvaluator(),
        rolePreconditioner = preconditioner,
        clock = clock,
      )
      .run(request())
  }

  private fun profiles(
    distribution: VerifiedDistribution,
    profiler: DiagnosticProfiler,
  ): Map<Int, CaptureProfile> =
    listOf(10, 20, 40).associateWith { forks ->
      testProfile(
        distribution = distribution,
        family = CaptureProfileFamily.WARM,
        profiler = profiler,
        forks = forks,
        warmupIterations = 5,
        measurementIterations = 10,
      )
    }
}

private fun writeJmhResult(spec: ProcessInvocation, script: CaptureScript): ProcessResult {
  if (script.exitCode != 0) return ProcessResult(script.exitCode)
  val forks = spec.arguments.valueAfter("-f").toInt()
  val iterations = spec.arguments.valueAfter("-i").toInt()
  val values = script.forkValues.take(forks)
  require(values.size == forks)
  val rawData =
    values.joinToString(prefix = "[", postfix = "]") { forkValue ->
      val observations = script.observations ?: List(iterations) { forkValue }
      require(observations.size == iterations)
      observations.joinToString(prefix = "[", postfix = "]")
    }
  val bytes =
    """
      [{
        "benchmark":"$EXPECTED_BENCHMARK",
        "mode":"ss",
        "threads":1,
        "forks":$forks,
        "warmupIterations":5,
        "warmupTime":"1 s",
        "warmupBatchSize":1,
        "measurementIterations":$iterations,
        "measurementTime":"1 s",
        "measurementBatchSize":1,
        "params":{"scenario":"fixture"},
        "primaryMetric":{
          "score":1,
          "scoreError":0,
          "scoreConfidence":[1,1],
          "scorePercentiles":{"0.0":1,"100.0":1},
          "scoreUnit":"ms/op",
          "rawData":$rawData
        },
        "secondaryMetrics":{}
      }]
    """.trimIndent().encodeToByteArray()
  Files.write(spec.resultPath, bytes)
  return ProcessResult(0)
}

private fun List<String>.valueAfter(flag: String): String = get(indexOf(flag) + 1)

private inline fun withCampaignFixture(block: (CampaignFixture) -> Unit) {
  val baselineFixture = DistributionFixture.create()
  val candidateFixture = DistributionFixture.create()
  val root = Files.createTempDirectory("campaign-runner-test-")
  try {
    val baseline =
      (DistributionValidator().validate(baselineFixture.request()) as DistributionValidation.Valid)
        .distribution
    val candidate =
      (DistributionValidator().validate(candidateFixture.request()) as DistributionValidation.Valid)
        .distribution
    block(CampaignFixture(baselineFixture, candidateFixture, baseline, candidate, root))
  } finally {
    baselineFixture.close()
    candidateFixture.close()
    root.toFile().deleteRecursively()
  }
}

private class MutableClock(
  private var current: Instant = Instant.EPOCH,
) : Clock() {
  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = current

  fun advance(duration: Duration) {
    current = current.plus(duration)
  }
}

private class ScriptedClock(
  instants: List<Instant>,
) : Clock() {
  private val values = ArrayDeque(instants)
  private var last = instants.last()

  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = if (values.isEmpty()) last else values.removeFirst().also { last = it }
}

private infix fun <T> T?.shouldBeNot(other: T?) {
  (this == other) shouldBe false
}

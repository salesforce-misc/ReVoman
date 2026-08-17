/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.nio.file.Path
import performance.hash.Sha256
import performance.model.AdvertisedResources
import performance.model.ArtifactIdentity
import performance.model.CaptureArtifacts
import performance.model.CaptureCell
import performance.model.CaptureDocument
import performance.model.CaptureIdentity
import performance.model.CaptureOutcome
import performance.model.CaptureProfileIdentity
import performance.model.DependencyIdentity
import performance.model.EvidenceStatus
import performance.model.EvidenceStrength
import performance.model.FinalOutcomeReason
import performance.model.ForkSummary
import performance.model.GitProvenance
import performance.model.HostDocumentRef
import performance.model.JdkIdentity
import performance.model.JmhResultRowRef
import performance.model.LinuxIdentity
import performance.model.LoggingProfileIdentity
import performance.model.NetworkIdentity
import performance.model.OciIdentity
import performance.model.PrimaryMetricIdentity
import performance.model.ProtocolIdentity
import performance.model.ProvenanceRoles
import performance.model.QualificationEvidence
import performance.model.RuntimeIdentity
import performance.model.RuntimeLimits
import performance.model.SampleDimensions
import performance.model.SecurityIdentity
import performance.model.StorageIdentity
import performance.model.SubstrateIdentity
import performance.model.ToolchainIdentity
import performance.runner.RunnerExit
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

class CaptureComparatorTest :
  FunSpec(
    {
      test("compatible A/A captures produce deterministic diagnostic estimates") {
        val computation = CaptureComparator().compare(calibrationRequest())

        val completed = computation.shouldBeInstanceOf<ComparisonComputation.Completed>()
        completed.exit shouldBe RunnerExit.SUCCESS
        completed.document.kind shouldBe ComparisonKind.CALIBRATION
        completed.document.strength shouldBe ComparisonStrength.DIAGNOSTIC
        completed.document.compatibility shouldBe ComparisonCompatibility.COMPATIBLE
        completed.document.cells.single().estimate.pointRatio shouldBe 1.0
        completed.document.cells.single().direction shouldBe DirectionOutcome.INCONCLUSIVE
        completed.document.cells.single().policy shouldBe PolicyOutcome.NOT_ENFORCED
        completed.document.calibration?.passed shouldBe true
      }

      test("bundle proof rejects unsealed, schema-invalid, and checksum-invalid inputs") {
        BundleVerificationFailure.entries.forEach { failure ->
          val request =
            calibrationRequest(
              baseline = CaptureBundleProof.rejected(Path.of("/capture/a1"), listOf(failure))
            )

          val incompatible =
            CaptureComparator().compare(request).shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          incompatible.exit shouldBe RunnerExit.INCOMPATIBLE
          incompatible.reasons shouldContain failure.compatibilityFailure
        }
      }

      test("incompatibility renders enumerated reasons without creating estimates") {
        val base = verifiedCapture("a1", "process-a1", 1)

        val incompatible =
          CaptureComparator()
            .compare(calibrationRequest(base, base))
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()

        incompatible.estimatesCreated shouldBe false
        incompatible.reasons shouldContain CompatibilityFailure.SAME_CAPTURE
        incompatible.jsonBytes.decodeToString() shouldContainText "\"same-capture\""
        incompatible.jsonBytes.decodeToString() shouldNotContain "\"estimate\""
        incompatible.markdownBytes.decodeToString() shouldContainText "same-capture"
        EvidenceSchemaValidator()
          .validate(SchemaKind.COMPARISON, incompatible.jsonBytes)
          .shouldBeEmpty()
      }

      test("capture-owned host document references may differ without changing compatibility") {
        val candidate = verifiedCapture("a2", "process-a2", 2)
        val qualification =
          candidate.document.qualification.shouldBeInstanceOf<QualificationEvidence.GithubHosted>()
        val independentlyObserved =
          candidate.copy(
            document =
              candidate.document.copy(
                qualification =
                  qualification.copy(
                    setup = HostDocumentRef("setup-a2.json", sha('8')),
                    cleanup = HostDocumentRef("cleanup-a2.json", sha('9')),
                  )
              )
          )

        CaptureComparator()
          .compare(calibrationRequest(candidate = independentlyObserved))
          .shouldBeInstanceOf<ComparisonComputation.Completed>()
      }

      test("each capture qualification policy must bind its own protocol identity") {
        fun withWrongQualificationPolicy(
          capture: CaptureBundleProof.Verified
        ): CaptureBundleProof.Verified {
          val qualification =
            capture.document.qualification.shouldBeInstanceOf<QualificationEvidence.GithubHosted>()
          return capture.copy(
            document =
              capture.document.copy(
                qualification = qualification.copy(policyHash = sha('e'))
              )
          )
        }

        val incompatible =
          CaptureComparator()
            .compare(
              calibrationRequest(
                baseline = withWrongQualificationPolicy(verifiedCapture("a1", "process-a1", 1)),
                candidate = withWrongQualificationPolicy(verifiedCapture("a2", "process-a2", 2)),
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()

        incompatible.reasons shouldContain CompatibilityFailure.QUALIFICATION_POLICY_MISMATCH
      }

      test("normalizes the capture direction to the frozen cell identity token") {
        val capture = verifiedCapture("a1", "process-a1", 1)

        CaptureCompatibility.cellIdentity(capture.document, capture.document.cells.single()) shouldBe
          testCellIdentity()
      }

      test("malformed empty fork samples fail compatibility without escaping an exception") {
        val candidate = verifiedCapture("a2", "process-a2", 2)
        val malformed =
          candidate.copy(samples = mapOf(testCellIdentity() to List(10) { ForkSamples(emptyList()) }))

        val incompatible =
          CaptureComparator()
            .compare(calibrationRequest(candidate = malformed))
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
        incompatible.estimatesCreated shouldBe false
        incompatible.reasons shouldContain CompatibilityFailure.SAMPLE_DIMENSION_MISMATCH
      }

      test("validates persisted fork means independently from estimator fork medians") {
        val candidate = verifiedCapture("a2", "process-a2", 2)
        val observations = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0)
        val documentCell = candidate.document.cells.single()
        val meanBound =
          candidate.copy(
            document =
              candidate.document.copy(
                cells =
                  listOf(
                    documentCell.copy(
                      derivedForkSummaries =
                        documentCell.derivedForkSummaries.mapIndexed { index, summary ->
                          if (index == 0) summary.copy(score = BigDecimal("14.5")) else summary
                        }
                    )
                  )
              ),
            samples =
              mapOf(
                testCellIdentity() to
                  candidate.samples.getValue(testCellIdentity()).mapIndexed { index, samples ->
                    if (index == 0) ForkSamples(observations) else samples
                  }
              ),
          )

        CaptureComparator()
          .compare(calibrationRequest(candidate = meanBound))
          .shouldBeInstanceOf<ComparisonComputation.Completed>()
      }

      test("rejects identity, profiler, sample, cell, and immutable-key incompatibilities") {
        val base = verifiedCapture("a1", "process-a1", 1)
        val cases =
          listOf(
            base to CompatibilityFailure.SAME_CAPTURE,
            verifiedCapture("a2", "process-a2", 2, profiler = "jfr") to
              CompatibilityFailure.PROFILER_PRESENT,
            verifiedCapture("a2", "process-a2", 2, sample = 0.0) to
              CompatibilityFailure.INVALID_PRIMARY_SAMPLE,
            verifiedCapture("a2", "process-a2", 2, forkCount = 9) to
              CompatibilityFailure.UNDERSAMPLED_CELL,
            verifiedCapture("a2", "process-a2", 2, qualificationSha = sha('e')) to
              CompatibilityFailure.QUALIFICATION_POLICY_MISMATCH,
            verifiedCapture("a2", "process-a2", 2, runtimeHost = "other-host") to
              CompatibilityFailure.RUNTIME_MISMATCH,
            verifiedCapture("a2", "process-a2", 2, comparatorSha = sha('e')) to
              CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH,
          )

        cases.forEach { (candidate, expected) ->
          val request = calibrationRequest(base, candidate)
          val incompatible =
            CaptureComparator().compare(request).shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          incompatible.reasons shouldContain expected
          incompatible.estimatesCreated shouldBe false
        }
      }

      test("rejects every frozen execution and capture identity mismatch before arithmetic") {
        val candidate = verifiedCapture("a2", "process-a2", 2)
        val changedCell = testCellIdentity().copy(benchmark = "example.Changed.benchmark")
        val originalCell = candidate.document.cells.single()
        val cases =
          listOf(
            candidate.copy(
              manifest = candidate.manifest.copy(captureSha256 = sha('e'))
            ) to CompatibilityFailure.BUNDLE_MANIFEST_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  protocol = candidate.document.protocol.copy(hostAdapterSha256 = sha('e'))
                )
            ) to CompatibilityFailure.PROTOCOL_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  artifacts =
                    candidate.document.artifacts.copy(
                      executingRunner = ArtifactIdentity("lib/performance-runner.jar", sha('e'))
                    )
                )
            ) to CompatibilityFailure.ARTIFACT_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  toolchain = candidate.document.toolchain.copy(jmhCoreVersion = "1.36")
                )
            ) to CompatibilityFailure.TOOLCHAIN_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  runtime =
                    candidate.document.runtime.copy(
                      jdk = candidate.document.runtime.jdk.copy(version = "21.0.10+7-LTS")
                    )
                )
            ) to CompatibilityFailure.RUNTIME_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  logging = candidate.document.logging.copy(configurationSha256 = sha('e'))
                )
            ) to CompatibilityFailure.LOGGING_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  profile = candidate.document.profile.copy(variantSha256 = sha('e'))
                )
            ) to CompatibilityFailure.PROFILE_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  cells = listOf(originalCell.copy(benchmark = changedCell.benchmark))
                ),
              samples = mapOf(changedCell to candidate.samples.getValue(testCellIdentity())),
            ) to CompatibilityFailure.CELL_IDENTITY_MISMATCH,
            candidate.copy(
              document =
                candidate.document.copy(
                  cells =
                    listOf(
                      originalCell.copy(
                        derivedForkSummaries =
                          originalCell.derivedForkSummaries.mapIndexed { index, summary ->
                            if (index == 0) summary.copy(score = BigDecimal("11")) else summary
                          }
                      )
                    )
                )
            ) to CompatibilityFailure.DERIVED_SUMMARY_MISMATCH,
          )

        cases.forEach { (changed, expected) ->
          val incompatible =
            CaptureComparator()
              .compare(calibrationRequest(candidate = changed))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          incompatible.estimatesCreated shouldBe false
          incompatible.reasons shouldContain expected
        }
      }

      test("calibration requires same treatment and complete distribution identity") {
        val treatmentMismatch =
          CaptureComparator()
            .compare(
              calibrationRequest(
                candidate =
                  verifiedCapture(
                    "a2",
                    "process-a2",
                    2,
                    treatmentSha = "b".repeat(40),
                  )
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
        treatmentMismatch.reasons shouldContain CompatibilityFailure.CALIBRATION_TREATMENT_MISMATCH

        val artifactMismatch =
          CaptureComparator()
            .compare(
              calibrationRequest(
                candidate = verifiedCapture("a2", "process-a2", 2, productionSha = sha('b'))
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
        artifactMismatch.reasons shouldContain CompatibilityFailure.CALIBRATION_DISTRIBUTION_MISMATCH
      }

      test("calibration and candidate roles require one session with consecutive A1 A2 B order") {
        val a2 = verifiedCapture("a2", "process-a2", 2)
        listOf(
            a2.copy(
              document =
                a2.document.copy(identity = a2.document.identity.copy(performanceSessionId = "other"))
            ),
            a2.copy(
              document = a2.document.copy(identity = a2.document.identity.copy(sessionSequence = 3))
            ),
          )
          .forEach { unordered ->
            CaptureComparator()
              .compare(calibrationRequest(candidate = unordered))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
          }

        val candidate = candidateRequest()
        val calibration =
          candidate.calibration.shouldBeInstanceOf<VerifiedCalibrationComparison>()
        listOf(
            calibration.copy(a1Sequence = 0),
            calibration.copy(a2Sequence = 4),
            calibration.copy(bSequence = 4),
            calibration.copy(performanceSessionId = "other"),
          )
          .forEach { unordered ->
            CaptureComparator()
              .compare(candidate.copy(calibration = unordered))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH
          }
      }

      test("candidate comparison permits only the declared treatment and production deltas") {
        val request = candidateRequest()
        val completed =
          CaptureComparator().compare(request).shouldBeInstanceOf<ComparisonComputation.Completed>()

        completed.exit shouldBe RunnerExit.SUCCESS
        completed.document.kind shouldBe ComparisonKind.CANDIDATE
        completed.document.calibration?.a2CaptureId shouldBe "a2"
        completed.document.calibration?.bCaptureId shouldBe "b"

        val missingCalibration =
          CaptureComparator()
            .compare(request.copy(calibration = null))
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
        missingCalibration.reasons shouldContain CompatibilityFailure.CALIBRATION_EVIDENCE_MISSING

        val rejectedCalibration =
          CaptureComparator()
            .compare(
              request.copy(
                calibration =
                  RejectedCalibrationComparison(
                    Path.of("/comparison/a1-a2"),
                    listOf(BundleVerificationFailure.CHECKSUM_INVALID),
                  )
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
        rejectedCalibration.reasons shouldContain
          CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID

        val staleCalibration =
          CaptureComparator()
            .compare(
              request.copy(
                calibration =
                  request.calibration
                    .shouldBeInstanceOf<VerifiedCalibrationComparison>()
                    .copy(a2CaptureId = "unrelated")
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
        staleCalibration.reasons shouldContain CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH
      }

      test("candidate comparison rejects non-distinct or non-derived production deltas") {
        val request = candidateRequest()
        val candidate = request.candidate.shouldBeInstanceOf<CaptureBundleProof.Verified>()
        val sameTreatment =
          candidate.copy(
            document =
              candidate.document.copy(
                provenance =
                  candidate.document.provenance.copy(
                    treatment = GitProvenance("a".repeat(40), true)
                  )
              )
          )
        CaptureComparator()
          .compare(request.copy(candidate = sameTreatment))
          .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          .reasons shouldContain CompatibilityFailure.CANDIDATE_TREATMENT_NOT_DISTINCT

        val sameDistribution =
          candidate.copy(
            document =
              candidate.document.copy(
                artifacts =
                  candidate.document.artifacts.copy(
                    distribution = request.baseline.shouldBeInstanceOf<CaptureBundleProof.Verified>()
                      .document.artifacts.distribution
                  )
              )
          )
        CaptureComparator()
          .compare(request.copy(candidate = sameDistribution))
          .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          .reasons shouldContain CompatibilityFailure.CANDIDATE_DELTA_INVALID
      }

      test("candidate comparison rejects forbidden harness and dependency deltas") {
        val request = candidateRequest()
        val candidate = request.candidate.shouldBeInstanceOf<CaptureBundleProof.Verified>()
        val harnessChanged =
          candidate.copy(
            document =
              candidate.document.copy(
                provenance =
                  candidate.document.provenance.copy(
                    immutableHarness = GitProvenance("9".repeat(40), true)
                  )
              )
          )
        CaptureComparator()
          .compare(request.copy(candidate = harnessChanged))
          .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          .reasons shouldContain CompatibilityFailure.IMMUTABLE_HARNESS_MISMATCH

        val dependencyChanged =
          candidate.copy(
            document =
              candidate.document.copy(
                artifacts =
                  candidate.document.artifacts.copy(
                    dependencies = listOf(DependencyIdentity("example:dep:1", sha('9')))
                  )
              )
          )
        CaptureComparator()
          .compare(request.copy(candidate = dependencyChanged))
          .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          .reasons shouldContain CompatibilityFailure.DEPENDENCY_MISMATCH
      }

      test("candidate comparison permits clean freezer and adapter checkout provenance deltas") {
        val request = candidateRequest()
        val candidate = request.candidate.shouldBeInstanceOf<CaptureBundleProof.Verified>()
        val allowed =
          candidate.copy(
            document =
              candidate.document.copy(
                provenance =
                  candidate.document.provenance.copy(
                    distributionFreezer = GitProvenance("8".repeat(40), true),
                    captureRunner = GitProvenance("9".repeat(40), true),
                  )
              )
          )

        CaptureComparator()
          .compare(request.copy(candidate = allowed))
          .shouldBeInstanceOf<ComparisonComputation.Completed>()

        val unclean =
          allowed.copy(
            document =
              allowed.document.copy(
                provenance =
                  allowed.document.provenance.copy(
                    captureRunner = allowed.document.provenance.captureRunner.copy(treeClean = false)
                  )
              )
          )
        CaptureComparator()
          .compare(request.copy(candidate = unclean))
          .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          .reasons shouldContain CompatibilityFailure.CANDIDATE_DELTA_INVALID
      }

      test("direction and policy endpoint equality follow the frozen inclusive rules") {
        CaptureComparator.directionForTesting(0.9, 1.0) shouldBe DirectionOutcome.INCONCLUSIVE
        CaptureComparator.directionForTesting(1.0, 1.1) shouldBe DirectionOutcome.INCONCLUSIVE
        CaptureComparator.directionForTesting(0.8, 0.99) shouldBe DirectionOutcome.IMPROVEMENT
        CaptureComparator.directionForTesting(1.01, 1.2) shouldBe DirectionOutcome.REGRESSION

        CaptureComparator.policyForTesting(0.9, 1.1, 0.1) shouldBe PolicyOutcome.PASS
        CaptureComparator.policyForTesting(1.1, 1.2, 0.1) shouldBe PolicyOutcome.INCONCLUSIVE
        CaptureComparator.policyForTesting(1.1000000001, 1.2, 0.1) shouldBe PolicyOutcome.FAIL

        CaptureComparator.calibrationForTesting(0.95, 0.95, 1.05) shouldBe true
        CaptureComparator.calibrationForTesting(1.05, 0.95, 1.05) shouldBe true
        CaptureComparator.calibrationForTesting(0.9499999999, 0.95, 1.05) shouldBe false
        CaptureComparator.calibrationForTesting(1.0500000001, 0.95, 1.05) shouldBe false
        CaptureComparator.calibrationForTesting(1.0, 0.9499999999, 1.05) shouldBe false

        CaptureComparator.aggregatePolicyForTesting(
          listOf(PolicyOutcome.PASS, PolicyOutcome.INCONCLUSIVE)
        ) shouldBe PolicyOutcome.INCONCLUSIVE
        CaptureComparator.aggregatePolicyForTesting(
          listOf(PolicyOutcome.PASS, PolicyOutcome.FAIL, PolicyOutcome.INCONCLUSIVE)
        ) shouldBe PolicyOutcome.FAIL
      }

      test("enforced policy maps aggregate failure and uncertainty to stable exits") {
        val failed =
          CaptureComparator()
            .compare(
              candidateRequest(
                baselineSample = 10.0,
                candidateSample = 12.0,
                policy = regressionPolicy(0.1),
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Completed>()
        failed.document.policy.outcome shouldBe PolicyOutcome.FAIL
        failed.exit shouldBe RunnerExit.POLICY_FAILED

        val inconclusive =
          CaptureComparator()
            .compare(
              candidateRequest(
                baselineSamples = (1..10).map(Int::toDouble),
                candidateSamples = (1..10).map { it.toDouble() * 1.1 },
                policy = regressionPolicy(0.05),
              )
            )
            .shouldBeInstanceOf<ComparisonComputation.Completed>()
        inconclusive.document.policy.outcome shouldBe PolicyOutcome.INCONCLUSIVE
        inconclusive.exit shouldBe RunnerExit.POLICY_INCONCLUSIVE
      }

      test("regression policy derives its identity only from strict canonical bytes") {
        val bytes =
          ("""{"maximumRegressionBudget":0.1,"schemaVersion":"regression-policy-v1"}""" + "\n")
            .encodeToByteArray()

        val policy = RegressionPolicy.parse(bytes)

        policy.maximumRegressionBudget shouldBe 0.1
        policy.sha256 shouldBe Sha256.digest(bytes)
        runCatching { RegressionPolicy.parse(" {\"maximumRegressionBudget\":0.1}".encodeToByteArray()) }
          .isFailure shouldBe true
        runCatching {
            RegressionPolicy.parse(
              """{"maximumRegressionBudget":-0.1,"schemaVersion":"regression-policy-v1"}"""
                .plus("\n")
                .encodeToByteArray()
            )
          }
          .isFailure shouldBe true
      }
    }
  )

internal fun calibrationRequest(
  baseline: CaptureBundleProof = verifiedCapture("a1", "process-a1", 1),
  candidate: CaptureBundleProof = verifiedCapture("a2", "process-a2", 2),
): ComparisonRequest =
  ComparisonRequest(
    kind = ComparisonKind.CALIBRATION,
    baseline = baseline,
    candidate = candidate,
    execution = executionIdentity(),
  )

internal fun candidateRequest(
  baselineSample: Double = 10.0,
  candidateSample: Double = 9.0,
  baselineSamples: List<Double>? = null,
  candidateSamples: List<Double>? = null,
  policy: RegressionPolicy? = null,
): ComparisonRequest {
  val a2 =
    verifiedCapture(
      "a2",
      "process-a2",
      2,
      sample = baselineSample,
      forkValues = baselineSamples,
    )
  val b =
    verifiedCapture(
      "b",
      "process-b",
      3,
      treatmentSha = "b".repeat(40),
      productionSha = sha('b'),
      distributionSha = sha('c'),
      sample = candidateSample,
      forkValues = candidateSamples,
    )
  return ComparisonRequest(
    kind = ComparisonKind.CANDIDATE,
    baseline = a2,
    candidate = b,
    execution = executionIdentity(),
    calibration =
      VerifiedCalibrationComparison(
        root = Path.of("/comparison/a1-a2"),
        sha256 = sha('6'),
        a1CaptureId = "a1",
        a2CaptureId = "a2",
        bCaptureId = "b",
        performanceSessionId = "session",
        a1Sequence = 1,
        a2Sequence = 2,
        bSequence = 3,
        passingCells = setOf(testCellIdentity()),
      ),
    regressionPolicy = policy,
  )
}

internal fun verifiedCapture(
  captureId: String,
  processRunId: String,
  sequence: Int,
  treatmentSha: String = "a".repeat(40),
  productionSha: Sha256 = sha('a'),
  distributionSha: Sha256 = sha('d'),
  profiler: String = "none",
  sample: Double = 10.0,
  forkValues: List<Double>? = null,
  forkCount: Int = 10,
  qualificationSha: Sha256 = sha('1'),
  runtimeHost: String = "host",
  comparatorSha: Sha256 = sha('2'),
): CaptureBundleProof.Verified {
  val identity = CaptureIdentity(captureId, processRunId, "session", sequence)
  val cellIdentity = testCellIdentity()
  val values = forkValues ?: List(forkCount) { sample }
  val forkSamples = values.map { value -> ForkSamples(List(10) { value }) }
  val production = ArtifactIdentity("lib/revoman.jar", productionSha)
  val benchmark = ArtifactIdentity("lib/benchmarks.jar", sha('3'))
  val document =
    CaptureDocument(
      schemaVersion = "capture-v1",
      benchmarkProtocolVersion = "performance-v1",
      identity = identity,
      outcome =
        CaptureOutcome(
          EvidenceStatus.VALID,
          EvidenceStrength.DIAGNOSTIC,
          listOf(FinalOutcomeReason.BOUNDED_DIAGNOSTIC),
          "2026-08-17T00:00:00Z",
          "2026-08-17T00:01:00Z",
          0,
        ),
      provenance =
        ProvenanceRoles(
          GitProvenance(treatmentSha, true),
          GitProvenance("1".repeat(40), true),
          GitProvenance("2".repeat(40), true),
          GitProvenance("3".repeat(40), true),
        ),
      protocol =
        ProtocolIdentity(
          sha('4'),
          sha('5'),
          qualificationSha,
          sha('6'),
          sha('7'),
          sha('8'),
          sha('9'),
          comparatorSha,
        ),
      artifacts =
        CaptureArtifacts(
          production = production,
          benchmark = benchmark,
          distribution = ArtifactIdentity("metadata/distribution.sha256", distributionSha),
          orderedClasspath = listOf(production, benchmark),
          executingRunner = ArtifactIdentity("lib/performance-runner.jar", sha('2')),
          orderedRunnerClasspath =
            listOf(ArtifactIdentity("lib/performance-runner.jar", sha('2'))),
          dependencies = listOf(DependencyIdentity("example:dep:1", sha('5'))),
          rawJmhInputSha256 = sha('0'),
        ),
      toolchain =
        ToolchainIdentity("9.7.0", "0.7.3", "1.37", "2.4.20-RC", "schema-v1", "privacy-v1"),
      runtime = testRuntime(runtimeHost),
      qualification =
        QualificationEvidence.GithubHosted(
          policyHash = qualificationSha,
          setup = HostDocumentRef("setup.json", sha('1')),
          cleanup = HostDocumentRef("cleanup.json", sha('2')),
          macFieldsInapplicableReason = "hosted",
        ),
      logging = LoggingProfileIdentity("benchmark-noop", sha('3')),
      profile =
        CaptureProfileIdentity(
          family = "warm",
          identity = "warm-$forkCount-$profiler-v1",
          variantSha256 = sha('4'),
          forks = forkCount,
          warmupIterations = 5,
          measurementIterations = 10,
          profiler = profiler,
        ),
      cells =
        listOf(
          CaptureCell(
            benchmark = cellIdentity.benchmark,
            parameters = cellIdentity.parameters,
            mode = cellIdentity.mode,
            unit = cellIdentity.unit,
            threads = cellIdentity.threads,
            batchSize = cellIdentity.batchSize,
            primaryMetric =
              PrimaryMetricIdentity(cellIdentity.primaryMetric, "lowerIsBetter"),
            jmhResultRow = JmhResultRowRef("/0", sha('4')),
            sampleDimensions = SampleDimensions(forkCount, 10, 10),
            derivedForkSummaries =
              values.mapIndexed { index, value ->
                ForkSummary(index + 1, 10, BigDecimal.valueOf(value))
              },
          )
        ),
    )
  return CaptureBundleProof.Verified(
    root = Path.of("/capture/$captureId").toAbsolutePath().normalize(),
    document = document,
    captureSha256 = sha(captureId.first()),
    bundleSha256 = sha(processRunId.last()),
    manifest =
      CaptureBundleManifest(
        treatment = document.provenance.treatment,
        production = document.artifacts.production,
        distribution = document.artifacts.distribution,
        captureSha256 = sha(captureId.first()),
      ),
    samples = mapOf(cellIdentity to forkSamples),
  )
}

internal fun testCellIdentity(): CellIdentity =
  CellIdentity(
    "com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.revUp",
    "warm",
    emptyMap(),
    "ss",
    "ms/op",
    1,
    1,
    "score",
    "lower-is-better",
  )

internal fun executionIdentity(): VerifiedComparisonExecution =
  VerifiedComparisonExecution(
    ComparisonExecutionIdentity(
      comparatorSha256 = sha('2'),
      rendererSha256 = sha('9'),
      schemaSha256 = sha('8'),
      bootstrapVectorSha256 = sha('5'),
      qualificationPolicySha256 = sha('1'),
    )
  )


private fun testRuntime(hostId: String): RuntimeIdentity =
  RuntimeIdentity(
    jdk = JdkIdentity(sha('1'), "Eclipse Temurin", "21.0.11+10-LTS", listOf("-Xms2g")),
    oci = OciIdentity("image@sha256:${"a".repeat(64)}", "sha256:${"b".repeat(64)}", "sha256:${"c".repeat(64)}"),
    linux = LinuxIdentity("Ubuntu 24.04", "6.12.0-linuxkit", "arm64"),
    limits = RuntimeLimits("0-3", 6_442_450_944, 6_442_450_944, 512),
    storage = StorageIdentity("containerVolume", listOf("operation-output")),
    network = NetworkIdentity("none", "never"),
    security = SecurityIdentity("10001:10001", true, true, emptyList()),
    environment = mapOf("LANG" to "C.UTF-8", "TZ" to "UTC"),
    hostId = hostId,
    substrate =
      SubstrateIdentity.GithubHosted(
        "ubuntu-24.04-arm",
        "20260817.1",
        "6.12.0",
        "28.3.3",
        AdvertisedResources(4, 16_000_000_000),
      ),
  )

internal fun sha(character: Char): Sha256 = Sha256.parse(character.toString().repeat(64))

internal fun regressionPolicy(maximumRegressionBudget: Double): RegressionPolicy =
  RegressionPolicy.parse(
    ("""{"maximumRegressionBudget":$maximumRegressionBudget,"schemaVersion":"regression-policy-v1"}""" +
        "\n")
      .encodeToByteArray()
  )

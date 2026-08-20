/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import performance.campaign.CaptureRole
import performance.campaign.ReceiptFileFact
import performance.compare.CaptureBundleVerifier
import performance.compare.CaptureComparator
import performance.compare.RegressionPolicy
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.AdvertisedResources
import performance.model.ArtifactIdentity
import performance.model.CaptureArtifacts
import performance.model.CaptureCell
import performance.model.CaptureIdentity
import performance.model.CaptureProfileIdentity
import performance.model.DependencyIdentity
import performance.model.EvidenceStatus
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
import performance.model.ProvenanceRoles
import performance.model.ProvisionalCaptureDocument
import performance.model.ProvisionalCaptureOutcome
import performance.model.ProvisionalEvidenceStrength
import performance.model.ProvisionalOutcomeReason
import performance.model.ProtocolIdentity
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
import performance.support.CaptureBundleFixture
import performance.support.DistributionFixture
import performance.support.DistributionFixture.Companion.PRODUCTION_JAR
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Campaign finalization derives every claim-bearing byte from reverified 9A-sealed paths. */
class CampaignFinalizerTest :
  FunSpec(
    {
      test("bounded host qualification is derived from the mounted documents without private state") {
        val root = Files.createTempDirectory("bounded-host-qualification-").toRealPath()
        val policy = Sha256.parse("a".repeat(64))
        val expected = writeQualification(root, policy, Sha256.parse("b".repeat(64)))

        HostQualificationReader.read(
          root = root,
          policy = policy,
          substrate =
            SubstrateIdentity.ControlledMac(
              macosVersion = "26.6.2",
              macosBuild = "25G83",
              hardwareModelClass = "Mac16,5",
              dockerDesktopVersion = "4.87.0",
              dockerEngineVersion = "29.7.2",
              vmResources = AdvertisedResources(16, 8318709760L),
            ),
          campaign = false,
        ) shouldBe
          QualificationEvidence.ControlledMacBoundedDiagnostic(
            policyHash = policy,
            preflight = expected.preflight,
            watcher = expected.watcher,
            postflight = expected.postflight,
            restoration = expected.restoration,
            campaignFieldsInapplicableReason = "standaloneBoundedDiagnostic",
          )
      }

      test("campaign host qualification is derived from the mounted documents without private state") {
        val root = Files.createTempDirectory("campaign-host-qualification-").toRealPath()
        val policy = Sha256.parse("a".repeat(64))
        val expected = writeQualification(root, policy, Sha256.parse("b".repeat(64)))

        HostQualificationReader.read(
          root = root,
          policy = policy,
          substrate =
            SubstrateIdentity.ControlledMac(
              macosVersion = "26.6.2",
              macosBuild = "25G83",
              hardwareModelClass = "Mac16,5",
              dockerDesktopVersion = "4.87.0",
              dockerEngineVersion = "29.7.2",
              vmResources = AdvertisedResources(16, 8318709760L),
            ),
          campaign = true,
        ) shouldBe expected
      }

      test("github hosted diagnostic qualification is derived from mounted documents") {
        val root = Files.createTempDirectory("github-hosted-qualification-").toRealPath()
        val policy = Sha256.parse("a".repeat(64))
        val expected = writeQualification(root, policy, Sha256.parse("b".repeat(64)))

        HostQualificationReader.read(
          root = root,
          policy = policy,
          substrate = githubHostedSubstrate(),
          campaign = false,
        ) shouldBe
          QualificationEvidence.GithubHosted(
            policyHash = policy,
            setup = expected.preflight,
            cleanup = expected.restoration,
            macFieldsInapplicableReason = "githubHosted",
          )
      }

      test("github hosted diagnostic rejects missing malformed and mismatched qualification") {
        val policy = Sha256.parse("a".repeat(64))
        val missing = Files.createTempDirectory("github-hosted-missing-").toRealPath()
        val malformed = Files.createTempDirectory("github-hosted-malformed-").toRealPath()
        Files.writeString(malformed.resolve("preflight.json"), "not-json")
        val mismatched = Files.createTempDirectory("github-hosted-mismatched-").toRealPath()
        writeQualification(mismatched, policy, Sha256.parse("b".repeat(64)))

        listOf(
            missing to policy,
            malformed to policy,
            mismatched to Sha256.parse("f".repeat(64)),
          )
          .forEach { (root, expectedPolicy) ->
            shouldThrow<Exception> {
              HostQualificationReader.read(
                root = root,
                policy = expectedPolicy,
                substrate = githubHostedSubstrate(),
                campaign = false,
              )
            }
          }
      }

      test("private campaign finalization selects mounted controlled Mac documents") {
        CampaignFixture.create(listOf(true)).use { fixture ->
          val campaignQualification = fixture.qualification
          val capture = fixture.attempts.single().a1.sealedRoot.resolve("capture.json")
          val method =
            PrivateOperationFinalizer::class.java.getDeclaredMethod(
              "campaignQualification",
              Path::class.java,
              ProvisionalCaptureDocument::class.java,
            )
          method.isAccessible = true

          method.invoke(
            PrivateOperationFinalizer,
            fixture.qualificationRoot,
            provisional(json(capture)),
          ) shouldBe campaignQualification
        }
      }

      test("a passing ten fork graph produces the exact canonical private campaign tree") {
        CampaignFixture.create(listOf(true)).use { fixture ->
          val output = fixture.root.resolve("campaign-pass")
          val computed =
            fixture.finalizer
              .compute(fixture.request(output))
              .shouldBeInstanceOf<CampaignComputationOutcome.Computed>()

          computed.exit shouldBe RunnerExit.SUCCESS
          computed.root shouldBe output
          val campaignBytes = Files.readAllBytes(output.resolve("campaign.json"))
          EvidenceSchemaValidator().validate(SchemaKind.CAMPAIGN, campaignBytes).shouldBeEmpty()
          val campaign = CanonicalJson.parseStrict(campaignBytes).asObject()
          campaign.text("status") shouldBe "qualified"
          campaign.text("strength") shouldBe "canonical"
          campaign.get("claimEligible").asBoolean() shouldBe true
          campaign.array("attempts").size() shouldBe 1

          listOf("captures/10-a1", "captures/10-a2", "captures/10-b").forEach { relative ->
            captureDocument(output, relative).outcome() shouldBe
              ("canonical" to "controlledMacCampaign")
            CaptureBundleVerifier.verify(output.resolve(relative)).failures.shouldBeEmpty()
          }
          comparisonDocument(output, "comparisons/10-calibration").text("strength") shouldBe
            "diagnostic"
          comparisonDocument(output, "comparisons/candidate").text("strength") shouldBe
            "canonical"
          val forgedCalibration =
            comparisonDocument(output, "comparisons/10-calibration").apply {
              put("strength", "canonical")
            }
          EvidenceSchemaValidator()
            .validate(SchemaKind.COMPARISON, CanonicalJson.encode(forgedCalibration))
            .shouldNotBeEmpty()
          regularFiles(output).shouldContainExactlyInAnyOrder(expectedPassingTree())
          verifyRecursiveManifest(output)
          computed.campaignSha256 shouldBe Sha256.digest(campaignBytes)
          computed.manifestSha256 shouldBe Sha256.digest(output.resolve("checksums.sha256"))
        }
      }

      test("a passing controlled Mac cold campaign remains canonical") {
        CampaignFixture.create(listOf(true), profileFamily = "cold").use { fixture ->
          val output = fixture.root.resolve("controlled-cold-campaign")
          val computed =
            fixture.finalizer
              .compute(fixture.request(output))
              .shouldBeInstanceOf<CampaignComputationOutcome.Computed>()

          computed.exit shouldBe RunnerExit.SUCCESS
          val campaign = json(output.resolve("campaign.json"))
          campaign.text("strength") shouldBe "canonical"
          campaign.get("claimEligible").asBoolean() shouldBe true
          listOf("captures/10-a1", "captures/10-a2", "captures/10-b").forEach { relative ->
            captureDocument(output, relative).objectNode("profile").text("family") shouldBe "cold"
          }
        }
      }

      test("failed ten forks followed by passing twenty forks retains diagnostics and selects twenty") {
        CampaignFixture.create(listOf(false, true)).use { fixture ->
          val output = fixture.root.resolve("campaign-escalated")
          val computed = fixture.finalizer.compute(fixture.request(output)).shouldBeInstanceOf<CampaignComputationOutcome.Computed>()

          computed.exit shouldBe RunnerExit.SUCCESS
          val campaign = json(output.resolve("campaign.json"))
          campaign.text("selectedAttemptId") shouldBe fixture.attempts.last().attemptId
          campaign.array("attempts").values().asSequence().map { it.get("forks").asInt() }.toList() shouldBe listOf(10, 20)
          captureDocument(output, "captures/10-a1").outcome() shouldBe
            ("diagnostic" to "controlledMacBoundedDiagnostic")
          captureDocument(output, "captures/10-a2").outcome() shouldBe
            ("diagnostic" to "controlledMacBoundedDiagnostic")
          listOf("captures/20-a1", "captures/20-a2", "captures/20-b").forEach { relative ->
            captureDocument(output, relative).outcome() shouldBe
              ("canonical" to "controlledMacCampaign")
          }
          Files.exists(output.resolve("captures/10-b")) shouldBe false
          comparisonDocument(output, "comparisons/10-calibration").objectNode("calibration").get("passed").asBoolean() shouldBe false
          comparisonDocument(output, "comparisons/20-calibration").objectNode("calibration").get("passed").asBoolean() shouldBe true
          verifyRecursiveManifest(output)
        }
      }

      test("exhausted ten twenty forty ladder is complete diagnostic and nonclaiming") {
        CampaignFixture.create(listOf(false, false, false)).use { fixture ->
          val output = fixture.root.resolve("campaign-exhausted")
          val computed = fixture.finalizer.compute(fixture.request(output)).shouldBeInstanceOf<CampaignComputationOutcome.Computed>()

          computed.exit shouldBe RunnerExit.CALIBRATION_FAILED
          val campaign = json(output.resolve("campaign.json"))
          campaign.text("status") shouldBe "exhausted"
          campaign.text("strength") shouldBe "diagnostic"
          campaign.get("claimEligible").asBoolean() shouldBe false
          campaign.get("selectedAttemptId") shouldBe null
          campaign.get("candidate") shouldBe null
          Files.exists(output.resolve("comparisons/candidate")) shouldBe false
          listOf(10, 20, 40).forEach { forks ->
            listOf("a1", "a2").forEach { role ->
              captureDocument(output, "captures/$forks-$role").outcome() shouldBe
                ("diagnostic" to "controlledMacBoundedDiagnostic")
            }
            comparisonDocument(output, "comparisons/$forks-calibration").objectNode("calibration").get("passed").asBoolean() shouldBe false
          }
          regularFiles(output).size shouldBe 45
          verifyRecursiveManifest(output)
        }
      }

      test("known passing and exhausted inputs produce byte-identical trees at distinct roots") {
        listOf(listOf(true), listOf(false, false, false)).forEachIndexed { index, decisions ->
          CampaignFixture.create(decisions).use { fixture ->
            val first = fixture.root.resolve("deterministic-$index-a")
            val second = fixture.root.resolve("deterministic-$index-b")
            fixture.finalizer.compute(fixture.request(first)).shouldBeInstanceOf<CampaignComputationOutcome.Computed>()
            fixture.finalizer.compute(fixture.request(second)).shouldBeInstanceOf<CampaignComputationOutcome.Computed>()
            treeBytes(first) shouldBe treeBytes(second)
          }
        }
      }

      test("candidate policy is recomputed and bound into canonical campaign evidence") {
        CampaignFixture.create(listOf(true)).use { fixture ->
          val policyBytes = CanonicalJson.encode(node {
            put("maximumRegressionBudget", 0.0)
            put("schemaVersion", "regression-policy-v1")
          })
          val policy = RegressionPolicy.parse(policyBytes)
          val output = fixture.root.resolve("campaign-policy")
          fixture.finalizer.compute(fixture.request(output).copy(regressionPolicy = policy))
            .shouldBeInstanceOf<CampaignComputationOutcome.Computed>()
          val candidate = comparisonDocument(output, "comparisons/candidate")
          candidate.objectNode("policy").text("sha256") shouldBe policy.sha256.hex
          json(output.resolve("campaign.json")).objectNode("candidate").text("policySha256") shouldBe policy.sha256.hex
        }
      }

      test("complete declared graph and evidence mutation matrix rejects without a usable tree") {
        val cases = negativeCases()
        cases.size shouldBe 52
        cases.forEach { case ->
          CampaignFixture.create(case.decisions).use { fixture ->
            val output = fixture.root.resolve("rejected-${case.name}")
            val request = case.mutate(fixture, fixture.request(output))
            fixture.finalizer.compute(request).shouldBeInstanceOf<CampaignComputationOutcome.Rejected>()
            Files.exists(output) shouldBe false
          }
        }
      }

      test("every ingress capture is bounded diagnostic evidence before campaign projection") {
        ingressStrengthCases().forEach { case ->
          CampaignFixture.create(case.decisions).use { fixture ->
            case.mutate(fixture)
            val output = fixture.root.resolve("rejected-${case.name}")

            fixture.finalizer
              .compute(fixture.request(output))
              .shouldBeInstanceOf<CampaignComputationOutcome.Rejected>()
            Files.exists(output) shouldBe false
          }
        }
      }

      test("the internal API exposes no caller strength selector or forgeable success constructor") {
        CampaignComputationRequest::class.java.declaredFields.map { it.name }.joinToString() shouldNotContain "strength"
        CampaignComputationRequest::class.java.declaredFields.map { it.name }.joinToString() shouldNotContain "canonical"
        CampaignAttemptInput::class.java.declaredFields.map { it.name }.joinToString() shouldNotContain "proof"
        CampaignComputationOutcome.Computed::class.java.constructors.all {
          it.parameterTypes.firstOrNull() == Any::class.java
        } shouldBe true
        shouldThrow<IllegalArgumentException> {
          CampaignComputationOutcome.Computed(
            Any(),
            Path.of("/tmp/not-a-campaign"),
            Sha256.parse("a".repeat(64)),
            Sha256.parse("b".repeat(64)),
            RunnerExit.SUCCESS,
          )
        }
        val successConstructor = CampaignComputationOutcome.Computed::class.java.constructors.single()
        shouldThrow<InvocationTargetException> {
          successConstructor.newInstance(
            Any(),
            Path.of("/tmp/not-a-campaign"),
            "a".repeat(64),
            "b".repeat(64),
            RunnerExit.SUCCESS,
            null,
          )
        }
        CampaignComputationOutcome.Computed::class.java.declaredClasses
          .flatMap { it.declaredMethods.toList() }
          .filter { it.returnType == CampaignComputationOutcome.Computed::class.java }
          .shouldBeEmpty()
        val permitType = Class.forName("performance.finalize.CampaignMaterializationPermit")
        permitType.constructors.single().parameterTypes.toList() shouldBe listOf(Any::class.java)
        shouldThrow<IllegalArgumentException> {
          CampaignMaterializationPermit(Any())
        }
        shouldThrow<InvocationTargetException> {
          permitType.constructors.single().newInstance(Any())
        }
        listOf(CampaignMaterializer::class.java, CampaignRenderer::class.java).forEach { type ->
          val canonicalWriter = type.declaredMethods.single { it.name in setOf("materialize", "render") }
          canonicalWriter.isSynthetic shouldBe true
          canonicalWriter.parameterTypes.first() shouldBe permitType
        }
        hostileJvmCallerCompilation().let { compilation ->
          compilation.succeeded shouldBe false
          compilation.diagnostics shouldContain "materialize"
          compilation.diagnostics shouldContain "render"
        }
        listOf(
          VerifiedCampaignInput::class.java,
          VerifiedAttempt::class.java,
          ValidatedHostDocuments::class.java,
        ).forEach { proofType ->
          proofType.declaredMethods.none { it.name == "copy" } shouldBe true
          proofType.constructors.single().parameterTypes.first() shouldBe Any::class.java
        }
        CaptureBundleVerifier.Projection::class.java.constructors.single().parameterTypes.first() shouldBe Any::class.java
        performance.compare.ComparisonStrength.entries.map { it.name } shouldBe listOf("DIAGNOSTIC")
      }
    },
  )

internal class CampaignFixture private constructor(
  val root: Path,
  val baseline: DistributionFixture,
  val candidate: DistributionFixture,
  val attempts: List<CampaignAttemptInput>,
  val qualificationRoot: Path,
  val qualification: QualificationEvidence.ControlledMacCampaign,
  val profileFamily: String,
  val finalizer: CampaignFinalizer,
) : AutoCloseable {
  fun request(output: Path): CampaignComputationRequest =
    CampaignComputationRequest(
      campaignId = CAMPAIGN_ID,
      performanceSessionId = SESSION_ID,
      profileFamily = profileFamily,
      attempts = attempts,
      baselineDistribution = baseline.root,
      candidateDistribution = candidate.root,
      qualificationRoot = qualificationRoot,
      qualification = qualification,
      regressionPolicy = null,
      outputRoot = output,
    )

  override fun close() {
    baseline.close()
    candidate.close()
    root.toFile().deleteRecursively()
  }

  companion object {
    fun create(
      calibrationPasses: List<Boolean>,
      profileFamily: String = "warm",
    ): CampaignFixture {
      val root = Files.createTempDirectory("campaign-finalizer-").toRealPath()
      val baseline = DistributionFixture.create().apply { prepareComparisonProtocol() }
      val candidate =
        DistributionFixture.create().apply {
          prepareComparisonProtocol()
          replaceJar(
            PRODUCTION_JAR,
            mapOf("example/Candidate.class" to DistributionFixture.compiledClass("example.Candidate")),
          )
        }
      val capturesRoot = root.resolve("sealed")
      Files.createDirectories(capturesRoot)
      var sequence = 0
      val attempts =
        calibrationPasses.mapIndexed { index, passes ->
          val forks = listOf(10, 20, 40)[index]
          val attemptId = "$profileFamily-$forks-${index + 1}"
          fun capture(
            role: CaptureRole,
            distribution: DistributionFixture,
            value: Double,
            candidateRole: Boolean = false,
          ): CampaignCaptureInput {
            sequence += 1
            val captureId = "capture-${forks}-${role.shortName.lowercase()}-$sequence"
            val sealedRoot = capturesRoot.resolve("$forks-${role.shortName.lowercase()}")
            sealDiagnostic(
              distribution = distribution,
              captureId = captureId,
              sequence = sequence,
              forks = forks,
              value = value,
              startedAt = Instant.parse("2026-08-18T00:00:00Z").plusSeconds((sequence - 1) * 60L),
              completedAt = Instant.parse("2026-08-18T00:00:00Z").plusSeconds(sequence * 60L),
              target = sealedRoot,
              candidateRole = candidateRole,
              profileFamily = profileFamily,
            )
            return CampaignCaptureInput(
              role = role,
              sealedRoot = sealedRoot,
              receipt = receipt(role, sequence.toLong(), distribution.root),
            )
          }
          val a1 = capture(CaptureRole.BASELINE_A1, baseline, 10.0)
          val a2 = capture(CaptureRole.BASELINE_A2, baseline, if (passes) 10.0 else 20.0)
          val b =
            if (passes) {
              capture(CaptureRole.CANDIDATE_B, candidate, 9.0, candidateRole = true)
            } else {
              null
            }
          CampaignAttemptInput(attemptId, forks, a1, a2, b)
        }
      val qualificationRoot = root.resolve("qualification")
      val firstCapture = json(attempts.first().a1.sealedRoot.resolve("capture.json"))
      val policy = firstCapture.objectNode("protocol").sha("qualificationPolicySha256")
      val adapter = firstCapture.objectNode("protocol").sha("hostAdapterSha256")
      val qualification = writeQualification(qualificationRoot, policy, adapter)
      val finalizer =
        CampaignFinalizer(
          CaptureComparator.forTest(
            comparatorCodeSource = baseline.root.resolve(baseline.runnerClasspath.first()),
            effectiveClasspath = baseline.runnerClasspath.map(baseline.root::resolve),
          ),
        )
      return CampaignFixture(
        root,
        baseline,
        candidate,
        attempts,
        qualificationRoot,
        qualification,
        profileFamily,
        finalizer,
      )
    }
  }
}

private fun sealDiagnostic(
  distribution: DistributionFixture,
  captureId: String,
  sequence: Int,
  forks: Int,
  value: Double,
  startedAt: Instant,
  completedAt: Instant,
  target: Path,
  candidateRole: Boolean,
  profileFamily: String,
) {
  val source =
    CaptureBundleFixture.create(
      distribution = distribution,
      captureId = captureId,
      processRunId = "$captureId-process",
      sessionId = SESSION_ID,
      sequence = sequence,
      treatmentSha = if (candidateRole) "9".repeat(40) else null,
      freezerSha = if (candidateRole) "7".repeat(40) else null,
      captureRunnerSha = "8".repeat(40),
      startedAtUtc = startedAt.toString(),
      completedAtUtc = completedAt.toString(),
      forkSamples =
        List(forks) {
          List(if (profileFamily == "cold") 1 else 10) { value }
        },
      profileFamily = profileFamily,
  )
  try {
    if (forks != 10) {
      val profile = json(distribution.root.resolve("protocol/profiles/$profileFamily.json"))
      val variant = profile.array("variants").single { it.get("forks").asInt() == forks }.asObject()
      source.mutateCapture { capture ->
        capture.objectNode("profile").apply {
          put("identity", variant.text("identity"))
          put("variantSha256", Sha256.digest(CanonicalJson.encode(variant)).hex)
          put("forks", forks)
        }
      }
    }
    val operationRoot = target.resolveSibling(".${target.fileName}.operation")
    Files.createDirectories(operationRoot)
    listOf("jmh-result.json", "stdout.log", "stderr.log").forEach { name ->
      Files.copy(source.root.resolve(name), operationRoot.resolve(name))
    }
    val document = provisional(source.captureDocument())
    val qualification =
      QualificationEvidence.ControlledMacBoundedDiagnostic(
        policyHash = document.protocol.qualificationPolicySha256,
        preflight = HostDocumentRef("host/preflight.json", Sha256.parse("a".repeat(64))),
        watcher = HostDocumentRef("host/watcher.json", Sha256.parse("b".repeat(64))),
        postflight = HostDocumentRef("host/postflight.json", Sha256.parse("c".repeat(64))),
        restoration = HostDocumentRef("host/restoration.json", Sha256.parse("d".repeat(64))),
        campaignFieldsInapplicableReason = "standaloneBoundedDiagnostic",
      )
    DiagnosticCaptureSealer
      .seal(document, operationRoot, target, qualification)
      .shouldBeInstanceOf<DiagnosticSealOutcome.Sealed>()
    CaptureBundleVerifier.verify(target).failures.shouldBeEmpty()
  } finally {
    source.close()
  }
}

private fun provisional(document: ObjectNode): ProvisionalCaptureDocument =
  ProvisionalCaptureDocument(
    schemaVersion = "capture-provisional-v1",
    benchmarkProtocolVersion = document.text("benchmarkProtocolVersion"),
    identity = document.objectNode("identity").identity(),
    outcome =
      document.objectNode("outcome").let { outcome ->
        ProvisionalCaptureOutcome(
          EvidenceStatus.VALID,
          ProvisionalEvidenceStrength.DIAGNOSTIC,
          listOf(ProvisionalOutcomeReason.BOUNDED_DIAGNOSTIC),
          outcome.text("startedAtUtc"),
          outcome.text("completedAtUtc"),
          0,
        )
      },
    provenance = document.objectNode("provenance").provenance(),
    protocol = document.objectNode("protocol").protocol(),
    artifacts = document.objectNode("artifacts").artifacts(),
    toolchain = document.objectNode("toolchain").toolchain(),
    runtime = document.objectNode("runtime").runtime(),
    logging = document.objectNode("logging").logging(),
    profile = document.objectNode("profile").profile(),
    cells = document.array("cells").values().asSequence().map { it.asObject().cell() }.toList(),
  )

private fun ObjectNode.identity(): CaptureIdentity =
  CaptureIdentity(
    text("captureId"),
    text("processRunId"),
    text("performanceSessionId"),
    int("sessionSequence"),
  )

private fun ObjectNode.provenance(): ProvenanceRoles =
  ProvenanceRoles(git("treatment"), git("immutableHarness"), git("distributionFreezer"), git("captureRunner"))

private fun ObjectNode.git(name: String): GitProvenance =
  objectNode(name).let { GitProvenance(it.text("gitSha"), it.get("treeClean").asBoolean()) }

private fun ObjectNode.protocol(): ProtocolIdentity =
  ProtocolIdentity(
    sha("benchmarkSourceSha256"),
    sha("benchmarkProtocolSha256"),
    sha("qualificationPolicySha256"),
    sha("workloadTreeSha256"),
    sha("hostAdapterSha256"),
    sha("schemaSha256"),
    sha("rendererSha256"),
    sha("comparatorSha256"),
  )

private fun ObjectNode.artifacts(): CaptureArtifacts =
  CaptureArtifacts(
    artifact("production"),
    artifact("benchmark"),
    artifact("distribution"),
    array("orderedClasspath").values().asSequence().map { it.asObject().artifact() }.toList(),
    artifact("executingRunner"),
    array("orderedRunnerClasspath").values().asSequence().map { it.asObject().artifact() }.toList(),
    array("dependencies").values().asSequence().map { value ->
      value.asObject().let { DependencyIdentity(it.text("coordinate"), it.sha("sha256")) }
    }.toList(),
    sha("rawJmhInputSha256"),
  )

private fun ObjectNode.artifact(name: String): ArtifactIdentity = objectNode(name).artifact()

private fun ObjectNode.artifact(): ArtifactIdentity = ArtifactIdentity(text("path"), sha("sha256"))

private fun ObjectNode.toolchain(): ToolchainIdentity =
  ToolchainIdentity(
    text("gradleVersion"),
    text("jmhPluginVersion"),
    text("jmhCoreVersion"),
    text("kotlinCompilerVersion"),
    text("schemaVersion"),
    text("sanitizerVersion"),
  )

private fun ObjectNode.runtime(): RuntimeIdentity =
  RuntimeIdentity(
    jdk =
      objectNode("jdk").let {
        JdkIdentity(
          it.sha("binarySha256"),
          it.text("vendor"),
          it.text("version"),
          it.stringList("jvmArguments"),
        )
      },
    oci =
      objectNode("oci").let {
        OciIdentity(it.text("imageReference"), it.text("platformManifestDigest"), it.text("configDigest"))
      },
    linux =
      objectNode("linux").let { LinuxIdentity(it.text("os"), it.text("kernel"), it.text("architecture")) },
    limits =
      objectNode("limits").let {
        RuntimeLimits(it.text("cpuSet"), it.long("memoryBytes"), it.long("memorySwapBytes"), it.int("pidLimit"))
      },
    storage =
      objectNode("storage").let { StorageIdentity(it.text("distributionSource"), it.stringList("writableMounts")) },
    network = objectNode("network").let { NetworkIdentity(it.text("mode"), it.text("pullPolicy")) },
    security =
      objectNode("security").let {
        SecurityIdentity(
          it.text("user"),
          it.get("readOnlyRoot").asBoolean(),
          it.get("noNewPrivileges").asBoolean(),
          it.stringList("capabilities"),
        )
      },
    environment = objectNode("environment").properties().associate { it.key to it.value.asString() },
    hostId = text("hostId"),
    substrate =
      objectNode("substrate").let {
        SubstrateIdentity.ControlledMac(
          it.text("macosVersion"),
          it.text("macosBuild"),
          it.text("hardwareModelClass"),
          it.text("dockerDesktopVersion"),
          it.text("dockerEngineVersion"),
          it.objectNode("vmResources").let { resources ->
            AdvertisedResources(resources.int("cpus"), resources.long("memoryBytes"))
          },
        )
      },
  )

private fun ObjectNode.logging(): LoggingProfileIdentity =
  LoggingProfileIdentity(text("profile"), sha("configurationSha256"))

private fun ObjectNode.profile(): CaptureProfileIdentity =
  CaptureProfileIdentity(
    text("family"),
    text("identity"),
    sha("variantSha256"),
    int("forks"),
    int("warmupIterations"),
    int("measurementIterations"),
    text("profiler"),
  )

private fun ObjectNode.cell(): CaptureCell =
  CaptureCell(
    benchmark = text("benchmark"),
    parameters = objectNode("parameters").properties().associate { it.key to it.value.asString() },
    mode = text("mode"),
    unit = text("unit"),
    threads = int("threads"),
    batchSize = int("batchSize"),
    primaryMetric =
      objectNode("primaryMetric").let { PrimaryMetricIdentity(it.text("name"), it.text("direction")) },
    jmhResultRow =
      objectNode("jmhResultRow").let { JmhResultRowRef(it.text("jsonPointer"), it.sha("sha256")) },
    sampleDimensions =
      objectNode("sampleDimensions").let {
        SampleDimensions(it.int("forks"), it.int("measurementIterations"), it.int("samplesPerFork"))
      },
    derivedForkSummaries =
      array("derivedForkSummaries").values().asSequence().map { value ->
        value.asObject().let { ForkSummary(it.int("fork"), it.int("sampleCount"), it.get("score").decimalValue()) }
      }.toList(),
  )

private fun receipt(role: CaptureRole, sequence: Long, root: Path): CampaignReceiptInput {
  val files =
    Files.walk(root).use { paths ->
      paths
        .filter(Files::isRegularFile)
        .map(root::relativize)
        .map { it.joinToString("/") }
        .sorted()
        .map { relative ->
          val path = root.resolve(relative)
          ReceiptFileFact(relative, Files.size(path), Sha256.digest(path))
        }
        .toList()
    }
  return CampaignReceiptInput(
    role,
    root,
    Sha256.digest(root.resolve(DistributionFixture.CHECKSUM_MANIFEST)),
    files,
    Duration.ofSeconds(10),
    sequence,
  )
}

private fun githubHostedSubstrate(): SubstrateIdentity.GithubHosted =
  SubstrateIdentity.GithubHosted(
    runnerLabel = "ubuntu-24.04-arm",
    runnerImageVersion = "runner-image_v1+rev.2",
    kernel = "6.11.0",
    dockerEngineVersion = "29.7.2",
    advertisedResources = AdvertisedResources(4, 17179869184L),
  )

private fun writeQualification(
  root: Path,
  policy: Sha256,
  adapter: Sha256,
): QualificationEvidence.ControlledMacCampaign {
  Files.createDirectories(root)
  val fingerprint = "a".repeat(64)
  val snapshot =
    node {
      put("containerFingerprintSha256", fingerprint)
      put("cpuIdlePercent", 99.0)
      put("cpuLoadPercent", 1.0)
      put("memoryPressure", "normal")
      put("pageOuts", 0)
      put("powerState", "ac")
      put("runtimeFingerprintSha256", fingerprint)
      put("swapBytes", 0)
      put("thermalState", "nominal")
    }
  val preflight =
    node {
      put("adapterSha256", adapter.hex)
      put("architecture", "arm64")
      set(
        "checks",
        passChecks(
          "containers",
          "context",
          "cpuIdle",
          "image",
          "interference",
          "memoryPressure",
          "power",
          "runtime",
          "swapPage",
          "thermal",
          "userIdle",
        ),
      )
      put("kind", "preflight")
      put("lockAcquired", true)
      put("observedAtUtc", "2026-08-17T23:59:00Z")
      put("operationId", CAMPAIGN_ID)
      put("policySha256", policy.hex)
      put("schemaVersion", "preflight-v1")
      set("snapshot", snapshot.deepCopy())
      put("userIdleMillis", 600000)
    }
  val watcher =
    node {
      put("cadenceMillis", 1000)
      put("completedAtUtc", "2026-08-18T00:10:00Z")
      put("expectedSamples", 1)
      put("kind", "watcher")
      set(
        "observations",
        JsonNodeFactory.instance.arrayNode().add(
          node {
            put("containerFingerprintSha256", fingerprint)
            put("cpuLoadPercent", 1.0)
            put("event", "none")
            put("memoryPressure", "normal")
            put("observedAtUtc", "2026-08-18T00:05:00Z")
            put("pageOuts", 0)
            put("powerState", "ac")
            put("runtimeFingerprintSha256", fingerprint)
            put("swapBytes", 0)
            put("thermalState", "nominal")
          },
        ),
      )
      put("observedSamples", 1)
      put("policySha256", policy.hex)
      put("schemaVersion", "watcher-v1")
      put("startedAtUtc", "2026-08-17T23:59:30Z")
      put("terminalState", "completed")
    }
  val postflight =
    node {
      set(
        "checks",
        passChecks(
          "cleanup",
          "containers",
          "cpuIdle",
          "interference",
          "memoryPressure",
          "power",
          "runtime",
          "swapPage",
          "thermal",
        ),
      )
      put("kind", "postflight")
      put("observedAtUtc", "2026-08-18T00:11:00Z")
      put("policySha256", policy.hex)
      put("processExit", 0)
      put("schemaVersion", "postflight-v1")
      set("snapshot", snapshot.deepCopy())
    }
  val restoration =
    node {
      put("cleanupPassed", true)
      put("kind", "restoration")
      put("lockReleaseReady", true)
      put("observedAtUtc", "2026-08-18T00:12:00Z")
      put("policySha256", policy.hex)
      put("restoredState", "passed")
      put("schemaVersion", "restoration-v1")
    }
  val documents =
    listOf(
      "preflight.json" to preflight,
      "watcher.json" to watcher,
      "postflight.json" to postflight,
      "restoration.json" to restoration,
    )
  documents.forEach { (name, document) -> Files.write(root.resolve(name), CanonicalJson.encode(document)) }
  fun ref(name: String): HostDocumentRef = HostDocumentRef(name, Sha256.digest(root.resolve(name)))
  return QualificationEvidence.ControlledMacCampaign(
    policy,
    ref("preflight.json"),
    ref("watcher.json"),
    ref("postflight.json"),
    ref("restoration.json"),
    true,
  )
}

private data class NegativeCase(
  val name: String,
  val decisions: List<Boolean> = listOf(true),
  val mutate: (CampaignFixture, CampaignComputationRequest) -> CampaignComputationRequest,
)

private data class IngressStrengthCase(
  val name: String,
  val decisions: List<Boolean>,
  val mutate: (CampaignFixture) -> Unit,
)

private fun ingressStrengthCases(): List<IngressStrengthCase> =
  listOf(
    IngressStrengthCase("canary-selected", listOf(true)) { fixture ->
      mutateIngressCapture(fixture.attempts.single().a1.sealedRoot, IngressMutation.CANARY)
    },
    IngressStrengthCase("canonical-selected", listOf(true)) { fixture ->
      fixture.attempts.single().captures().forEach {
        mutateIngressCapture(it.sealedRoot, IngressMutation.CANONICAL)
      }
    },
    IngressStrengthCase("mixed-qualification", listOf(true)) { fixture ->
      fixture.attempts.single().captures().forEach {
        mutateIngressCapture(it.sealedRoot, IngressMutation.CAMPAIGN_QUALIFICATION)
      }
    },
    IngressStrengthCase("canary-discarded-attempt", listOf(false, true)) { fixture ->
      listOf(fixture.attempts.first().a1, fixture.attempts.first().a2).forEach {
        mutateIngressCapture(it.sealedRoot, IngressMutation.CANARY)
      }
    },
    IngressStrengthCase("canonical-exhausted-campaign", listOf(false, false, false)) { fixture ->
      listOf(fixture.attempts.last().a1, fixture.attempts.last().a2).forEach {
        mutateIngressCapture(it.sealedRoot, IngressMutation.CANONICAL)
      }
    },
  )

private enum class IngressMutation {
  CANARY,
  CANONICAL,
  CAMPAIGN_QUALIFICATION,
}

private fun CampaignAttemptInput.captures(): List<CampaignCaptureInput> =
  listOfNotNull(a1, a2, b)

private fun mutateIngressCapture(root: Path, mutation: IngressMutation) {
  mutateSealedCapture(root) { capture ->
    val outcome = capture.objectNode("outcome")
    val qualification = capture.objectNode("qualification")
    when (mutation) {
      IngressMutation.CANARY -> {
        outcome.put("strength", "canary")
        outcome.set(
          "claimEligibilityReasons",
          JsonNodeFactory.instance.arrayNode().add("structuralCanary"),
        )
      }
      IngressMutation.CANONICAL -> {
        outcome.put("strength", "canonical")
        outcome.set(
          "claimEligibilityReasons",
          JsonNodeFactory.instance.arrayNode().add("controlledMacCampaignQualified"),
        )
        qualification.put("kind", "controlledMacCampaign")
        qualification.remove("campaignFieldsInapplicableReason")
        qualification.put("cleanupPassed", true)
      }
      IngressMutation.CAMPAIGN_QUALIFICATION -> {
        qualification.put("kind", "controlledMacCampaign")
        qualification.remove("campaignFieldsInapplicableReason")
        qualification.put("cleanupPassed", true)
      }
    }
  }
}

private data class HostileCompilation(
  val succeeded: Boolean,
  val diagnostics: String,
)

private fun hostileJvmCallerCompilation(): HostileCompilation {
  val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
  val sourceRoot = Files.createTempDirectory("campaign-hostile-java-")
  val source = sourceRoot.resolve("performance/finalize/HostileCanonicalCaller.java")
  val output = Files.createDirectory(sourceRoot.resolve("classes"))
  Files.createDirectories(source.parent)
  Files.writeString(
    source,
    """
      package performance.finalize;

      import performance.compare.CaptureComparator;
      import performance.model.CampaignDocument;

      final class HostileCanonicalCaller {
        void write(
            VerifiedCampaignInput proof,
            CaptureComparator comparator,
            CampaignDocument document) {
          CampaignMaterializer.INSTANCE.materialize(proof, comparator);
          CampaignRenderer.INSTANCE.render(document);
        }
      }
    """.trimIndent(),
  )
  val diagnostics = DiagnosticCollector<JavaFileObject>()
  val writer = StringWriter()
  val succeeded =
    compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fileManager ->
      compiler
        .getTask(
          writer,
          fileManager,
          diagnostics,
          listOf(
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            output.toString(),
          ),
          null,
          fileManager.getJavaFileObjects(source),
        ).call()
    }
  val messages =
    buildString {
      append(writer)
      diagnostics.diagnostics.forEach { diagnostic ->
        appendLine(diagnostic.getMessage(null))
      }
    }
  sourceRoot.toFile().deleteRecursively()
  return HostileCompilation(succeeded, messages)
}

private fun negativeCases(): List<NegativeCase> =
  listOf(
    NegativeCase("cardinality") { _, request -> request.copy(attempts = emptyList(), selectedAttemptId = null) },
    NegativeCase("order", listOf(false, true)) { _, request -> request.copy(attempts = request.attempts.reversed()) },
    NegativeCase("duplicate-root") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a2 = attempt.a1.copy(role = CaptureRole.BASELINE_A2))))
    },
    NegativeCase("aliased-root") { fixture, request ->
      val attempt = request.attempts.single()
      val alias = fixture.root.resolve("alias-a2")
      Files.createSymbolicLink(alias, attempt.a2.sealedRoot)
      request.copy(attempts = listOf(attempt.copy(a2 = attempt.a2.copy(sealedRoot = alias))))
    },
    NegativeCase("role") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a1 = attempt.a1.copy(role = CaptureRole.BASELINE_A2))))
    },
    NegativeCase("attempt") { _, request ->
      request.copy(attempts = listOf(request.attempts.single().copy(attemptId = "unsafe/attempt")))
    },
    NegativeCase("fork") { _, request -> request.copy(attempts = listOf(request.attempts.single().copy(forks = 20))) },
    NegativeCase("session") { _, request ->
      mutateSealedCapture(request.attempts.single().a2.sealedRoot) {
        it.objectNode("identity").put("performanceSessionId", "different-session")
      }
      request
    },
    NegativeCase("sequence") { _, request ->
      val attempt = request.attempts.single()
      mutateSealedCapture(attempt.a2.sealedRoot) { it.objectNode("identity").put("sessionSequence", 12) }
      request.copy(
        attempts = listOf(attempt.copy(a2 = attempt.a2.copy(receipt = attempt.a2.receipt.copy(sequence = 12)))),
      )
    },
    NegativeCase("capture-id") { _, request ->
      val attempt = request.attempts.single()
      val duplicate = json(attempt.a1.sealedRoot.resolve("capture.json")).objectNode("identity").text("captureId")
      mutateSealedCapture(attempt.a2.sealedRoot) { it.objectNode("identity").put("captureId", duplicate) }
      request
    },
    NegativeCase("campaign-id") { _, request -> request.copy(campaignId = "unsafe/campaign") },
    NegativeCase("sensitive-campaign-id") { _, request -> request.copy(campaignId = "secret-campaign") },
    NegativeCase("selected-binding") { _, request -> request.copy(selectedAttemptId = "not-an-attempt") },
    NegativeCase("receipt-role") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a1 = attempt.a1.copy(receipt = attempt.a1.receipt.copy(role = CaptureRole.BASELINE_A2)))))
    },
    NegativeCase("receipt-settle") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a1 = attempt.a1.copy(receipt = attempt.a1.receipt.copy(settleDuration = Duration.ofSeconds(9))))))
    },
    NegativeCase("receipt-distribution-root") { fixture, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a1 = attempt.a1.copy(receipt = attempt.a1.receipt.copy(distributionRoot = fixture.candidate.root)))))
    },
    NegativeCase("receipt-manifest") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a1 = attempt.a1.copy(receipt = attempt.a1.receipt.copy(manifestSha256 = Sha256.parse("f".repeat(64)))))))
    },
    NegativeCase("receipt-path") { _, request -> mutateReceiptFact(request) { it.copy(relativePath = "wrong/path") } },
    NegativeCase("receipt-length") { _, request -> mutateReceiptFact(request) { it.copy(byteLength = it.byteLength + 1) } },
    NegativeCase("receipt-sha") { _, request -> mutateReceiptFact(request) { it.copy(sha256 = Sha256.parse("f".repeat(64))) } },
    NegativeCase("host-ref") { _, request ->
      request.copy(qualification = request.qualification.copy(preflight = request.qualification.preflight.copy(path = "../preflight.json")))
    },
    NegativeCase("host-hash") { _, request ->
      request.copy(qualification = request.qualification.copy(preflight = request.qualification.preflight.copy(sha256 = Sha256.parse("f".repeat(64)))))
    },
    NegativeCase("duration") { _, request ->
      mutateSealedCapture(requireNotNull(request.attempts.single().b).sealedRoot) {
        it.objectNode("outcome").put("completedAtUtc", "2026-08-18T03:00:00Z")
      }
      request
    },
    NegativeCase("profile") { _, request ->
      mutateSealedCapture(request.attempts.single().a2.sealedRoot, verifierAccepted = false) {
        it.objectNode("profile").put("forks", 20)
      }
      request
    },
    NegativeCase("qualification") { _, request ->
      request.copy(qualification = request.qualification.copy(cleanupPassed = false))
    },
    NegativeCase("policy-relationship") { _, request ->
      mutateSealedCapture(request.attempts.single().a2.sealedRoot) {
        it.objectNode("qualification").put("policyHash", "f".repeat(64))
      }
      request
    },
    NegativeCase("capture-checksum") { _, request ->
      val capture = request.attempts.single().a1.sealedRoot.resolve("capture.json")
      Files.write(capture, Files.readAllBytes(capture) + '\n'.code.toByte())
      request
    },
    NegativeCase("calibration-decision") { _, request ->
      request.copy(attempts = listOf(request.attempts.single().copy(calibrationPassed = false)))
    },
    NegativeCase("failed-calibration-decision", listOf(false, false, false)) { _, request ->
      request.copy(attempts = request.attempts.mapIndexed { index, attempt ->
        if (index == 1) attempt.copy(calibrationPassed = true) else attempt
      })
    },
    NegativeCase("a2-b-binding") { _, request ->
      val attempt = request.attempts.single()
      val b = requireNotNull(attempt.b)
      mutateSealedCapture(b.sealedRoot) { it.objectNode("identity").put("sessionSequence", 13) }
      request.copy(attempts = listOf(attempt.copy(b = b.copy(receipt = b.receipt.copy(sequence = 13)))))
    },
    NegativeCase("candidate-distinctness") { _, request ->
      val attempt = request.attempts.single()
      val baseline = json(attempt.a2.sealedRoot.resolve("capture.json"))
      mutateSealedCapture(requireNotNull(attempt.b).sealedRoot) { candidate ->
        candidate.objectNode("provenance").set("treatment", baseline.objectNode("provenance").objectNode("treatment").deepCopy())
        val baselineProduction = baseline.objectNode("artifacts").objectNode("production")
        val artifacts = candidate.objectNode("artifacts")
        artifacts.set("production", baselineProduction.deepCopy())
        artifacts.array("orderedClasspath").forEach { entry ->
          if (entry.get("path").asString() == PRODUCTION_JAR) {
            entry.asObject().put("sha256", baselineProduction.text("sha256"))
          }
        }
      }
      request
    },
    NegativeCase("missing-b") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(b = null)), selectedAttemptId = null)
    },
    NegativeCase("process-id") { _, request ->
      val attempt = request.attempts.single()
      val duplicate = json(attempt.a1.sealedRoot.resolve("capture.json")).objectNode("identity").text("processRunId")
      mutateSealedCapture(attempt.a2.sealedRoot) { it.objectNode("identity").put("processRunId", duplicate) }
      request
    },
    NegativeCase("attempt-id-duplicate", listOf(false, true)) { _, request ->
      val first = request.attempts.first()
      request.copy(attempts = listOf(first, request.attempts.last().copy(attemptId = first.attemptId)))
    },
    NegativeCase("output-overlap") { _, request ->
      request.copy(outputRoot = request.attempts.single().a1.sealedRoot.resolve("campaign-child"))
    },
    NegativeCase("distribution-alias") { fixture, request ->
      request.copy(candidateDistribution = fixture.baseline.root)
    },
  ) + roleMutationCases() + checksumMutationCases() + hostReferenceCases() + hostSemanticCases()

private fun roleMutationCases(): List<NegativeCase> =
  listOf(
    NegativeCase("role-a2") { _, request ->
      val attempt = request.attempts.single()
      request.copy(attempts = listOf(attempt.copy(a2 = attempt.a2.copy(role = CaptureRole.BASELINE_A1))))
    },
    NegativeCase("role-b") { _, request ->
      val attempt = request.attempts.single()
      val b = requireNotNull(attempt.b)
      request.copy(attempts = listOf(attempt.copy(b = b.copy(role = CaptureRole.BASELINE_A1))))
    },
  )

private fun checksumMutationCases(): List<NegativeCase> =
  listOf("a2", "b").map { role ->
    NegativeCase("capture-checksum-$role") { _, request ->
      val attempt = request.attempts.single()
      val root = if (role == "a2") attempt.a2.sealedRoot else requireNotNull(attempt.b).sealedRoot
      val capture = root.resolve("capture.json")
      Files.write(capture, Files.readAllBytes(capture) + '\n'.code.toByte())
      request
    }
  }

private fun hostReferenceCases(): List<NegativeCase> =
  listOf("preflight", "watcher", "postflight", "restoration").flatMap { kind ->
    listOf(
      NegativeCase("host-ref-$kind") { _, request ->
        request.copy(qualification = request.qualification.mapRef(kind) { it.copy(path = "../$kind.json") })
      },
      NegativeCase("host-hash-$kind") { _, request ->
        request.copy(qualification = request.qualification.mapRef(kind) { it.copy(sha256 = Sha256.parse("f".repeat(64))) })
      },
    )
  }

private fun hostSemanticCases(): List<NegativeCase> =
  listOf(
    NegativeCase("host-preflight-identity") { fixture, request ->
      mutateHostDocument(fixture, request, "preflight") { it.put("adapterSha256", "f".repeat(64)) }
    },
    NegativeCase("host-watcher-completeness") { fixture, request ->
      mutateHostDocument(fixture, request, "watcher") { it.put("observedSamples", 2) }
    },
    NegativeCase("host-postflight-exit") { fixture, request ->
      mutateHostDocument(fixture, request, "postflight") { it.put("processExit", 1) }
    },
    NegativeCase("host-restoration-cleanup") { fixture, request ->
      mutateHostDocument(fixture, request, "restoration") { it.put("cleanupPassed", false) }
    },
  )

private fun QualificationEvidence.ControlledMacCampaign.mapRef(
  kind: String,
  mutation: (HostDocumentRef) -> HostDocumentRef,
): QualificationEvidence.ControlledMacCampaign =
  when (kind) {
    "preflight" -> copy(preflight = mutation(preflight))
    "watcher" -> copy(watcher = mutation(watcher))
    "postflight" -> copy(postflight = mutation(postflight))
    "restoration" -> copy(restoration = mutation(restoration))
    else -> error("unknown host document")
  }

private fun mutateHostDocument(
  fixture: CampaignFixture,
  request: CampaignComputationRequest,
  kind: String,
  mutation: (ObjectNode) -> Unit,
): CampaignComputationRequest {
  val ref =
    when (kind) {
      "preflight" -> request.qualification.preflight
      "watcher" -> request.qualification.watcher
      "postflight" -> request.qualification.postflight
      "restoration" -> request.qualification.restoration
      else -> error("unknown host document")
    }
  val path = fixture.qualificationRoot.resolve(ref.path)
  val bytes = CanonicalJson.encode(json(path).apply(mutation))
  Files.write(path, bytes)
  return request.copy(
    qualification = request.qualification.mapRef(kind) { it.copy(sha256 = Sha256.digest(bytes)) },
  )
}

private fun mutateReceiptFact(
  request: CampaignComputationRequest,
  mutation: (ReceiptFileFact) -> ReceiptFileFact,
): CampaignComputationRequest {
  val attempt = request.attempts.single()
  val receipt = attempt.a1.receipt
  val facts = receipt.files.toMutableList().apply { this[0] = mutation(first()) }
  return request.copy(attempts = listOf(attempt.copy(a1 = attempt.a1.copy(receipt = receipt.copy(files = facts)))))
}

private fun mutateSealedCapture(
  root: Path,
  verifierAccepted: Boolean = true,
  mutation: (ObjectNode) -> Unit,
) {
  val path = root.resolve("capture.json")
  val document = json(path).apply(mutation)
  Files.write(path, CanonicalJson.encode(document))
  val files = listOf("capture.json", "jmh-result.json", "stderr.log", "stdout.log")
  Files.writeString(
    root.resolve("checksums.sha256"),
    files.sorted().joinToString("\n", postfix = "\n") { name -> "${Sha256.digest(root.resolve(name)).hex}  $name" },
  )
  if (verifierAccepted) CaptureBundleVerifier.verify(root).failures.shouldBeEmpty()
}

private fun treeBytes(root: Path): Map<String, List<Byte>> =
  regularFiles(root).associateWith { relative -> Files.readAllBytes(root.resolve(relative)).toList() }

private fun expectedPassingTree(): List<String> =
  buildList {
    listOf("captures/10-a1", "captures/10-a2", "captures/10-b").forEach { root ->
      addAll(
        listOf(
          "$root/capture.json",
          "$root/checksums.sha256",
          "$root/jmh-result.json",
          "$root/stderr.log",
          "$root/stdout.log",
        ),
      )
    }
    listOf("comparisons/10-calibration", "comparisons/candidate").forEach { root ->
      add("$root/checksums.sha256")
      add("$root/comparison.json")
      add("$root/comparison.md")
    }
    add("campaign.json")
    add("checksums.sha256")
    add("host/postflight.json")
    add("host/preflight.json")
    add("host/restoration.json")
    add("host/watcher.json")
  }

private fun passChecks(vararg names: String): ObjectNode =
  node { names.forEach { name -> put(name, "pass") } }

private fun verifyRecursiveManifest(root: Path) {
  val expected =
    regularFiles(root)
      .filter { it != "checksums.sha256" }
      .sorted()
      .map { relative -> "${Sha256.digest(root.resolve(relative)).hex}  $relative" }
  Files.readString(root.resolve("checksums.sha256")) shouldBe
    expected.joinToString("\n", postfix = "\n")
}

private fun regularFiles(root: Path): List<String> =
  Files.walk(root).use { paths ->
    paths
      .filter(Files::isRegularFile)
      .map(root::relativize)
      .map { it.joinToString("/") }
      .sorted()
      .toList()
  }

private fun captureDocument(root: Path, relative: String): ObjectNode =
  json(root.resolve(relative).resolve("capture.json"))

private fun comparisonDocument(root: Path, relative: String): ObjectNode =
  json(root.resolve(relative).resolve("comparison.json"))

private fun ObjectNode.outcome(): Pair<String, String> =
  objectNode("outcome").text("strength") to objectNode("qualification").text("kind")

private fun json(path: Path): ObjectNode =
  CanonicalJson.parseStrict(Files.readAllBytes(path)).asObject()

private fun node(block: ObjectNode.() -> Unit): ObjectNode =
  JsonNodeFactory.instance.objectNode().apply(block)

private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()

private fun ObjectNode.array(name: String): ArrayNode = get(name).asArray()

private fun ObjectNode.text(name: String): String = get(name).asString()

private fun ObjectNode.int(name: String): Int = get(name).asInt()

private fun ObjectNode.long(name: String): Long = get(name).asLong()

private fun ObjectNode.sha(name: String): Sha256 = Sha256.parse(text(name))

private fun ObjectNode.stringList(name: String): List<String> =
  array(name).values().asSequence().map(JsonNode::asString).toList()

private const val CAMPAIGN_ID = "campaign-fixed"
private const val SESSION_ID = "session-fixed"

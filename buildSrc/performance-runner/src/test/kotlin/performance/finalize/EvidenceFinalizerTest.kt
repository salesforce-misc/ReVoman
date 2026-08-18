/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import performance.capture.DroppedSamples
import performance.capture.ProfilerIdentity
import performance.capture.ProfilerSummary
import performance.compare.CaptureBundleVerifier
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.publication.ChecksumManifest
import performance.publication.PublicationCommand
import performance.runner.RunnerExit
import performance.support.CaptureBundleFixture
import performance.support.DistributionFixture
import tools.jackson.databind.node.JsonNodeFactory

/** Integration boundary: 9C verifies and publishes immutable 9A/9B bytes without recomputation. */
class EvidenceFinalizerTest :
  FunSpec(
    {
      test("diagnostic finalization publishes one byte-identical reverified 9A tree") {
        val distribution = DistributionFixture.create().apply { prepareComparisonProtocol() }
        val source = CaptureBundleFixture.create(distribution).apply(::makeBoundedDiagnostic)
        val parent = Files.createTempDirectory("diagnostic-publication-").toRealPath()
        reserve(parent, "diagnostic-1")
        val before = snapshot(source.root)

        val outcome =
          EvidenceFinalizer.forTest(movingCommand())
            .finalizeDiagnostic(
              DiagnosticFinalizationRequest(source.root, parent, "diagnostic-1"),
            )
            .shouldBeInstanceOf<FinalizationOutcome.Published>()

        outcome.exit shouldBe RunnerExit.SUCCESS
        snapshot(outcome.root) shouldBe before
        snapshot(source.root) shouldBe before
        CaptureBundleVerifier.verify(outcome.root).failures shouldBe emptyList()
        source.close()
        distribution.close()
      }

      test("campaign finalization publishes the immutable 9B tree without choosing strength again") {
        CampaignFixture.create(listOf(true)).use { fixture ->
          val privateRoot = fixture.root.resolve("private-campaign")
          fixture.finalizer.compute(fixture.request(privateRoot))
            .shouldBeInstanceOf<CampaignComputationOutcome.Computed>()
          val before = snapshot(privateRoot)
          val parent = Files.createTempDirectory("campaign-publication-").toRealPath()
          reserve(parent, "campaign-1")

          val outcome =
            EvidenceFinalizer.forTest(movingCommand())
              .finalizeCampaign(CampaignFinalizationRequest(privateRoot, parent, "campaign-1"))
              .shouldBeInstanceOf<FinalizationOutcome.Published>()

          snapshot(outcome.root) shouldBe before
          snapshot(privateRoot) shouldBe before
          ChecksumManifest.verify(outcome.root) shouldBe true
          CanonicalJson.parseStrict(Files.readAllBytes(outcome.root.resolve("campaign.json")))
            .get("strength").asString() shouldBe "canonical"
        }
      }

      test("freeze finalization delegates validation and publication to the runner-owned boundary") {
        val source = Files.createTempDirectory("freeze-finalizer-source-").toRealPath()
        Files.createDirectories(source.resolve("metadata"))
        Files.createDirectories(source.resolve("bin"))
        Files.writeString(source.resolve("metadata/distribution.sha256"), "verified\n")
        Files.writeString(source.resolve("bin/performance-runner"), "runner\n")
        val before = snapshot(source)
        val parent = Files.createTempDirectory("freeze-finalizer-parent-").toRealPath()
        reserve(parent, "freeze-finalizer")

        val outcome =
          EvidenceFinalizer.forTest(movingCommand())
            .finalizeFreeze(
              FreezeFinalizationRequest(source, parent, "freeze-finalizer"),
              verifyDistribution = { root ->
                Files.readString(root.resolve("metadata/distribution.sha256")) == "verified\n"
              },
            )
            .shouldBeInstanceOf<FinalizationOutcome.Published>()

        outcome.exit shouldBe RunnerExit.SUCCESS
        snapshot(outcome.root) shouldBe before
        Files.exists(outcome.root.resolve("checksums.sha256")) shouldBe false
      }

      test("profiler finalization requires durable hash-bound intent completion and raw absence") {
        val distribution = DistributionFixture.create().apply { prepareComparisonProtocol() }
        val source =
          CaptureBundleFixture.create(distribution, profiler = "jfr").apply(::makeBoundedDiagnostic)
        val operation = Files.createTempDirectory("profiler-operation-").toRealPath()
        val rawHash = Sha256.parse("a".repeat(64))
        val summary =
          ProfilerSummary(
              captureId = "a1",
              rawInputSha256 = rawHash,
              durationNanos = 1,
              profiler =
                ProfilerIdentity.jfr(
                  source.captureDocument().get("profile").get("variantSha256").asString().let(Sha256::parse),
                  Sha256.parse("b".repeat(64)),
                ),
              droppedSamples = DroppedSamples(0, 0),
              aggregates = emptyList(),
            )
            .canonicalBytes()
        source.addAndReseal("profiler-summary.json", summary)
        source.mutateCapture { capture ->
          capture.set(
            "profilerSummary",
            JsonNodeFactory.instance.objectNode().apply {
              put("path", "profiler-summary.json")
              put("rawInputSha256", rawHash.hex)
              put("sha256", Sha256.digest(summary).hex)
              put("variantSha256", capture.get("profile").get("variantSha256").asString())
            },
          )
        }
        val evidence = profilerEvidence(operation, source.root, rawHash, summary)
        val parent = Files.createTempDirectory("profiler-publication-").toRealPath()
        reserve(parent, "profiler-1")

        val outcome =
          EvidenceFinalizer.forTest(movingCommand())
            .finalizeDiagnostic(
              DiagnosticFinalizationRequest(source.root, parent, "profiler-1", profiler = evidence),
            )
            .shouldBeInstanceOf<FinalizationOutcome.Published>()

        Files.exists(outcome.root.resolve("profiler-summary.json")) shouldBe true
        Files.walk(outcome.root).use { paths ->
          paths.noneMatch { it.fileName.toString().endsWith(".jfr") }
        } shouldBe true

        val hostileParent = Files.createTempDirectory("profiler-hostile-").toRealPath()
        reserve(hostileParent, "profiler-hostile")
        Files.writeString(operation.resolve("raw.jfr"), "raw-private")
        val invalid =
          EvidenceFinalizer.forTest(movingCommand())
            .finalizeDiagnostic(
              DiagnosticFinalizationRequest(source.root, hostileParent, "profiler-hostile", profiler = evidence),
            )
            .shouldBeInstanceOf<FinalizationOutcome.Published>()
        invalid.exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID
        Files.readString(invalid.root.resolve("INVALID/reason")) shouldBe
          "INPUT_OR_PROTOCOL_INVALID\n"
        Files.walk(invalid.root).use { paths ->
          paths.noneMatch { it.fileName.toString().endsWith(".jfr") }
        } shouldBe true
        Files.exists(hostileParent.resolve("profiler-hostile")) shouldBe false
        source.close()
        distribution.close()
      }

      test("sanitized INVALID is impossible before verified reservation and complete after it") {
        val parent = Files.createTempDirectory("invalid-finalization-").toRealPath()
        val finalizer = EvidenceFinalizer.forTest(movingCommand())

        finalizer
          .finalizeInvalid(
            InvalidFinalizationRequest(parent, "invalid-1", FinalizationFailure.MEASUREMENT_INVALID),
          )
          .shouldBeInstanceOf<FinalizationOutcome.Rejected>()
        Files.list(parent).use { it.toList() } shouldBe emptyList()

        reserve(parent, "invalid-1")
        val published =
          finalizer
            .finalizeInvalid(
              InvalidFinalizationRequest(parent, "invalid-1", FinalizationFailure.MEASUREMENT_INVALID),
            )
            .shouldBeInstanceOf<FinalizationOutcome.Published>()
        published.exit shouldBe RunnerExit.MEASUREMENT_INVALID
        Files.walk(published.root).use { paths ->
          paths.map { published.root.relativize(it).toString() }.filter(String::isNotEmpty).toList()
        } shouldContainExactlyInAnyOrder
          listOf("INVALID", "INVALID/reason", "checksums.sha256", "stderr.log")
        Files.readString(published.root.resolve("INVALID/reason")) shouldBe "MEASUREMENT_INVALID\n"
        val text = Files.readString(published.root.resolve("stderr.log"))
        text shouldBe "performance-runner: MEASUREMENT_INVALID\n"
        text shouldNotContain parent.toString()
        ChecksumManifest.verify(published.root) shouldBe true
      }

      test("finalizer rejects altered source bytes and publication failure supersedes prior exit") {
        val distribution = DistributionFixture.create().apply { prepareComparisonProtocol() }
        val source = CaptureBundleFixture.create(distribution).apply(::makeBoundedDiagnostic)
        Files.writeString(source.root.resolve("stdout.log"), "altered")
        val parent = Files.createTempDirectory("altered-finalization-").toRealPath()
        reserve(parent, "altered")

        val invalid =
          EvidenceFinalizer.forTest(movingCommand())
            .finalizeDiagnostic(DiagnosticFinalizationRequest(source.root, parent, "altered"))
            .shouldBeInstanceOf<FinalizationOutcome.Published>()
        invalid.exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID
        Files.readString(invalid.root.resolve("INVALID/reason")) shouldBe
          "INPUT_OR_PROTOCOL_INVALID\n"
        Files.exists(parent.resolve("altered")) shouldBe false

        source.reseal()
        val collisionParent = Files.createTempDirectory("failed-finalization-").toRealPath()
        reserve(collisionParent, "failed")
        EvidenceFinalizer.forTest(PublicationCommand { 1 })
          .finalizeDiagnostic(
            DiagnosticFinalizationRequest(
              source.root,
              collisionParent,
              "failed",
              terminal = RunnerExit.POLICY_FAILED,
            ),
          )
          .shouldBeInstanceOf<FinalizationOutcome.Rejected>()
          .exit shouldBe RunnerExit.INTERNAL_OR_PUBLICATION_FAILED
        source.close()
        distribution.close()
      }
    },
  )

private fun movingCommand(): PublicationCommand = PublicationCommand { command ->
  Files.move(Path.of(command[4]), Path.of(command[5]), ATOMIC_MOVE)
  0
}

private fun reserve(parent: Path, token: String) {
  val reservation = parent.resolve(".$token.reservation")
  Files.createDirectory(reservation)
  Files.setPosixFilePermissions(reservation, PosixFilePermissions.fromString("rwx------"))
  Files.writeString(reservation.resolve("token"), "$token\n")
}

private fun snapshot(root: Path): Map<String, ByteArray> =
  Files.walk(root).use { paths ->
    paths
      .filter(Files::isRegularFile)
      .map { root.relativize(it).joinToString("/") to Files.readAllBytes(it) }
      .sorted(compareBy { it.first })
      .toList()
      .toMap()
  }

internal fun makeBoundedDiagnostic(source: CaptureBundleFixture) {
  source.mutateCapture { capture ->
    capture.get("outcome").asObject().apply {
      put("strength", "diagnostic")
      set(
        "claimEligibilityReasons",
        JsonNodeFactory.instance.arrayNode().add("boundedDiagnostic"),
      )
    }
    capture.get("qualification").asObject().apply {
      put("kind", "controlledMacBoundedDiagnostic")
      remove("cleanupPassed")
      put("campaignFieldsInapplicableReason", "standaloneBoundedDiagnostic")
    }
  }
}

private fun profilerEvidence(
  operation: Path,
  source: Path,
  rawHash: Sha256,
  summary: ByteArray,
): ProfilerFinalizationEvidence {
  val captureHash = Sha256.digest(source.resolve("capture.json"))
  val intent =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put("captureId", "a1")
        put("provisionalCaptureSha256", captureHash.hex)
        put("rawInputSha256", rawHash.hex)
        put("schemaVersion", "profiler-scrub-intent-v1")
        put("summarySha256", Sha256.digest(summary).hex)
      },
    )
  Files.write(operation.resolve("profiler-scrub-intent.json"), intent)
  val completion =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put("intentSha256", Sha256.digest(intent).hex)
        put("schemaVersion", "profiler-scrub-complete-v1")
        put("summarySha256", Sha256.digest(summary).hex)
      },
    )
  Files.write(operation.resolve("profiler-scrub-complete.json"), completion)
  return ProfilerFinalizationEvidence(
    operation,
    operation.resolve("profiler-scrub-intent.json"),
    operation.resolve("profiler-scrub-complete.json"),
    captureHash,
  )
}

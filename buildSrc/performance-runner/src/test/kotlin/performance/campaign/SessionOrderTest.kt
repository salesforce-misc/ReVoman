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
import java.nio.file.Files
import java.time.Duration
import javax.tools.ToolProvider
import performance.compare.CaptureBundleProof
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidator
import performance.support.DistributionFixture

class SessionOrderTest :
  FunSpec(
    {
      test("the selected candidate order is explicit A1 A2 B") {
        SessionOrder.selected shouldContainExactly
          listOf(CaptureRole.BASELINE_A1, CaptureRole.BASELINE_A2, CaptureRole.CANDIDATE_B)
      }

      test("the frozen runner creates opaque distinct performance session identities") {
        val first = SessionIdentity.create("campaign-test")
        val second = SessionIdentity.create("campaign-test")

        first.campaignId shouldBe "campaign-test"
        (first.performanceSessionId == second.performanceSessionId) shouldBe false
      }

      test("preconditioning records every manifest-bound file in unsigned UTF-8 order") {
        withDistribution { fixture, distribution ->
          val sleeps = mutableListOf<Duration>()
          val receipt =
            DefaultRolePreconditioner(sleeper = sleeps::add)
              .prepare(CaptureRole.BASELINE_A1, distribution)

          sleeps shouldBe emptyList()
          CampaignReceiptValidator.validate(
            receipt = receipt,
            expectedRole = CaptureRole.BASELINE_A1,
            distribution = distribution,
            expectedSettleDuration = Duration.ofSeconds(10),
            expectedSequence = 1,
          ) shouldBe true
          sleeps shouldContainExactly listOf(Duration.ofSeconds(10))
          receipt.role shouldBe CaptureRole.BASELINE_A1
          receipt.distributionRoot shouldBe distribution.root
          receipt.files.map(ReceiptFileFact::relativePath) shouldBe
            receipt.files.map(ReceiptFileFact::relativePath).sortedWith(::compareUnsignedUtf8)
          receipt.files.map(ReceiptFileFact::relativePath) shouldBe
            (fixture.checksumLines().map { it.substring(66) } +
                DistributionFixture.CHECKSUM_MANIFEST)
              .sortedWith(::compareUnsignedUtf8)
          receipt.files.all { it.byteLength > 0 && it.sha256.hex.length == 64 } shouldBe true
        }
      }

      test("the settle window begins after the final distribution verification read") {
        withDistribution { fixture, distribution ->
          val manifest = fixture.root.resolve(DistributionFixture.CHECKSUM_MANIFEST)
          val receipt =
            DefaultRolePreconditioner(sleeper = { Files.delete(manifest) })
              .prepare(CaptureRole.BASELINE_A1, distribution)

          CampaignReceiptValidator.validate(
            receipt = receipt,
            expectedRole = CaptureRole.BASELINE_A1,
            distribution = distribution,
            expectedSettleDuration = Duration.ofSeconds(10),
            expectedSequence = 1,
          ) shouldBe true
        }
      }

      test("preconditioning rejects undeclared changed and symlinked bytes") {
        withDistribution { fixture, distribution ->
          fixture.writeWithoutResealing("unexpected.bin", byteArrayOf(1))
          shouldThrow<IllegalArgumentException> {
            DefaultRolePreconditioner(sleeper = {}).prepare(CaptureRole.BASELINE_A1, distribution)
          }
        }
        withDistribution { fixture, distribution ->
          fixture.writeWithoutResealing(DistributionFixture.PRODUCTION_JAR, byteArrayOf(1))
          shouldThrow<IllegalArgumentException> {
            DefaultRolePreconditioner(sleeper = {}).prepare(CaptureRole.BASELINE_A1, distribution)
          }
        }
        withDistribution { fixture, distribution ->
          Files.createSymbolicLink(
            fixture.root.resolve("linked.jar"),
            fixture.root.resolve(DistributionFixture.PRODUCTION_JAR),
          )
          shouldThrow<IllegalArgumentException> {
            DefaultRolePreconditioner(sleeper = {}).prepare(CaptureRole.BASELINE_A1, distribution)
          }
        }
      }

      test("the runner rejects missing reordered stale wrong-role and unequal-policy receipts") {
        val mutations =
          listOf<(PreconditioningReceipt, DistributionFixture) -> PreconditioningReceipt>(
            { receipt, _ -> receipt.copy(files = receipt.files.drop(1)) },
            { receipt, _ -> receipt.copy(files = receipt.files.reversed()) },
            { receipt, fixture ->
              fixture.writeWithoutResealing(DistributionFixture.PRODUCTION_JAR, byteArrayOf(9))
              receipt
            },
            { receipt, _ -> receipt.copy(role = CaptureRole.CANDIDATE_B) },
            { receipt, fixture ->
              receipt.copy(distributionRoot = fixture.root.resolveSibling("wrong-root"))
            },
            { receipt, _ -> receipt.copy(settleDuration = Duration.ofSeconds(9)) },
            { receipt, _ -> receipt.copy(sequence = receipt.sequence + 7) },
          )
        mutations.forEach { mutation ->
          withDistribution { fixture, distribution ->
            val receipt =
              DefaultRolePreconditioner(sleeper = {}).prepare(CaptureRole.BASELINE_A1, distribution)
            CampaignReceiptValidator.validate(
              receipt = mutation(receipt, fixture),
              expectedRole = CaptureRole.BASELINE_A1,
              distribution = distribution,
              expectedSettleDuration = Duration.ofSeconds(10),
              expectedSequence = 1,
            ) shouldBe false
          }
        }
      }

      test("provisional capture proof cannot cross the sealed comparison or report boundary") {
        CaptureBundleProof::class.java.isAssignableFrom(ValidatedProvisionalCapture::class.java) shouldBe
          false
        performance.model.ComparisonReportDocument::class.java.isAssignableFrom(
          ValidatedProvisionalCapture::class.java,
        ) shouldBe false
        val evaluatorBytes =
          checkNotNull(
              ProvisionalCalibrationEvaluator::class.java.getResourceAsStream(
                "/performance/campaign/ProvisionalCalibrationEvaluator.class",
              ),
            )
            .use { it.readAllBytes() }
        evaluatorBytes.toString(Charsets.ISO_8859_1).contains("CaptureComparator") shouldBe false

        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val root = Files.createTempDirectory("campaign-boundary-compile-")
        try {
          val source = root.resolve("Boundary.java")
          Files.writeString(
            source,
            """
              import performance.campaign.ValidatedProvisionalCapture;
              import performance.compare.CaptureBundleProof;
              final class Boundary {
                void accepts(CaptureBundleProof proof) {}
                void rejected(ValidatedProvisionalCapture proof) { accepts(proof); }
              }
            """.trimIndent(),
          )
          compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            root.toString(),
            source.toString(),
          ) shouldBeNot 0
        } finally {
          root.toFile().deleteRecursively()
        }
      }
    },
  )

private inline fun withDistribution(
  block: (DistributionFixture, performance.distribution.VerifiedDistribution) -> Unit,
) {
  val fixture = DistributionFixture.create()
  try {
    val distribution =
      (DistributionValidator().validate(fixture.request()) as DistributionValidation.Valid).distribution
    block(fixture, distribution)
  } finally {
    fixture.close()
  }
}

private fun compareUnsignedUtf8(left: String, right: String): Int {
  val leftBytes = left.encodeToByteArray()
  val rightBytes = right.encodeToByteArray()
  for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
    val comparison =
      (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
    if (comparison != 0) return comparison
  }
  return leftBytes.size - rightBytes.size
}

private infix fun Int.shouldBeNot(other: Int) {
  (this == other) shouldBe false
}

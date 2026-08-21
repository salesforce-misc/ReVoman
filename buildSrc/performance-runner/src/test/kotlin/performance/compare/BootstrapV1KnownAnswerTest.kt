/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThan
import java.util.HexFormat
import performance.json.CanonicalJson

class BootstrapV1KnownAnswerTest :
  FunSpec(
    {
      test("matches the independently generated bootstrap-v1 byte and estimator vector") {
        val vectorBytes =
          checkNotNull(
              BootstrapV1KnownAnswerTest::class.java.getResourceAsStream(
                "/performance/protocol/test-vectors/bootstrap-v1.json"
              )
            )
            .use { it.readAllBytes() }
        val vector = CanonicalJson.parseStrict(vectorBytes).asObject()
        CanonicalJson.encode(vector) shouldBe vectorBytes
        val encoding = vector.get("encoding").asObject()
        encoding.get("cellDomain").asString() shouldBe "revoman-cell-v1\u0000"
        encoding.get("seedDomain").asString() shouldBe "revoman-bootstrap-v1\u0000"
        encoding.get("integerEncoding").asString() shouldBe "u32-unsigned-big-endian"
        encoding.get("lengthPrefix").asString() shouldBe "u32-utf8-byte-length"
        encoding.get("parameterOrder").asString() shouldBe "unsigned-utf8-key"
        encoding.get("prng").asString() shouldBe "sha256-counter-rejection-sampling"
        encoding.get("drawOrder").asString() shouldBe "baseline-before-candidate"
        encoding.get("median").asString() shouldBe "sorted-middle-or-even-arithmetic-mean"
        encoding.get("quantile").asString() shouldBe "hyndman-fan-type-7"
        encoding.get("replicates").asInt() shouldBe 20_000
        val vectorInputs = vector.get("inputs").asObject()
        vectorInputs.get("baselineCaptureId").asString() shouldBe "baseline-a1"
        vectorInputs.get("candidateCaptureId").asString() shouldBe "candidate-b"
        vectorInputs.get("cell").asObject().get("benchmark").asString() shouldBe
          "com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.revUp"

        val cell =
          CellIdentity(
            benchmark =
              "com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.revUp",
            profile = "warm",
            parameters = emptyMap(),
            mode = "ss",
            unit = "ms/op",
            threads = 1,
            batchSize = 1,
            primaryMetric = "score",
            direction = "lower-is-better",
          )
        val expectedCellHex =
          "7265766f6d616e2d63656c6c2d7631000000003b636f6d2e73616c6573666f7263652e7265766f6d616e2e62656e63686d61726b2e526576557056335761726d42656e63686d61726b2e7265765570000000047761726d00000000000000027373000000056d732f6f7000000001000000010000000573636f72650000000f6c6f7765722d69732d626574746572"
        val expectedSeedHex =
          "d52e488aa4efe5750f992d76743928ca5e46c07ad4b9467c510433bc762468d4"
        val vectorExpected = vector.get("expected").asObject()
        vectorExpected.get("cellByteCount").asInt() shouldBe 142
        vectorExpected.get("cellBytesHex").asString() shouldBe expectedCellHex
        vectorExpected.get("seedHex").asString() shouldBe expectedSeedHex
        vectorExpected
          .get("firstAcceptedBaselineIndices")
          .asArray()
          .values()
          .asSequence()
          .map { it.asInt() }
          .toList()
          .shouldContainExactly(2, 0, 2, 0, 0, 0)
        vectorExpected
          .get("firstAcceptedCandidateIndices")
          .asArray()
          .values()
          .asSequence()
          .map { it.asInt() }
          .toList()
          .shouldContainExactly(1, 1, 0, 1, 2, 2)

        val cellBytes = cell.canonicalBytes()
        cellBytes.size shouldBe 142
        HexFormat.of().formatHex(cellBytes) shouldBe expectedCellHex
        HexFormat.of()
          .formatHex(BootstrapV1.seedForTesting("baseline-a1", "candidate-b", cell)) shouldBe
          expectedSeedHex
        BootstrapV1.acceptedIndicesForTesting(
            "baseline-a1",
            "candidate-b",
            cell,
            replicate = 0,
            side = 0,
            drawCount = 6,
            populationSize = 3,
          )
          .shouldContainExactly(2, 0, 2, 0, 0, 0)
        BootstrapV1.acceptedIndicesForTesting(
            "baseline-a1",
            "candidate-b",
            cell,
            replicate = 0,
            side = 1,
            drawCount = 6,
            populationSize = 3,
          )
          .shouldContainExactly(1, 1, 0, 1, 2, 2)

        val estimate =
          BootstrapV1.estimate(
            baselineCaptureId = "baseline-a1",
            candidateCaptureId = "candidate-b",
            cell = cell,
            baseline = listOf(ForkSamples(listOf(11.0)), ForkSamples(listOf(21.0)), ForkSamples(listOf(31.0))),
            candidate = listOf(ForkSamples(listOf(9.0)), ForkSamples(listOf(19.0)), ForkSamples(listOf(29.0))),
          )

        estimate.pointRatio shouldBe 0.9047619047619048
        estimate.gainPercent shouldBe 9.523809523809524
        estimate.lower95Ratio shouldBe 0.2903225806451613
        estimate.upper95Ratio shouldBe 2.6363636363636362
      }

      test("uses the arithmetic mean for even medians and rejects invalid samples") {
        BootstrapV1.medianForTesting(listOf(4.0, 1.0, 3.0, 2.0)) shouldBe 2.5
        shouldFailBootstrap { BootstrapV1.medianForTesting(emptyList()) }
        shouldFailBootstrap { BootstrapV1.medianForTesting(listOf(1.0, 0.0)) }
        shouldFailBootstrap { BootstrapV1.medianForTesting(listOf(Double.NaN)) }
        shouldFailBootstrap { BootstrapV1.medianForTesting(listOf(Double.POSITIVE_INFINITY)) }
      }

      test("orders parameter keys by unsigned UTF-8 bytes rather than UTF-16 code units") {
        val supplementary = "\uD800\uDC00"
        val privateUse = "\uE000"
        val bytes =
          CellIdentity(
              benchmark = "example.Benchmark.measure",
              profile = "warm",
              parameters = linkedMapOf(supplementary to "later", privateUse to "earlier"),
              mode = "ss",
              unit = "ms/op",
              threads = 1,
              batchSize = 1,
              primaryMetric = "score",
              direction = "lower-is-better",
            )
            .canonicalBytes()

        bytes.indexOf(privateUse.encodeToByteArray()) shouldBeLessThan
          bytes.indexOf(supplementary.encodeToByteArray())
      }
    }
  )

private inline fun shouldFailBootstrap(block: () -> Unit) {
  runCatching(block).isFailure shouldBe true
}

private fun ByteArray.indexOf(needle: ByteArray): Int =
  indices.first { start ->
    start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
  }

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.support.DistributionFixture.Companion.EXPECTED_BENCHMARK

class JmhResultCanonicalizerTest :
  FunSpec(
    {
      test("canonical projection drops derived nonfinite fields but preserves raw fork observations") {
        val raw = resource("valid-derived-nonfinite.json")
        val expected =
          ExpectedCells(listOf(ExpectedCell(EXPECTED_BENCHMARK, mapOf("scenario" to "fixture"))))

        val result =
          JmhResultCanonicalizer()
            .canonicalize(raw, geometry(), expected)
            .shouldBeInstanceOf<JmhCanonicalization.Valid>()

        result.rawInputSha256 shouldBe Sha256.digest(raw)
        CanonicalJson.encode(CanonicalJson.parseStrict(result.canonicalBytes)) shouldBe
          result.canonicalBytes
        result.canonicalBytes.decodeToString() shouldNotContain "NaN"
        result.canonicalBytes.decodeToString() shouldNotContain "scoreConfidence"
        result.canonicalBytes.decodeToString() shouldNotContain "scoreError"
        result.canonicalBytes.decodeToString() shouldContain "\"rawData\":[[1.25]]"
        result.cells.single().derivedForkSummaries.map { it.score.toPlainString() } shouldContainExactly
          listOf("1.25")
      }

      test("row identity includes benchmark and exact parameters independent of JSON object order") {
        val expected =
          ExpectedCells(
            listOf(
              ExpectedCell(
                EXPECTED_BENCHMARK,
                mapOf("scenario" to "fixture", "transport" to "wire"),
              ),
            ),
          )
        val raw = validJmhBytes(params = "\"transport\":\"wire\",\"scenario\":\"fixture\"")

        val result =
          JmhResultCanonicalizer()
            .canonicalize(raw, geometry(), expected)
            .shouldBeInstanceOf<JmhCanonicalization.Valid>()

        result.cells.single().parameters.toList() shouldContainExactly
          listOf("scenario" to "fixture", "transport" to "wire")
        result.canonicalBytes.decodeToString() shouldContain
          "\"params\":{\"scenario\":\"fixture\",\"transport\":\"wire\"}"
      }

      test("exact row-set validation distinguishes duplicates missing rows and extra rows") {
        val expected =
          ExpectedCells(listOf(ExpectedCell(EXPECTED_BENCHMARK, mapOf("scenario" to "fixture"))))
        val validRow = validJmhBytes().decodeToString().trim().removePrefix("[").removeSuffix("]")
        val extraRow = validRow.replace(EXPECTED_BENCHMARK, EXTRA_BENCHMARK)
        val cases =
          mapOf(
            "[$validRow,$validRow]" to CaptureFailure.DUPLICATE_RESULT_ROW,
            "[$extraRow]" to CaptureFailure.MISSING_RESULT_ROW,
            "[$validRow,$extraRow]" to CaptureFailure.EXTRA_RESULT_ROW,
          )

        cases.forEach { (input, failure) ->
          val result =
            JmhResultCanonicalizer()
              .canonicalize(input.encodeToByteArray(), geometry(), expected)
              .shouldBeInstanceOf<JmhCanonicalization.Invalid>()

          result.reasons shouldContain failure
        }
      }

      test("nonpositive and nonfinite raw observations are never sanitized into valid evidence") {
        mapOf(
            "0" to CaptureFailure.NONPOSITIVE_PRIMARY_OBSERVATION,
            "-1" to CaptureFailure.NONPOSITIVE_PRIMARY_OBSERVATION,
            "NaN" to CaptureFailure.NONFINITE_PRIMARY_OBSERVATION,
            "Infinity" to CaptureFailure.NONFINITE_PRIMARY_OBSERVATION,
            "-Infinity" to CaptureFailure.NONFINITE_PRIMARY_OBSERVATION,
          )
          .forEach { (observation, failure) ->
            val result =
              JmhResultCanonicalizer()
                .canonicalize(
                  validJmhBytes(rawObservation = observation),
                  geometry(),
                  ExpectedCells(
                    listOf(ExpectedCell(EXPECTED_BENCHMARK, mapOf("scenario" to "fixture"))),
                  ),
                )
                .shouldBeInstanceOf<JmhCanonicalization.Invalid>()

            result.reasons shouldContain failure
          }
      }

      test("geometry mismatches fail instead of accepting JMH annotation defaults") {
        val result =
          JmhResultCanonicalizer()
            .canonicalize(
              validJmhBytes(),
              geometry().copy(forks = 10),
              ExpectedCells(
                listOf(ExpectedCell(EXPECTED_BENCHMARK, mapOf("scenario" to "fixture"))),
              ),
            )
            .shouldBeInstanceOf<JmhCanonicalization.Invalid>()

        result.reasons shouldContain CaptureFailure.RESULT_GEOMETRY_MISMATCH
      }

      test("warmup batch-size mismatches fail closed") {
        val raw =
          validJmhBytes()
            .decodeToString()
            .replace("\"warmupBatchSize\":1", "\"warmupBatchSize\":2")
            .encodeToByteArray()

        val result =
          JmhResultCanonicalizer()
            .canonicalize(
              raw,
              geometry(),
              ExpectedCells(
                listOf(ExpectedCell(EXPECTED_BENCHMARK, mapOf("scenario" to "fixture"))),
              ),
            )
            .shouldBeInstanceOf<JmhCanonicalization.Invalid>()

        result.reasons shouldContain CaptureFailure.RESULT_GEOMETRY_MISMATCH
      }
    },
  )

private fun geometry(): CaptureGeometry =
  CaptureGeometry(
    forks = 1,
    warmupIterations = 0,
    measurementIterations = 1,
    batchSize = 1,
    threads = 1,
    mode = "ss",
    unit = "ms",
  )

private fun resource(name: String): ByteArray =
  checkNotNull(JmhResultCanonicalizerTest::class.java.getResourceAsStream("/performance/jmh/$name")) {
    "missing JMH fixture $name"
  }.use { it.readAllBytes() }

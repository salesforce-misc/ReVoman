/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import java.util.TimeZone
import performance.json.CanonicalJson
import performance.hash.Sha256
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.node.JsonNodeFactory

class ComparisonRendererGoldenTest :
  FunSpec(
    {
      test("candidate JSON and Markdown match golden bytes under distinct locales and timezones") {
        val originalLocale = Locale.getDefault()
        val originalTimeZone = TimeZone.getDefault()
        try {
          val renders =
            listOf(
                Locale.ROOT to TimeZone.getTimeZone("UTC"),
                Locale.forLanguageTag("tr-TR") to TimeZone.getTimeZone("Asia/Kolkata"),
              )
              .map { (locale, timeZone) ->
                Locale.setDefault(locale)
                TimeZone.setDefault(timeZone)
                deterministicCandidateRender()
              }

          renders[0].json shouldBe golden("comparison/candidate.json")
          renders[0].markdown shouldBe golden("comparison/candidate.md")
          renders[1].json shouldBe renders[0].json
          renders[1].markdown shouldBe renders[0].markdown
          renders[0].markdown.decodeToString() shouldContain
            "Candidate/baseline ratio interval"
          renders[0].markdown.decodeToString() shouldNotContain "ratio interval (%)"
        } finally {
          Locale.setDefault(originalLocale)
          TimeZone.setDefault(originalTimeZone)
        }
      }

      test("comparison output satisfies its strict schema") {
        val completed = completedCandidateComparison()
        val validator = EvidenceSchemaValidator()

        validator.validate(SchemaKind.COMPARISON, completed.jsonBytes).shouldBeEmpty()
        val mutated = CanonicalJson.parseStrict(completed.jsonBytes).asObject().apply { put("extra", true) }
        validator
          .validate(SchemaKind.COMPARISON, CanonicalJson.encode(mutated))
          .shouldNotBeEmpty()
      }

      test("provisional calibration and regression policy schemas are strict") {
        val factory = JsonNodeFactory.instance
        val calibration =
          factory.objectNode().apply {
            put("schemaVersion", "calibration-provisional-v1")
            put("attemptId", "warm-10-1")
            put("profile", "warm")
            put("forks", 10)
            put("performanceSessionId", "session")
            put("a1CaptureId", "a1")
            put("a2CaptureId", "a2")
            put("a1Sequence", 1)
            put("a2Sequence", 2)
            put("passingCellsSha256", "6".repeat(64))
            put("passed", true)
          }
        val policy =
          factory.objectNode().apply {
            put("schemaVersion", "regression-policy-v1")
            put("maximumRegressionBudget", 0.05)
          }
        val validator = EvidenceSchemaValidator()

        validator
          .validate(SchemaKind.CALIBRATION_PROVISIONAL, CanonicalJson.encode(calibration))
          .shouldBeEmpty()
        validator
          .validate(SchemaKind.REGRESSION_POLICY, CanonicalJson.encode(policy))
          .shouldBeEmpty()
        calibration.put("unexpected", "value")
        policy.put("maximumRegressionBudget", -0.01)
        validator
          .validate(SchemaKind.CALIBRATION_PROVISIONAL, CanonicalJson.encode(calibration))
          .shouldNotBeEmpty()
        validator
          .validate(SchemaKind.REGRESSION_POLICY, CanonicalJson.encode(policy))
          .shouldNotBeEmpty()
      }

      test("comparison schema enumerates every stable compatibility reason code") {
        val schema =
          CanonicalJson.parseStrict(
              checkNotNull(
                  ComparisonRendererGoldenTest::class.java.getResourceAsStream(
                    "/performance/protocol/schemas/comparison-v1.schema.json"
                  )
                )
                .use { stream -> stream.readAllBytes() }
            )
            .asObject()
        val schemaReasons =
          schema
            .get("\$defs")
            .asObject()
            .get("compatibilityReason")
            .asObject()
            .get("enum")
            .asArray()
            .values()
            .asSequence()
            .map { value -> value.asString() }
            .toList()

        schemaReasons.shouldContainExactlyInAnyOrder(
          CompatibilityFailure.entries.map { reason ->
            reason.name.lowercase().replace('_', '-')
          }
        )
      }
    }
  )

private fun golden(relativePath: String): ByteArray =
  checkNotNull(
      ComparisonRendererGoldenTest::class.java.getResourceAsStream(
        "/performance/golden/$relativePath"
      )
    )
    .use { stream -> stream.readAllBytes() }

private fun deterministicCandidateRender(): RenderedComparison {
  val document = completedCandidateComparison().document
  return ComparisonRenderer().render(
    document.copy(
      baseline =
        document.baseline.copy(
          bundleSha256 = Sha256.parse("9a6fc8d8943f026d699ee8493d887f4450f8b6084a5c8f533f19170561f2bcd1"),
          captureSha256 = Sha256.parse("f89eb1a29b988cdb3a4eb1665a289d6a17145efb3765b4ea755e34e208734444"),
        ),
      candidate =
        document.candidate.copy(
          bundleSha256 = Sha256.parse("b0ae5ce003ac873bbb4a635ebb80886cb37ca9967ab71b3441dd5d6b531301d7"),
          captureSha256 = Sha256.parse("04e5f6730aae3e4dce1ad2e145d3e2595d5fa4fca84924489736cefb883cd1d4"),
        ),
      calibration = document.calibration?.copy(evidenceSha256 = Sha256.parse("5b926fc19909c6ddf481192392b36d3a2d972f59aa4bf9adf1875881dcc21790")),
      implementation =
        document.implementation.copy(
          protocolSha256 = Sha256.parse("c0a032da7c676241c10d900ca120e422aa7859638a038e13c8f90e4b74c54614"),
        ),
    ),
  )
}

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
                CaptureComparator()
                  .compare(candidateRequest())
                  .shouldBeInstanceOf<ComparisonComputation.Completed>()
              }

          renders[0].jsonBytes shouldBe golden("comparison/candidate.json")
          renders[0].markdownBytes shouldBe golden("comparison/candidate.md")
          renders[1].jsonBytes shouldBe renders[0].jsonBytes
          renders[1].markdownBytes shouldBe renders[0].markdownBytes
          renders[0].markdownBytes.decodeToString() shouldContain
            "Candidate/baseline ratio interval"
          renders[0].markdownBytes.decodeToString() shouldNotContain "ratio interval (%)"
        } finally {
          Locale.setDefault(originalLocale)
          TimeZone.setDefault(originalTimeZone)
        }
      }

      test("comparison output satisfies its strict schema") {
        val completed =
          CaptureComparator()
            .compare(candidateRequest())
            .shouldBeInstanceOf<ComparisonComputation.Completed>()
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

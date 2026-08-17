/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import performance.hash.Sha256
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

class ProfilerSummaryTest :
  FunSpec(
    {
      test("GC summary publishes only the frozen allocation and collection counters") {
        val result =
          ProfilerSummary.fromGc(
              GcProfilerInput(
                captureId = "capture-1",
                rawInputSha256 = Sha256.parse(TEST_SHA),
                variantSha256 = Sha256.parse("b".repeat(64)),
                durationNanos = 1_000_000,
                secondaryMetrics =
                  mapOf(
                    "gc.alloc.rate.norm" to BigDecimal("4096.0"),
                    "gc.count" to BigDecimal("2"),
                    "gc.time" to BigDecimal("1.5"),
                    "gc.alloc.rate" to BigDecimal("99.0"),
                    "custom.secret.metric" to BigDecimal("7"),
                  ),
              ),
            )
            .shouldBeInstanceOf<ProfilerSummaryBuild.Valid>()

        result.summary.profiler.kind shouldBe "gc"
        result.summary.gcCounters shouldBe
          GcCounters(
            allocationBytesPerOperation = BigDecimal("4096.0"),
            collections = BigDecimal("2"),
            collectionTimeMillis = BigDecimal("1.5"),
          )
        result.canonicalBytes.decodeToString() shouldContain "gc.alloc.rate.norm"
        result.canonicalBytes.decodeToString() shouldNotContain "gc.alloc.rate\""
        result.canonicalBytes.decodeToString() shouldNotContain "custom.secret.metric"
        EvidenceSchemaValidator()
          .validate(SchemaKind.PROFILER_SUMMARY, result.canonicalBytes)
          .isEmpty() shouldBe true
      }

      test("GC summary rejects missing nonfinite or negative completeness counters") {
        val valid =
          mapOf(
            "gc.alloc.rate.norm" to BigDecimal("4096"),
            "gc.count" to BigDecimal("2"),
            "gc.time" to BigDecimal("1.5"),
          )
        mapOf(
            "missing allocation" to valid - "gc.alloc.rate.norm",
            "missing collections" to valid - "gc.count",
            "missing collection time" to valid - "gc.time",
            "negative allocation" to valid + ("gc.alloc.rate.norm" to BigDecimal("-1")),
          )
          .forEach { (_, metrics) ->
            val result =
              ProfilerSummary.fromGc(
                  GcProfilerInput(
                    "capture-1",
                    Sha256.parse(TEST_SHA),
                    Sha256.parse(TEST_SHA),
                    1,
                    metrics,
                  ),
                )
                .shouldBeInstanceOf<ProfilerSummaryBuild.Invalid>()

            result.reasons shouldContain ProfilerSummaryFailure.INCOMPLETE_GC_METRICS
          }
      }

      test("summary encoding rejects unsafe symbols and missing duration completeness") {
        shouldThrow<IllegalArgumentException> {
          ProfilerSummary(
              captureId = "capture-1",
              rawInputSha256 = Sha256.parse(TEST_SHA),
              durationNanos = 0,
              profiler = ProfilerIdentity.jfr(Sha256.parse(TEST_SHA), Sha256.parse(TEST_SHA)),
              droppedSamples = DroppedSamples(0, 0),
              aggregates =
                listOf(
                  ProfilerAggregate(
                    category = "application",
                    className = "/Users/alice/Secret",
                    methodName = "run",
                    executionSamples = 1,
                    allocationBytes = 0,
                    lockEvents = 0,
                    ioBytes = 0,
                  ),
                ),
            )
            .canonicalBytes()
        }
      }
    },
  )

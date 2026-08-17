/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.runner.RunnerExit
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import performance.support.CaptureBundleFixture
import performance.support.ComparisonBundleFixture
import performance.support.DistributionFixture
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

class CaptureComparatorTest :
  FunSpec(
    {
      test("comparison request exposes only verifier-owned path inputs") {
        ComparisonRequest::class.java.declaredFields.associate { field -> field.name to field.type } shouldBe
          mapOf(
            "runnerDistribution" to Path::class.java,
            "kind" to ComparisonKind::class.java,
            "baseline" to Path::class.java,
            "candidate" to Path::class.java,
            "calibration" to Path::class.java,
            "regressionPolicy" to RegressionPolicy::class.java,
          )
        CaptureComparator::class.java.constructors
          .filterNot { constructor -> constructor.isSynthetic }
          .map { constructor -> constructor.parameterTypes.toList() } shouldBe
          listOf(listOf(ComparisonRenderer::class.java))
        CaptureComparator.Companion::class.java.declaredMethods
          .filter { method -> method.name.startsWith("forTest") }
          .also { methods -> methods.isNotEmpty() shouldBe true }
          .all { method -> method.isSynthetic } shouldBe true
      }

      test("sealed capture paths reach the comparator verifier without caller proofs") {
        withCalibrationScenario { scenario ->
          val incompatible =
            scenario.comparator()
              .compare(scenario.request(baseline = scenario.a1.root, candidate = scenario.a1.root))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()

          incompatible.estimatesCreated shouldBe false
          incompatible.reasons shouldContain CompatibilityFailure.SAME_CAPTURE
        }
      }

      test("compatible A/A captures produce deterministic diagnostic estimates") {
        withCalibrationScenario { scenario ->
          val result = scenario.comparator().compare(scenario.request())
          if (result is ComparisonComputation.Incompatible) {
            val violations =
              EvidenceSchemaValidator()
                .validate(SchemaKind.CAPTURE, Files.readAllBytes(scenario.a1.root.resolve("capture.json")))
            error("${result.reasons}: $violations")
          }
          val completed = result.shouldBeInstanceOf<ComparisonComputation.Completed>()

          completed.exit shouldBe RunnerExit.SUCCESS
          completed.document.kind shouldBe ComparisonKind.CALIBRATION
          completed.document.strength shouldBe ComparisonStrength.DIAGNOSTIC
          completed.document.compatibility shouldBe ComparisonCompatibility.COMPATIBLE
          completed.document.cells.single().estimate.pointRatio shouldBe 1.0
          completed.document.cells.single().direction shouldBe DirectionOutcome.INCONCLUSIVE
          completed.document.cells.single().policy shouldBe PolicyOutcome.NOT_ENFORCED
          completed.document.calibration?.passed shouldBe true
        }
      }

      test("compatible multicell captures estimate every verified cell independently") {
        withCalibrationScenario(multiCell = true) { scenario ->
          val completed =
            scenario
              .comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Completed>()

          completed.document.cells.size shouldBe 2
          completed.document.cells.map { cell -> cell.identity.parameters }.toSet() shouldBe
            setOf(emptyMap(), mapOf("variant" to "secondary"))
          completed.document.cells.forEach { cell ->
            cell.estimate.pointRatio shouldBe 1.0
            cell.direction shouldBe DirectionOutcome.INCONCLUSIVE
          }
        }
      }

      test("bundle proof rejects unsealed, schema-invalid, and checksum-invalid inputs") {
        val mutations =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture -> capture.deleteAndReseal("stderr.log") }) to
              CompatibilityFailure.BUNDLE_UNSEALED,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture { document -> document.put("schemaVersion", "capture-v2") }
            }) to CompatibilityFailure.BUNDLE_SCHEMA_INVALID,
            ({ capture: CaptureBundleFixture -> capture.writeRaw("capture.json", "{}".encodeToByteArray()) }) to
              CompatibilityFailure.BUNDLE_CHECKSUM_INVALID,
          )
        mutations.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a1)
            val incompatible =
              scenario.comparator()
                .compare(scenario.request())
                .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            incompatible.reasons shouldContain expected
          }
        }
      }

      test("incompatibility renders enumerated reasons without creating estimates") {
        withCalibrationScenario { scenario ->
          val incompatible =
            scenario.comparator()
              .compare(scenario.request(candidate = scenario.a1.root))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()

          incompatible.estimatesCreated shouldBe false
          incompatible.reasons shouldBe incompatible.reasons.sortedBy(Enum<*>::name)
          incompatible.jsonBytes.decodeToString() shouldContainText "\"same-capture\""
          incompatible.jsonBytes.decodeToString() shouldNotContain "\"estimate\""
          incompatible.markdownBytes.decodeToString() shouldContainText "same-capture"
          EvidenceSchemaValidator()
            .validate(SchemaKind.COMPARISON, incompatible.jsonBytes)
            .shouldBeEmpty()
        }
      }

      test("capture-owned host document references may differ without changing compatibility") {
        withCalibrationScenario { scenario ->
          scenario.a2.mutateCapture { document ->
            document
              .objectNode("qualification")
              .objectNode("preflight")
              .put("sha256", "8".repeat(64))
          }

          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Completed>()
        }
      }

      test("each capture qualification policy must bind its own protocol identity") {
        withCalibrationScenario { scenario ->
          scenario.a2.mutateCapture { document ->
            document.objectNode("qualification").put("policyHash", "e".repeat(64))
          }

          val incompatible =
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          incompatible.reasons shouldContain CompatibilityFailure.QUALIFICATION_POLICY_MISMATCH
        }
      }

      test("normalizes the capture direction to the frozen cell identity token") {
        withCalibrationScenario { scenario ->
          val completed =
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Completed>()

          completed.document.cells.single().identity.direction shouldBe "lower-is-better"
        }
      }

      test("malformed empty fork samples fail compatibility without escaping an exception") {
        withCalibrationScenario { scenario ->
          mutateFirstRow(scenario.a2) { row ->
            row.objectNode("primaryMetric").arrayNode("rawData").get(0).asArray().removeAll()
          }

          val incompatible =
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          incompatible.estimatesCreated shouldBe false
          incompatible.reasons shouldContain CompatibilityFailure.SAMPLE_DIMENSION_MISMATCH
        }
      }

      test("validates persisted fork means independently from estimator fork medians") {
        withCalibrationScenario { scenario ->
          val observations = (1..10).map(Int::toDouble)
          scenario.a2.replaceForkSamples(
            listOf(observations) + List(9) { List(10) { 10.0 } },
          )

          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Completed>()
        }
      }

      test("rejects identity, profiler, sample, cell, and immutable-key incompatibilities") {
        val cases =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture ->
              capture.addAndReseal("profiler-summary.json", "{}".encodeToByteArray())
            }) to
              CompatibilityFailure.PROFILER_PRESENT,
            ({ capture: CaptureBundleFixture ->
              mutateFirstRow(capture) { row ->
                row.objectNode("primaryMetric").arrayNode("rawData").get(0).asArray().set(0, 0.0)
              }
            }) to CompatibilityFailure.INVALID_PRIMARY_SAMPLE,
            ({ capture: CaptureBundleFixture -> capture.replaceForkSamples(List(9) { List(10) { 10.0 } }) }) to
              CompatibilityFailure.UNDERSAMPLED_CELL,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("runtime").objectNode("linux").put("kernel", "6.12.77-linuxkit")
              }
            }) to CompatibilityFailure.RUNTIME_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.arrayNode("cells").get(0).asObject().put("benchmark", "example.Other.measure")
              }
            }) to CompatibilityFailure.CELL_IDENTITY_MISMATCH,
          )
        cases.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a2)
            val incompatible =
              scenario.comparator()
                .compare(scenario.request())
                .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            incompatible.reasons shouldContain expected
            incompatible.estimatesCreated shouldBe false
          }
        }
      }

      test("rejects every frozen execution and capture identity mismatch before arithmetic") {
        val cases =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("protocol").put("hostAdapterSha256", "e".repeat(64))
              }
            }) to CompatibilityFailure.PROTOCOL_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("artifacts")
                  .objectNode("executingRunner")
                  .put("sha256", "e".repeat(64))
              }
            }) to CompatibilityFailure.ARTIFACT_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("toolchain").put("jmhCoreVersion", "1.36")
              }
            }) to CompatibilityFailure.TOOLCHAIN_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("logging").put("configurationSha256", "e".repeat(64))
              }
            }) to CompatibilityFailure.LOGGING_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("protocol").put("comparatorSha256", "e".repeat(64))
              }
            }) to CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH,
          )
        cases.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a2)
            val incompatible =
              scenario.comparator()
                .compare(scenario.request())
                .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            incompatible.reasons shouldContain expected
            incompatible.estimatesCreated shouldBe false
          }
        }
      }

      test("calibration requires same treatment and complete distribution identity") {
        val cases =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("provenance")
                  .objectNode("treatment")
                  .put("gitSha", "9".repeat(40))
              }
            }) to CompatibilityFailure.CALIBRATION_TREATMENT_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("artifacts")
                  .objectNode("distribution")
                  .put("sha256", "9".repeat(64))
              }
            }) to CompatibilityFailure.CALIBRATION_DISTRIBUTION_MISMATCH,
          )
        cases.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a2)
            val incompatible =
              scenario.comparator()
                .compare(scenario.request())
                .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            incompatible.reasons shouldContain expected
          }
        }
      }

      test("calibration and candidate roles require one session with consecutive A1 A2 B order") {
        withCalibrationScenario(a2Session = "other-session") { scenario ->
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
        }
        withCandidateScenario(bSequence = 4) { scenario ->
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
        }
      }

      test("candidate comparison permits only declared treatment and production deltas") {
        withCandidateScenario { scenario ->
          val completed =
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Completed>()

          completed.exit shouldBe RunnerExit.SUCCESS
          completed.document.kind shouldBe ComparisonKind.CANDIDATE
          completed.document.strength shouldBe ComparisonStrength.DIAGNOSTIC
          completed.document.calibration?.a2CaptureId shouldBe "a2"
          completed.document.calibration?.bCaptureId shouldBe "b"
          completed.document.calibration?.passed shouldBe true
        }
      }

      test("candidate comparison rejects non-distinct or non-derived production deltas") {
        withCandidateScenario(bTreatment = "1".repeat(40)) { scenario ->
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.CANDIDATE_TREATMENT_NOT_DISTINCT
        }
        withCandidateScenario(distinctProduction = false) { scenario ->
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.CANDIDATE_PRODUCTION_NOT_DISTINCT
        }
        withCandidateScenario { scenario ->
          scenario.b.mutateCapture {
            it.objectNode("artifacts")
              .arrayNode("orderedClasspath")
              .get(2)
              .asObject()
              .put("sha256", "d".repeat(64))
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.CANDIDATE_DELTA_INVALID
        }
      }

      test("candidate comparison rejects forbidden harness and dependency deltas") {
        withCandidateScenario { scenario ->
          scenario.b.mutateCapture {
            it.objectNode("provenance")
              .objectNode("immutableHarness")
              .put("gitSha", "8".repeat(40))
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IMMUTABLE_HARNESS_MISMATCH
        }
        withCandidateScenario { scenario ->
          scenario.b.mutateCapture {
            it.objectNode("artifacts")
              .arrayNode("dependencies")
              .get(0)
              .asObject()
              .put("sha256", "8".repeat(64))
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.DEPENDENCY_MISMATCH
        }
      }

      test("candidate comparison permits clean freezer and adapter checkout provenance deltas") {
        withCandidateScenario { scenario ->
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Completed>()
        }
      }

      test("direction and policy endpoint equality follow frozen inclusive rules") {
        CaptureComparator.directionForTesting(0.9, 0.999) shouldBe DirectionOutcome.IMPROVEMENT
        CaptureComparator.directionForTesting(0.9, 1.0) shouldBe DirectionOutcome.INCONCLUSIVE
        CaptureComparator.directionForTesting(1.0, 1.1) shouldBe DirectionOutcome.INCONCLUSIVE
        CaptureComparator.directionForTesting(1.001, 1.1) shouldBe DirectionOutcome.REGRESSION
        CalibrationQualification.passes(0.95, 0.95, 1.05) shouldBe true
        CalibrationQualification.passes(1.05, 1.0, 1.10) shouldBe true
        CaptureComparator.policyForTesting(0.9, 1.05, 0.05) shouldBe PolicyOutcome.PASS
        CaptureComparator.policyForTesting(1.05, 1.06, 0.05) shouldBe PolicyOutcome.INCONCLUSIVE
        CaptureComparator.policyForTesting(1.051, 1.06, 0.05) shouldBe PolicyOutcome.FAIL
      }

      test("enforced policy maps aggregate failure and uncertainty to stable exits") {
        CaptureComparator.aggregatePolicyForTesting(
          listOf(PolicyOutcome.PASS, PolicyOutcome.FAIL, PolicyOutcome.INCONCLUSIVE),
        ) shouldBe PolicyOutcome.FAIL
        CaptureComparator.aggregatePolicyForTesting(
          listOf(PolicyOutcome.PASS, PolicyOutcome.INCONCLUSIVE),
        ) shouldBe PolicyOutcome.INCONCLUSIVE
        CaptureComparator.aggregatePolicyForTesting(
          listOf(PolicyOutcome.PASS, PolicyOutcome.PASS),
        ) shouldBe PolicyOutcome.PASS
      }

      test("regression policy derives identity only from strict canonical bytes") {
        val bytes =
          CanonicalJson.encode(
            tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().apply {
              put("schemaVersion", "regression-policy-v1")
              put("maximumRegressionBudget", 0.05)
            }
          )
        val policy = RegressionPolicy.parse(bytes)

        policy.maximumRegressionBudget shouldBe 0.05
        policy.sha256 shouldBe Sha256.digest(bytes)
        shouldThrow<IllegalArgumentException> {
          RegressionPolicy.parse(bytes + ' '.code.toByte())
        }
      }

      test("capture bundle verifier rejects exact-layout and noncanonical mutations") {
        val mutations =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture -> capture.addAndReseal("unexpected.txt", byteArrayOf(1)) }) to
              CompatibilityFailure.BUNDLE_UNSEALED,
            ({ capture: CaptureBundleFixture ->
              val bytes = Files.readAllBytes(capture.root.resolve("capture.json"))
              capture.writeRaw("capture.json", bytes + ' '.code.toByte())
              capture.reseal()
            }) to CompatibilityFailure.BUNDLE_SCHEMA_INVALID,
            ({ capture: CaptureBundleFixture ->
              val bytes = Files.readAllBytes(capture.root.resolve("jmh-result.json"))
              capture.writeRaw("jmh-result.json", bytes + ' '.code.toByte())
              capture.reseal()
            }) to CompatibilityFailure.BUNDLE_SCHEMA_INVALID,
          )
        mutations.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a1)
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain expected
          }
        }
      }

      test("capture bundle verifier rejects JMH row, dimension, sample, and mean mutations") {
        val mutations =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture ->
              mutateFirstRow(capture) { it.put("benchmark", "example.Other.measure") }
            }) to CompatibilityFailure.CELL_IDENTITY_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.arrayNode("cells")
                  .get(0)
                  .asObject()
                  .objectNode("sampleDimensions")
                  .put("samplesPerFork", 9)
              }
            }) to CompatibilityFailure.SAMPLE_DIMENSION_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              mutateFirstRow(capture) {
                it.objectNode("primaryMetric").arrayNode("rawData").get(0).asArray().set(0, -1.0)
              }
            }) to CompatibilityFailure.INVALID_PRIMARY_SAMPLE,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.arrayNode("cells")
                  .get(0)
                  .asObject()
                  .arrayNode("derivedForkSummaries")
                  .get(0)
                  .asObject()
                  .put("score", 9.0)
              }
            }) to CompatibilityFailure.DERIVED_SUMMARY_MISMATCH,
          )
        mutations.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a2)
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain expected
          }
        }
      }

      test("candidate calibration directory is checksum schema and capture bound") {
        val mutations =
          listOf<Pair<(ComparisonBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ bundle: ComparisonBundleFixture -> bundle.writeRaw("comparison.json", byteArrayOf(1)) }) to
              CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID,
            ({ bundle: ComparisonBundleFixture -> bundle.mutateDocument { it.put("kind", "candidate") } }) to
              CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID,
            ({ bundle: ComparisonBundleFixture ->
              bundle.mutateDocument {
                it.objectNode("candidate").put("captureId", "unrelated-a2")
                it.objectNode("calibration").put("a2CaptureId", "unrelated-a2")
              }
            }) to CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH,
            ({ bundle: ComparisonBundleFixture -> bundle.addAndReseal("extra.txt", byteArrayOf(1)) }) to
              CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID,
            ({ bundle: ComparisonBundleFixture ->
              bundle.mutateDocument {
                it.arrayNode("cells").get(0).asObject().put("directionOutcome", "regression")
              }
            }) to CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID,
            ({ bundle: ComparisonBundleFixture ->
              bundle.mutateDocument {
                it.arrayNode("cells")
                  .get(0)
                  .asObject()
                  .objectNode("estimate")
                  .put("gainPercent", 123.0)
              }
            }) to CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID,
          )
        mutations.forEach { (mutation, expected) ->
          withCandidateScenario { scenario ->
            mutation(scenario.calibration)
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain expected
          }
        }
      }

      test("capture verifier rejects nested JMH shape coercions and positive-double underflow") {
        val mutations =
          listOf<(CaptureBundleFixture) -> Unit>(
            { capture ->
              mutateFirstRowAndBind(capture) { row -> row.put("params", "not-an-object") }
            },
            { capture ->
              mutateFirstRowAndBind(capture) { row ->
                row.objectNode("params").put("variant", 1)
              }
            },
            { capture ->
              mutateFirstRowAndBind(capture) { row ->
                row.objectNode("primaryMetric").put("unexpected", true)
              }
            },
            { capture ->
              mutateFirstRowAndBind(capture) { row ->
                row.set("secondaryMetrics", tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode())
              }
            },
          )
        mutations.forEach { mutation ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a2)
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain CompatibilityFailure.CELL_IDENTITY_MISMATCH
          }
        }
        withCalibrationScenario { scenario ->
          mutateFirstRowAndBind(scenario.a2) { row ->
            row.objectNode("primaryMetric")
              .arrayNode("rawData")
              .get(0)
              .asArray()
              .set(
                0,
                tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(
                  java.math.BigDecimal("1e-9999")
                ),
              )
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.INVALID_PRIMARY_SAMPLE
        }
      }

      test("capture verifier rejects symlink ancestors and never mints projections on failure") {
        withCalibrationScenario { scenario ->
          scenario.a1.addAndReseal("unexpected.txt", byteArrayOf(1))
          val verification = CaptureBundleVerifier.verify(scenario.a1.root)
          verification.projection shouldBe null
          verification.failures shouldContain CompatibilityFailure.BUNDLE_UNSEALED
        }
        withCalibrationScenario { scenario ->
          val aliasParent = Files.createTempDirectory("capture-alias-").toRealPath()
          val alias = aliasParent.resolve("linked-parent")
          try {
            Files.createSymbolicLink(alias, scenario.a1.root.parent)
            scenario.comparator()
              .compare(scenario.request(baseline = alias.resolve("capture")))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain CompatibilityFailure.BUNDLE_UNSEALED
          } finally {
            Files.deleteIfExists(alias)
            aliasParent.toFile().deleteRecursively()
          }
        }
      }

      test("verified projections defensively freeze cell and sample collections") {
        withCalibrationScenario { scenario ->
          val projection = checkNotNull(CaptureBundleVerifier.verify(scenario.a1.root).projection)

          shouldThrow<RuntimeException> {
            @Suppress("UNCHECKED_CAST")
            (projection.cells as MutableList<CellIdentity>).clear()
          }
          shouldThrow<RuntimeException> {
            @Suppress("UNCHECKED_CAST")
            (projection.samples as MutableMap<CellIdentity, List<ForkSamples>>).clear()
          }
          shouldThrow<RuntimeException> {
            @Suppress("UNCHECKED_CAST")
            (projection.samples.values.single() as MutableList<ForkSamples>).clear()
          }
          shouldThrow<RuntimeException> {
            @Suppress("UNCHECKED_CAST")
            (projection.samples.values.single().single().measurements as MutableList<Double>).clear()
          }
        }
      }

      test("every capture provenance role must be clean") {
        listOf("treatment", "immutableHarness", "distributionFreezer", "captureRunner").forEach {
          role ->
          withCalibrationScenario { scenario ->
            scenario.a2.mutateCapture {
              it.objectNode("provenance").objectNode(role).put("treeClean", false)
            }
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain CompatibilityFailure.BUNDLE_SCHEMA_INVALID
          }
        }
      }

      test("pairwise-equal frozen identity tampering cannot substitute another closure member") {
        val cases =
          listOf<Pair<(CaptureBundleFixture) -> Unit, CompatibilityFailure>>(
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("protocol").put("benchmarkSourceSha256", "a".repeat(64))
              }
            }) to CompatibilityFailure.PROTOCOL_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("protocol").put("workloadTreeSha256", "a".repeat(64))
              }
            }) to CompatibilityFailure.PROTOCOL_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("logging").put("configurationSha256", "b".repeat(64))
              }
            }) to CompatibilityFailure.LOGGING_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("toolchain").put("schemaVersion", "alternate-schema")
              }
            }) to CompatibilityFailure.TOOLCHAIN_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("toolchain").put("sanitizerVersion", "alternate-sanitizer")
              }
            }) to CompatibilityFailure.TOOLCHAIN_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("runtime")
                  .objectNode("security")
                  .put("user", "10002:10002")
              }
            }) to CompatibilityFailure.RUNTIME_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("runtime").objectNode("environment").remove("LC_ALL")
              }
            }) to CompatibilityFailure.BUNDLE_SCHEMA_INVALID,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("provenance").objectNode("treatment").put("gitSha", "8".repeat(40))
              }
            }) to CompatibilityFailure.ARTIFACT_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("provenance")
                  .objectNode("immutableHarness")
                  .put("gitSha", "8".repeat(40))
              }
            }) to CompatibilityFailure.ARTIFACT_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("provenance")
                  .objectNode("distributionFreezer")
                  .put("gitSha", "8".repeat(40))
              }
            }) to CompatibilityFailure.ARTIFACT_MISMATCH,
            ({ capture: CaptureBundleFixture ->
              capture.mutateCapture {
                it.objectNode("protocol").put("comparatorSha256", "4".repeat(64))
              }
            }) to CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH,
          )
        cases.forEach { (mutation, expected) ->
          withCalibrationScenario { scenario ->
            mutation(scenario.a1)
            mutation(scenario.a2)
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldContain expected
          }
        }
      }

      test("capture intervals and calibration chronology reject rollback overlap and excess duration") {
        withCalibrationScenario { scenario ->
          scenario.a2.mutateCapture {
            it.objectNode("outcome").apply {
              put("startedAtUtc", "2026-08-17T00:03:00Z")
              put("completedAtUtc", "2026-08-17T00:02:00Z")
            }
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.CAPTURE_INVALID
        }
        withCalibrationScenario { scenario ->
          scenario.a2.mutateCapture {
            it.objectNode("outcome").put("startedAtUtc", "2026-08-17T00:00:59Z")
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
        }
        withCalibrationScenario { scenario ->
          scenario.a2.mutateCapture {
            it.objectNode("outcome").apply {
              put("startedAtUtc", "2026-08-17T03:00:00Z")
              put("completedAtUtc", "2026-08-17T03:01:00Z")
            }
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
        }
        withCandidateScenario { scenario ->
          scenario.b.mutateCapture {
            it.objectNode("outcome").put("startedAtUtc", "2026-08-17T00:01:59Z")
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
        }
        withCandidateScenario { scenario ->
          scenario.b.mutateCapture {
            it.objectNode("outcome").apply {
              put("startedAtUtc", "2026-08-17T03:00:00Z")
              put("completedAtUtc", "2026-08-17T03:01:00Z")
            }
          }
          scenario.comparator()
            .compare(scenario.request())
            .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
            .reasons shouldContain CompatibilityFailure.IDENTITY_ORDER_INVALID
        }
      }

      test("incompatible reports use only distribution-derived qualification policy identity") {
        withCalibrationScenario { scenario ->
          val originalPolicy =
            scenario.a1.captureDocument().objectNode("qualification").sha("policyHash")
          listOf(scenario.a1, scenario.a2).forEach { capture ->
            capture.mutateCapture { document ->
              document.objectNode("qualification").put("policyHash", "e".repeat(64))
              document
                .objectNode("protocol")
                .put("qualificationPolicySha256", "e".repeat(64))
            }
          }

          val incompatible =
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
          incompatible.document.implementation.qualificationPolicySha256 shouldBe originalPolicy
          incompatible.reasons shouldContain CompatibilityFailure.QUALIFICATION_POLICY_MISMATCH
        }
      }

      test("qualification kind mapping covers campaign bounded diagnostic and hosted policies") {
        withCalibrationScenario { scenario ->
          val compatible =
            ComparisonInputVerifier.verify(scenario.request()) { true }
              .shouldBeInstanceOf<ComparisonInputVerifier.Result.Compatible>()
          val mappings = compatible.distribution.qualificationPolicies

          mappings["controlledMacCampaign"] shouldBe
            mappings["controlledMacBoundedDiagnostic"]
          (mappings["githubHosted"] == mappings["controlledMacCampaign"]) shouldBe false
        }
      }

      test("executing runner identity rejects a different valid distribution before capture input") {
        withCalibrationScenario { scenario ->
          val other = DistributionFixture.create()
          try {
            other.prepareComparisonProtocol()
            scenario
              .comparator()
              .compare(scenario.request().copy(runnerDistribution = other.root))
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldBe listOf(CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH)
          } finally {
            other.close()
          }
        }
      }

      test("executing runner identity rejects unsafe or inexact effective classpaths") {
        withCalibrationScenario { scenario ->
          val valid = scenario.distribution.executingIdentity()
          val runner = valid.effectiveClasspath.first()
          val dependency = valid.effectiveClasspath.last()
          val benchmarkJar =
            scenario.distribution.root.resolve(DistributionFixture.BENCHMARK_JAR)
          val symlink = scenario.distribution.root.parent.resolve("runner-link.jar")
          Files.createSymbolicLink(symlink, runner)
          val variants =
            listOf(
              valid.copy(effectiveClasspath = valid.effectiveClasspath + benchmarkJar),
              valid.copy(effectiveClasspath = listOf(runner)),
              valid.copy(effectiveClasspath = listOf(dependency, runner)),
              valid.copy(effectiveClasspath = emptyList()),
              valid.copy(effectiveClasspath = listOf(Path.of("runner/performance-runner.jar"))),
              valid.copy(effectiveClasspath = listOf(Path.of("/tmp/*.jar"))),
              valid.copy(effectiveClasspath = valid.effectiveClasspath + runner),
              valid.copy(effectiveClasspath = listOf(scenario.distribution.root)),
              valid.copy(effectiveClasspath = listOf(symlink, dependency)),
              valid.copy(comparatorCodeSource = symlink),
              valid.copy(fileCodeSource = false),
              valid.copy(systemApplicationClassLoader = false),
            )

          variants.forEach { identity ->
            identity
              .comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Incompatible>()
              .reasons shouldBe listOf(CompatibilityFailure.EXECUTING_IDENTITY_MISMATCH)
          }
        }
      }

      test("passing calibration with a regression policy remains valid candidate evidence") {
        val policy = regressionPolicy(0.05)
        withCandidateScenario(calibrationPolicy = policy) { scenario ->
          val completed =
            scenario.comparator()
              .compare(scenario.request())
              .shouldBeInstanceOf<ComparisonComputation.Completed>()

          completed.document.calibration?.passed shouldBe true
          completed.document.calibration?.a2CaptureId shouldBe "a2"
        }
      }
    },
  )

internal fun completedCandidateComparison(): ComparisonComputation.Completed {
  lateinit var completed: ComparisonComputation.Completed
  withCandidateScenario { scenario ->
    completed =
      scenario.comparator()
        .compare(scenario.request())
        .shouldBeInstanceOf<ComparisonComputation.Completed>()
  }
  return completed
}

private class CalibrationScenario(
  val distribution: DistributionFixture,
  val a1: CaptureBundleFixture,
  val a2: CaptureBundleFixture,
) : AutoCloseable {
  fun request(
    baseline: Path = a1.root,
    candidate: Path = a2.root,
    policy: RegressionPolicy? = null,
  ): ComparisonRequest =
    ComparisonRequest(
      runnerDistribution = distribution.root,
      kind = ComparisonKind.CALIBRATION,
      baseline = baseline,
      candidate = candidate,
      regressionPolicy = policy,
    )

  override fun close() {
    a2.close()
    a1.close()
    distribution.close()
  }
}

private class CandidateScenario(
  val distribution: DistributionFixture,
  val a1: CaptureBundleFixture,
  val a2: CaptureBundleFixture,
  val b: CaptureBundleFixture,
  val calibration: ComparisonBundleFixture,
) : AutoCloseable {
  fun request(policy: RegressionPolicy? = null): ComparisonRequest =
    ComparisonRequest(
      runnerDistribution = distribution.root,
      kind = ComparisonKind.CANDIDATE,
      baseline = a2.root,
      candidate = b.root,
      calibration = calibration.root,
      regressionPolicy = policy,
    )

  override fun close() {
    calibration.close()
    b.close()
    a2.close()
    a1.close()
    distribution.close()
  }
}

private fun CalibrationScenario.comparator(): CaptureComparator = distribution.comparator()

private fun CandidateScenario.comparator(): CaptureComparator = distribution.comparator()

private fun DistributionFixture.comparator(): CaptureComparator =
  executingIdentity().comparator()

private data class TestExecutingRunnerObservation(
  val comparatorCodeSource: Path,
  val effectiveClasspath: List<Path>,
  val fileCodeSource: Boolean,
  val systemApplicationClassLoader: Boolean,
)

private fun TestExecutingRunnerObservation.comparator(): CaptureComparator =
  CaptureComparator.forTest(
    comparatorCodeSource = comparatorCodeSource,
    effectiveClasspath = effectiveClasspath,
    fileCodeSource = fileCodeSource,
    systemApplicationClassLoader = systemApplicationClassLoader,
  )

private fun DistributionFixture.executingIdentity(): TestExecutingRunnerObservation =
  TestExecutingRunnerObservation(
    comparatorCodeSource = root.resolve(runnerClasspath.first()),
    effectiveClasspath = runnerClasspath.map { relativePath -> root.resolve(relativePath) },
    fileCodeSource = true,
    systemApplicationClassLoader = true,
  )

private inline fun withCalibrationScenario(
  a2Session: String = "session",
  multiCell: Boolean = false,
  block: (CalibrationScenario) -> Unit,
) {
  val distribution = DistributionFixture.create()
  distribution.prepareComparisonProtocol(multiCell)
  val a1 =
    CaptureBundleFixture.create(
      distribution,
      captureId = "a1",
      processRunId = "process-a1",
      sequence = 1,
    )
  val a2 =
    CaptureBundleFixture.create(
      distribution,
      captureId = "a2",
      processRunId = "process-a2",
      sessionId = a2Session,
      sequence = 2,
      startedAtUtc = "2026-08-17T00:01:00Z",
      completedAtUtc = "2026-08-17T00:02:00Z",
    )
  CalibrationScenario(distribution, a1, a2).use(block)
}

private inline fun withCandidateScenario(
  bTreatment: String = "9".repeat(40),
  distinctProduction: Boolean = true,
  bSequence: Int = 3,
  calibrationPolicy: RegressionPolicy? = null,
  block: (CandidateScenario) -> Unit,
) {
  val distribution = DistributionFixture.create()
  distribution.prepareComparisonProtocol()
  val a1 =
    CaptureBundleFixture.create(
      distribution,
      captureId = "a1",
      processRunId = "process-a1",
      sequence = 1,
    )
  val a2 =
    CaptureBundleFixture.create(
      distribution,
      captureId = "a2",
      processRunId = "process-a2",
      sequence = 2,
      startedAtUtc = "2026-08-17T00:01:00Z",
      completedAtUtc = "2026-08-17T00:02:00Z",
    )
  val calibrationResult =
    distribution.comparator()
      .compare(
        ComparisonRequest(
          runnerDistribution = distribution.root,
          kind = ComparisonKind.CALIBRATION,
          baseline = a1.root,
          candidate = a2.root,
          regressionPolicy = calibrationPolicy,
        ),
      )
      .shouldBeInstanceOf<ComparisonComputation.Completed>()
  val calibration = ComparisonBundleFixture.create(calibrationResult)
  val baselineProduction =
    a2.captureDocument().objectNode("artifacts").objectNode("production").sha("sha256")
  val b =
    CaptureBundleFixture.create(
      distribution,
      captureId = "b",
      processRunId = "process-b",
      sequence = bSequence,
      treatmentSha = bTreatment,
      productionSha = if (distinctProduction) Sha256.parse("b".repeat(64)) else baselineProduction,
      distributionSha = Sha256.parse("c".repeat(64)),
      freezerSha = "7".repeat(40),
      captureRunnerSha = "8".repeat(40),
      startedAtUtc = "2026-08-17T00:02:00Z",
      completedAtUtc = "2026-08-17T00:03:00Z",
      forkSamples = List(10) { List(10) { 9.0 } },
    )
  CandidateScenario(distribution, a1, a2, b, calibration).use(block)
}

private fun mutateFirstRow(capture: CaptureBundleFixture, mutation: (ObjectNode) -> Unit) {
  val rows = capture.jmhResult()
  mutation(rows.get(0).asObject())
  capture.writeRaw("jmh-result.json", CanonicalJson.encode(rows))
  capture.reseal()
}

private fun mutateFirstRowAndBind(
  capture: CaptureBundleFixture,
  mutation: (ObjectNode) -> Unit,
) {
  val rows = capture.jmhResult()
  val row = rows.get(0).asObject()
  mutation(row)
  capture.writeRaw("jmh-result.json", CanonicalJson.encode(rows))
  capture.mutateCapture { document ->
    document
      .arrayNode("cells")
      .get(0)
      .asObject()
      .objectNode("jmhResultRow")
      .put("sha256", Sha256.digest(CanonicalJson.encode(row)).hex)
  }
}

private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()

private fun ObjectNode.arrayNode(name: String): ArrayNode = get(name).asArray()

private fun ObjectNode.sha(name: String): Sha256 = Sha256.parse(get(name).asString())

private fun regressionPolicy(maximumRegressionBudget: Double): RegressionPolicy =
  RegressionPolicy.parse(
    CanonicalJson.encode(
      tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().apply {
        put("schemaVersion", "regression-policy-v1")
        put("maximumRegressionBudget", maximumRegressionBudget)
      }
    )
  )

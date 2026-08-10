/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.salesforce.revoman.benchmark.driver.cli.BenchmarkDriverApplication
import com.salesforce.revoman.benchmark.driver.cli.CliExitCode
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ArtifactSnapshot
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.stats.RatioInterval
import com.salesforce.revoman.benchmark.driver.stats.Statistic
import com.squareup.moshi.JsonDataException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ReleaseGateEvaluatorTest {
    @TempDir lateinit var temporaryDirectory: Path

    private val evaluator = ReleaseGateEvaluator(resamples = 100)

    @Test
    fun `cold median exact upper ratio 1_05 passes`() {
        val report = evaluateLifecycle(RunMode.COLD, mapOf(MetricId.LATENCY to 1.05))

        assertThat(report.decision(GateId.COLD_MEDIAN).interval!!.upper95).isEqualTo(1.05)
        assertThat(report.decision(GateId.COLD_MEDIAN).decision).isEqualTo(GateDecision.PASS)
    }

    @Test
    fun `cold p95 one floating point step above 1_10 fails without epsilon`() {
        val report =
            evaluateLifecycle(RunMode.COLD, mapOf(MetricId.LATENCY to Math.nextUp(1.10)))

        assertThat(report.decision(GateId.COLD_P95).decision).isEqualTo(GateDecision.FAIL)
    }

    @Test
    fun `warm allocation exact upper ratio 1_03 passes`() {
        val report = evaluateLifecycle(RunMode.WARM, mapOf(MetricId.ALLOCATED_BYTES to 1.03))

        assertThat(report.decision(GateId.WARM_ALLOCATION).decision).isEqualTo(GateDecision.PASS)
    }

    @Test
    fun `every ratio release boundary is inclusive and the next valid value fails`() {
        val cases =
            listOf(
                BoundaryCase(RunMode.COLD, GateId.COLD_P95, MetricId.LATENCY, 1.10, Math.nextUp(1.10)),
                BoundaryCase(RunMode.COLD, GateId.COLD_ALLOCATION, MetricId.ALLOCATED_BYTES, 1.05, 1.06),
                BoundaryCase(RunMode.COLD, GateId.COLD_PEAK_RSS, MetricId.PEAK_RSS, 1.05, 1.06),
                BoundaryCase(RunMode.WARM, GateId.WARM_MEDIAN, MetricId.LATENCY, 1.03, Math.nextUp(1.03)),
                BoundaryCase(RunMode.WARM, GateId.WARM_P95, MetricId.LATENCY, 1.05, Math.nextUp(1.05)),
                BoundaryCase(RunMode.WARM, GateId.WARM_ALLOCATION, MetricId.ALLOCATED_BYTES, 1.03, 1.04),
            )

        cases.forEach { boundary ->
            val exact = evaluateLifecycle(boundary.mode, mapOf(boundary.metric to boundary.exact))
            val breach = evaluateLifecycle(boundary.mode, mapOf(boundary.metric to boundary.breach))
            assertThat(exact.decision(boundary.gate).decision).isEqualTo(GateDecision.PASS)
            assertThat(breach.decision(boundary.gate).decision).isEqualTo(GateDecision.FAIL)
        }
    }

    @Test
    fun `cold and warm targeted improvements use exact upper endpoints`() {
        val cold =
            evaluateLifecycle(
                mode = RunMode.COLD,
                ratios = mapOf(MetricId.LATENCY to 0.85),
                targetedClaims = listOf(TargetedClaim(RunMode.COLD, MetricId.LATENCY, Statistic.MEDIAN)),
            )
        val warm =
            evaluateLifecycle(
                mode = RunMode.WARM,
                ratios = mapOf(MetricId.LATENCY to 0.80),
                targetedClaims = listOf(TargetedClaim(RunMode.WARM, MetricId.LATENCY, Statistic.MEDIAN)),
            )
        val coldBreach =
            evaluateLifecycle(
                mode = RunMode.COLD,
                ratios = mapOf(MetricId.LATENCY to Math.nextUp(0.85)),
                targetedClaims = listOf(TargetedClaim(RunMode.COLD, MetricId.LATENCY, Statistic.MEDIAN)),
            )
        val warmBreach =
            evaluateLifecycle(
                mode = RunMode.WARM,
                ratios = mapOf(MetricId.LATENCY to Math.nextUp(0.80)),
                targetedClaims = listOf(TargetedClaim(RunMode.WARM, MetricId.LATENCY, Statistic.MEDIAN)),
            )

        assertThat(cold.targeted(MetricId.LATENCY, Statistic.MEDIAN).decision)
            .isEqualTo(GateDecision.PASS)
        assertThat(warm.targeted(MetricId.LATENCY, Statistic.MEDIAN).decision)
            .isEqualTo(GateDecision.PASS)
        assertThat(coldBreach.targeted(MetricId.LATENCY, Statistic.MEDIAN).decision)
            .isEqualTo(GateDecision.FAIL)
        assertThat(warmBreach.targeted(MetricId.LATENCY, Statistic.MEDIAN).decision)
            .isEqualTo(GateDecision.FAIL)
    }

    @Test
    fun `two targeted metrics have distinct composite decision keys`() {
        val report =
            evaluateLifecycle(
                mode = RunMode.COLD,
                ratios = mapOf(MetricId.LATENCY to 0.80, MetricId.ALLOCATED_BYTES to 0.80),
                targetedClaims =
                    listOf(
                        TargetedClaim(RunMode.COLD, MetricId.LATENCY, Statistic.MEDIAN),
                        TargetedClaim(RunMode.COLD, MetricId.ALLOCATED_BYTES, Statistic.MEAN),
                    ),
            )

        assertThat(report.metrics.filter { it.claimKind == ClaimKind.TARGETED_IMPROVEMENT })
            .hasSize(2)
        assertThat(report.metrics.map(MetricDecision::decisionKey).distinct())
            .hasSize(report.metrics.size)
    }

    @Test
    fun `retained slope exact 1024 passes and one floating point step above fails`() {
        val manifest = ComparisonFixtures.manifest(RunMode.RETAINED, listOf(GateId.RETAINED_SLOPE))
        val exact = evaluator.evaluate(ComparisonFixtures.retainedResult(1_024.0), listOf(manifest))
        val breach =
            evaluator.evaluate(
                ComparisonFixtures.retainedResult(1_025.0),
                listOf(manifest),
            )

        assertThat(exact.decision(GateId.RETAINED_SLOPE).decision).isEqualTo(GateDecision.PASS)
        assertThat(breach.decision(GateId.RETAINED_SLOPE).decision).isEqualTo(GateDecision.FAIL)
    }

    @Test
    fun `retained candidate requires real tokens at every checkpoint and all tokens cleared`() {
        val manifest = ComparisonFixtures.manifest(RunMode.RETAINED, listOf(GateId.RETAINED_SLOPE))
        val fakeOnly =
            evaluator.evaluate(
                ComparisonFixtures.retainedResult(
                    candidateSlope = 0.0,
                    candidateWeakTypes = listOf("FakeExecutionToken"),
                ),
                listOf(manifest),
            )
        val uncleared =
            evaluator.evaluate(
                ComparisonFixtures.retainedResult(candidateSlope = 0.0, candidateCleared = false),
                listOf(manifest),
            )

        assertThat(fakeOnly.decision(GateId.RETAINED_SLOPE).decision)
            .isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(fakeOnly.overall).isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(uncleared.decision(GateId.RETAINED_SLOPE).decision)
            .isEqualTo(GateDecision.FAIL)
    }

    @Test
    fun `retained gate requires five fresh replicates for each role`() {
        val result = ComparisonFixtures.retainedResult(candidateSlope = 0.0)
        val reusedProcesses =
            ComparisonFixtures.withSeries(result, MetricId.RETAINED_BYTES) { series ->
                series.copy(
                    blocks =
                        requireNotNull(series.blocks).map { block ->
                            block.copy(
                                observations =
                                    block.observations.map { observation ->
                                        observation.copy(
                                            processId =
                                                1_000L +
                                                    observation.iteration +
                                                    if (observation.targetId == "candidate") 10 else 0
                                        )
                                    }
                            )
                        }
                )
            }
        val manifest = ComparisonFixtures.manifest(RunMode.RETAINED, listOf(GateId.RETAINED_SLOPE))

        val report = evaluator.evaluate(reusedProcesses, listOf(manifest))

        assertThat(report.decision(GateId.RETAINED_SLOPE).decision)
            .isEqualTo(GateDecision.INCONCLUSIVE)
    }

    @Test
    fun `per step allocation exact spread 1_10 passes and next value fails`() {
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.RETAINED,
                listOf(GateId.PER_STEP_ALLOCATION_SPREAD),
            )
        val exact =
            evaluator.evaluate(
                ComparisonFixtures.perStepResult(listOf(100.0, 105.0, 110.0)),
                listOf(manifest),
            )
        val breach =
            evaluator.evaluate(
                ComparisonFixtures.perStepResult(listOf(100.0, 105.0, 110.0003125)),
                listOf(manifest),
            )

        assertThat(exact.decision(GateId.PER_STEP_ALLOCATION_SPREAD).observedValue)
            .isEqualTo(1.10)
        assertThat(exact.decision(GateId.PER_STEP_ALLOCATION_SPREAD).decision)
            .isEqualTo(GateDecision.PASS)
        assertThat(breach.decision(GateId.PER_STEP_ALLOCATION_SPREAD).decision)
            .isEqualTo(GateDecision.FAIL)
    }

    @Test
    fun `per step observations require positive count evidence and exact 800 1600 3200 shape`() {
        val valid = ComparisonFixtures.perStepResult(listOf(100.0, 105.0, 110.0))
        val missingCount =
            ComparisonFixtures.withSeries(valid, MetricId.BYTES_PER_STEP) { series ->
                series.copy(
                    blocks =
                        requireNotNull(series.blocks).mapIndexed { blockIndex, block ->
                            if (blockIndex == 0) {
                                block.copy(
                                    observations =
                                        block.observations.mapIndexed { index, observation ->
                                            if (index == 0) observation.copy(executionCount = null)
                                            else observation
                                        }
                                )
                            } else block
                        }
                )
            }
        val wrongCounts = ComparisonFixtures.perStepResult(listOf(100.0, 105.0, 110.0), listOf(800, 1_600, 3_201))

        assertThrows<IllegalArgumentException> { missingCount.validate() }
        val report =
            evaluator.evaluate(
                wrongCounts,
                listOf(
                    ComparisonFixtures.manifest(
                        RunMode.RETAINED,
                        listOf(GateId.PER_STEP_ALLOCATION_SPREAD),
                    )
                ),
            )
        assertThat(report.decision(GateId.PER_STEP_ALLOCATION_SPREAD).decision)
            .isEqualTo(GateDecision.INCONCLUSIVE)

        val zeroCount =
            ComparisonFixtures.withSeries(valid, MetricId.BYTES_PER_STEP) { series ->
                series.copy(
                    blocks =
                        requireNotNull(series.blocks).mapIndexed { blockIndex, block ->
                            if (blockIndex == 0) {
                                block.copy(
                                    observations =
                                        block.observations.mapIndexed { index, observation ->
                                            if (index == 0) observation.copy(executionCount = 0)
                                            else observation
                                        }
                                )
                            } else block
                        }
                )
            }
        assertThrows<IllegalArgumentException> { zeroCount.validate() }
    }

    @Test
    fun `count evidence is forbidden for metrics other than bytes per step`() {
        val result = ComparisonFixtures.lifecycleResult(RunMode.WARM)
        val invalid =
            ComparisonFixtures.withSeries(result, MetricId.LATENCY) { series ->
                series.copy(
                    blocks =
                        requireNotNull(series.blocks).mapIndexed { blockIndex, block ->
                            if (blockIndex == 0) {
                                block.copy(
                                    observations =
                                        block.observations.mapIndexed { index, observation ->
                                            if (index == 0) observation.copy(executionCount = 800)
                                            else observation
                                        }
                                )
                            } else block
                        }
                )
            }

        assertThrows<IllegalArgumentException> { invalid.validate() }
    }

    @Test
    fun `target identity rejects classpath hash mismatch without reopening paths`() {
        val target = ComparisonFixtures.lifecycleResult(RunMode.COLD).targets.last()
        val changed =
            target.copy(
                classpath = listOf(ArtifactSnapshot("candidate.jar", 201, "2".repeat(64)))
            )

        val failure = assertThrows<IllegalArgumentException> { changed.validate("target") }

        assertThat(failure).hasMessageThat().contains("classpathSha256")
        assertThat(changed.classpath.single().toString()).doesNotContain("/")
    }

    @Test
    fun `lifecycle manifest does not invent retained or per step gates`() {
        val report = evaluateLifecycle(RunMode.WARM, emptyMap())

        assertThat(report.metrics.mapNotNull(MetricDecision::gate))
            .containsExactlyElementsIn(ComparisonFixtures.requiredLifecycleGates(RunMode.WARM))
            .inOrder()
    }

    @Test
    fun `smoke and CI evidence remain inconclusive even when thresholds pass`() {
        val smoke =
            evaluateLifecycle(RunMode.COLD, emptyMap(), intent = RunIntent.SMOKE)
        val ci =
            evaluateLifecycle(RunMode.COLD, emptyMap(), intent = RunIntent.CI_SELF_TEST)

        assertThat(smoke.overall).isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(ci.overall).isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(smoke.metrics.map(MetricDecision::decision).distinct())
            .containsExactly(GateDecision.INCONCLUSIVE)
    }

    @Test
    fun `insufficient cold samples and warm forks are inconclusive`() {
        val cold =
            evaluateLifecycle(RunMode.COLD, emptyMap(), acceptedBlocks = 49)
        val warm =
            evaluateLifecycle(RunMode.WARM, emptyMap(), acceptedBlocks = 4)

        assertThat(cold.overall).isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(warm.overall).isEqualTo(GateDecision.INCONCLUSIVE)
    }

    @Test
    fun `accepted evidence shorter than the requested campaign is unavailable before statistics`() {
        val complete = ComparisonFixtures.lifecycleResult(RunMode.WARM)
        val short =
            complete
                .copy(
                    configuration =
                        complete.configuration.copy(
                            requestedAcceptedBlocks =
                                complete.configuration.requestedAcceptedBlocks + 1
                        )
                )
                .withRejectedBlock()
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.WARM,
                ComparisonFixtures.requiredLifecycleGates(RunMode.WARM),
            )

        val report = evaluator.evaluate(short, listOf(manifest))

        assertThat(report.metrics.map(MetricDecision::decision).distinct())
            .containsExactly(GateDecision.INCONCLUSIVE)
        assertThat(report.metrics.map(MetricDecision::interval).distinct()).containsExactly(null)
        assertThat(report.rejectedBlocks).hasSize(short.workloads.single().metricSeries.size)
    }

    @Test
    fun `biased accepted first positions invalidate normative and targeted ratio evidence`() {
        val biased = ComparisonFixtures.lifecycleResult(RunMode.WARM).withBaselineAlwaysFirst()
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.WARM,
                ComparisonFixtures.requiredLifecycleGates(RunMode.WARM),
            )

        val report =
            evaluator.evaluate(
                biased,
                listOf(manifest),
                listOf(TargetedClaim(RunMode.WARM, MetricId.LATENCY, Statistic.MEDIAN)),
            )

        assertThat(report.metrics.map(MetricDecision::decision).distinct())
            .containsExactly(GateDecision.INCONCLUSIVE)
        assertThat(report.metrics.map(MetricDecision::interval).distinct()).containsExactly(null)
    }

    @Test
    fun `biased accepted first positions invalidate retained and per step structural evidence`() {
        val retainedManifest =
            ComparisonFixtures.manifest(RunMode.RETAINED, listOf(GateId.RETAINED_SLOPE))
        val perStepManifest =
            ComparisonFixtures.manifest(
                RunMode.RETAINED,
                listOf(GateId.PER_STEP_ALLOCATION_SPREAD),
            )

        val retained =
            evaluator.evaluate(
                ComparisonFixtures.retainedResult(candidateSlope = 0.0).withBaselineAlwaysFirst(),
                listOf(retainedManifest),
            )
        val perStep =
            evaluator.evaluate(
                ComparisonFixtures.perStepResult(listOf(100.0, 105.0, 110.0))
                    .withBaselineAlwaysFirst(),
                listOf(perStepManifest),
            )

        assertThat(retained.decision(GateId.RETAINED_SLOPE).decision)
            .isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(retained.decision(GateId.RETAINED_SLOPE).slopeInterval).isNull()
        assertThat(perStep.decision(GateId.PER_STEP_ALLOCATION_SPREAD).decision)
            .isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(perStep.decision(GateId.PER_STEP_ALLOCATION_SPREAD).observedValue).isNull()
    }

    @Test
    fun `balanced odd accepted order remains complete for ratio and structural gates`() {
        val warm =
            evaluateLifecycle(
                mode = RunMode.WARM,
                ratios = emptyMap(),
                targetedClaims =
                    listOf(TargetedClaim(RunMode.WARM, MetricId.LATENCY, Statistic.MEDIAN)),
            )
        val retained =
            evaluator.evaluate(
                ComparisonFixtures.retainedResult(candidateSlope = 0.0),
                listOf(
                    ComparisonFixtures.manifest(
                        RunMode.RETAINED,
                        listOf(GateId.RETAINED_SLOPE),
                    )
                ),
            )
        val perStep =
            evaluator.evaluate(
                ComparisonFixtures.perStepResult(listOf(100.0, 105.0, 110.0)),
                listOf(
                    ComparisonFixtures.manifest(
                        RunMode.RETAINED,
                        listOf(GateId.PER_STEP_ALLOCATION_SPREAD),
                    )
                ),
            )

        assertThat(warm.targeted(MetricId.LATENCY, Statistic.MEDIAN).decision)
            .isEqualTo(GateDecision.FAIL)
        assertThat(warm.targeted(MetricId.LATENCY, Statistic.MEDIAN).interval).isNotNull()
        assertThat(retained.decision(GateId.RETAINED_SLOPE).decision)
            .isEqualTo(GateDecision.PASS)
        assertThat(perStep.decision(GateId.PER_STEP_ALLOCATION_SPREAD).decision)
            .isEqualTo(GateDecision.PASS)
    }

    @Test
    fun `missing declared gate is inconclusive`() {
        val result =
            ComparisonFixtures.lifecycleResult(RunMode.COLD).copy(
                workloads =
                    ComparisonFixtures.lifecycleResult(RunMode.COLD).workloads.map { workload ->
                        workload.copy(
                            metricSeries =
                                workload.metricSeries.filterNot {
                                    it.metric == MetricId.ALLOCATED_BYTES
                                }
                        )
                    }
            )
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.COLD,
                ComparisonFixtures.requiredLifecycleGates(RunMode.COLD),
            )

        val report = evaluator.evaluate(result, listOf(manifest))

        assertThat(report.decision(GateId.COLD_ALLOCATION).decision)
            .isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(report.overall).isEqualTo(GateDecision.INCONCLUSIVE)
    }

    @Test
    fun `rejected blocks remain auditable and never enter ratio statistics`() {
        val base = ComparisonFixtures.lifecycleResult(RunMode.COLD)
        val withRejected =
            ComparisonFixtures.withSeries(base, MetricId.LATENCY) { series ->
                val rejected =
                    requireNotNull(series.blocks).first().copy(
                        blockId = 999,
                        accepted = false,
                        rejectionReasons = listOf("thermal", "load"),
                        observations = emptyList(),
                    )
                series.copy(blocks = requireNotNull(series.blocks) + rejected)
            }
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.COLD,
                ComparisonFixtures.requiredLifecycleGates(RunMode.COLD),
            )

        val report = evaluator.evaluate(withRejected, listOf(manifest))

        assertThat(report.decision(GateId.COLD_MEDIAN).interval!!.pointEstimate).isEqualTo(1.0)
        assertThat(report.rejectedBlocks.single().reasons)
            .containsExactly("thermal", "load")
            .inOrder()
    }

    @Test
    fun `replacement exhaustion with zero or short accepted evidence is inconclusive`() {
        listOf(0, 1).forEach { acceptedCount ->
            val complete = ComparisonFixtures.lifecycleResult(RunMode.COLD)
            val exhausted =
                complete.copy(
                    workloads =
                        complete.workloads.map { workload ->
                            workload.copy(
                                metricSeries =
                                    workload.metricSeries.map { series ->
                                        series.copy(
                                            blocks =
                                                requireNotNull(series.blocks).mapIndexed { index, block ->
                                                    if (index < acceptedCount) block
                                                    else
                                                        block.copy(
                                                            accepted = false,
                                                            rejectionReasons =
                                                                listOf("replacement-budget-exhausted"),
                                                            observations = emptyList(),
                                                        )
                                                }
                                        )
                                    }
                            )
                        }
                )
            val manifest =
                ComparisonFixtures.manifest(
                    RunMode.COLD,
                    ComparisonFixtures.requiredLifecycleGates(RunMode.COLD),
                )

            assertThat(exhausted.validate()).isSameInstanceAs(exhausted)
            val report = evaluator.evaluate(exhausted, listOf(manifest))
            assertThat(report.overall).isEqualTo(GateDecision.INCONCLUSIVE)
            assertThat(report.rejectedBlocks).isNotEmpty()
        }
    }

    @Test
    fun `controlled evidence with no requested decisions is inconclusive`() {
        val gateFreeCold = ComparisonFixtures.lifecycleResult(RunMode.COLD)
        val retainedArchive = ComparisonFixtures.retainedResult(candidateSlope = 0.0)

        listOf(gateFreeCold to RunMode.COLD, retainedArchive to RunMode.RETAINED)
            .forEach { (result, mode) ->
                val report =
                    evaluator.evaluate(
                        result,
                        listOf(ComparisonFixtures.manifest(mode, requiredGates = emptyList())),
                    )

                assertThat(report.metrics).isEmpty()
                assertThat(report.overall).isEqualTo(GateDecision.INCONCLUSIVE)
            }
    }

    @Test
    fun `retained evidence requires two acknowledged GC cycles at every trust boundary`() {
        val valid = ComparisonFixtures.retainedResult(candidateSlope = 0.0)
        val invalid =
            valid.copy(
                workloads =
                    valid.workloads.map { workload ->
                        workload.copy(
                            metricSeries =
                                workload.metricSeries.map { series ->
                                    series.copy(
                                        blocks =
                                            requireNotNull(series.blocks).map { block ->
                                                block.copy(
                                                    observations =
                                                        block.observations.map { observation ->
                                                            observation.copy(
                                                                retainedEvidence =
                                                                    observation.retainedEvidence?.copy(
                                                                        completedGcCycles = 0
                                                                    )
                                                            )
                                                        }
                                                )
                                            }
                                    )
                                }
                        )
                    }
            )
        val invalidJson = temporaryDirectory.resolve("retained-zero-gc.json")
        val validJson = temporaryDirectory.resolve("retained-two-gc.json")
        BenchmarkJson.write(validJson, valid)
        Files.writeString(
            invalidJson,
            BenchmarkJson.encode(valid).toString(UTF_8).replace(
                "\"completedGcCycles\":2",
                "\"completedGcCycles\":0",
            ),
        )
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.RETAINED,
                requiredGates = emptyList(),
            )

        assertThat(valid.validate()).isSameInstanceAs(valid)
        BenchmarkJson.validateSchema(validJson, PAIRED_SCHEMA)
        assertThrows<IllegalArgumentException>("model") { invalid.validate() }
        assertThrows<IllegalArgumentException>("schema") {
            BenchmarkJson.validateSchema(invalidJson, PAIRED_SCHEMA)
        }
        assertThrows<IllegalArgumentException>("decode") {
            BenchmarkJson.read<BenchmarkResultV1>(invalidJson)
        }
        assertThrows<IllegalArgumentException>("evaluation") {
            evaluator.evaluate(invalid, listOf(manifest))
        }
        val validVerify = verify(validJson)
        assertWithMessage(validVerify.error).that(validVerify.exit).isEqualTo(CliExitCode.SUCCESS)
        assertThat(verify(invalidJson).exit).isEqualTo(CliExitCode.INVALID_INPUT)
    }

    @Test
    fun `overall precedence is incompatible then inconclusive then fail then pass`() {
        val regression =
            ComparisonFixtures.lifecycleResult(
                RunMode.COLD,
                ratios = mapOf(MetricId.LATENCY to 2.0),
            )
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.COLD,
                ComparisonFixtures.requiredLifecycleGates(RunMode.COLD),
            )
        val incompatible = regression.copy(environment = regression.environment.copy(policySha256 = null))
        val smokeRegression = regression.copy(intent = RunIntent.SMOKE)
        val dirtySmoke =
            smokeRegression.copy(
                harness = smokeRegression.harness.copy(dirty = true),
                targets =
                    smokeRegression.targets.mapIndexed { index, target ->
                        if (index == 1) target.copy(dirty = true) else target
                    },
            )

        assertThat(evaluator.evaluate(incompatible, listOf(manifest)).overall)
            .isEqualTo(GateDecision.INCOMPATIBLE)
        assertThat(evaluator.evaluate(smokeRegression, listOf(manifest)).overall)
            .isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(evaluator.evaluate(dirtySmoke, listOf(manifest)).overall)
            .isEqualTo(GateDecision.INCONCLUSIVE)
        assertThat(evaluator.evaluate(regression, listOf(manifest)).overall)
            .isEqualTo(GateDecision.FAIL)
        assertThat(evaluateLifecycle(RunMode.COLD, emptyMap()).overall)
            .isEqualTo(GateDecision.PASS)
    }

    @Test
    fun `comparison decisions reject duplicate keys and mixed evidence families`() {
        val report = evaluateLifecycle(RunMode.COLD, emptyMap())
        val first = report.metrics.first()
        val duplicate = report.copy(metrics = report.metrics + first)
        val mixed =
            report.copy(
                metrics =
                    listOf(
                        first.copy(
                            observedValue = 1.0,
                            interval = RatioInterval(1.0, 1.0, 1.0),
                        )
                    ) + report.metrics.drop(1)
            )

        assertThrows<IllegalArgumentException> { duplicate.validate() }
        assertThrows<IllegalArgumentException> { mixed.validate() }
        assertThrows<IllegalArgumentException> {
            report.copy(metrics = listOf(first.copy(interval = null))).validate()
        }
        val wrongRetainedFamily =
            MetricDecision(
                gate = GateId.RETAINED_SLOPE,
                claimKind = ClaimKind.STRUCTURAL,
                mode = RunMode.RETAINED,
                metric = MetricId.RETAINED_BYTES,
                statistic = null,
                interval = null,
                slopeInterval = null,
                observedValue = 1.0,
                limit = 1_024.0,
                decision = GateDecision.PASS,
                reason = "wrong family",
            )
        assertThrows<IllegalArgumentException> {
            report.copy(metrics = listOf(wrongRetainedFamily)).validate()
        }
    }

    @Test
    fun `rejected evidence is canonically sorted while reason order is preserved`() {
        val report =
            ComparisonReport(
                    campaignId = "campaign",
                    compatibilityErrors = emptyList(),
                    metrics = emptyList(),
                    rejectedBlocks =
                        listOf(
                            RejectedBlockEvidence("z", MetricId.PEAK_RSS, 2, listOf("thermal", "load")),
                            RejectedBlockEvidence("a", MetricId.LATENCY, 3, listOf("power", "swap")),
                        ),
                    overall = GateDecision.INCONCLUSIVE,
                )
                .canonicalized()

        assertThat(report.rejectedBlocks.map(RejectedBlockEvidence::workloadId))
            .containsExactly("a", "z")
            .inOrder()
        assertThat(report.rejectedBlocks.last().reasons)
            .containsExactly("thermal", "load")
            .inOrder()
    }

    @Test
    fun `all comparison goldens satisfy strict schema and canonical round trip`() {
        GOLDENS.forEach { name ->
            val source = golden(name)
            BenchmarkJson.validateSchema(source, COMPARISON_SCHEMA)
            val report = BenchmarkJson.read<ComparisonReport>(source)

            assertThat(BenchmarkJson.encode(report)).isEqualTo(Files.readAllBytes(source))
            assertThat(report.toMarkdown()).contains("Overall: ${report.overall}")
        }
    }

    @Test
    fun `comparison schema and parser reject unknown fields`() {
        val source = Files.readString(golden("pass.json"))
        val invalid = temporaryDirectory.resolve("unknown.json")
        Files.writeString(invalid, source.replaceFirst("{", "{\"unexpected\":true,"))

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(invalid, COMPARISON_SCHEMA)
        }
        assertThrows<JsonDataException> { BenchmarkJson.read<ComparisonReport>(invalid) }
    }

    private fun evaluateLifecycle(
        mode: RunMode,
        ratios: Map<MetricId, Double>,
        targetedClaims: List<TargetedClaim> = emptyList(),
        intent: RunIntent = RunIntent.CONTROLLED,
        acceptedBlocks: Int = when (mode) {
            RunMode.COLD -> 50
            RunMode.WARM, RunMode.RETAINED -> 5
        },
    ): ComparisonReport {
        val result = ComparisonFixtures.lifecycleResult(mode, intent, ratios, acceptedBlocks)
        val manifest =
            ComparisonFixtures.manifest(
                mode,
                ComparisonFixtures.requiredLifecycleGates(mode),
            )
        return evaluator.evaluate(result, listOf(manifest), targetedClaims)
    }

    private fun ComparisonReport.decision(gate: GateId): MetricDecision =
        metrics.single { it.gate == gate }

    private fun ComparisonReport.targeted(metric: MetricId, statistic: Statistic): MetricDecision =
        metrics.single {
            it.claimKind == ClaimKind.TARGETED_IMPROVEMENT &&
                it.metric == metric &&
                it.statistic == statistic
        }

    private fun BenchmarkResultV1.withBaselineAlwaysFirst(): BenchmarkResultV1 =
        copy(
            workloads =
                workloads.map { workload ->
                    workload.copy(
                        metricSeries =
                            workload.metricSeries.map { series ->
                                series.copy(
                                    blocks =
                                        requireNotNull(series.blocks).map { block ->
                                            block.copy(
                                                targetOrder =
                                                    listOf(
                                                        ComparisonFixtures.BASELINE_ID,
                                                        ComparisonFixtures.CANDIDATE_ID,
                                                    )
                                            )
                                        }
                                )
                            }
                    )
                }
        )

    private fun BenchmarkResultV1.withRejectedBlock(): BenchmarkResultV1 =
        copy(
            workloads =
                workloads.map { workload ->
                    workload.copy(
                        metricSeries =
                            workload.metricSeries.map { series ->
                                val rejected =
                                    requireNotNull(series.blocks).first().copy(
                                        blockId = 999,
                                        accepted = false,
                                        rejectionReasons = listOf("replacement-budget-exhausted"),
                                        observations = emptyList(),
                                    )
                                series.copy(blocks = requireNotNull(series.blocks) + rejected)
                            }
                    )
                }
        )

    private fun golden(name: String): Path =
        Path.of(
            requireNotNull(javaClass.getResource("/compare/$name")) {
                    "Missing comparison golden: $name"
                }
                .toURI()
        )

    private fun verify(path: Path): VerifyResult {
        val error = ByteArrayOutputStream()
        val exit = BenchmarkDriverApplication.execute(
            arguments = arrayOf("verify", "--input", path.toRealPath().toString()),
            output = PrintStream(ByteArrayOutputStream()),
            error = PrintStream(error),
            installationRoot = temporaryDirectory.toRealPath(),
        )
        return VerifyResult(exit, error.toString())
    }

    private companion object {
        const val COMPARISON_SCHEMA: String = "/schema/revoman-benchmark-comparison-v1.schema.json"
        const val PAIRED_SCHEMA: String = "/schema/revoman-benchmark-v1.schema.json"
        val GOLDENS =
            listOf(
                "pass.json",
                "exact-boundary.json",
                "regression.json",
                "improvement.json",
                "retained-failure.json",
                "incompatible.json",
                "smoke.json",
                "inconclusive.json",
            )
    }

    private data class BoundaryCase(
        val mode: RunMode,
        val gate: GateId,
        val metric: MetricId,
        val exact: Double,
        val breach: Double,
    )

    private data class VerifyResult(val exit: Int, val error: String)
}

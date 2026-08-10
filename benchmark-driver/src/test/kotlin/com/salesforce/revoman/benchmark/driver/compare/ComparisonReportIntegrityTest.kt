/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.stats.RatioInterval
import com.salesforce.revoman.benchmark.driver.stats.SlopeInterval
import com.salesforce.revoman.benchmark.driver.stats.Statistic
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ComparisonReportIntegrityTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `forged cold median cannot inflate its limit through model schema encode or decode`() {
        val forgedDecision =
            coldMedianDecision().copy(
                interval = RatioInterval(2.0, 2.0, 2.0),
                limit = 100.0,
            )
        val forgedReport = report(listOf(forgedDecision), GateDecision.PASS)
        val forgedJson =
            """{"campaignId":"forged-limit","compatibilityErrors":[],"metrics":[{"claimKind":"NON_REGRESSION","decision":"PASS","gate":"COLD_MEDIAN","interval":{"lower95":2.0,"pointEstimate":2.0,"upper95":2.0},"limit":100.0,"metric":"LATENCY","mode":"COLD","reason":"forged","statistic":"MEDIAN"}],"overall":"PASS","rejectedBlocks":[],"schema":"revoman-benchmark-comparison/v1"}"""
        val source =
            temporaryDirectory.resolve("forged-cold-median.json").also {
                Files.writeString(it, forgedJson)
            }

        assertThrows<IllegalArgumentException> { forgedDecision.validate("metric") }
        assertThrows<IllegalArgumentException> { forgedReport.validate() }
        assertThrows<IllegalArgumentException> { BenchmarkJson.encode(forgedReport) }
        assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(source, COMPARISON_SCHEMA)
        }
        assertThrows<IllegalArgumentException> { BenchmarkJson.read<ComparisonReport>(source) }
    }

    @Test
    fun `every normative gate binds its exact canonical limit in model schema and decoder`() {
        gateCases().forEach { gateCase ->
            val valid = gateCase.decision()
            val wrongLimit = Math.nextUp(gateCase.limit)
            val forged = valid.copy(limit = wrongLimit)
            val validJson = BenchmarkJson.encode(report(listOf(valid), GateDecision.PASS)).toString(UTF_8)
            val forgedJson =
                validJson.replace(
                    "\"limit\":${gateCase.limit}",
                    "\"limit\":$wrongLimit",
                )
            val source =
                temporaryDirectory.resolve("forged-${gateCase.gate}.json").also {
                    Files.writeString(it, forgedJson)
                }

            valid.validate("metric")
            assertThrows<IllegalArgumentException> { forged.validate("metric") }
            assertThrows<IllegalArgumentException> {
                BenchmarkJson.validateSchema(source, COMPARISON_SCHEMA)
            }
            assertThrows<IllegalArgumentException> { BenchmarkJson.read<ComparisonReport>(source) }

            val inconclusive = valid.copy(
                interval = null,
                slopeInterval = null,
                observedValue = null,
                decision = GateDecision.INCONCLUSIVE,
            )
            inconclusive.validate("metric")
        }
    }

    @Test
    fun `targeted cold and warm claims bind exact canonical improvement limits`() {
        val cases =
            listOf(
                targetedDecision(RunMode.COLD, 0.85),
                targetedDecision(RunMode.WARM, 0.80),
            )

        cases.forEach { valid ->
            val forged = valid.copy(limit = 100.0)
            val validJson = BenchmarkJson.encode(report(listOf(valid), GateDecision.PASS)).toString(UTF_8)
            val source =
                temporaryDirectory.resolve("forged-targeted-${valid.mode}.json").also {
                    Files.writeString(it, validJson.replace("\"limit\":${valid.limit}", "\"limit\":100.0"))
                }

            assertThrows<IllegalArgumentException> { forged.validate("metric") }
            assertThrows<IllegalArgumentException> {
                BenchmarkJson.validateSchema(source, COMPARISON_SCHEMA)
            }
            assertThrows<IllegalArgumentException> { BenchmarkJson.read<ComparisonReport>(source) }
        }
    }

    @Test
    fun `evaluator rejects noncanonical regression policy`() {
        val forgedPolicy = RegressionPolicy(coldMedianUpper = 100.0)

        assertThrows<IllegalArgumentException> {
            ReleaseGateEvaluator(policy = forgedPolicy, resamples = 10)
        }
    }

    @Test
    fun `metric decisions bind gate identity and exact threshold result`() {
        val valid = coldMedianDecision()
        val forgeries =
            listOf(
                valid.copy(
                    interval = RatioInterval(1.0, 1.0, 1.06),
                    decision = GateDecision.PASS,
                ),
                valid.copy(decision = GateDecision.FAIL),
                valid.copy(mode = RunMode.WARM),
                valid.copy(metric = MetricId.ALLOCATED_BYTES),
                valid.copy(statistic = Statistic.P95),
                valid.copy(claimKind = ClaimKind.STRUCTURAL),
            )

        forgeries.forEach { forged ->
            assertThrows<IllegalArgumentException> { forged.validate("metric") }
        }
    }

    @Test
    fun `report overall is derived exactly from compatibility and metric precedence`() {
        val pass = coldMedianDecision()
        val fail =
            pass.copy(
                interval = RatioInterval(1.1, 1.1, 1.1),
                decision = GateDecision.FAIL,
            )
        val inconclusive =
            pass.copy(
                gate = GateId.COLD_P95,
                statistic = Statistic.P95,
                limit = 1.10,
                decision = GateDecision.INCONCLUSIVE,
            )
        val incompatible =
            pass.copy(
                interval = null,
                decision = GateDecision.INCOMPATIBLE,
            )

        listOf(
                report(listOf(fail), GateDecision.PASS),
                report(listOf(inconclusive), GateDecision.PASS),
                report(listOf(incompatible), GateDecision.PASS),
                report(listOf(pass), GateDecision.FAIL),
                report(listOf(pass), GateDecision.PASS, listOf("metadata mismatch")),
                report(listOf(pass), GateDecision.INCOMPATIBLE),
            )
            .forEach { forged ->
                assertThrows<IllegalArgumentException> { forged.validate() }
            }

        assertThat(report(listOf(fail, inconclusive), GateDecision.INCONCLUSIVE).validate())
            .isNotNull()
        assertThat(
                report(
                        listOf(fail, inconclusive),
                        GateDecision.INCOMPATIBLE,
                        listOf("metadata mismatch"),
                    )
                    .validate()
            )
            .isNotNull()
    }

    @Test
    fun `empty decision report is inconclusive and can never persist as pass`() {
        val inconclusive = report(emptyList(), GateDecision.INCONCLUSIVE)
        val persisted = temporaryDirectory.resolve("empty-inconclusive.json")

        assertThat(inconclusive.validate()).isSameInstanceAs(inconclusive)
        BenchmarkJson.write(persisted, inconclusive)
        BenchmarkJson.validateSchema(persisted, COMPARISON_SCHEMA)
        assertThat(BenchmarkJson.read<ComparisonReport>(persisted).overall)
            .isEqualTo(GateDecision.INCONCLUSIVE)

        val forgedPass = Files.readString(persisted).replace("INCONCLUSIVE", "PASS")
        val forged =
            temporaryDirectory.resolve("empty-pass.json").also { Files.writeString(it, forgedPass) }
        assertThrows<IllegalArgumentException> {
            report(emptyList(), GateDecision.PASS).validate()
        }
        assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(forged, COMPARISON_SCHEMA)
        }
        assertThrows<IllegalArgumentException> { BenchmarkJson.read<ComparisonReport>(forged) }
    }

    @Test
    fun `schema and decoder reject expressible report forgeries`() {
        val valid = Files.readString(resource("pass.json"))
        val wrongGate = valid.replace("WARM_MEDIAN", "COLD_MEDIAN")
        val wrongOverall = valid.replace("\"overall\":\"PASS\"", "\"overall\":\"FAIL\"")

        listOf("wrong-gate.json" to wrongGate, "wrong-overall.json" to wrongOverall).forEach {
            (name, json) ->
            val path = temporaryDirectory.resolve(name).also { Files.writeString(it, json) }
            assertThrows<IllegalArgumentException> {
                BenchmarkJson.validateSchema(path, COMPARISON_SCHEMA)
            }
            assertThrows<IllegalArgumentException> { BenchmarkJson.read<ComparisonReport>(path) }
        }
    }

    @Test
    fun `decoder rejects numeric threshold forgery the schema cannot compare`() {
        val valid = Files.readString(resource("pass.json"))
        val forged =
            valid
                .replace("\"upper95\":1.0", "\"upper95\":2.0")
                .let { json ->
                    temporaryDirectory.resolve("numeric-forgery.json").also {
                        Files.writeString(it, json)
                    }
                }

        BenchmarkJson.validateSchema(forged, COMPARISON_SCHEMA)
        assertThrows<IllegalArgumentException> { BenchmarkJson.read<ComparisonReport>(forged) }
    }

    @Test
    fun `Markdown renders every evidence family from the canonical report`() {
        val report =
            ComparisonReport(
                    campaignId = "markdown-fixture",
                    compatibilityErrors = emptyList(),
                    metrics =
                        listOf(
                            coldMedianDecision(),
                            MetricDecision(
                                gate = GateId.RETAINED_SLOPE,
                                claimKind = ClaimKind.STRUCTURAL,
                                mode = RunMode.RETAINED,
                                metric = MetricId.RETAINED_BYTES,
                                statistic = null,
                                interval = null,
                                slopeInterval = SlopeInterval(10.0, 5.0, 15.0),
                                observedValue = null,
                                limit = 1_024.0,
                                decision = GateDecision.PASS,
                                reason = "retained passes",
                            ),
                            MetricDecision(
                                gate = GateId.PER_STEP_ALLOCATION_SPREAD,
                                claimKind = ClaimKind.STRUCTURAL,
                                mode = RunMode.RETAINED,
                                metric = MetricId.BYTES_PER_STEP,
                                statistic = null,
                                interval = null,
                                slopeInterval = null,
                                observedValue = 1.1,
                                limit = 1.1,
                                decision = GateDecision.PASS,
                                reason = "spread passes",
                            ),
                        ),
                    rejectedBlocks = emptyList(),
                    overall = GateDecision.PASS,
                )
                .canonicalized()

        assertThat(report.toMarkdown()).isEqualTo(Files.readString(resource("rendered-report.md")))
    }

    private fun coldMedianDecision(): MetricDecision =
        MetricDecision(
            gate = GateId.COLD_MEDIAN,
            claimKind = ClaimKind.NON_REGRESSION,
            mode = RunMode.COLD,
            metric = MetricId.LATENCY,
            statistic = Statistic.MEDIAN,
            interval = RatioInterval(0.9, 0.8, 1.0),
            slopeInterval = null,
            observedValue = null,
            limit = 1.05,
            decision = GateDecision.PASS,
            reason = "ratio passes",
        )

    private fun targetedDecision(mode: RunMode, limit: Double): MetricDecision =
        MetricDecision(
            gate = null,
            claimKind = ClaimKind.TARGETED_IMPROVEMENT,
            mode = mode,
            metric = MetricId.LATENCY,
            statistic = Statistic.MEDIAN,
            interval = RatioInterval(0.5, 0.5, 0.5),
            slopeInterval = null,
            observedValue = null,
            limit = limit,
            decision = GateDecision.PASS,
            reason = "targeted passes",
        )

    private fun gateCases(): List<GateCase> =
        listOf(
            GateCase(GateId.COLD_MEDIAN, ClaimKind.NON_REGRESSION, RunMode.COLD, MetricId.LATENCY, Statistic.MEDIAN, 1.05),
            GateCase(GateId.COLD_P95, ClaimKind.NON_REGRESSION, RunMode.COLD, MetricId.LATENCY, Statistic.P95, 1.10),
            GateCase(GateId.COLD_ALLOCATION, ClaimKind.NON_REGRESSION, RunMode.COLD, MetricId.ALLOCATED_BYTES, Statistic.MEAN, 1.05),
            GateCase(GateId.COLD_PEAK_RSS, ClaimKind.NON_REGRESSION, RunMode.COLD, MetricId.PEAK_RSS, Statistic.MEAN, 1.05),
            GateCase(GateId.WARM_MEDIAN, ClaimKind.NON_REGRESSION, RunMode.WARM, MetricId.LATENCY, Statistic.MEDIAN, 1.03),
            GateCase(GateId.WARM_P95, ClaimKind.NON_REGRESSION, RunMode.WARM, MetricId.LATENCY, Statistic.P95, 1.05),
            GateCase(GateId.WARM_ALLOCATION, ClaimKind.NON_REGRESSION, RunMode.WARM, MetricId.ALLOCATED_BYTES, Statistic.MEAN, 1.03),
            GateCase(GateId.RETAINED_SLOPE, ClaimKind.STRUCTURAL, RunMode.RETAINED, MetricId.RETAINED_BYTES, null, 1_024.0),
            GateCase(
                GateId.PER_STEP_ALLOCATION_SPREAD,
                ClaimKind.STRUCTURAL,
                RunMode.RETAINED,
                MetricId.BYTES_PER_STEP,
                null,
                1.10,
            ),
        )

    private fun report(
        metrics: List<MetricDecision>,
        overall: GateDecision,
        compatibilityErrors: List<String> = emptyList(),
    ): ComparisonReport =
        ComparisonReport(
                campaignId = "forged",
                compatibilityErrors = compatibilityErrors,
                metrics = metrics,
                rejectedBlocks = emptyList(),
                overall = overall,
            )
            .canonicalized()

    private fun resource(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource("/compare/$name")).toURI())

    private companion object {
        const val COMPARISON_SCHEMA: String = "/schema/revoman-benchmark-comparison-v1.schema.json"
    }

    private data class GateCase(
        val gate: GateId,
        val claimKind: ClaimKind,
        val mode: RunMode,
        val metric: MetricId,
        val statistic: Statistic?,
        val limit: Double,
    ) {
        fun decision(): MetricDecision =
            MetricDecision(
                gate = gate,
                claimKind = claimKind,
                mode = mode,
                metric = metric,
                statistic = statistic,
                interval =
                    if (claimKind == ClaimKind.NON_REGRESSION) {
                        RatioInterval(0.5, 0.5, 0.5)
                    } else {
                        null
                    },
                slopeInterval =
                    if (metric == MetricId.RETAINED_BYTES) {
                        SlopeInterval(100.0, 100.0, 100.0)
                    } else {
                        null
                    },
                observedValue = if (metric == MetricId.BYTES_PER_STEP) 1.0 else null,
                limit = limit,
                decision = GateDecision.PASS,
                reason = "canonical passes",
            )
    }
}

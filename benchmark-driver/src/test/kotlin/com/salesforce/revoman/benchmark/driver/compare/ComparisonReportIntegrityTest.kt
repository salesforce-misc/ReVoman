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
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ComparisonReportIntegrityTest {
    @TempDir lateinit var temporaryDirectory: Path

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
}

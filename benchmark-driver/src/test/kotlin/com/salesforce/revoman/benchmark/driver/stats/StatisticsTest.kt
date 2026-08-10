/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.stats

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StatisticsTest {
    @Test
    fun `R7 median and p95 match hand calculated values`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 100.0)

        assertThat(r7Quantile(values, 0.5)).isEqualTo(3.0)
        assertThat(r7Quantile(values, 0.95)).isWithin(1.0e-12).of(80.8)
    }

    @Test
    fun `R7 validates probabilities and finite samples and returns direct boundaries`() {
        val values = listOf(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE)

        assertThat(r7Quantile(values, 0.0)).isEqualTo(-Double.MAX_VALUE)
        assertThat(r7Quantile(values, 0.5)).isEqualTo(0.0)
        assertThat(r7Quantile(values, 1.0)).isEqualTo(Double.MAX_VALUE)
        listOf(-0.01, 1.01, Double.NaN).forEach { probability ->
            assertThrows<IllegalArgumentException> { r7Quantile(values, probability) }
        }
        assertThrows<IllegalArgumentException> { r7Quantile(emptyList(), 0.5) }
        assertThrows<IllegalArgumentException> {
            r7Quantile(listOf(1.0, Double.POSITIVE_INFINITY), 0.5)
        }
    }

    @Test
    fun `cold hierarchy treats every fresh process as one fork observation`() {
        val hierarchy =
            pairedHierarchyFromAcceptedBlocks(
                blocks =
                    listOf(
                        block(
                            observations =
                                listOf(
                                    observation("baseline", fork = 0, processId = 101, value = 10.0),
                                    observation("baseline", fork = 1, processId = 102, value = 20.0),
                                    observation("candidate", fork = 0, processId = 201, value = 11.0),
                                    observation("candidate", fork = 1, processId = 202, value = 22.0),
                                )
                        )
                    ),
                baselineTargetId = "baseline",
                candidateTargetId = "candidate",
                mode = RunMode.COLD,
            )

        assertThat(hierarchy.blocks.single().baselineForks)
            .containsExactly(ForkSeries(0, listOf(10.0)), ForkSeries(1, listOf(20.0)))
            .inOrder()
        assertThat(hierarchy.blocks.single().candidateForks)
            .containsExactly(ForkSeries(0, listOf(11.0)), ForkSeries(1, listOf(22.0)))
            .inOrder()
    }

    @Test
    fun `raw hierarchy adapter rejects reused cold processes and warm fork process drift`() {
        val reusedCold =
            block(
                observations =
                    listOf(
                        observation("baseline", 0, 101, 10.0),
                        observation("baseline", 1, 101, 20.0),
                        observation("candidate", 0, 201, 11.0),
                        observation("candidate", 1, 202, 22.0),
                    )
            )
        assertThrows<IllegalArgumentException> {
            pairedHierarchyFromAcceptedBlocks(
                listOf(reusedCold),
                "baseline",
                "candidate",
                RunMode.COLD,
            )
        }

        val driftingWarm =
            block(
                observations =
                    listOf(
                        observation("baseline", 0, 101, 10.0, iteration = 0),
                        observation("baseline", 0, 102, 20.0, iteration = 1),
                        observation("candidate", 0, 201, 11.0, iteration = 0),
                        observation("candidate", 0, 201, 22.0, iteration = 1),
                    )
            )
        assertThrows<IllegalArgumentException> {
            pairedHierarchyFromAcceptedBlocks(
                listOf(driftingWarm),
                "baseline",
                "candidate",
                RunMode.WARM,
            )
        }
    }

    private fun block(observations: List<MetricObservation>): AlternatingBlock =
        AlternatingBlock(
            blockId = 7,
            targetOrder = listOf("baseline", "candidate"),
            healthBefore = health(),
            healthDuring = listOf(health()),
            healthAfter = health(),
            accepted = true,
            rejectionReasons = emptyList(),
            observations = observations,
        )

    private fun observation(
        targetId: String,
        fork: Int,
        processId: Long,
        value: Double,
        iteration: Int = 0,
    ): MetricObservation =
        MetricObservation(
            targetId = targetId,
            metric = MetricId.LATENCY,
            provider = "test",
            unit = MetricUnit.NANOSECONDS,
            fork = fork,
            iteration = iteration,
            processId = processId,
            value = value,
        )

    private fun health(): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = 1,
            loadAverage = 0.0,
            cpuBusyFraction = 0.0,
            availableMemoryBytes = 1,
            swapUsedBytes = 0,
            thermalValue = 0.0,
            onAcPower = true,
            governors = listOf("performance"),
        )
}

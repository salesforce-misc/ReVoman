/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.stats

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HierarchicalBootstrapTest {
    @Test
    fun `bootstrap resamples blocks then forks then warm iterations`() {
        val interval =
            hierarchicalRatioInterval(
                samples = threeLevelHierarchy(),
                statistic = Statistic.MEDIAN,
                resamples = 9,
                seed = 123,
            )

        assertThat(interval.pointEstimate).isWithin(1.0e-15).of(1.0833333333333333)
        assertThat(interval.lower95).isWithin(1.0e-15).of(0.11036750029353057)
        assertThat(interval.upper95).isWithin(1.0e-14).of(9.000000000000004)
    }

    @Test
    fun `same seed produces byte identical interval`() {
        val first =
            BenchmarkJson.encode(
                hierarchicalRatioInterval(
                    threeLevelHierarchy(),
                    Statistic.P95,
                    resamples = 200,
                    seed = 8675309,
                )
            )
        val second =
            BenchmarkJson.encode(
                hierarchicalRatioInterval(
                    threeLevelHierarchy(),
                    Statistic.P95,
                    resamples = 200,
                    seed = 8675309,
                )
            )

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `default seed and ten thousand resamples match checked in golden`() {
        val actual =
            BenchmarkJson.encode(
                hierarchicalRatioInterval(goldenHierarchy(), Statistic.MEDIAN)
            )
        val expected =
            requireNotNull(
                    javaClass.getResourceAsStream(
                        "/stats/hierarchical-bootstrap-seed-5245564f4d414e31.json"
                    )
                ) { "Missing hierarchical bootstrap golden" }
                .use { stream -> stream.readAllBytes().toString(UTF_8).trimEnd().toByteArray(UTF_8) }

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `an extreme valid observation is retained`() {
        val interval =
            hierarchicalRatioInterval(
                samples =
                    PairedHierarchy(
                        listOf(
                            PairedBlockSamples(
                                blockId = 0,
                                baselineForks = listOf(ForkSeries(0, listOf(1.0, 1.0))),
                                candidateForks =
                                    listOf(ForkSeries(0, listOf(1.0, Double.MAX_VALUE))),
                            )
                        )
                    ),
                statistic = Statistic.MEAN,
                resamples = 200,
                seed = 99,
            )

        assertThat(interval.pointEstimate).isGreaterThan(1.0e300)
        assertThat(interval.upper95).isGreaterThan(1.0e300)
    }

    @Test
    fun `zero baseline statistic is rejected before ratio construction`() {
        val samples =
            PairedHierarchy(
                listOf(
                    PairedBlockSamples(
                        blockId = 0,
                        baselineForks = listOf(ForkSeries(0, listOf(0.0))),
                        candidateForks = listOf(ForkSeries(0, listOf(1.0))),
                    )
                )
            )

        val failure = assertThrows<IllegalArgumentException> {
            hierarchicalRatioInterval(samples, Statistic.MEDIAN, resamples = 1, seed = 1)
        }

        assertThat(failure).hasMessageThat().contains("baseline")
    }

    @Test
    fun `hierarchy rejects invalid IDs empty leaves nonfinite values and nonpositive resamples`() {
        val valid = goldenHierarchy()
        val duplicateBlock = PairedHierarchy(valid.blocks + valid.blocks.first().copy())
        val duplicateFork =
            PairedHierarchy(
                listOf(
                    valid.blocks.first().copy(
                        baselineForks =
                            listOf(ForkSeries(0, listOf(1.0)), ForkSeries(0, listOf(2.0)))
                    )
                )
            )
        val emptyLeaf =
            PairedHierarchy(
                listOf(
                    valid.blocks.first().copy(
                        baselineForks = listOf(ForkSeries(0, emptyList()))
                    )
                )
            )
        val nonfinite =
            PairedHierarchy(
                listOf(
                    valid.blocks.first().copy(
                        candidateForks = listOf(ForkSeries(0, listOf(Double.NaN)))
                    )
                )
            )

        listOf(duplicateBlock, duplicateFork, emptyLeaf, nonfinite).forEach { samples ->
            assertThrows<IllegalArgumentException> {
                hierarchicalRatioInterval(samples, Statistic.MEDIAN, resamples = 1)
            }
        }
        assertThrows<IllegalArgumentException> {
            hierarchicalRatioInterval(valid, Statistic.MEDIAN, resamples = 0)
        }
    }

    @Test
    fun `nonfinite point or bootstrap ratios are rejected`() {
        val overflowing =
            PairedHierarchy(
                listOf(
                    PairedBlockSamples(
                        blockId = 0,
                        baselineForks = listOf(ForkSeries(0, listOf(Double.MIN_VALUE))),
                        candidateForks = listOf(ForkSeries(0, listOf(Double.MAX_VALUE))),
                    )
                )
            )

        assertThrows<IllegalArgumentException> {
            hierarchicalRatioInterval(overflowing, Statistic.MEAN, resamples = 1)
        }
    }

    private fun threeLevelHierarchy(): PairedHierarchy =
        PairedHierarchy(
            blocks =
                listOf(
                    PairedBlockSamples(
                        blockId = 0,
                        baselineForks =
                            listOf(
                                ForkSeries(0, listOf(1.0, 2.0)),
                                ForkSeries(1, listOf(10.0, 20.0)),
                            ),
                        candidateForks =
                            listOf(
                                ForkSeries(0, listOf(2.0, 8.0)),
                                ForkSeries(1, listOf(20.0, 80.0)),
                            ),
                    ),
                    PairedBlockSamples(
                        blockId = 1,
                        baselineForks =
                            listOf(
                                ForkSeries(0, listOf(100.0, 200.0)),
                                ForkSeries(1, listOf(1_000.0, 2_000.0)),
                            ),
                        candidateForks =
                            listOf(
                                ForkSeries(0, listOf(50.0, 400.0)),
                                ForkSeries(1, listOf(500.0, 4_000.0)),
                            ),
                    ),
                )
        )

    private fun goldenHierarchy(): PairedHierarchy =
        PairedHierarchy(
            blocks =
                listOf(
                    PairedBlockSamples(
                        blockId = 11,
                        baselineForks =
                            listOf(
                                ForkSeries(0, listOf(90.0, 100.0, 110.0)),
                                ForkSeries(1, listOf(95.0, 105.0, 115.0)),
                            ),
                        candidateForks =
                            listOf(
                                ForkSeries(0, listOf(92.0, 103.0, 116.0)),
                                ForkSeries(1, listOf(97.0, 108.0, 121.0)),
                            ),
                    ),
                    PairedBlockSamples(
                        blockId = 12,
                        baselineForks =
                            listOf(
                                ForkSeries(0, listOf(88.0, 99.0, 111.0)),
                                ForkSeries(1, listOf(94.0, 106.0, 118.0)),
                            ),
                        candidateForks =
                            listOf(
                                ForkSeries(0, listOf(91.0, 102.0, 117.0)),
                                ForkSeries(1, listOf(96.0, 109.0, 123.0)),
                            ),
                    ),
                )
        )
}

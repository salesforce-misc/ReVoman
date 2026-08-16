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
import com.salesforce.revoman.benchmark.driver.model.PowerEvidence
import com.salesforce.revoman.benchmark.driver.model.RetainedEvidence
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TheilSenTest {
    @Test
    fun `Theil Sen is median of all pairwise slopes`() {
        val points =
            listOf(
                RetainedPoint(1_000, 100.0),
                RetainedPoint(2_000, 400.0),
                RetainedPoint(4_000, 800.0),
            )

        assertThat(theilSen(points)).isWithin(1.0e-15).of(7.0 / 30.0)
    }

    @Test
    fun `Theil Sen rejects duplicate execution counts nonfinite bytes and slope overflow`() {
        assertThrows<IllegalArgumentException> {
            theilSen(listOf(RetainedPoint(1, 1.0), RetainedPoint(1, 2.0)))
        }
        assertThrows<IllegalArgumentException> {
            theilSen(listOf(RetainedPoint(1, 1.0), RetainedPoint(2, Double.NaN)))
        }
        assertThrows<IllegalArgumentException> {
            theilSen(
                listOf(
                    RetainedPoint(1, -Double.MAX_VALUE),
                    RetainedPoint(2, Double.MAX_VALUE),
                )
            )
        }
    }

    @Test
    fun `retained slope bootstrap resamples host blocks then replicate slopes and preserves weak reference failures`() {
        val weakFailure = WeakReferenceOutcome("ExecutionSession", created = 3, cleared = 2)
        val samples = retainedHierarchy(weakFailure)

        val interval =
            retainedSlopeInterval(
                samples = samples,
                targetRole = TargetRole.CANDIDATE,
                resamples = 9,
                seed = 456,
            )

        assertThat(interval.pointEstimateBytesPerExecution).isWithin(1.0e-12).of(30.0)
        assertThat(interval.lower95BytesPerExecution).isWithin(1.0e-12).of(20.0)
        assertThat(interval.upper95BytesPerExecution).isWithin(1.0e-12).of(50.0)
        assertThat(
                samples.blocks
                    .flatMap(RetainedBlockSamples::candidateReplicates)
                    .flatMap(RetainedReplicate::weakReferences)
            )
            .contains(weakFailure)
    }

    @Test
    fun `retained raw adapter concatenates every weak outcome in execution count order`() {
        val failure = WeakReferenceOutcome("two-thousand-failure", created = 2, cleared = 1)
        val block =
            retainedBlock(
                baseline =
                    rawRetainedGroup(
                        targetId = "baseline",
                        group = 0,
                        outcomes =
                            mapOf(
                                1_000 to
                                    listOf(
                                        WeakReferenceOutcome("one-a", 1, 1),
                                        WeakReferenceOutcome("one-b", 2, 2),
                                    ),
                                2_000 to listOf(failure),
                                4_000 to listOf(WeakReferenceOutcome("four", 4, 4)),
                            ),
                    ),
                candidate =
                    rawRetainedGroup(
                        targetId = "candidate",
                        group = 0,
                        outcomes =
                            mapOf(
                                1_000 to listOf(WeakReferenceOutcome("candidate-one", 1, 1)),
                                2_000 to listOf(WeakReferenceOutcome("candidate-two", 2, 2)),
                                4_000 to listOf(WeakReferenceOutcome("candidate-four", 4, 4)),
                            ),
                    ),
            )

        val hierarchy =
            retainedHierarchyFromAcceptedBlocks(listOf(block), "baseline", "candidate")
        val replicate = hierarchy.blocks.single().baselineReplicates.single()

        assertThat(replicate.points.map(RetainedPoint::executionCount))
            .containsExactly(1_000, 2_000, 4_000)
            .inOrder()
        assertThat(replicate.weakReferences.map(WeakReferenceOutcome::type))
            .containsExactly("one-a", "one-b", "two-thousand-failure", "four")
            .inOrder()
        assertThat(replicate.weakReferences).contains(failure)
    }

    @Test
    fun `retained raw adapter rejects a checkpoint that drops weak evidence`() {
        val block =
            retainedBlock(
                baseline =
                    rawRetainedGroup(
                        targetId = "baseline",
                        group = 0,
                        outcomes =
                            mapOf(
                                1_000 to emptyList(),
                                2_000 to listOf(WeakReferenceOutcome("baseline-two", 2, 2)),
                                4_000 to listOf(WeakReferenceOutcome("baseline-four", 4, 4)),
                            ),
                    ),
                candidate =
                    rawRetainedGroup(
                        targetId = "candidate",
                        group = 0,
                        outcomes =
                            mapOf(
                                1_000 to listOf(WeakReferenceOutcome("candidate-one", 1, 1)),
                                2_000 to listOf(WeakReferenceOutcome("candidate-two", 2, 2)),
                                4_000 to listOf(WeakReferenceOutcome("candidate-four", 4, 4)),
                            ),
                    ),
            )

        assertThrows<IllegalArgumentException> {
            retainedHierarchyFromAcceptedBlocks(listOf(block), "baseline", "candidate")
        }
    }

    @Test
    fun `retained hierarchy rejects ID shape and weak evidence mismatches`() {
        val valid = retainedHierarchy(WeakReferenceOutcome("ExecutionSession", 1, 0))
        val first = valid.blocks.first()
        val firstBaseline = first.baselineReplicates.first()
        val invalidSamples =
            listOf(
                RetainedHierarchy(valid.blocks + first.copy()),
                RetainedHierarchy(
                    valid.blocks.mapIndexed { index, block ->
                        if (index == 0) {
                            block.copy(
                                baselineReplicates =
                                    block.baselineReplicates + block.baselineReplicates.first()
                            )
                        } else {
                            block
                        }
                    }
                ),
                RetainedHierarchy(
                    valid.blocks.mapIndexed { index, block ->
                        if (index == 0) {
                            block.copy(
                                candidateReplicates =
                                    block.candidateReplicates.map { replicate ->
                                        replicate.copy(
                                            replicateGroup = replicate.replicateGroup + 100
                                        )
                                    }
                            )
                        } else {
                            block
                        }
                    }
                ),
                RetainedHierarchy(
                    valid.blocks.mapIndexed { index, block ->
                        if (index == 0) {
                            block.copy(
                                baselineReplicates =
                                    listOf(firstBaseline.copy(blockId = block.blockId + 100)) +
                                        block.baselineReplicates.drop(1)
                            )
                        } else {
                            block
                        }
                    }
                ),
                RetainedHierarchy(
                    valid.blocks.mapIndexed { index, block ->
                        if (index == 0) {
                            block.copy(
                                baselineReplicates =
                                    listOf(firstBaseline.copy(points = firstBaseline.points.dropLast(1))) +
                                        block.baselineReplicates.drop(1)
                            )
                        } else {
                            block
                        }
                    }
                ),
                RetainedHierarchy(
                    valid.blocks.mapIndexed { index, block ->
                        if (index == 0) {
                            block.copy(
                                candidateReplicates =
                                    block.candidateReplicates.map {
                                        it.copy(targetId = "baseline")
                                    }
                            )
                        } else {
                            block
                        }
                    }
                ),
                RetainedHierarchy(
                    valid.blocks.mapIndexed { index, block ->
                        if (index == 0) {
                            block.copy(
                                baselineReplicates =
                                    listOf(
                                        firstBaseline.copy(
                                            weakReferences =
                                                listOf(
                                                    WeakReferenceOutcome(
                                                        "invalid-clear-count",
                                                        created = 1,
                                                        cleared = 2,
                                                    )
                                                )
                                        )
                                    ) + block.baselineReplicates.drop(1)
                            )
                        } else {
                            block
                        }
                    }
                ),
            )

        invalidSamples.forEach { samples ->
            assertThrows<IllegalArgumentException> {
                retainedSlopeInterval(samples, TargetRole.BASELINE, resamples = 1)
            }
        }
        assertThrows<IllegalArgumentException> {
            retainedSlopeInterval(valid, TargetRole.BASELINE, resamples = 0)
        }
        assertThrows<IllegalArgumentException> {
            retainedSlopeInterval(
                RetainedHierarchy(listOf(valid.blocks.first())),
                TargetRole.BASELINE,
                resamples = 1,
            )
        }
    }

    private fun retainedHierarchy(weakFailure: WeakReferenceOutcome): RetainedHierarchy =
        RetainedHierarchy(
            blocks =
                listOf(
                    retainedSlopeBlock(
                        blockId = 0,
                        baselineSlopes = listOf(1.0, 2.0, 3.0),
                        candidateSlopes = listOf(10.0, 20.0, 30.0),
                        weakFailure = weakFailure,
                    ),
                    retainedSlopeBlock(
                        blockId = 1,
                        baselineSlopes = listOf(4.0, 5.0),
                        candidateSlopes = listOf(40.0, 50.0),
                        weakFailure = weakFailure,
                    ),
                )
        )

    private fun retainedSlopeBlock(
        blockId: Int,
        baselineSlopes: List<Double>,
        candidateSlopes: List<Double>,
        weakFailure: WeakReferenceOutcome,
    ): RetainedBlockSamples =
        RetainedBlockSamples(
            blockId = blockId,
            baselineReplicates =
                baselineSlopes.mapIndexed { group, slope ->
                    replicate(blockId, "baseline", group, slope, weakFailure)
                },
            candidateReplicates =
                candidateSlopes.mapIndexed { group, slope ->
                    replicate(blockId, "candidate", group, slope, weakFailure)
                },
        )

    private fun replicate(
        blockId: Int,
        targetId: String,
        group: Int,
        slope: Double,
        weakFailure: WeakReferenceOutcome,
    ): RetainedReplicate =
        RetainedReplicate(
            blockId = blockId,
            targetId = targetId,
            replicateGroup = group,
            points =
                listOf(
                    RetainedPoint(1_000, 100.0 + slope * 1_000),
                    RetainedPoint(2_000, 100.0 + slope * 2_000),
                    RetainedPoint(4_000, 100.0 + slope * 4_000),
                ),
            weakReferences = listOf(weakFailure),
        )

    private fun rawRetainedGroup(
        targetId: String,
        group: Int,
        outcomes: Map<Int, List<WeakReferenceOutcome>>,
    ): List<MetricObservation> =
        listOf(4_000, 1_000, 2_000).mapIndexed { shuffledIndex, executionCount ->
            MetricObservation(
                targetId = targetId,
                metric = MetricId.RETAINED_BYTES,
                provider = "full-gc/v1",
                unit = MetricUnit.BYTES,
                fork = group,
                iteration = when (executionCount) {
                    1_000 -> 0
                    2_000 -> 1
                    else -> 2
                },
                replicateGroup = group,
                processId = 10_000L * (if (targetId == "baseline") 1 else 2) + shuffledIndex,
                value = executionCount.toDouble(),
                retainedEvidence =
                    RetainedEvidence(
                        executionCount = executionCount,
                        completedGcCycles = 2,
                        weakReferences = requireNotNull(outcomes[executionCount]),
                    ),
            )
        }

    private fun retainedBlock(
        baseline: List<MetricObservation>,
        candidate: List<MetricObservation>,
    ): AlternatingBlock =
        AlternatingBlock(
            blockId = 0,
            targetOrder = listOf("baseline", "candidate"),
            healthBefore = health(),
            healthDuring = listOf(health()),
            healthAfter = health(),
            accepted = true,
            rejectionReasons = emptyList(),
            observations = baseline + candidate,
        )

    private fun health(): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = 1,
            loadAverage = 0.0,
            cpuBusyFraction = 0.0,
            availableMemoryBytes = 1,
            swapUsedBytes = 0,
            thermalValue = 0.0,
            powerEvidence = PowerEvidence.EXTERNAL_POWER_ONLINE,
            governors = listOf("performance"),
        )
}

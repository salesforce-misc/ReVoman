/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.host.HostHealthGate
import com.salesforce.revoman.benchmark.driver.host.HostHealthProbe
import com.salesforce.revoman.benchmark.driver.host.HostHealthReason
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AlternatingBlockSchedulerTest {
    @Test
    fun `same seed produces byte identical balanced target order`() {
        val first = AlternatingBlockScheduler(8675309).schedule(51, "baseline", "candidate")
        val second = AlternatingBlockScheduler(8675309).schedule(51, "baseline", "candidate")
        val firstBytes = scheduleBytes(first)
        val secondBytes = scheduleBytes(second)

        assertThat(secondBytes).isEqualTo(firstBytes)
        assertThat(first.map(TargetOrder::blockId)).containsExactlyElementsIn(0 until 51).inOrder()
        assertThat(first.all { it.targetIds.toSet() == setOf("baseline", "candidate") }).isTrue()
        val baselineFirst = first.count { it.targetIds.first() == "baseline" }
        val candidateFirst = first.size - baselineFirst
        assertThat(kotlin.math.abs(baselineFirst - candidateFirst)).isAtMost(1)
    }

    @Test
    fun `different seed changes interleaving without grouping either target`() {
        val first = AlternatingBlockScheduler(1).schedule(20, "baseline", "candidate")
        val second = AlternatingBlockScheduler(2).schedule(20, "baseline", "candidate")

        assertThat(second).isNotEqualTo(first)
        assertThat(first).containsNoneIn(
            listOf(
                TargetOrder(0, listOf("baseline", "baseline")),
                TargetOrder(0, listOf("candidate", "candidate")),
            )
        )
        assertThat(first.map { it.targetIds.first() }.distinct())
            .containsExactly("baseline", "candidate")
    }

    @Test
    fun `rejected pair retains health evidence and reasons while excluding both observations`() {
        val valid = validSnapshot()
        val highLoad = valid.copy(loadAverage = 4.0)
        val samples = ArrayDeque(listOf(valid, highLoad, highLoad, valid))
        val executed = mutableListOf<ScheduledTarget>()
        val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 0), samples)

        val result =
            coordinator.run(1, "baseline", "candidate") { target ->
                executed += target
                listOf(observation(target.targetId, value = 100.0))
            }

        assertThat(result.outcome).isEqualTo(PairedBlockOutcome.INCONCLUSIVE)
        assertThat(result.blocks).hasSize(1)
        assertThat(result.blocks.single().accepted).isFalse()
        assertThat(result.blocks.single().rejectionReasons)
            .containsExactly(HostHealthReason.LOAD_AVERAGE_EXCEEDS_MAXIMUM)
        assertThat(result.blocks.single().healthDuring).hasSize(2)
        assertThat(result.blocks.single().observations).isEmpty()
        assertThat(executed.map(ScheduledTarget::targetRole).toSet())
            .containsExactly(TargetRole.BASELINE, TargetRole.CANDIDATE)
    }

    @Test
    fun `replacement blocks continue until accepted count is met`() {
        val valid = validSnapshot()
        val highLoad = valid.copy(loadAverage = 4.0)
        val samples =
            ArrayDeque(
                listOf(
                    valid,
                    highLoad,
                    highLoad,
                    valid,
                    valid,
                    valid,
                    valid,
                    valid,
                    valid,
                    valid,
                    valid,
                    valid,
                )
            )
        val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 1), samples)

        val result = coordinator.run(2, "baseline", "candidate") { target ->
            listOf(observation(target.targetId, value = target.blockId.toDouble()))
        }

        assertThat(result.outcome).isEqualTo(PairedBlockOutcome.COMPLETE)
        assertThat(result.blocks.map { it.blockId }).containsExactly(0, 1, 2).inOrder()
        assertThat(result.blocks.map { it.accepted }).containsExactly(false, true, true).inOrder()
        assertThat(result.acceptedBlocks).hasSize(2)
        assertThat(result.rejectedBlocks).hasSize(1)
        assertThat(result.acceptedBlocks.all { it.observations.size == 2 }).isTrue()
    }

    @Test
    fun `insufficient accepted replacement blocks is inconclusive`() {
        val valid = validSnapshot()
        val highLoad = valid.copy(loadAverage = 4.0)
        val samples = ArrayDeque(List(12) { index -> if (index % 4 in 1..2) highLoad else valid })
        val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 1), samples)

        val result = coordinator.run(2, "baseline", "candidate") { target ->
            listOf(observation(target.targetId, value = 1.0))
        }

        assertThat(result.outcome).isEqualTo(PairedBlockOutcome.INCONCLUSIVE)
        assertThat(result.blocks).hasSize(3)
        assertThat(result.acceptedBlocks).isEmpty()
        assertThat(result.rejectedBlocks).hasSize(3)
    }

    @Test
    fun `changing measured latency cannot change health decision`() {
        fun execute(value: Double): PairedBlockCampaign {
            val samples = ArrayDeque(List(4) { validSnapshot() })
            return coordinator(policy().copy(maximumReplacementBlocks = 0), samples)
                .run(1, "baseline", "candidate") { target ->
                    listOf(observation(target.targetId, value))
                }
        }

        val fast = execute(1.0)
        val slow = execute(Double.MAX_VALUE)

        assertThat(slow.blocks.map { it.accepted to it.rejectionReasons })
            .isEqualTo(fast.blocks.map { it.accepted to it.rejectionReasons })
        assertThat(slow.blocks.single().observations.map(MetricObservation::value))
            .isNotEqualTo(fast.blocks.single().observations.map(MetricObservation::value))
    }

    @Test
    fun `scheduler coordinates plug into caller supplied cold positions`() {
        val samples = ArrayDeque(List(4) { validSnapshot() })
        val positions = mutableListOf<ColdPosition>()
        val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 0), samples)

        coordinator.run(1, "baseline", "candidate") { target ->
            positions += ColdPosition(target.blockId, target.targetRole, fork = 0)
            listOf(observation(target.targetId, 1.0))
        }

        assertThat(positions.map(ColdPosition::blockId).distinct()).containsExactly(0)
        assertThat(positions.map(ColdPosition::targetRole).toSet())
            .containsExactly(TargetRole.BASELINE, TargetRole.CANDIDATE)
    }

    @Test
    fun `primary target failure suppresses the final health sample failure`() {
        val primary = DeliberateFailure("target failed")
        var calls = 0
        val probe =
            HostHealthProbe {
                calls += 1
                if (calls == 2) error("after failed") else validSnapshot()
            }
        val coordinator =
            PairedBlockOrchestrator(
                policy = policy().copy(maximumReplacementBlocks = 0),
                scheduler = AlternatingBlockScheduler(1),
                probe = probe,
                gate = HostHealthGate(policy().copy(maximumReplacementBlocks = 0)),
            )

        val failure = assertThrows<DeliberateFailure> {
            coordinator.run(1, "baseline", "candidate") { throw primary }
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.suppressed.map(Throwable::message)).containsExactly("after failed")
    }

    @Test
    fun `replacement attempt count overflow fails before probing`() {
        var samples = 0
        val maximum = policy().copy(maximumReplacementBlocks = Int.MAX_VALUE)
        val coordinator =
            PairedBlockOrchestrator(
                policy = maximum,
                scheduler = AlternatingBlockScheduler(1),
                probe = HostHealthProbe {
                    samples += 1
                    validSnapshot()
                },
                gate = HostHealthGate(maximum),
            )

        assertThrows<ArithmeticException> {
            coordinator.run(Int.MAX_VALUE, "baseline", "candidate") { emptyList() }
        }
        assertThat(samples).isEqualTo(0)
    }

    private fun coordinator(
        policy: ControlledHostPolicy,
        samples: ArrayDeque<HostHealthSnapshot>,
    ): PairedBlockOrchestrator =
        PairedBlockOrchestrator(
            policy = policy,
            scheduler = AlternatingBlockScheduler(8675309),
            probe = HostHealthProbe { samples.removeFirst() },
            gate = HostHealthGate(policy),
        )

    private fun policy(): ControlledHostPolicy =
        BenchmarkJson.read(resourcePath("/host/valid.json"))

    private fun validSnapshot(): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = 1,
            loadAverage = 0.25,
            cpuBusyFraction = 0.25,
            availableMemoryBytes = 2_147_483_648,
            swapUsedBytes = 0,
            thermalValue = 50.0,
            onAcPower = true,
            governors = listOf("performance", "performance"),
        )

    private fun observation(targetId: String, value: Double): MetricObservation =
        MetricObservation(
            targetId = targetId,
            metric = MetricId.LATENCY,
            provider = "test-provider/v1",
            unit = MetricUnit.NANOSECONDS,
            fork = 0,
            iteration = 0,
            processId = 42,
            value = value,
        )

    private fun scheduleBytes(schedule: List<TargetOrder>): ByteArray =
        schedule.joinToString(separator = "\n") { order ->
            "${order.blockId}:${order.targetIds.joinToString(",")}"
        }.toByteArray(UTF_8)

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing test resource: $name" }.toURI())

    private class DeliberateFailure(message: String) : RuntimeException(message)
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.host.HostHealthGate
import com.salesforce.revoman.benchmark.driver.host.HostHealthProbe
import com.salesforce.revoman.benchmark.driver.host.HostHealthReason
import com.salesforce.revoman.benchmark.driver.host.HostGateEvent
import com.salesforce.revoman.benchmark.driver.host.HostGateEventSink
import com.salesforce.revoman.benchmark.driver.host.HostIncompleteReason
import com.salesforce.revoman.benchmark.driver.host.HostProbePhase
import com.salesforce.revoman.benchmark.driver.host.SampledHostExecution
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `every scheduled prefix keeps first position counts within one`() {
        (0L until 50L).forEach { seed ->
            val schedule = AlternatingBlockScheduler(seed).schedule(51, "baseline", "candidate")

            schedule.indices.forEach { lastIndex ->
                val prefix = schedule.subList(0, lastIndex + 1)
                val baselineFirst = prefix.count { it.targetIds.first() == "baseline" }
                val candidateFirst = prefix.size - baselineFirst
                assertWithMessage("seed=$seed prefix=${lastIndex + 1}")
                    .that(kotlin.math.abs(baselineFirst - candidateFirst))
                    .isAtMost(1)
            }
        }
    }

    @Test
    fun `complete outcome requires balanced accepted first positions`() {
        (0L until 50L).forEach { seed ->
            val samples = ArrayDeque(List(8) { validSnapshot() })
            val result =
                coordinator(
                        policy = policy().copy(maximumReplacementBlocks = 0),
                        samples = samples,
                        seed = seed,
                    )
                    .run(2, "baseline", "candidate") { target ->
                        listOf(observation(target.targetId, 1.0))
                    }
            val baselineFirst =
                result.acceptedBlocks.count { block -> block.targetOrder.first() == "baseline" }
            val candidateFirst = result.acceptedBlocks.size - baselineFirst

            assertWithMessage("seed=$seed")
                .that(result.outcome)
                .isEqualTo(PairedBlockOutcome.COMPLETE)
            assertWithMessage("seed=$seed accepted first-position balance")
                .that(kotlin.math.abs(baselineFirst - candidateFirst))
                .isAtMost(1)
        }
    }

    @Test
    fun `selective rejection that leaves biased accepted evidence is inconclusive`() {
        val seed = 8675309L
        val planned = AlternatingBlockScheduler(seed).schedule(3, "baseline", "candidate")
        val repeatedOrientation =
            requireNotNull(
                planned
                    .groupBy { it.targetIds.first() }
                    .entries
                    .singleOrNull { (_, blocks) -> blocks.size == 2 }
            ).key
        val rejectedBlock =
            planned.take(2).single { it.targetIds.first() != repeatedOrientation }.blockId
        val samples = scriptedSamples(blocks = 3, rejectedBlocks = setOf(rejectedBlock))
        val events = mutableListOf<HostGateEvent>()
        val result =
            coordinator(
                    policy = policy().copy(maximumReplacementBlocks = 1),
                    samples = samples,
                    seed = seed,
                    eventSink = HostGateEventSink { event -> events += event() },
                )
                .run(2, "baseline", "candidate") { target ->
                    listOf(observation(target.targetId, 1.0))
                }

        assertThat(result.acceptedBlocks).hasSize(2)
        assertThat(result.rejectedBlocks.map { it.blockId }).containsExactly(rejectedBlock)
        assertThat(result.acceptedBlocks.map { it.targetOrder.first() }.distinct()).hasSize(1)
        assertThat(result.outcome).isEqualTo(PairedBlockOutcome.INCONCLUSIVE)
        assertThat(events.filterIsInstance<HostGateEvent.CampaignIncomplete>().single().reason)
            .isEqualTo(HostIncompleteReason.IMBALANCED_ACCEPTED_ORDER)
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
    fun `empty callback evidence fails before accepted or rejected completion`() {
        listOf(false, true).forEach { rejectHealth ->
            val samples =
                scriptedSamples(
                    blocks = 1,
                    rejectedBlocks = if (rejectHealth) setOf(0) else emptySet(),
                )
            val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 0), samples)

            val failure = assertThrows<IllegalArgumentException> {
                coordinator.run(1, "baseline", "candidate") { emptyList() }
            }

            assertThat(failure).hasMessageThat().contains("must not be empty")
        }
    }

    @Test
    fun `wrong target callback evidence fails even when host health rejects the pair`() {
        val samples = scriptedSamples(blocks = 1, rejectedBlocks = setOf(0))
        val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 0), samples)

        val failure = assertThrows<IllegalArgumentException> {
            coordinator.run(1, "baseline", "candidate") { target ->
                listOf(observation("not-${target.targetId}", 1.0))
            }
        }

        assertThat(failure).hasMessageThat().contains("wrong target")
    }

    @Test
    fun `intrinsically invalid callback observations fail closed`() {
        val valid = observation("baseline", 1.0)
        val invalidCases =
            listOf(
                "blank provider" to valid.copy(provider = " "),
                "negative fork" to valid.copy(fork = -1),
                "negative iteration" to valid.copy(iteration = -1),
                "invalid process" to valid.copy(processId = 0),
                "negative value" to valid.copy(value = -1.0),
                "nonfinite value" to valid.copy(value = Double.NaN),
                "negative replicate" to valid.copy(replicateGroup = -1),
            )

        invalidCases.forEach { (name, invalid) ->
            val samples = scriptedSamples(blocks = 1, rejectedBlocks = emptySet())
            val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 0), samples)

            assertThrows<IllegalArgumentException>(name) {
                coordinator.run(1, "baseline", "candidate") { target ->
                    listOf(invalid.copy(targetId = target.targetId))
                }
            }
        }
    }

    @Test
    fun `duplicate intrinsic callback coordinates fail closed`() {
        val samples = scriptedSamples(blocks = 1, rejectedBlocks = emptySet())
        val coordinator = coordinator(policy().copy(maximumReplacementBlocks = 0), samples)

        val failure = assertThrows<IllegalArgumentException> {
            coordinator.run(1, "baseline", "candidate") { target ->
                val first = observation(target.targetId, 1.0)
                listOf(first, first.copy(processId = first.processId + 1))
            }
        }

        assertThat(failure).hasMessageThat().contains("coordinates")
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
    fun `during health bracket overlaps each callback without sampler threads`() {
        val policy = policy().copy(maximumReplacementBlocks = 0)
        val probe = BracketingProbe()
        val callbackThreads = mutableListOf<Long>()
        val coordinator =
            PairedBlockOrchestrator(
                policy = policy,
                scheduler = AlternatingBlockScheduler(1),
                probe = probe,
                gate = HostHealthGate(policy),
            )

        val result = coordinator.run(1, "baseline", "candidate") { target ->
            assertThat(probe.bracketStarted.await(0, TimeUnit.MILLISECONDS)).isTrue()
            assertThat(probe.bracketOpen.get()).isTrue()
            callbackThreads += Thread.currentThread().threadId()
            listOf(observation(target.targetId, 1.0))
        }

        assertThat(result.blocks.single().healthDuring).hasSize(2)
        assertThat(probe.bracketThreads).containsExactlyElementsIn(callbackThreads).inOrder()
        assertThat(probe.bracketOpen.get()).isFalse()
    }

    @Test
    fun `during probe failure fails closed after callback execution`() {
        val policy = policy().copy(maximumReplacementBlocks = 0)
        var executed = false
        val probe =
            object : HostHealthProbe {
                override fun sample(): HostHealthSnapshot = validSnapshot()

                override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> {
                    execution()
                    error("during failed")
                }
            }
        val coordinator =
            PairedBlockOrchestrator(
                policy = policy,
                scheduler = AlternatingBlockScheduler(1),
                probe = probe,
                gate = HostHealthGate(policy),
            )

        val failure = assertThrows<IllegalStateException> {
            coordinator.run(1, "baseline", "candidate") { target ->
                executed = true
                listOf(observation(target.targetId, 1.0))
            }
        }

        assertThat(executed).isTrue()
        assertThat(failure).hasMessageThat().contains("during failed")
    }

    @Test
    fun `primary target failure suppresses the final health sample failure`() {
        val primary = DeliberateFailure("target failed")
        var calls = 0
        val events = mutableListOf<HostGateEvent>()
        val probe =
            object : HostHealthProbe {
                override fun sample(): HostHealthSnapshot {
                    calls += 1
                    return if (calls == 2) error("after failed") else validSnapshot()
                }

                override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> =
                    try {
                        SampledHostExecution(execution(), validSnapshot())
                    } catch (failure: Throwable) {
                        failure.addSuppressed(IllegalStateException("during failed"))
                        throw failure
                    }
            }
        val coordinator =
            PairedBlockOrchestrator(
                policy = policy().copy(maximumReplacementBlocks = 0),
                scheduler = AlternatingBlockScheduler(1),
                probe = probe,
                gate = HostHealthGate(policy().copy(maximumReplacementBlocks = 0)),
                eventSink = HostGateEventSink { event -> events += event() },
            )

        val failure = assertThrows<DeliberateFailure> {
            coordinator.run(1, "baseline", "candidate") { throw primary }
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.suppressed.map(Throwable::message))
            .containsExactly("during failed", "after failed")
            .inOrder()
        assertThat(events.filterIsInstance<HostGateEvent.ProbeFailed>().map { it.phase })
            .containsExactly(HostProbePhase.DURING, HostProbePhase.AFTER)
            .inOrder()
    }

    @Test
    fun `replacement attempt count overflow fails before probing`() {
        var samples = 0
        val maximum = policy().copy(maximumReplacementBlocks = Int.MAX_VALUE)
        val coordinator =
            PairedBlockOrchestrator(
                policy = maximum,
                scheduler = AlternatingBlockScheduler(1),
                probe =
                    object : HostHealthProbe {
                        override fun sample(): HostHealthSnapshot {
                            samples += 1
                            return validSnapshot()
                        }

                        override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> {
                            samples += 1
                            return SampledHostExecution(execution(), validSnapshot())
                        }
                    },
                gate = HostHealthGate(maximum),
            )

        assertThrows<ArithmeticException> {
            coordinator.run(Int.MAX_VALUE, "baseline", "candidate") { emptyList() }
        }
        assertThat(samples).isEqualTo(0)
    }

    @Test
    fun `NoOp host event sink does not evaluate lazy diagnostics`() {
        var evaluated = false

        HostGateEventSink.NoOp.emit {
            evaluated = true
            HostGateEvent.CampaignIncomplete(
                requestedAcceptedBlocks = 1,
                acceptedBlocks = 0,
                attemptedBlocks = 1,
                reason = HostIncompleteReason.INSUFFICIENT_ACCEPTED_BLOCKS,
                baselineFirstBlocks = 0,
                candidateFirstBlocks = 0,
            )
        }

        assertThat(evaluated).isFalse()
    }

    @Test
    fun `structured host events report rejection replacement and incomplete outside callbacks`() {
        val policy = policy().copy(maximumReplacementBlocks = 1)
        val samples = scriptedSamples(blocks = 2, rejectedBlocks = setOf(0, 1))
        val callbackActive = AtomicBoolean()
        val events = mutableListOf<HostGateEvent>()
        val sink =
            HostGateEventSink { event ->
                assertThat(callbackActive.get()).isFalse()
                events += event()
            }
        val coordinator =
            PairedBlockOrchestrator(
                policy = policy,
                scheduler = AlternatingBlockScheduler(1),
                probe = QueuedProbe(samples),
                gate = HostHealthGate(policy),
                eventSink = sink,
            )

        val result = coordinator.run(1, "baseline", "candidate") { target ->
            callbackActive.set(true)
            try {
                listOf(observation(target.targetId, 1.0))
            } finally {
                callbackActive.set(false)
            }
        }

        assertThat(result.outcome).isEqualTo(PairedBlockOutcome.INCONCLUSIVE)
        assertThat(events.filterIsInstance<HostGateEvent.BlockRejected>().map { it.blockId })
            .containsExactly(0, 1)
            .inOrder()
        assertThat(events.filterIsInstance<HostGateEvent.ReplacementScheduled>().map { it.blockId })
            .containsExactly(1)
        assertThat(events.filterIsInstance<HostGateEvent.CampaignIncomplete>()).hasSize(1)
    }

    @Test
    fun `structured host event reports fail closed probe phase`() {
        val policy = policy().copy(maximumReplacementBlocks = 0)
        val events = mutableListOf<HostGateEvent>()
        val probe =
            object : HostHealthProbe {
                override fun sample(): HostHealthSnapshot = error("before probe failed")

                override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> =
                    error("unexpected during probe")
            }
        val coordinator =
            PairedBlockOrchestrator(
                policy = policy,
                scheduler = AlternatingBlockScheduler(1),
                probe = probe,
                gate = HostHealthGate(policy),
                eventSink = HostGateEventSink { event -> events += event() },
            )

        assertThrows<IllegalStateException> {
            coordinator.run(1, "baseline", "candidate") { target ->
                listOf(observation(target.targetId, 1.0))
            }
        }

        val failure = events.single() as HostGateEvent.ProbeFailed
        assertThat(failure.phase).isEqualTo(HostProbePhase.BEFORE)
        assertThat(failure.detail).contains("before probe failed")
    }

    private fun coordinator(
        policy: ControlledHostPolicy,
        samples: ArrayDeque<HostHealthSnapshot>,
        seed: Long = 8675309,
        eventSink: HostGateEventSink = HostGateEventSink.NoOp,
    ): PairedBlockOrchestrator =
        PairedBlockOrchestrator(
            policy = policy,
            scheduler = AlternatingBlockScheduler(seed),
            probe = QueuedProbe(samples),
            gate = HostHealthGate(policy),
            eventSink = eventSink,
        )

    private fun scriptedSamples(
        blocks: Int,
        rejectedBlocks: Set<Int>,
    ): ArrayDeque<HostHealthSnapshot> =
        ArrayDeque(
            (0 until blocks).flatMap { blockId ->
                val valid = validSnapshot()
                val during =
                    if (blockId in rejectedBlocks) valid.copy(loadAverage = 4.0) else valid
                listOf(valid, during, during, valid)
            }
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

    private class QueuedProbe(
        private val samples: ArrayDeque<HostHealthSnapshot>
    ) : HostHealthProbe {
        override fun sample(): HostHealthSnapshot = samples.removeFirst()

        override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> =
            SampledHostExecution(execution(), samples.removeFirst())
    }

    private inner class BracketingProbe : HostHealthProbe {
        val bracketStarted = CountDownLatch(1)
        val bracketOpen = AtomicBoolean()
        val bracketThreads = mutableListOf<Long>()
        private var capturedAt = 0L

        override fun sample(): HostHealthSnapshot =
            validSnapshot().copy(capturedAtNanos = ++capturedAt)

        override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> {
            bracketThreads += Thread.currentThread().threadId()
            bracketOpen.set(true)
            bracketStarted.countDown()
            return try {
                SampledHostExecution(
                    value = execution(),
                    snapshot = validSnapshot().copy(capturedAtNanos = ++capturedAt),
                )
            } finally {
                bracketOpen.set(false)
            }
        }
    }
}

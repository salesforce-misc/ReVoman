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
import com.salesforce.revoman.benchmark.driver.host.PowerEvidenceRequirement
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.PowerEvidence
import com.salesforce.revoman.benchmark.driver.model.RetainedEvidence
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BenchmarkCampaignTest {
    @Test
    fun `warm allocation timeout adds fixed bounded headroom to smoke and controlled budgets`() {
        assertThat(warmAllocationControllerTimeout(0, 1, Duration.ofSeconds(1)))
            .isEqualTo(Duration.ofSeconds(31))
        assertThat(warmAllocationControllerTimeout(20, 100, Duration.ofSeconds(1)))
            .isEqualTo(Duration.ofSeconds(150))
    }

    @Test
    fun `assembler keeps provider identity and actual alternating block role observations`() {
        val blocks =
            listOf(
                block(0, listOf("baseline", "candidate"), 100),
                block(1, listOf("candidate", "baseline"), 200),
            )
        val evidence =
            blocks.flatMap { block ->
                block.targetOrder.map { targetId ->
                    ProviderEvidence(
                        blockId = block.blockId,
                        targetId = targetId,
                        metric = MetricId.LATENCY,
                        provider = "provider/v1",
                        providerConfigurationSha256 = "a".repeat(64),
                        unit = MetricUnit.NANOSECONDS,
                        artifacts = emptyList(),
                    )
                }
            }

        val series = CampaignEvidenceAssembler.assemble(blocks, evidence)

        assertThat(requireNotNull(series.blocks).map { it.targetOrder })
            .containsExactly(listOf("baseline", "candidate"), listOf("candidate", "baseline"))
            .inOrder()
        assertThat(requireNotNull(series.blocks).flatMap { it.observations }.map { it.targetId })
            .containsExactly("baseline", "candidate", "candidate", "baseline")
            .inOrder()
    }

    @Test
    fun `assembler combines baseline and candidate retained evidence under one exact v2 identity`() {
        val provider = "revoman-retained-two-phase-weak-proof-final-heap/v2"
        val configuration =
            "b8d9dfd1d8497bb1e45b543ffa46882cbc6094a91d783a060ced2213c437f6e8"
        val order = listOf("baseline-retained", "candidate-retained")
        val retainedBlock =
            block(0, order, 100).copy(
                observations =
                    listOf(1_000, 2_000, 4_000).flatMapIndexed { iteration, executionCount ->
                        listOf(
                            retainedObservation(
                                targetId = order[0],
                                provider = provider,
                                iteration = iteration,
                                processId = 100L + iteration,
                                executionCount = executionCount,
                                weakReferences =
                                    listOf(WeakReferenceOutcome("Cs1FakeExecutionToken", 1, 1)),
                            ),
                            retainedObservation(
                                targetId = order[1],
                                provider = provider,
                                iteration = iteration,
                                processId = 103L + iteration,
                                executionCount = executionCount,
                                weakReferences =
                                    listOf(
                                        WeakReferenceOutcome(
                                            "ExecutionSession",
                                            executionCount,
                                            executionCount,
                                        ),
                                        WeakReferenceOutcome(
                                            "KickExecution",
                                            executionCount,
                                            executionCount,
                                        ),
                                    ),
                            ),
                        )
                    }
            )
        val evidence =
            order.map { targetId ->
                ProviderEvidence(
                    blockId = 0,
                    targetId = targetId,
                    metric = MetricId.RETAINED_BYTES,
                    provider = provider,
                    providerConfigurationSha256 = configuration,
                    unit = MetricUnit.BYTES,
                    artifacts = emptyList(),
                )
            }

        val series = CampaignEvidenceAssembler.assemble(listOf(retainedBlock), evidence)

        assertThat(series.provider).isEqualTo(provider)
        assertThat(series.providerConfigurationSha256).isEqualTo(configuration)
        assertThat(requireNotNull(series.blocks).single().observations.map { it.targetId }.distinct())
            .containsExactlyElementsIn(order)
            .inOrder()
        assertThat(
                requireNotNull(series.blocks).single().observations.filter { it.iteration == 0 }.map {
                    requireNotNull(it.retainedEvidence).weakReferences.map(WeakReferenceOutcome::type)
                }
            )
            .containsExactly(
                listOf("Cs1FakeExecutionToken"),
                listOf("ExecutionSession", "KickExecution"),
            )
            .inOrder()
    }

    @Test
    fun `assembler rejects provider configuration drift instead of merging incompatible evidence`() {
        val blocks = listOf(block(0, listOf("baseline", "candidate"), 100))
        val evidence =
            listOf(
                providerEvidence(0, "baseline", "a"),
                providerEvidence(0, "candidate", "b"),
            )

        val failure = assertThrows<IllegalArgumentException> {
            CampaignEvidenceAssembler.assemble(blocks, evidence)
        }

        assertThat(failure).hasMessageThat().contains("provider identities")
    }

    @Test
    fun `assembler preserves rejected block evidence without target observations`() {
        val rejected =
            block(0, listOf("baseline", "candidate"), 100)
                .copy(accepted = false, rejectionReasons = listOf("thermal"), observations = emptyList())

        val series =
            CampaignEvidenceAssembler.assemble(
                listOf(rejected, block(1, listOf("candidate", "baseline"), 200)),
                listOf(
                    providerEvidence(0, "baseline", "a"),
                    providerEvidence(0, "candidate", "a"),
                    providerEvidence(1, "candidate", "a"),
                    providerEvidence(1, "baseline", "a"),
                ),
            )

        assertThat(requireNotNull(series.blocks).first().accepted).isFalse()
        assertThat(requireNotNull(series.blocks).first().rejectionReasons).containsExactly("thermal")
        assertThat(requireNotNull(series.blocks).first().observations).isEmpty()
    }

    @Test
    fun `synthetic smoke health records unavailable power evidence and cannot satisfy fixed mains`() {
        val probe = syntheticHostProbe()
        val samples = listOf(probe.sample(), probe.sample(), probe.sample())

        assertThat(samples.map { it.powerEvidence })
            .containsExactly(
                PowerEvidence.UNAVAILABLE,
                PowerEvidence.UNAVAILABLE,
                PowerEvidence.UNAVAILABLE,
            )
            .inOrder()
        assertThat(
                HostHealthGate(
                        smokePolicy(
                            powerEvidenceRequirement = PowerEvidenceRequirement.FIXED_MAINS,
                        )
                    )
                    .assess(samples[0], samples.drop(1).take(1), samples[2])
                    .accepted
            )
            .isFalse()
    }

    private fun providerEvidence(blockId: Int, targetId: String, hashDigit: String): ProviderEvidence =
        ProviderEvidence(
            blockId = blockId,
            targetId = targetId,
            metric = MetricId.LATENCY,
            provider = "provider/v1",
            providerConfigurationSha256 = hashDigit.repeat(64),
            unit = MetricUnit.NANOSECONDS,
            artifacts = emptyList(),
        )

    private fun block(blockId: Int, order: List<String>, value: Long): AlternatingBlock =
        AlternatingBlock(
            blockId = blockId,
            targetOrder = order,
            healthBefore = health(blockId * 3L),
            healthDuring = listOf(health(blockId * 3L + 1)),
            healthAfter = health(blockId * 3L + 2),
            accepted = true,
            rejectionReasons = emptyList(),
            observations =
                order.mapIndexed { index, target ->
                    MetricObservation(
                        targetId = target,
                        metric = MetricId.LATENCY,
                        provider = "provider/v1",
                        unit = MetricUnit.NANOSECONDS,
                        fork = 0,
                        iteration = 0,
                        processId = value + index,
                        value = value.toDouble() + index,
                    )
                },
        )

    private fun retainedObservation(
        targetId: String,
        provider: String,
        iteration: Int,
        processId: Long,
        executionCount: Int,
        weakReferences: List<WeakReferenceOutcome>,
    ): MetricObservation =
        MetricObservation(
            targetId = targetId,
            metric = MetricId.RETAINED_BYTES,
            provider = provider,
            unit = MetricUnit.BYTES,
            fork = 0,
            iteration = iteration,
            replicateGroup = 0,
            processId = processId,
            value = 1_000.0,
            retainedEvidence =
                RetainedEvidence(
                    executionCount = executionCount,
                    completedGcCycles = 4,
                    weakReferences = weakReferences,
                ),
        )

    private fun health(time: Long): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = time,
            loadAverage = 0.0,
            cpuBusyFraction = 0.0,
            availableMemoryBytes = 1,
            swapUsedBytes = 0,
            thermalValue = 0.0,
            powerEvidence = PowerEvidence.EXTERNAL_POWER_ONLINE,
            governors = listOf("performance"),
        )

    private fun syntheticHostProbe(): HostHealthProbe {
        val type =
            Class.forName("com.salesforce.revoman.benchmark.driver.run.SyntheticHostProbe")
        val constructor = type.getDeclaredConstructor().also { it.isAccessible = true }
        return constructor.newInstance() as HostHealthProbe
    }

    private fun smokePolicy(
        powerEvidenceRequirement: PowerEvidenceRequirement,
    ): ControlledHostPolicy =
        ControlledHostPolicy(
            hostFingerprintSha256 = "0".repeat(64),
            cpuModel = "smoke",
            cpuCount = 1,
            allowedGovernors = setOf("unknown"),
            powerEvidenceRequirement = powerEvidenceRequirement,
            maximumLoadAverage = Double.MAX_VALUE,
            maximumCpuBusyFraction = 1.0,
            minimumAvailableMemoryBytes = 1,
            maximumSwapDeltaBytes = Long.MAX_VALUE,
            maximumThermalValue = Double.MAX_VALUE,
            probeIntervalMillis = 1,
            maximumReplacementBlocks = 0,
        )
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
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

    private fun health(time: Long): HostHealthSnapshot =
        HostHealthSnapshot(time, 0.0, 0.0, 1, 0, 0.0, true, listOf("performance"))
}

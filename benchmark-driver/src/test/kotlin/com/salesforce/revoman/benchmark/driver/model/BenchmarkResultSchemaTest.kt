/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.squareup.moshi.JsonDataException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BenchmarkResultSchemaTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `valid v1 campaign round trips canonically`() {
        val source = resultFixture("minimal-valid.json")
        BenchmarkJson.validateSchema(source, RESULT_SCHEMA)

        val firstWrite = temporaryDirectory.resolve("first.json")
        val secondWrite = temporaryDirectory.resolve("second.json")
        BenchmarkJson.write(firstWrite, BenchmarkJson.read<BenchmarkResultV1>(source))
        BenchmarkJson.write(secondWrite, BenchmarkJson.read<BenchmarkResultV1>(firstWrite))

        assertThat(Files.readAllBytes(secondWrite)).isEqualTo(Files.readAllBytes(firstWrite))
    }

    @Test
    fun `unknown result property is rejected`() {
        val source = resultFixture("invalid-unknown-field.json")
        val schemaFailure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(source, RESULT_SCHEMA)
        }

        val parseFailure = assertThrows<JsonDataException> {
            BenchmarkJson.read<BenchmarkResultV1>(source)
        }

        assertThat(schemaFailure).hasMessageThat().contains("unexpected")
        assertThat(parseFailure).hasMessageThat().contains("unexpected")
    }

    @Test
    fun `missing 64 character hash is rejected`() {
        val source = resultFixture("invalid-missing-hash.json")
        val schemaFailure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(source, RESULT_SCHEMA)
        }

        val parseFailure = assertThrows<JsonDataException> {
            BenchmarkJson.read<BenchmarkResultV1>(source)
        }

        assertThat(schemaFailure).hasMessageThat().contains("distributionSha256")
        assertThat(parseFailure).hasMessageThat().contains("distributionSha256")
    }

    @Test
    fun `declared sample count must equal observations`() {
        val source = resultFixture("invalid-count.json")
        BenchmarkJson.validateSchema(source, RESULT_SCHEMA)

        val failure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.read<BenchmarkResultV1>(source)
        }

        assertThat(failure).hasMessageThat().contains("expected=4")
        assertThat(failure).hasMessageThat().contains("actual=2")
    }

    @Test
    fun `raw observations and exact histogram are mutually exclusive`() {
        val source = resultFixture("invalid-both-sample-forms.json")
        val schemaFailure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(source, RESULT_SCHEMA)
        }

        val validationFailure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.read<BenchmarkResultV1>(source)
        }

        assertThat(schemaFailure).hasMessageThat().contains("metricSeries")
        assertThat(validationFailure).hasMessageThat().contains("workloads[0].metricSeries[0]")
    }

    @Test
    fun `campaign assignments must match target and adapter identities`() {
        val campaign = BenchmarkJson.read<BenchmarkResultV1>(resultFixture("minimal-valid.json"))
        val invalidAssignment = campaign.configuration.targets.first().copy(adapterId = "other-adapter")
        val invalidCampaign = campaign.copy(
            configuration = campaign.configuration.copy(
                targets = listOf(invalidAssignment) + campaign.configuration.targets.drop(1),
            ),
        )

        val failure = assertThrows<IllegalArgumentException> { invalidCampaign.validate() }

        assertThat(failure).hasMessageThat().contains("adapterId")
        assertThat(failure).hasMessageThat().contains("baseline")
    }

    @Test
    fun `alternating block preserves reversed paired execution order`() {
        val campaign = validCampaign()
        val reversedOrder = campaign.withBlock {
            copy(targetOrder = targetOrder.reversed())
        }

        assertThat(reversedOrder.validate()).isSameInstanceAs(reversedOrder)
    }

    @Test
    fun `alternating block rejects duplicate target IDs`() {
        val campaign = validCampaign().withBlock {
            copy(targetOrder = listOf("baseline", "baseline"))
        }

        val failure = assertThrows<IllegalArgumentException> { campaign.validate() }

        assertThat(failure).hasMessageThat().contains("targetOrder")
    }

    @Test
    fun `alternating block rejects a missing target ID`() {
        val campaign = validCampaign().withBlock {
            copy(targetOrder = listOf("baseline"))
        }

        val failure = assertThrows<IllegalArgumentException> { campaign.validate() }

        assertThat(failure).hasMessageThat().contains("targetOrder")
    }

    @Test
    fun `rejected block keeps health reasons while excluding the whole observation pair`() {
        val campaign = validCampaign()
        val accepted = campaign.onlyBlock()
        val rejected =
            accepted.copy(
                blockId = 1,
                accepted = false,
                rejectionReasons = listOf("load-average-exceeds-maximum"),
                observations = emptyList(),
            )
        val blocks = listOf(accepted, rejected)
        val withRejected =
            campaign.copy(
                workloads =
                    campaign.workloads.map { workload ->
                        workload.copy(
                            metricSeries = workload.metricSeries.map { series -> series.copy(blocks = blocks) }
                        )
                    }
            )
        val written = temporaryDirectory.resolve("accepted-and-rejected.json")

        assertThat(withRejected.validate()).isSameInstanceAs(withRejected)
        BenchmarkJson.write(written, withRejected)
        assertThat(
                BenchmarkJson.read<BenchmarkResultV1>(written)
                    .workloads.single()
                    .metricSeries.single()
                    .blocks!!
                    .single { it.blockId == 0 }
                    .accepted
            )
            .isTrue()
        assertThat(
                BenchmarkJson.read<BenchmarkResultV1>(written)
                    .workloads.single()
                    .metricSeries.single()
                    .blocks!!
                    .single { it.blockId == 1 }
                    .observations
            )
            .isEmpty()

        val contaminated =
            withRejected.copy(
                workloads =
                    withRejected.workloads.map { workload ->
                        workload.copy(
                            metricSeries =
                                workload.metricSeries.map { series ->
                                    series.copy(
                                        blocks =
                                            requireNotNull(series.blocks).map { block ->
                                                if (block.blockId == 1) {
                                                    block.copy(observations = accepted.observations)
                                                } else {
                                                    block
                                                }
                                            }
                                    )
                                }
                        )
                    }
            )

        val failure = assertThrows<IllegalArgumentException> { contaminated.validate() }
        assertThat(failure).hasMessageThat().contains("rejected block observations must be empty")
    }

    @Test
    fun `duplicate observation coordinate cannot replace a required coordinate`() {
        val campaign = coordinateGridCampaign()
        val observations = campaign.onlyBlock().observations
        val replaced = observations.dropLast(1) + observations.first().copy(processId = 9001)

        val failure = assertThrows<IllegalArgumentException> {
            campaign.withBlock { copy(observations = replaced) }.validate()
        }

        assertThat(failure).hasMessageThat().contains("coordinates")
    }

    @Test
    fun `missing observation coordinate is rejected`() {
        val campaign = coordinateGridCampaign()
        val incomplete = campaign.onlyBlock().observations.dropLast(1)

        val failure = assertThrows<IllegalArgumentException> {
            campaign.withBlock { copy(observations = incomplete) }.validate()
        }

        assertThat(failure).hasMessageThat().contains("expected=8")
        assertThat(failure).hasMessageThat().contains("actual=7")
    }

    @Test
    fun `observation iteration outside the configured range is rejected`() {
        val campaign = coordinateGridCampaign()
        val observations = campaign.onlyBlock().observations
        val outOfRange = observations.dropLast(1) + observations.last().copy(iteration = 2)

        val failure = assertThrows<IllegalArgumentException> {
            campaign.withBlock { copy(observations = outOfRange) }.validate()
        }

        assertThat(failure).hasMessageThat().contains("coordinates")
    }

    @Test
    fun `observation fork outside the configured range is rejected`() {
        val campaign = coordinateGridCampaign()
        val observations = campaign.onlyBlock().observations
        val outOfRange = observations.dropLast(1) + observations.last().copy(fork = 2)

        val failure = assertThrows<IllegalArgumentException> {
            campaign.withBlock { copy(observations = outOfRange) }.validate()
        }

        assertThat(failure).hasMessageThat().contains("fork")
    }

    private fun validCampaign(): BenchmarkResultV1 =
        BenchmarkJson.read(resultFixture("minimal-valid.json"))

    private fun coordinateGridCampaign(): BenchmarkResultV1 {
        val campaign = validCampaign()
        val templatesByTarget = campaign.onlyBlock().observations.associateBy(MetricObservation::targetId)
        val observations =
            campaign.targets.flatMap { target ->
                (0 until 2).flatMap { fork ->
                    (0 until 2).map { iteration ->
                        requireNotNull(templatesByTarget[target.id]).copy(
                            fork = fork,
                            iteration = iteration,
                            processId = 1_000L + fork * 10 + iteration,
                        )
                    }
                }
            }
        return campaign
            .copy(
                configuration = campaign.configuration.copy(
                    forksPerBlock = 2,
                    measurementIterations = 2,
                ),
            )
            .withBlock { copy(observations = observations) }
    }

    private fun BenchmarkResultV1.onlyBlock(): AlternatingBlock =
        requireNotNull(workloads.single().metricSeries.single().blocks).single()

    private fun BenchmarkResultV1.withBlock(
        transform: AlternatingBlock.() -> AlternatingBlock,
    ): BenchmarkResultV1 {
        val workload = workloads.single()
        val series = workload.metricSeries.single()
        val updatedSeries = series.copy(blocks = listOf(onlyBlock().transform()))
        return copy(
            workloads = listOf(workload.copy(metricSeries = listOf(updatedSeries))),
        )
    }

    private fun resultFixture(name: String): Path =
        Path.of(
            requireNotNull(javaClass.getResource("/results/v1/$name")) {
                "Missing result fixture: $name"
            }.toURI(),
        )

    private companion object {
        const val RESULT_SCHEMA = "/schema/revoman-benchmark-v1.schema.json"
    }
}

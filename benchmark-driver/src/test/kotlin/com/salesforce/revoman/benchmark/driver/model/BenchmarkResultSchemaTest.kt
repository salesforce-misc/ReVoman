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

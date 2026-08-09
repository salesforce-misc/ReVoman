/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class JmhGcResultImporterTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `JMH importer requires raw gc alloc rate norm and preserves block fork identity`() {
        val imported = JmhGcResultImporter.import(
            rawResult = resourcePath("/metrics/jmh-gc.txt"),
            targetId = "candidate",
            blockId = 7,
            targetRole = TargetRole.CANDIDATE,
            fork = 3,
        )

        assertThat(imported.blockId).isEqualTo(7)
        assertThat(imported.targetRole).isEqualTo(TargetRole.CANDIDATE)
        assertThat(imported.fork).isEqualTo(3)
        assertThat(imported.providerConfigurationSha256).hasLength(64)
        assertThat(imported.observations.map { it.metric })
            .containsExactly(MetricId.ALLOCATED_BYTES, MetricId.ALLOCATED_BYTES)
        assertThat(imported.observations.map { it.unit })
            .containsExactly(MetricUnit.BYTES_PER_OPERATION, MetricUnit.BYTES_PER_OPERATION)
        assertThat(imported.observations.map { it.fork }).containsExactly(3, 3).inOrder()
        assertThat(imported.observations.map { it.iteration }).containsExactly(0, 1).inOrder()
        assertThat(imported.observations.map { it.processId }).containsExactly(4_201L, 4_201L)
        assertThat(imported.observations.map { it.value }).containsExactly(100.25, 101.5).inOrder()
        val differentCoordinates =
            JmhGcResultImporter.import(
                resourcePath("/metrics/jmh-gc.txt"),
                "baseline",
                99,
                TargetRole.BASELINE,
                8,
            )
        assertThat(differentCoordinates.providerConfigurationSha256)
            .isEqualTo(imported.providerConfigurationSha256)
    }

    @Test
    fun `JMH importer rejects missing or aggregate only allocation evidence`() {
        val original = Files.readString(resourcePath("/metrics/jmh-gc.txt"))
        val missing = temporaryDirectory.resolve("missing.json")
        Files.writeString(
            missing,
            original.replace("\"gc.alloc.rate.norm\"", "\"missing.gc.alloc.rate.norm\""),
        )
        assertThrows<IllegalArgumentException> { import(missing) }

        val aggregateOnly = temporaryDirectory.resolve("aggregate-only.json")
        Files.writeString(
            aggregateOnly,
            original.replace(",\n        \"rawData\": [[100.25, 101.5]]", ""),
        )
        val failure = assertThrows<IllegalArgumentException> { import(aggregateOnly) }
        assertThat(failure).hasMessageThat().contains("rawData")
    }

    private fun import(path: Path): JmhGcAllocationImport =
        JmhGcResultImporter.import(path, "baseline", 1, TargetRole.BASELINE, 0)

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing test resource: $name" }.toURI())
}

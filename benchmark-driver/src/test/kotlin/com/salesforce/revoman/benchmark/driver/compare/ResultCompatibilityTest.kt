/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.RunMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResultCompatibilityTest {
    @Test
    fun `controlled paired campaign with treatment-specific adapters and classpaths is comparable`() {
        val result = ComparisonFixtures.lifecycleResult(RunMode.COLD)
        val manifest =
            ComparisonFixtures.manifest(
                RunMode.COLD,
                ComparisonFixtures.requiredLifecycleGates(RunMode.COLD),
            )

        assertThat(ResultCompatibility.errors(result, listOf(manifest))).isEmpty()
        assertThat(ResultCompatibility.requireComparable(result, listOf(manifest))).isSameInstanceAs(result)
    }

    @Test
    fun `java home paths are not comparison keys`() {
        val result = ComparisonFixtures.lifecycleResult(RunMode.COLD)
        val candidate = result.targets.last()
        val moved =
            result.copy(
                targets =
                    result.targets.dropLast(1) +
                        candidate.copy(buildJdk = candidate.buildJdk.copy(javaHome = "/other/jdk"))
            )

        assertThat(ResultCompatibility.errors(moved, manifestsFor(moved))).isEmpty()
    }

    @Test
    fun `manifest hashes are validated structurally without pretending to recompute absent bytes`() {
        val result = ComparisonFixtures.lifecycleResult(RunMode.COLD)
        val candidate = result.targets.last()
        val differentValidManifestHash =
            result.copy(
                targets =
                    result.targets.dropLast(1) + candidate.copy(manifestSha256 = "a".repeat(64))
            )
        val malformedManifestHash =
            result.copy(
                targets = result.targets.dropLast(1) + candidate.copy(manifestSha256 = "bad")
            )

        assertThat(ResultCompatibility.errors(differentValidManifestHash, manifestsFor(result)))
            .isEmpty()
        assertThat(ResultCompatibility.errors(malformedManifestHash, manifestsFor(result)))
            .isNotEmpty()
    }

    @Test
    fun `every compatibility identity is rejected independently`() {
        val valid = ComparisonFixtures.lifecycleResult(RunMode.COLD)
        val mutations: Map<String, (BenchmarkResultV1) -> BenchmarkResultV1> =
            linkedMapOf(
                "fixed baseline commit" to { result ->
                    result.withTarget(0) { copy(gitCommit = "other-baseline") }
                },
                "clean harness" to { result -> result.copy(harness = result.harness.copy(dirty = true)) },
                "clean baseline" to { result -> result.withTarget(0) { copy(dirty = true) } },
                "clean candidate" to { result -> result.withTarget(1) { copy(dirty = true) } },
                "harness commit" to { result -> result.copy(harness = result.harness.copy(commit = "")) },
                "harness tree" to { result -> result.copy(harness = result.harness.copy(tree = "")) },
                "harness artifact set" to {
                    result -> result.copy(harness = result.harness.copy(distributionSha256 = "0".repeat(64)))
                },
                "workload contract" to {
                    result -> result.copy(workloads = result.workloads.map { it.copy(contractSha256 = "0".repeat(64)) })
                },
                "fixture" to {
                    result -> result.copy(workloads = result.workloads.map { it.copy(fixtureSha256 = "0".repeat(64)) })
                },
                "classpath snapshot hash" to {
                    result -> result.withTarget(1) { copy(classpathSha256 = "0".repeat(64)) }
                },
                "Gradle version" to {
                    result -> result.withTarget(1) { copy(gradleVersion = "8.0") }
                },
                "wrapper hash" to {
                    result -> result.withTarget(1) { copy(wrapperSha256 = "0".repeat(64)) }
                },
                "JDK distribution" to {
                    result -> result.withTarget(1) { copy(buildJdk = buildJdk.copy(distribution = "Corretto")) }
                },
                "JDK full version" to {
                    result -> result.withTarget(1) { copy(buildJdk = buildJdk.copy(fullVersion = "21.0.9")) }
                },
                "JVM flags" to {
                    result -> result.withTarget(1) { copy(buildJdk = buildJdk.copy(jvmFlags = listOf("-Xmx2g"))) }
                },
                "host fingerprint" to {
                    result -> result.copy(environment = result.environment.copy(hostFingerprintSha256 = "bad"))
                },
                "host policy" to {
                    result -> result.copy(environment = result.environment.copy(policySha256 = null))
                },
                "adapter pinning" to {
                    result ->
                        result.copy(
                            harness = result.harness.copy(adapters = result.harness.adapters.dropLast(1))
                        )
                },
                "metric provider" to {
                    result -> result.withObservation(MetricId.LATENCY) { copy(provider = "other") }
                },
                "provider configuration" to {
                    result ->
                        ComparisonFixtures.withSeries(result, MetricId.LATENCY) { series ->
                            series.copy(providerConfigurationSha256 = "BAD")
                        }
                },
                "metric unit" to {
                    result ->
                        result.withObservation(MetricId.LATENCY) {
                            copy(unit = com.salesforce.revoman.benchmark.driver.model.MetricUnit.BYTES)
                        }
                },
                "pairing" to {
                    result ->
                        ComparisonFixtures.withSeries(result, MetricId.LATENCY) { series ->
                            series.copy(
                                blocks =
                                    requireNotNull(series.blocks).mapIndexed { index, block ->
                                        if (index == 0) block.copy(targetOrder = listOf("baseline", "baseline"))
                                        else block
                                    }
                            )
                        }
                },
            )

        mutations.forEach { (_, mutate) ->
            val changed = mutate(valid)
            assertThat(ResultCompatibility.errors(changed, manifestsFor(changed)))
                .isNotEmpty()
        }
    }

    @Test
    fun `require comparable rejects before statistics`() {
        val result = ComparisonFixtures.lifecycleResult(RunMode.COLD).withTarget(0) {
            copy(gitCommit = "not-the-fixed-baseline")
        }

        val failure = assertThrows<IllegalArgumentException> {
            ResultCompatibility.requireComparable(result, manifestsFor(result))
        }

        assertThat(failure).hasMessageThat().contains("baseline commit")
    }

    private fun manifestsFor(result: BenchmarkResultV1) =
        listOf(
            ComparisonFixtures.manifest(
                result.configuration.mode,
                ComparisonFixtures.requiredLifecycleGates(result.configuration.mode),
            )
        )

    private fun BenchmarkResultV1.withTarget(
        index: Int,
        transform: com.salesforce.revoman.benchmark.driver.model.TargetIdentity.() ->
            com.salesforce.revoman.benchmark.driver.model.TargetIdentity,
    ): BenchmarkResultV1 = copy(targets = targets.mapIndexed { targetIndex, target -> if (targetIndex == index) target.transform() else target })

    private fun BenchmarkResultV1.withObservation(
        metric: MetricId,
        transform: com.salesforce.revoman.benchmark.driver.model.MetricObservation.() ->
            com.salesforce.revoman.benchmark.driver.model.MetricObservation,
    ): BenchmarkResultV1 =
        ComparisonFixtures.withSeries(this, metric) { series ->
            series.copy(
                blocks =
                    requireNotNull(series.blocks).mapIndexed { blockIndex, block ->
                        if (blockIndex == 0) {
                            block.copy(
                                observations =
                                    block.observations.mapIndexed { observationIndex, observation ->
                                        if (observationIndex == 0) observation.transform() else observation
                                    }
                            )
                        } else {
                            block
                        }
                    }
            )
        }
}

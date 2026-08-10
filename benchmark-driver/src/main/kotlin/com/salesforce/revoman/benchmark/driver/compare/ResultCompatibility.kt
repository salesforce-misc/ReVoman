/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricSeries
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest

/** Performs every metadata and pairing check before a statistic is calculated. */
object ResultCompatibility {
    const val FIXED_BASELINE_COMMIT: String = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"

    /** Returns all deterministic incompatibilities without opening a checkout or artifact path. */
    fun errors(
        result: BenchmarkResultV1,
        workloadManifests: List<WorkloadManifest> = emptyList(),
    ): List<String> {
        val structuralFailure = runCatching(result::validate).exceptionOrNull()
        if (structuralFailure != null) {
            return listOf("benchmark result is structurally invalid: ${structuralFailure.message}")
        }
        val manifestFailures =
            workloadManifests.mapNotNull { manifest ->
                runCatching(manifest::validate).exceptionOrNull()?.let { failure ->
                    "workload manifest ${manifest.id} is structurally invalid: ${failure.message}"
                }
            }
        if (manifestFailures.isNotEmpty()) return manifestFailures.sorted()

        val assignments = result.configuration.targets.associateBy { it.role }
        val targets = result.targets.associateBy(TargetIdentity::id)
        val baseline = targets.getValue(assignments.getValue(TargetRole.BASELINE).targetId)
        val candidate = targets.getValue(assignments.getValue(TargetRole.CANDIDATE).targetId)
        val manifestsById = workloadManifests.associateBy(WorkloadManifest::id)

        return buildList {
                if (baseline.gitCommit != FIXED_BASELINE_COMMIT) {
                    add(
                        "baseline commit must be $FIXED_BASELINE_COMMIT, " +
                            "actual=${baseline.gitCommit}"
                    )
                }
                if (result.intent == RunIntent.CONTROLLED) {
                    if (result.harness.dirty) add("controlled comparison requires a clean harness")
                    result.targets.filter(TargetIdentity::dirty).forEach { target ->
                        add("controlled comparison requires clean target ${target.id}")
                    }
                    if (result.environment.policySha256 == null) {
                        add("controlled comparison requires a host policy identity")
                    }
                }
                if (result.harness.artifacts.isEmpty()) {
                    add("comparison harness artifact snapshot must not be empty")
                } else if (
                    ContentHasher.artifactSetSha256(result.harness.artifacts) !=
                        result.harness.distributionSha256
                ) {
                    add("harness distributionSha256 does not match its ordered artifact snapshot")
                }
                compareBuildInputs(baseline, candidate).forEach(::add)
                compareRuntimeJdk(result.environment.jdk, baseline.buildJdk).forEach(::add)
                compareRuntimeJdk(result.environment.jdk, candidate.buildJdk).forEach(::add)

                if (manifestsById.size != workloadManifests.size) {
                    add("workload manifest IDs must be unique")
                }
                if (
                    manifestsById.keys != result.workloads.map { it.id }.toSet() ||
                        workloadManifests.size != result.workloads.size
                ) {
                    add("workload manifests must identify every result workload exactly once")
                }
                result.workloads.forEach { workload ->
                    if (workload.contractSha256 != result.harness.workloadContractSha256) {
                        add(
                            "workload ${workload.id} contract hash does not match the harness " +
                                "workload contract identity"
                        )
                    }
                    manifestsById[workload.id]?.let { manifest ->
                        if (workload.fixtureSha256 != manifest.fixtureTreeSha256) {
                            add("workload ${workload.id} fixture hash does not match its manifest")
                        }
                    }
                    compareMetricSeries(workload.mode, workload.metricSeries).forEach {
                        add("${workload.id}: $it")
                    }
                }
            }
            .distinct()
            .sorted()
    }

    /** Returns [result] or rejects the complete incompatibility list before any statistics run. */
    fun requireComparable(
        result: BenchmarkResultV1,
        workloadManifests: List<WorkloadManifest> = emptyList(),
    ): BenchmarkResultV1 =
        result.apply {
            val errors = errors(result, workloadManifests)
            require(errors.isEmpty()) { "Benchmark result is incompatible: ${errors.joinToString("; ")}" }
        }

    private fun compareBuildInputs(
        baseline: TargetIdentity,
        candidate: TargetIdentity,
    ): List<String> =
        buildList {
            if (baseline.gradleVersion != candidate.gradleVersion) {
                add("target Gradle versions must match across roles")
            }
            if (baseline.wrapperSha256 != candidate.wrapperSha256) {
                add("target wrapper hashes must match across roles")
            }
            compareJdkKeys(baseline.buildJdk, candidate.buildJdk, "target build JDK").forEach(::add)
        }

    private fun compareRuntimeJdk(runtime: JdkIdentity, build: JdkIdentity): List<String> =
        compareJdkKeys(runtime, build, "runtime and target build JDK", compareFlags = false)

    private fun compareJdkKeys(
        left: JdkIdentity,
        right: JdkIdentity,
        label: String,
        compareFlags: Boolean = true,
    ): List<String> =
        buildList {
            if (left.distribution != right.distribution) add("$label distributions must match")
            if (left.vendor != right.vendor) add("$label vendors must match")
            if (left.fullVersion != right.fullVersion) add("$label full versions must match")
            if (compareFlags && left.jvmFlags != right.jvmFlags) add("$label JVM flags must match")
        }

    private fun compareMetricSeries(
        mode: RunMode,
        series: List<MetricSeries>,
    ): List<String> =
        buildList {
            if (series.map(MetricSeries::metric).distinct().size != series.size) {
                add("metric series IDs must be unique")
            }
            series.forEach { metricSeries ->
                if (metricSeries.unit != expectedUnit(mode, metricSeries.metric)) {
                    add("${metricSeries.metric} uses incompatible unit ${metricSeries.unit}")
                }
            }
        }

    private fun expectedUnit(
        mode: RunMode,
        metric: MetricId,
    ): MetricUnit =
        when (metric) {
            MetricId.LATENCY -> MetricUnit.NANOSECONDS
            MetricId.ALLOCATED_BYTES ->
                when (mode) {
                    RunMode.WARM -> MetricUnit.BYTES_PER_OPERATION
                    RunMode.COLD, RunMode.RETAINED -> MetricUnit.BYTES
                }
            MetricId.PEAK_RSS,
            MetricId.RETAINED_BYTES,
            MetricId.BYTES_PER_STEP,
            -> MetricUnit.BYTES
        }
}

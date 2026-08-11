/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.salesforce.revoman.benchmark.driver.model.AdapterIdentity
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.ArtifactSnapshot
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.CampaignConfiguration
import com.salesforce.revoman.benchmark.driver.model.EnvironmentIdentity
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.HarnessIdentity
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.MetricSeries
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.PowerEvidence
import com.salesforce.revoman.benchmark.driver.model.RetainedEvidence
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetAssignment
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.model.WorkloadResult

internal object ComparisonFixtures {
    const val BASELINE_ID: String = "baseline"
    const val CANDIDATE_ID: String = "candidate"
    const val WORKLOAD_ID: String = "fixture-workload.v1"
    const val BASELINE_COMMIT: String = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
    const val HARNESS_ARTIFACT_SET_SHA256: String =
        "c668a7b99ec517a18ba6d18c18218c3a327682a10e4e7084214af7730b6fd849"
    const val BASELINE_CLASSPATH_SHA256: String =
        "5a2b3c851e605c3eda37a4e016e75d31b57357507a9b43c0b224382e6769400c"
    const val CANDIDATE_CLASSPATH_SHA256: String =
        "8b559a99eef562400e6c671090e9c40c81d6c27099127f1962adc4adcda9f936"

    fun lifecycleResult(
        mode: RunMode,
        intent: RunIntent = RunIntent.CONTROLLED,
        ratios: Map<MetricId, Double> = emptyMap(),
        acceptedBlocks: Int = when (mode) {
            RunMode.COLD -> 50
            RunMode.WARM, RunMode.RETAINED -> 5
        },
    ): BenchmarkResultV1 {
        require(mode != RunMode.RETAINED)
        val metrics =
            when (mode) {
                RunMode.COLD ->
                    listOf(MetricId.LATENCY, MetricId.ALLOCATED_BYTES, MetricId.PEAK_RSS)
                RunMode.WARM -> listOf(MetricId.LATENCY, MetricId.ALLOCATED_BYTES)
                RunMode.RETAINED -> error("retained handled separately")
            }
        return result(
            mode = mode,
            intent = intent,
            acceptedBlocks = acceptedBlocks,
            series = metrics.map { metric -> ratioSeries(mode, metric, ratios[metric] ?: 1.0, acceptedBlocks) },
        )
    }

    fun retainedResult(
        candidateSlope: Double,
        candidateWeakTypes: List<String> = listOf("ExecutionSession", "KickExecution"),
        candidateCleared: Boolean = true,
        acceptedBlocks: Int = 5,
    ): BenchmarkResultV1 =
        result(
            mode = RunMode.RETAINED,
            acceptedBlocks = acceptedBlocks,
            series =
                listOf(
                    MetricSeries(
                        metric = MetricId.RETAINED_BYTES,
                        provider = "retained-provider/v1",
                        providerConfigurationSha256 = "7".repeat(64),
                        unit = MetricUnit.BYTES,
                        blocks =
                            List(acceptedBlocks) { blockId ->
                                acceptedBlock(
                                    blockId,
                                    retainedObservations(
                                        blockId = blockId,
                                        targetId = BASELINE_ID,
                                        slope = 0.0,
                                        weakTypes = listOf("FakeExecutionToken"),
                                        cleared = true,
                                    ) +
                                        retainedObservations(
                                            blockId = blockId,
                                            targetId = CANDIDATE_ID,
                                            slope = candidateSlope,
                                            weakTypes = candidateWeakTypes,
                                            cleared = candidateCleared,
                                        ),
                                )
                            },
                    )
                ),
        )

    fun perStepResult(
        normalizedCandidateBytes: List<Double>,
        counts: List<Int> = listOf(800, 1_600, 3_200),
        acceptedBlocks: Int = 5,
    ): BenchmarkResultV1 =
        result(
            mode = RunMode.RETAINED,
            acceptedBlocks = acceptedBlocks,
            series =
                listOf(
                    MetricSeries(
                        metric = MetricId.BYTES_PER_STEP,
                        provider = "report-allocation/v1",
                        providerConfigurationSha256 = "7".repeat(64),
                        unit = MetricUnit.BYTES,
                        blocks =
                            List(acceptedBlocks) { blockId ->
                                acceptedBlock(
                                    blockId,
                                    perStepObservations(blockId, BASELINE_ID, counts, listOf(100.0, 100.0, 100.0)) +
                                        perStepObservations(
                                            blockId,
                                            CANDIDATE_ID,
                                            counts,
                                            normalizedCandidateBytes,
                                        ),
                                )
                            },
                    )
                ),
        )

    fun manifest(
        mode: RunMode,
        requiredGates: List<GateId>,
        id: String = WORKLOAD_ID,
    ): WorkloadManifest =
        WorkloadManifest(
            id = id,
            contractVersion = 1,
            files =
                listOf(
                    HashedArtifact(
                        logicalId = "fixture.json",
                        executionPath = "fixture.json",
                        sizeBytes = 2,
                        sha256 = "6".repeat(64),
                    )
                ),
            fixtureTreeSha256 = "c".repeat(64),
            operationIds = listOf(id),
            requiredGatesByMode =
                RunMode.entries.associateWith { candidateMode ->
                    if (candidateMode == mode) requiredGates.sortedBy(Enum<*>::ordinal) else emptyList()
                },
            expectedDigest = ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0),
        )

    fun requiredLifecycleGates(mode: RunMode): List<GateId> =
        when (mode) {
            RunMode.COLD ->
                listOf(
                    GateId.COLD_MEDIAN,
                    GateId.COLD_P95,
                    GateId.COLD_ALLOCATION,
                    GateId.COLD_PEAK_RSS,
                )
            RunMode.WARM ->
                listOf(GateId.WARM_MEDIAN, GateId.WARM_P95, GateId.WARM_ALLOCATION)
            RunMode.RETAINED -> emptyList()
        }

    fun withSeries(
        result: BenchmarkResultV1,
        metric: MetricId,
        transform: (MetricSeries) -> MetricSeries,
    ): BenchmarkResultV1 =
        result.copy(
            workloads =
                result.workloads.map { workload ->
                    workload.copy(
                        metricSeries =
                            workload.metricSeries.map { series ->
                                if (series.metric == metric) transform(series) else series
                            }
                    )
                }
        )

    private fun result(
        mode: RunMode,
        intent: RunIntent = RunIntent.CONTROLLED,
        acceptedBlocks: Int,
        series: List<MetricSeries>,
    ): BenchmarkResultV1 {
        val baselineAdapter = AdapterIdentity("baseline-83f3cd70", "d".repeat(64))
        val candidateAdapter = AdapterIdentity("major-v1", "e".repeat(64))
        val jdk =
            JdkIdentity(
                distribution = "Temurin",
                vendor = "Eclipse Adoptium",
                fullVersion = "21.0.8+9-LTS",
                javaHome = "/runtime/jdk",
                jvmFlags = listOf("-Xms1g", "-Xmx1g"),
            )
        return BenchmarkResultV1(
            campaignId = "campaign-${mode.name.lowercase()}",
            intent = intent,
            createdAt = "2026-08-10T00:00:00Z",
            configuration =
                CampaignConfiguration(
                    mode = mode,
                    targets =
                        listOf(
                            TargetAssignment(TargetRole.BASELINE, BASELINE_ID, baselineAdapter.id),
                            TargetAssignment(TargetRole.CANDIDATE, CANDIDATE_ID, candidateAdapter.id),
                        ),
                    metricPasses =
                        series
                            .map { metricSeries -> metricPass(metricSeries.metric) }
                            .distinct()
                            .sortedBy(Enum<*>::ordinal),
                    seed = 0x5245564F4D414E31L,
                    requestedAcceptedBlocks = acceptedBlocks,
                    forksPerBlock = 1,
                    warmupIterations = if (mode == RunMode.WARM) 3 else 0,
                    measurementIterations = if (mode == RunMode.RETAINED) 0 else 1,
                ),
            harness =
                HarnessIdentity(
                    commit = "harness-commit",
                    tree = "harness-tree",
                    dirty = false,
                    distributionSha256 = HARNESS_ARTIFACT_SET_SHA256,
                    artifacts =
                        listOf(
                            HashedArtifact(
                                logicalId = "driver.jar",
                                executionPath = "/ignored/install/driver.jar",
                                sizeBytes = 300,
                                sha256 = "3".repeat(64),
                            )
                        ),
                    workloadContractSha256 = "b".repeat(64),
                    fixtureSetSha256 = "a".repeat(64),
                    adapters = listOf(baselineAdapter, candidateAdapter),
                ),
            environment =
                EnvironmentIdentity(
                    jdk = jdk,
                    osName = "Linux",
                    osVersion = "6",
                    kernel = "6.8.0",
                    cpuModel = "Benchmark CPU",
                    cpuCount = 8,
                    governor = "performance",
                    physicalMemoryBytes = 17_179_869_184,
                    hostFingerprintSha256 = "8".repeat(64),
                    policySha256 = "9".repeat(64),
                ),
            targets =
                listOf(
                    target(
                        id = BASELINE_ID,
                        commit = BASELINE_COMMIT,
                        javaHome = "/baseline/jdk",
                        adapter = baselineAdapter,
                        classpath =
                            listOf(ArtifactSnapshot("baseline.jar", 100, "1".repeat(64))),
                        classpathSha256 = BASELINE_CLASSPATH_SHA256,
                        manifestSha256 = "1".repeat(64),
                    ),
                    target(
                        id = CANDIDATE_ID,
                        commit = "candidate-commit",
                        javaHome = "/candidate/jdk",
                        adapter = candidateAdapter,
                        classpath =
                            listOf(ArtifactSnapshot("candidate.jar", 200, "2".repeat(64))),
                        classpathSha256 = CANDIDATE_CLASSPATH_SHA256,
                        manifestSha256 = "2".repeat(64),
                    ),
                ),
            workloads =
                listOf(
                    WorkloadResult(
                        id = WORKLOAD_ID,
                        contractSha256 = "b".repeat(64),
                        fixtureSha256 = "c".repeat(64),
                        mode = mode,
                        metricSeries = series,
                    )
                ),
        )
    }

    private fun target(
        id: String,
        commit: String,
        javaHome: String,
        adapter: AdapterIdentity,
        classpath: List<ArtifactSnapshot>,
        classpathSha256: String,
        manifestSha256: String,
    ): TargetIdentity =
        TargetIdentity(
            id = id,
            gitCommit = commit,
            gitTree = "$id-tree",
            dirty = false,
            gradleVersion = "9.0.0",
            wrapperSha256 = "f".repeat(64),
            buildJdk =
                JdkIdentity(
                    distribution = "Temurin",
                    vendor = "Eclipse Adoptium",
                    fullVersion = "21.0.8+9-LTS",
                    javaHome = javaHome,
                    jvmFlags = listOf("-Xms1g", "-Xmx1g"),
                ),
            manifestSha256 = manifestSha256,
            classpathSha256 = classpathSha256,
            classpath = classpath,
            adapter = adapter,
        )

    private fun ratioSeries(
        mode: RunMode,
        metric: MetricId,
        ratio: Double,
        acceptedBlocks: Int,
    ): MetricSeries {
        val unit =
            when {
                metric == MetricId.LATENCY -> MetricUnit.NANOSECONDS
                mode == RunMode.WARM && metric == MetricId.ALLOCATED_BYTES ->
                    MetricUnit.BYTES_PER_OPERATION
                else -> MetricUnit.BYTES
            }
        val provider = "${mode.name.lowercase()}-${metric.name.lowercase()}-provider/v1"
        return MetricSeries(
            metric = metric,
            provider = provider,
            providerConfigurationSha256 = "7".repeat(64),
            unit = unit,
            blocks =
                List(acceptedBlocks) { blockId ->
                    acceptedBlock(
                        blockId,
                        observations =
                            listOf(
                                observation(blockId, BASELINE_ID, metric, provider, unit, 100.0),
                                observation(
                                    blockId,
                                    CANDIDATE_ID,
                                    metric,
                                    provider,
                                    unit,
                                    100.0 * ratio,
                                ),
                            ),
                    )
                },
        )
    }

    private fun observation(
        blockId: Int,
        targetId: String,
        metric: MetricId,
        provider: String,
        unit: MetricUnit,
        value: Double,
    ): MetricObservation =
        MetricObservation(
            targetId = targetId,
            metric = metric,
            provider = provider,
            unit = unit,
            fork = 0,
            iteration = 0,
            processId = processId(blockId, targetId, 0),
            value = value,
        )

    private fun retainedObservations(
        blockId: Int,
        targetId: String,
        slope: Double,
        weakTypes: List<String>,
        cleared: Boolean,
    ): List<MetricObservation> =
        listOf(1_000, 2_000, 4_000).mapIndexed { iteration, executionCount ->
            MetricObservation(
                targetId = targetId,
                metric = MetricId.RETAINED_BYTES,
                provider = "retained-provider/v1",
                unit = MetricUnit.BYTES,
                fork = 0,
                iteration = iteration,
                replicateGroup = blockId,
                processId = processId(blockId, targetId, iteration),
                value = 10_000.0 + slope * executionCount,
                retainedEvidence =
                    RetainedEvidence(
                        executionCount = executionCount,
                        completedGcCycles = 2,
                        weakReferences =
                            weakTypes.map { type ->
                                WeakReferenceOutcome(type, created = 10, cleared = if (cleared) 10 else 9)
                            },
                    ),
            )
        }

    private fun perStepObservations(
        blockId: Int,
        targetId: String,
        counts: List<Int>,
        normalizedBytes: List<Double>,
    ): List<MetricObservation> =
        counts.zip(normalizedBytes).mapIndexed { iteration, (executionCount, normalized) ->
            MetricObservation(
                targetId = targetId,
                metric = MetricId.BYTES_PER_STEP,
                provider = "report-allocation/v1",
                unit = MetricUnit.BYTES,
                fork = 0,
                iteration = iteration,
                processId = processId(blockId, targetId, iteration),
                value = executionCount * normalized,
                executionCount = executionCount,
            )
        }

    private fun acceptedBlock(blockId: Int, observations: List<MetricObservation>): AlternatingBlock =
        AlternatingBlock(
            blockId = blockId,
            targetOrder =
                if (blockId % 2 == 0) listOf(BASELINE_ID, CANDIDATE_ID)
                else listOf(CANDIDATE_ID, BASELINE_ID),
            healthBefore = health(blockId * 10L),
            healthDuring = listOf(health(blockId * 10L + 1)),
            healthAfter = health(blockId * 10L + 2),
            accepted = true,
            rejectionReasons = emptyList(),
            observations = observations,
        )

    private fun health(capturedAtNanos: Long): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = capturedAtNanos,
            loadAverage = 0.1,
            cpuBusyFraction = 0.1,
            availableMemoryBytes = 8_000_000_000,
            swapUsedBytes = 0,
            thermalValue = 30.0,
            powerEvidence = PowerEvidence.EXTERNAL_POWER_ONLINE,
            governors = listOf("performance"),
        )

    private fun processId(blockId: Int, targetId: String, iteration: Int): Long =
        1_000L + blockId * 10L + (if (targetId == BASELINE_ID) 0 else 5) + iteration

    private fun metricPass(metric: MetricId): MetricPass =
        when (metric) {
            MetricId.LATENCY -> MetricPass.LATENCY
            MetricId.ALLOCATED_BYTES, MetricId.BYTES_PER_STEP -> MetricPass.ALLOCATION
            MetricId.PEAK_RSS -> MetricPass.PEAK_RSS
            MetricId.RETAINED_BYTES -> MetricPass.RETAINED
        }
}

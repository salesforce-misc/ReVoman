/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.squareup.moshi.JsonClass

/** Declares why a benchmark campaign was run. */
enum class RunIntent {
    CONTROLLED,
    SMOKE,
    CI_SELF_TEST,
}

/** Gives each paired target its comparison role. */
enum class TargetRole {
    BASELINE,
    CANDIDATE,
}

/** Identifies a measured quantity independently of its provider. */
enum class MetricId {
    LATENCY,
    ALLOCATED_BYTES,
    PEAK_RSS,
    RETAINED_BYTES,
    BYTES_PER_STEP,
}

/** Records the exact unit emitted by a metric provider. */
enum class MetricUnit {
    NANOSECONDS,
    NANOSECONDS_PER_OPERATION,
    BYTES,
    BYTES_PER_EXECUTION,
    BYTES_PER_OPERATION,
}

/** Identifies one comparison gate that a workload can require. */
enum class GateId {
    COLD_MEDIAN,
    COLD_P95,
    COLD_ALLOCATION,
    COLD_PEAK_RSS,
    WARM_MEDIAN,
    WARM_P95,
    WARM_ALLOCATION,
    RETAINED_SLOPE,
    PER_STEP_ALLOCATION_SPREAD,
}

/** One complete, paired benchmark campaign and its immutable identities. */
@JsonClass(generateAdapter = true)
data class BenchmarkResultV1(
    val schema: String = RESULT_SCHEMA_V1,
    val campaignId: String,
    val intent: RunIntent,
    val createdAt: String,
    val configuration: CampaignConfiguration,
    val harness: HarnessIdentity,
    val environment: EnvironmentIdentity,
    val targets: List<TargetIdentity>,
    val workloads: List<WorkloadResult>,
) {
    /** Rejects structurally valid documents whose identities or measurements are inconsistent. */
    fun validate(): BenchmarkResultV1 = apply {
        require(schema == RESULT_SCHEMA_V1) { "Unsupported benchmark result schema: $schema" }
        requireNonBlank("campaignId", campaignId)
        requireNonBlank("createdAt", createdAt)
        configuration.validate()
        harness.validate()
        environment.validate()
        require(targets.size == TargetRole.entries.size) {
            "paired campaign must contain exactly ${TargetRole.entries.size} target identities"
        }
        require(workloads.isNotEmpty()) { "workloads must not be empty" }
        targets.forEachIndexed { index, target -> target.validate("targets[$index]") }

        val workloadIds = workloads.map(WorkloadResult::id)
        require(workloadIds.distinct().size == workloadIds.size) {
            "workload IDs must be unique"
        }

        val targetIds = targets.map(TargetIdentity::id)
        require(targetIds.distinct().size == targetIds.size) { "target identity IDs must be unique" }
        val assignmentsByTarget = configuration.targets.associateBy(TargetAssignment::targetId)
        require(assignmentsByTarget.keys == targetIds.toSet()) {
            "configuration target IDs must match target identities: " +
                "expected=${targetIds.toSet()}, actual=${assignmentsByTarget.keys}"
        }
        val adaptersById = harness.adapters.associateBy(AdapterIdentity::id)
        targets.forEach { target ->
            val assignment = requireNotNull(assignmentsByTarget[target.id])
            require(assignment.adapterId == target.adapter.id) {
                "adapterId for ${target.id} must match target adapter identity: " +
                    "expected=${target.adapter.id}, actual=${assignment.adapterId}"
            }
            require(adaptersById[target.adapter.id] == target.adapter) {
                "adapter identity for ${target.id} must match harness adapter ${target.adapter.id}"
            }
        }

        workloads.forEachIndexed { index, workload ->
            workload.validate(
                path = "workloads[$index]",
                intent = intent,
                configuration = configuration,
                targetIds = targetIds,
            )
        }
    }

    internal fun canonicalized(): BenchmarkResultV1 =
        copy(configuration = configuration.canonicalized())
}

/** Binds a comparison role to one target and one adapter. */
@JsonClass(generateAdapter = true)
data class TargetAssignment(
    val role: TargetRole,
    val targetId: String,
    val adapterId: String,
)

/** Captures all deterministic choices shared by paired executions. */
@JsonClass(generateAdapter = true)
data class CampaignConfiguration(
    val mode: RunMode,
    val targets: List<TargetAssignment>,
    val metricPasses: List<MetricPass>,
    val seed: Long,
    val requestedAcceptedBlocks: Int,
    val forksPerBlock: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
) {
    internal fun validate() {
        require(targets.size == TargetRole.entries.size) {
            "paired campaign must configure exactly ${TargetRole.entries.size} targets"
        }
        require(targets.map(TargetAssignment::role).toSet() == TargetRole.entries.toSet()) {
            "paired campaign requires exactly one BASELINE and one CANDIDATE assignment"
        }
        require(targets.map(TargetAssignment::targetId).distinct().size == targets.size) {
            "configured target IDs must be unique"
        }
        targets.forEachIndexed { index, target ->
            requireNonBlank("configuration.targets[$index].targetId", target.targetId)
            requireNonBlank("configuration.targets[$index].adapterId", target.adapterId)
        }
        requireCanonicalEnumOrder("configuration.metricPasses", metricPasses)
        require(metricPasses.isNotEmpty()) { "configuration.metricPasses must not be empty" }
        require(requestedAcceptedBlocks > 0) { "requestedAcceptedBlocks must be positive" }
        require(forksPerBlock > 0) { "forksPerBlock must be positive" }
        require(warmupIterations >= 0) { "warmupIterations must not be negative" }
        when (mode) {
            RunMode.COLD,
            RunMode.WARM,
            -> require(measurementIterations > 0) {
                "$mode measurementIterations must be positive"
            }
            RunMode.RETAINED -> require(measurementIterations == 0) {
                "RETAINED measurementIterations must be zero"
            }
        }
    }

    internal fun canonicalized(): CampaignConfiguration =
        copy(metricPasses = metricPasses.distinct().sortedBy(Enum<*>::ordinal))
}

/** Preserves one host-health-bracketed alternating block. */
@JsonClass(generateAdapter = true)
data class AlternatingBlock(
    val blockId: Int,
    val targetOrder: List<String>,
    val healthBefore: HostHealthSnapshot,
    val healthDuring: List<HostHealthSnapshot>,
    val healthAfter: HostHealthSnapshot,
    val accepted: Boolean,
    val rejectionReasons: List<String>,
    val observations: List<MetricObservation>,
)

/** Records one provider observation with its exact target, fork, and iteration coordinates. */
@JsonClass(generateAdapter = true)
data class MetricObservation(
    val targetId: String,
    val metric: MetricId,
    val provider: String,
    val unit: MetricUnit,
    val fork: Int,
    val iteration: Int,
    val replicateGroup: Int? = null,
    val processId: Long,
    val value: Double,
    val executionCount: Int? = null,
    val retainedEvidence: RetainedEvidence? = null,
)

internal data class IntrinsicObservationCoordinate(
    val metric: MetricId,
    val provider: String,
    val unit: MetricUnit,
    val fork: Int,
    val iteration: Int,
    val replicateGroup: Int?,
)

internal fun MetricObservation.intrinsicCoordinate(): IntrinsicObservationCoordinate =
    IntrinsicObservationCoordinate(metric, provider, unit, fork, iteration, replicateGroup)

/** Validates fields that do not require a parent metric series or campaign configuration. */
internal fun MetricObservation.validateIntrinsic(path: String) {
    requireNonBlank("$path.targetId", targetId)
    requireNonBlank("$path.provider", provider)
    require(fork >= 0) { "$path.fork must not be negative" }
    require(iteration >= 0) { "$path.iteration must not be negative" }
    require(processId > 0) { "$path.processId must be positive" }
    requireFiniteNonNegative("$path.value", value)
    if (unit in BYTE_UNITS) {
        require(value % 1.0 == 0.0) { "$path.value must be mathematically integral for $unit" }
    }
    replicateGroup?.let { require(it >= 0) { "$path.replicateGroup must not be negative" } }
    val perStepMetric = metric == MetricId.BYTES_PER_STEP
    require(perStepMetric || executionCount == null) {
        "$path.executionCount is allowed only for BYTES_PER_STEP"
    }
    require(!perStepMetric || (executionCount ?: 0) > 0) {
        "$path.executionCount is required and must be positive for BYTES_PER_STEP"
    }
    val retainedMetric = metric == MetricId.RETAINED_BYTES
    require(retainedMetric || replicateGroup == null) {
        "$path.replicateGroup is allowed only for RETAINED_BYTES"
    }
    require(!retainedMetric || replicateGroup != null) {
        "$path.replicateGroup is required for RETAINED_BYTES"
    }
    require(retainedMetric || retainedEvidence == null) {
        "$path.retainedEvidence is allowed only for RETAINED_BYTES"
    }
    require(!retainedMetric || retainedEvidence != null) {
        "$path.retainedEvidence is required for RETAINED_BYTES"
    }
    retainedEvidence?.validate("$path.retainedEvidence")
}

/** Captures retained-mode execution and garbage-collection evidence. */
@JsonClass(generateAdapter = true)
data class RetainedEvidence(
    val executionCount: Int,
    val completedGcCycles: Int,
    val weakReferences: List<WeakReferenceOutcome>,
)

/** Summarizes weak-reference outcomes for one retained object type. */
@JsonClass(generateAdapter = true)
data class WeakReferenceOutcome(
    val type: String,
    val created: Int,
    val cleared: Int,
)

/** Identifies the harness, fixtures, contracts, and adapters that produced a campaign. */
@JsonClass(generateAdapter = true)
data class HarnessIdentity(
    val commit: String,
    val tree: String,
    val dirty: Boolean,
    val distributionSha256: String,
    val artifacts: List<HashedArtifact>,
    val workloadContractSha256: String,
    val fixtureSetSha256: String,
    val adapters: List<AdapterIdentity>,
) {
    internal fun validate() {
        requireNonBlank("harness.commit", commit)
        requireNonBlank("harness.tree", tree)
        requireSha256("harness.distributionSha256", distributionSha256)
        requireSha256("harness.workloadContractSha256", workloadContractSha256)
        requireSha256("harness.fixtureSetSha256", fixtureSetSha256)
        validateArtifacts("harness.artifacts", artifacts)
        require(ContentHasher.artifactSetSha256(artifacts) == distributionSha256) {
            "harness.distributionSha256 must match its ordered artifact snapshot"
        }
        require(adapters.isNotEmpty()) { "harness.adapters must not be empty" }
        require(adapters.map(AdapterIdentity::id).distinct().size == adapters.size) {
            "harness adapter IDs must be unique"
        }
        adapters.forEachIndexed { index, adapter -> adapter.validate("harness.adapters[$index]") }
    }
}

/** Captures the host and runtime facts needed to reproduce a campaign. */
@JsonClass(generateAdapter = true)
data class EnvironmentIdentity(
    val jdk: JdkIdentity,
    val osName: String,
    val osVersion: String,
    val kernel: String,
    val cpuModel: String,
    val cpuCount: Int,
    val governor: String,
    val physicalMemoryBytes: Long,
    val hostFingerprintSha256: String,
    val policySha256: String?,
) {
    internal fun validate() {
        jdk.validate("environment.jdk")
        requireNonBlank("environment.osName", osName)
        requireNonBlank("environment.osVersion", osVersion)
        requireNonBlank("environment.kernel", kernel)
        requireNonBlank("environment.cpuModel", cpuModel)
        require(cpuCount > 0) { "environment.cpuCount must be positive" }
        requireNonBlank("environment.governor", governor)
        require(physicalMemoryBytes > 0) { "environment.physicalMemoryBytes must be positive" }
        requireSha256("environment.hostFingerprintSha256", hostFingerprintSha256)
        policySha256?.let { requireSha256("environment.policySha256", it) }
    }
}

/** Identifies one locally built comparison target without exposing its checkout path. */
@JsonClass(generateAdapter = true)
data class TargetIdentity(
    val id: String,
    val gitCommit: String,
    val gitTree: String,
    val dirty: Boolean,
    val gradleVersion: String,
    val wrapperSha256: String,
    val buildJdk: JdkIdentity,
    val manifestSha256: String,
    val classpathSha256: String,
    val classpath: List<ArtifactSnapshot>,
    val adapter: AdapterIdentity,
) {
    internal fun validate(path: String) {
        requireNonBlank("$path.id", id)
        requireNonBlank("$path.gitCommit", gitCommit)
        requireNonBlank("$path.gitTree", gitTree)
        requireNonBlank("$path.gradleVersion", gradleVersion)
        requireSha256("$path.wrapperSha256", wrapperSha256)
        buildJdk.validate("$path.buildJdk")
        requireSha256("$path.manifestSha256", manifestSha256)
        requireSha256("$path.classpathSha256", classpathSha256)
        require(classpath.isNotEmpty()) { "$path.classpath must not be empty" }
        validateArtifactSnapshots("$path.classpath", classpath)
        require(ContentHasher.artifactSnapshotSetSha256(classpath) == classpathSha256) {
            "$path.classpathSha256 must match the ordered path-free classpath snapshot"
        }
        adapter.validate("$path.adapter")
    }
}

/** Captures one host-health sample around or during a measurement block. */
@JsonClass(generateAdapter = true)
data class HostHealthSnapshot(
    val capturedAtNanos: Long,
    val loadAverage: Double,
    val cpuBusyFraction: Double,
    val availableMemoryBytes: Long,
    val swapUsedBytes: Long,
    val thermalValue: Double,
    val onAcPower: Boolean,
    val governors: List<String>,
) {
    internal fun validate(path: String) {
        require(capturedAtNanos >= 0) { "$path.capturedAtNanos must not be negative" }
        requireFiniteNonNegative("$path.loadAverage", loadAverage)
        requireFiniteNonNegative("$path.cpuBusyFraction", cpuBusyFraction)
        require(cpuBusyFraction <= 1.0) { "$path.cpuBusyFraction must not exceed one" }
        require(availableMemoryBytes >= 0) { "$path.availableMemoryBytes must not be negative" }
        require(swapUsedBytes >= 0) { "$path.swapUsedBytes must not be negative" }
        requireFiniteNonNegative("$path.thermalValue", thermalValue)
        require(governors.none(String::isBlank)) { "$path.governors must not contain blanks" }
    }
}

/**
 * Validates one serialized block health timeline. Timestamps are nondecreasing because multiple
 * samples may come from a deterministic or coarse monotonic clock tick.
 */
internal fun validateHostHealthTimeline(
    before: HostHealthSnapshot,
    during: List<HostHealthSnapshot>,
    after: HostHealthSnapshot,
    path: String,
) {
    require(during.isNotEmpty()) { "$path.healthDuring must not be empty" }
    val samples = listOf(before) + during + after
    samples.forEachIndexed { index, sample -> sample.validate("$path.health[$index]") }
    require(
        samples.zipWithNext().all { (left, right) ->
            left.capturedAtNanos <= right.capturedAtNanos
        }
    ) {
        "$path health capturedAtNanos values must be non-decreasing"
    }
}

/** Holds all metric evidence for one workload and run mode. */
@JsonClass(generateAdapter = true)
data class WorkloadResult(
    val id: String,
    val contractSha256: String,
    val fixtureSha256: String,
    val mode: RunMode,
    val metricSeries: List<MetricSeries>,
) {
    internal fun validate(
        path: String,
        intent: RunIntent,
        configuration: CampaignConfiguration,
        targetIds: List<String>,
    ) {
        requireNonBlank("$path.id", id)
        requireSha256("$path.contractSha256", contractSha256)
        requireSha256("$path.fixtureSha256", fixtureSha256)
        require(mode == configuration.mode) {
            "$path.mode must match campaign mode: expected=${configuration.mode}, actual=$mode"
        }
        require(metricSeries.isNotEmpty()) { "$path.metricSeries must not be empty" }
        metricSeries.forEachIndexed { index, series ->
            val seriesPath = "$path.metricSeries[$index]"
            series.validate(seriesPath, configuration, targetIds)
            require(intent != RunIntent.CONTROLLED || series.blocks != null) {
                "$seriesPath must retain raw blocks for a controlled release comparison"
            }
        }
    }
}

/** Stores either raw alternating blocks or exact per-target histogram evidence. */
@JsonClass(generateAdapter = true)
data class MetricSeries(
    val metric: MetricId,
    val provider: String,
    val providerConfigurationSha256: String,
    val unit: MetricUnit,
    val artifacts: List<HashedArtifact> = emptyList(),
    val blocks: List<AlternatingBlock>? = null,
    val histograms: List<ExactHistogram>? = null,
) {
    /** Rejects sample forms and evidence that cannot identify an exact metric series. */
    fun validate(): MetricSeries = apply {
        validate("metricSeries", configuration = null, targetIds = null)
    }

    internal fun validate(
        path: String,
        configuration: CampaignConfiguration?,
        targetIds: List<String>?,
    ) {
        requireNonBlank("$path.provider", provider)
        requireSha256("$path.providerConfigurationSha256", providerConfigurationSha256)
        validateArtifacts("$path.artifacts", artifacts)
        require((blocks == null) != (histograms == null)) {
            "$path must contain exactly one of blocks or histograms"
        }
        blocks?.let { validateBlocks(path, it, configuration, targetIds) }
        histograms?.let { validateHistograms(path, it, targetIds) }
    }

    private fun validateBlocks(
        path: String,
        rawBlocks: List<AlternatingBlock>,
        configuration: CampaignConfiguration?,
        targetIds: List<String>?,
    ) {
        require(rawBlocks.isNotEmpty()) { "$path.blocks must not be empty" }
        require(rawBlocks.map(AlternatingBlock::blockId).distinct().size == rawBlocks.size) {
            "$path.blocks blockId values must be unique"
        }
        configuration?.let { configured ->
            val acceptedCount = rawBlocks.count(AlternatingBlock::accepted)
            require(acceptedCount <= configured.requestedAcceptedBlocks) {
                "$path accepted block count must not exceed requestedAcceptedBlocks: " +
                    "maximum=${configured.requestedAcceptedBlocks}, actual=$acceptedCount"
            }
        }
        rawBlocks.forEachIndexed { blockIndex, block ->
            val blockPath = "$path.blocks[$blockIndex]"
            require(block.blockId >= 0) { "$blockPath.blockId must not be negative" }
            targetIds?.let { configuredIds ->
                require(
                    block.targetOrder.size == configuredIds.size &&
                        block.targetOrder.toSet() == configuredIds.toSet(),
                ) {
                    "$blockPath.targetOrder must contain every configured target exactly once: " +
                        "expected=${configuredIds.toSet()}, actual=${block.targetOrder}"
                }
            }
            require(block.targetOrder.distinct().size == block.targetOrder.size) {
                "$blockPath.targetOrder must contain unique target IDs"
            }
            validateHostHealthTimeline(
                before = block.healthBefore,
                during = block.healthDuring,
                after = block.healthAfter,
                path = blockPath,
            )
            require(block.rejectionReasons.none(String::isBlank)) {
                "$blockPath.rejectionReasons must not contain blanks"
            }
            require(!block.accepted || block.rejectionReasons.isEmpty()) {
                "$blockPath accepted block must not contain rejection reasons"
            }
            require(block.accepted || block.rejectionReasons.isNotEmpty()) {
                "$blockPath rejected block must contain rejection reasons"
            }
            if (block.accepted) {
                validateObservations(blockPath, block.observations, configuration, targetIds)
            } else {
                require(block.observations.isEmpty()) {
                    "$blockPath rejected block observations must be empty"
                }
            }
        }
    }

    private fun validateObservations(
        blockPath: String,
        observations: List<MetricObservation>,
        configuration: CampaignConfiguration?,
        targetIds: List<String>?,
    ) {
        configuration?.let { configured ->
            val expectedCoordinates =
                expectedObservationCoordinates(configured, requireNotNull(targetIds))
            val actualCoordinates = observations.map(MetricObservation::coordinate)
            require(observations.size == expectedCoordinates.size) {
                "$blockPath observations must match declared sample count: " +
                    "expected=${expectedCoordinates.size}, actual=${observations.size}"
            }
            require(actualCoordinates.distinct().size == actualCoordinates.size) {
                "$blockPath observation coordinates must be unique: " +
                    "actual=$actualCoordinates"
            }
            require(actualCoordinates.toSet() == expectedCoordinates) {
                "$blockPath observation coordinates must exactly cover the configured hierarchy: " +
                    "missing=${expectedCoordinates - actualCoordinates.toSet()}, " +
                    "unexpected=${actualCoordinates.toSet() - expectedCoordinates}"
            }
        }
        observations.forEachIndexed { observationIndex, observation ->
            val observationPath = "$blockPath.observations[$observationIndex]"
            observation.validateIntrinsic(observationPath)
            targetIds?.let { require(observation.targetId in it) { "$observationPath.targetId is unknown" } }
            require(observation.metric == metric) { "$observationPath.metric must match parent series" }
            require(observation.provider == provider) { "$observationPath.provider must match parent series" }
            require(observation.unit == unit) { "$observationPath.unit must match parent series" }
            configuration?.let { configured ->
                require(observation.fork < configured.forksPerBlock) {
                    "$observationPath.fork exceeds forksPerBlock"
                }
            }
        }
        if (metric == MetricId.RETAINED_BYTES) validateRetainedGroups(blockPath, observations)
    }

    private fun validateRetainedGroups(
        blockPath: String,
        observations: List<MetricObservation>,
    ) {
        observations
            .groupBy { observation ->
                Triple(
                    observation.targetId,
                    observation.fork,
                    requireNotNull(observation.replicateGroup),
                )
            }
            .forEach { (group, points) ->
                require(points.map(MetricObservation::iteration).sorted() == RETAINED_POINT_INDICES) {
                    "$blockPath retained replicate $group must contain iterations $RETAINED_POINT_INDICES"
                }
                require(
                    points
                        .sortedBy(MetricObservation::iteration)
                        .map { point -> requireNotNull(point.retainedEvidence).executionCount } ==
                        RETAINED_EXECUTION_COUNTS
                ) {
                    "$blockPath retained replicate $group must contain execution counts " +
                        "$RETAINED_EXECUTION_COUNTS"
                }
                require(points.map(MetricObservation::processId).distinct().size == points.size) {
                    "$blockPath retained replicate $group requires one fresh process per point"
                }
            }
    }

    private fun validateHistograms(
        path: String,
        exactHistograms: List<ExactHistogram>,
        targetIds: List<String>?,
    ) {
        require(exactHistograms.isNotEmpty()) { "$path.histograms must not be empty" }
        val histogramTargetIds = exactHistograms.map(ExactHistogram::targetId)
        require(histogramTargetIds.distinct().size == histogramTargetIds.size) {
            "$path.histograms target IDs must be unique"
        }
        targetIds?.let {
            require(histogramTargetIds.toSet() == it.toSet()) {
                "$path.histograms must contain every configured target exactly once"
            }
        }
        exactHistograms.forEachIndexed { histogramIndex, histogram ->
            val histogramPath = "$path.histograms[$histogramIndex]"
            requireNonBlank("$histogramPath.targetId", histogram.targetId)
            require(histogram.buckets.isNotEmpty()) { "$histogramPath.buckets must not be empty" }
            histogram.buckets.forEachIndexed { bucketIndex, bucket ->
                val bucketPath = "$histogramPath.buckets[$bucketIndex]"
                requireFiniteNonNegative("$bucketPath.value", bucket.value)
                require(bucket.count > 0) { "$bucketPath.count must be positive" }
            }
        }
    }
}

private fun expectedObservationCoordinates(
    configuration: CampaignConfiguration,
    targetIds: List<String>,
): Set<ObservationCoordinate> {
    val iterations =
        when (configuration.mode) {
            RunMode.COLD,
            RunMode.WARM,
            -> 0 until configuration.measurementIterations
            RunMode.RETAINED -> RETAINED_POINT_INDICES
        }
    return targetIds
        .flatMap { targetId ->
            (0 until configuration.forksPerBlock).flatMap { fork ->
                iterations.map { iteration -> ObservationCoordinate(targetId, fork, iteration) }
            }
        }
        .toSet()
}

private fun MetricObservation.coordinate(): ObservationCoordinate =
    ObservationCoordinate(targetId, fork, iteration)

private data class ObservationCoordinate(
    val targetId: String,
    val fork: Int,
    val iteration: Int,
)

/** Stores an exact histogram for one configured target. */
@JsonClass(generateAdapter = true)
data class ExactHistogram(
    val targetId: String,
    val buckets: List<HistogramBucket>,
)

/** One exact observed value and its multiplicity. */
@JsonClass(generateAdapter = true)
data class HistogramBucket(
    val value: Double,
    val count: Long,
)

private fun RetainedEvidence.validate(path: String) {
    require(executionCount > 0) { "$path.executionCount must be positive" }
    require(completedGcCycles >= 2) { "$path.completedGcCycles must be at least two" }
    weakReferences.forEachIndexed { index, outcome ->
        val outcomePath = "$path.weakReferences[$index]"
        requireNonBlank("$outcomePath.type", outcome.type)
        require(outcome.created >= 0) { "$outcomePath.created must not be negative" }
        require(outcome.cleared in 0..outcome.created) {
            "$outcomePath.cleared must be between zero and created"
        }
    }
}

internal fun AdapterIdentity.validate(path: String) {
    requireNonBlank("$path.id", id)
    requireSha256("$path.sourceSha256", sourceSha256)
}

internal fun JdkIdentity.validate(path: String) {
    requireNonBlank("$path.distribution", distribution)
    requireNonBlank("$path.vendor", vendor)
    requireNonBlank("$path.fullVersion", fullVersion)
    requireNonBlank("$path.javaHome", javaHome)
    require(jvmFlags.none(String::isBlank)) { "$path.jvmFlags must not contain blanks" }
}

internal fun validateArtifacts(path: String, artifacts: List<HashedArtifact>) {
    require(artifacts.map(HashedArtifact::logicalId).distinct().size == artifacts.size) {
        "$path logical IDs must be unique"
    }
    artifacts.forEachIndexed { index, artifact -> artifact.validate("$path[$index]") }
}

internal fun HashedArtifact.validate(path: String) {
    requireNonBlank("$path.logicalId", logicalId)
    requireNonBlank("$path.executionPath", executionPath)
    require(sizeBytes >= 0) { "$path.sizeBytes must not be negative" }
    requireSha256("$path.sha256", sha256)
}

internal fun validateArtifactSnapshots(path: String, artifacts: List<ArtifactSnapshot>) {
    require(artifacts.map(ArtifactSnapshot::logicalId).distinct().size == artifacts.size) {
        "$path logical IDs must be unique"
    }
    artifacts.forEachIndexed { index, artifact ->
        val artifactPath = "$path[$index]"
        requireNonBlank("$artifactPath.logicalId", artifact.logicalId)
        require(artifact.sizeBytes >= 0) { "$artifactPath.sizeBytes must not be negative" }
        requireSha256("$artifactPath.sha256", artifact.sha256)
    }
}

internal fun requireSha256(path: String, value: String) {
    require(value.matches(SHA256_PATTERN)) { "$path must be a lowercase 64-character SHA-256 hash" }
}

internal fun requireNonBlank(path: String, value: String) {
    require(value.isNotBlank()) { "$path must not be blank" }
}

internal fun requireFiniteNonNegative(path: String, value: Double) {
    require(value.isFinite() && value >= 0.0) { "$path must be finite and non-negative" }
}

internal fun <E : Enum<E>> requireCanonicalEnumOrder(path: String, values: List<E>) {
    require(values == values.distinct().sortedBy(Enum<*>::ordinal)) {
        "$path must be unique and in enum order"
    }
}

internal const val RESULT_SCHEMA_V1: String = "revoman-benchmark/v1"
internal val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
private val BYTE_UNITS =
    setOf(MetricUnit.BYTES, MetricUnit.BYTES_PER_EXECUTION)
private val RETAINED_EXECUTION_COUNTS = listOf(1_000, 2_000, 4_000)
private val RETAINED_POINT_INDICES = RETAINED_EXECUTION_COUNTS.indices.toList()

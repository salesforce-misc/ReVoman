/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.cli.ArtifactDirectory
import com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.host.HostHealthGate
import com.salesforce.revoman.benchmark.driver.host.HostHealthProbe
import com.salesforce.revoman.benchmark.driver.host.LinuxHostProbe
import com.salesforce.revoman.benchmark.driver.host.SampledHostExecution
import com.salesforce.revoman.benchmark.driver.host.VerifiedControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.integrity.LoadedTargetManifest
import com.salesforce.revoman.benchmark.driver.integrity.RuntimeIdentityFactory
import com.salesforce.revoman.benchmark.driver.integrity.TargetManifestLoader
import com.salesforce.revoman.benchmark.driver.jmh.ForkPidProfiler
import com.salesforce.revoman.benchmark.driver.metrics.GnuTimePeakRssProvider
import com.salesforce.revoman.benchmark.driver.metrics.MacOsTimePeakRssProvider
import com.salesforce.revoman.benchmark.driver.metrics.PeakRssProvider
import com.salesforce.revoman.benchmark.driver.model.AlternatingBlock
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.CampaignConfiguration
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.JmhLoggingConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.MetricSeries
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetAssignment
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.model.WorkloadResult
import com.salesforce.revoman.benchmark.driver.process.JdkProcessLauncher
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Provider identity captured beside one scheduler callback without changing block evidence. */
data class ProviderEvidence(
    val blockId: Int,
    val targetId: String,
    val metric: MetricId,
    val provider: String,
    val providerConfigurationSha256: String,
    val unit: MetricUnit,
    val artifacts: List<HashedArtifact>,
)

/** Merges only an exact provider/configuration/unit identity into one raw paired metric series. */
object CampaignEvidenceAssembler {
    fun assemble(
        blocks: List<AlternatingBlock>,
        evidence: List<ProviderEvidence>,
    ): MetricSeries {
        require(blocks.isNotEmpty()) { "Campaign metric blocks must not be empty" }
        require(evidence.isNotEmpty()) { "Campaign provider evidence must not be empty" }
        val identities =
            evidence
                .map { item ->
                    ProviderIdentity(
                        item.metric,
                        item.provider,
                        item.providerConfigurationSha256,
                        item.unit,
                    )
                }
                .distinct()
        require(identities.size == 1) {
            "Campaign provider identities must be identical before evidence can be merged: $identities"
        }
        val expectedCoordinates =
            blocks.flatMap { block -> block.targetOrder.map { targetId -> block.blockId to targetId } }
        val actualCoordinates = evidence.map { item -> item.blockId to item.targetId }
        require(actualCoordinates.size == actualCoordinates.distinct().size) {
            "Campaign provider evidence coordinates must be unique"
        }
        require(actualCoordinates.toSet() == expectedCoordinates.toSet()) {
            "Campaign provider evidence must cover every scheduled block/target coordinate exactly once"
        }
        val identity = identities.single()
        return MetricSeries(
                metric = identity.metric,
                provider = identity.provider,
                providerConfigurationSha256 = identity.providerConfigurationSha256,
                unit = identity.unit,
                artifacts = evidence.flatMap(ProviderEvidence::artifacts),
                blocks = blocks,
                histograms = null,
            )
            .validate()
    }
}

private data class ProviderIdentity(
    val metric: MetricId,
    val provider: String,
    val providerConfigurationSha256: String,
    val unit: MetricUnit,
)

/** Complete caller request after target models are decoded but before campaign-wide preflight. */
data class CampaignRequest(
    val intent: RunIntent,
    val mode: RunMode,
    val baseline: TargetManifest,
    val baselineManifestPath: Path,
    val baselineAdapterId: String,
    val candidate: TargetManifest,
    val candidateManifestPath: Path,
    val candidateAdapterId: String,
    val workloadId: String,
    val blocks: Int,
    val forksPerBlock: Int,
    val warmups: Int,
    val iterations: Int,
    val seed: Long,
    val metricPasses: Set<MetricPass>,
    val artifactDirectory: Path,
    val hostPolicy: ControlledHostPolicy?,
    val hostPolicyPath: Path? = null,
)

/** Runs all metric passes through one immutable target/logging/fixture identity session. */
class BenchmarkCampaign(
    installationRoot: Path,
    private val processLauncher: ProcessLauncher = JdkProcessLauncher(),
    private val warmAllocationLauncher: WarmAllocationLauncher = JmhControllerProcessLauncher(),
    private val timeout: Duration = Duration.ofMinutes(2),
) {
    private val identityFactory = RuntimeIdentityFactory(installationRoot.toRealPath())

    fun run(request: CampaignRequest): BenchmarkResultV1 {
        val baseline = TargetManifestLoader.load(request.baselineManifestPath)
        val candidate = TargetManifestLoader.load(request.candidateManifestPath)
        require(baseline.manifest == request.baseline) {
            "Baseline request model differs from its verified manifest"
        }
        require(candidate.manifest == request.candidate) {
            "Candidate request model differs from its verified manifest"
        }
        val policy =
            request.hostPolicyPath?.let { source ->
                ControlledHostPolicy.load(source.toAbsolutePath().normalize()).also { loaded ->
                    require(loaded.policy == request.hostPolicy) {
                        "Host policy request model differs from its verified source"
                    }
                }
            }
        return runVerified(request, baseline, candidate, policy)
    }

    internal fun runVerified(
        request: CampaignRequest,
        baseline: LoadedTargetManifest,
        candidate: LoadedTargetManifest,
        hostPolicy: VerifiedControlledHostPolicy?,
    ): BenchmarkResultV1 {
        validateRequest(request, baseline, candidate, hostPolicy)
        val artifactRoot = ArtifactDirectory.reserve(request.artifactDirectory)
        val workloadRoot = identityFactory.installationRoot.resolve("workloads/v1").toRealPath()
        val workloadSource = workloadRoot.resolve(request.workloadId).toRealPath()
        require(workloadSource.parent == workloadRoot && Files.isDirectory(workloadSource)) {
            "Campaign workload must be one exact packaged workload: ${request.workloadId}"
        }
        val logging =
            VerifiedLoggingConfiguration.preflight(
                identityFactory.installationRoot
                    .resolve("conf/log4j2-benchmark.xml")
                    .toRealPath()
            )
        return VerifiedCampaignSession.open(
                identityFactory = identityFactory,
                artifactRoot = artifactRoot,
                workloadSource = workloadSource,
                logging = logging,
                baseline = baseline,
                baselineAdapterId = request.baselineAdapterId,
                candidate = candidate,
                candidateAdapterId = request.candidateAdapterId,
                hostPolicy = hostPolicy,
            )
            .use { session -> execute(request, session, artifactRoot) }
    }

    private fun execute(
        request: CampaignRequest,
        session: VerifiedCampaignSession,
        artifactRoot: Path,
    ): BenchmarkResultV1 {
        val series =
            request.metricPasses
                .sortedBy(Enum<*>::ordinal)
                .map { metricPass -> executePass(request, session, artifactRoot, metricPass) }
        val assignments =
            listOf(
                TargetAssignment(
                    TargetRole.BASELINE,
                    session.baseline.manifest.targetId,
                    request.baselineAdapterId,
                ),
                TargetAssignment(
                    TargetRole.CANDIDATE,
                    session.candidate.manifest.targetId,
                    request.candidateAdapterId,
                ),
            )
        return BenchmarkResultV1(
                campaignId = "campaign-${UUID.randomUUID()}",
                intent = request.intent,
                createdAt = Instant.now().toString(),
                configuration =
                    CampaignConfiguration(
                        mode = request.mode,
                        targets = assignments,
                        metricPasses = request.metricPasses.sortedBy(Enum<*>::ordinal),
                        seed = request.seed,
                        requestedAcceptedBlocks = request.blocks,
                        forksPerBlock = request.forksPerBlock,
                        warmupIterations = request.warmups,
                        measurementIterations = request.iterations,
                    ),
                harness = session.harness,
                environment = session.environment,
                targets = listOf(session.baselineIdentity, session.candidateIdentity),
                workloads =
                    listOf(
                        WorkloadResult(
                            id = session.workload.manifest.id,
                            contractSha256 = session.harness.workloadContractSha256,
                            fixtureSha256 = session.workload.manifest.fixtureTreeSha256,
                            mode = request.mode,
                            metricSeries = series,
                        )
                    ),
            )
            .validate()
    }

    private fun executePass(
        request: CampaignRequest,
        session: VerifiedCampaignSession,
        artifactRoot: Path,
        metricPass: MetricPass,
    ): MetricSeries {
        val passRoot =
            Files.createDirectory(artifactRoot.resolve("pass-${metricPass.name.lowercase()}"))
                .toRealPath()
        val policy = session.hostPolicy?.policy ?: smokePolicy()
        val probe: HostHealthProbe =
            session.hostPolicy?.let { LinuxHostProbe(it.policy) } ?: SyntheticHostProbe()
        val orchestrator =
            PairedBlockOrchestrator(
                policy = policy,
                scheduler = AlternatingBlockScheduler(request.seed),
                probe = probe,
                gate = HostHealthGate(policy),
            )
        val evidence = mutableListOf<ProviderEvidence>()
        val campaign =
            orchestrator.run(
                requestedAcceptedBlocks = request.blocks,
                baselineId = session.baseline.manifest.targetId,
                candidateId = session.candidate.manifest.targetId,
            ) { scheduled ->
                val target = session.target(scheduled.targetRole)
                val adapterId =
                    when (scheduled.targetRole) {
                        TargetRole.BASELINE -> request.baselineAdapterId
                        TargetRole.CANDIDATE -> request.candidateAdapterId
                    }
                val results =
                    (0 until request.forksPerBlock).map { fork ->
                        val coordinate =
                            reserveCoordinate(passRoot, scheduled.blockId, scheduled.targetRole, fork)
                        session.fixture.resetExecution(
                            "${metricPass.name}-${scheduled.blockId}-${scheduled.targetRole}-$fork"
                        )
                        executeProvider(
                            request = request,
                            session = session,
                            target = target,
                            adapterId = adapterId,
                            scheduled = scheduled,
                            fork = fork,
                            coordinate = coordinate,
                            metricPass = metricPass,
                        )
                    }
                val identities = results.map(ProviderResult::identity).distinct()
                require(identities.size == 1) {
                    "Provider identity changed across forks for block ${scheduled.blockId}"
                }
                val identity = identities.single()
                evidence +=
                    ProviderEvidence(
                        blockId = scheduled.blockId,
                        targetId = scheduled.targetId,
                        metric = identity.metric,
                        provider = identity.provider,
                        providerConfigurationSha256 = identity.providerConfigurationSha256,
                        unit = identity.unit,
                        artifacts = results.flatMap(ProviderResult::artifacts),
                    )
                results.flatMap(ProviderResult::observations)
            }
        return CampaignEvidenceAssembler.assemble(campaign.blocks, evidence)
    }

    private fun executeProvider(
        request: CampaignRequest,
        session: VerifiedCampaignSession,
        target: SessionTarget,
        adapterId: String,
        scheduled: ScheduledTarget,
        fork: Int,
        coordinate: Path,
        metricPass: MetricPass,
    ): ProviderResult {
        val workload =
            WorkloadRequest(
                id = session.workload.manifest.id,
                contractVersion = session.workload.manifest.contractVersion,
                fixtureRoot = session.workload.snapshotRoot.toString(),
                baseUrl = session.fixture.baseUrl,
            )
        val expectedDigest = requireNotNull(session.workload.manifest.expectedDigest)
        return when (request.mode) {
            RunMode.COLD -> {
                val plan =
                    ColdPlan(
                        intent = request.intent,
                        target = target.loaded.manifest,
                        targetManifestPath = target.loaded.verified.manifestPath,
                        adapterId = adapterId,
                        workload = workload,
                        expectedDigest = expectedDigest,
                        sampleCount = 1,
                        metricPass = metricPass,
                        timeout = timeout,
                        loggingConfiguration = session.logging,
                        artifactDirectory =
                            coordinate.takeIf {
                                metricPass == MetricPass.ALLOCATION || metricPass == MetricPass.PEAK_RSS
                            },
                        jfrConfigurationFile =
                            session.jfrConfiguration.sourcePath.takeIf {
                                metricPass == MetricPass.ALLOCATION
                            },
                        peakRssProvider =
                            peakRssProvider(request.intent).takeIf {
                                metricPass == MetricPass.PEAK_RSS
                            },
                        position = ColdPosition(scheduled.blockId, scheduled.targetRole, fork),
                    )
                val runner = ColdRunner(processLauncher)
                val result =
                    if (metricPass == MetricPass.ALLOCATION) {
                        runner.runWithEvidence(plan, target.runner, session.jfrSnapshot)
                    } else {
                        runner.runWithEvidence(plan, target.runner)
                    }
                result.toProviderResult()
            }
            RunMode.WARM ->
                when (metricPass) {
                    MetricPass.LATENCY ->
                        WarmRunner(processLauncher)
                            .runWithEvidence(
                                WarmPlan(
                                    intent = request.intent,
                                    target = target.loaded.manifest,
                                    targetManifestPath = target.loaded.verified.manifestPath,
                                    adapterId = adapterId,
                                    workload = workload,
                                    expectedDigest = expectedDigest,
                                    forksPerBlock = 1,
                                    warmupIterations = request.warmups,
                                    measurementIterations = request.iterations,
                                    metricPass = metricPass,
                                    timeout = timeout,
                                    loggingConfiguration = session.logging,
                                ),
                                target.runner,
                            )
                            .let { result ->
                                result.copy(
                                    observations = result.observations.map { it.copy(fork = fork) }
                                )
                            }
                            .toProviderResult()
                    MetricPass.ALLOCATION ->
                        warmAllocation(request, session, target, adapterId, scheduled, fork, coordinate)
                    else -> error("Unsupported warm metric pass: $metricPass")
                }
            RunMode.RETAINED ->
                RetainedMemoryRunner(processLauncher)
                    .run(
                        RetainedMemoryPlan(
                            target = target.loaded.manifest,
                            targetManifestPath = target.loaded.verified.manifestPath,
                            adapterId = adapterId,
                            workload = workload,
                            expectedDigest = expectedDigest,
                            blockId = scheduled.blockId,
                            targetRole = scheduled.targetRole,
                            fork = fork,
                            replicateGroup = Math.addExact(Math.multiplyExact(scheduled.blockId, request.forksPerBlock), fork),
                            timeout = timeout,
                            loggingConfiguration = session.logging,
                        ),
                        target.runner,
                    )
                    .toProviderResult()
        }
    }

    private fun warmAllocation(
        request: CampaignRequest,
        session: VerifiedCampaignSession,
        target: SessionTarget,
        adapterId: String,
        scheduled: ScheduledTarget,
        fork: Int,
        coordinate: Path,
    ): ProviderResult {
        val configuration =
            JmhRunConfiguration(
                requestedIncludes = listOf("WarmLifecycleAllocationBenchmark"),
                requestedForks = 1,
                profilers = listOf("gc"),
                internalProfilers = listOf(ForkPidProfiler::class.java.name),
                quick = request.intent != RunIntent.CONTROLLED,
                logging =
                    JmhLoggingConfiguration(
                        log4j2ConfigurationFileSha256 = session.logging.sha256,
                        log4j2GlobalConfigurationFileSha256 = session.logging.sha256,
                        kotlinLoggingStartupMessage = "false",
                        revomanBanner = "off",
                    ),
            )
        return WarmAllocationRunner(warmAllocationLauncher)
            .run(
                WarmAllocationPlan(
                    intent = request.intent,
                    blockId = scheduled.blockId,
                    targetRole = scheduled.targetRole,
                    fork = fork,
                    target = target.loaded.manifest,
                    targetManifestPath = target.loaded.verified.manifestPath,
                    adapterId = adapterId,
                    benchmarkClassesJar = session.benchmarkClassesJar,
                    controllerClasspath = session.controllerClasspath,
                    targetClasspath = target.loaded.manifest.classpath.map { Path.of(it.executionPath) },
                    installationRoot = identityFactory.installationRoot,
                    outputDirectory = coordinate,
                    warmupIterations = request.warmups,
                    measurementIterations = request.iterations,
                    timeout =
                        warmAllocationControllerTimeout(
                            request.warmups,
                            request.iterations,
                            WARM_ALLOCATION_ITERATION_DURATION,
                        ),
                    loggingConfiguration = session.logging,
                    iterationDuration = WARM_ALLOCATION_ITERATION_DURATION,
                    fixtureRoot = session.workload.snapshotRoot,
                    expectedJmhEvidence =
                        JmhEvidenceExpectation(
                            harness = session.harness,
                            environment = session.environment,
                            target = target.identity,
                            workload = session.workload.workloadIdentity,
                            configuration = configuration,
                        ),
                    verifiedTarget = target.loaded.verified,
                    loggingSnapshot = session.loggingSnapshot,
                    fixtureBaseUrl = session.fixture.baseUrl,
                )
            )
            .toProviderResult()
    }

    private fun validateRequest(
        request: CampaignRequest,
        baseline: LoadedTargetManifest,
        candidate: LoadedTargetManifest,
        hostPolicy: VerifiedControlledHostPolicy?,
    ) {
        require(request.blocks > 0) { "blocks must be positive" }
        require(request.forksPerBlock > 0) { "forksPerBlock must be positive" }
        require(request.warmups >= 0) { "warmups must not be negative" }
        require(request.metricPasses.isNotEmpty()) { "metricPasses must not be empty" }
        require(baseline.manifest.targetId != candidate.manifest.targetId) {
            "Baseline and candidate target IDs must be distinct"
        }
        if (request.intent == RunIntent.CONTROLLED) {
            baseline.requireClean("Controlled execution")
            candidate.requireClean("Controlled execution")
            require(hostPolicy != null) { "Controlled execution requires a verified host policy" }
            val minimum = if (request.mode == RunMode.COLD) 50 else 5
            require(request.blocks >= minimum) {
                "Controlled ${request.mode} execution requires at least $minimum accepted blocks"
            }
        } else {
            require(hostPolicy == null) { "Smoke/CI execution cannot claim a controlled host policy" }
        }
        when (request.mode) {
            RunMode.COLD -> {
                require(request.iterations == 1) { "Cold execution requires exactly one measured execution" }
                require(request.warmups == 0) { "Cold execution requires zero warmups" }
                require(request.metricPasses.all { it in COLD_PASSES }) { "Unsupported cold metric pass" }
            }
            RunMode.WARM -> {
                require(request.iterations > 0) { "Warm execution requires positive iterations" }
                require(request.metricPasses.all { it in WARM_PASSES }) { "Unsupported warm metric pass" }
            }
            RunMode.RETAINED -> {
                require(request.iterations == 0 && request.warmups == 0) {
                    "Retained execution requires zero warmups and iterations"
                }
                require(request.metricPasses == setOf(MetricPass.RETAINED)) {
                    "Retained execution requires only the retained metric pass"
                }
            }
        }
    }
}

internal fun warmAllocationControllerTimeout(
    warmupIterations: Int,
    measurementIterations: Int,
    iterationDuration: Duration,
): Duration {
    require(warmupIterations >= 0) { "warmupIterations must not be negative" }
    require(measurementIterations > 0) { "measurementIterations must be positive" }
    require(!iterationDuration.isZero && !iterationDuration.isNegative) {
        "iterationDuration must be positive"
    }
    val iterationCount = Math.addExact(warmupIterations, measurementIterations)
    val iterationBudget = iterationDuration.multipliedBy(iterationCount.toLong())
    return iterationBudget.plus(WARM_ALLOCATION_TIMEOUT_HEADROOM)
}

private data class ProviderResult(
    val identity: ProviderIdentity,
    val artifacts: List<HashedArtifact>,
    val observations: List<com.salesforce.revoman.benchmark.driver.model.MetricObservation>,
)

private fun ColdRunResult.toProviderResult(): ProviderResult =
    ProviderResult(
        ProviderIdentity(observations.first().metric, provider, providerConfigurationSha256, observations.first().unit),
        artifacts,
        observations,
    )

private fun WarmRunResult.toProviderResult(): ProviderResult =
    ProviderResult(
        ProviderIdentity(observations.first().metric, provider, providerConfigurationSha256, observations.first().unit),
        emptyList(),
        observations,
    )

private fun WarmAllocationResult.toProviderResult(): ProviderResult =
    ProviderResult(
        ProviderIdentity(observations.first().metric, provider, providerConfigurationSha256, observations.first().unit),
        artifacts,
        observations,
    )

private fun RetainedMemoryResult.toProviderResult(): ProviderResult =
    ProviderResult(
        ProviderIdentity(observations.first().metric, provider, providerConfigurationSha256, observations.first().unit),
        emptyList(),
        observations,
    )

private class SyntheticHostProbe : HostHealthProbe {
    private val clock = AtomicLong()

    override fun sample(): HostHealthSnapshot = health()

    override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> =
        SampledHostExecution(execution(), health())

    private fun health(): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = clock.getAndIncrement(),
            loadAverage = 0.0,
            cpuBusyFraction = 0.0,
            availableMemoryBytes = Long.MAX_VALUE,
            swapUsedBytes = 0,
            thermalValue = 0.0,
            onAcPower = true,
            governors = listOf("unknown"),
        )
}

private fun smokePolicy(): ControlledHostPolicy =
    ControlledHostPolicy(
        hostFingerprintSha256 = "0".repeat(64),
        cpuModel = "smoke",
        cpuCount = 1,
        allowedGovernors = setOf("unknown"),
        requireAcPower = false,
        maximumLoadAverage = Double.MAX_VALUE,
        maximumCpuBusyFraction = 1.0,
        minimumAvailableMemoryBytes = 1,
        maximumSwapDeltaBytes = Long.MAX_VALUE,
        maximumThermalValue = Double.MAX_VALUE,
        probeIntervalMillis = 1,
        maximumReplacementBlocks = 0,
    )

private fun reserveCoordinate(
    passRoot: Path,
    blockId: Int,
    role: TargetRole,
    fork: Int,
): Path {
    val block = passRoot.resolve("block-$blockId")
    if (Files.notExists(block, NOFOLLOW_LINKS)) Files.createDirectory(block)
    val roleDirectory = block.resolve("role-${role.name.lowercase()}")
    if (Files.notExists(roleDirectory, NOFOLLOW_LINKS)) Files.createDirectory(roleDirectory)
    return Files.createDirectory(roleDirectory.resolve("fork-$fork")).toRealPath()
}

private fun peakRssProvider(intent: RunIntent): PeakRssProvider =
    when {
        intent == RunIntent.CONTROLLED -> GnuTimePeakRssProvider
        System.getProperty("os.name").startsWith("Mac") -> MacOsTimePeakRssProvider
        else -> GnuTimePeakRssProvider
    }

private val COLD_PASSES = setOf(MetricPass.LATENCY, MetricPass.ALLOCATION, MetricPass.PEAK_RSS)
private val WARM_PASSES = setOf(MetricPass.LATENCY, MetricPass.ALLOCATION)
private val WARM_ALLOCATION_ITERATION_DURATION = Duration.ofSeconds(1)
private val WARM_ALLOCATION_TIMEOUT_HEADROOM = Duration.ofSeconds(30)

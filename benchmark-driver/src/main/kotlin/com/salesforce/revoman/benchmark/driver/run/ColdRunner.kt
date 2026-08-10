/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.metrics.GnuTimePeakRssProvider
import com.salesforce.revoman.benchmark.driver.metrics.JfrAllocationReader
import com.salesforce.revoman.benchmark.driver.metrics.PeakRssProvider
import com.salesforce.revoman.benchmark.driver.metrics.VerifiedJfrConfiguration
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.model.requireExpectedExecutionDigest
import com.salesforce.revoman.benchmark.driver.process.JavaCommand
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import com.salesforce.revoman.benchmark.driver.process.ProcessObservation
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Scheduler-supplied coordinates for one cold target position inside a paired host block. */
data class ColdPosition(
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
)

/** Configuration for fresh-JVM cold execution or one scheduler-supplied provider position. */
data class ColdPlan(
    val intent: RunIntent,
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val sampleCount: Int,
    val metricPass: MetricPass,
    val timeout: Duration,
    val loggingConfiguration: VerifiedLoggingConfiguration,
    val artifactDirectory: Path? = null,
    val jfrConfigurationFile: Path? = null,
    val peakRssProvider: PeakRssProvider? = null,
    val position: ColdPosition? = null,
)

/** Provider identity, immutable configuration, artifacts, and raw cold observations. */
data class ColdRunResult(
    val position: ColdPosition?,
    val provider: String,
    val providerConfigurationSha256: String,
    val artifacts: List<HashedArtifact>,
    val observations: List<MetricObservation>,
)

/** Runs each cold observation in a distinct target process. */
class ColdRunner(
    private val launcher: ProcessLauncher,
    private val jfrAllocationReader: JfrAllocationReader = JfrAllocationReader(),
) {
    /** Executes [plan] with one measured child process per returned observation. */
    fun run(plan: ColdPlan): List<MetricObservation> = runWithEvidence(plan).observations

    /** Executes one uncontaminated metric pass and retains its provider identity and artifacts. */
    fun runWithEvidence(plan: ColdPlan): ColdRunResult {
        val expectedDigest = validate(plan)
        val campaign =
            RunnerCampaign.open(
                expectedTarget = plan.target,
                targetManifestPath = plan.targetManifestPath,
                loggingConfiguration = plan.loggingConfiguration,
            )
        return campaign.withPostflight { runWithEvidence(plan, campaign, expectedDigest) }
    }

    /** Executes against a campaign-owned verified target/logging session without rereading identities. */
    internal fun runWithEvidence(plan: ColdPlan, campaign: RunnerCampaign): ColdRunResult {
        val expectedDigest = validate(plan)
        return runWithEvidence(plan, campaign, expectedDigest)
    }

    /** Executes allocation against a campaign-owned immutable JFR snapshot. */
    internal fun runWithEvidence(
        plan: ColdPlan,
        campaign: RunnerCampaign,
        jfrConfigurationSnapshot: Path,
    ): ColdRunResult {
        val expectedDigest = validate(plan)
        require(plan.metricPass == MetricPass.ALLOCATION && plan.jfrConfigurationFile != null) {
            "A campaign-owned JFR snapshot applies only to a configured allocation pass"
        }
        requireCanonicalFile("jfrConfigurationSnapshot", jfrConfigurationSnapshot)
        return runCampaign(plan, campaign, expectedDigest, jfrConfigurationSnapshot)
    }

    private fun runWithEvidence(
        plan: ColdPlan,
        campaign: RunnerCampaign,
        expectedDigest: ExecutionDigest,
    ): ColdRunResult {
        val source = plan.jfrConfigurationFile
        return if (plan.metricPass == MetricPass.ALLOCATION && source != null) {
            withJfrSnapshot(VerifiedJfrConfiguration.preflight(source)) { snapshot ->
                runCampaign(plan, campaign, expectedDigest, snapshot)
            }
        } else {
            runCampaign(plan, campaign, expectedDigest, jfrConfigurationFile = null)
        }
    }

    private fun runCampaign(
        plan: ColdPlan,
        campaign: RunnerCampaign,
        expectedDigest: ExecutionDigest,
        jfrConfigurationFile: Path?,
    ): ColdRunResult =
        when (plan.metricPass) {
            MetricPass.LATENCY -> runLatency(plan, campaign, expectedDigest)
            MetricPass.ALLOCATION ->
                runAllocation(
                    plan,
                    campaign,
                    expectedDigest,
                    requireNotNull(jfrConfigurationFile),
                )
            MetricPass.PEAK_RSS -> runPeakRss(plan, campaign, expectedDigest)
            MetricPass.RETAINED -> error("ColdRunner does not execute retained-memory passes")
        }.also { result -> requireDistinctProcessIds(result.observations, "cold samples") }

    private fun runLatency(
        plan: ColdPlan,
        campaign: RunnerCampaign,
        expectedDigest: ExecutionDigest,
    ): ColdRunResult {
        val observations =
            List(plan.sampleCount) { sample ->
                val process = launchCold(campaign, plan, expectedDigest)
                latencyObservation(plan, process, sample)
            }
        return ColdRunResult(
            position = plan.position,
            provider = COLD_LATENCY_PROVIDER,
            providerConfigurationSha256 =
                coldProviderConfigurationSha256(
                    plan = plan,
                    baseProviderConfigurationSha256 =
                        ContentHasher.sha256(COLD_LATENCY_PROVIDER.toByteArray(UTF_8)),
                ),
            artifacts = emptyList(),
            observations = observations,
        )
    }

    private fun runAllocation(
        plan: ColdPlan,
        campaign: RunnerCampaign,
        expectedDigest: ExecutionDigest,
        configuration: Path,
    ): ColdRunResult {
        val artifactDirectory = requireNotNull(plan.artifactDirectory)
        var providerConfigurationSha256: String? = null
        val artifacts = mutableListOf<HashedArtifact>()
        val observations =
            List(plan.sampleCount) { sample ->
                val coordinate = requireNotNull(plan.position)
                val logicalId = allocationArtifactId(coordinate)
                val recording = artifactDirectory.resolve(logicalId)
                require(!Files.exists(recording)) { "JFR artifact already exists: $recording" }
                val process =
                    launchCold(
                        campaign = campaign,
                        plan = plan,
                        expectedDigest = expectedDigest,
                        jfrConfigurationFile = configuration,
                        jfrRecordingFile = recording,
                    )
                val resultHash =
                    requireNotNull(process.result.jfrConfigurationSha256) {
                        "Cold allocation worker omitted JFR configuration metadata"
                    }
                val measurement =
                    jfrAllocationReader.read(recording, configuration, resultHash)
                providerConfigurationSha256?.let { prior ->
                    check(prior == measurement.providerConfigurationSha256) {
                        "Cold allocation provider configuration changed within a pass"
                    }
                } ?: run { providerConfigurationSha256 = measurement.providerConfigurationSha256 }
                artifacts +=
                    HashedArtifact(
                        logicalId = logicalId,
                        executionPath = recording.toString(),
                        sizeBytes = Files.size(recording),
                        sha256 = ContentHasher.sha256(recording),
                    )
                MetricObservation(
                    targetId = plan.target.targetId,
                    metric = MetricId.ALLOCATED_BYTES,
                    provider = measurement.provider,
                    unit = MetricUnit.BYTES,
                    fork = coordinate.fork,
                    iteration = 0,
                    processId = process.processId,
                    value = measurement.allocatedBytes.toDouble(),
                )
            }
        return ColdRunResult(
            position = plan.position,
            provider = JfrAllocationReader.PROVIDER_ID,
            providerConfigurationSha256 =
                coldProviderConfigurationSha256(
                    plan = plan,
                    baseProviderConfigurationSha256 = requireNotNull(providerConfigurationSha256),
                ),
            artifacts = artifacts.toList(),
            observations = observations,
        )
    }

    private fun <T> withJfrSnapshot(
        configuration: VerifiedJfrConfiguration,
        block: (Path) -> T,
    ): T {
        var directory: Path? = null
        var snapshot: Path? = null
        val outcome =
            runCatching {
                val created = Files.createTempDirectory("revoman-jfr-configuration-").toRealPath()
                directory = created
                val materialized = configuration.materialize(created)
                snapshot = materialized
                block(materialized)
            }
        var failure = outcome.exceptionOrNull()
        listOf<() -> Unit>(
                { snapshot?.let(configuration::postflight) },
                { directory?.let(::deleteRecursively) },
            )
            .forEach { finalizer ->
                try {
                    finalizer()
                } catch (finalizerFailure: Throwable) {
                    failure = mergeFailures(failure, finalizerFailure)
                }
            }
        failure?.let { throw it }
        return outcome.getOrThrow()
    }

    private fun runPeakRss(
        plan: ColdPlan,
        campaign: RunnerCampaign,
        expectedDigest: ExecutionDigest,
    ): ColdRunResult {
        val artifactDirectory = requireNotNull(plan.artifactDirectory)
        val provider = requireNotNull(plan.peakRssProvider)
        val observations =
            List(plan.sampleCount) { sample ->
                val providerOutput = artifactDirectory.resolve("peak-rss-$sample.txt")
                require(!Files.exists(providerOutput)) {
                    "Peak RSS provider output already exists: $providerOutput"
                }
                var primary: Throwable? = null
                try {
                    val process =
                        launchCold(
                            campaign = campaign,
                            plan = plan,
                            expectedDigest = expectedDigest,
                            invocationPrefix = provider.invocationPrefix(providerOutput),
                        )
                    MetricObservation(
                        targetId = plan.target.targetId,
                        metric = MetricId.PEAK_RSS,
                        provider = provider.id,
                        unit = MetricUnit.BYTES,
                        fork = plan.position?.fork ?: sample,
                        iteration = 0,
                        processId = process.processId,
                        value = provider.parse(providerOutput).toDouble(),
                    )
                } catch (failure: Throwable) {
                    primary = failure
                    throw failure
                } finally {
                    try {
                        Files.deleteIfExists(providerOutput)
                    } catch (deletionFailure: Throwable) {
                        primary?.let { failure ->
                            if (failure !== deletionFailure) failure.addSuppressed(deletionFailure)
                        } ?: throw deletionFailure
                    }
                }
            }
        return ColdRunResult(
            position = plan.position,
            provider = provider.id,
            providerConfigurationSha256 =
                coldProviderConfigurationSha256(
                    plan = plan,
                    baseProviderConfigurationSha256 = provider.configurationSha256,
                ),
            artifacts = emptyList(),
            observations = observations,
        )
    }

    private fun launchCold(
        campaign: RunnerCampaign,
        plan: ColdPlan,
        expectedDigest: ExecutionDigest,
        jfrConfigurationFile: Path? = null,
        jfrRecordingFile: Path? = null,
        invocationPrefix: List<String> = emptyList(),
    ): ProcessObservation =
        campaign
            .launch(
                launcher = launcher,
                adapterId = plan.adapterId,
                workload = plan.workload,
                mode = RunMode.COLD,
                metricPass = plan.metricPass,
                expectedDigest = expectedDigest,
                warmupIterations = 0,
                measurementIterations = 1,
                timeout = plan.timeout,
                jfrConfigurationFile = jfrConfigurationFile,
                jfrRecordingFile = jfrRecordingFile,
                invocationPrefix = invocationPrefix,
            )
            .also { process ->
                validateProcess(process, 0, 1, expectedDigest)
            }

    private fun latencyObservation(
        plan: ColdPlan,
        process: ProcessObservation,
        sample: Int,
    ): MetricObservation =
        MetricObservation(
            targetId = plan.target.targetId,
            metric = MetricId.LATENCY,
            provider = COLD_LATENCY_PROVIDER,
            unit = MetricUnit.NANOSECONDS,
            fork = plan.position?.fork ?: sample,
            iteration = 0,
            processId = process.processId,
            value = process.elapsedNanos.toDouble(),
        )

    private fun validate(plan: ColdPlan): ExecutionDigest {
        require(plan.sampleCount > 0) { "Cold sampleCount must be positive" }
        plan.position?.let { position ->
            require(position.blockId >= 0) { "Cold position blockId must not be negative" }
            require(position.fork >= 0) { "Cold position fork must not be negative" }
            require(plan.sampleCount == 1) {
                "Cold scheduler position requires exactly one process"
            }
        }
        require(plan.intent != RunIntent.CONTROLLED || plan.position != null) {
            "Controlled cold execution requires a scheduler-supplied position"
        }
        require(plan.position != null || plan.metricPass == MetricPass.LATENCY) {
            "Cold resource metric passes require a scheduler-supplied position"
        }
        validateCommon(plan.adapterId, plan.timeout)
        validateProviderConfiguration(plan)
        return requireMacroOracle(plan.expectedDigest)
    }

    private fun validateProviderConfiguration(plan: ColdPlan) {
        when (plan.metricPass) {
            MetricPass.LATENCY -> {
                require(plan.artifactDirectory == null) {
                    "Cold LATENCY pass must not configure an artifact directory"
                }
                require(plan.jfrConfigurationFile == null && plan.peakRssProvider == null) {
                    "Cold LATENCY pass must not configure resource providers"
                }
            }
            MetricPass.ALLOCATION -> {
                requireArtifactDirectory(plan.artifactDirectory)
                requireCanonicalFile("jfrConfigurationFile", plan.jfrConfigurationFile)
                require(plan.peakRssProvider == null) {
                    "Cold ALLOCATION pass cannot configure a peak RSS provider"
                }
            }
            MetricPass.PEAK_RSS -> {
                requireArtifactDirectory(plan.artifactDirectory)
                require(plan.peakRssProvider != null) {
                    "Cold PEAK_RSS pass requires a provider"
                }
                require(
                    plan.intent != RunIntent.CONTROLLED ||
                        plan.peakRssProvider.id == GnuTimePeakRssProvider.id
                ) {
                    "Controlled cold PEAK_RSS requires the Linux GNU time provider"
                }
                require(plan.jfrConfigurationFile == null) {
                    "Cold PEAK_RSS pass cannot configure JFR"
                }
            }
            MetricPass.RETAINED -> error("ColdRunner does not execute retained-memory passes")
        }
    }

    private fun requireArtifactDirectory(path: Path?): Path {
        val required = requireNotNull(path) { "Resource metric pass requires artifactDirectory" }
        require(required.isAbsolute && required.normalize() == required && Files.isDirectory(required)) {
            "artifactDirectory must be an existing absolute normalized directory: $required"
        }
        return required
    }

    private fun requireCanonicalFile(name: String, path: Path?): Path {
        val required = requireNotNull(path) { "Cold ALLOCATION pass requires $name" }
        require(required.isAbsolute && required.normalize() == required) {
            "$name must be absolute and normalized: $required"
        }
        require(Files.isRegularFile(required) && required.toRealPath() == required) {
            "$name must be a canonical regular file: $required"
        }
        return required
    }
}

internal class RunnerCampaign private constructor(
    private val verified: VerifiedTargetManifest,
    private val loggingConfiguration: VerifiedLoggingConfiguration,
    private val loggingSnapshot: Path,
    private val campaignDirectory: Path,
    private val javaExecutable: Path,
    private val classpath: List<Path>,
    private val workingDirectory: Path,
    private val ownsResources: Boolean,
) {
    fun launch(
        launcher: ProcessLauncher,
        adapterId: String,
        workload: WorkloadRequest,
        mode: RunMode,
        metricPass: MetricPass,
        expectedDigest: ExecutionDigest,
        warmupIterations: Int,
        measurementIterations: Int,
        timeout: Duration,
        jfrConfigurationFile: Path? = null,
        jfrRecordingFile: Path? = null,
        retainedExecutionCount: Int? = null,
        invocationPrefix: List<String> = emptyList(),
    ): ProcessObservation {
        val invocationDirectory =
            Files.createTempDirectory(campaignDirectory, "target-fork-").toRealPath()
        return withRecursiveCleanup(invocationDirectory) {
            val commandPath = invocationDirectory.resolve("command.json")
            val resultPath = invocationDirectory.resolve("result.json")
            val workerCommand =
                TargetForkCommand(
                    verification = verified.verificationToken(),
                    adapterId = adapterId,
                    mode = mode,
                    metricPass = metricPass,
                    workload = workload,
                    expectedDigest = expectedDigest,
                    warmupIterations = warmupIterations,
                    measurementIterations = measurementIterations,
                    resultFile = resultPath.toString(),
                    jfrConfigurationFile = jfrConfigurationFile?.toString(),
                    jfrRecordingFile = jfrRecordingFile?.toString(),
                    retainedExecutionCount = retainedExecutionCount,
                )
            BenchmarkJson.write(commandPath, workerCommand)
            launcher.launch(
                JavaCommand(
                    executable = javaExecutable,
                    jvmArgs =
                        listOf(
                            "-Dlog4j2.configurationFile=${loggingSnapshot.toUri()}",
                            "-Dlog4j2.*.Configuration.file=${loggingSnapshot.toUri()}",
                            "-Dkotlin-logging.logStartupMessage=false",
                            "-Drevoman.banner=off",
                        ),
                    classpath = classpath,
                    mainClass = TARGET_FORK_MAIN,
                    programArgs = listOf(commandPath.toString()),
                    workingDirectory = workingDirectory,
                    timeout = timeout,
                    invocationPrefix = invocationPrefix,
                )
            )
        }
    }

    fun <T> withPostflight(block: () -> T): T {
        if (!ownsResources) return block()
        val outcome = runCatching(block)
        var failure = outcome.exceptionOrNull()
        listOf<() -> Unit>(
                verified::postflight,
                { loggingConfiguration.postflight(loggingSnapshot) },
                { deleteRecursively(campaignDirectory) },
            )
            .forEach { finalizer ->
                try {
                    finalizer()
                } catch (finalizerFailure: Throwable) {
                    failure = mergeFailures(failure, finalizerFailure)
                }
            }
        failure?.let { throw it }
        return outcome.getOrThrow()
    }

    private fun <T> withRecursiveCleanup(directory: Path, block: () -> T): T {
        var primaryFailure: Throwable? = null
        return try {
            block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                deleteRecursively(directory)
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.let { primary ->
                    if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
                } ?: throw cleanupFailure
            }
        }
    }

    companion object {
        fun open(
            expectedTarget: TargetManifest,
            targetManifestPath: Path,
            loggingConfiguration: VerifiedLoggingConfiguration,
        ): RunnerCampaign {
            val manifestPath = requiredCanonicalFile("targetManifestPath", targetManifestPath)
            val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
            val executable = javaHome.resolve("bin/java")
            require(Files.isRegularFile(executable)) { "Java executable is missing: $executable" }
            val workingDirectory = Path.of(System.getProperty("user.dir")).toRealPath()
            val classpath =
                System.getProperty("java.class.path")
                    .split(System.getProperty("path.separator"))
                    .filter(String::isNotBlank)
                    .map { entry ->
                        val path = Path.of(entry)
                        (if (path.isAbsolute) path else workingDirectory.resolve(path)).normalize()
                    }
            require(classpath.isNotEmpty()) { "Current Java classpath must not be empty" }
            val campaignDirectory = Files.createTempDirectory("revoman-benchmark-campaign-").toRealPath()
            var failure: Throwable? = null
            return try {
                val loggingSnapshot = loggingConfiguration.materialize(campaignDirectory)
                val verified = VerifiedTargetManifest.preflight(manifestPath, expectedTarget)
                RunnerCampaign(
                    verified = verified,
                    loggingConfiguration = loggingConfiguration,
                    loggingSnapshot = loggingSnapshot,
                    campaignDirectory = campaignDirectory,
                    javaExecutable = executable,
                    classpath = classpath,
                    workingDirectory = workingDirectory,
                    ownsResources = true,
                )
            } catch (setupFailure: Throwable) {
                failure = setupFailure
                throw setupFailure
            } finally {
                if (failure != null) {
                    try {
                        deleteRecursively(campaignDirectory)
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
            }
        }

        /** Uses one campaign-owned verified identity and logging snapshot across every metric pass. */
        fun openVerified(
            verified: VerifiedTargetManifest,
            loggingConfiguration: VerifiedLoggingConfiguration,
            loggingSnapshot: Path,
            campaignDirectory: Path,
            workingDirectory: Path,
        ): RunnerCampaign {
            require(campaignDirectory.toRealPath() == campaignDirectory) {
                "Campaign directory must be canonical: $campaignDirectory"
            }
            require(loggingSnapshot.toRealPath() == loggingSnapshot) {
                "Logging snapshot must be canonical: $loggingSnapshot"
            }
            val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
            val executable = javaHome.resolve("bin/java")
            require(Files.isRegularFile(executable)) { "Java executable is missing: $executable" }
            return RunnerCampaign(
                verified = verified,
                loggingConfiguration = loggingConfiguration,
                loggingSnapshot = loggingSnapshot,
                campaignDirectory = campaignDirectory,
                javaExecutable = executable,
                classpath = currentDriverClasspath(workingDirectory),
                workingDirectory = workingDirectory,
                ownsResources = false,
            )
        }

        private fun currentDriverClasspath(workingDirectory: Path): List<Path> =
            System.getProperty("java.class.path")
                .split(System.getProperty("path.separator"))
                .filter(String::isNotBlank)
                .map { entry ->
                    val path = Path.of(entry)
                    (if (path.isAbsolute) path else workingDirectory.resolve(path)).normalize()
                }
                .also { classpath -> require(classpath.isNotEmpty()) { "Current Java classpath must not be empty" } }

        private fun requiredCanonicalFile(name: String, path: Path): Path {
            require(path.isAbsolute && path.normalize() == path) {
                "$name must be an absolute normalized path: $path"
            }
            val canonical = path.toRealPath()
            require(canonical == path && Files.isRegularFile(path)) {
                "$name must be a canonical regular file: $path"
            }
            return path
        }
    }
}

private fun mergeFailures(primary: Throwable?, next: Throwable): Throwable =
    primary?.also { existing ->
        if (existing !== next) existing.addSuppressed(next)
    } ?: next

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

internal fun validateCommon(adapterId: String, timeout: Duration) {
    require(adapterId.isNotBlank()) { "adapterId must not be blank" }
    require(!timeout.isZero && !timeout.isNegative) { "Runner timeout must be positive" }
}

internal fun validateProcess(
    process: ProcessObservation,
    warmupIterations: Int,
    measurementIterations: Int,
    expectedDigest: ExecutionDigest,
) {
    check(process.exitCode == 0) { "Target process exited with exit code ${process.exitCode}" }
    check(process.processId > 0) { "Target process ID must be positive" }
    check(process.elapsedNanos >= 0) { "Target process elapsed time must not be negative" }
    check(process.stdoutTail.isEmpty()) { "Target process emitted stdout: ${process.stdoutTail}" }
    check(process.stderrTail.isEmpty()) { "Target process emitted stderr: ${process.stderrTail}" }
    val result = process.result
    check(result.protocolVersion == 1) {
        "Target result protocol version ${result.protocolVersion} is unsupported"
    }
    check(result.processId == process.processId) {
        "Target result PID ${result.processId} differs from process PID ${process.processId}"
    }
    check(result.warmupIterations == warmupIterations) {
        "Target result warmup count ${result.warmupIterations} differs from $warmupIterations"
    }
    check(result.measurementIterations == measurementIterations) {
        "Target result measurement count ${result.measurementIterations} differs from $measurementIterations"
    }
    check(result.samples.size == measurementIterations) {
        "Target result sample count ${result.samples.size} differs from $measurementIterations"
    }
    check(result.samples.map { it.iteration } == result.samples.indices.toList()) {
        "Target result sample iterations are not contiguous from zero"
    }
    result.samples.forEachIndexed { index, sample ->
        check(sample.latencyNanos >= 0) { "Target result sample latency must not be negative" }
        check(sample.digest.executedSteps >= 0) {
            "Target result digest executedSteps must not be negative"
        }
        check(sample.digest.failureCount >= 0) {
            "Target result digest failureCount must not be negative"
        }
        requireExpectedExecutionDigest(
            actual = sample.digest,
            expected = expectedDigest,
            location = "parent measurement[$index]",
        )
    }
}

internal fun requireMacroOracle(expectedDigest: ExecutionDigest?): ExecutionDigest {
    val expected =
        requireNotNull(expectedDigest) {
            "Cold/warm macro plans require a non-null expectedDigest oracle"
        }
    require(expected.executedSteps >= 0) { "expectedDigest.executedSteps must not be negative" }
    require(expected.failureCount == 0) { "expectedDigest.failureCount must be zero" }
    return expected
}

internal fun requireDistinctProcessIds(observations: List<MetricObservation>, label: String) {
    check(observations.map(MetricObservation::processId).distinct().size == observations.size) {
        "$label require one distinct process per fork"
    }
}

internal const val TARGET_FORK_MAIN: String =
    "com.salesforce.revoman.benchmark.driver.process.TargetForkMainKt"
internal const val COLD_LATENCY_PROVIDER: String = "parent-process-wall-time/v1"

private fun allocationArtifactId(position: ColdPosition): String =
    "cold-allocation-block-${position.blockId}-role-${position.targetRole.name.lowercase()}-" +
        "fork-${position.fork}.jfr"

private fun coldProviderConfigurationSha256(
    plan: ColdPlan,
    baseProviderConfigurationSha256: String,
): String =
    ContentHasher.sha256(
        listOf(
                "revoman-cold-provider-run/v2",
                baseProviderConfigurationSha256,
                plan.metricPass.name,
                RunMode.COLD.name,
                plan.timeout.toNanos().toString(),
                plan.loggingConfiguration.sha256,
            )
            .joinToString("\u0000")
            .toByteArray(UTF_8)
    )

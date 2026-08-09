/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.MetricUnit
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.process.JavaCommand
import com.salesforce.revoman.benchmark.driver.process.ProcessLauncher
import com.salesforce.revoman.benchmark.driver.process.ProcessObservation
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Configuration for a fresh-JVM cold latency campaign. */
data class ColdPlan(
    val intent: RunIntent,
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val workload: WorkloadRequest,
    val sampleCount: Int,
    val metricPass: MetricPass,
    val timeout: Duration,
    val loggingConfiguration: Path,
)

/** Runs each cold observation in a distinct target process. */
class ColdRunner(private val launcher: ProcessLauncher) {
    /** Executes [plan] with one measured child process per returned observation. */
    fun run(plan: ColdPlan): List<MetricObservation> {
        validate(plan)
        val campaign =
            RunnerCampaign.open(
                expectedTarget = plan.target,
                targetManifestPath = plan.targetManifestPath,
                loggingConfiguration = plan.loggingConfiguration,
            )
        return campaign.withPostflight {
            val observations =
                List(plan.sampleCount) { sample ->
                    val process =
                        campaign.launch(
                            launcher = launcher,
                            adapterId = plan.adapterId,
                            workload = plan.workload,
                            mode = RunMode.COLD,
                            metricPass = plan.metricPass,
                            warmupIterations = 0,
                            measurementIterations = 1,
                            timeout = plan.timeout,
                        )
                    validateProcess(process, warmupIterations = 0, measurementIterations = 1)
                    MetricObservation(
                        targetId = plan.target.targetId,
                        metric = MetricId.LATENCY,
                        provider = COLD_LATENCY_PROVIDER,
                        unit = MetricUnit.NANOSECONDS,
                        fork = sample,
                        iteration = 0,
                        processId = process.processId,
                        value = process.elapsedNanos.toDouble(),
                    )
                }
            requireDistinctProcessIds(observations, "cold samples")
            observations
        }
    }

    private fun validate(plan: ColdPlan) {
        require(plan.sampleCount > 0) { "Cold sampleCount must be positive" }
        require(plan.intent != RunIntent.CONTROLLED || plan.sampleCount >= MIN_CONTROLLED_COLD_SAMPLES) {
            "Controlled cold plans require at least $MIN_CONTROLLED_COLD_SAMPLES samples"
        }
        validateCommon(plan.adapterId, plan.metricPass, plan.timeout)
    }
}

internal class RunnerCampaign private constructor(
    private val verified: VerifiedTargetManifest,
    private val targetManifestPath: Path,
    private val loggingConfiguration: Path,
    private val javaExecutable: Path,
    private val classpath: List<Path>,
    private val workingDirectory: Path,
) {
    fun launch(
        launcher: ProcessLauncher,
        adapterId: String,
        workload: WorkloadRequest,
        mode: RunMode,
        metricPass: MetricPass,
        warmupIterations: Int,
        measurementIterations: Int,
        timeout: Duration,
    ): ProcessObservation {
        val invocationDirectory = Files.createTempDirectory("revoman-target-fork-").toRealPath()
        val commandPath = invocationDirectory.resolve("command.json")
        val resultPath = invocationDirectory.resolve("result.json")
        val workerCommand =
            TargetForkCommand(
                verification =
                    TargetVerificationToken(
                        targetManifest = targetManifestPath.toString(),
                        targetManifestSha256 = verified.manifestSha256,
                        targetClasspathSha256 = verified.classpathSha256,
                        artifactStamps = verified.artifactStamps,
                    ),
                adapterId = adapterId,
                mode = mode,
                metricPass = metricPass,
                workload = workload,
                warmupIterations = warmupIterations,
                measurementIterations = measurementIterations,
                resultFile = resultPath.toString(),
            )
        BenchmarkJson.write(commandPath, workerCommand)
        return try {
            launcher.launch(
                JavaCommand(
                    executable = javaExecutable,
                    jvmArgs =
                        listOf(
                            "-Dlog4j2.configurationFile=${loggingConfiguration.toUri()}",
                            "-Dlog4j2.*.Configuration.file=${loggingConfiguration.toUri()}",
                            "-Dkotlin-logging.logStartupMessage=false",
                            "-Drevoman.banner=off",
                        ),
                    classpath = classpath,
                    mainClass = TARGET_FORK_MAIN,
                    programArgs = listOf(commandPath.toString()),
                    workingDirectory = workingDirectory,
                    timeout = timeout,
                )
            )
        } finally {
            Files.deleteIfExists(resultPath)
            Files.deleteIfExists(commandPath)
            Files.deleteIfExists(invocationDirectory)
        }
    }

    fun <T> withPostflight(block: () -> T): T {
        var primaryFailure: Throwable? = null
        return try {
            block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                verified.postflight()
            } catch (postflightFailure: Throwable) {
                primaryFailure?.let { primary ->
                    if (primary !== postflightFailure) primary.addSuppressed(postflightFailure)
                } ?: throw postflightFailure
            }
        }
    }

    companion object {
        fun open(
            expectedTarget: TargetManifest,
            targetManifestPath: Path,
            loggingConfiguration: Path,
        ): RunnerCampaign {
            val manifestPath = requiredCanonicalFile("targetManifestPath", targetManifestPath)
            require(BenchmarkJson.read<TargetManifest>(manifestPath) == expectedTarget) {
                "Plan target does not match targetManifestPath at $manifestPath"
            }
            val logging = requiredCanonicalFile("loggingConfiguration", loggingConfiguration)
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
            val verified = VerifiedTargetManifest.preflight(manifestPath)
            return RunnerCampaign(
                verified = verified,
                targetManifestPath = manifestPath,
                loggingConfiguration = logging,
                javaExecutable = executable,
                classpath = classpath,
                workingDirectory = workingDirectory,
            )
        }

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

internal fun validateCommon(adapterId: String, metricPass: MetricPass, timeout: Duration) {
    require(adapterId.isNotBlank()) { "adapterId must not be blank" }
    require(metricPass == MetricPass.LATENCY) { "Task 6 runners support only LATENCY" }
    require(!timeout.isZero && !timeout.isNegative) { "Runner timeout must be positive" }
}

internal fun validateProcess(
    process: ProcessObservation,
    warmupIterations: Int,
    measurementIterations: Int,
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
    result.samples.forEach { sample ->
        check(sample.latencyNanos >= 0) { "Target result sample latency must not be negative" }
        check(sample.digest.executedSteps >= 0) {
            "Target result digest executedSteps must not be negative"
        }
        check(sample.digest.failureCount >= 0) {
            "Target result digest failureCount must not be negative"
        }
    }
}

internal fun requireDistinctProcessIds(observations: List<MetricObservation>, label: String) {
    check(observations.map(MetricObservation::processId).distinct().size == observations.size) {
        "$label require one distinct process per fork"
    }
}

internal const val TARGET_FORK_MAIN: String =
    "com.salesforce.revoman.benchmark.driver.process.TargetForkMainKt"
internal const val COLD_LATENCY_PROVIDER: String = "parent-process-wall-time/v1"
internal const val MIN_CONTROLLED_COLD_SAMPLES: Int = 50

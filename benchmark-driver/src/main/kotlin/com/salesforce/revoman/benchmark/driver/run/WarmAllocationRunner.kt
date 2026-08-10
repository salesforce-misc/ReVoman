/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.fixture.DeterministicHttpFixture
import com.salesforce.revoman.benchmark.driver.jmh.FIXTURE_ROOT_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.JMH_CAMPAIGN_CONTEXT_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.LIFECYCLE_BASE_URL_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.LIFECYCLE_MANIFEST_SHA256_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.LIFECYCLE_SOURCE_ROOT_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.TARGET_TOKEN_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.TARGET_TOKEN_SHA256_PROPERTY
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.metrics.JmhGcResultImporter
import com.salesforce.revoman.benchmark.driver.metrics.WARM_LIFECYCLE_ALLOCATION_INCLUDE
import com.salesforce.revoman.benchmark.driver.model.MetricObservation
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.model.requireExpectedExecutionDigest
import com.salesforce.revoman.benchmark.driver.process.JavaCommand
import com.salesforce.revoman.benchmark.driver.process.JmhControllerObservation
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** One scheduler-supplied warm allocation launch for one role in one accepted host block. */
data class WarmAllocationPlan(
    val intent: RunIntent,
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val benchmarkClassesJar: Path,
    val controllerClasspath: List<Path>,
    val targetClasspath: List<Path>,
    val installationRoot: Path,
    val outputDirectory: Path,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val timeout: Duration,
    val loggingConfiguration: VerifiedLoggingConfiguration,
    val iterationDuration: Duration = Duration.ofSeconds(1),
    val fixtureRoot: Path? = null,
    val fixtureBaseUrl: String? = null,
    val expectedJmhEvidence: JmhEvidenceExpectation? = null,
    val verifiedTarget: VerifiedTargetManifest? = null,
    val loggingSnapshot: Path? = null,
)

/** Immutable strict-JMH command plus coordinates supplied by Task 8/11. */
data class WarmAllocationLaunch(
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
    val forkCount: Int,
    val profilers: List<String>,
    val benchmarkIncludes: List<String>,
    val targetClasspath: List<Path>,
    val command: JavaCommand,
    val rawResult: Path,
    val normalizedResult: Path,
    val humanOutput: Path,
)

/** System boundary used by Task 11's lifecycle-safe JMH controller process launcher. */
fun interface WarmAllocationLauncher {
    fun launch(request: WarmAllocationLaunch): JmhControllerObservation
}

/** Warm allocation evidence ready for Task 11 to attach to its actual paired block. */
data class WarmAllocationResult(
    val blockId: Int,
    val targetRole: TargetRole,
    val fork: Int,
    val provider: String,
    val providerConfigurationSha256: String,
    val artifacts: List<HashedArtifact>,
    val observations: List<MetricObservation>,
)

/** Launches one strict lifecycle JMH controller without taking ownership of block scheduling. */
class WarmAllocationRunner(private val launcher: WarmAllocationLauncher) {
    /** Executes one supplied target position with exactly one raw JMH fork and the GC profiler. */
    fun run(plan: WarmAllocationPlan): WarmAllocationResult {
        validate(plan)
        val ownsSession = plan.verifiedTarget == null
        val verified =
            plan.verifiedTarget
                ?: VerifiedTargetManifest.preflight(plan.targetManifestPath, plan.target)
        var campaignDirectory: Path? = null
        var primary: Throwable? = null
        try {
            val loggingSnapshot =
                plan.loggingSnapshot
                    ?: Files.createTempDirectory("revoman-warm-allocation-")
                        .toRealPath()
                        .also { campaignDirectory = it }
                        .let(plan.loggingConfiguration::materialize)
            val launch = launchRequest(plan, loggingSnapshot)
            val process = launcher.launch(launch)
            validateController(process, launch, plan.expectedJmhEvidence != null)
            val imported =
                JmhGcResultImporter.import(
                    rawResult = launch.rawResult,
                    targetId = plan.target.targetId,
                    blockId = plan.blockId,
                    targetRole = plan.targetRole,
                    fork = plan.fork,
                )
            val observations =
                plan.expectedJmhEvidence?.let { expectation ->
                    normalizedAllocationObservations(
                        result =
                            NormalizedJmhEvidenceVerifier.verify(
                                launch.normalizedResult.toRealPath(),
                                expectation,
                            ),
                        plan = plan,
                        imported = imported.observations,
                    )
                } ?: imported.observations
            return WarmAllocationResult(
                blockId = plan.blockId,
                targetRole = plan.targetRole,
                fork = plan.fork,
                provider = imported.provider,
                providerConfigurationSha256 = providerConfigurationSha256(plan, imported.providerConfigurationSha256),
                artifacts =
                    if (plan.expectedJmhEvidence == null) emptyList()
                    else warmAllocationArtifacts(plan, launch),
                observations = observations,
            )
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var finalizationFailure = primary
            val finalizers =
                if (ownsSession) {
                    listOf<() -> Unit>(
                        verified::postflight,
                        {
                            campaignDirectory?.let { directory ->
                                plan.loggingConfiguration.postflight(
                                    directory.resolve("log4j2-benchmark.xml")
                                )
                            }
                        },
                        { campaignDirectory?.let(::deleteWarmAllocationDirectory) },
                    )
                } else {
                    emptyList()
                }
            finalizers
                .forEach { finalizer ->
                    try {
                        finalizer()
                    } catch (finalizerFailure: Throwable) {
                        finalizationFailure =
                            finalizationFailure?.also { failure ->
                                if (failure !== finalizerFailure) failure.addSuppressed(finalizerFailure)
                            } ?: finalizerFailure
                    }
                }
            if (primary == null) finalizationFailure?.let { throw it }
        }
    }

    private fun launchRequest(plan: WarmAllocationPlan, loggingSnapshot: Path): WarmAllocationLaunch {
        val rawResult = plan.outputDirectory.resolve("jmh-raw.json")
        val normalizedResult = plan.outputDirectory.resolve("revoman-benchmark-jmh-v1.json")
        val humanOutput = plan.outputDirectory.resolve("jmh-output.txt")
        listOf(rawResult, normalizedResult, humanOutput).forEach { output ->
            require(!Files.exists(output)) { "Warm allocation output already exists: $output" }
        }
        val javaExecutable = Path.of(System.getProperty("java.home")).toRealPath().resolve("bin/java")
        val loggingUri = loggingSnapshot.toUri()
        val iterationTime = "${plan.iterationDuration.toMillis()}ms"
        val includes = listOf(WARM_LIFECYCLE_ALLOCATION_INCLUDE)
        val profilers = listOf("gc")
        val campaignJvmArguments = campaignJvmArguments(plan)
        val command =
            JavaCommand(
                executable = javaExecutable,
                jvmArgs =
                    listOf(
                        "-Drevoman.benchmark.rawJmhOutput=$rawResult",
                        "-Drevoman.benchmark.resultOutput=$normalizedResult",
                        "-Drevoman.benchmark.targetManifest=${plan.targetManifestPath}",
                        "-Drevoman.benchmark.adapter=${plan.adapterId}",
                        "-Drevoman.benchmark.includes=${includes.single()}",
                        "-Drevoman.benchmark.installationRoot=${plan.installationRoot}",
                        "-Drevoman.benchmark.requestedForks=1",
                        "-Drevoman.benchmark.profilers=gc",
                        "-Drevoman.benchmark.quick=${plan.intent != RunIntent.CONTROLLED}",
                        "-Dlog4j2.configurationFile=$loggingUri",
                        "-Dlog4j2.*.Configuration.file=$loggingUri",
                        "-Dkotlin-logging.logStartupMessage=false",
                        "-Drevoman.banner=off",
                    ) + campaignJvmArguments,
                classpath = plan.controllerClasspath,
                mainClass = JMH_DRIVER_MAIN,
                programArgs =
                    listOf(
                        WARM_LIFECYCLE_ALLOCATION_INCLUDE,
                        "-rf",
                        "json",
                        "-rff",
                        rawResult.toString(),
                        "-o",
                        humanOutput.toString(),
                        "-f",
                        "1",
                        "-prof",
                        "gc",
                        "-wi",
                        plan.warmupIterations.toString(),
                        "-i",
                        plan.measurementIterations.toString(),
                        "-w",
                        iterationTime,
                        "-r",
                        iterationTime,
                    ),
                workingDirectory = plan.outputDirectory,
                timeout = plan.timeout,
            )
        plan.fixtureRoot?.takeIf { plan.verifiedTarget == null }?.let { fixture ->
            val insertion = command.jvmArgs.indexOfFirst { it.startsWith("-Dlog4j2.configurationFile=") }
            val withFixture =
                command.copy(
                    jvmArgs =
                        command.jvmArgs.toMutableList().apply {
                            add(
                                if (insertion >= 0) insertion else size,
                                "-D$LIFECYCLE_SOURCE_ROOT_PROPERTY=$fixture",
                            )
                        }
                )
            return launch(
                plan = plan,
                command = withFixture,
                rawResult = rawResult,
                normalizedResult = normalizedResult,
                humanOutput = humanOutput,
                profilers = profilers,
                includes = includes,
            )
        }
        return launch(plan, command, rawResult, normalizedResult, humanOutput, profilers, includes)
    }

    private fun campaignJvmArguments(plan: WarmAllocationPlan): List<String> {
        val verified = plan.verifiedTarget ?: return emptyList()
        val fixtureRoot = requireNotNull(plan.fixtureRoot)
        val fixtureBaseUrl = requireNotNull(plan.fixtureBaseUrl)
        val tokenPath = plan.outputDirectory.resolve("target-verification-token.json")
        val contextPath = plan.outputDirectory.resolve("campaign-jmh-context.json")
        require(!Files.exists(tokenPath) && !Files.exists(contextPath)) {
            "Campaign JMH identity files must be absent"
        }
        BenchmarkJson.write(tokenPath, verified.verificationToken())
        check(tokenPath.toFile().setReadOnly()) { "Cannot make campaign JMH target token read-only" }
        val contextArgument =
            plan.expectedJmhEvidence?.let { expectation ->
                BenchmarkJson.write(contextPath, expectation)
                listOf("-D$JMH_CAMPAIGN_CONTEXT_PROPERTY=${contextPath.toRealPath()}")
            }.orEmpty()
        val manifestSha256 =
            plan.expectedJmhEvidence?.workload?.manifestSha256
                ?: ContentHasher.sha256(fixtureRoot.resolve("manifest.json"))
        return listOf(
            "-D$TARGET_TOKEN_PROPERTY=${tokenPath.toRealPath()}",
            "-D$TARGET_TOKEN_SHA256_PROPERTY=${ContentHasher.sha256(tokenPath)}",
            "-D$FIXTURE_ROOT_PROPERTY=$fixtureRoot",
            "-D$LIFECYCLE_BASE_URL_PROPERTY=$fixtureBaseUrl",
            "-D$LIFECYCLE_MANIFEST_SHA256_PROPERTY=$manifestSha256",
        ) + contextArgument
    }

    private fun launch(
        plan: WarmAllocationPlan,
        command: JavaCommand,
        rawResult: Path,
        normalizedResult: Path,
        humanOutput: Path,
        profilers: List<String>,
        includes: List<String>,
    ): WarmAllocationLaunch =
        WarmAllocationLaunch(
            blockId = plan.blockId,
            targetRole = plan.targetRole,
            fork = plan.fork,
            forkCount = 1,
            profilers = profilers,
            benchmarkIncludes = includes,
            targetClasspath = plan.targetClasspath,
            command = command,
            rawResult = rawResult,
            normalizedResult = normalizedResult,
            humanOutput = humanOutput,
        )

    private fun validate(plan: WarmAllocationPlan) {
        require(plan.blockId >= 0) { "Warm allocation blockId must not be negative" }
        require(plan.fork >= 0) { "Warm allocation fork must not be negative" }
        require(plan.adapterId.isNotBlank()) { "Warm allocation adapterId must not be blank" }
        require(plan.warmupIterations >= 0) {
            "Warm allocation warmupIterations must not be negative"
        }
        require(plan.measurementIterations > 0) {
            "Warm allocation measurementIterations must be positive"
        }
        require(!plan.timeout.isZero && !plan.timeout.isNegative) {
            "Warm allocation timeout must be positive"
        }
        require(!plan.iterationDuration.isZero && !plan.iterationDuration.isNegative) {
            "Warm allocation iterationDuration must be positive"
        }
        require(plan.iterationDuration.toMillis() > 0) {
            "Warm allocation iterationDuration must be at least one millisecond"
        }
        requireCanonicalFile("benchmarkClassesJar", plan.benchmarkClassesJar)
        require(plan.benchmarkClassesJar.fileName.toString() == "benchmark-driver-jmh-classes.jar") {
            "Warm allocation requires the fixed thin benchmark classes JAR"
        }
        require(plan.controllerClasspath.isNotEmpty()) {
            "Warm allocation controller classpath must not be empty"
        }
        plan.controllerClasspath.forEachIndexed { index, path ->
            requireCanonicalFile("controllerClasspath[$index]", path)
        }
        require(plan.benchmarkClassesJar in plan.controllerClasspath) {
            "Warm allocation controller classpath must contain the thin benchmark classes JAR"
        }
        val expectedTargetClasspath = plan.target.classpath.map { Path.of(it.executionPath) }
        require(plan.targetClasspath == expectedTargetClasspath) {
            "Warm allocation target classpath must preserve the supplied manifest order"
        }
        require(plan.targetClasspath.none { path -> path.fileName.toString().endsWith("-jmh.jar") }) {
            "Warm allocation target classpath must not contain an uber JMH JAR"
        }
        requireCanonicalDirectory("installationRoot", plan.installationRoot)
        requireCanonicalDirectory("outputDirectory", plan.outputDirectory)
        plan.fixtureRoot?.let { requireCanonicalDirectory("fixtureRoot", it) }
        require((plan.verifiedTarget == null) == (plan.loggingSnapshot == null)) {
            "Campaign-owned warm allocation requires both verifiedTarget and loggingSnapshot"
        }
        plan.verifiedTarget?.let { verified ->
            require(verified.manifest == plan.target && verified.manifestPath == plan.targetManifestPath) {
                "Campaign-owned target verification must match the warm allocation plan"
            }
            require(plan.fixtureRoot != null && !plan.fixtureBaseUrl.isNullOrBlank()) {
                "Campaign-owned warm allocation requires the parent fixture root and base URL"
            }
        }
        plan.loggingSnapshot?.let { snapshot -> requireCanonicalFile("loggingSnapshot", snapshot) }
    }
}

internal fun executeWarmLifecycleAllocation(
    prepared: PreparedWorkload,
    expectedDigest: ExecutionDigest,
): Long =
    requireExpectedExecutionDigest(
            actual = prepared.execute(),
            expected = expectedDigest,
            location = "warm lifecycle allocation",
        )
        .checksum

internal fun loadWarmLifecycleExpectedDigest(
    fixtureRoot: Path,
    expectedManifestSha256: String,
): ExecutionDigest {
    requireCanonicalDirectory("warm lifecycle fixtureRoot", fixtureRoot)
    val manifestPath = fixtureRoot.resolve("manifest.json")
    val manifestBytes = Files.readAllBytes(manifestPath)
    val actualManifestSha256 = ContentHasher.sha256(manifestBytes)
    require(actualManifestSha256 == expectedManifestSha256) {
        "Warm lifecycle manifest SHA-256 mismatch: " +
            "expected=$expectedManifestSha256, actual=$actualManifestSha256"
    }
    val manifest = BenchmarkJson.decode<WorkloadManifest>(manifestBytes, manifestPath.toString())
    require(manifest.id == WARM_LIFECYCLE_WORKLOAD_ID) {
        "Warm lifecycle allocation requires workload $WARM_LIFECYCLE_WORKLOAD_ID, actual=${manifest.id}"
    }
    require(manifest.contractVersion == WARM_LIFECYCLE_CONTRACT_VERSION) {
        "Warm lifecycle allocation requires contract version $WARM_LIFECYCLE_CONTRACT_VERSION, " +
            "actual=${manifest.contractVersion}"
    }
    DeterministicHttpFixture.verifyFixture(manifest, fixtureRoot)
    return requireNotNull(manifest.expectedDigest) {
        "Warm lifecycle allocation requires a non-null expectedDigest oracle"
    }
}

private fun validateController(
    process: JmhControllerObservation,
    launch: WarmAllocationLaunch,
    requireNormalizedResult: Boolean,
) {
    check(process.exitCode == 0) { "JMH controller exited with code ${process.exitCode}" }
    check(process.processId > 0) { "JMH controller process ID must be positive" }
    check(process.stdoutTail.isEmpty()) { "JMH controller emitted stdout: ${process.stdoutTail}" }
    check(process.stderrTail.isEmpty()) { "JMH controller emitted stderr: ${process.stderrTail}" }
    check(Files.isRegularFile(launch.rawResult) && Files.size(launch.rawResult) > 2) {
        "JMH controller omitted raw allocation evidence: ${launch.rawResult}"
    }
    if (requireNormalizedResult) {
        check(Files.isRegularFile(launch.normalizedResult) && Files.size(launch.normalizedResult) > 2) {
            "JMH controller omitted normalized allocation evidence: ${launch.normalizedResult}"
        }
    }
}

private fun normalizedAllocationObservations(
    result: com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1,
    plan: WarmAllocationPlan,
    imported: List<MetricObservation>,
): List<MetricObservation> {
    val benchmark =
        result.benchmarks.singleOrNull()
            ?: throw IllegalArgumentException("Warm allocation normalized JMH evidence must contain one row")
    require(benchmark.name.contains(WARM_LIFECYCLE_ALLOCATION_INCLUDE)) {
        "Warm allocation normalized JMH evidence contains the wrong benchmark: ${benchmark.name}"
    }
    require(benchmark.forks == 1) { "Warm allocation normalized JMH evidence must contain one fork" }
    require(benchmark.warmupIterations == plan.warmupIterations) {
        "Warm allocation normalized JMH warmup count differs from the scheduled plan"
    }
    require(benchmark.measurementIterations == plan.measurementIterations) {
        "Warm allocation normalized JMH measurement count differs from the scheduled plan"
    }
    val observations =
        benchmark.metricSeries
            .singleOrNull { series ->
                series.metric == com.salesforce.revoman.benchmark.driver.model.MetricId.ALLOCATED_BYTES &&
                    series.unit == com.salesforce.revoman.benchmark.driver.model.MetricUnit.BYTES_PER_OPERATION
            }
            ?.rawObservations
            ?: throw IllegalArgumentException(
                "Warm allocation normalized JMH evidence requires raw allocation observations"
            )
    require(observations == imported.map { observation -> observation.copy(fork = 0) }) {
        "Normalized and raw warm allocation observations differ"
    }
    return observations.map { observation -> observation.copy(fork = plan.fork) }
}

private fun warmAllocationArtifacts(
    plan: WarmAllocationPlan,
    launch: WarmAllocationLaunch,
): List<HashedArtifact> {
    val prefix =
        "warm-allocation-block-${plan.blockId}-role-${plan.targetRole.name.lowercase()}-" +
            "fork-${plan.fork}"
    return listOf(
            "$prefix-raw.json" to launch.rawResult,
            "$prefix-normalized.json" to launch.normalizedResult,
            "$prefix-output.txt" to launch.humanOutput,
        )
        .map { (logicalId, path) ->
            require(Files.isRegularFile(path)) { "Warm allocation artifact is missing: $path" }
            HashedArtifact(
                logicalId = logicalId,
                executionPath = path.toRealPath().toString(),
                sizeBytes = Files.size(path),
                sha256 = ContentHasher.sha256(path),
            )
        }
}

private fun providerConfigurationSha256(
    plan: WarmAllocationPlan,
    importedConfigurationSha256: String,
): String =
    ContentHasher.sha256(
        listOf(
                "revoman-warm-allocation-run/v1",
                importedConfigurationSha256,
                plan.loggingConfiguration.sha256,
                plan.warmupIterations.toString(),
                plan.measurementIterations.toString(),
                plan.iterationDuration.toNanos().toString(),
                plan.timeout.toNanos().toString(),
                "forks=1",
                "profiler=gc",
            )
            .joinToString("\u0000")
            .toByteArray(UTF_8)
    )

private fun requireCanonicalFile(name: String, path: Path) {
    require(path.isAbsolute && path.normalize() == path && Files.isRegularFile(path)) {
        "$name must be an absolute normalized regular file: $path"
    }
    require(path.toRealPath() == path) { "$name must be canonical: $path" }
}

private fun requireCanonicalDirectory(name: String, path: Path) {
    require(path.isAbsolute && path.normalize() == path && Files.isDirectory(path)) {
        "$name must be an absolute normalized directory: $path"
    }
    require(path.toRealPath() == path) { "$name must be canonical: $path" }
}

private fun deleteWarmAllocationDirectory(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

private const val JMH_DRIVER_MAIN: String =
    "com.salesforce.revoman.benchmark.driver.jmh.JmhDriverMainKt"
private const val WARM_LIFECYCLE_WORKLOAD_ID: String = "lifecycle.no-script-one-step.v1"
private const val WARM_LIFECYCLE_CONTRACT_VERSION: Int = 1

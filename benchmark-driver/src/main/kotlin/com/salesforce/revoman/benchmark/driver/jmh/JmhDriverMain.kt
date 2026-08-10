/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.salesforce.revoman.benchmark.driver.integrity.BuildIdentity
import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.integrity.HarnessSourceManifest
import com.salesforce.revoman.benchmark.driver.fixture.DeterministicHttpFixture
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ArtifactSnapshot
import com.salesforce.revoman.benchmark.driver.model.EnvironmentIdentity
import com.salesforce.revoman.benchmark.driver.model.HarnessIdentity
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.JmhLoggingConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhWorkloadIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.metrics.WARM_LIFECYCLE_ALLOCATION_INCLUDE
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions
import org.openjdk.jmh.runner.options.Options
import org.openjdk.jmh.runner.options.OptionsBuilder

internal fun runJmh(
    args: Array<String>,
    execute: (Options) -> Collection<RunResult> = { Runner(it).run() },
): Collection<RunResult> {
    val commandLine = CommandLineOptions(*args)
    val options =
        OptionsBuilder()
            .parent(commandLine)
            .shouldFailOnError(true)
            .addProfiler(ForkPidProfiler::class.java)
            .jvmArgsAppend(*forkJvmArguments().toTypedArray())
            .build()
    return execute(options).also { check(it.isNotEmpty()) { "JMH produced no result rows" } }
}

/** Strict JMH controller entry point; all runner, option, import, and postflight failures escape. */
fun main(args: Array<String>) {
    val rawResult = requiredPath(RAW_RESULT_PROPERTY)
    val resultOutput = requiredPath(RESULT_OUTPUT_PROPERTY)
    val manifestPath = requiredPath(TARGET_MANIFEST_PROPERTY).toRealPath()
    val requestedIncludes =
        requiredProperty(INCLUDES_PROPERTY).split(INCLUDE_SEPARATOR).filter(String::isNotBlank)
    val installationRoot = requiredPath(INSTALLATION_ROOT_PROPERTY).toRealPath()
    val verified = VerifiedTargetManifest.preflight(manifestPath)
    val normalizedResult =
        withLifecycleFixture(requestedIncludes, installationRoot) { lifecycleWorkloadIdentity ->
            withJmhPostflight(verified::postflight) {
                val tokenPath = rawResult.resolveSibling("${rawResult.fileName}.target-token.json")
                writeReadOnlyToken(tokenPath, verified)
                System.setProperty(TARGET_TOKEN_PROPERTY, tokenPath.toRealPath().toString())
                System.setProperty(TARGET_TOKEN_SHA256_PROPERTY, ContentHasher.sha256(tokenPath))
                System.setProperty(TARGET_MANIFEST_PROPERTY, manifestPath.toString())

                runJmh(args)
                val imported =
                    JmhResultImporter.`import`(
                        rawResult = rawResult,
                        targetId = verified.manifest.targetId,
                        requestedIncludes = requestedIncludes,
                    )
                attachRuntimeIdentities(
                    imported = imported,
                    verified = verified,
                    requestedIncludes = requestedIncludes,
                    installationRoot = installationRoot,
                    lifecycleWorkloadIdentity = lifecycleWorkloadIdentity,
                )
            }
        }
    writeJmhResult(resultOutput, normalizedResult)
}

internal fun <T> withJmhPostflight(postflight: () -> Unit, block: () -> T): T {
    var primary: Throwable? = null
    return try {
        block()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            postflight()
        } catch (postflightFailure: Throwable) {
            primary?.let { failure ->
                if (failure !== postflightFailure) failure.addSuppressed(postflightFailure)
            } ?: throw postflightFailure
        }
    }
}

internal fun <T> withLifecycleFixture(
    requestedIncludes: List<String>,
    installationRoot: Path,
    hooks: LifecycleFixtureHooks = LifecycleFixtureHooks.NONE,
    block: (JmhWorkloadIdentity?) -> T,
): T {
    val lifecycleSelected =
        requestedIncludes.any { include -> include.contains(WARM_LIFECYCLE_ALLOCATION_INCLUDE) }
    if (!lifecycleSelected) return block(null)
    require(
        requestedIncludes.all { include -> include.contains(WARM_LIFECYCLE_ALLOCATION_INCLUDE) }
    ) {
        "Warm lifecycle allocation must run in a dedicated JMH controller launch"
    }
    val sourceRoot =
        installationRoot
            .resolve("workloads/v1/lifecycle.no-script-one-step.v1")
            .toRealPath()
    val snapshot = VerifiedLifecycleWorkloadSnapshot.open(sourceRoot)
    val outcome =
        runCatching {
            DeterministicHttpFixture.open(snapshot.manifest, snapshot.snapshotRoot).use { fixture ->
                fixture.resetExecution("warm-lifecycle-allocation")
                System.setProperty(FIXTURE_ROOT_PROPERTY, snapshot.snapshotRoot.toString())
                System.setProperty(LIFECYCLE_BASE_URL_PROPERTY, fixture.baseUrl)
                System.setProperty(
                    LIFECYCLE_MANIFEST_SHA256_PROPERTY,
                    snapshot.manifestSha256,
                )
                hooks.afterFixtureStarted(snapshot)
                block(snapshot.workloadIdentity)
            }
        }
    var failure = outcome.exceptionOrNull()
    listOf<() -> Unit>(
            snapshot::postflightSource,
            snapshot::postflightSnapshot,
            snapshot::close,
        )
        .forEach { finalizer ->
            try {
                finalizer()
            } catch (finalizerFailure: Throwable) {
                failure = mergeLifecycleFailures(failure, finalizerFailure)
            }
        }
    failure?.let { throw it }
    return outcome.getOrThrow()
}

internal fun interface LifecycleFixtureHooks {
    fun afterFixtureStarted(snapshot: VerifiedLifecycleWorkloadSnapshot)

    companion object {
        val NONE: LifecycleFixtureHooks = LifecycleFixtureHooks {}
    }
}

internal fun writeJmhResult(output: Path, result: JmhBenchmarkResultV1) {
    result.validate()
    val parent = requireNotNull(output.parent) { "JMH result output needs a parent: $output" }
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".${output.fileName}.", ".tmp")
    try {
        BenchmarkJson.write(temporary, result)
        BenchmarkJson.validateSchema(temporary, "/schema/revoman-benchmark-jmh-v1.schema.json")
        Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun attachRuntimeIdentities(
    imported: ImportedJmhResult,
    verified: VerifiedTargetManifest,
    requestedIncludes: List<String>,
    installationRoot: Path,
    lifecycleWorkloadIdentity: JmhWorkloadIdentity?,
): JmhBenchmarkResultV1 {
    val sourceManifest =
        BenchmarkJson.read<HarnessSourceManifest>(
            installationRoot.resolve("conf/benchmark-harness-source-v1.json")
        )
    val runtimeArtifacts = runtimeArtifacts(installationRoot)
    val harness =
        HarnessIdentity(
            commit = sourceManifest.commit,
            tree = sourceManifest.tree,
            dirty = sourceManifest.dirty,
            distributionSha256 = ContentHasher.artifactSetSha256(runtimeArtifacts),
            artifacts = runtimeArtifacts,
            workloadContractSha256 = sourceManifest.workloadContractSha256,
            fixtureSetSha256 = sourceManifest.fixtureSetSha256,
            adapters = sourceManifest.adapters,
        )
    val adapterId = requiredProperty(ADAPTER_PROPERTY)
    val adapter =
        requireNotNull(sourceManifest.adapters.singleOrNull { it.id == adapterId }) {
            "Installed harness source identity has no exact adapter: $adapterId"
        }
    val target =
        TargetIdentity(
            id = verified.manifest.targetId,
            gitCommit = verified.manifest.gitCommit,
            gitTree = verified.manifest.gitTree,
            dirty = verified.manifest.dirty,
            gradleVersion = verified.manifest.gradleVersion,
            wrapperSha256 = verified.manifest.wrapperSha256,
            buildJdk = verified.manifest.jdk,
            manifestSha256 = verified.manifestSha256,
            classpathSha256 = verified.classpathSha256,
            classpath =
                verified.manifest.classpath.map { artifact ->
                    ArtifactSnapshot(
                        logicalId = artifact.logicalId,
                        sizeBytes = artifact.sizeBytes,
                        sha256 = artifact.sha256,
                    )
                },
            adapter = adapter,
        )
    val workload = resolveJmhWorkloadIdentity(lifecycleWorkloadIdentity)
    val configuration =
        JmhRunConfiguration(
            requestedIncludes = requestedIncludes,
            requestedForks = requiredProperty(REQUESTED_FORKS_PROPERTY).toInt(),
            profilers =
                System.getProperty(PROFILERS_PROPERTY)
                    .orEmpty()
                    .split(',')
                    .filter(String::isNotBlank),
            internalProfilers = listOf(ForkPidProfiler::class.java.name),
            quick = requiredProperty(QUICK_PROPERTY).toBooleanStrict(),
            logging =
                jmhLoggingConfiguration(
                    log4j2Configuration = requiredFileUri(LOG_CONFIG_PROPERTY),
                    log4j3Configuration = requiredFileUri(LOG4J3_CONFIG_PROPERTY),
                    kotlinLoggingStartupMessage =
                        requiredProperty(KOTLIN_LOGGING_STARTUP_PROPERTY),
                    revomanBanner = requiredProperty(REVOMAN_BANNER_PROPERTY),
                ),
        )
    val createdAt = Instant.now().toString()
    return JmhResultImporter.attachIdentities(
        imported = imported,
        resultId = "jmh-${target.id}-${Instant.parse(createdAt).toEpochMilli()}",
        createdAt = createdAt,
        harness = harness,
        environment = currentEnvironment(),
        target = target,
        workload = workload,
        configuration = configuration,
    )
}

internal fun resolveJmhWorkloadIdentity(
    lifecycleWorkloadIdentity: JmhWorkloadIdentity?
): JmhWorkloadIdentity = lifecycleWorkloadIdentity ?: loadJmhWorkloadIdentity()

private fun loadJmhWorkloadIdentity(): JmhWorkloadIdentity {
    val fixtureRoot = requiredPath(FIXTURE_ROOT_PROPERTY).toRealPath()
    val workloadManifestPath = fixtureRoot.resolve("manifest.json")
    val manifestBytes = Files.readAllBytes(workloadManifestPath)
    val workloadManifest =
        BenchmarkJson.decode<WorkloadManifest>(manifestBytes, workloadManifestPath.toString())
    verifyWorkloadFiles(fixtureRoot, workloadManifest)
    return JmhWorkloadIdentity(
        manifestSha256 = ContentHasher.sha256(manifestBytes),
        manifest = workloadManifest,
    )
}

internal fun jmhLoggingConfiguration(
    log4j2Configuration: Path,
    log4j3Configuration: Path,
    kotlinLoggingStartupMessage: String,
    revomanBanner: String,
): JmhLoggingConfiguration =
    JmhLoggingConfiguration(
            log4j2ConfigurationFileSha256 = ContentHasher.sha256(log4j2Configuration),
            log4j2GlobalConfigurationFileSha256 = ContentHasher.sha256(log4j3Configuration),
            kotlinLoggingStartupMessage = kotlinLoggingStartupMessage,
            revomanBanner = revomanBanner,
        )
        .also(JmhLoggingConfiguration::validate)

private fun runtimeArtifacts(installationRoot: Path) =
    BuildIdentity.runtimeArtifacts(
        installationRoot,
        listOf("lib", "schema", "workloads", "jfr", "conf", "libexec")
            .map(installationRoot::resolve)
            .filter(Files::exists)
            .flatMap { root ->
                Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
            },
    )

private fun verifyWorkloadFiles(fixtureRoot: Path, manifest: WorkloadManifest) {
    val files =
        manifest.files.mapIndexed { index, artifact ->
            val executionPath = Path.of(artifact.executionPath)
            require(!executionPath.isAbsolute && executionPath.normalize() == executionPath) {
                "workload.files[$index].executionPath must be normalized and relative"
            }
            val path = fixtureRoot.resolve(executionPath).toRealPath()
            require(path.startsWith(fixtureRoot)) {
                "workload.files[$index] escapes fixture root: $path"
            }
            require(Files.size(path) == artifact.sizeBytes) {
                "workload.files[$index] size differs from manifest"
            }
            require(ContentHasher.sha256(path) == artifact.sha256) {
                "workload.files[$index] SHA-256 differs from manifest"
            }
            path
        }
    require(ContentHasher.treeSha256(fixtureRoot, files) == manifest.fixtureTreeSha256) {
        "workload fixture tree SHA-256 differs from manifest"
    }
}

private fun currentEnvironment(): EnvironmentIdentity {
    val osName = System.getProperty("os.name")
    val osVersion = System.getProperty("os.version")
    val cpuModel = System.getProperty("os.arch")
    val cpuCount = Runtime.getRuntime().availableProcessors()
    val physicalMemory =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize
            ?.takeIf { it > 0 }
            ?: Runtime.getRuntime().maxMemory()
    val fingerprint =
        ContentHasher.sha256(
            "$osName\u0000$osVersion\u0000$cpuModel\u0000$cpuCount\u0000$physicalMemory"
                .toByteArray(UTF_8)
        )
    return EnvironmentIdentity(
        jdk =
            JdkIdentity(
                distribution = System.getProperty("java.runtime.name"),
                vendor = System.getProperty("java.vendor"),
                fullVersion = System.getProperty("java.runtime.version"),
                javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().toString(),
                jvmFlags = ManagementFactory.getRuntimeMXBean().inputArguments.toList(),
            ),
        osName = osName,
        osVersion = osVersion,
        kernel = osVersion,
        cpuModel = cpuModel,
        cpuCount = cpuCount,
        governor = "unknown",
        physicalMemoryBytes = physicalMemory,
        hostFingerprintSha256 = fingerprint,
        policySha256 = null,
    )
}

private fun writeReadOnlyToken(
    tokenPath: Path,
    verified: VerifiedTargetManifest,
) {
    Files.createDirectories(requireNotNull(tokenPath.parent))
    if (Files.exists(tokenPath)) {
        check(tokenPath.toFile().setWritable(true)) { "Cannot replace prior JMH token: $tokenPath" }
        Files.delete(tokenPath)
    }
    BenchmarkJson.write(
        tokenPath,
        verified.verificationToken(),
    )
    check(tokenPath.toFile().setReadOnly()) { "Cannot make JMH target token read-only: $tokenPath" }
}

private fun forkJvmArguments(): List<String> =
    listOf(
            TARGET_TOKEN_PROPERTY,
            TARGET_TOKEN_SHA256_PROPERTY,
            TARGET_MANIFEST_PROPERTY,
            ADAPTER_PROPERTY,
            FIXTURE_ROOT_PROPERTY,
            LOG_CONFIG_PROPERTY,
            LOG4J3_CONFIG_PROPERTY,
            KOTLIN_LOGGING_STARTUP_PROPERTY,
            REVOMAN_BANNER_PROPERTY,
            LIFECYCLE_BASE_URL_PROPERTY,
            LIFECYCLE_MANIFEST_SHA256_PROPERTY,
        )
        .mapNotNull { name -> System.getProperty(name)?.let { value -> "-D$name=$value" } }

private fun requiredPath(name: String): Path = Path.of(requiredProperty(name)).toAbsolutePath().normalize()

private fun requiredFileUri(name: String): Path {
    val uri = URI.create(requiredProperty(name))
    require(uri.scheme == "file") { "System property $name must be an absolute file URI: $uri" }
    return Path.of(uri).toRealPath()
}

private fun requiredProperty(name: String): String =
    requireNotNull(System.getProperty(name)) { "Missing required system property: $name" }
        .also { require(it.isNotBlank()) { "System property $name must not be blank" } }

internal const val RAW_RESULT_PROPERTY: String = "revoman.benchmark.rawJmhOutput"
internal const val RESULT_OUTPUT_PROPERTY: String = "revoman.benchmark.resultOutput"
internal const val TARGET_MANIFEST_PROPERTY: String = "revoman.benchmark.targetManifest"
internal const val ADAPTER_PROPERTY: String = "revoman.benchmark.adapter"
internal const val FIXTURE_ROOT_PROPERTY: String = "revoman.benchmark.fixtureRoot"
internal const val LOG_CONFIG_PROPERTY: String = "log4j2.configurationFile"
internal const val LOG4J3_CONFIG_PROPERTY: String = "log4j2.*.Configuration.file"
internal const val KOTLIN_LOGGING_STARTUP_PROPERTY: String = "kotlin-logging.logStartupMessage"
internal const val REVOMAN_BANNER_PROPERTY: String = "revoman.banner"
internal const val TARGET_TOKEN_PROPERTY: String = "revoman.benchmark.targetToken"
internal const val TARGET_TOKEN_SHA256_PROPERTY: String = "revoman.benchmark.targetTokenSha256"
internal const val INCLUDES_PROPERTY: String = "revoman.benchmark.includes"
internal const val INSTALLATION_ROOT_PROPERTY: String = "revoman.benchmark.installationRoot"
internal const val REQUESTED_FORKS_PROPERTY: String = "revoman.benchmark.requestedForks"
internal const val PROFILERS_PROPERTY: String = "revoman.benchmark.profilers"
internal const val QUICK_PROPERTY: String = "revoman.benchmark.quick"
internal const val LIFECYCLE_BASE_URL_PROPERTY: String =
    "revoman.benchmark.lifecycleBaseUrl"
internal const val LIFECYCLE_MANIFEST_SHA256_PROPERTY: String =
    "revoman.benchmark.lifecycleManifestSha256"
internal const val INCLUDE_SEPARATOR: String = "\u001f"

private fun mergeLifecycleFailures(primary: Throwable?, next: Throwable): Throwable =
    primary?.also { existing ->
        if (existing !== next) existing.addSuppressed(next)
    } ?: next

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.integrity.RuntimeIdentityFactory
import com.salesforce.revoman.benchmark.driver.fixture.DeterministicHttpFixture
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ArtifactSnapshot
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.JmhLoggingConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhWorkloadIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.metrics.WARM_LIFECYCLE_ALLOCATION_INCLUDE
import com.salesforce.revoman.benchmark.driver.run.JmhEvidenceExpectation
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.net.URI
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
    val campaignContext = System.getProperty(JMH_CAMPAIGN_CONTEXT_PROPERTY)?.takeIf(String::isNotBlank)
    if (campaignContext != null) {
        val normalized =
            runCampaignOwnedJmh(
                args = args,
                rawResult = rawResult,
                manifestPath = manifestPath,
                requestedIncludes = requestedIncludes,
                contextPath = Path.of(campaignContext).toRealPath(),
            )
        writeJmhResult(resultOutput, normalized)
        return
    }
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

private fun runCampaignOwnedJmh(
    args: Array<String>,
    rawResult: Path,
    manifestPath: Path,
    requestedIncludes: List<String>,
    contextPath: Path,
): JmhBenchmarkResultV1 {
    require(Files.isRegularFile(contextPath)) { "Campaign JMH context must be a regular file" }
    val expectation = BenchmarkJson.read<JmhEvidenceExpectation>(contextPath)
    val tokenPath = requiredPath(TARGET_TOKEN_PROPERTY).toRealPath()
    val expectedTokenSha256 = requiredProperty(TARGET_TOKEN_SHA256_PROPERTY)
    require(ContentHasher.sha256(tokenPath) == expectedTokenSha256) {
        "Campaign JMH target token SHA-256 mismatch"
    }
    val verification = BenchmarkJson.read<TargetVerificationToken>(tokenPath)
    require(verification.targetManifest == manifestPath.toString()) {
        "Campaign JMH target token does not match the scheduled manifest"
    }
    val verified = VerifiedTargetManifest.fromVerificationToken(verification)
    requireCampaignExpectation(expectation, verified, requestedIncludes)

    runJmh(args)
    val imported =
        JmhResultImporter.`import`(
            rawResult = rawResult,
            targetId = expectation.target.id,
            requestedIncludes = requestedIncludes,
        )
    val createdAt = Instant.now().toString()
    return JmhResultImporter.attachIdentities(
        imported = imported,
        resultId = "jmh-${expectation.target.id}-${Instant.parse(createdAt).toEpochMilli()}",
        createdAt = createdAt,
        harness = expectation.harness,
        environment = expectation.environment,
        target = expectation.target,
        workload = expectation.workload,
        configuration = expectation.configuration,
    )
}

private fun requireCampaignExpectation(
    expectation: JmhEvidenceExpectation,
    verified: VerifiedTargetManifest,
    requestedIncludes: List<String>,
) {
    val adapterId = requiredProperty(ADAPTER_PROPERTY)
    val manifest = verified.manifest
    val actualTarget =
        TargetIdentity(
            id = manifest.targetId,
            gitCommit = manifest.gitCommit,
            gitTree = manifest.gitTree,
            dirty = manifest.dirty,
            gradleVersion = manifest.gradleVersion,
            wrapperSha256 = manifest.wrapperSha256,
            buildJdk = manifest.jdk,
            manifestSha256 = verified.manifestSha256,
            classpathSha256 = verified.classpathSha256,
            classpath =
                manifest.classpath.map { artifact ->
                    ArtifactSnapshot(artifact.logicalId, artifact.sizeBytes, artifact.sha256)
                },
            adapter = expectation.target.adapter,
        )
    require(actualTarget == expectation.target) {
        "Campaign JMH target identity does not match the parent verification"
    }
    require(adapterId == expectation.target.adapter.id) {
        "Campaign JMH adapter does not match the scheduled target role"
    }
    require(expectation.harness.adapters.singleOrNull { it.id == adapterId } == expectation.target.adapter) {
        "Campaign JMH harness does not contain the scheduled role adapter"
    }
    require(expectation.configuration.requestedIncludes == requestedIncludes) {
        "Campaign JMH includes do not match the parent configuration"
    }
    require(expectation.configuration.requestedForks == requiredProperty(REQUESTED_FORKS_PROPERTY).toInt()) {
        "Campaign JMH fork count does not match the parent configuration"
    }
    require(expectation.configuration.quick == requiredProperty(QUICK_PROPERTY).toBooleanStrict()) {
        "Campaign JMH quick mode does not match the parent configuration"
    }
    require(expectation.workload.manifestSha256 == requiredProperty(LIFECYCLE_MANIFEST_SHA256_PROPERTY)) {
        "Campaign JMH workload identity does not match the parent snapshot"
    }
    requiredPath(FIXTURE_ROOT_PROPERTY).toRealPath()
    requiredProperty(LIFECYCLE_BASE_URL_PROPERTY)
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
        System.getProperty(LIFECYCLE_SOURCE_ROOT_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toRealPath() }
            ?: installationRoot
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
    val identityFactory = RuntimeIdentityFactory(installationRoot)
    val harness = identityFactory.harnessIdentity()
    val adapterId = requiredProperty(ADAPTER_PROPERTY)
    val target = identityFactory.targetIdentity(verified, adapterId, harness)
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
        environment = identityFactory.environmentIdentity(),
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
internal const val LIFECYCLE_SOURCE_ROOT_PROPERTY: String =
    "revoman.benchmark.lifecycleSourceRoot"
internal const val JMH_CAMPAIGN_CONTEXT_PROPERTY: String =
    "revoman.benchmark.campaignContext"
internal const val INCLUDE_SEPARATOR: String = "\u001f"

private fun mergeLifecycleFailures(primary: Throwable?, next: Throwable): Throwable =
    primary?.also { existing ->
        if (existing !== next) existing.addSuppressed(next)
    } ?: next

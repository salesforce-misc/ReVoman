/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.fixture.DeterministicHttpFixture
import com.salesforce.revoman.benchmark.driver.host.VerifiedControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.integrity.LoadedTargetManifest
import com.salesforce.revoman.benchmark.driver.integrity.RuntimeIdentityFactory
import com.salesforce.revoman.benchmark.driver.jmh.VerifiedLifecycleWorkloadSnapshot
import com.salesforce.revoman.benchmark.driver.metrics.VerifiedJfrConfiguration
import com.salesforce.revoman.benchmark.driver.model.EnvironmentIdentity
import com.salesforce.revoman.benchmark.driver.model.HarnessIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import java.nio.file.Files
import java.nio.file.Path

internal data class SessionTarget(
    val loaded: LoadedTargetManifest,
    val identity: TargetIdentity,
    val runner: RunnerCampaign,
)

/** Owns one immutable identity/resource graph across every metric pass in a paired campaign. */
internal class VerifiedCampaignSession private constructor(
    private val identityFactory: RuntimeIdentityFactory,
    val baseline: LoadedTargetManifest,
    val candidate: LoadedTargetManifest,
    val baselineIdentity: TargetIdentity,
    val candidateIdentity: TargetIdentity,
    val harness: HarnessIdentity,
    val environment: EnvironmentIdentity,
    val workload: VerifiedLifecycleWorkloadSnapshot,
    val fixture: DeterministicHttpFixture,
    val logging: VerifiedLoggingConfiguration,
    val hostPolicy: VerifiedControlledHostPolicy?,
    val controllerClasspath: List<Path>,
    val benchmarkClassesJar: Path,
    val jfrConfiguration: VerifiedJfrConfiguration,
    val jfrSnapshot: Path,
    val loggingSnapshot: Path,
    private val sessionRoot: Path,
    private val baselineRunner: RunnerCampaign,
    private val candidateRunner: RunnerCampaign,
) : AutoCloseable {
    fun target(role: TargetRole): SessionTarget =
        when (role) {
            TargetRole.BASELINE -> SessionTarget(baseline, baselineIdentity, baselineRunner)
            TargetRole.CANDIDATE -> SessionTarget(candidate, candidateIdentity, candidateRunner)
        }

    override fun close() {
        var failure: Throwable? = null
        listOf<() -> Unit>(
                fixture::close,
                baseline.verified::postflight,
                candidate.verified::postflight,
                { identityFactory.postflight(harness) },
                workload::postflightSource,
                workload::postflightSnapshot,
                { logging.postflight(loggingSnapshot) },
                { jfrConfiguration.postflight(jfrSnapshot) },
                workload::close,
                { deleteRecursively(sessionRoot) },
            )
            .forEach { finalizer ->
                try {
                    finalizer()
                } catch (next: Throwable) {
                    failure = mergeCampaignFailures(failure, next)
                }
            }
        failure?.let { throw it }
    }

    companion object {
        fun open(
            identityFactory: RuntimeIdentityFactory,
            artifactRoot: Path,
            workloadSource: Path,
            logging: VerifiedLoggingConfiguration,
            baseline: LoadedTargetManifest,
            baselineAdapterId: String,
            candidate: LoadedTargetManifest,
            candidateAdapterId: String,
            hostPolicy: VerifiedControlledHostPolicy?,
        ): VerifiedCampaignSession {
            val sessionRoot = Files.createDirectory(artifactRoot.resolve("session")).toRealPath()
            var workload: VerifiedLifecycleWorkloadSnapshot? = null
            var fixture: DeterministicHttpFixture? = null
            var loggingSnapshot: Path? = null
            var jfrConfiguration: VerifiedJfrConfiguration? = null
            var jfrSnapshot: Path? = null
            var harness: HarnessIdentity? = null
            try {
                val materializedLogging = logging.materialize(sessionRoot).also { loggingSnapshot = it }
                val verifiedJfr =
                    VerifiedJfrConfiguration.preflight(
                            identityFactory.installationRoot
                                .resolve("jfr/revoman-allocation-v1.jfc")
                                .toRealPath()
                        )
                        .also { jfrConfiguration = it }
                val materializedJfr = verifiedJfr.materialize(sessionRoot).also { jfrSnapshot = it }
                val snapshot =
                    VerifiedLifecycleWorkloadSnapshot.open(workloadSource, sessionRoot).also {
                        workload = it
                    }
                val server =
                    DeterministicHttpFixture.open(snapshot.manifest, snapshot.snapshotRoot).also {
                        fixture = it
                    }
                val capturedHarness = identityFactory.harnessIdentity().also { harness = it }
                if (hostPolicy != null) {
                    require(!capturedHarness.dirty) {
                        "Controlled execution requires a clean harness source identity"
                    }
                }
                val baselineIdentity =
                    identityFactory.targetIdentity(baseline.verified, baselineAdapterId, capturedHarness)
                val candidateIdentity =
                    identityFactory.targetIdentity(candidate.verified, candidateAdapterId, capturedHarness)
                val workingDirectory = identityFactory.installationRoot
                val baselineRunner =
                    RunnerCampaign.openVerified(
                        baseline.verified,
                        logging,
                        materializedLogging,
                        sessionRoot,
                        workingDirectory,
                    )
                val candidateRunner =
                    RunnerCampaign.openVerified(
                        candidate.verified,
                        logging,
                        materializedLogging,
                        sessionRoot,
                        workingDirectory,
                    )
                val lib = identityFactory.installationRoot.resolve("lib")
                val controllerClasspath =
                    Files.list(lib).use { paths ->
                        paths
                            .filter { path -> path.fileName.toString().endsWith(".jar") }
                            .sorted()
                            .map(Path::toRealPath)
                            .toList()
                    }
                val thin = lib.resolve("benchmark-driver-jmh-classes.jar").toRealPath()
                require(thin in controllerClasspath) {
                    "Installed controller classpath omits thin JMH JAR"
                }
                return VerifiedCampaignSession(
                    identityFactory,
                    baseline,
                    candidate,
                    baselineIdentity,
                    candidateIdentity,
                    capturedHarness,
                    identityFactory.environmentIdentity(hostPolicy),
                    snapshot,
                    server,
                    logging,
                    hostPolicy,
                    controllerClasspath,
                    thin,
                    verifiedJfr,
                    materializedJfr,
                    materializedLogging,
                    sessionRoot,
                    baselineRunner,
                    candidateRunner,
                )
            } catch (failure: Throwable) {
                listOf<() -> Unit>(
                        { fixture?.close() },
                        baseline.verified::postflight,
                        candidate.verified::postflight,
                        { harness?.let(identityFactory::postflight) },
                        { workload?.postflightSource() },
                        { workload?.postflightSnapshot() },
                        { loggingSnapshot?.let(logging::postflight) },
                        {
                            jfrConfiguration?.let { configuration ->
                                jfrSnapshot?.let(configuration::postflight)
                            }
                        },
                        { workload?.close() },
                        { deleteRecursively(sessionRoot) },
                    )
                    .forEach { finalizer ->
                        try {
                            finalizer()
                        } catch (next: Throwable) {
                            if (failure !== next) failure.addSuppressed(next)
                        }
                    }
                throw failure
            }
        }
    }
}

private fun mergeCampaignFailures(primary: Throwable?, next: Throwable): Throwable =
    primary?.also { existing ->
        if (existing !== next) existing.addSuppressed(next)
    } ?: next

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

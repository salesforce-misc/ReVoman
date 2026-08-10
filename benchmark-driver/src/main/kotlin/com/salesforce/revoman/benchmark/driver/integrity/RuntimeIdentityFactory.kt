/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.integrity

import com.salesforce.revoman.benchmark.driver.host.VerifiedControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ArtifactSnapshot
import com.salesforce.revoman.benchmark.driver.model.EnvironmentIdentity
import com.salesforce.revoman.benchmark.driver.model.HarnessIdentity
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** Creates shared harness, runtime-environment, and target identities from one installation. */
class RuntimeIdentityFactory(installationRoot: Path) {
    val installationRoot: Path = installationRoot.toRealPath()

    init {
        require(this.installationRoot == installationRoot && Files.isDirectory(installationRoot)) {
            "Installation root must be a canonical directory: $installationRoot"
        }
    }

    /** Loads the embedded source identity and binds it to the exact declared installed artifacts. */
    fun harnessIdentity(): HarnessIdentity {
        val source =
            BenchmarkJson.read<HarnessSourceManifest>(
                installationRoot.resolve("conf/benchmark-harness-source-v1.json")
            )
        val artifacts = runtimeArtifacts()
        return HarnessIdentity(
            commit = source.commit,
            tree = source.tree,
            dirty = source.dirty,
            distributionSha256 = ContentHasher.artifactSetSha256(artifacts),
            artifacts = artifacts,
            workloadContractSha256 = source.workloadContractSha256,
            fixtureSetSha256 = source.fixtureSetSha256,
            adapters = source.adapters,
        )
    }

    /** Recomputes the installed distribution after a campaign and rejects any identity mutation. */
    fun postflight(expected: HarnessIdentity) {
        val actual = harnessIdentity()
        check(actual == expected) {
            "The installed harness changed during the campaign"
        }
    }

    /** Produces a path-free target snapshot bound to one installed adapter source identity. */
    fun targetIdentity(
        verified: VerifiedTargetManifest,
        adapterId: String,
        harness: HarnessIdentity,
    ): TargetIdentity {
        val adapter =
            requireNotNull(harness.adapters.singleOrNull { it.id == adapterId }) {
                "Installed harness source identity has no exact adapter: $adapterId"
            }
        val manifest = verified.manifest
        return TargetIdentity(
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
                    ArtifactSnapshot(
                        logicalId = artifact.logicalId,
                        sizeBytes = artifact.sizeBytes,
                        sha256 = artifact.sha256,
                    )
                },
            adapter = adapter,
        )
    }

    /** Captures one runtime environment, optionally bound to a verified controlled-host policy. */
    fun environmentIdentity(policy: VerifiedControlledHostPolicy? = null): EnvironmentIdentity {
        val osName = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val cpuModel = policy?.policy?.cpuModel ?: System.getProperty("os.arch")
        val cpuCount = policy?.policy?.cpuCount ?: Runtime.getRuntime().availableProcessors()
        val physicalMemory =
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                ?.totalMemorySize
                ?.takeIf { it > 0 }
                ?: Runtime.getRuntime().maxMemory()
        val fingerprint =
            policy?.policy?.hostFingerprintSha256
                ?: ContentHasher.sha256(
                    "$osName\u0000$osVersion\u0000$cpuModel\u0000$cpuCount\u0000$physicalMemory"
                        .toByteArray(UTF_8)
                )
        return EnvironmentIdentity(
            jdk = currentJdkIdentity(),
            osName = osName,
            osVersion = osVersion,
            kernel = osVersion,
            cpuModel = cpuModel,
            cpuCount = cpuCount,
            governor = policy?.policy?.allowedGovernors?.sorted()?.joinToString(",") ?: "unknown",
            physicalMemoryBytes = physicalMemory,
            hostFingerprintSha256 = fingerprint,
            policySha256 = policy?.canonicalSha256,
        )
    }

    /** Returns the exact path-stable installed artifact set; timestamps and installation path are absent. */
    fun runtimeArtifacts() =
        BuildIdentity.runtimeArtifacts(
            installationRoot,
            RUNTIME_ARTIFACT_DIRECTORIES
                .map(installationRoot::resolve)
                .filter(Files::exists)
                .flatMap { root ->
                    Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
                },
        )
}

internal fun currentJdkIdentity(): JdkIdentity =
    JdkIdentity(
        distribution = System.getProperty("java.runtime.name"),
        vendor = System.getProperty("java.vendor"),
        fullVersion = System.getProperty("java.runtime.version"),
        javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().toString(),
        jvmFlags = ManagementFactory.getRuntimeMXBean().inputArguments.toList(),
    )

private val RUNTIME_ARTIFACT_DIRECTORIES =
    listOf("lib", "schema", "workloads", "jfr", "conf", "libexec")

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.VerifiedArtifactStamp
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Content-verified controller identity plus cheap worker reconstruction stamps. */
data class VerifiedTargetManifest internal constructor(
    val manifest: TargetManifest,
    val manifestSha256: String,
    val classpathSha256: String,
    val artifactStamps: List<VerifiedArtifactStamp>,
) {
    /**
     * Rehashes the complete ordered classpath after a campaign and rejects any intervening change.
     */
    fun postflight() {
        val postflightArtifacts = mutableListOf<HashedArtifact>()
        val failures = mutableListOf<String>()
        manifest.classpath.forEachIndexed { index, artifact ->
            val expectedStamp = artifactStamps[index]
            val path = Path.of(artifact.executionPath)
            val canonicalPath = runCatching(path::toRealPath).getOrNull()
            val attributes =
                canonicalPath?.let {
                    runCatching { Files.readAttributes(it, BasicFileAttributes::class.java) }.getOrNull()
                }
            val actualHash =
                canonicalPath?.let {
                    runCatching { ContentHasher.sha256(it) }.getOrNull()
                }

            when {
                canonicalPath == null -> failures += "${artifact.logicalId}: path is missing"
                canonicalPath != path ->
                    failures +=
                        "${artifact.logicalId}: path is no longer canonical (${artifact.executionPath})"
            }
            if (attributes == null) {
                failures += "${artifact.logicalId}: attributes are unavailable"
            } else {
                val actualStamp = artifactStamp(artifact.logicalId, path, attributes)
                if (actualStamp != expectedStamp) {
                    failures += "${artifact.logicalId}: cheap file stamp changed"
                }
            }
            if (actualHash == null) {
                failures += "${artifact.logicalId}: content is unreadable"
            } else {
                if (actualHash != artifact.sha256) {
                    failures += "${artifact.logicalId}: SHA-256 changed"
                }
                postflightArtifacts +=
                    artifact.copy(
                        sizeBytes = attributes?.size() ?: -1,
                        sha256 = actualHash,
                    )
            }
        }

        if (
            postflightArtifacts.size == manifest.classpath.size &&
                ContentHasher.artifactSetSha256(postflightArtifacts) != classpathSha256
        ) {
            failures += "ordered classpath identity changed"
        }
        check(failures.isEmpty()) {
            "Target campaign invalid after postflight: ${failures.joinToString()}"
        }
    }

    companion object {
        /** Performs full controller-side manifest and artifact verification before timed work. */
        fun preflight(manifestPath: Path): VerifiedTargetManifest {
            val canonicalManifest = manifestPath.toRealPath()
            val manifest = BenchmarkJson.read<TargetManifest>(canonicalManifest)
            val artifactStamps =
                manifest.classpath.mapIndexed { index, artifact ->
                    val path = requireCanonicalArtifactPath("classpath[$index]", artifact.executionPath)
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    require(attributes.isRegularFile) { "classpath[$index] must be a regular file: $path" }
                    require(attributes.size() == artifact.sizeBytes) {
                        "classpath[$index] size mismatch for ${artifact.logicalId}: " +
                            "expected=${artifact.sizeBytes}, actual=${attributes.size()}"
                    }
                    val actualHash = ContentHasher.sha256(path)
                    require(actualHash == artifact.sha256) {
                        "classpath[$index] SHA-256 mismatch for ${artifact.logicalId}: " +
                            "expected=${artifact.sha256}, actual=$actualHash"
                    }
                    artifactStamp(artifact.logicalId, path, attributes)
                }
            return VerifiedTargetManifest(
                manifest = manifest,
                manifestSha256 = ContentHasher.sha256(canonicalManifest),
                classpathSha256 = ContentHasher.artifactSetSha256(manifest.classpath),
                artifactStamps = artifactStamps,
            )
        }

        /**
         * Reconstructs a verified worker view from identities and cheap stamps without hashing JARs.
         */
        fun fromWorkerCommand(command: TargetForkCommand): VerifiedTargetManifest {
            val verification = command.verification
            val manifestPath = Path.of(verification.targetManifest)
            val canonicalManifest = manifestPath.toRealPath()
            require(canonicalManifest == manifestPath) {
                "Worker target manifest path must be canonical: $manifestPath"
            }
            val manifest = BenchmarkJson.read<TargetManifest>(canonicalManifest)
            val actualManifestHash = ContentHasher.sha256(canonicalManifest)
            require(actualManifestHash == verification.targetManifestSha256) {
                "Worker manifest SHA-256 differs from controller verification: " +
                    "expected=${verification.targetManifestSha256}, actual=$actualManifestHash"
            }
            val actualClasspathHash = ContentHasher.artifactSetSha256(manifest.classpath)
            require(actualClasspathHash == verification.targetClasspathSha256) {
                "Worker classpath SHA-256 differs from controller verification: " +
                    "expected=${verification.targetClasspathSha256}, actual=$actualClasspathHash"
            }
            require(verification.artifactStamps.size == manifest.classpath.size) {
                "Worker artifact stamp count differs from target classpath"
            }
            val workerStamps =
                manifest.classpath.mapIndexed { index, artifact ->
                    val path = requireCanonicalArtifactPath("classpath[$index]", artifact.executionPath)
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    require(attributes.isRegularFile) { "classpath[$index] must be a regular file: $path" }
                    artifactStamp(artifact.logicalId, path, attributes)
                }
            require(workerStamps == verification.artifactStamps) {
                "Worker artifact cheap stamps differ from controller verification"
            }
            return VerifiedTargetManifest(
                manifest = manifest,
                manifestSha256 = actualManifestHash,
                classpathSha256 = actualClasspathHash,
                artifactStamps = workerStamps,
            )
        }
    }
}

private fun requireCanonicalArtifactPath(name: String, executionPath: String): Path {
    val path = Path.of(executionPath)
    require(path.isAbsolute) { "$name executionPath must be absolute: $executionPath" }
    require(path.normalize() == path) { "$name executionPath must be normalized: $executionPath" }
    val canonicalPath = path.toRealPath()
    require(canonicalPath == path) { "$name executionPath must be canonical: $executionPath" }
    return path
}

private fun artifactStamp(
    logicalId: String,
    path: Path,
    attributes: BasicFileAttributes,
): VerifiedArtifactStamp =
    VerifiedArtifactStamp(
        logicalId = logicalId,
        executionPath = path.toString(),
        sizeBytes = attributes.size(),
        lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
        fileKey = attributes.fileKey()?.toString(),
    )

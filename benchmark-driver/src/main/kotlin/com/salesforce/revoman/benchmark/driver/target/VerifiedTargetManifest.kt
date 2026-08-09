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
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.VerifiedArtifactStamp
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Content-verified controller identity plus cheap worker reconstruction stamps. */
data class VerifiedTargetManifest internal constructor(
    val manifestPath: Path,
    val manifest: TargetManifest,
    val manifestSha256: String,
    val classpathSha256: String,
    val artifactStamps: List<VerifiedArtifactStamp>,
) {
    /** Recreates the exact worker token bound to this verified manifest snapshot. */
    fun verificationToken(): TargetVerificationToken =
        TargetVerificationToken(
            targetManifest = manifestPath.toString(),
            targetManifestSha256 = manifestSha256,
            targetClasspathSha256 = classpathSha256,
            artifactStamps = artifactStamps,
        )

    /**
     * Rehashes the manifest and complete ordered classpath after a campaign.
     */
    fun postflight() {
        val postflightArtifacts = mutableListOf<HashedArtifact>()
        val failures = mutableListOf<String>()
        val postflightManifest =
            runCatching { captureManifest(manifestPath) }
                .onFailure { failures += "target manifest is unreadable or invalid" }
                .getOrNull()
        if (postflightManifest != null && postflightManifest.sha256 != manifestSha256) {
            failures += "target manifest SHA-256 changed"
        }
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
        fun preflight(manifestPath: Path): VerifiedTargetManifest =
            preflight(manifestPath, expectedManifest = null)

        /** Verifies one byte snapshot and optionally binds it to [expectedManifest]. */
        fun preflight(
            manifestPath: Path,
            expectedManifest: TargetManifest?,
        ): VerifiedTargetManifest {
            val snapshot = captureManifest(manifestPath)
            expectedManifest?.let { expected ->
                require(snapshot.manifest == expected) {
                    "Verified manifest does not match the expected target"
                }
            }
            val artifactStamps =
                snapshot.manifest.classpath.mapIndexed { index, artifact ->
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
                manifestPath = snapshot.path,
                manifest = snapshot.manifest,
                manifestSha256 = snapshot.sha256,
                classpathSha256 = ContentHasher.artifactSetSha256(snapshot.manifest.classpath),
                artifactStamps = artifactStamps,
            )
        }

        /**
         * Reconstructs a verified worker view from identities and cheap stamps without hashing JARs.
         */
        fun fromWorkerCommand(command: TargetForkCommand): VerifiedTargetManifest {
            val verification = command.verification
            val manifestPath = Path.of(verification.targetManifest)
            require(manifestPath.toRealPath() == manifestPath) {
                "Worker target manifest path must be canonical: $manifestPath"
            }
            val snapshot = captureManifest(manifestPath)
            require(snapshot.sha256 == verification.targetManifestSha256) {
                "Worker manifest SHA-256 differs from controller verification: " +
                    "expected=${verification.targetManifestSha256}, actual=${snapshot.sha256}"
            }
            val actualClasspathHash = ContentHasher.artifactSetSha256(snapshot.manifest.classpath)
            require(actualClasspathHash == verification.targetClasspathSha256) {
                "Worker classpath SHA-256 differs from controller verification: " +
                    "expected=${verification.targetClasspathSha256}, actual=$actualClasspathHash"
            }
            val manifestLogicalIds = snapshot.manifest.classpath.map(HashedArtifact::logicalId)
            val stampLogicalIds = verification.artifactStamps.map(VerifiedArtifactStamp::logicalId)
            require(stampLogicalIds == manifestLogicalIds) {
                "Worker artifact stamp IDs/order differ from target manifest: " +
                    "expected=$manifestLogicalIds, actual=$stampLogicalIds"
            }
            val workerStamps =
                snapshot.manifest.classpath.mapIndexed { index, artifact ->
                    val path = requireCanonicalArtifactPath("classpath[$index]", artifact.executionPath)
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    require(attributes.isRegularFile) { "classpath[$index] must be a regular file: $path" }
                    artifactStamp(artifact.logicalId, path, attributes)
                }
            require(workerStamps == verification.artifactStamps) {
                "Worker artifact cheap stamps differ from controller verification"
            }
            return VerifiedTargetManifest(
                manifestPath = snapshot.path,
                manifest = snapshot.manifest,
                manifestSha256 = snapshot.sha256,
                classpathSha256 = actualClasspathHash,
                artifactStamps = workerStamps,
            )
        }
    }
}

private data class ManifestSnapshot(
    val path: Path,
    val manifest: TargetManifest,
    val sha256: String,
)

private fun captureManifest(manifestPath: Path): ManifestSnapshot {
    val canonical = manifestPath.toRealPath()
    val bytes = Files.readAllBytes(canonical)
    return ManifestSnapshot(
        path = canonical,
        manifest = BenchmarkJson.decode(bytes, canonical.toString()),
        sha256 = ContentHasher.sha256(bytes),
    )
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

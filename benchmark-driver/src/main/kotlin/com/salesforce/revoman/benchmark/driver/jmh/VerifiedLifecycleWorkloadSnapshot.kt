/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.JmhWorkloadIdentity
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** One captured lifecycle workload, its private fork input tree, and both postflight boundaries. */
internal class VerifiedLifecycleWorkloadSnapshot private constructor(
    val sourceRoot: Path,
    val manifest: WorkloadManifest,
    val manifestSha256: String,
    val snapshotRoot: Path,
    private var manifestBytes: ByteArray?,
    private var capturedFiles: List<CapturedWorkloadFile>,
) : AutoCloseable {
    val workloadIdentity: JmhWorkloadIdentity =
        JmhWorkloadIdentity(manifestSha256 = manifestSha256, manifest = manifest)

    fun postflightSource() {
        postflight("source", sourceRoot)
    }

    fun postflightSnapshot() {
        postflight("snapshot", snapshotRoot)
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            deleteRecursively(snapshotRoot)
        } catch (cleanupFailure: Throwable) {
            failure = cleanupFailure
        } finally {
            manifestBytes?.fill(0)
            manifestBytes = null
            capturedFiles.forEach { file -> file.bytes.fill(0) }
            capturedFiles = emptyList()
        }
        failure?.let { throw it }
    }

    private fun postflight(label: String, root: Path) {
        val failures = mutableListOf<String>()
        val canonicalRoot = runCatching(root::toRealPath).getOrNull()
        if (canonicalRoot != root) failures += "root path is no longer canonical"
        val actualNames =
            canonicalRoot?.let { canonical ->
                runCatching { fixtureFileNames(canonical) }
                    .onFailure { failures += "file set is unreadable" }
                    .getOrNull()
            }
        val expectedNames = capturedFiles.map(CapturedWorkloadFile::portablePath).sorted()
        if (actualNames != null && actualNames != expectedNames) {
            failures += "file set changed: expected=$expectedNames, actual=$actualNames"
        }

        val currentManifest = readPostflightBytes(root.resolve(MANIFEST_FILE), "manifest.json", failures)
        if (currentManifest != null && ContentHasher.sha256(currentManifest) != manifestSha256) {
            failures += "manifest.json SHA-256 changed"
        }

        val actualTree = linkedMapOf<String, ByteArray>()
        capturedFiles.forEach { captured ->
            val actual =
                readPostflightBytes(root.resolve(captured.relativePath), captured.portablePath, failures)
            if (actual != null) {
                if (actual.size.toLong() != captured.sizeBytes) {
                    failures += "${captured.portablePath} size changed"
                }
                if (ContentHasher.sha256(actual) != captured.sha256) {
                    failures += "${captured.portablePath} SHA-256 changed"
                }
                actualTree[captured.portablePath] = actual
            }
        }
        if (
            actualTree.size == capturedFiles.size &&
                ContentHasher.treeSha256(actualTree) != manifest.fixtureTreeSha256
        ) {
            failures += "fixture tree SHA-256 changed"
        }
        check(failures.isEmpty()) {
            "$label lifecycle workload invalid after postflight: ${failures.joinToString()}"
        }
    }

    companion object {
        fun open(
            sourceRoot: Path,
            snapshotParent: Path? = null,
            readBytes: (Path) -> ByteArray = Files::readAllBytes,
        ): VerifiedLifecycleWorkloadSnapshot {
            val canonicalSource = sourceRoot.toRealPath()
            require(canonicalSource == sourceRoot && Files.isDirectory(canonicalSource)) {
                "Lifecycle workload source must be a canonical directory: $sourceRoot"
            }
            val manifestPath = canonicalSource.resolve(MANIFEST_FILE)
            require(manifestPath.toRealPath() == manifestPath && Files.isRegularFile(manifestPath)) {
                "Lifecycle workload manifest must be a canonical regular file: $manifestPath"
            }
            val capturedManifestBytes = readBytes(manifestPath).copyOf()
            val capturedManifest =
                BenchmarkJson.decode<WorkloadManifest>(capturedManifestBytes, manifestPath.toString())
            require(capturedManifest.id == LIFECYCLE_WORKLOAD_ID) {
                "Lifecycle workload must be $LIFECYCLE_WORKLOAD_ID, actual=${capturedManifest.id}"
            }
            require(capturedManifest.contractVersion == LIFECYCLE_CONTRACT_VERSION) {
                "Lifecycle workload contract version must be $LIFECYCLE_CONTRACT_VERSION, " +
                    "actual=${capturedManifest.contractVersion}"
            }
            require(capturedManifest.expectedDigest != null) {
                "Lifecycle workload requires a non-null expectedDigest oracle"
            }
            val captured = captureDeclaredFiles(canonicalSource, capturedManifest, readBytes)
            val expectedNames = captured.map(CapturedWorkloadFile::portablePath).sorted()
            val actualNames = fixtureFileNames(canonicalSource)
            require(actualNames == expectedNames) {
                "Lifecycle workload file set differs: expected=$expectedNames, actual=$actualNames"
            }
            val capturedTree =
                ContentHasher.treeSha256(captured.associate { file -> file.portablePath to file.bytes })
            require(capturedTree == capturedManifest.fixtureTreeSha256) {
                "Lifecycle workload fixture tree SHA-256 differs: " +
                    "expected=${capturedManifest.fixtureTreeSha256}, actual=$capturedTree"
            }

            var materializedRoot: Path? = null
            try {
                val root = createSnapshotDirectory(snapshotParent).also { materializedRoot = it }
                captured.forEach { file ->
                    val destination = root.resolve(file.relativePath)
                    Files.createDirectories(requireNotNull(destination.parent))
                    Files.write(destination, file.bytes, CREATE_NEW)
                }
                Files.write(root.resolve(MANIFEST_FILE), capturedManifestBytes, CREATE_NEW)
                return VerifiedLifecycleWorkloadSnapshot(
                        sourceRoot = canonicalSource,
                        manifest = capturedManifest,
                        manifestSha256 = ContentHasher.sha256(capturedManifestBytes),
                        snapshotRoot = root,
                        manifestBytes = capturedManifestBytes,
                        capturedFiles = captured,
                    )
                    .also(VerifiedLifecycleWorkloadSnapshot::postflightSnapshot)
            } catch (failure: Throwable) {
                try {
                    materializedRoot?.let(::deleteRecursively)
                } catch (cleanupFailure: Throwable) {
                    if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
                } finally {
                    capturedManifestBytes.fill(0)
                    captured.forEach { file -> file.bytes.fill(0) }
                }
                throw failure
            }
        }

        private fun captureDeclaredFiles(
            root: Path,
            manifest: WorkloadManifest,
            readBytes: (Path) -> ByteArray,
        ): List<CapturedWorkloadFile> {
            val relativePaths =
                manifest.files.mapIndexed { index, artifact ->
                    normalizedRelativePath("files[$index].executionPath", artifact.executionPath)
                }
            require(relativePaths.distinct().size == relativePaths.size) {
                "Lifecycle workload execution paths must be unique"
            }
            require(relativePaths.none { path -> portablePath(path) == MANIFEST_FILE }) {
                "Lifecycle workload files must not declare $MANIFEST_FILE"
            }
            return manifest.files.zip(relativePaths).map { (artifact, relativePath) ->
                val declaredPath = root.resolve(relativePath)
                val canonicalPath = declaredPath.toRealPath()
                require(canonicalPath == declaredPath && canonicalPath.startsWith(root)) {
                    "Lifecycle workload path must be canonical and remain below source: " +
                        artifact.executionPath
                }
                require(Files.isRegularFile(canonicalPath)) {
                    "Lifecycle workload file must be regular: $canonicalPath"
                }
                val bytes = readBytes(canonicalPath).copyOf()
                val sha256 = ContentHasher.sha256(bytes)
                require(bytes.size.toLong() == artifact.sizeBytes && sha256 == artifact.sha256) {
                    "Lifecycle workload bytes differ for ${artifact.executionPath}: " +
                        "expectedSize=${artifact.sizeBytes}, actualSize=${bytes.size}, " +
                        "expectedSha256=${artifact.sha256}, actualSha256=$sha256"
                }
                CapturedWorkloadFile(
                    relativePath = relativePath,
                    portablePath = portablePath(relativePath),
                    sizeBytes = artifact.sizeBytes,
                    sha256 = artifact.sha256,
                    bytes = bytes,
                )
            }
        }

        private fun createSnapshotDirectory(parent: Path?): Path {
            if (parent == null) {
                return Files.createTempDirectory(SNAPSHOT_PREFIX).toRealPath()
            }
            val canonicalParent = parent.toRealPath()
            require(canonicalParent == parent && Files.isDirectory(parent)) {
                "Lifecycle snapshot parent must be a canonical directory: $parent"
            }
            return Files.createTempDirectory(parent, SNAPSHOT_PREFIX).toRealPath()
        }
    }
}

private data class CapturedWorkloadFile(
    val relativePath: Path,
    val portablePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val bytes: ByteArray,
)

private fun readPostflightBytes(
    path: Path,
    name: String,
    failures: MutableList<String>,
): ByteArray? {
    val canonical = runCatching(path::toRealPath).getOrNull()
    if (canonical != path) {
        failures += "$name path is missing or no longer canonical"
        return null
    }
    return runCatching { Files.readAllBytes(path) }
        .onFailure { failures += "$name is unreadable" }
        .getOrNull()
}

private fun fixtureFileNames(root: Path): List<String> =
    Files.walk(root).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .map(root::relativize)
            .map(::portablePath)
            .filter { it != MANIFEST_FILE }
            .sorted()
            .toList()
    }

private fun normalizedRelativePath(name: String, value: String): Path {
    val path = Path.of(value)
    require(!path.isAbsolute && path.normalize() == path && path.nameCount > 0) {
        "$name must be normalized and relative: $value"
    }
    return path
}

private fun portablePath(path: Path): String = path.map(Path::toString).joinToString("/")

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

private const val MANIFEST_FILE: String = "manifest.json"
private const val LIFECYCLE_WORKLOAD_ID: String = "lifecycle.no-script-one-step.v1"
private const val LIFECYCLE_CONTRACT_VERSION: Int = 1
private const val SNAPSHOT_PREFIX: String = "revoman-lifecycle-workload-"

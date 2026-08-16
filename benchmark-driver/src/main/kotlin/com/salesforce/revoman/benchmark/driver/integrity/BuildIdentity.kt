/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.integrity

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.AdapterIdentity
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.squareup.moshi.JsonClass
import java.nio.file.Files
import java.nio.file.Path

/** Git facts recorded separately from byte-exact source identities. */
@JsonClass(generateAdapter = true)
data class GitBuildIdentity(
    val commit: String,
    val tree: String,
    val dirty: Boolean,
)

/** Non-self-referential source identity embedded in the benchmark distribution. */
@JsonClass(generateAdapter = true)
data class HarnessSourceManifest(
    val schema: String = "revoman-benchmark-harness-source/v1",
    val commit: String,
    val tree: String,
    val dirty: Boolean,
    val sourceSha256: String,
    val artifacts: List<HashedArtifact>,
    val workloadContractSha256: String,
    val fixtureSetSha256: String,
    val adapters: List<AdapterIdentity>,
)

/** Builds path-stable source and installed-distribution identities. */
object BuildIdentity {
    /** Enumerates regular source inputs once in normalized repository-relative path order. */
    fun sourceFiles(repositoryRoot: Path, inputRoots: Iterable<Path>): List<Path> {
        val root = repositoryRoot.toAbsolutePath().normalize()
        return inputRoots
            .flatMap { input ->
                val normalized = input.toAbsolutePath().normalize()
                require(normalized == root || normalized.startsWith(root)) {
                    "Source input must be inside repository root $root: $input"
                }
                when {
                    Files.notExists(normalized) -> emptyList()
                    normalized.isRegularFile() -> listOf(normalized)
                    else -> Files.walk(normalized).use { paths -> paths.filter(Path::isRegularFile).toList() }
                }
            }
            .distinct()
            .sortedBy { root.relativize(it).toPosixString() }
    }

    /** Creates the embedded identity from checked-in source inputs only. */
    fun createSourceManifest(
        repositoryRoot: Path,
        git: GitBuildIdentity,
        adapterRoots: List<Path>,
        benchmarkContractRoots: List<Path>,
        fixtureRoots: List<Path> = benchmarkContractRoots,
        adapterIds: Map<Path, String> = emptyMap(),
    ): HarnessSourceManifest {
        val root = repositoryRoot.toAbsolutePath().normalize()
        val allFiles = sourceFiles(root, adapterRoots + benchmarkContractRoots + fixtureRoots)
        require(allFiles.isNotEmpty()) { "Harness source identity has no input files" }
        val artifacts = allFiles.map { it.toHashedArtifact(root) }
        val adapterIdentities =
            adapterRoots.map { adapterRoot ->
                val files = sourceFiles(root, listOf(adapterRoot))
                require(files.isNotEmpty()) { "Adapter source root has no files: $adapterRoot" }
                AdapterIdentity(
                    id = adapterIds[adapterRoot] ?: adapterRoot.fileName.toString(),
                    sourceSha256 = ContentHasher.treeSha256(root, files),
                )
            }
        val contractFiles = sourceFiles(root, benchmarkContractRoots)
        val fixtureFiles = sourceFiles(root, fixtureRoots)
        require(contractFiles.isNotEmpty()) { "Benchmark contract identity has no input files" }
        require(fixtureFiles.isNotEmpty()) { "Fixture identity has no input files" }
        return HarnessSourceManifest(
            commit = git.commit,
            tree = git.tree,
            dirty = git.dirty,
            sourceSha256 = ContentHasher.treeSha256(root, allFiles),
            artifacts = artifacts,
            workloadContractSha256 = ContentHasher.treeSha256(root, contractFiles),
            fixtureSetSha256 = ContentHasher.treeSha256(root, fixtureFiles),
            adapters = adapterIdentities,
        )
    }

    /** Writes the source manifest atomically without ever adding a distribution hash. */
    fun writeSourceManifest(output: Path, manifest: HarnessSourceManifest) {
        BenchmarkJson.write(output, manifest)
    }

    /** Hashes every regular file below [installationRoot] by logical path, size, and bytes. */
    fun runtimeArtifactSetSha256(installationRoot: Path): String {
        val root = installationRoot.toAbsolutePath().normalize()
        val artifacts = runtimeArtifacts(root, sourceFiles(root, listOf(root)))
        require(artifacts.isNotEmpty()) { "Runtime artifact set is empty: $root" }
        return ContentHasher.artifactSetSha256(artifacts)
    }

    /** Creates path-stable records for an explicitly declared runtime artifact set. */
    fun runtimeArtifacts(installationRoot: Path, files: Iterable<Path>): List<HashedArtifact> {
        val root = installationRoot.toAbsolutePath().normalize()
        return files
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .onEach { file ->
                require(file.startsWith(root) && file != root) {
                    "Runtime artifact must be below installation root $root: $file"
                }
                require(file.isRegularFile()) { "Runtime artifact must be a regular file: $file" }
            }
            .distinct()
            .sortedBy { root.relativize(it).toPosixString() }
            .map { it.toHashedArtifact(root) }
    }

    /** Reads the current repository's Git commit, tree, and dirty state. */
    fun gitIdentity(repositoryRoot: Path): GitBuildIdentity {
        val root = repositoryRoot.toAbsolutePath().normalize()
        return GitBuildIdentity(
            commit = git(root, "rev-parse", "HEAD").trim(),
            tree = git(root, "rev-parse", "HEAD^{tree}").trim(),
            dirty = git(root, "status", "--porcelain", "--untracked-files=normal").isNotBlank(),
        )
    }

    private fun git(repositoryRoot: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repositoryRoot.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Git identity command failed: $output" }
        return output
    }
}

/** Generates the embedded harness source manifest used by installed benchmark runners. */
fun main(arguments: Array<String>) {
    require(arguments.size == 2) { "Usage: BuildIdentity <repository-root> <output-file>" }
    val repositoryRoot = Path.of(arguments[0]).toRealPath()
    val output = Path.of(arguments[1]).toAbsolutePath().normalize()
    val driverRoot = repositoryRoot.resolve("benchmark-driver")
    val baselineAdapter =
        driverRoot.resolve(
            "src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/baseline"
        )
    val majorAdapter =
        driverRoot.resolve("src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major")
    val contractRoots =
        listOf(
            driverRoot.resolve("src/main/kotlin"),
            driverRoot.resolve("src/jmh"),
            driverRoot.resolve("src/main/resources/schema"),
            driverRoot.resolve("src/main/resources/workloads"),
            driverRoot.resolve("src/main/resources/jfr"),
            driverRoot.resolve("src/main/dist/conf"),
            driverRoot.resolve("src/main/dist/libexec"),
        )
    val fixtureRoots = listOf(driverRoot.resolve("src/main/resources/workloads"))
    val manifest =
        BuildIdentity.createSourceManifest(
            repositoryRoot = repositoryRoot,
            git = BuildIdentity.gitIdentity(repositoryRoot),
            adapterRoots = listOf(baselineAdapter, majorAdapter),
            benchmarkContractRoots = contractRoots,
            fixtureRoots = fixtureRoots,
            adapterIds =
                mapOf(
                    baselineAdapter to "baseline-83f3cd70",
                    majorAdapter to "major-v1",
                ),
        )
    BuildIdentity.writeSourceManifest(output, manifest)
}

private fun Path.toHashedArtifact(root: Path): HashedArtifact {
    val logicalPath = root.relativize(this).toPosixString()
    return HashedArtifact(
        logicalId = logicalPath,
        executionPath = logicalPath,
        sizeBytes = Files.size(this),
        sha256 = ContentHasher.sha256(this),
    )
}

private fun Path.toPosixString(): String = joinToString("/") { it.toString() }

private fun Path.isRegularFile(): Boolean = Files.isRegularFile(this)

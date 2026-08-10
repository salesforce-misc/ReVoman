/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.cli

import com.salesforce.revoman.benchmark.driver.compare.GateDecision
import com.salesforce.revoman.benchmark.driver.compare.ReleaseGateEvaluator
import com.salesforce.revoman.benchmark.driver.integrity.LoadedTargetManifest
import com.salesforce.revoman.benchmark.driver.integrity.TargetManifestLoader
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.run.BenchmarkCampaign
import com.salesforce.revoman.benchmark.driver.run.CampaignRequest
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Process-level exit values used by all stable commands. */
object CliExitCode {
    const val SUCCESS: Int = 0
    const val EXECUTION_FAILED: Int = 1
    const val INVALID_INPUT: Int = 2
    const val GATE_NOT_PASSED: Int = 3
}

/** Parses, dispatches, and maps failures without allowing command implementations to terminate tests. */
object BenchmarkDriverApplication {
    fun execute(
        arguments: Array<String>,
        output: PrintStream = System.out,
        error: PrintStream = System.err,
        installationRoot: Path = discoverInstallationRoot(),
    ): Int =
        try {
            dispatch(BenchmarkCli.parse(arguments), output, installationRoot.toRealPath())
        } catch (failure: CliUsageException) {
            error.println(failure.message)
            CliExitCode.INVALID_INPUT
        } catch (failure: IllegalArgumentException) {
            error.println(failure.message)
            CliExitCode.INVALID_INPUT
        } catch (failure: Throwable) {
            error.println(failure.message ?: failure.javaClass.name)
            CliExitCode.EXECUTION_FAILED
        }

    private fun dispatch(command: BenchmarkCommand, output: PrintStream, installationRoot: Path): Int =
        when (command) {
            BenchmarkCommand.ListWorkloads -> listWorkloads(installationRoot, output)
            is BenchmarkCommand.RunPaired -> runPaired(command.options, installationRoot, capture = false)
            is BenchmarkCommand.CaptureBaseline ->
                runPaired(command.options, installationRoot, capture = true)
            is BenchmarkCommand.Compare -> compare(command, installationRoot)
            is BenchmarkCommand.Verify -> verify(command.input)
        }

    private fun listWorkloads(installationRoot: Path, output: PrintStream): Int {
        val workloadRoot = installationRoot.resolve("workloads/v1").toRealPath()
        Files.list(workloadRoot).use { paths ->
            paths
                .filter(Files::isDirectory)
                .sorted()
                .map { root -> loadWorkloadManifest(root.resolve("manifest.json")) }
                .forEach { manifest -> output.println(manifest.id) }
        }
        return CliExitCode.SUCCESS
    }

    private fun runPaired(
        options: RunOptions,
        installationRoot: Path,
        capture: Boolean,
    ): Int {
        val baseline = TargetManifestLoader.load(canonicalFile(options.baseline, "--baseline"))
        val candidate = TargetManifestLoader.load(canonicalFile(options.candidate, "--candidate"))
        if (capture) requireBaselineCapturePins(options, baseline, candidate)
        val hostPolicy =
            options.hostPolicy?.let { source ->
                com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy.load(
                    canonicalFile(source, "--host-policy")
                )
            }
        val output = requireAbsentOutputs(listOf("--output" to options.output)).single()
        val artifactDirectory = options.artifactDirectory.toAbsolutePath().normalize()
        require(output != artifactDirectory) { "--output and --artifacts-dir must be distinct" }
        val request =
            CampaignRequest(
                intent = options.intent,
                mode = options.mode,
                baseline = baseline.manifest,
                baselineManifestPath = baseline.verified.manifestPath,
                baselineAdapterId = options.baselineAdapter,
                candidate = candidate.manifest,
                candidateManifestPath = candidate.verified.manifestPath,
                candidateAdapterId = options.candidateAdapter,
                workloadId = options.workloadId,
                blocks = options.blocks,
                forksPerBlock = options.forksPerBlock,
                warmups = options.warmups,
                iterations = options.iterations,
                seed = options.seed,
                metricPasses = options.metricPasses.toSet(),
                artifactDirectory = artifactDirectory,
                hostPolicy = hostPolicy?.policy,
                hostPolicyPath = hostPolicy?.source,
            )
        val result =
            BenchmarkCampaign(installationRoot)
                .runVerified(request, baseline, candidate, hostPolicy)
        writeSchemaValidated(
            output,
            result,
            "/schema/revoman-benchmark-v1.schema.json",
        )
        return CliExitCode.SUCCESS
    }

    private fun compare(command: BenchmarkCommand.Compare, installationRoot: Path): Int {
        val result = readPairedResult(command.input)
        val manifests =
            result.workloads.map { workload ->
                val root = packagedWorkloadRoot(installationRoot, workload.id)
                loadWorkloadManifest(root.resolve("manifest.json"))
            }
        val report = ReleaseGateEvaluator().evaluate(result, manifests)
        val outputs =
            requireAbsentOutputs(
                listOf(
                    "--output-json" to command.outputJson,
                    "--output-md" to command.outputMarkdown,
                )
            )
        writeSchemaValidated(
            outputs[0],
            report,
            "/schema/revoman-benchmark-comparison-v1.schema.json",
        )
        writeTextAtomically(outputs[1], report.toMarkdown())
        return if (report.overall == GateDecision.PASS) {
            CliExitCode.SUCCESS
        } else {
            CliExitCode.GATE_NOT_PASSED
        }
    }

    private fun verify(input: Path): Int {
        val path = canonicalFile(input, "--input")
        val bytes = Files.readAllBytes(path)
        when (val schema = BenchmarkJson.schemaId(bytes, path.toString())) {
            PAIRED_SCHEMA -> {
                BenchmarkJson.validateSchema(bytes, path.toString(), PAIRED_SCHEMA_RESOURCE)
                BenchmarkJson.decode<BenchmarkResultV1>(bytes, path.toString())
            }
            JMH_SCHEMA -> {
                BenchmarkJson.validateSchema(bytes, path.toString(), JMH_SCHEMA_RESOURCE)
                BenchmarkJson.decode<JmhBenchmarkResultV1>(bytes, path.toString())
            }
            TARGET_SCHEMA -> TargetManifestLoader.load(path).requireClean("Target verification")
            else -> throw IllegalArgumentException("Unsupported verification schema: $schema")
        }
        return CliExitCode.SUCCESS
    }

    private fun readPairedResult(input: Path): BenchmarkResultV1 {
        val path = canonicalFile(input, "--input")
        val bytes = Files.readAllBytes(path)
        require(BenchmarkJson.schemaId(bytes, path.toString()) == PAIRED_SCHEMA) {
            "compare accepts only $PAIRED_SCHEMA evidence"
        }
        BenchmarkJson.validateSchema(bytes, path.toString(), PAIRED_SCHEMA_RESOURCE)
        return BenchmarkJson.decode(bytes, path.toString())
    }

    private fun loadWorkloadManifest(path: Path): WorkloadManifest {
        val canonical = path.toRealPath()
        require(canonical == path && Files.isRegularFile(canonical)) {
            "Workload manifest must be a canonical regular file: $path"
        }
        return BenchmarkJson.decode(Files.readAllBytes(canonical), canonical.toString())
    }

    private fun packagedWorkloadRoot(installationRoot: Path, workloadId: String): Path {
        require(workloadId.matches(PACKAGED_WORKLOAD_ID_PATTERN)) {
            "Result workload ID is not a packaged workload ID: $workloadId"
        }
        val root = installationRoot.resolve("workloads/v1").toRealPath()
        val workload = root.resolve(workloadId).toRealPath()
        require(workload.parent == root && Files.isDirectory(workload)) {
            "Result workload is not an exact packaged workload: $workloadId"
        }
        return workload
    }

    private fun requireBaselineCapturePins(
        options: RunOptions,
        baseline: LoadedTargetManifest,
        candidate: LoadedTargetManifest,
    ) {
        require(options.intent == RunIntent.CONTROLLED) {
            "capture-baseline requires --intent controlled"
        }
        require(options.baselineAdapter == FIXED_BASELINE_ADAPTER) {
            "capture-baseline requires baseline adapter $FIXED_BASELINE_ADAPTER"
        }
        require(options.candidateAdapter == FIXED_BASELINE_ADAPTER) {
            "capture-baseline requires candidate adapter $FIXED_BASELINE_ADAPTER"
        }
        listOf(baseline, candidate).forEach { target ->
            target.requireClean("capture-baseline")
            require(target.manifest.gitCommit == FIXED_BASELINE_COMMIT) {
                "capture-baseline requires full commit $FIXED_BASELINE_COMMIT for both roles"
            }
        }
    }

    private fun canonicalFile(path: Path, option: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        val canonical = absolute.toRealPath()
        require(canonical == absolute && Files.isRegularFile(canonical)) {
            "$option must be a canonical regular file: $path"
        }
        return canonical
    }

    private fun requireAbsentOutputs(outputs: List<Pair<String, Path>>): List<Path> {
        val normalized =
            outputs.map { (name, requested) ->
                val path = requested.toAbsolutePath().normalize()
                val parent = requireNotNull(path.parent) { "$name requires a parent" }
                require(Files.isDirectory(parent) && parent.toRealPath() == parent) {
                    "$name parent must be an existing canonical directory: $parent"
                }
                require(Files.isWritable(parent)) { "$name parent must be writable: $parent" }
                require(!Files.exists(path, NOFOLLOW_LINKS)) { "$name must be absent: $path" }
                name to path
            }
        require(normalized.map(Pair<String, Path>::second).distinct().size == normalized.size) {
            "Output paths must be pairwise distinct: $normalized"
        }
        return normalized.map(Pair<String, Path>::second)
    }

    private inline fun <reified T : Any> writeSchemaValidated(
        output: Path,
        value: T,
        schemaResource: String,
    ) {
        val temporary = Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
        try {
            BenchmarkJson.write(temporary, value)
            BenchmarkJson.validateSchema(temporary, schemaResource)
            Files.move(temporary, output, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeTextAtomically(output: Path, text: String) {
        val temporary = Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, text, UTF_8)
            Files.move(temporary, output, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

fun discoverInstallationRoot(): Path {
    val location =
        Path.of(
                BenchmarkDriverApplication::class.java.protectionDomain.codeSource.location.toURI()
            )
            .toAbsolutePath()
            .normalize()
    val candidate = if (Files.isRegularFile(location)) location.parent.parent else location
    require(Files.isDirectory(candidate.resolve("lib")) && Files.isDirectory(candidate.resolve("workloads"))) {
        "Cannot discover installed benchmark-driver root from $location"
    }
    return candidate.toRealPath()
}

private const val PAIRED_SCHEMA = "revoman-benchmark/v1"
private const val JMH_SCHEMA = "revoman-benchmark-jmh/v1"
private const val TARGET_SCHEMA = "revoman-target-manifest/v1"
private const val PAIRED_SCHEMA_RESOURCE = "/schema/revoman-benchmark-v1.schema.json"
private const val JMH_SCHEMA_RESOURCE = "/schema/revoman-benchmark-jmh-v1.schema.json"
private const val FIXED_BASELINE_COMMIT = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
private const val FIXED_BASELINE_ADAPTER = "baseline-83f3cd70"
private val PACKAGED_WORKLOAD_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

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
import com.salesforce.revoman.benchmark.driver.integrity.RuntimeIdentityFactory
import com.salesforce.revoman.benchmark.driver.integrity.TargetManifestLoader
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.ArtifactSnapshot
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.run.BenchmarkCampaign
import com.salesforce.revoman.benchmark.driver.run.CampaignRequest
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

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
        val baselinePath = canonicalFile(options.baseline, "--baseline")
        val candidatePath = canonicalFile(options.candidate, "--candidate")
        val baseline = TargetManifestLoader.load(baselinePath)
        val candidate = TargetManifestLoader.load(candidatePath)
        if (capture) requireBaselineCapturePins(options, baseline, candidate)
        val hostPolicyPath = options.hostPolicy?.let { canonicalFile(it, "--host-policy") }
        val hostPolicy =
            hostPolicyPath?.let { source ->
                com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy.load(
                    source
                )
            }
        val artifactDirectory = options.artifactDirectory.toAbsolutePath().normalize()
        return AtomicOutputSet.reserve(
                inputs =
                    listOf("--baseline" to baselinePath, "--candidate" to candidatePath) +
                        listOfNotNull(hostPolicyPath?.let { "--host-policy" to it }),
                outputs = listOf("--output" to options.output),
            )
            .use { outputSet ->
                val output = outputSet.paths.single()
                require(output != artifactDirectory) {
                    "--output and --artifacts-dir must be distinct"
                }
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
                prepareSchemaValidated(
                    outputSet,
                    0,
                    result,
                    "/schema/revoman-benchmark-v1.schema.json",
                )
                outputSet.publish()
                publishedCampaignExitCode(result)
            }
    }

    private fun compare(command: BenchmarkCommand.Compare, installationRoot: Path): Int {
        val input = canonicalFile(command.input, "--input")
        val result = readPairedResult(input)
        val installedHarness = RuntimeIdentityFactory(installationRoot).harnessIdentity()
        require(result.harness == installedHarness) {
            "compare input harness does not match the current installed benchmark-driver distribution"
        }
        val manifests =
            result.workloads.map { workload ->
                val root = packagedWorkloadRoot(installationRoot, workload.id)
                loadWorkloadManifest(root.resolve("manifest.json"))
            }
        val report = ReleaseGateEvaluator().evaluate(result, manifests)
        AtomicOutputSet.reserve(
                inputs = listOf("--input" to input),
                outputs =
                    listOf(
                        "--output-json" to command.outputJson,
                        "--output-md" to command.outputMarkdown,
                    ),
            )
            .use { outputSet ->
                prepareSchemaValidated(
                    outputSet,
                    0,
                    report,
                    "/schema/revoman-benchmark-comparison-v1.schema.json",
                )
                outputSet.prepare(1) { temporary ->
                    Files.writeString(temporary, report.toMarkdown())
                }
                outputSet.publish()
            }
        return if (!command.enforceReleaseGates || report.overall == GateDecision.PASS) {
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
        val baselineClasspath = baseline.manifest.pathFreeClasspath()
        val candidateClasspath = candidate.manifest.pathFreeClasspath()
        require(
            baseline.verified.classpathSha256 == candidate.verified.classpathSha256 &&
                baselineClasspath == candidateClasspath
        ) {
            "capture-baseline requires identical path-free classpath hashes and artifact snapshots"
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

    private inline fun <reified T : Any> prepareSchemaValidated(
        outputSet: AtomicOutputSet,
        index: Int,
        value: T,
        schemaResource: String,
    ) {
        outputSet.prepare(index) { temporary ->
            BenchmarkJson.write(temporary, value)
            BenchmarkJson.validateSchema(temporary, schemaResource)
        }
    }
}

private fun com.salesforce.revoman.benchmark.driver.model.TargetManifest.pathFreeClasspath():
    List<ArtifactSnapshot> =
    classpath.map { artifact ->
        ArtifactSnapshot(artifact.logicalId, artifact.sizeBytes, artifact.sha256)
    }

internal fun publishedCampaignExitCode(result: BenchmarkResultV1): Int {
    if (result.intent != RunIntent.CONTROLLED) return CliExitCode.SUCCESS
    val requested = result.configuration.requestedAcceptedBlocks
    val baselineId =
        result.configuration.targets.single {
            it.role == com.salesforce.revoman.benchmark.driver.model.TargetRole.BASELINE
        }.targetId
    val incomplete =
        result.workloads.any { workload ->
            workload.metricSeries.any { series ->
                val accepted = series.blocks.orEmpty().filter { it.accepted }
                val baselineFirst = accepted.count { block -> block.targetOrder.first() == baselineId }
                accepted.size != requested ||
                    kotlin.math.abs(baselineFirst - (accepted.size - baselineFirst)) > 1
            }
        }
    return if (incomplete) CliExitCode.EXECUTION_FAILED else CliExitCode.SUCCESS
}

/**
 * Resolves the canonical installed application root from either its launcher JAR or exploded test
 * classes.
 *
 * @return a directory containing the distribution's `lib` and `workloads` children
 * @throws IllegalArgumentException when the code source is not an installed driver layout
 */
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

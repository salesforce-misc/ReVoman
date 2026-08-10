/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.integrity.TargetManifestLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarFile

/** Verifies the installed thin-JAR classpath and fatal JMH failure paths in child JVMs. */
fun main(arguments: Array<String>) {
    require(arguments.size == 3) {
        "Usage: HarnessSelfTestMain <installation-root> <target-manifest> <adapter-id>"
    }
    val installationRoot = Path.of(arguments[0]).toRealPath()
    val targetManifestPath = Path.of(arguments[1]).toRealPath()
    val adapterId = arguments[2].also { require(it.isNotBlank()) { "adapter-id must not be blank" } }
    val verified = TargetManifestLoader.load(targetManifestPath).verified
    val manifest = verified.manifest
    try {
        verifyTargetLogicalIds(manifest)
        verifyDistributionLayout(installationRoot)
        verifyThinJar(installationRoot.resolve("lib/benchmark-driver-jmh-classes.jar"))
        verifyTruffleMultiRelease(manifest)
        verifySourceManifest(installationRoot.resolve("conf/benchmark-harness-source-v1.json"))
        verifyChildProcesses(installationRoot, targetManifestPath, manifest.targetId, adapterId)
    } finally {
        verified.postflight()
    }
}

private fun verifyTargetLogicalIds(manifest: TargetManifest) {
    val logicalIds = manifest.classpath.map { it.logicalId }
    require(logicalIds.first().startsWith("project:") && logicalIds.first().endsWith(":jar")) {
        "Target JAR must be first with a stable project logical ID: ${logicalIds.first()}"
    }
    require(logicalIds.drop(1).all { it.startsWith("maven:") || it.startsWith("project:") }) {
        "Target dependencies require stable Maven/project logical IDs: $logicalIds"
    }
    require(logicalIds.distinct().size == logicalIds.size) {
        "Target manifest logical IDs must be unique"
    }
}

private fun verifyThinJar(thinJar: Path) {
    require(Files.isRegularFile(thinJar)) { "Thin JMH classes JAR is missing: $thinJar" }
    JarFile(thinJar.toFile()).use { jar ->
        val names = jar.entries().asSequence().map { it.name }.toList()
        require(names.none { it.startsWith("org/graalvm/") }) {
            "Thin JMH classes JAR contains flattened Graal classes"
        }
        require("META-INF/BenchmarkList" in names) { "Thin JMH classes JAR lacks BenchmarkList" }
        require("META-INF/CompilerHints" in names) { "Thin JMH classes JAR lacks CompilerHints" }
        require(names.any { it.endsWith("HarnessSanityBenchmark.class") }) {
            "Thin JMH classes JAR lacks HarnessSanityBenchmark"
        }
        require(names.any { it.endsWith("WarmLifecycleAllocationBenchmark.class") }) {
            "Thin JMH classes JAR lacks WarmLifecycleAllocationBenchmark"
        }
        require(names.none { it.startsWith("com/salesforce/revoman/internal/") }) {
            "Thin JMH classes JAR contains flattened target classes"
        }
    }
}

private fun verifyDistributionLayout(installationRoot: Path) {
    listOf(
            "bin/benchmark-driver",
            "lib/benchmark-driver-jmh-classes.jar",
            "conf/log4j2-benchmark.xml",
            "conf/benchmark-harness-source-v1.json",
            "libexec/benchmark-target.init.gradle.kts",
            "schema/revoman-target-manifest-v1.schema.json",
            "schema/revoman-benchmark-v1.schema.json",
            "schema/revoman-benchmark-jmh-v1.schema.json",
            "schema/revoman-benchmark-comparison-v1.schema.json",
            "schema/revoman-controlled-host-v1.schema.json",
            "workloads/v1/lifecycle.no-script-one-step.v1/manifest.json",
            "jfr/revoman-allocation-v1.jfc",
        )
        .forEach { relative ->
            val path = installationRoot.resolve(relative)
            require(Files.isRegularFile(path)) { "Installed distribution is missing $relative" }
        }
}

private fun verifyTruffleMultiRelease(manifest: TargetManifest) {
    val truffle =
        manifest.classpath.singleOrNull {
            Path.of(it.executionPath).fileName.toString() == "truffle-api-25.2.4.jar"
        }
    requireNotNull(truffle) { "Target manifest must contain original truffle-api-25.2.4.jar" }
    JarFile(truffle.executionPath).use { jar ->
        val multiRelease =
            jar.manifest.mainAttributes.getValue(Attributes.Name.MULTI_RELEASE)?.toBoolean()
        require(multiRelease == true) {
            "truffle-api-25.2.4.jar must declare Multi-Release: true"
        }
    }
}

private fun verifySourceManifest(sourceManifest: Path) {
    require(Files.isRegularFile(sourceManifest)) {
        "Installed harness source manifest is missing: $sourceManifest"
    }
    require(!Files.readString(sourceManifest).contains("distributionSha256")) {
        "Embedded harness source manifest must not contain its own distribution hash"
    }
}

private fun verifyChildProcesses(
    installationRoot: Path,
    targetManifest: Path,
    targetId: String,
    adapterId: String,
) {
    val outputRoot = installationRoot.resolve("self-test")
    Files.createDirectories(outputRoot)
    val sanity =
        runChild(
            installationRoot = installationRoot,
            targetManifest = targetManifest,
            adapterId = adapterId,
            include = "HarnessSanityBenchmark",
            outputRoot = outputRoot,
            label = "sanity",
            forks = 2,
        )
    check(sanity.exitCode == 0) {
        "HarnessSanityBenchmark child failed (${sanity.exitCode}): ${Files.readString(sanity.log)}"
    }
    val imported =
        JmhResultImporter.`import`(
            rawResult = sanity.rawResult,
            targetId = targetId,
            requestedIncludes = listOf("HarnessSanityBenchmark"),
        )
    check(imported.benchmarks.isNotEmpty() && imported.observations.isNotEmpty()) {
        "HarnessSanityBenchmark produced no imported rows"
    }
    val forkProcessIds =
        imported.observations
            .groupBy { it.fork }
            .mapValues { (fork, observations) ->
                observations.map { it.processId }.distinct().singleOrNull()
                    ?: error("Harness sanity fork $fork did not have one stable PID")
            }
    check(forkProcessIds.size == 2 && forkProcessIds.values.distinct().size == 2) {
        "Harness sanity forks did not have distinct PIDs: $forkProcessIds"
    }
    check(sanity.processId !in forkProcessIds.values) {
        "Harness sanity observations used controller PID ${sanity.processId}: $forkProcessIds"
    }
    requireQuietOutput("Harness sanity", sanity)

    val targetSmoke =
        runChild(
            installationRoot = installationRoot,
            targetManifest = targetManifest,
            adapterId = adapterId,
            include = "SmokeBenchmark",
            outputRoot = outputRoot,
            label = "target-smoke",
        )
    check(targetSmoke.exitCode == 0) {
        "Target-loading SmokeBenchmark child failed (${targetSmoke.exitCode}): " +
            Files.readString(targetSmoke.log)
    }
    requireQuietOutput("Target-loading smoke", targetSmoke)

    val failure =
        runChild(
            installationRoot = installationRoot,
            targetManifest = targetManifest,
            adapterId = adapterId,
            include = "HarnessFailureFixtureBenchmark",
            outputRoot = outputRoot,
            label = "intentional-failure",
        )
    check(failure.exitCode != 0) { "Intentional JMH fork failure unexpectedly exited zero" }

    val unmatched =
        runChild(
            installationRoot = installationRoot,
            targetManifest = targetManifest,
            adapterId = adapterId,
            include = "DefinitelyUnmatchedBenchmark",
            outputRoot = outputRoot,
            label = "unmatched",
        )
    check(unmatched.exitCode != 0) { "Unmatched JMH include unexpectedly exited zero" }
}

private fun requireQuietOutput(label: String, result: ChildResult) {
    val output = Files.readString(result.humanOutput) + Files.readString(result.log)
    check(!output.contains("Multi-Release classes are not configured correctly")) {
        "$label reproduced the flattened multi-release failure"
    }
    check(!output.lineSequence().any { it.contains(" INFO ") }) {
        "$label emitted benchmark-contaminating INFO logs"
    }
    check(!output.contains("kotlin-logging: initializing")) {
        "$label emitted kotlin-logging startup output"
    }
}

private fun runChild(
    installationRoot: Path,
    targetManifest: Path,
    adapterId: String,
    include: String,
    outputRoot: Path,
    label: String,
    forks: Int = 1,
): ChildResult {
    val rawResult = outputRoot.resolve("$label.json")
    val normalizedResult = outputRoot.resolve("$label-normalized.json")
    val humanOutput = outputRoot.resolve("$label.txt")
    val log = outputRoot.resolve("$label-process.txt")
    listOf(rawResult, normalizedResult, humanOutput, log).forEach(Files::deleteIfExists)
    val fixtureRoot = installationRoot.resolve("workloads/v1/jmh.component-operations.v1").toRealPath()
    val logging = installationRoot.resolve("conf/log4j2-benchmark.xml").toRealPath()
    val command =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-D$RAW_RESULT_PROPERTY=$rawResult",
            "-D$RESULT_OUTPUT_PROPERTY=$normalizedResult",
            "-D$TARGET_MANIFEST_PROPERTY=$targetManifest",
            "-D$ADAPTER_PROPERTY=$adapterId",
            "-D$FIXTURE_ROOT_PROPERTY=$fixtureRoot",
            "-D$INCLUDES_PROPERTY=$include",
            "-D$INSTALLATION_ROOT_PROPERTY=$installationRoot",
            "-D$REQUESTED_FORKS_PROPERTY=$forks",
            "-D$PROFILERS_PROPERTY=",
            "-D$QUICK_PROPERTY=true",
            "-D$LOG_CONFIG_PROPERTY=${logging.toUri()}",
            "-D$LOG4J3_CONFIG_PROPERTY=${logging.toUri()}",
            "-D$KOTLIN_LOGGING_STARTUP_PROPERTY=false",
            "-Drevoman.banner=off",
            "-cp",
            System.getProperty("java.class.path"),
            "com.salesforce.revoman.benchmark.driver.jmh.JmhDriverMainKt",
            include,
            "-wi",
            "0",
            "-i",
            "1",
            "-r",
            "50ms",
            "-f",
            forks.toString(),
            "-rf",
            "json",
            "-rff",
            rawResult.toString(),
            "-o",
            humanOutput.toString(),
        )
    val process =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
    val processId = process.pid()
    return ChildResult(
        exitCode = process.waitFor(),
        processId = processId,
        rawResult = rawResult,
        humanOutput = humanOutput,
        log = log,
    )
}

private data class ChildResult(
    val exitCode: Int,
    val processId: Long,
    val rawResult: Path,
    val humanOutput: Path,
    val log: Path,
)

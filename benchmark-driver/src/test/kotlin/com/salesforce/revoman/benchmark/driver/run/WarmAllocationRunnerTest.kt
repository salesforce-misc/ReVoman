/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.jmh.FIXTURE_ROOT_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.LIFECYCLE_BASE_URL_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.LIFECYCLE_SOURCE_ROOT_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.TARGET_TOKEN_PROPERTY
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.process.JmhControllerObservation
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WarmAllocationRunnerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `five scheduled blocks launch lifecycle allocation with one independent JMH fork each`() {
        val target = runnerTarget(temporaryDirectory.resolve("target"))
        val thinJar = Files.writeString(temporaryDirectory.resolve("benchmark-driver-jmh-classes.jar"), "thin")
        val controllerJar = Files.writeString(temporaryDirectory.resolve("benchmark-driver.jar"), "controller")
        val launches = mutableListOf<WarmAllocationLaunch>()
        val rawFixture = resourcePath("/metrics/jmh-gc.txt")
        val runner =
            WarmAllocationRunner(
                WarmAllocationLauncher { launch ->
                    launches += launch
                    Files.writeString(
                        launch.rawResult,
                        Files.readString(rawFixture).replace("4201", (4_201 + launches.size).toString()),
                    )
                    JmhControllerObservation(0, 8_000L + launches.size, "", "")
                }
            )

        val results =
            (0 until 5).map { block ->
                runner.run(
                    warmAllocationPlan(
                        root = temporaryDirectory.resolve("block-$block"),
                        target = target,
                        thinJar = thinJar,
                        controllerJar = controllerJar,
                        blockId = block,
                    )
                )
            }

        assertThat(launches).hasSize(5)
        assertThat(launches.map { it.blockId }).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(launches.map { it.forkCount }.distinct()).containsExactly(1)
        assertThat(launches.map { it.profilers }.distinct()).containsExactly(listOf("gc"))
        assertThat(launches.flatMap { it.benchmarkIncludes }.distinct())
            .containsExactly("WarmLifecycleAllocationBenchmark")
        assertThat(launches.map { it.targetClasspath }.distinct())
            .containsExactly(target.classpath.map { Path.of(it.executionPath) })
        assertThat(results.flatMap { it.observations }.map { it.processId }.distinct()).hasSize(5)
    }

    @Test
    fun `warm lifecycle benchmark operation invokes prepared execute exactly once`() {
        val executions = AtomicInteger()
        val prepared =
            object : PreparedWorkload {
                override fun execute(): ExecutionDigest {
                    executions.incrementAndGet()
                    return ExecutionDigest(31, 1, 0)
                }

                override fun operation(id: String): TargetOperation = error("not used")

                override fun close() = Unit
            }

        assertThat(executeWarmLifecycleAllocation(prepared, ExecutionDigest(31, 1, 0)))
            .isEqualTo(31L)
        assertThat(executions.get()).isEqualTo(1)
    }

    @Test
    fun `warm lifecycle benchmark rejects every digest mismatch after one execute`() {
        val mismatches =
            listOf(
                "checksum" to ExecutionDigest(99, 1, 0),
                "executedSteps" to ExecutionDigest(31, 2, 0),
                "failureCount" to ExecutionDigest(31, 1, 1),
            )

        mismatches.forEach { (field, actual) ->
            val executions = AtomicInteger()
            val prepared = preparedWorkload(executions, actual)

            val failure = assertThrows<IllegalStateException> {
                executeWarmLifecycleAllocation(prepared, ExecutionDigest(31, 1, 0))
            }

            assertThat(failure).hasMessageThat().contains(field)
            assertThat(executions.get()).isEqualTo(1)
        }
    }

    @Test
    fun `warm lifecycle oracle hashes and parses one manifest read bound by controller identity`() {
        val fixtureRoot = materializeLifecycleFixture(temporaryDirectory.resolve("lifecycle"))
        val manifestPath = fixtureRoot.resolve("manifest.json")
        val manifestBytes = Files.readAllBytes(manifestPath)
        val manifestSha256 =
            com.salesforce.revoman.benchmark.driver.integrity.ContentHasher.sha256(manifestBytes)

        val expected = loadWarmLifecycleExpectedDigest(fixtureRoot, manifestSha256)

        assertThat(expected).isEqualTo(ExecutionDigest(31, 1, 0))
        val manifest = BenchmarkJson.read<WorkloadManifest>(manifestPath)
        BenchmarkJson.write(
            manifestPath,
            manifest.copy(
                expectedDigest = requireNotNull(manifest.expectedDigest).copy(checksum = 999)
            ),
        )
        val failure = assertThrows<IllegalArgumentException> {
            loadWarmLifecycleExpectedDigest(fixtureRoot, manifestSha256)
        }
        assertThat(failure).hasMessageThat().contains("lifecycle manifest SHA-256 mismatch")
    }

    @Test
    fun `campaign owned warm allocation defers target and logging postflight to composition root`() {
        val target = runnerTarget(temporaryDirectory.resolve("shared-target"))
        val verified = VerifiedTargetManifest.preflight(targetManifestPath(target), target)
        val thinJar = Files.writeString(temporaryDirectory.resolve("benchmark-driver-jmh-classes.jar"), "thin")
        val controllerJar = Files.writeString(temporaryDirectory.resolve("benchmark-driver.jar"), "controller")
        val session = Files.createDirectories(temporaryDirectory.resolve("session")).toRealPath()
        val logging = loggingConfiguration(target)
        val loggingSnapshot = logging.materialize(session)
        val rawFixture = resourcePath("/metrics/jmh-gc.txt")
        val fixtureRoot = materializeLifecycleFixture(temporaryDirectory.resolve("shared-fixture"))
        var capturedLaunch: WarmAllocationLaunch? = null
        val runner =
            WarmAllocationRunner { launch ->
                capturedLaunch = launch
                Files.writeString(launch.rawResult, Files.readString(rawFixture))
                Files.writeString(targetManifestPath(target), "changed")
                JmhControllerObservation(0, 9_001, "", "")
            }
        val plan =
            warmAllocationPlan(
                    root = temporaryDirectory.resolve("shared-output"),
                    target = target,
                    thinJar = thinJar,
                    controllerJar = controllerJar,
                    blockId = 0,
                )
                .copy(
                    verifiedTarget = verified,
                    loggingSnapshot = loggingSnapshot,
                    fixtureRoot = fixtureRoot,
                    fixtureBaseUrl = "http://127.0.0.1:54321",
                )

        val result = runner.run(plan)

        assertThat(result.observations).isNotEmpty()
        val jvmArgs = requireNotNull(capturedLaunch).command.jvmArgs
        assertThat(jvmArgs).contains("-D$FIXTURE_ROOT_PROPERTY=$fixtureRoot")
        assertThat(jvmArgs).contains("-D$LIFECYCLE_BASE_URL_PROPERTY=http://127.0.0.1:54321")
        assertThat(jvmArgs.any { it.startsWith("-D$TARGET_TOKEN_PROPERTY=") }).isTrue()
        assertThat(jvmArgs.any { it.startsWith("-D$LIFECYCLE_SOURCE_ROOT_PROPERTY=") }).isFalse()
        assertThrows<IllegalStateException> { verified.postflight() }
    }

    private fun preparedWorkload(
        executions: AtomicInteger,
        digest: ExecutionDigest,
    ): PreparedWorkload =
        object : PreparedWorkload {
            override fun execute(): ExecutionDigest {
                executions.incrementAndGet()
                return digest
            }

            override fun operation(id: String): TargetOperation = error("not used")

            override fun close() = Unit
        }

    private fun warmAllocationPlan(
        root: Path,
        target: com.salesforce.revoman.benchmark.driver.model.TargetManifest,
        thinJar: Path,
        controllerJar: Path,
        blockId: Int,
    ): WarmAllocationPlan {
        Files.createDirectories(root)
        return WarmAllocationPlan(
            intent = RunIntent.CONTROLLED,
            blockId = blockId,
            targetRole = TargetRole.BASELINE,
            fork = 0,
            target = target,
            targetManifestPath = targetManifestPath(target),
            adapterId = "baseline-83f3cd70",
            benchmarkClassesJar = thinJar.toRealPath(),
            controllerClasspath = listOf(controllerJar.toRealPath(), thinJar.toRealPath()),
            targetClasspath = target.classpath.map { Path.of(it.executionPath) },
            installationRoot = temporaryDirectory.toRealPath(),
            outputDirectory = root.toRealPath(),
            warmupIterations = 1,
            measurementIterations = 2,
            timeout = Duration.ofSeconds(5),
            loggingConfiguration = loggingConfiguration(target),
        )
    }

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing test resource: $name" }.toURI())

    private fun materializeLifecycleFixture(destination: Path): Path {
        Files.createDirectories(destination)
        listOf("manifest.json", "collection.postman_collection.json", "handler.json").forEach { name ->
            val resource = "/workloads/v1/lifecycle.no-script-one-step.v1/$name"
            requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing resource: $resource" }
                .use { input -> Files.copy(input, destination.resolve(name)) }
        }
        return destination.toRealPath()
    }
}

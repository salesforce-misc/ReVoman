/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.process.JmhControllerObservation
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
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
}

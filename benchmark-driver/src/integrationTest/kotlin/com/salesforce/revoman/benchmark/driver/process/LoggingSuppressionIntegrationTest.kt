/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.fixture.DeterministicHttpFixture
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.run.ColdPlan
import com.salesforce.revoman.benchmark.driver.run.ColdRunner
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoggingSuppressionIntegrationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `benchmark log configuration leaves target stdout and stderr empty`() {
        val target = integrationTarget()
        val fixtureRoot = materializeLifecycleFixture(temporaryDirectory.resolve("fixture"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve("manifest.json"))
        var process: ProcessObservation? = null
        val capturingLauncher = ProcessLauncher { command ->
            JdkProcessLauncher().launch(command).also { process = it }
        }

        DeterministicHttpFixture.open(manifest).use { fixture ->
            fixture.resetExecution("quiet")
            ColdRunner(capturingLauncher).run(
                ColdPlan(
                    intent = RunIntent.SMOKE,
                    target = target.target,
                    targetManifestPath = target.manifestPath,
                    adapterId = integrationAdapter(),
                    workload = lifecycleRequest(fixtureRoot, fixture.baseUrl),
                    sampleCount = 1,
                    metricPass = MetricPass.LATENCY,
                    timeout = Duration.ofSeconds(30),
                    loggingConfiguration = benchmarkLoggingConfiguration(),
                )
            )
        }

        val observed = requireNotNull(process)
        assertThat(observed.stdoutTail).isEmpty()
        assertThat(observed.stderrTail).isEmpty()
    }
}

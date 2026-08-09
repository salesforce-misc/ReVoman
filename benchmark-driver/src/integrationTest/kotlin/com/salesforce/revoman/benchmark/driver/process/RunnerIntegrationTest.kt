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
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.run.ColdPlan
import com.salesforce.revoman.benchmark.driver.run.ColdRunner
import com.salesforce.revoman.benchmark.driver.run.VerifiedLoggingConfiguration
import com.salesforce.revoman.benchmark.driver.run.WarmPlan
import com.salesforce.revoman.benchmark.driver.run.WarmRunner
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.concurrent.ExecutorService
import kotlin.system.exitProcess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RunnerIntegrationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `real subprocess smoke runs use fresh cold PIDs and one warm PID per fork`() {
        val targetSource = integrationTarget()
        val fixtureRoot = materializeLifecycleFixture(temporaryDirectory.resolve("fixture"))
        val workloadManifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve("manifest.json"))
        DeterministicHttpFixture.verifyFixture(workloadManifest, fixtureRoot)

        DeterministicHttpFixture.open(workloadManifest).use { fixture ->
            fixture.resetExecution("cold")
            val cold =
                ColdRunner(JdkProcessLauncher()).run(
                    ColdPlan(
                        intent = RunIntent.SMOKE,
                        target = targetSource.target,
                        targetManifestPath = targetSource.manifestPath,
                        adapterId = integrationAdapter(),
                        workload = lifecycleRequest(fixtureRoot, fixture.baseUrl),
                        expectedDigest = requireNotNull(workloadManifest.expectedDigest),
                        sampleCount = 3,
                        metricPass = MetricPass.LATENCY,
                        timeout = Duration.ofSeconds(30),
                        loggingConfiguration = benchmarkLoggingConfiguration(),
                    )
                )
            assertThat(cold).hasSize(3)
            assertThat(cold.map { it.processId }.distinct()).hasSize(3)
            assertThat(cold.map { it.fork }).containsExactly(0, 1, 2).inOrder()
            fixture.requestCount("cold") shouldEqual 3

            fixture.resetExecution("warm")
            val warm =
                WarmRunner(JdkProcessLauncher()).run(
                    WarmPlan(
                        intent = RunIntent.SMOKE,
                        target = targetSource.target,
                        targetManifestPath = targetSource.manifestPath,
                        adapterId = integrationAdapter(),
                        workload = lifecycleRequest(fixtureRoot, fixture.baseUrl),
                        expectedDigest = requireNotNull(workloadManifest.expectedDigest),
                        forksPerBlock = 2,
                        warmupIterations = 2,
                        measurementIterations = 3,
                        metricPass = MetricPass.LATENCY,
                        timeout = Duration.ofSeconds(30),
                        loggingConfiguration = benchmarkLoggingConfiguration(),
                    )
                )
            assertThat(warm).hasSize(6)
            assertThat(warm.map { it.processId }.distinct()).hasSize(2)
            assertThat(warm.groupBy { it.fork }.keys).containsExactly(0, 1)
            warm.groupBy { it.fork }.values.forEach { fork ->
                assertThat(fork.map { it.iteration }).containsExactly(0, 1, 2).inOrder()
            }
            fixture.requestCount("warm") shouldEqual 10
        }
    }

    @Test
    fun `target fork rejects a failing warmup before measuring or publishing a result`() {
        val target = integrationTarget()
        val fixtureRoot = materializeLifecycleFixture(temporaryDirectory.resolve("warmup-fixture"))
        val workloadManifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve("manifest.json"))
        val wrongOracle = requireNotNull(workloadManifest.expectedDigest).copy(checksum = 999)

        DeterministicHttpFixture.open(workloadManifest).use { fixture ->
            fixture.resetExecution("warmup-failure")

            val failure = assertThrows<IllegalStateException> {
                WarmRunner(JdkProcessLauncher()).run(
                    WarmPlan(
                        intent = RunIntent.SMOKE,
                        target = target.target,
                        targetManifestPath = target.manifestPath,
                        adapterId = integrationAdapter(),
                        workload = lifecycleRequest(fixtureRoot, fixture.baseUrl),
                        expectedDigest = wrongOracle,
                        forksPerBlock = 1,
                        warmupIterations = 1,
                        measurementIterations = 1,
                        metricPass = MetricPass.LATENCY,
                        timeout = Duration.ofSeconds(30),
                        loggingConfiguration = benchmarkLoggingConfiguration(),
                    )
                )
            }

            assertThat(failure).hasMessageThat().contains("exit code 1")
            fixture.requestCount("warmup-failure") shouldEqual 1
        }
    }

    @Test
    fun `launcher concurrently drains and retains only 64 KiB output tails`() {
        val command = launcherFixtureCommand("output", Duration.ofSeconds(10))

        val observation = JdkProcessLauncher().launch(command)

        assertThat(observation.stdoutTail.toByteArray()).hasLength(64 * 1024)
        assertThat(observation.stderrTail.toByteArray()).hasLength(64 * 1024)
        assertThat(observation.stdoutTail.toSet()).containsExactly('O')
        assertThat(observation.stderrTail.toSet()).containsExactly('E')
    }

    @Test
    fun `launcher rejects an in-place result write instead of guarded replacement`() {
        val command = launcherFixtureCommand("in-place", Duration.ofSeconds(10))

        val failure = assertThrows<IllegalStateException> { JdkProcessLauncher().launch(command) }

        assertThat(failure).hasMessageThat().contains("guarded result")
    }

    @Test
    fun `launcher rejects nonzero malformed empty and missing child results`() {
        listOf(
                "nonzero" to "exit code 17",
                "malformed" to "malformed result",
                "empty" to "empty or missing result",
                "missing" to "empty or missing result",
            )
            .forEach { (mode, expectedMessage) ->
                val failure = assertThrows<IllegalStateException> {
                    JdkProcessLauncher().launch(launcherFixtureCommand(mode, Duration.ofSeconds(10)))
                }
                assertThat(failure).hasMessageThat().contains(expectedMessage)
            }
    }

    @Test
    fun `launcher timeout terminates the descendant process tree`() {
        val childPidFile = temporaryDirectory.resolve("descendant.pid")
        val command =
            launcherFixtureCommand(
                mode = "timeout",
                timeout = Duration.ofSeconds(2),
                additionalParameters = mapOf("childPidFile" to childPidFile.toString()),
            )

        val failure = assertThrows<IllegalStateException> { JdkProcessLauncher().launch(command) }

        assertThat(failure).hasMessageThat().contains("timed out")
        val childPid = Files.readString(childPidFile).toLong()
        repeat(20) {
            if (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(50)
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse()
    }

    @Test
    fun `post-root drain failure terminates a tracked descendant before cleanup finalizers`() {
        val terminationFailure = DeliberateCleanupFailure("process tree termination failed")
        val shutdownFailure = DeliberateCleanupFailure("drain shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                terminationFailure = terminationFailure,
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )
        val childPidFile = temporaryDirectory.resolve("post-root-pipes-descendant.pid")
        var childPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup)
                    .launch(
                        launcherFixtureCommand(
                            mode = "orphan-pipes",
                            timeout = Duration.ofSeconds(10),
                            additionalParameters = mapOf("childPidFile" to childPidFile.toString()),
                        )
                    )
            }

            childPid = Files.readString(childPidFile).toLong()
            assertThat(failure).hasMessageThat().contains("Could not drain child process output")
            assertThat(failure.suppressed)
                .asList()
                .containsExactly(terminationFailure, shutdownFailure, guardFailure)
                .inOrder()
            assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(processIsAlive(childPid)).isFalse()
        } finally {
            childPid?.let(::forceStopFixtureProcess)
        }
    }

    @Test
    fun `post-root nonzero and malformed failures terminate tracked descendants`() {
        listOf(
                "orphan-nonzero" to "exit code 17",
                "orphan-malformed" to "malformed result",
            )
            .forEach { (mode, expectedMessage) ->
                val childPidFile = temporaryDirectory.resolve("$mode-descendant.pid")
                var childPid: Long? = null
                try {
                    val failure = assertThrows<IllegalStateException> {
                        JdkProcessLauncher()
                            .launch(
                                launcherFixtureCommand(
                                    mode = mode,
                                    timeout = Duration.ofSeconds(10),
                                    additionalParameters =
                                        mapOf("childPidFile" to childPidFile.toString()),
                                )
                            )
                    }

                    childPid = Files.readString(childPidFile).toLong()
                    assertThat(failure).hasMessageThat().contains(expectedMessage)
                    assertThat(processIsAlive(childPid)).isFalse()
                } finally {
                    childPid?.let(::forceStopFixtureProcess)
                }
            }
    }

    @Test
    fun `body failure remains primary when drain shutdown and guard cleanup fail`() {
        val shutdownFailure = DeliberateCleanupFailure("drain shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )

        val failure = assertThrows<IllegalStateException> {
            JdkProcessLauncher(cleanup)
                .launch(launcherFixtureCommand("nonzero", Duration.ofSeconds(10)))
        }

        assertThat(failure).hasMessageThat().contains("exit code 17")
        assertThat(failure.suppressed).asList().containsExactly(shutdownFailure, guardFailure).inOrder()
    }

    @Test
    fun `drain interruption restores caller status only after ordered cleanup`() {
        val interruption = InterruptedException("drain wait interrupted")
        val terminationFailure = DeliberateCleanupFailure("process tree termination failed")
        val shutdownFailure = DeliberateCleanupFailure("drain shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                terminationFailure = terminationFailure,
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )
        val interruptedAwaiter =
            OutputDrainAwaiter {
                Thread.currentThread().interrupt()
                check(Thread.interrupted())
                throw interruption
            }

        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup, interruptedAwaiter)
                    .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
            }

            assertThat(failure).hasMessageThat().contains("Could not drain child process output")
            assertThat(failure).hasCauseThat().isSameInstanceAs(interruption)
            assertThat(failure.suppressed)
                .asList()
                .containsExactly(terminationFailure, shutdownFailure, guardFailure)
                .inOrder()
            assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(cleanup.interruptedDuringCleanup).containsExactly(false, false, false).inOrder()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `timeout remains primary when process tree termination reports multiple failures`() {
        val nestedTerminationFailure = DeliberateCleanupFailure("forcible descendant cleanup failed")
        val terminationFailure =
            DeliberateCleanupFailure("process tree termination failed").also {
                it.addSuppressed(nestedTerminationFailure)
            }
        val shutdownFailure = DeliberateCleanupFailure("drain shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                terminationFailure = terminationFailure,
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )
        val childPidFile = temporaryDirectory.resolve("failing-termination-descendant.pid")
        val command =
            launcherFixtureCommand(
                mode = "timeout",
                timeout = Duration.ofSeconds(2),
                additionalParameters = mapOf("childPidFile" to childPidFile.toString()),
            )

        val failure = assertThrows<IllegalStateException> {
            JdkProcessLauncher(cleanup).launch(command)
        }

        assertThat(failure).hasMessageThat().contains("timed out")
        assertThat(failure.suppressed)
            .asList()
            .containsExactly(terminationFailure, shutdownFailure, guardFailure)
            .inOrder()
        assertThat(terminationFailure.suppressed).asList().containsExactly(nestedTerminationFailure)
        assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
        val childPid = Files.readString(childPidFile).toLong()
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse()
    }

    @Test
    fun `successful body promotes first cleanup failure and suppresses later failures`() {
        val shutdownFailure = DeliberateCleanupFailure("drain shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )

        val failure = assertThrows<DeliberateCleanupFailure> {
            JdkProcessLauncher(cleanup)
                .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
        }

        assertThat(failure).isSameInstanceAs(shutdownFailure)
        assertThat(failure.suppressed).asList().containsExactly(guardFailure)
    }

    @Test
    fun `launcher excludes bounded task shutdown from elapsed time and leaves no tracker thread`() {
        val shutdownDelay = Duration.ofMillis(300)
        val cleanup = InjectedLauncherCleanup(shutdownDelay = shutdownDelay)
        val startedAt = System.nanoTime()

        val observation =
            JdkProcessLauncher(cleanup)
                .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
        val wallNanos = System.nanoTime() - startedAt

        assertThat(wallNanos - observation.elapsedNanos).isAtLeast(shutdownDelay.toNanos())
        assertThat(
                Thread.getAllStackTraces()
                    .keys
                    .filter { thread -> thread.isAlive && thread.name.startsWith(LAUNCHER_THREAD_PREFIX) }
            )
            .isEmpty()
    }

    @Test
    fun `unexpected tracker failure invalidates evidence and triggers ordered cleanup`() {
        val trackingFailure = DeliberateCleanupFailure("descendant tracking failed")
        val cleanup = InjectedLauncherCleanup()
        val trackerFactory =
            ProcessTreeTrackerFactory {
                FailingProcessTreeTracking(trackingFailure)
            }

        val failure = assertThrows<IllegalStateException> {
            JdkProcessLauncher(cleanup, trackerFactory)
                .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
        }

        assertThat(failure).hasMessageThat().contains("Could not track target process descendants")
        assertThat(failure).hasCauseThat().isSameInstanceAs(trackingFailure)
        assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
    }

    private fun launcherFixtureCommand(
        mode: String,
        timeout: Duration,
        additionalParameters: Map<String, String> = emptyMap(),
    ): JavaCommand {
        val target = integrationTarget()
        val verified = VerifiedTargetManifest.preflight(target.manifestPath)
        val caseRoot = Files.createDirectories(temporaryDirectory.resolve("launcher-$mode"))
        val commandPath = caseRoot.resolve("command.json").toAbsolutePath().normalize()
        val resultPath = caseRoot.resolve("result.json").toAbsolutePath().normalize()
        BenchmarkJson.write(
            commandPath,
            TargetForkCommand(
                verification =
                    TargetVerificationToken(
                        targetManifest = target.manifestPath.toString(),
                        targetManifestSha256 = verified.manifestSha256,
                        targetClasspathSha256 = verified.classpathSha256,
                        artifactStamps = verified.artifactStamps,
                    ),
                adapterId = integrationAdapter(),
                mode = RunMode.WARM,
                metricPass = MetricPass.LATENCY,
                workload =
                    WorkloadRequest(
                        id = "launcher-fixture",
                        contractVersion = 1,
                        fixtureRoot = caseRoot.toString(),
                        baseUrl = "http://127.0.0.1:1",
                        parameters = additionalParameters + ("mode" to mode),
                    ),
                expectedDigest = null,
                warmupIterations = 0,
                measurementIterations = 1,
                resultFile = resultPath.toString(),
            ),
        )
        return JavaCommand(
            executable = javaExecutable(),
            jvmArgs = emptyList(),
            classpath = currentClasspath(),
            mainClass = ProcessLauncherFixtureMain::class.java.name,
            programArgs = listOf(commandPath.toString()),
            workingDirectory = Path.of(System.getProperty("user.dir")).toRealPath(),
            timeout = timeout,
        )
    }
}

class ProcessLauncherFixtureMain {
    companion object {
        @JvmStatic
        fun main(arguments: Array<String>) {
            val command = BenchmarkJson.read<TargetForkCommand>(Path.of(arguments.single()))
            when (command.workload.parameters.getValue("mode")) {
                "output" -> {
                    System.out.print("O".repeat(80 * 1024))
                    System.err.print("E".repeat(80 * 1024))
                    writeFixtureResult(command)
                }
                "valid" -> writeFixtureResult(command)
                "in-place" -> writeFixtureResultInPlace(command)
                "nonzero" -> exitProcess(17)
                "malformed" -> writeRawAtomically(Path.of(command.resultFile), "{malformed".toByteArray())
                "empty" -> writeRawAtomically(Path.of(command.resultFile), byteArrayOf())
                "missing" -> Unit
                "timeout" -> {
                    val descendant =
                        ProcessBuilder(
                                javaExecutable().toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SleepingDescendantMain::class.java.name,
                            )
                            .start()
                    Files.writeString(
                        Path.of(command.workload.parameters.getValue("childPidFile")),
                        descendant.pid().toString(),
                    )
                    Thread.sleep(Duration.ofMinutes(5))
                }
                "orphan-pipes" -> spawnLongLivedDescendant(command, inheritPipes = true)
                "orphan-nonzero" -> {
                    spawnLongLivedDescendant(command, inheritPipes = false)
                    exitProcess(17)
                }
                "orphan-malformed" -> {
                    spawnLongLivedDescendant(command, inheritPipes = false)
                    writeRawAtomically(Path.of(command.resultFile), "{malformed".toByteArray())
                }
                else -> error("Unknown launcher fixture mode")
            }
        }
    }
}

class SleepingDescendantMain {
    companion object {
        @JvmStatic
        fun main(arguments: Array<String>) {
            require(arguments.isEmpty())
            Thread.sleep(Duration.ofMinutes(5))
        }
    }
}

private fun writeFixtureResult(command: TargetForkCommand) {
    BenchmarkJson.write(Path.of(command.resultFile), fixtureResult(command))
}

private fun spawnLongLivedDescendant(command: TargetForkCommand, inheritPipes: Boolean) {
    val builder =
        ProcessBuilder(
            javaExecutable().toString(),
            "-cp",
            System.getProperty("java.class.path"),
            SleepingDescendantMain::class.java.name,
        )
    if (inheritPipes) {
        builder.inheritIO()
    } else {
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    }
    val descendant = builder.start()
    Files.writeString(
        Path.of(command.workload.parameters.getValue("childPidFile")),
        descendant.pid().toString(),
    )
    Thread.sleep(Duration.ofMillis(500))
}

private fun writeFixtureResultInPlace(command: TargetForkCommand) {
    val result = Path.of(command.resultFile)
    val encoded = result.resolveSibling("encoded-result.json")
    BenchmarkJson.write(encoded, fixtureResult(command))
    Files.write(result, Files.readAllBytes(encoded))
}

private fun writeRawAtomically(result: Path, bytes: ByteArray) {
    val temporary = result.resolveSibling(".${result.fileName}.fixture.tmp")
    Files.write(temporary, bytes)
    Files.move(temporary, result, ATOMIC_MOVE, REPLACE_EXISTING)
}

private fun fixtureResult(command: TargetForkCommand): TargetForkResult =
    TargetForkResult(
        processId = ProcessHandle.current().pid(),
        warmupIterations = command.warmupIterations,
        measurementIterations = command.measurementIterations,
        samples =
            listOf(
                TargetSample(
                    iteration = 0,
                    latencyNanos = 123,
                    digest = ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0),
                )
            ),
    )

internal data class IntegrationTarget(val manifestPath: Path, val target: TargetManifest)

internal fun integrationTarget(): IntegrationTarget {
    val path =
        Path.of(
                requireNotNull(System.getProperty("revoman.benchmark.targetManifest")) {
                    "revoman.benchmark.targetManifest is required"
                }
            )
            .toRealPath()
    return IntegrationTarget(path, BenchmarkJson.read(path))
}

internal fun integrationAdapter(): String =
    requireNotNull(System.getProperty("revoman.benchmark.adapter")) {
        "revoman.benchmark.adapter is required"
    }

internal fun benchmarkLoggingConfigurationPath(): Path {
    val workingDirectory = Path.of(System.getProperty("user.dir")).toRealPath()
    return listOf(
            workingDirectory.resolve("benchmark-driver/src/main/dist/conf/log4j2-benchmark.xml"),
            workingDirectory.resolve("src/main/dist/conf/log4j2-benchmark.xml"),
        )
        .first(Files::isRegularFile)
        .toRealPath()
}

internal fun benchmarkLoggingConfiguration(): VerifiedLoggingConfiguration =
    VerifiedLoggingConfiguration.preflight(benchmarkLoggingConfigurationPath())

internal fun materializeLifecycleFixture(destination: Path): Path {
    Files.createDirectories(destination)
    listOf("manifest.json", "collection.postman_collection.json", "handler.json").forEach { fileName ->
        val resource = "/workloads/v1/lifecycle.no-script-one-step.v1/$fileName"
        requireNotNull(RunnerIntegrationTest::class.java.getResourceAsStream(resource)) {
                "Missing resource: $resource"
            }
            .use { input -> Files.copy(input, destination.resolve(fileName)) }
    }
    return destination.toRealPath()
}

internal fun lifecycleRequest(fixtureRoot: Path, baseUrl: String): WorkloadRequest =
    WorkloadRequest(
        id = "lifecycle.no-script-one-step.v1",
        contractVersion = 1,
        fixtureRoot = fixtureRoot.toString(),
        baseUrl = baseUrl,
    )

internal fun javaExecutable(): Path =
    Path.of(System.getProperty("java.home")).toRealPath().resolve("bin/java")

internal fun currentClasspath(): List<Path> {
    val workingDirectory = Path.of(System.getProperty("user.dir")).toRealPath()
    return System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .filter(String::isNotBlank)
        .map { entry ->
            val path = Path.of(entry)
            (if (path.isAbsolute) path else workingDirectory.resolve(path)).normalize()
        }
}

private infix fun Int.shouldEqual(expected: Int) {
    assertThat(this).isEqualTo(expected)
}

private fun processIsAlive(processId: Long): Boolean =
    ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)

private fun forceStopFixtureProcess(processId: Long) {
    ProcessHandle.of(processId).ifPresent(ProcessHandle::destroyForcibly)
    repeat(100) {
        if (!processIsAlive(processId)) return
        Thread.sleep(20)
    }
}

private class DeliberateCleanupFailure(message: String) : RuntimeException(message)

private class FailingProcessTreeTracking(private val failure: Throwable) : ProcessTreeTracking {
    override fun run() = Unit

    override fun stopSampling() = Unit

    override fun failureOrNull(): Throwable = failure

    override fun snapshot(): ProcessTreeSnapshot = ProcessTreeSnapshot(emptyList())
}

private class InjectedLauncherCleanup(
    private val terminationFailure: Throwable? = null,
    private val shutdownFailure: Throwable? = null,
    private val guardFailure: Throwable? = null,
    private val shutdownDelay: Duration = Duration.ZERO,
) : LauncherCleanup {
    val calls = mutableListOf<String>()
    val interruptedDuringCleanup = mutableListOf<Boolean>()
    var terminationCalls = 0
        private set

    override fun terminateProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
        freezeTracking: () -> Unit,
    ) {
        calls += "termination"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        terminationCalls += 1
        DefaultLauncherCleanup.terminateProcessTree(process, retainedDescendants, freezeTracking)
        terminationFailure?.let { throw it }
    }

    override fun shutdownOutputDrains(executor: ExecutorService) {
        calls += "shutdown"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        if (!shutdownDelay.isZero) Thread.sleep(shutdownDelay)
        DefaultLauncherCleanup.shutdownOutputDrains(executor)
        shutdownFailure?.let { throw it }
    }

    override fun deleteGuard(path: Path) {
        calls += "guard"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        DefaultLauncherCleanup.deleteGuard(path)
        guardFailure?.let { throw it }
    }
}

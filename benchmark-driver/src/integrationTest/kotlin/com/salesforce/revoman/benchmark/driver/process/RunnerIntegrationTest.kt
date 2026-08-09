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
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    fun `pid reader waits for complete atomic publication`() {
        val pidFile = Files.createFile(temporaryDirectory.resolve("delayed-process.pid"))
        val publisherFailure = AtomicReference<Throwable?>()
        val publisher =
            Thread.ofVirtual().name("revoman-benchmark-pid-publisher").start {
                try {
                    Thread.sleep(100)
                    writeFixtureTextAtomically(pidFile, "4242")
                } catch (caught: Throwable) {
                    publisherFailure.set(caught)
                }
            }
        try {
            assertThat(awaitProcessId(pidFile)).isEqualTo(4242)
            publisherFailure.get()?.let { throw AssertionError("PID publication failed", it) }
        } finally {
            publisher.join(Duration.ofSeconds(2))
            if (publisher.isAlive) publisher.interrupt()
            publisher.join(Duration.ofSeconds(2))
            check(!publisher.isAlive) { "PID publisher did not stop" }
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
        var childPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> { JdkProcessLauncher().launch(command) }

            assertThat(failure).hasMessageThat().contains("timed out")
            childPid = awaitProcessId(childPidFile)
            awaitProcessExit(childPid)
            assertThat(processIsAlive(childPid)).isFalse()
        } finally {
            childPid = childPid ?: readProcessIdIfPresent(childPidFile)
            childPid?.let(::forceStopFixtureProcess)
        }
    }

    @Test
    fun `successful child with redirected live descendant is invalid and cleaned`() {
        val childPidFile = temporaryDirectory.resolve("valid-orphan-descendant.pid")
        val rootReleaseFile = temporaryDirectory.resolve("valid-orphan-root.release")
        val shutdownFailure = DeliberateCleanupFailure("launcher task shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )
        val tracker = AtomicReference<ProcessTreeTracking>()
        val coordinator = releaseRootAfterDescendantIsTracked(childPidFile, rootReleaseFile, tracker)
        var childPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup, retainingTrackerFactory(tracker))
                    .launch(
                        launcherFixtureCommand(
                            mode = "valid-orphan",
                            timeout = Duration.ofSeconds(10),
                            additionalParameters =
                                mapOf(
                                    "childPidFile" to childPidFile.toString(),
                                    "rootReleaseFile" to rootReleaseFile.toString(),
                                ),
                        )
                    )
            }

            childPid = awaitProcessId(childPidFile)
            assertThat(failure).hasMessageThat().contains("live descendant")
            assertThat(failure.suppressed)
                .asList()
                .containsExactly(shutdownFailure, guardFailure)
                .inOrder()
            assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(processIsAlive(childPid)).isFalse()
        } finally {
            Files.writeString(rootReleaseFile, "release")
            val coordinationFailure = runCatching(coordinator::await).exceptionOrNull()
            childPid = childPid ?: readProcessIdIfPresent(childPidFile)
            childPid?.let(::forceStopFixtureProcess)
            coordinationFailure?.let { throw it }
        }
    }

    @Test
    fun `tracker retains a child that spawns a grandchild after root exit`() {
        val childPidFile = temporaryDirectory.resolve("late-grandchild-child.pid")
        val grandchildPidFile = temporaryDirectory.resolve("late-grandchild.pid")
        val rootReleaseFile = temporaryDirectory.resolve("late-grandchild-root.release")
        val grandchildTrackedFile = temporaryDirectory.resolve("late-grandchild-tracked.release")
        val tracker = AtomicReference<ProcessTreeTracking>()
        val coordinator =
            coordinateLateGrandchild(
                childPidFile = childPidFile,
                grandchildPidFile = grandchildPidFile,
                rootReleaseFile = rootReleaseFile,
                grandchildTrackedFile = grandchildTrackedFile,
                trackerReference = tracker,
            )
        var childPid: Long? = null
        var grandchildPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(DefaultLauncherCleanup, retainingTrackerFactory(tracker))
                    .launch(
                        launcherFixtureCommand(
                            mode = "late-grandchild",
                            timeout = Duration.ofSeconds(10),
                            additionalParameters =
                                mapOf(
                                    "childPidFile" to childPidFile.toString(),
                                    "grandchildPidFile" to grandchildPidFile.toString(),
                                    "rootReleaseFile" to rootReleaseFile.toString(),
                                    "grandchildTrackedFile" to grandchildTrackedFile.toString(),
                                ),
                        )
                    )
            }

            childPid = awaitProcessId(childPidFile)
            grandchildPid = awaitProcessId(grandchildPidFile)
            assertThat(failure).hasMessageThat().contains("live descendant")
            assertThat(processIsAlive(childPid)).isFalse()
            assertThat(processIsAlive(grandchildPid)).isFalse()
        } finally {
            Files.writeString(rootReleaseFile, "release")
            Files.writeString(grandchildTrackedFile, "release")
            val coordinationFailure = runCatching(coordinator::await).exceptionOrNull()
            childPid = childPid ?: readProcessIdIfPresent(childPidFile)
            grandchildPid = grandchildPid ?: readProcessIdIfPresent(grandchildPidFile)
            childPid?.let(::forceStopFixtureProcess)
            grandchildPid?.let(::forceStopFixtureProcess)
            coordinationFailure?.let { throw it }
        }
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
        val rootReleaseFile = temporaryDirectory.resolve("post-root-pipes-root.release")
        val tracker = AtomicReference<ProcessTreeTracking>()
        val coordinator = releaseRootAfterDescendantIsTracked(childPidFile, rootReleaseFile, tracker)
        var childPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup, retainingTrackerFactory(tracker))
                    .launch(
                        launcherFixtureCommand(
                            mode = "orphan-pipes",
                            timeout = Duration.ofSeconds(10),
                            additionalParameters =
                                mapOf(
                                    "childPidFile" to childPidFile.toString(),
                                    "rootReleaseFile" to rootReleaseFile.toString(),
                                ),
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
            Files.writeString(rootReleaseFile, "release")
            val coordinationFailure = runCatching(coordinator::await).exceptionOrNull()
            childPid = childPid ?: readProcessIdIfPresent(childPidFile)
            childPid?.let(::forceStopFixtureProcess)
            coordinationFailure?.let { throw it }
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
                val rootReleaseFile = temporaryDirectory.resolve("$mode-root.release")
                val tracker = AtomicReference<ProcessTreeTracking>()
                val coordinator =
                    releaseRootAfterDescendantIsTracked(childPidFile, rootReleaseFile, tracker)
                var childPid: Long? = null
                try {
                    val failure = assertThrows<IllegalStateException> {
                        JdkProcessLauncher(DefaultLauncherCleanup, retainingTrackerFactory(tracker))
                            .launch(
                                launcherFixtureCommand(
                                    mode = mode,
                                    timeout = Duration.ofSeconds(10),
                                    additionalParameters =
                                        mapOf(
                                            "childPidFile" to childPidFile.toString(),
                                            "rootReleaseFile" to rootReleaseFile.toString(),
                                        ),
                                )
                            )
                    }

                    childPid = Files.readString(childPidFile).toLong()
                    assertThat(failure).hasMessageThat().contains(expectedMessage)
                    assertThat(processIsAlive(childPid)).isFalse()
                } finally {
                    Files.writeString(rootReleaseFile, "release")
                    val coordinationFailure = runCatching(coordinator::await).exceptionOrNull()
                    childPid = childPid ?: readProcessIdIfPresent(childPidFile)
                    childPid?.let(::forceStopFixtureProcess)
                    coordinationFailure?.let { throw it }
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
        assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
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

        var childPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup).launch(command)
            }

            assertThat(failure).hasMessageThat().contains("timed out")
            assertThat(failure.suppressed)
                .asList()
                .containsExactly(terminationFailure, shutdownFailure, guardFailure)
                .inOrder()
            assertThat(terminationFailure.suppressed)
                .asList()
                .containsExactly(nestedTerminationFailure)
            assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            childPid = awaitProcessId(childPidFile)
            assertThat(processIsAlive(childPid)).isFalse()
        } finally {
            childPid = childPid ?: readProcessIdIfPresent(childPidFile)
            childPid?.let(::forceStopFixtureProcess)
        }
    }

    @Test
    fun `successful body promotes first cleanup failure and suppresses later failures`() {
        val terminationFailure = DeliberateCleanupFailure("process tree finalization failed")
        val shutdownFailure = DeliberateCleanupFailure("drain shutdown failed")
        val guardFailure = DeliberateCleanupFailure("guard delete failed")
        val cleanup =
            InjectedLauncherCleanup(
                terminationFailure = terminationFailure,
                shutdownFailure = shutdownFailure,
                guardFailure = guardFailure,
            )

        val failure = assertThrows<DeliberateCleanupFailure> {
            JdkProcessLauncher(cleanup)
                .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
        }

        assertThat(failure).isSameInstanceAs(terminationFailure)
        assertThat(failure.suppressed).asList().containsExactly(shutdownFailure, guardFailure).inOrder()
        assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
    }

    @Test
    fun `successful body finalizes its owned process tree exactly once`() {
        val cleanup = InjectedLauncherCleanup()

        val observation =
            JdkProcessLauncher(cleanup)
                .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))

        assertThat(observation.exitCode).isEqualTo(0)
        assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
    }

    @Test
    fun `outer tracker join continues one finalization and force kills its late retained handle`() {
        val lateProcess =
            ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SleepingDescendantMain::class.java.name,
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val lateHandle = lateProcess.toHandle()
        val tracker = LatePublishingProcessTreeTracking(lateHandle)
        val cleanup = InjectedLauncherCleanup()
        val clock = MutableIntegrationMonotonicClock()
        val deadline =
            ProcessTreeFinalizationDeadline.start(
                clock = clock,
                budget =
                    ProcessTreeFinalizationBudget(
                        totalNanos = Duration.ofSeconds(2).toNanos(),
                        trackerJoinNanos = Duration.ofMillis(500).toNanos(),
                        postFreezeNanos = Duration.ofSeconds(1).toNanos(),
                    ),
            )
        clock.advanceTo(Duration.ofSeconds(1).toNanos())
        try {
            assertThrows<TimeoutException> {
                JdkProcessLauncher(
                        cleanup = cleanup,
                        trackerFactory = ProcessTreeTrackerFactory { tracker },
                        finalizationDeadlineFactory =
                            ProcessTreeFinalizationDeadlineFactory { deadline },
                    )
                    .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
            }

            awaitProcessExit(lateProcess.pid())
            assertThat(processIsAlive(lateProcess.pid())).isFalse()
            assertThat(tracker.stopCalls.get()).isEqualTo(3)
            assertThat(cleanup.calls)
                .containsExactly("termination", "continuation", "shutdown", "guard")
                .inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(cleanup.continuationCalls).isEqualTo(1)
        } finally {
            forceStopFixtureProcess(lateProcess.pid())
        }
    }

    @Test
    fun `launcher shutdown continues one finalization after both tracker joins time out`() {
        val lateProcess =
            ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SleepingDescendantMain::class.java.name,
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val tracker = ShutdownPublishingProcessTreeTracking(lateProcess.toHandle())
        val cleanup = InjectedLauncherCleanup()
        val clock = MutableIntegrationMonotonicClock()
        val deadline =
            ProcessTreeFinalizationDeadline.start(
                clock = clock,
                budget =
                    ProcessTreeFinalizationBudget(
                        totalNanos = 100,
                        trackerJoinNanos = 20,
                        postFreezeNanos = 10,
                    ),
            )
        clock.advanceTo(90)
        try {
            assertThrows<TimeoutException> {
                JdkProcessLauncher(
                        cleanup = cleanup,
                        trackerFactory = ProcessTreeTrackerFactory { tracker },
                        finalizationDeadlineFactory =
                            ProcessTreeFinalizationDeadlineFactory { deadline },
                    )
                    .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
            }

            awaitProcessExit(lateProcess.pid())
            assertThat(processIsAlive(lateProcess.pid())).isFalse()
            assertThat(cleanup.calls)
                .containsExactly("termination", "shutdown", "continuation", "guard")
                .inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(cleanup.continuationCalls).isEqualTo(1)
        } finally {
            forceStopFixtureProcess(lateProcess.pid())
        }
    }

    @Test
    fun `launcher shutdown failure still continues one finalization and kills its published handle`() {
        val lateProcess =
            ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SleepingDescendantMain::class.java.name,
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val shutdownFailure = DeliberateCleanupFailure("launcher task shutdown failed")
        val tracker = ShutdownPublishingProcessTreeTracking(lateProcess.toHandle())
        val cleanup = InjectedLauncherCleanup(shutdownFailure = shutdownFailure)
        val clock = MutableIntegrationMonotonicClock()
        val deadline =
            ProcessTreeFinalizationDeadline.start(
                clock = clock,
                budget =
                    ProcessTreeFinalizationBudget(
                        totalNanos = 100,
                        trackerJoinNanos = 20,
                        postFreezeNanos = 10,
                    ),
            )
        clock.advanceTo(90)
        var launchFailure: TimeoutException? = null
        try {
            launchFailure =
                assertThrows<TimeoutException> {
                    JdkProcessLauncher(
                            cleanup = cleanup,
                            trackerFactory = ProcessTreeTrackerFactory { tracker },
                            finalizationDeadlineFactory =
                                ProcessTreeFinalizationDeadlineFactory { deadline },
                        )
                        .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
                }
        } finally {
            try {
                awaitProcessExit(lateProcess.pid())
                assertThat(processIsAlive(lateProcess.pid())).isFalse()
                assertThat(cleanup.calls)
                    .containsExactly("termination", "shutdown", "continuation", "guard")
                    .inOrder()
                assertThat(cleanup.terminationCalls).isEqualTo(1)
                assertThat(cleanup.continuationCalls).isEqualTo(1)
            } finally {
                forceStopFixtureProcess(lateProcess.pid())
            }
        }
        val failure = requireNotNull(launchFailure)
        assertThat(failure.suppressed).hasLength(3)
        assertThat(failure.suppressed[0]).isInstanceOf(TimeoutException::class.java)
        assertThat(failure.suppressed[1]).isSameInstanceAs(shutdownFailure)
    }

    @Test
    fun `tracker failure published during shutdown is retained after join timeout evidence`() {
        val trackingFailure = DeliberateCleanupFailure("late descendant tracking failure")
        val tracker = ShutdownFailingProcessTreeTracking(trackingFailure)
        val cleanup = InjectedLauncherCleanup()
        val clock = MutableIntegrationMonotonicClock()
        val deadline =
            ProcessTreeFinalizationDeadline.start(
                clock = clock,
                budget =
                    ProcessTreeFinalizationBudget(
                        totalNanos = 100,
                        trackerJoinNanos = 20,
                        postFreezeNanos = 10,
                    ),
            )
        clock.advanceTo(90)

        val failure =
            assertThrows<TimeoutException> {
                JdkProcessLauncher(
                        cleanup = cleanup,
                        trackerFactory = ProcessTreeTrackerFactory { tracker },
                        finalizationDeadlineFactory =
                            ProcessTreeFinalizationDeadlineFactory { deadline },
                    )
                    .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
            }

        assertThat(cleanup.calls)
            .containsExactly("termination", "shutdown", "continuation", "guard")
            .inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
        assertThat(cleanup.continuationCalls).isEqualTo(1)
        assertThat(failure.suppressed).hasLength(2)
        assertThat(failure.suppressed[0]).isInstanceOf(TimeoutException::class.java)
        assertThat(failure.suppressed[1])
            .hasMessageThat()
            .contains("Could not track target process descendants")
        assertThat(failure.suppressed[1]).hasCauseThat().isSameInstanceAs(trackingFailure)
    }

    @Test
    fun `same tracker failure before and after shutdown is added once by identity`() {
        val trackingFailure = DeliberateCleanupFailure("persistent descendant tracking failure")
        val tracker = PersistentFailingProcessTreeTracking(trackingFailure)
        val cleanup = InjectedLauncherCleanup()
        val clock = MutableIntegrationMonotonicClock()
        val deadline =
            ProcessTreeFinalizationDeadline.start(
                clock = clock,
                budget =
                    ProcessTreeFinalizationBudget(
                        totalNanos = 100,
                        trackerJoinNanos = 20,
                        postFreezeNanos = 10,
                    ),
            )
        clock.advanceTo(90)

        val failure =
            assertThrows<TimeoutException> {
                JdkProcessLauncher(
                        cleanup = cleanup,
                        trackerFactory = ProcessTreeTrackerFactory { tracker },
                        finalizationDeadlineFactory =
                            ProcessTreeFinalizationDeadlineFactory { deadline },
                    )
                    .launch(launcherFixtureCommand("valid", Duration.ofSeconds(10)))
            }

        assertThat(cleanup.calls)
            .containsExactly("termination", "shutdown", "continuation", "guard")
            .inOrder()
        assertThat(cleanup.terminationCalls).isEqualTo(1)
        assertThat(cleanup.continuationCalls).isEqualTo(1)
        assertThat(failure.suppressed).hasLength(2)
        assertThat(failure.suppressed[0]).isInstanceOf(TimeoutException::class.java)
        assertThat(failure.suppressed[1])
            .hasMessageThat()
            .contains("Could not track target process descendants")
        assertThat(failure.suppressed[1]).hasCauseThat().isSameInstanceAs(trackingFailure)
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
    fun `unexpected tracker failure promptly finalizes a long-lived root`() {
        val trackingFailure = DeliberateCleanupFailure("descendant tracking failed")
        val cleanup = InjectedLauncherCleanup()
        val rootPidFile = temporaryDirectory.resolve("tracker-failure-root.pid")
        val trackerFactory =
            ProcessTreeTrackerFactory {
                FailingProcessTreeTracking(trackingFailure) {
                    awaitFixtureCondition("long-lived root process to start") {
                        Files.isRegularFile(rootPidFile)
                    }
                }
            }
        val startedAt = System.nanoTime()
        var rootPid: Long? = null
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup, trackerFactory)
                    .launch(
                        launcherFixtureCommand(
                            mode = "long-lived",
                            timeout = Duration.ofSeconds(30),
                            additionalParameters = mapOf("rootPidFile" to rootPidFile.toString()),
                        )
                    )
            }

            val elapsed = System.nanoTime() - startedAt
            rootPid = awaitProcessId(rootPidFile)
            assertThat(failure).hasMessageThat().contains("Could not track target process descendants")
            assertThat(failure).hasCauseThat().isSameInstanceAs(trackingFailure)
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5).toNanos())
            assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(processIsAlive(rootPid)).isFalse()
        } finally {
            rootPid = rootPid ?: readProcessIdIfPresent(rootPidFile)
            rootPid?.let(::forceStopFixtureTree)
        }
    }

    @Test
    fun `tracker overflow promptly finalizes a long-lived process tree`() {
        val cleanup = InjectedLauncherCleanup()
        val rootPidFile = temporaryDirectory.resolve("tracker-overflow-root.pid")
        val childPidFile = temporaryDirectory.resolve("tracker-overflow-children.pid")
        val trackerFactory =
            ProcessTreeTrackerFactory { root ->
                DeferredProcessTreeTracking(
                    delegate = ProcessTreeTracker(root, maxTrackedDescendants = 1),
                    beforeRun = {
                        awaitFixtureCondition("overflow fixture children to start") {
                            readProcessIdsIfPresent(childPidFile).size == 2
                        }
                    },
                )
            }
        val startedAt = System.nanoTime()
        var rootPid: Long? = null
        var childPids = emptyList<Long>()
        try {
            val failure = assertThrows<IllegalStateException> {
                JdkProcessLauncher(cleanup, trackerFactory)
                    .launch(
                        launcherFixtureCommand(
                            mode = "tracker-overflow",
                            timeout = Duration.ofSeconds(30),
                            additionalParameters =
                                mapOf(
                                    "rootPidFile" to rootPidFile.toString(),
                                    "childPidFile" to childPidFile.toString(),
                                ),
                        )
                    )
            }

            val elapsed = System.nanoTime() - startedAt
            rootPid = awaitProcessId(rootPidFile)
            childPids = awaitProcessIds(childPidFile, expectedCount = 2)
            assertThat(failure).hasMessageThat().contains("Could not track target process descendants")
            assertThat(failure).hasCauseThat().hasMessageThat().contains("exceeded 1 live descendants")
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5).toNanos())
            assertThat(cleanup.calls).containsExactly("termination", "shutdown", "guard").inOrder()
            assertThat(cleanup.terminationCalls).isEqualTo(1)
            assertThat(processIsAlive(rootPid)).isFalse()
            childPids.forEach { childPid -> assertThat(processIsAlive(childPid)).isFalse() }
        } finally {
            rootPid = rootPid ?: readProcessIdIfPresent(rootPidFile)
            childPids = if (childPids.isEmpty()) readProcessIdsIfPresent(childPidFile) else childPids
            rootPid?.let(::forceStopFixtureTree)
            childPids.forEach(::forceStopFixtureProcess)
        }
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
                    writeFixtureTextAtomically(
                        Path.of(command.workload.parameters.getValue("childPidFile")),
                        descendant.pid().toString(),
                    )
                    CountDownLatch(1).await()
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
                "valid-orphan" -> {
                    spawnLongLivedDescendant(command, inheritPipes = false)
                    writeFixtureResult(command)
                }
                "late-grandchild" -> {
                    spawnLateGrandchildChild(command)
                    writeFixtureResult(command)
                }
                "long-lived" -> {
                    writeFixtureTextAtomically(
                        Path.of(command.workload.parameters.getValue("rootPidFile")),
                        ProcessHandle.current().pid().toString(),
                    )
                    CountDownLatch(1).await()
                }
                "tracker-overflow" -> spawnOverflowDescendants(command)
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
            CountDownLatch(1).await()
        }
    }
}

class LateGrandchildChildMain {
    companion object {
        @JvmStatic
        fun main(arguments: Array<String>) {
            require(arguments.size == 4)
            val rootPid = arguments[0].toLong()
            val childPidFile = Path.of(arguments[1])
            val grandchildPidFile = Path.of(arguments[2])
            val grandchildTrackedFile = Path.of(arguments[3])
            writeFixtureTextAtomically(childPidFile, ProcessHandle.current().pid().toString())
            awaitFixtureCondition("root process $rootPid to exit") { !processIsAlive(rootPid) }
            val grandchild =
                ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        SleepingDescendantMain::class.java.name,
                    )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            writeFixtureTextAtomically(grandchildPidFile, grandchild.pid().toString())
            awaitFixtureCondition("grandchild tracker acknowledgement") {
                Files.isRegularFile(grandchildTrackedFile)
            }
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
    writeFixtureTextAtomically(
        Path.of(command.workload.parameters.getValue("childPidFile")),
        descendant.pid().toString(),
    )
    awaitFixtureCondition("root release") {
        Files.isRegularFile(Path.of(command.workload.parameters.getValue("rootReleaseFile")))
    }
}

private fun spawnLateGrandchildChild(command: TargetForkCommand) {
    val parameters = command.workload.parameters
    val child =
        ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LateGrandchildChildMain::class.java.name,
                ProcessHandle.current().pid().toString(),
                parameters.getValue("childPidFile"),
                parameters.getValue("grandchildPidFile"),
                parameters.getValue("grandchildTrackedFile"),
            )
            .inheritIO()
            .start()
    awaitFixtureCondition("late-grandchild child ${child.pid()} to become ready") {
        Files.isRegularFile(Path.of(parameters.getValue("childPidFile")))
    }
    awaitFixtureCondition("root release") {
        Files.isRegularFile(Path.of(parameters.getValue("rootReleaseFile")))
    }
}

private fun spawnOverflowDescendants(command: TargetForkCommand) {
    val parameters = command.workload.parameters
    writeFixtureTextAtomically(
        Path.of(parameters.getValue("rootPidFile")),
        ProcessHandle.current().pid().toString(),
    )
    val descendants =
        List(2) {
            ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SleepingDescendantMain::class.java.name,
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    writeFixtureTextAtomically(
        Path.of(parameters.getValue("childPidFile")),
        descendants.joinToString(separator = "\n") { process -> process.pid().toString() },
    )
    CountDownLatch(1).await()
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

private fun writeFixtureTextAtomically(path: Path, value: String) {
    writeRawAtomically(path, value.toByteArray(UTF_8))
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

private fun retainingTrackerFactory(
    reference: AtomicReference<ProcessTreeTracking>
): ProcessTreeTrackerFactory =
    ProcessTreeTrackerFactory { root ->
        ProcessTreeTracker(root).also(reference::set)
    }

private fun releaseRootAfterDescendantIsTracked(
    childPidFile: Path,
    rootReleaseFile: Path,
    trackerReference: AtomicReference<ProcessTreeTracking>,
): FixtureCoordinator =
    fixtureCoordinator {
        try {
            val childPid = awaitProcessId(childPidFile)
            val tracker = awaitTracker(trackerReference)
            awaitFixtureCondition("descendant $childPid to be retained") {
                tracker.snapshot().descendants.any { handle -> handle.pid() == childPid }
            }
        } finally {
            Files.writeString(rootReleaseFile, "release")
        }
    }

private fun coordinateLateGrandchild(
    childPidFile: Path,
    grandchildPidFile: Path,
    rootReleaseFile: Path,
    grandchildTrackedFile: Path,
    trackerReference: AtomicReference<ProcessTreeTracking>,
): FixtureCoordinator =
    fixtureCoordinator {
        try {
            val childPid = awaitProcessId(childPidFile)
            val tracker = awaitTracker(trackerReference)
            awaitFixtureCondition("child $childPid to be retained") {
                tracker.snapshot().descendants.any { handle -> handle.pid() == childPid }
            }
            Files.writeString(rootReleaseFile, "release")
            val grandchildPid = awaitProcessId(grandchildPidFile)
            awaitFixtureCondition("late grandchild $grandchildPid to be retained") {
                tracker.snapshot().descendants.any { handle -> handle.pid() == grandchildPid }
            }
        } finally {
            Files.writeString(rootReleaseFile, "release")
            Files.writeString(grandchildTrackedFile, "release")
        }
    }

private fun awaitTracker(reference: AtomicReference<ProcessTreeTracking>): ProcessTreeTracking {
    awaitFixtureCondition("process tree tracker") { reference.get() != null }
    return requireNotNull(reference.get())
}

private fun awaitProcessId(path: Path): Long {
    var processId: Long? = null
    awaitFixtureCondition("parseable process ID file $path") {
        readProcessIdIfPresent(path)?.let { parsed ->
            processId = parsed
            true
        } ?: false
    }
    return requireNotNull(processId)
}

private fun readProcessIdIfPresent(path: Path): Long? =
    if (Files.isRegularFile(path)) {
        runCatching { Files.readString(path).trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
            .getOrNull()
    } else {
        null
    }

private fun awaitProcessIds(path: Path, expectedCount: Int): List<Long> {
    var processIds = emptyList<Long>()
    awaitFixtureCondition("$expectedCount process IDs in $path") {
        processIds = readProcessIdsIfPresent(path)
        processIds.size == expectedCount
    }
    return processIds
}

private fun readProcessIdsIfPresent(path: Path): List<Long> =
    if (Files.isRegularFile(path)) {
        runCatching {
                val parsed = Files.readAllLines(path).map { line -> line.trim().toLongOrNull() }
                if (parsed.any { processId -> processId == null }) {
                    emptyList()
                } else {
                    parsed.filterNotNull()
                }
            }
            .getOrDefault(emptyList())
    } else {
        emptyList()
    }

private fun awaitProcessExit(processId: Long) {
    awaitFixtureCondition("process $processId to exit") { !processIsAlive(processId) }
}

private fun awaitFixtureCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
        Thread.sleep(10)
    }
}

private fun fixtureCoordinator(action: () -> Unit): FixtureCoordinator {
    val failure = AtomicReference<Throwable?>()
    val thread =
        Thread.ofVirtual().name("revoman-benchmark-fixture-coordinator").start {
            try {
                action()
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
    return FixtureCoordinator(thread, failure)
}

private class FixtureCoordinator(
    private val thread: Thread,
    private val failure: AtomicReference<Throwable?>,
) {
    fun await() {
        thread.join(Duration.ofSeconds(5))
        check(!thread.isAlive) { "Fixture coordinator did not stop" }
        failure.get()?.let { throw AssertionError("Fixture coordination failed", it) }
    }
}

private fun forceStopFixtureProcess(processId: Long) {
    ProcessHandle.of(processId).ifPresent(ProcessHandle::destroyForcibly)
    runCatching { awaitProcessExit(processId) }
}

private fun forceStopFixtureTree(processId: Long) {
    ProcessHandle.of(processId).ifPresent { root ->
        root.descendants().use { descendants ->
            descendants.toList().asReversed().forEach(ProcessHandle::destroyForcibly)
        }
        root.destroyForcibly()
    }
    forceStopFixtureProcess(processId)
}

private class DeliberateCleanupFailure(message: String) : RuntimeException(message)

private class FailingProcessTreeTracking(
    private val failure: Throwable,
    private val beforeFailure: () -> Unit = {},
) : ProcessTreeTracking {
    private val signal = CompletableFuture<Throwable>()

    override fun run() {
        beforeFailure()
        signal.complete(failure)
    }

    override fun stopSampling() = Unit

    override fun failureOrNull(): Throwable = failure

    override fun failureSignal(): CompletableFuture<Throwable> = signal

    override fun snapshot(): ProcessTreeSnapshot = ProcessTreeSnapshot(emptyList())
}

private class DeferredProcessTreeTracking(
    private val delegate: ProcessTreeTracking,
    private val beforeRun: () -> Unit,
) : ProcessTreeTracking {
    override fun run() {
        beforeRun()
        delegate.run()
    }

    override fun stopSampling() = delegate.stopSampling()

    override fun failureOrNull(): Throwable? = delegate.failureOrNull()

    override fun failureSignal(): CompletableFuture<Throwable> = delegate.failureSignal()

    override fun snapshot(): ProcessTreeSnapshot = delegate.snapshot()
}

private class LatePublishingProcessTreeTracking(
    private val lateHandle: ProcessHandle
) : ProcessTreeTracking {
    private val completed = CountDownLatch(1)
    private val published = AtomicBoolean(false)
    val stopCalls = AtomicInteger()

    override fun run() {
        completed.await()
    }

    override fun stopSampling() {
        if (stopCalls.incrementAndGet() >= 3) {
            published.set(true)
            completed.countDown()
        }
    }

    override fun failureOrNull(): Throwable? = null

    override fun failureSignal(): CompletableFuture<Throwable> = CompletableFuture()

    override fun snapshot(): ProcessTreeSnapshot =
        ProcessTreeSnapshot(if (published.get()) listOf(lateHandle) else emptyList())
}

private class ShutdownPublishingProcessTreeTracking(
    private val lateHandle: ProcessHandle
) : ProcessTreeTracking {
    private val published = AtomicBoolean(false)

    override fun run() {
        try {
            CountDownLatch(1).await()
        } catch (_: InterruptedException) {
            published.set(true)
        }
    }

    override fun stopSampling() = Unit

    override fun failureOrNull(): Throwable? = null

    override fun failureSignal(): CompletableFuture<Throwable> = CompletableFuture()

    override fun snapshot(): ProcessTreeSnapshot =
        ProcessTreeSnapshot(if (published.get()) listOf(lateHandle) else emptyList())
}

private class ShutdownFailingProcessTreeTracking(
    private val failure: Throwable
) : ProcessTreeTracking {
    private val publishedFailure = AtomicReference<Throwable?>()
    private val signal = CompletableFuture<Throwable>()

    override fun run() {
        try {
            CountDownLatch(1).await()
        } catch (_: InterruptedException) {
            publishedFailure.set(failure)
        }
    }

    override fun stopSampling() = Unit

    override fun failureOrNull(): Throwable? = publishedFailure.get()

    override fun failureSignal(): CompletableFuture<Throwable> = signal

    override fun snapshot(): ProcessTreeSnapshot = ProcessTreeSnapshot(emptyList())
}

private class PersistentFailingProcessTreeTracking(
    private val failure: Throwable
) : ProcessTreeTracking {
    private val signal = CompletableFuture<Throwable>()

    override fun run() {
        try {
            CountDownLatch(1).await()
        } catch (_: InterruptedException) {
            // Shutdown releases the deterministic tracker task.
        }
    }

    override fun stopSampling() = Unit

    override fun failureOrNull(): Throwable = failure

    override fun failureSignal(): CompletableFuture<Throwable> = signal

    override fun snapshot(): ProcessTreeSnapshot = ProcessTreeSnapshot(emptyList())
}

private class MutableIntegrationMonotonicClock : MonotonicClock {
    private var currentNanos = 0L

    override fun nanoTime(): Long = currentNanos

    fun advanceTo(nanos: Long) {
        require(nanos >= currentNanos)
        currentNanos = nanos
    }
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
    var continuationCalls = 0
        private set

    override fun finalizeOwnedProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
        freezeTracking: (Long) -> Unit,
        deadline: ProcessTreeFinalizationDeadline,
    ): OwnedProcessTreeFinalization {
        calls += "termination"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        terminationCalls += 1
        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process,
                retainedDescendants,
                freezeTracking,
                deadline,
            )
        terminationFailure?.let { throw it }
        return result
    }

    override fun continueOwnedProcessTreeFinalization(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
    ): OwnedProcessTreeFinalization {
        calls += "continuation"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        continuationCalls += 1
        return DefaultLauncherCleanup.continueOwnedProcessTreeFinalization(
            process,
            retainedDescendants,
        )
    }

    override fun shutdownLauncherTasks(executor: ExecutorService) {
        calls += "shutdown"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        if (!shutdownDelay.isZero) Thread.sleep(shutdownDelay)
        DefaultLauncherCleanup.shutdownLauncherTasks(executor)
        shutdownFailure?.let { throw it }
    }

    override fun deleteGuard(path: Path) {
        calls += "guard"
        interruptedDuringCleanup += Thread.currentThread().isInterrupted
        DefaultLauncherCleanup.deleteGuard(path)
        guardFailure?.let { throw it }
    }
}

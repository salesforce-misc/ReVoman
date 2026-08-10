/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.TargetRole
import com.salesforce.revoman.benchmark.driver.process.JavaCommand
import com.salesforce.revoman.benchmark.driver.process.LauncherCleanup
import com.salesforce.revoman.benchmark.driver.process.OutputDrainAwaiter
import com.salesforce.revoman.benchmark.driver.process.OwnedProcessTreeFinalization
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeFinalizationDeadline
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeTracking
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeSnapshot
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeTrackerFactory
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class JmhControllerProcessLauncherTest {
    @TempDir lateinit var temporaryDirectory: Path

    @AfterEach
    fun clearInterruption() {
        Thread.interrupted()
    }

    @Test
    fun `post start interruption finalizes once closes tasks and restores interruption`() {
        val process = process(exited = false)
        val tracker = TestProcessTreeTracking()
        val cleanup = RecordingJmhCleanup()
        val started = AtomicBoolean()
        val launcher = launcher(process, tracker, cleanup, started)

        Thread.currentThread().interrupt()
        val failure = assertThrows<IllegalStateException> { launcher.launch(request()) }

        assertThat(failure).hasMessageThat().contains("Interrupted while waiting for JMH controller")
        assertThat(started.get()).isTrue()
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(cleanup.events).containsExactly("finalize", "shutdown").inOrder()
        assertThat(cleanup.finalizationCalls.get()).isEqualTo(1)
        assertThat(tracker.stopCalls.get()).isAtLeast(1)
    }

    @Test
    fun `tracker failure remains primary while process and launcher tasks are finalized`() {
        val trackingFailure = DeliberateJmhFailure("tracker failed")
        val process = process(exited = false)
        val tracker = TestProcessTreeTracking(trackingFailure)
        val cleanup = RecordingJmhCleanup()

        val failure =
            assertThrows<IllegalStateException> {
                launcher(process, tracker, cleanup).launch(request())
            }

        assertThat(failure).hasMessageThat().contains("Could not track JMH controller descendants")
        assertThat(failure).hasCauseThat().isSameInstanceAs(trackingFailure)
        assertThat(cleanup.events).containsExactly("finalize", "shutdown").inOrder()
        assertThat(cleanup.finalizationCalls.get()).isEqualTo(1)
    }

    @Test
    fun `output drain failure remains primary and finalizes all owned resources`() {
        val drainFailure = DeliberateJmhFailure("drain failed")
        val cleanup = RecordingJmhCleanup()
        val launcher =
            launcher(
                process = process(exited = true),
                tracker = TestProcessTreeTracking(),
                cleanup = cleanup,
                drainAwaiter = OutputDrainAwaiter { throw drainFailure },
            )

        val failure = assertThrows<IllegalStateException> { launcher.launch(request()) }

        assertThat(failure).hasMessageThat().contains("Could not drain JMH controller output")
        assertThat(failure).hasCauseThat().isSameInstanceAs(drainFailure)
        assertThat(cleanup.events).containsExactly("finalize", "shutdown").inOrder()
        assertThat(cleanup.finalizationCalls.get()).isEqualTo(1)
    }

    @Test
    fun `observation failure remains primary with ordered suppressed cleanup failures`() {
        val parseFailure = DeliberateJmhFailure("observation parse failed")
        val finalizationFailure = DeliberateJmhFailure("finalization failed")
        val shutdownFailure = DeliberateJmhFailure("shutdown failed")
        val process = process(exited = true, exitValueFailure = parseFailure)
        val cleanup =
            RecordingJmhCleanup(
                finalizationFailure = finalizationFailure,
                shutdownFailure = shutdownFailure,
            )

        val failure =
            assertThrows<DeliberateJmhFailure> {
                launcher(process, TestProcessTreeTracking(), cleanup).launch(request())
            }

        assertThat(failure).isSameInstanceAs(parseFailure)
        assertThat(failure.suppressed)
            .asList()
            .containsExactly(finalizationFailure, shutdownFailure)
            .inOrder()
        assertThat(cleanup.events)
            .containsExactly("finalize", "continue", "shutdown")
            .inOrder()
        assertThat(cleanup.finalizationCalls.get()).isEqualTo(1)
        assertThat(cleanup.continuationCalls.get()).isEqualTo(1)
    }

    @Test
    fun `successful launch finalizes before returning and leaks no launcher threads`() {
        val cleanup = RecordingJmhCleanup()
        val observation =
            launcher(process(exited = true), TestProcessTreeTracking(), cleanup).launch(request())

        assertThat(observation.exitCode).isEqualTo(0)
        assertThat(cleanup.events).containsExactly("finalize", "shutdown").inOrder()
        assertThat(cleanup.finalizationCalls.get()).isEqualTo(1)
        assertThat(liveJmhLauncherThreads()).isEmpty()
    }

    private fun launcher(
        process: Process,
        tracker: ProcessTreeTracking,
        cleanup: LauncherCleanup,
        started: AtomicBoolean = AtomicBoolean(),
        drainAwaiter: OutputDrainAwaiter = OutputDrainAwaiter { future -> future.get() },
    ): JmhControllerProcessLauncher =
        JmhControllerProcessLauncher(
            cleanup = cleanup,
            drainAwaiter = drainAwaiter,
            trackerFactory = ProcessTreeTrackerFactory { tracker },
            processStarter =
                JmhControllerProcessStarter { _, _ ->
                    started.set(true)
                    process
                },
        )

    private fun process(exited: Boolean, exitValueFailure: Throwable? = null): Process {
        val process = mockk<Process>()
        val handle = mockk<ProcessHandle>()
        every { process.pid() } returns 9_001L
        every { process.toHandle() } returns handle
        every { process.inputStream } returns ByteArrayInputStream("stdout".toByteArray())
        every { process.errorStream } returns ByteArrayInputStream("stderr".toByteArray())
        every { process.onExit() } returns
            if (exited) CompletableFuture.completedFuture(process)
            else CompletableFuture()
        every { process.isAlive } returns !exited
        every { process.destroy() } returns Unit
        every { process.destroyForcibly() } returns process
        every { process.waitFor(any<Long>(), any<TimeUnit>()) } returns exited
        if (exitValueFailure == null) every { process.exitValue() } returns 0
        else every { process.exitValue() } throws exitValueFailure
        return process
    }

    private fun request(): WarmAllocationLaunch {
        val command =
            JavaCommand(
                executable = Path.of("/test/java"),
                jvmArgs = emptyList(),
                classpath = listOf(Path.of("/test/classes")),
                mainClass = "example.Main",
                programArgs = emptyList(),
                workingDirectory = temporaryDirectory.toRealPath(),
                timeout = Duration.ofSeconds(10),
            )
        return WarmAllocationLaunch(
            blockId = 0,
            targetRole = TargetRole.BASELINE,
            fork = 0,
            forkCount = 1,
            profilers = listOf("gc"),
            benchmarkIncludes = listOf("example.Benchmark"),
            targetClasspath = listOf(Path.of("/test/target.jar")),
            command = command,
            rawResult = temporaryDirectory.resolve("raw.json"),
            normalizedResult = temporaryDirectory.resolve("normalized.json"),
            humanOutput = temporaryDirectory.resolve("human.txt"),
        )
    }

    private fun liveJmhLauncherThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { thread ->
            thread.isAlive && thread.name.startsWith(JMH_CONTROLLER_LAUNCHER_THREAD_PREFIX)
        }
}

private class TestProcessTreeTracking(failure: Throwable? = null) : ProcessTreeTracking {
    private val sampling = AtomicBoolean(true)
    private val failure = failure
    private val failureSignal = CompletableFuture<Throwable>()
    val stopCalls = AtomicInteger()

    init {
        if (failure != null) failureSignal.complete(failure)
    }

    override fun run() {
        while (sampling.get() && !Thread.currentThread().isInterrupted) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
    }

    override fun stopSampling() {
        stopCalls.incrementAndGet()
        sampling.set(false)
    }

    override fun failureOrNull(): Throwable? = failure

    override fun failureSignal(): CompletableFuture<Throwable> = failureSignal

    override fun snapshot(): ProcessTreeSnapshot = ProcessTreeSnapshot(emptyList())
}

private class RecordingJmhCleanup(
    private val finalizationFailure: Throwable? = null,
    private val shutdownFailure: Throwable? = null,
) : LauncherCleanup {
    val events = mutableListOf<String>()
    val finalizationCalls = AtomicInteger()
    val continuationCalls = AtomicInteger()

    override fun finalizeOwnedProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
        freezeTracking: (Long) -> Unit,
        deadline: ProcessTreeFinalizationDeadline,
    ): OwnedProcessTreeFinalization {
        events += "finalize"
        finalizationCalls.incrementAndGet()
        finalizationFailure?.let { throw it }
        freezeTracking(deadline.remainingForTrackerJoin())
        return OwnedProcessTreeFinalization(hadLiveDescendants = false)
    }

    override fun continueOwnedProcessTreeFinalization(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
    ): OwnedProcessTreeFinalization {
        events += "continue"
        continuationCalls.incrementAndGet()
        return OwnedProcessTreeFinalization(hadLiveDescendants = false)
    }

    override fun shutdownLauncherTasks(executor: ExecutorService) {
        events += "shutdown"
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        shutdownFailure?.let { throw it }
    }

    override fun deleteGuard(path: Path) = error("JMH controller has no atomic guard")
}

private class DeliberateJmhFailure(message: String) : RuntimeException(message)

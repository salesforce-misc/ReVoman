/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JdkProcessLauncherTest {
    @Test
    fun `default process tree cleanup attempts every action and restores interruption`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val firstDescendant = mockk<ProcessHandle>()
        val secondDescendant = mockk<ProcessHandle>()
        val destroyFailure = DeliberateTerminationFailure("graceful destroy failed")
        var rootAlive = true
        var firstDescendantAlive = true
        var secondDescendantAlive = true
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.of(firstDescendant, secondDescendant) }
        every { secondDescendant.destroy() } answers {
            secondDescendantAlive = false
            true
        }
        every { firstDescendant.destroy() } throws destroyFailure
        every { root.destroy() } returns true
        every { secondDescendant.destroyForcibly() } answers {
            secondDescendantAlive = false
            true
        }
        every { firstDescendant.destroyForcibly() } answers {
            firstDescendantAlive = false
            true
        }
        every { root.destroyForcibly() } answers {
            rootAlive = false
            true
        }
        every { secondDescendant.isAlive } answers { secondDescendantAlive }
        every { firstDescendant.isAlive } answers { firstDescendantAlive }
        every { root.isAlive } answers { rootAlive }

        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows<DeliberateTerminationFailure> {
                DefaultLauncherCleanup.finalizeOwnedProcessTree(process)
            }

            assertThat(failure).isSameInstanceAs(destroyFailure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify(atLeast = 1) { secondDescendant.destroy() }
            verify(atLeast = 1) { firstDescendant.destroy() }
            verify(atLeast = 1) { root.destroy() }
            verify(exactly = 1) { firstDescendant.destroyForcibly() }
            verify(exactly = 1) { root.destroyForcibly() }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `process handle lookup failure still attempts root process cleanup`() {
        val process = mockk<Process>()
        val lookupFailure = DeliberateTerminationFailure("process handle lookup failed")
        var processAlive = true
        every { process.toHandle() } throws lookupFailure
        every { process.destroy() } just Runs
        every { process.destroyForcibly() } answers {
            processAlive = false
            process
        }
        every { process.isAlive } answers { processAlive }

        val failure = assertThrows<DeliberateTerminationFailure> {
            DefaultLauncherCleanup.finalizeOwnedProcessTree(process)
        }

        assertThat(failure).isSameInstanceAs(lookupFailure)
        verify(exactly = 1) { process.toHandle() }
        verify(atLeast = 1) { process.destroy() }
        verify(exactly = 1) { process.destroyForcibly() }
        verify(atLeast = 1) { process.isAlive }
    }

    @Test
    fun `process tree cleanup forcibly terminates descendants discovered during graceful cleanup`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val initialDescendant = mockk<ProcessHandle>()
        val lateDescendant = mockk<ProcessHandle>()
        var snapshots = 0
        var initialAlive = true
        var lateAlive = true
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.empty() }
        every { initialDescendant.destroy() } answers {
            initialAlive = false
            true
        }
        every { initialDescendant.descendants() } answers { Stream.empty() }
        every { lateDescendant.destroy() } returns true
        every { lateDescendant.descendants() } answers { Stream.empty() }
        every { initialDescendant.isAlive } answers { initialAlive }
        every { lateDescendant.isAlive } answers { lateAlive }
        every { initialDescendant.destroyForcibly() } answers {
            initialAlive = false
            true
        }
        every { lateDescendant.destroyForcibly() } answers {
            lateAlive = false
            true
        }
        every { root.isAlive } returns false
        var trackingFrozen = false

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = {
                    snapshots += 1
                    if (snapshots == 1) {
                        listOf(initialDescendant)
                    } else {
                        listOf(initialDescendant, lateDescendant)
                    }
                },
                freezeTracking = { trackingFrozen = true },
            )

        assertThat(snapshots).isAtLeast(2)
        assertThat(trackingFrozen).isTrue()
        assertThat(result.hadLiveDescendants).isTrue()
        verify(atLeast = 1) { initialDescendant.destroy() }
        verify(exactly = 1) { lateDescendant.destroyForcibly() }
    }

    @Test
    fun `process tree cleanup keeps tracking active and reports root that survives its deadline`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val descendant = mockk<ProcessHandle>()
        val events = mutableListOf<String>()
        var trackingFrozen = false
        var descendantAlive = true
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.empty() }
        every { descendant.destroy() } returns true
        every { descendant.descendants() } answers { Stream.empty() }
        every { root.destroy() } returns true
        every { descendant.isAlive } answers { descendantAlive }
        every { root.destroyForcibly() } returns true
        every { root.isAlive } returns true
        every { root.pid() } returns 999
        every { descendant.destroyForcibly() } answers {
            events += "descendant-force:$trackingFrozen"
            descendantAlive = false
            true
        }

        val startedAt = System.nanoTime()
        val failure = assertThrows<IllegalStateException> {
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(descendant) },
                freezeTracking = {
                    trackingFrozen = true
                    events += "freeze"
                },
            )
        }
        val elapsed = System.nanoTime() - startedAt

        assertThat(failure).hasMessageThat().contains("Could not terminate process 999")
        assertThat(elapsed).isAtLeast(Duration.ofSeconds(4).toNanos())
        assertThat(elapsed).isLessThan(Duration.ofSeconds(7).toNanos())
        assertThat(events).containsAtLeast("descendant-force:false", "freeze").inOrder()
    }

    @Test
    fun `process tree tracker records hard bound overflow as a failure`() {
        val root = mockk<ProcessHandle>()
        val firstDescendant = mockTrackedProcessHandle(processId = 101)
        val secondDescendant = mockTrackedProcessHandle(processId = 102)
        every { root.descendants() } answers { Stream.of(firstDescendant, secondDescendant) }
        every { root.isAlive } returns false

        val tracker = ProcessTreeTracker(root, maxTrackedDescendants = 1)
        tracker.run()

        assertThat(tracker.failureOrNull())
            .hasMessageThat()
            .contains("exceeded 1 live descendants")
        assertThat(tracker.failureSignal().get()).isSameInstanceAs(tracker.failureOrNull())
        assertThat(tracker.snapshot().descendants).containsExactly(firstDescendant)
    }

    @Test
    fun `process tree tracker exposes unexpected scan failure`() {
        val root = mockk<ProcessHandle>()
        val scanFailure = DeliberateTerminationFailure("descendant scan failed")
        every { root.descendants() } throws scanFailure

        val tracker = ProcessTreeTracker(root)
        tracker.run()

        assertThat(tracker.failureOrNull()).isSameInstanceAs(scanFailure)
        assertThat(tracker.failureSignal().get()).isSameInstanceAs(scanFailure)
    }

    @Test
    fun `process tree tracker scans retained live handles after root death`() {
        val root = mockk<ProcessHandle>()
        val child = mockTrackedProcessHandle(processId = 201)
        val grandchild = mockTrackedProcessHandle(processId = 202)
        every { root.descendants() } answers { Stream.of(child) }
        every { root.isAlive } returns false
        every { child.descendants() } answers { Stream.of(grandchild) }
        every { grandchild.descendants() } answers { Stream.empty() }
        val tracker = ProcessTreeTracker(root)
        val trackingThread = Thread.ofVirtual().start(tracker)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (tracker.snapshot().descendants.none { handle -> handle.pid() == 202L }) {
                check(System.nanoTime() < deadline) { "Grandchild was not retained" }
                Thread.sleep(10)
            }

            assertThat(tracker.snapshot().descendants).containsAtLeast(child, grandchild)
        } finally {
            tracker.stopSampling()
            trackingThread.join(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `process tree tracker construction does not scan on launch thread`() {
        val root = mockk<ProcessHandle>()

        ProcessTreeTracker(root)

        verify(exactly = 0) { root.descendants() }
    }

    @Test
    fun `launcher background shutdown is bounded and awaits task termination`() {
        val executor = mockk<ExecutorService>()
        every { executor.shutdownNow() } returns emptyList()
        every { executor.awaitTermination(5, TimeUnit.SECONDS) } returns true

        DefaultLauncherCleanup.shutdownLauncherTasks(executor)

        verifyOrder {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        verify(exactly = 1) { executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `launcher failure aggregation bounds retained cleanup diagnostics`() {
        val primary = DeliberateTerminationFailure("primary")
        val cleanupFailures =
            List(200) { index -> DeliberateTerminationFailure("cleanup failure $index") }
        val failures = FailureAccumulator()
        failures.add(primary)
        cleanupFailures.forEach(failures::add)

        val thrown = assertThrows<DeliberateTerminationFailure>(failures::throwIfAny)

        assertThat(thrown).isSameInstanceAs(primary)
        assertThat(thrown.suppressed.asList().take(128))
            .containsExactlyElementsIn(cleanupFailures.take(128))
            .inOrder()
        assertThat(thrown.suppressed).hasLength(129)
        assertThat(thrown.suppressed.last())
            .hasMessageThat()
            .contains("Additional launcher failures omitted")
    }
}

private fun mockTrackedProcessHandle(processId: Long): ProcessHandle {
    val handle = mockk<ProcessHandle>()
    val info = mockk<ProcessHandle.Info>()
    every { handle.isAlive } returns true
    every { handle.pid() } returns processId
    every { handle.info() } returns info
    every { info.startInstant() } returns Optional.of(Instant.ofEpochMilli(processId))
    return handle
}

private class DeliberateTerminationFailure(message: String) : RuntimeException(message)

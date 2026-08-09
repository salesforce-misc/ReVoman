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
        val waitFailure = InterruptedException("graceful wait interrupted")
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.of(firstDescendant, secondDescendant) }
        every { secondDescendant.destroy() } returns true
        every { firstDescendant.destroy() } throws destroyFailure
        every { root.destroy() } returns true
        every { process.waitFor(1, TimeUnit.SECONDS) } answers {
            Thread.interrupted()
            throw waitFailure
        }
        every { secondDescendant.destroyForcibly() } returns true
        every { firstDescendant.destroyForcibly() } returns true
        every { root.destroyForcibly() } returns true
        every { process.waitFor(5, TimeUnit.SECONDS) } returns true
        every { secondDescendant.isAlive } returns false
        every { firstDescendant.isAlive } returns false
        every { root.isAlive } returns false

        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows<DeliberateTerminationFailure> {
                DefaultLauncherCleanup.terminateProcessTree(process)
            }

            assertThat(failure).isSameInstanceAs(destroyFailure)
            assertThat(failure.suppressed).asList().containsExactly(waitFailure)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verifyOrder {
                secondDescendant.destroy()
                firstDescendant.destroy()
                root.destroy()
                process.waitFor(1, TimeUnit.SECONDS)
                root.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                secondDescendant.destroyForcibly()
                firstDescendant.destroyForcibly()
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `process handle lookup failure still attempts root process cleanup`() {
        val process = mockk<Process>()
        val lookupFailure = DeliberateTerminationFailure("process handle lookup failed")
        every { process.toHandle() } throws lookupFailure
        every { process.destroy() } just Runs
        every { process.waitFor(1, TimeUnit.SECONDS) } returns true
        every { process.destroyForcibly() } returns process
        every { process.waitFor(5, TimeUnit.SECONDS) } returns true
        every { process.isAlive } returns false

        val failure = assertThrows<DeliberateTerminationFailure> {
            DefaultLauncherCleanup.terminateProcessTree(process)
        }

        assertThat(failure).isSameInstanceAs(lookupFailure)
        verifyOrder {
            process.toHandle()
            process.destroy()
            process.waitFor(1, TimeUnit.SECONDS)
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            process.isAlive
        }
    }

    @Test
    fun `process tree cleanup forcibly terminates descendants discovered during graceful cleanup`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val initialDescendant = mockk<ProcessHandle>()
        val lateDescendant = mockk<ProcessHandle>()
        var snapshots = 0
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.empty() }
        every { initialDescendant.destroy() } returns true
        every { lateDescendant.destroy() } returns true
        every { root.destroy() } returns true
        every { process.waitFor(1, TimeUnit.SECONDS) } returns true
        every { initialDescendant.isAlive } returns false
        every { lateDescendant.isAlive } returns false
        every { initialDescendant.destroyForcibly() } returns true
        every { lateDescendant.destroyForcibly() } returns true
        every { root.destroyForcibly() } returns true
        every { process.waitFor(5, TimeUnit.SECONDS) } returns true
        every { root.isAlive } returns false
        var trackingFrozen = false

        DefaultLauncherCleanup.terminateProcessTree(
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
        verifyOrder {
            initialDescendant.destroy()
            root.destroy()
            process.waitFor(1, TimeUnit.SECONDS)
            root.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            lateDescendant.destroyForcibly()
            initialDescendant.destroyForcibly()
        }
    }

    @Test
    fun `process tree cleanup keeps tracking active when root survives forcible wait`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val descendant = mockk<ProcessHandle>()
        val events = mutableListOf<String>()
        var trackingFrozen = false
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.empty() }
        every { descendant.destroy() } returns true
        every { root.destroy() } returns true
        every { process.waitFor(1, TimeUnit.SECONDS) } returns false
        every { descendant.isAlive } returns false
        every { root.destroyForcibly() } returns true
        every { process.waitFor(5, TimeUnit.SECONDS) } returns false
        every { root.isAlive } returns true
        every { root.pid() } returns 999
        every { descendant.destroyForcibly() } answers {
            events += "descendant-force:$trackingFrozen"
            true
        }

        assertThrows<IllegalStateException> {
            DefaultLauncherCleanup.terminateProcessTree(
                process = process,
                retainedDescendants = { listOf(descendant) },
                freezeTracking = {
                    trackingFrozen = true
                    events += "freeze"
                },
            )
        }

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

        DefaultLauncherCleanup.shutdownOutputDrains(executor)

        verifyOrder {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        verify(exactly = 1) { executor.awaitTermination(5, TimeUnit.SECONDS) }
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

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
import java.util.concurrent.CompletableFuture
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
    fun `final snapshot force kills a handle published after the pre-freeze budget expires`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val lateDescendant = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
        val budget =
            ProcessTreeFinalizationBudget(
                totalNanos = 100,
                trackerJoinNanos = 20,
                postFreezeNanos = 10,
            )
        val deadline = ProcessTreeFinalizationDeadline.start(clock, budget)
        var lateDescendantPublished = false
        var lateDescendantAlive = true
        var freezeTimeoutNanos = -1L
        every { process.toHandle() } returns root
        every { root.descendants() } answers { Stream.empty() }
        every { root.isAlive } returns false
        every { lateDescendant.descendants() } answers { Stream.empty() }
        every { lateDescendant.isAlive } answers { lateDescendantAlive }
        every { lateDescendant.pid() } returns 303
        every { lateDescendant.destroyForcibly() } answers {
            lateDescendantAlive = false
            true
        }
        clock.advanceTo(70)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = {
                    if (lateDescendantPublished) listOf(lateDescendant) else emptyList()
                },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    lateDescendantPublished = true
                    clock.advanceTo(100)
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(lateDescendantAlive).isFalse()
        verify(exactly = 1) { lateDescendant.destroyForcibly() }
    }

    @Test
    fun `pre-freeze traversal stops at its phase boundary and preserves tracker join reserve`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val firstDescendant = mockk<ProcessHandle>()
        val secondDescendant = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var firstDescendantAlive = true
        var secondDescendantAlive = true
        var freezeTimeoutNanos = -1L
        every { process.toHandle() } returns root
        every { root.hashCode() } answers {
            if (!trackingFrozen && clock.nanoTime() >= deadline.preFreezeDeadlineNanos) {
                preFreezeEvents += "root-anchor"
            }
            101
        }
        every { root.isAlive } returns false
        every { root.descendants() } answers {
            if (!trackingFrozen) {
                preFreezeEvents += "root-descendants"
                clock.advanceBy(10)
            }
            Stream.empty()
        }
        every { firstDescendant.isAlive } answers {
            if (!trackingFrozen && clock.nanoTime() < deadline.preFreezeDeadlineNanos) {
                preFreezeEvents += "first-alive"
                clock.advanceTo(deadline.preFreezeDeadlineNanos)
            }
            firstDescendantAlive
        }
        every { secondDescendant.isAlive } answers {
            if (!trackingFrozen) {
                preFreezeEvents += "second-alive"
                clock.advanceBy(10)
            }
            secondDescendantAlive
        }
        every { firstDescendant.descendants() } answers {
            if (!trackingFrozen) {
                preFreezeEvents += "first-descendants"
                clock.advanceBy(10)
            }
            Stream.empty()
        }
        every { secondDescendant.descendants() } answers {
            if (!trackingFrozen) {
                preFreezeEvents += "second-descendants"
                clock.advanceBy(10)
            }
            Stream.empty()
        }
        every { firstDescendant.destroy() } answers {
            firstDescendantAlive = false
            true
        }
        every { secondDescendant.destroy() } answers {
            secondDescendantAlive = false
            true
        }
        every { firstDescendant.destroyForcibly() } answers {
            firstDescendantAlive = false
            true
        }
        every { secondDescendant.destroyForcibly() } answers {
            secondDescendantAlive = false
            true
        }

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(firstDescendant, secondDescendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("first-alive")
        assertThat(result.hadLiveDescendants).isTrue()
    }

    @Test
    fun `anchor deduplication cannot start a handle check beyond the phase boundary`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val descendant = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var deduplicatingDescendantAnchor = false
        var descendantAlive = true
        var freezeTimeoutNanos = -1L
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers {
            if (!trackingFrozen) deduplicatingDescendantAnchor = true
            Stream.empty()
        }
        every { descendant.hashCode() } answers {
            if (!trackingFrozen && deduplicatingDescendantAnchor) {
                preFreezeEvents += "descendant-deduplication"
                clock.advanceTo(deadline.preFreezeDeadlineNanos)
            }
            202
        }
        every { descendant.equals(root) } answers {
            if (!trackingFrozen && clock.nanoTime() >= deadline.preFreezeDeadlineNanos) {
                preFreezeEvents += "descendant-equality"
            }
            false
        }
        every { descendant.isAlive } answers {
            if (!trackingFrozen && clock.nanoTime() >= deadline.preFreezeDeadlineNanos) {
                preFreezeEvents += "descendant-alive"
                clock.advanceBy(10)
            }
            descendantAlive
        }
        every { descendant.descendants() } answers { Stream.empty() }
        every { descendant.destroy() } answers {
            descendantAlive = false
            true
        }
        every { descendant.destroyForcibly() } answers {
            descendantAlive = false
            true
        }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(descendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("descendant-deduplication")
        assertThat(result.hadLiveDescendants).isTrue()
    }

    @Test
    fun `membership equality that reaches the phase boundary cannot start insertion`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val seedDescendant = mockk<ProcessHandle>()
        val descendant = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var seedDescendantAlive = true
        var descendantAlive = true
        var membershipEqualityCalls = 0
        var freezeTimeoutNanos = -1L
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.empty() }
        every { seedDescendant.hashCode() } returns 301
        every { seedDescendant.isAlive } answers { seedDescendantAlive }
        every { seedDescendant.descendants() } answers { Stream.empty() }
        every { seedDescendant.destroyForcibly() } answers {
            seedDescendantAlive = false
            true
        }
        every { descendant.hashCode() } answers {
            if (!trackingFrozen && membershipEqualityCalls > 0) {
                preFreezeEvents += "insertion"
                clock.advanceBy(10)
            }
            301
        }
        every { descendant.equals(seedDescendant) } answers {
            if (!trackingFrozen && membershipEqualityCalls == 0) {
                membershipEqualityCalls += 1
                preFreezeEvents += "membership-equality"
                clock.advanceTo(deadline.preFreezeDeadlineNanos)
            }
            false
        }
        every { descendant.isAlive } answers { descendantAlive }
        every { descendant.descendants() } answers { Stream.empty() }
        every { descendant.destroyForcibly() } answers {
            descendantAlive = false
            true
        }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(seedDescendant, descendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("membership-equality")
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
    }

    @Test
    fun `expired phase on remember entry cannot start membership`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val seedDescendant = mockk<ProcessHandle>()
        val descendant = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var seedDescendantAlive = true
        var descendantAlive = true
        var phaseExpiryArmed = false
        var freezeTimeoutNanos = -1L
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.empty() }
        every { seedDescendant.hashCode() } returns 310
        every { seedDescendant.isAlive } answers { seedDescendantAlive }
        every { seedDescendant.descendants() } answers { Stream.empty() }
        every { seedDescendant.destroyForcibly() } answers {
            seedDescendantAlive = false
            true
        }
        every { descendant.hashCode() } answers {
            if (!trackingFrozen) preFreezeEvents += "membership"
            311
        }
        every { descendant.isAlive } answers {
            if (!trackingFrozen && !phaseExpiryArmed) {
                phaseExpiryArmed = true
                clock.advanceTo(deadline.preFreezeDeadlineNanos)
            }
            descendantAlive
        }
        every { descendant.descendants() } answers { Stream.empty() }
        every { descendant.destroyForcibly() } answers {
            descendantAlive = false
            true
        }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(seedDescendant, descendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).isEmpty()
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
    }

    @Test
    fun `retained live handle whose liveness consumes the phase is terminally forced`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        var trackingFrozen = false
        var descendantAlive = true
        var descendantLivenessCalls = 0
        var descendantForceCalls = 0
        var freezeTimeoutNanos = -1L
        val descendant =
            TestProcessHandle(
                processId = 312,
                alive = {
                    descendantLivenessCalls += 1
                    if (!trackingFrozen && descendantLivenessCalls == 1) {
                        clock.advanceTo(deadline.preFreezeDeadlineNanos)
                    }
                    descendantAlive
                },
                hash = { 312 },
                force = {
                    descendantForceCalls += 1
                    descendantAlive = false
                    true
                },
            )
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.empty() }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = {
                    if (trackingFrozen) emptyList() else listOf(descendant)
                },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
        assertThat(descendantForceCalls).isEqualTo(1)
    }

    @Test
    fun `phase expiry after membership cannot start insertion`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val seedDescendant = mockk<ProcessHandle>()
        val descendant = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var seedDescendantAlive = true
        var descendantAlive = true
        var preFreezeHashCalls = 0
        var freezeTimeoutNanos = -1L
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.empty() }
        every { seedDescendant.hashCode() } returns 320
        every { seedDescendant.isAlive } answers { seedDescendantAlive }
        every { seedDescendant.descendants() } answers { Stream.empty() }
        every { seedDescendant.destroyForcibly() } answers {
            seedDescendantAlive = false
            true
        }
        every { descendant.hashCode() } answers {
            if (!trackingFrozen) {
                preFreezeHashCalls += 1
                preFreezeEvents +=
                    if (preFreezeHashCalls == 1) "membership" else "insertion"
                if (preFreezeHashCalls == 1) {
                    clock.advanceAfterNextRead(deadline.preFreezeDeadlineNanos)
                }
            }
            321
        }
        every { descendant.isAlive } answers { descendantAlive }
        every { descendant.descendants() } answers { Stream.empty() }
        every { descendant.destroyForcibly() } answers {
            descendantAlive = false
            true
        }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(seedDescendant, descendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("membership")
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
    }

    @Test
    fun `overflow membership at the phase boundary cannot start forcible destruction`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var overflowAlive = true
        var overflowForceCalls = 0
        var freezeTimeoutNanos = -1L
        val retainedCapacity =
            List(4_096) { index ->
                TestProcessHandle(
                    processId = 1_000L + index,
                    alive = { !trackingFrozen },
                    hash = { index },
                )
            }
        val overflow =
            TestProcessHandle(
                processId = 9_999,
                alive = { overflowAlive },
                hash = {
                    if (!trackingFrozen) {
                        preFreezeEvents += "overflow-membership"
                        clock.advanceTo(deadline.preFreezeDeadlineNanos)
                    }
                    9_999
                },
                force = {
                    overflowForceCalls += 1
                    if (!trackingFrozen) preFreezeEvents += "overflow-force"
                    overflowAlive = false
                    true
                },
            )
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.empty() }
        clock.advanceTo(60)

        val failure =
            assertThrows<IllegalStateException> {
                DefaultLauncherCleanup.finalizeOwnedProcessTree(
                    process = process,
                    retainedDescendants = {
                        if (trackingFrozen) retainedCapacity else retainedCapacity + overflow
                    },
                    freezeTracking = { timeoutNanos ->
                        freezeTimeoutNanos = timeoutNanos
                        trackingFrozen = true
                    },
                    deadline = deadline,
                )
            }

        assertThat(failure).hasMessageThat().contains("exceeded 4096 live descendants")
        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("overflow-membership")
        assertThat(overflowAlive).isFalse()
        assertThat(overflowForceCalls).isEqualTo(1)
    }

    @Test
    fun `hidden anchor descendant observed at capacity is terminally forced`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var overflowAlive = true
        var overflowForceCalls = 0
        var freezeTimeoutNanos = -1L
        val retainedCapacity =
            List(4_096) { index ->
                TestProcessHandle(
                    processId = 30_000L + index,
                    alive = { !trackingFrozen },
                    hash = { index },
                )
            }
        val overflow =
            TestProcessHandle(
                processId = 39_999,
                alive = { overflowAlive },
                hash = {
                    if (!trackingFrozen) {
                        preFreezeEvents += "overflow-membership"
                        clock.advanceTo(deadline.preFreezeDeadlineNanos)
                    }
                    39_999
                },
                force = {
                    overflowForceCalls += 1
                    if (!trackingFrozen) preFreezeEvents += "overflow-force"
                    overflowAlive = false
                    true
                },
            )
        every { process.toHandle() } returns root
        every { root.hashCode() } returns 40_000
        every { root.isAlive } returns false
        every { root.descendants() } answers {
            if (trackingFrozen) Stream.empty() else Stream.of(overflow)
        }
        clock.advanceTo(60)

        val failure =
            assertThrows<IllegalStateException> {
                DefaultLauncherCleanup.finalizeOwnedProcessTree(
                    process = process,
                    retainedDescendants = { retainedCapacity },
                    freezeTracking = { timeoutNanos ->
                        freezeTimeoutNanos = timeoutNanos
                        trackingFrozen = true
                    },
                    deadline = deadline,
                )
            }

        assertThat(failure).hasMessageThat().contains("exceeded 4096 live descendants")
        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("overflow-membership")
        assertThat(overflowAlive).isFalse()
        assertThat(overflowForceCalls).isEqualTo(1)
    }

    @Test
    fun `hidden anchor live descendant whose liveness consumes the phase retains overflow evidence`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var overflowAlive = true
        var overflowLivenessCalls = 0
        var overflowForceCalls = 0
        var freezeTimeoutNanos = -1L
        val retainedCapacity =
            List(4_096) { index ->
                TestProcessHandle(
                    processId = 40_000L + index,
                    alive = { !trackingFrozen },
                    hash = { index },
                )
            }
        val overflow =
            TestProcessHandle(
                processId = 49_999,
                alive = {
                    overflowLivenessCalls += 1
                    if (!trackingFrozen && overflowLivenessCalls == 1) {
                        preFreezeEvents += "overflow-alive"
                        clock.advanceTo(deadline.preFreezeDeadlineNanos)
                    }
                    overflowAlive
                },
                hash = {
                    if (!trackingFrozen) preFreezeEvents += "overflow-membership"
                    49_999
                },
                force = {
                    overflowForceCalls += 1
                    if (!trackingFrozen) preFreezeEvents += "overflow-force"
                    overflowAlive = false
                    true
                },
            )
        every { process.toHandle() } returns root
        every { root.hashCode() } returns 50_000
        every { root.isAlive } returns false
        every { root.descendants() } answers {
            if (trackingFrozen) Stream.empty() else Stream.of(overflow)
        }
        clock.advanceTo(60)

        val failure =
            assertThrows<IllegalStateException> {
                DefaultLauncherCleanup.finalizeOwnedProcessTree(
                    process = process,
                    retainedDescendants = {
                        if (trackingFrozen) emptyList() else retainedCapacity
                    },
                    freezeTracking = { timeoutNanos ->
                        freezeTimeoutNanos = timeoutNanos
                        trackingFrozen = true
                    },
                    deadline = deadline,
                )
            }

        assertThat(failure).hasMessageThat().contains("exceeded 4096 live descendants")
        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("overflow-alive")
        assertThat(overflowAlive).isFalse()
        assertThat(overflowForceCalls).isEqualTo(1)
    }

    @Test
    fun `phase expiry after overflow evidence cannot start forcible destruction`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var overflowAlive = true
        var overflowForceCalls = 0
        var freezeTimeoutNanos = -1L
        val retainedCapacity =
            List(4_096) { index ->
                TestProcessHandle(
                    processId = 20_000L + index,
                    alive = { !trackingFrozen },
                    hash = { index },
                )
            }
        val overflow =
            TestProcessHandle(
                processId = 29_999,
                alive = { overflowAlive },
                hash = {
                    if (!trackingFrozen) {
                        preFreezeEvents += "overflow-membership"
                        clock.advanceAfterNextRead(deadline.preFreezeDeadlineNanos)
                    }
                    29_999
                },
                force = {
                    overflowForceCalls += 1
                    if (!trackingFrozen) preFreezeEvents += "overflow-force"
                    overflowAlive = false
                    true
                },
            )
        every { process.toHandle() } returns root
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.empty() }
        clock.advanceTo(60)

        val failure =
            assertThrows<IllegalStateException> {
                DefaultLauncherCleanup.finalizeOwnedProcessTree(
                    process = process,
                    retainedDescendants = {
                        if (trackingFrozen) retainedCapacity else retainedCapacity + overflow
                    },
                    freezeTracking = { timeoutNanos ->
                        freezeTimeoutNanos = timeoutNanos
                        trackingFrozen = true
                    },
                    deadline = deadline,
                )
            }

        assertThat(failure).hasMessageThat().contains("exceeded 4096 live descendants")
        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("overflow-membership")
        assertThat(overflowAlive).isFalse()
        assertThat(overflowForceCalls).isEqualTo(1)
    }

    @Test
    fun `non-root anchor equality at the phase boundary cannot start liveness check`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var comparingAnchorToRoot = false
        var descendantAlive = true
        var freezeTimeoutNanos = -1L
        val descendant =
            TestProcessHandle(
                processId = 402,
                alive = {
                    if (
                        !trackingFrozen &&
                            comparingAnchorToRoot &&
                            clock.nanoTime() >= deadline.preFreezeDeadlineNanos
                    ) {
                        preFreezeEvents += "anchor-alive"
                        clock.advanceBy(10)
                    }
                    descendantAlive
                },
                hash = { 402 },
                force = {
                    descendantAlive = false
                    true
                },
                equality = { other ->
                    if (
                        !trackingFrozen &&
                            comparingAnchorToRoot &&
                            other is ProcessHandle &&
                            other.pid() == 401L
                    ) {
                        preFreezeEvents += "anchor-equality"
                        clock.advanceTo(deadline.preFreezeDeadlineNanos)
                    }
                    false
                },
            )
        every { process.toHandle() } returns root
        every { root.pid() } returns 401
        every { root.hashCode() } returns 401
        every { root.isAlive } returns false
        every { root.descendants() } answers {
            if (!trackingFrozen) comparingAnchorToRoot = true
            Stream.empty()
        }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(descendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("anchor-equality")
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
    }

    @Test
    fun `phase expiry after anchor equality cannot start liveness check`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var comparingAnchorToRoot = false
        var descendantAlive = true
        var freezeTimeoutNanos = -1L
        val descendant =
            TestProcessHandle(
                processId = 412,
                alive = {
                    if (
                        !trackingFrozen &&
                            comparingAnchorToRoot &&
                            clock.nanoTime() >= deadline.preFreezeDeadlineNanos
                    ) {
                        preFreezeEvents += "anchor-alive"
                        clock.advanceBy(10)
                    }
                    descendantAlive
                },
                hash = { 412 },
                force = {
                    descendantAlive = false
                    true
                },
                equality = { other ->
                    if (
                        !trackingFrozen &&
                            comparingAnchorToRoot &&
                            other is ProcessHandle &&
                            other.pid() == 411L
                    ) {
                        preFreezeEvents += "anchor-equality"
                        clock.advanceAfterNextRead(deadline.preFreezeDeadlineNanos)
                    }
                    false
                },
            )
        every { process.toHandle() } returns root
        every { root.pid() } returns 411
        every { root.hashCode() } returns 411
        every { root.isAlive } returns false
        every { root.descendants() } answers {
            if (!trackingFrozen) comparingAnchorToRoot = true
            Stream.empty()
        }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(descendant) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).containsExactly("anchor-equality")
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
    }

    @Test
    fun `anchor descendant remember honors phase and terminal snapshot stays unconditional`() {
        val process = mockk<Process>()
        val root = mockk<ProcessHandle>()
        val clock = MutableMonotonicClock()
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
        val preFreezeEvents = mutableListOf<String>()
        var trackingFrozen = false
        var seedAlive = true
        var descendantAlive = true
        var phaseExpiryArmed = false
        var descendantHashCalls = 0
        var descendantForceCalls = 0
        var freezeTimeoutNanos = -1L
        val seed =
            TestProcessHandle(
                processId = 420,
                alive = { seedAlive },
                hash = { 420 },
                force = {
                    seedAlive = false
                    true
                },
            )
        val descendant =
            TestProcessHandle(
                processId = 421,
                alive = {
                    if (!trackingFrozen && !phaseExpiryArmed) {
                        phaseExpiryArmed = true
                        clock.advanceTo(deadline.preFreezeDeadlineNanos)
                    }
                    descendantAlive
                },
                hash = {
                    if (!trackingFrozen) {
                        descendantHashCalls += 1
                        preFreezeEvents +=
                            if (descendantHashCalls == 1) "membership" else "insertion"
                    }
                    421
                },
                force = {
                    descendantForceCalls += 1
                    descendantAlive = false
                    true
                },
            )
        every { process.toHandle() } returns root
        every { root.hashCode() } returns 419
        every { root.isAlive } returns false
        every { root.descendants() } answers { Stream.of(descendant) }
        clock.advanceTo(60)

        val result =
            DefaultLauncherCleanup.finalizeOwnedProcessTree(
                process = process,
                retainedDescendants = { listOf(seed) },
                freezeTracking = { timeoutNanos ->
                    freezeTimeoutNanos = timeoutNanos
                    trackingFrozen = true
                    clock.advanceTo(deadline.deadlineNanos)
                },
                deadline = deadline,
            )

        assertThat(freezeTimeoutNanos).isEqualTo(20)
        assertThat(preFreezeEvents).isEmpty()
        assertThat(result.hadLiveDescendants).isTrue()
        assertThat(descendantAlive).isFalse()
        assertThat(descendantForceCalls).isEqualTo(1)
    }

    @Test
    fun `graceful phase cap preserves reserved budgets across nano time rollover`() {
        val clock = MutableMonotonicClock(Long.MAX_VALUE - 300_000_000)
        val deadline =
            ProcessTreeFinalizationDeadline.start(
                clock = clock,
                budget =
                    ProcessTreeFinalizationBudget(
                        totalNanos = 1_000_000_000,
                        trackerJoinNanos = 200_000_000,
                        postFreezeNanos = 100_000_000,
                    ),
            )

        val gracefulDeadline =
            deadline.cappedDeadlineNanos(
                maxDurationNanos = 250_000_000,
                capNanos = deadline.preFreezeDeadlineNanos,
            )

        assertThat(deadline.remainingUntil(gracefulDeadline)).isEqualTo(250_000_000)
        assertThat(deadline.remainingUntil(deadline.preFreezeDeadlineNanos)).isEqualTo(700_000_000)
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
    fun `process tree tracker bounds historical observed descendants`() {
        val root = mockk<ProcessHandle>()
        val firstDescendant = mockTrackedProcessHandle(processId = 101)
        val secondDescendant = mockTrackedProcessHandle(processId = 102)
        every { root.descendants() } answers { Stream.of(firstDescendant, secondDescendant) }
        every { root.isAlive } returns false

        val tracker = ProcessTreeTracker(root, maxTrackedDescendants = 1)
        tracker.run()

        assertThat(tracker.failureOrNull())
            .hasMessageThat()
            .contains("exceeded 1 observed descendants")
        assertThat(tracker.failureSignal().get()).isSameInstanceAs(tracker.failureOrNull())
        assertThat(tracker.snapshot().descendants).containsExactly(firstDescendant)
        assertThat(tracker.snapshot().observedDescendantPids).containsExactly(101L)
    }

    @Test
    fun `process tree tracker remembers descendant that exits before liveness filtering`() {
        val root = mockk<ProcessHandle>()
        val exited = mockk<ProcessHandle>()
        every { root.descendants() } answers { Stream.of(exited) }
        every { root.isAlive } returns false
        every { exited.pid() } returns 303L
        every { exited.info().startInstant() } returns Optional.of(Instant.EPOCH)
        every { exited.isAlive } returns false
        every { exited.descendants() } answers { Stream.empty() }
        val tracker = ProcessTreeTracker(root)
        val trackingThread = Thread.ofVirtual().start(tracker)
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (303L !in tracker.snapshot().observedDescendantPids) {
                check(System.nanoTime() < deadline) { "Exited descendant was not observed" }
                Thread.sleep(1)
            }
        } finally {
            tracker.stopSampling()
            trackingThread.join(Duration.ofSeconds(2))
        }

        assertThat(trackingThread.isAlive).isFalse()
        assertThat(tracker.failureOrNull()).isNull()
        assertThat(tracker.snapshot().observedDescendantPids).contains(303L)
        assertThat(tracker.snapshot().descendants).isEmpty()
    }

    @Test
    fun `deferred observed handle slots identity dedupe and force the fourth unique handle`() {
        var overflowAlive = true
        val retained =
            List(3) { index ->
                TestProcessHandle(
                    processId = 501L + index,
                    alive = { true },
                    hash = { error("Deferred retention must not hash handles") },
                    equality = { error("Deferred retention must not compare handles") },
                )
            }
        val overflow =
            TestProcessHandle(
                processId = 504,
                alive = { overflowAlive },
                hash = { error("Deferred overflow must not hash handles") },
                force = {
                    overflowAlive = false
                    true
                },
                equality = { error("Deferred overflow must not compare handles") },
            )
        val failures = FailureAccumulator()
        val deferred = DeferredObservedProcessHandles()
        retained.forEach { handle ->
            deferred.retain(handle) { error("Identity slot unexpectedly overflowed") }
        }
        deferred.retain(retained[1]) { error("Duplicate identity unexpectedly overflowed") }
        deferred.retain(overflow) { handle ->
            failures.add(IllegalStateException("deferred observed capacity exceeded"))
            failures.attempt { handle.destroyForcibly() }
        }

        val failure = assertThrows<IllegalStateException>(failures::throwIfAny)
        val drained = deferred.drain()

        assertThat(failure).hasMessageThat().contains("deferred observed capacity exceeded")
        assertThat(overflowAlive).isFalse()
        assertThat(drained).hasSize(3)
        assertThat((drained[0] as Any) === (retained[0] as Any)).isTrue()
        assertThat((drained[1] as Any) === (retained[1] as Any)).isTrue()
        assertThat((drained[2] as Any) === (retained[2] as Any)).isTrue()
        assertThat(deferred.drain()).isEmpty()
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

private class TestProcessHandle(
    private val processId: Long,
    private val alive: () -> Boolean,
    private val hash: () -> Int,
    private val force: () -> Boolean = { true },
    private val equality: ((Any?) -> Boolean)? = null,
) : ProcessHandle {
    override fun pid(): Long = processId

    override fun parent(): Optional<ProcessHandle> = Optional.empty()

    override fun children(): Stream<ProcessHandle> = Stream.empty()

    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    override fun info(): ProcessHandle.Info = ProcessHandle.current().info()

    override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture.completedFuture(this)

    override fun supportsNormalTermination(): Boolean = true

    override fun destroy(): Boolean = true

    override fun destroyForcibly(): Boolean = force()

    override fun isAlive(): Boolean = alive()

    override fun compareTo(other: ProcessHandle): Int = processId.compareTo(other.pid())

    override fun hashCode(): Int = hash()

    override fun equals(other: Any?): Boolean = equality?.invoke(other) ?: (this === other)
}

private class MutableMonotonicClock(initialNanos: Long = 0) : MonotonicClock {
    private var currentNanos = initialNanos
    private var advanceAfterReadTo: Long? = null

    override fun nanoTime(): Long {
        val result = currentNanos
        advanceAfterReadTo?.let { target ->
            currentNanos = target
            advanceAfterReadTo = null
        }
        return result
    }

    fun advanceTo(nanos: Long) {
        require(nanos >= currentNanos)
        currentNanos = nanos
    }

    fun advanceBy(nanos: Long) {
        require(nanos >= 0)
        currentNanos += nanos
    }

    fun advanceAfterNextRead(nanos: Long) {
        require(nanos >= currentNanos)
        check(advanceAfterReadTo == null)
        advanceAfterReadTo = nanos
    }
}

private class DeliberateTerminationFailure(message: String) : RuntimeException(message)

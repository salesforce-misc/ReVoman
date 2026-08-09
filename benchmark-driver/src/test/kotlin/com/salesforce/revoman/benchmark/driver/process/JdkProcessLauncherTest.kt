/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
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
        every { root.descendants() } returns Stream.of(firstDescendant, secondDescendant)
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
                secondDescendant.destroyForcibly()
                firstDescendant.destroyForcibly()
                root.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            Thread.interrupted()
        }
    }
}

private class DeliberateTerminationFailure(message: String) : RuntimeException(message)

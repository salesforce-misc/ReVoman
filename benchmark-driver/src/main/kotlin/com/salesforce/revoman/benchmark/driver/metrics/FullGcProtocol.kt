/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.locks.LockSupport

/** Heap usage captured only after two separately requested and acknowledged full GC cycles. */
data class FullGcSample(
    val usedHeapBytes: Long,
    val completedGcCycles: Int,
)

internal data class FullGcRuntime(
    val collectionCount: () -> Long,
    val requestGc: () -> Unit,
    val usedHeapBytes: () -> Long,
    val nanoTime: () -> Long,
    val awaitPoll: () -> Unit,
)

/** Requests and acknowledges two explicit collections before reading live heap usage. */
class FullGcProtocol
internal constructor(private val runtime: FullGcRuntime) {
    constructor() : this(systemRuntime())

    val id: String = PROVIDER_ID
    val configurationSha256: String = PROVIDER_CONFIGURATION_SHA256

    /** Returns one valid heap sample or fails when explicit collection cannot be acknowledged. */
    fun sample(timeoutPerCycle: Duration = DEFAULT_TIMEOUT_PER_CYCLE): FullGcSample {
        require(!timeoutPerCycle.isZero && !timeoutPerCycle.isNegative) {
            "Full GC acknowledgement timeout must be positive"
        }
        val timeoutNanos =
            try {
                timeoutPerCycle.toNanos()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("Full GC acknowledgement timeout is too large", failure)
            }
        val initial = supportedCollectionCount()
        runtime.requestGc()
        val first = awaitIncrease(initial, timeoutNanos, "first")
        runtime.requestGc()
        val second = awaitIncrease(first, timeoutNanos, "second")
        val usedHeapBytes = runtime.usedHeapBytes()
        require(usedHeapBytes >= 0) { "MemoryMXBean heap usage must not be negative" }
        val completed = second - initial
        require(completed >= 2) { "Two distinct full GC acknowledgements are required" }
        require(completed <= Int.MAX_VALUE) { "Full GC acknowledgement count exceeds Int range" }
        return FullGcSample(usedHeapBytes, completed.toInt())
    }

    private fun awaitIncrease(previous: Long, timeoutNanos: Long, ordinal: String): Long {
        val startedAt = runtime.nanoTime()
        while (true) {
            val current = supportedCollectionCount()
            if (current > previous) return current
            val elapsed = runtime.nanoTime() - startedAt
            check(elapsed >= 0 && elapsed < timeoutNanos) {
                "$ordinal explicit full GC acknowledgement timed out"
            }
            runtime.awaitPoll()
        }
    }

    private fun supportedCollectionCount(): Long =
        runtime.collectionCount().also { count ->
            check(count >= 0) { "Garbage collector collectionCount is unsupported" }
        }

    companion object {
        const val PROVIDER_ID: String = "jdk-memorymxbean-two-acknowledged-full-gc/v1"
        val DEFAULT_TIMEOUT_PER_CYCLE: Duration = Duration.ofSeconds(10)
        val PROVIDER_CONFIGURATION_SHA256: String =
            ContentHasher.sha256(
                "$PROVIDER_ID\u0000${DEFAULT_TIMEOUT_PER_CYCLE.toNanos()}".toByteArray(UTF_8)
            )

        private fun systemRuntime(): FullGcRuntime {
            val collectors = ManagementFactory.getGarbageCollectorMXBeans().toList()
            require(collectors.isNotEmpty()) { "No GarbageCollectorMXBean is available" }
            return FullGcRuntime(
                collectionCount = {
                    collectors.fold(0L) { total, collector ->
                        val count = collector.collectionCount
                        check(count >= 0) {
                            "Garbage collector ${collector.name} does not expose collectionCount"
                        }
                        try {
                            Math.addExact(total, count)
                        } catch (failure: ArithmeticException) {
                            throw IllegalStateException("Aggregate GC collectionCount overflow", failure)
                        }
                    }
                },
                requestGc = System::gc,
                usedHeapBytes = { ManagementFactory.getMemoryMXBean().heapMemoryUsage.used },
                nanoTime = System::nanoTime,
                awaitPoll = { LockSupport.parkNanos(GC_POLL_NANOS) },
            )
        }
    }
}

private const val GC_POLL_NANOS: Long = 1_000_000L

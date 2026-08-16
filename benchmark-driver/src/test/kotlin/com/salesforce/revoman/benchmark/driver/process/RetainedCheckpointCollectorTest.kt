/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.metrics.FullGcSample
import com.salesforce.revoman.benchmark.driver.target.TrackedWeakReference
import java.lang.ref.WeakReference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RetainedCheckpointCollectorTest {
    @Test
    fun `collector invokes source only in reachability phase and uses only final heap sample`() {
        val events = mutableListOf<String>()
        var sampleIndex = 0
        val collector =
            RetainedCheckpointCollector {
                sampleIndex++
                events += "gc-$sampleIndex"
                System.gc()
                when (sampleIndex) {
                    1 -> FullGcSample(usedHeapBytes = 111, completedGcCycles = 2)
                    2 -> FullGcSample(usedHeapBytes = 222, completedGcCycles = 3)
                    else -> error("unexpected sample")
                }
            }

        val checkpoint =
            collector.collect(executionCount = 7) {
                events += "source"
                listOf(
                    tracked("ExecutionSession"),
                    tracked("KickExecution"),
                )
            }

        assertThat(events).containsExactly("source", "gc-1", "gc-2").inOrder()
        assertThat(checkpoint.executionCount).isEqualTo(7)
        assertThat(checkpoint.usedHeapBytes).isEqualTo(222)
        assertThat(checkpoint.completedGcCycles).isEqualTo(5)
        assertThat(checkpoint.weakReferences)
            .containsExactly(
                com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome(
                    "ExecutionSession",
                    1,
                    1,
                ),
                com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome(
                    "KickExecution",
                    1,
                    1,
                ),
            )
            .inOrder()
    }

    @Test
    fun `final sampler cannot reach phase one raw array list wrappers or weak references`() {
        lateinit var rawArraySentinel: WeakReference<Any>
        lateinit var listSentinel: WeakReference<Any>
        lateinit var wrapperSentinel: WeakReference<Any>
        lateinit var referenceSentinel: WeakReference<Any>
        var sampleIndex = 0
        val collector =
            RetainedCheckpointCollector {
                sampleIndex++
                repeat(10) { System.gc() }
                if (sampleIndex == 2) {
                    assertThat(rawArraySentinel.get()).isNull()
                    assertThat(listSentinel.get()).isNull()
                    assertThat(wrapperSentinel.get()).isNull()
                    assertThat(referenceSentinel.get()).isNull()
                }
                FullGcSample(usedHeapBytes = sampleIndex.toLong(), completedGcCycles = 2)
            }

        val checkpoint =
            collector.collect(executionCount = 1) {
                val reference = WeakReference(Any())
                val wrapper = TrackedWeakReference("ExecutionSession", reference)
                val raw = arrayOf(wrapper)
                val records = raw.asList()
                rawArraySentinel = WeakReference(raw as Any)
                listSentinel = WeakReference(records as Any)
                wrapperSentinel = WeakReference(wrapper as Any)
                referenceSentinel = WeakReference(reference as Any)
                records
            }

        assertThat(checkpoint.usedHeapBytes).isEqualTo(2)
        assertThat(checkpoint.weakReferences.single().cleared).isEqualTo(1)
    }

    @Test
    fun `collector sums cycle counts with checked arithmetic`() {
        val samples =
            ArrayDeque(
                listOf(
                    FullGcSample(usedHeapBytes = 1, completedGcCycles = Int.MAX_VALUE),
                    FullGcSample(usedHeapBytes = 2, completedGcCycles = 2),
                )
            )
        val collector = RetainedCheckpointCollector { samples.removeFirst() }

        assertThrows<ArithmeticException> {
            collector.collect(1) { listOf(tracked("ExecutionSession")) }
        }
    }

    @Test
    fun `collector rejects invalid types duplicate identities and empty evidence`() {
        val invalid =
            listOf<() -> List<TrackedWeakReference>>(
                { emptyList() },
                { listOf(tracked("")) },
                { listOf(tracked("Unknown")) },
                {
                    val reference = WeakReference<Any>(Any())
                    listOf(
                        TrackedWeakReference("ExecutionSession", reference),
                        TrackedWeakReference("KickExecution", reference),
                    )
                },
                {
                    listOf(
                        TrackedWeakReference("ExecutionSession", WeakSubclass(Any()))
                    )
                },
            )

        invalid.forEach { source ->
            val collector =
                RetainedCheckpointCollector {
                    error("invalid evidence must fail before GC sampling")
                }
            assertThrows<IllegalStateException> { collector.collect(1, source) }
        }
    }

    private fun tracked(type: String): TrackedWeakReference =
        TrackedWeakReference(type, WeakReference(Any()))

    private class WeakSubclass(value: Any) : WeakReference<Any>(value)
}

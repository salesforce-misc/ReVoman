/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.salesforce.revoman.benchmark.driver.metrics.FullGcProtocol
import com.salesforce.revoman.benchmark.driver.metrics.FullGcSample
import com.salesforce.revoman.benchmark.driver.model.RetainedCheckpoint
import com.salesforce.revoman.benchmark.driver.model.WeakReferenceOutcome
import com.salesforce.revoman.benchmark.driver.target.TrackedWeakReference
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

internal data class ReachabilityProof(
    val weakReferences: List<WeakReferenceOutcome>,
    val completedGcCycles: Int,
)

internal class RetainedCheckpointCollector(
    private val sampleFullGc: () -> FullGcSample = { FullGcProtocol().sample() }
) {
    fun collect(
        executionCount: Int,
        referenceSource: () -> List<TrackedWeakReference>,
    ): RetainedCheckpoint {
        val reachability = proveReachability(referenceSource)
        val finalHeap = sampleFinalHeap()
        val completedGcCycles =
            Math.addExact(reachability.completedGcCycles, finalHeap.completedGcCycles)
        check(completedGcCycles >= 4) {
            "Two-phase retained checkpoint requires four GC acknowledgements"
        }
        return RetainedCheckpoint(
            executionCount = executionCount,
            usedHeapBytes = finalHeap.usedHeapBytes,
            completedGcCycles = completedGcCycles,
            weakReferences = reachability.weakReferences,
        )
    }

    internal fun proveReachability(
        referenceSource: () -> List<TrackedWeakReference>
    ): ReachabilityProof {
        val records = referenceSource()
        validateRecords(records)
        val sample = sampleFullGc()
        val outcomes =
            records
                .groupBy(TrackedWeakReference::type)
                .entries
                .sortedBy(Map.Entry<String, List<TrackedWeakReference>>::key)
                .map { (type, typedRecords) ->
                    WeakReferenceOutcome(
                        type = type,
                        created = typedRecords.size,
                        cleared = typedRecords.count { it.reference.get() == null },
                    )
                }
        return ReachabilityProof(outcomes, sample.completedGcCycles)
    }

    private fun sampleFinalHeap(): FullGcSample = sampleFullGc()

    private fun validateRecords(records: List<TrackedWeakReference>) {
        check(records.isNotEmpty()) { "Retained checkpoint requires weak-reference evidence" }
        val identities =
            Collections.newSetFromMap(IdentityHashMap<WeakReference<*>, Boolean>())
        records.forEach { record ->
            check(record.type in EXPECTED_TYPES) {
                "Unsupported lifecycle weak-reference type: ${record.type}"
            }
            check(record.reference.javaClass === WeakReference::class.java) {
                "Lifecycle evidence requires exact WeakReference instances"
            }
            check(identities.add(record.reference)) {
                "Lifecycle evidence repeats a WeakReference identity"
            }
        }
    }

    private companion object {
        val EXPECTED_TYPES =
            setOf("ExecutionSession", "KickExecution", "Cs1FakeExecutionToken")
    }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.host

/** Identifies the health bracket phase that failed without logging inside measured callbacks. */
enum class HostProbePhase {
    BEFORE,
    DURING,
    AFTER,
}

/** Explains why a controlled campaign could not produce an unbiased accepted set. */
enum class HostIncompleteReason {
    INSUFFICIENT_ACCEPTED_BLOCKS,
    IMBALANCED_ACCEPTED_ORDER,
}

/** Structured diagnostics for controlled-host scheduling. */
sealed interface HostGateEvent {
    data class BlockRejected(val blockId: Int, val reasons: List<String>) : HostGateEvent

    data class ReplacementScheduled(val blockId: Int, val replacementNumber: Int) : HostGateEvent

    data class CampaignIncomplete(
        val requestedAcceptedBlocks: Int,
        val acceptedBlocks: Int,
        val attemptedBlocks: Int,
        val reason: HostIncompleteReason,
        val baselineFirstBlocks: Int,
        val candidateFirstBlocks: Int,
    ) : HostGateEvent

    data class ProbeFailed(
        val blockId: Int,
        val phase: HostProbePhase,
        val detail: String,
    ) : HostGateEvent
}

/** Receives lazily built structured events outside measured callbacks. */
fun interface HostGateEventSink {
    fun emit(event: () -> HostGateEvent)

    companion object {
        /** Benchmark-safe default that performs no work and does not evaluate event suppliers. */
        val NoOp: HostGateEventSink = HostGateEventSink {}
    }
}

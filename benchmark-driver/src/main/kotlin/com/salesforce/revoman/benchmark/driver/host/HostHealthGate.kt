/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.host

import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.PowerEvidence
import com.salesforce.revoman.benchmark.driver.model.validateHostHealthTimeline

/** The result of one target callback whose CPU interval was bracketed by host sampling. */
data class SampledHostExecution<T>(val value: T, val snapshot: HostHealthSnapshot)

/** Produces point samples and callback-overlapping samples or fails closed. */
interface HostHealthProbe {
    fun sample(): HostHealthSnapshot

    /** Brackets the supplied callback so the returned health interval overlaps its execution. */
    fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T>
}

/** A health-only decision for one complete paired block. */
data class HealthDecision(val accepted: Boolean, val reasons: List<String>)

/** Stable machine-readable reasons emitted in policy evaluation order. */
object HostHealthReason {
    const val LOAD_AVERAGE_EXCEEDS_MAXIMUM: String = "load-average-exceeds-maximum"
    const val CPU_BUSY_FRACTION_EXCEEDS_MAXIMUM: String =
        "cpu-busy-fraction-exceeds-maximum"
    const val AVAILABLE_MEMORY_BELOW_MINIMUM: String = "available-memory-below-minimum"
    const val SWAP_GROWTH_EXCEEDS_MAXIMUM: String = "swap-growth-exceeds-maximum"
    const val THERMAL_VALUE_EXCEEDS_MAXIMUM: String = "thermal-value-exceeds-maximum"
    const val EXTERNAL_POWER_REQUIRED: String = "external-power-required"
    const val POWER_EVIDENCE_MISMATCH: String = "power-evidence-mismatch"
    const val GOVERNOR_NOT_ALLOWED: String = "governor-not-allowed"
}

/** Applies a controlled-host policy using only before/during/after host-health samples. */
class HostHealthGate(internal val policy: ControlledHostPolicy) {
    init {
        policy.validate()
    }

    /** Returns all reasons in stable policy order; metric observations are intentionally absent. */
    fun assess(
        before: HostHealthSnapshot,
        during: List<HostHealthSnapshot>,
        after: HostHealthSnapshot,
    ): HealthDecision {
        validateHostHealthTimeline(before, during, after, "hostHealthGate")
        val samples = listOf(before) + during + after
        val maximumSwapUsed = requireNotNull(samples.maxOfOrNull(HostHealthSnapshot::swapUsedBytes))
        val swapGrowth = Math.subtractExact(maximumSwapUsed, before.swapUsedBytes)
        val reasons =
            buildList {
                if (samples.any { it.loadAverage > policy.maximumLoadAverage }) {
                    add(HostHealthReason.LOAD_AVERAGE_EXCEEDS_MAXIMUM)
                }
                if (samples.any { it.cpuBusyFraction > policy.maximumCpuBusyFraction }) {
                    add(HostHealthReason.CPU_BUSY_FRACTION_EXCEEDS_MAXIMUM)
                }
                if (samples.any { it.availableMemoryBytes < policy.minimumAvailableMemoryBytes }) {
                    add(HostHealthReason.AVAILABLE_MEMORY_BELOW_MINIMUM)
                }
                if (swapGrowth > policy.maximumSwapDeltaBytes) {
                    add(HostHealthReason.SWAP_GROWTH_EXCEEDS_MAXIMUM)
                }
                if (samples.any { it.thermalValue > policy.maximumThermalValue }) {
                    add(HostHealthReason.THERMAL_VALUE_EXCEEDS_MAXIMUM)
                }
                when (policy.powerEvidenceRequirement) {
                    PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER -> Unit
                    PowerEvidenceRequirement.REQUIRE_EXTERNAL_POWER ->
                        if (samples.any { it.powerEvidence != PowerEvidence.EXTERNAL_POWER_ONLINE }) {
                            add(HostHealthReason.EXTERNAL_POWER_REQUIRED)
                        }
                    PowerEvidenceRequirement.FIXED_MAINS ->
                        if (samples.any { it.powerEvidence != PowerEvidence.FIXED_MAINS }) {
                            add(HostHealthReason.POWER_EVIDENCE_MISMATCH)
                        }
                }
                if (
                    samples.any { sample ->
                        sample.governors.isEmpty() ||
                            sample.governors.any { governor -> governor !in policy.allowedGovernors }
                    }
                ) {
                    add(HostHealthReason.GOVERNOR_NOT_ALLOWED)
                }
            }
        return HealthDecision(accepted = reasons.isEmpty(), reasons = reasons)
    }
}

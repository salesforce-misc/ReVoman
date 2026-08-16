/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.compare

import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.MetricId
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.stats.RatioInterval
import com.salesforce.revoman.benchmark.driver.stats.SlopeInterval
import com.salesforce.revoman.benchmark.driver.stats.Statistic
import com.squareup.moshi.JsonClass

/** Final state of one compatibility or release-gate decision. */
enum class GateDecision {
    PASS,
    FAIL,
    INCONCLUSIVE,
    INCOMPATIBLE,
}

/** Separates normative regression claims, requested improvements, and absolute invariants. */
enum class ClaimKind {
    NON_REGRESSION,
    TARGETED_IMPROVEMENT,
    STRUCTURAL,
}

/** One independently keyed metric or structural decision. */
@JsonClass(generateAdapter = true)
data class MetricDecision(
    val gate: GateId?,
    val claimKind: ClaimKind,
    val mode: RunMode,
    val metric: MetricId,
    val statistic: Statistic?,
    val interval: RatioInterval?,
    val slopeInterval: SlopeInterval?,
    val observedValue: Double?,
    val limit: Double,
    val decision: GateDecision,
    val reason: String,
) {
    /** Stable identity that prevents one targeted metric from overwriting another. */
    fun decisionKey(): DecisionKey = DecisionKey(claimKind, mode, metric, statistic)

    internal fun validate(path: String) {
        require(limit.isFinite() && limit >= 0.0) { "$path.limit must be finite and non-negative" }
        require(reason.isNotBlank()) { "$path.reason must not be blank" }
        interval?.validate("$path.interval")
        slopeInterval?.validate("$path.slopeInterval")
        observedValue?.let {
            require(it.isFinite() && it >= 0.0) {
                "$path.observedValue must be finite and non-negative"
            }
        }
        val evidenceCount = listOf(interval, slopeInterval, observedValue).count { it != null }
        require(evidenceCount <= 1) { "$path must contain at most one evidence field family" }
        val exactRetainedPolicyFailure =
            evidenceCount == 0 &&
                decision == GateDecision.FAIL &&
                gate == GateId.RETAINED_SLOPE &&
                claimKind == ClaimKind.STRUCTURAL &&
                mode == RunMode.RETAINED &&
                metric == MetricId.RETAINED_BYTES &&
                statistic == null
        require(
            decision in setOf(GateDecision.INCONCLUSIVE, GateDecision.INCOMPATIBLE) ||
                evidenceCount == 1 ||
                exactRetainedPolicyFailure
        ) {
            "$path PASS or FAIL decision requires exactly one evidence field family"
        }
        when (claimKind) {
            ClaimKind.NON_REGRESSION,
            ClaimKind.TARGETED_IMPROVEMENT,
            -> {
                require(metric !in STRUCTURAL_METRICS) {
                    "$path ratio decision cannot use structural metric $metric"
                }
                require(statistic != null && slopeInterval == null && observedValue == null) {
                    "$path ratio decision requires a statistic and only interval evidence"
                }
            }
            ClaimKind.STRUCTURAL -> {
                require(metric in STRUCTURAL_METRICS && statistic == null && interval == null) {
                    "$path structural decision requires a structural metric without ratio evidence"
                }
                if (evidenceCount == 1) {
                    require(
                        (metric == MetricId.RETAINED_BYTES && slopeInterval != null) ||
                            (metric == MetricId.BYTES_PER_STEP && observedValue != null)
                    ) {
                        "$path retained bytes require slope evidence and bytes per step require observed evidence"
                    }
                }
            }
        }
        require((gate == null) == (claimKind == ClaimKind.TARGETED_IMPROVEMENT)) {
            "$path targeted claims alone omit gate"
        }
        gate?.let { normativeGate ->
            require(CanonicalReleasePolicy.gate(normativeGate).decisionKey == decisionKey()) {
                "$path gate $normativeGate does not match claimKind, mode, metric, and statistic"
            }
        }
        if (claimKind == ClaimKind.TARGETED_IMPROVEMENT) {
            require(mode != RunMode.RETAINED) {
                "$path targeted improvement must use COLD or WARM mode"
            }
        }
        val canonicalLimit =
            gate?.let { CanonicalReleasePolicy.gate(it).limit }
                ?: CanonicalReleasePolicy.targetedLimit(mode)
        require(limit == canonicalLimit) {
            "$path.limit must equal the exact canonical release threshold $canonicalLimit"
        }
        val endpoint = interval?.upper95 ?: slopeInterval?.upper95BytesPerExecution ?: observedValue
        when (decision) {
            GateDecision.PASS -> require(requireNotNull(endpoint) <= limit) {
                "$path PASS evidence endpoint must be at most limit"
            }
            GateDecision.FAIL ->
                require(metric == MetricId.RETAINED_BYTES || requireNotNull(endpoint) > limit) {
                    "$path FAIL evidence endpoint must exceed limit"
                }
            GateDecision.INCONCLUSIVE,
            GateDecision.INCOMPATIBLE,
            -> Unit
        }
    }
}

/** Composite decision identity required by the release contract. */
data class DecisionKey(
    val claimKind: ClaimKind,
    val mode: RunMode,
    val metric: MetricId,
    val statistic: Statistic?,
)

/** Auditable host-rejection evidence that is never admitted to statistics. */
@JsonClass(generateAdapter = true)
data class RejectedBlockEvidence(
    val workloadId: String,
    val metric: MetricId,
    val blockId: Int,
    val reasons: List<String>,
)

/** Strict, canonical machine-readable comparison result. */
@JsonClass(generateAdapter = true)
data class ComparisonReport(
    val schema: String = COMPARISON_SCHEMA_V1,
    val campaignId: String,
    val compatibilityErrors: List<String>,
    val metrics: List<MetricDecision>,
    val rejectedBlocks: List<RejectedBlockEvidence>,
    val overall: GateDecision,
) {
    /** Rejects ambiguous decision keys, evidence shapes, and noncanonical report ordering. */
    fun validate(): ComparisonReport = apply {
        require(schema == COMPARISON_SCHEMA_V1) { "Unsupported comparison schema: $schema" }
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(compatibilityErrors.none(String::isBlank)) {
            "compatibilityErrors must not contain blanks"
        }
        require(compatibilityErrors == compatibilityErrors.distinct().sorted()) {
            "compatibilityErrors must be unique and sorted"
        }
        metrics.forEachIndexed { index, metric -> metric.validate("metrics[$index]") }
        require(metrics.map(MetricDecision::decisionKey).distinct().size == metrics.size) {
            "metrics must be unique by claimKind, mode, metric, and statistic"
        }
        require(metrics == metrics.sortedWith(METRIC_ORDER)) { "metrics must be in canonical order" }
        rejectedBlocks.forEachIndexed { index, evidence ->
            require(evidence.workloadId.isNotBlank()) {
                "rejectedBlocks[$index].workloadId must not be blank"
            }
            require(evidence.blockId >= 0) {
                "rejectedBlocks[$index].blockId must not be negative"
            }
            require(evidence.reasons.isNotEmpty() && evidence.reasons.none(String::isBlank)) {
                "rejectedBlocks[$index].reasons must contain non-blank reasons"
            }
        }
        require(rejectedBlocks == rejectedBlocks.sortedWith(REJECTED_ORDER)) {
            "rejectedBlocks must be in canonical workload, metric, and block order"
        }
        val expectedOverall =
            when {
                compatibilityErrors.isNotEmpty() ||
                    metrics.any { it.decision == GateDecision.INCOMPATIBLE } ->
                    GateDecision.INCOMPATIBLE
                metrics.any { it.decision == GateDecision.INCONCLUSIVE } ->
                    GateDecision.INCONCLUSIVE
                metrics.any { it.decision == GateDecision.FAIL } -> GateDecision.FAIL
                metrics.isEmpty() -> GateDecision.INCONCLUSIVE
                else -> GateDecision.PASS
            }
        require(overall == expectedOverall) {
            "overall must follow INCOMPATIBLE > INCONCLUSIVE > FAIL > PASS: " +
                "expected=$expectedOverall, actual=$overall"
        }
    }

    internal fun canonicalized(): ComparisonReport =
        copy(
            compatibilityErrors = compatibilityErrors.distinct().sorted(),
            metrics = metrics.sortedWith(METRIC_ORDER),
            rejectedBlocks = rejectedBlocks.sortedWith(REJECTED_ORDER),
        )

    /** Renders the validated machine result without recalculating or changing any decision. */
    fun toMarkdown(): String {
        validate()
        return buildString {
            appendLine("# Benchmark comparison")
            appendLine()
            appendLine("Campaign: $campaignId")
            appendLine("Overall: $overall")
            if (compatibilityErrors.isNotEmpty()) {
                appendLine()
                appendLine("## Compatibility errors")
                compatibilityErrors.forEach { appendLine("- $it") }
            }
            if (metrics.isNotEmpty()) {
                appendLine()
                appendLine("## Decisions")
                appendLine()
                appendLine(
                    "| Claim | Mode | Metric | Statistic | Point | Lower 95% | Upper 95% | " +
                        "Observed | Limit | Decision | Reason |"
                )
                appendLine("|---|---|---|---|---:|---:|---:|---:|---:|---|---|")
                metrics.forEach { metric ->
                    val point =
                        metric.interval?.pointEstimate
                            ?: metric.slopeInterval?.pointEstimateBytesPerExecution
                    val lower =
                        metric.interval?.lower95
                            ?: metric.slopeInterval?.lower95BytesPerExecution
                    val upper =
                        metric.interval?.upper95
                            ?: metric.slopeInterval?.upper95BytesPerExecution
                    appendLine(
                        "| ${metric.claimKind} | ${metric.mode} | ${metric.metric} | " +
                            "${metric.statistic ?: "-"} | ${point ?: "-"} | ${lower ?: "-"} | " +
                            "${upper ?: "-"} | ${metric.observedValue ?: "-"} | ${metric.limit} | " +
                            "${metric.decision} | " +
                            "${metric.reason.replace("|", "\\|")} |"
                    )
                }
            }
            if (rejectedBlocks.isNotEmpty()) {
                appendLine()
                appendLine("## Rejected blocks")
                rejectedBlocks.forEach { rejected ->
                    appendLine(
                        "- ${rejected.workloadId}/${rejected.metric}/${rejected.blockId}: " +
                            rejected.reasons.joinToString(", ")
                    )
                }
            }
        }
    }
}

private fun RatioInterval.validate(path: String) {
    require(listOf(pointEstimate, lower95, upper95).all { it.isFinite() && it >= 0.0 }) {
        "$path values must be finite and non-negative"
    }
    require(lower95 <= upper95) { "$path lower95 must not exceed upper95" }
}

private fun SlopeInterval.validate(path: String) {
    require(
        listOf(pointEstimateBytesPerExecution, lower95BytesPerExecution, upper95BytesPerExecution)
            .all(Double::isFinite)
    ) {
        "$path values must be finite"
    }
    require(lower95BytesPerExecution <= upper95BytesPerExecution) {
        "$path lower95BytesPerExecution must not exceed upper95BytesPerExecution"
    }
}

private val METRIC_ORDER =
    compareBy<MetricDecision>(
            { it.claimKind.ordinal },
            { it.mode.ordinal },
            { it.metric.ordinal },
            { it.statistic?.ordinal ?: -1 },
        )
        .thenBy { it.gate?.ordinal ?: -1 }
private val REJECTED_ORDER =
    compareBy<RejectedBlockEvidence>({ it.workloadId }, { it.metric.ordinal }, { it.blockId })
private val STRUCTURAL_METRICS = setOf(MetricId.RETAINED_BYTES, MetricId.BYTES_PER_STEP)
internal const val COMPARISON_SCHEMA_V1: String = "revoman-benchmark-comparison/v1"

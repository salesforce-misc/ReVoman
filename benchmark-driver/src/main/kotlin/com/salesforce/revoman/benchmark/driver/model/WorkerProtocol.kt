/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.squareup.moshi.JsonClass

/** Controls the target process lifecycle used for a benchmark run. */
enum class RunMode {
    COLD,
    WARM,
    RETAINED,
}

/** Selects the single metric family collected by a worker invocation. */
enum class MetricPass {
    LATENCY,
    ALLOCATION,
    PEAK_RSS,
    RETAINED,
}

/** Describes one versioned workload and its target-independent input parameters. */
@JsonClass(generateAdapter = true)
data class WorkloadRequest(
    val id: String,
    val contractVersion: Int,
    val fixtureRoot: String,
    val baseUrl: String,
    val parameters: Map<String, String> = emptyMap(),
)

/** Captures cheap filesystem identity fields for one verified target artifact. */
@JsonClass(generateAdapter = true)
data class VerifiedArtifactStamp(
    val logicalId: String,
    val executionPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val fileKey: String?,
)

/** Binds a worker invocation to one target manifest and its ordered verified artifacts. */
@JsonClass(generateAdapter = true)
data class TargetVerificationToken(
    val targetManifest: String,
    val targetManifestSha256: String,
    val targetClasspathSha256: String,
    val artifactStamps: List<VerifiedArtifactStamp>,
)

/** Carries the complete version-one command accepted by an isolated target worker. */
@JsonClass(generateAdapter = true)
data class TargetForkCommand(
    val protocolVersion: Int = 1,
    val verification: TargetVerificationToken,
    val adapterId: String,
    val mode: RunMode,
    val metricPass: MetricPass,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val resultFile: String,
)

/** Summarizes execution correctness without exposing target-runtime objects across the seam. */
@JsonClass(generateAdapter = true)
data class ExecutionDigest(
    val checksum: Long,
    val executedSteps: Int,
    val failureCount: Int,
)

/** Records one measured latency and its execution digest. */
@JsonClass(generateAdapter = true)
data class TargetSample(
    val iteration: Int,
    val latencyNanos: Long,
    val digest: ExecutionDigest,
)

/** Carries all completed samples emitted by one isolated target worker. */
@JsonClass(generateAdapter = true)
data class TargetForkResult(
    val protocolVersion: Int = 1,
    val processId: Long,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val samples: List<TargetSample>,
)

/** Rejects any failed or oracle-divergent execution before its timing can become evidence. */
internal fun requireExpectedExecutionDigest(
    actual: ExecutionDigest,
    expected: ExecutionDigest,
    location: String,
): ExecutionDigest =
    actual.apply {
        check(failureCount == 0) {
            "$location digest failureCount must be zero, actual=$failureCount"
        }
        check(checksum == expected.checksum) {
            "$location digest checksum mismatch: expected=${expected.checksum}, actual=$checksum"
        }
        check(executedSteps == expected.executedSteps) {
            "$location digest executedSteps mismatch: " +
                "expected=${expected.executedSteps}, actual=$executedSteps"
        }
        check(failureCount == expected.failureCount) {
            "$location digest failureCount mismatch: " +
                "expected=${expected.failureCount}, actual=$failureCount"
        }
    }

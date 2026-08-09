/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.squareup.moshi.JsonClass

enum class RunMode { COLD, WARM, RETAINED }

enum class MetricPass { LATENCY, ALLOCATION, PEAK_RSS, RETAINED }

@JsonClass(generateAdapter = true)
data class WorkloadRequest(
  val id: String,
  val contractVersion: Int,
  val fixtureRoot: String,
  val baseUrl: String,
  val parameters: Map<String, String> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class VerifiedArtifactStamp(
  val logicalId: String,
  val executionPath: String,
  val sizeBytes: Long,
  val lastModifiedMillis: Long,
  val fileKey: String?,
)

@JsonClass(generateAdapter = true)
data class TargetVerificationToken(
  val targetManifest: String,
  val targetManifestSha256: String,
  val targetClasspathSha256: String,
  val artifactStamps: List<VerifiedArtifactStamp>,
)

@JsonClass(generateAdapter = true)
data class TargetForkCommand(
  val protocolVersion: Int = 1,
  val verification: TargetVerificationToken,
  val adapterId: String,
  val mode: RunMode,
  val metricPass: MetricPass,
  val workload: WorkloadRequest,
  val warmupIterations: Int,
  val measurementIterations: Int,
  val resultFile: String,
)

@JsonClass(generateAdapter = true)
data class ExecutionDigest(
  val checksum: Long,
  val executedSteps: Int,
  val failureCount: Int,
)

@JsonClass(generateAdapter = true)
data class TargetSample(
  val iteration: Int,
  val latencyNanos: Long,
  val digest: ExecutionDigest,
)

@JsonClass(generateAdapter = true)
data class TargetForkResult(
  val protocolVersion: Int = 1,
  val processId: Long,
  val warmupIterations: Int,
  val measurementIterations: Int,
  val samples: List<TargetSample>,
)

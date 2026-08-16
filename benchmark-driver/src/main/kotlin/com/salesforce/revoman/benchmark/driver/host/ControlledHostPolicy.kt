/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.host

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.squareup.moshi.JsonClass
import java.nio.file.Files
import java.nio.file.Path

internal const val CONTROLLED_HOST_SCHEMA_RESOURCE: String =
    "/schema/revoman-controlled-host-v1.schema.json"
private const val CONTROLLED_HOST_SCHEMA_V1: String = "revoman-controlled-host/v1"
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

/** Specifies the source and acceptance criteria required for controlled power evidence. */
enum class PowerEvidenceRequirement {
    OBSERVE_EXTERNAL_POWER,
    REQUIRE_EXTERNAL_POWER,
    FIXED_MAINS,
}

/** Explicit, versioned limits and expected identity for one controlled Linux benchmark host. */
@JsonClass(generateAdapter = true)
data class ControlledHostPolicy(
    val schema: String = CONTROLLED_HOST_SCHEMA_V1,
    val hostFingerprintSha256: String,
    val cpuModel: String,
    val cpuCount: Int,
    val allowedGovernors: Set<String>,
    val powerEvidenceRequirement: PowerEvidenceRequirement,
    val maximumLoadAverage: Double,
    val maximumCpuBusyFraction: Double,
    val minimumAvailableMemoryBytes: Long,
    val maximumSwapDeltaBytes: Long,
    val maximumThermalValue: Double,
    val probeIntervalMillis: Long,
    val maximumReplacementBlocks: Int,
) {
    /** Fails closed when the policy cannot identify one exact host or contains invalid limits. */
    fun validate(): ControlledHostPolicy = apply {
        require(schema == CONTROLLED_HOST_SCHEMA_V1) {
            "Unsupported controlled host policy schema: $schema"
        }
        require(SHA256_PATTERN.matches(hostFingerprintSha256)) {
            "hostFingerprintSha256 must be a lowercase 64-character SHA-256"
        }
        require(cpuModel.isNotBlank() && cpuModel == cpuModel.trim()) {
            "cpuModel must be non-blank and trimmed"
        }
        require(cpuCount > 0) { "cpuCount must be positive" }
        require(allowedGovernors.isNotEmpty()) { "allowedGovernors must not be empty" }
        require(allowedGovernors.all { governor -> governor.isNotBlank() && governor == governor.trim() }) {
            "allowedGovernors must contain only non-blank trimmed values"
        }
        requireFiniteNonNegative("maximumLoadAverage", maximumLoadAverage)
        requireFiniteNonNegative("maximumCpuBusyFraction", maximumCpuBusyFraction)
        require(maximumCpuBusyFraction <= 1.0) {
            "maximumCpuBusyFraction must not exceed one"
        }
        require(minimumAvailableMemoryBytes > 0) {
            "minimumAvailableMemoryBytes must be positive"
        }
        require(maximumSwapDeltaBytes >= 0) { "maximumSwapDeltaBytes must not be negative" }
        require(maximumThermalValue.isFinite() && maximumThermalValue > 0.0) {
            "maximumThermalValue must be finite and positive degrees Celsius"
        }
        require(probeIntervalMillis > 0) { "probeIntervalMillis must be positive" }
        require(maximumReplacementBlocks >= 0) {
            "maximumReplacementBlocks must not be negative"
        }
    }

    internal fun canonicalized(): ControlledHostPolicy =
        copy(allowedGovernors = allowedGovernors.toSortedSet())

    companion object {
        /** Loads one canonical path from a coherent byte snapshot and validates JSON plus policy rules. */
        fun load(path: Path): VerifiedControlledHostPolicy {
            require(path.isAbsolute && path.normalize() == path) {
                "Controlled host policy path must be absolute and normalized: $path"
            }
            require(Files.isRegularFile(path)) {
                "Controlled host policy path must be a regular file: $path"
            }
            val canonicalPath = path.toRealPath()
            require(canonicalPath == path) {
                "Controlled host policy path must be canonical: $path"
            }
            val bytes = Files.readAllBytes(canonicalPath)
            BenchmarkJson.validateSchema(bytes, canonicalPath.toString(), CONTROLLED_HOST_SCHEMA_RESOURCE)
            val policy = BenchmarkJson.decode<ControlledHostPolicy>(bytes, canonicalPath.toString())
            val canonicalBytes = BenchmarkJson.encode(policy)
            return VerifiedControlledHostPolicy(
                policy = policy,
                source = canonicalPath,
                canonicalSha256 = ContentHasher.sha256(canonicalBytes),
            )
        }
    }
}

/** A validated controlled policy and its path-independent canonical semantic identity. */
data class VerifiedControlledHostPolicy(
    val policy: ControlledHostPolicy,
    val source: Path,
    val canonicalSha256: String,
)

private fun requireFiniteNonNegative(name: String, value: Double) {
    require(value.isFinite() && value >= 0.0) { "$name must be finite and non-negative" }
}

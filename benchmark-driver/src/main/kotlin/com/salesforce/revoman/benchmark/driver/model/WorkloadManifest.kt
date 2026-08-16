/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.squareup.moshi.JsonClass

/** Declares one versioned workload, its fixture identity, and the gates required by each mode. */
@JsonClass(generateAdapter = true)
data class WorkloadManifest(
    val id: String,
    val contractVersion: Int,
    val files: List<HashedArtifact>,
    val fixtureTreeSha256: String,
    val operationIds: List<String>,
    val requiredGatesByMode: Map<RunMode, List<GateId>>,
    val expectedDigest: ExecutionDigest?,
) {
    /** Rejects incomplete workload identities and noncanonical gate lists. */
    fun validate(): WorkloadManifest = apply {
        requireNonBlank("id", id)
        require(contractVersion > 0) { "contractVersion must be positive" }
        require(files.isNotEmpty()) { "files must not be empty" }
        validateArtifacts("files", files)
        requireSha256("fixtureTreeSha256", fixtureTreeSha256)
        require(operationIds.isNotEmpty()) { "operationIds must not be empty" }
        require(operationIds.none(String::isBlank)) { "operationIds must not contain blanks" }
        require(operationIds.distinct().size == operationIds.size) { "operationIds must be unique" }
        require(requiredGatesByMode.keys == RunMode.entries.toSet()) {
            "requiredGatesByMode must configure COLD, WARM, and RETAINED"
        }
        requiredGatesByMode.forEach { (mode, gates) ->
            requireCanonicalEnumOrder("requiredGatesByMode.$mode", gates)
        }
        expectedDigest?.let {
            require(it.executedSteps >= 0) { "expectedDigest.executedSteps must not be negative" }
            require(it.failureCount >= 0) { "expectedDigest.failureCount must not be negative" }
        }
    }

    internal fun canonicalized(): WorkloadManifest =
        copy(
            requiredGatesByMode = requiredGatesByMode.mapValues { (_, gates) ->
                gates.distinct().sortedBy(Enum<*>::ordinal)
            },
        )
}

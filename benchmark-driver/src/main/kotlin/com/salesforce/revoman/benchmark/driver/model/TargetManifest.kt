/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.model

import com.squareup.moshi.JsonClass

/** Declares the reproducible build identity and ordered runtime classpath of one target. */
@JsonClass(generateAdapter = true)
data class TargetManifest(
    val schema: String = TARGET_MANIFEST_SCHEMA_V1,
    val targetId: String,
    val gitCommit: String,
    val gitTree: String,
    val dirty: Boolean,
    val gradleVersion: String,
    val wrapperSha256: String,
    val jdk: JdkIdentity,
    val classpath: List<HashedArtifact>,
) {
    /** Rejects incomplete identities while preserving executable classpath order. */
    fun validate(): TargetManifest = apply {
        require(schema == TARGET_MANIFEST_SCHEMA_V1) { "Unsupported target manifest schema: $schema" }
        requireNonBlank("targetId", targetId)
        requireNonBlank("gitCommit", gitCommit)
        requireNonBlank("gitTree", gitTree)
        requireNonBlank("gradleVersion", gradleVersion)
        requireSha256("wrapperSha256", wrapperSha256)
        jdk.validate("jdk")
        require(classpath.isNotEmpty()) { "classpath must not be empty" }
        validateArtifacts("classpath", classpath)
    }
}

/** One content-addressed runtime artifact and its checkout-local execution path. */
@JsonClass(generateAdapter = true)
data class HashedArtifact(
    val logicalId: String,
    val executionPath: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Path-free content identity for one artifact in an ordered target runtime classpath. */
@JsonClass(generateAdapter = true)
data class ArtifactSnapshot(
    val logicalId: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Captures a complete JDK identity, including flags that affect benchmark behavior. */
@JsonClass(generateAdapter = true)
data class JdkIdentity(
    val distribution: String,
    val vendor: String,
    val fullVersion: String,
    val javaHome: String,
    val jvmFlags: List<String>,
)

/** Binds an adapter ID to the content hash of its source. */
@JsonClass(generateAdapter = true)
data class AdapterIdentity(
    val id: String,
    val sourceSha256: String,
)

private const val TARGET_MANIFEST_SCHEMA_V1: String = "revoman-target-manifest/v1"

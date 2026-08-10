/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.integrity

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path

/** One schema-valid model and the exact controller-side artifact verification bound to its source. */
data class LoadedTargetManifest(
    val manifest: TargetManifest,
    val snapshotBytes: ByteArray,
    val verified: VerifiedTargetManifest,
) {
    /** Applies release cleanliness without making smoke/JMH structure invalid. */
    fun requireClean(operation: String): LoadedTargetManifest = apply {
        require(!manifest.dirty) { "$operation requires a clean target ${manifest.targetId}" }
    }

    override fun equals(other: Any?): Boolean =
        other is LoadedTargetManifest &&
            manifest == other.manifest &&
            snapshotBytes.contentEquals(other.snapshotBytes) &&
            verified == other.verified

    override fun hashCode(): Int = 31 * (31 * manifest.hashCode() + snapshotBytes.contentHashCode()) + verified.hashCode()
}

/** Loads one coherent target-manifest snapshot before running the existing full artifact preflight. */
object TargetManifestLoader {
    fun load(path: Path): LoadedTargetManifest {
        val canonical = path.toAbsolutePath().normalize().toRealPath()
        require(canonical == path.toAbsolutePath().normalize() && Files.isRegularFile(canonical)) {
            "Target manifest must be a canonical regular file: $path"
        }
        val bytes = Files.readAllBytes(canonical)
        BenchmarkJson.validateSchema(bytes, canonical.toString(), TARGET_MANIFEST_SCHEMA_RESOURCE)
        val manifest = BenchmarkJson.decode<TargetManifest>(bytes, canonical.toString())
        val verified = VerifiedTargetManifest.preflightFromSnapshot(canonical, bytes, manifest)
        return LoadedTargetManifest(manifest, bytes.copyOf(), verified)
    }
}

internal const val TARGET_MANIFEST_SCHEMA_RESOURCE: String =
    "/schema/revoman-target-manifest-v1.schema.json"

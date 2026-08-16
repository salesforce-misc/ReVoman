/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Canonical logging source plus the exact content identity selected for a campaign. */
class VerifiedLoggingConfiguration private constructor(
    val sourcePath: Path,
    val sha256: String,
    private val capturedBytes: ByteArray,
) {
    internal fun materialize(campaignDirectory: Path): Path {
        val sourceHash = ContentHasher.sha256(sourcePath)
        require(sourceHash == sha256) {
            "Logging configuration changed before campaign setup: " +
                "expected=$sha256, actual=$sourceHash"
        }
        val snapshot = campaignDirectory.resolve("log4j2-benchmark.xml")
        Files.write(snapshot, capturedBytes, CREATE_NEW)
        check(ContentHasher.sha256(snapshot) == sha256) {
            "Logging configuration snapshot differs from verified bytes"
        }
        return snapshot
    }

    internal fun postflight(snapshot: Path) {
        val failures = mutableListOf<String>()
        val canonicalSource = runCatching(sourcePath::toRealPath).getOrNull()
        val canonicalSnapshot = runCatching(snapshot::toRealPath).getOrNull()
        val sourceHash = runCatching { ContentHasher.sha256(sourcePath) }.getOrNull()
        val snapshotHash = runCatching { ContentHasher.sha256(snapshot) }.getOrNull()
        if (canonicalSource != sourcePath) failures += "source path is no longer canonical"
        if (canonicalSnapshot != snapshot) failures += "snapshot path is no longer canonical"
        when {
            sourceHash == null -> failures += "source is unreadable"
            sourceHash != sha256 -> failures += "source SHA-256 changed"
        }
        when {
            snapshotHash == null -> failures += "snapshot is unreadable"
            snapshotHash != sha256 -> failures += "snapshot SHA-256 changed"
        }
        check(failures.isEmpty()) {
            "Logging configuration invalid after postflight: ${failures.joinToString()}"
        }
    }

    companion object {
        /** Reads and identifies one canonical logging configuration for later campaign use. */
        fun preflight(sourcePath: Path): VerifiedLoggingConfiguration {
            val canonical = sourcePath.toRealPath()
            require(canonical == sourcePath && Files.isRegularFile(canonical)) {
                "Logging configuration must be a canonical regular file: $sourcePath"
            }
            val bytes = Files.readAllBytes(canonical)
            return VerifiedLoggingConfiguration(
                sourcePath = canonical,
                sha256 = ContentHasher.sha256(bytes),
                capturedBytes = bytes.copyOf(),
            )
        }
    }
}

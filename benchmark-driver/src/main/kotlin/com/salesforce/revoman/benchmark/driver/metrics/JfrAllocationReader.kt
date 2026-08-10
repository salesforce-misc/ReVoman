/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import jdk.jfr.consumer.RecordingFile

/** Immutable allocation evidence read from one cold JDK 21 flight recording. */
data class JfrAllocationMeasurement(
    val provider: String,
    val providerConfigurationSha256: String,
    val recordingConfigurationSha256: String,
    val allocatedBytes: Long,
)

/** Captures one JFC byte snapshot and verifies both its source and materialized copy postflight. */
class VerifiedJfrConfiguration private constructor(
    val sourcePath: Path,
    val sha256: String,
    private val capturedBytes: ByteArray,
) {
    internal fun materialize(directory: Path): Path {
        require(ContentHasher.sha256(sourcePath) == sha256) {
            "JFR configuration changed before pass setup"
        }
        val snapshot = directory.resolve("revoman-allocation-v1.jfc")
        Files.write(snapshot, capturedBytes, CREATE_NEW)
        check(ContentHasher.sha256(snapshot) == sha256) {
            "JFR configuration snapshot differs from verified bytes"
        }
        return snapshot.toRealPath()
    }

    internal fun postflight(snapshot: Path) {
        val failures = mutableListOf<String>()
        val sourceHash = runCatching { ContentHasher.sha256(sourcePath) }.getOrNull()
        val snapshotHash = runCatching { ContentHasher.sha256(snapshot) }.getOrNull()
        if (runCatching(sourcePath::toRealPath).getOrNull() != sourcePath) {
            failures += "source path is no longer canonical"
        }
        if (runCatching(snapshot::toRealPath).getOrNull() != snapshot) {
            failures += "snapshot path is no longer canonical"
        }
        when {
            sourceHash == null -> failures += "source is unreadable"
            sourceHash != sha256 -> failures += "source SHA-256 changed"
        }
        when {
            snapshotHash == null -> failures += "snapshot is unreadable"
            snapshotHash != sha256 -> failures += "snapshot SHA-256 changed"
        }
        check(failures.isEmpty()) {
            "JFR configuration invalid after postflight: ${failures.joinToString()}"
        }
    }

    companion object {
        internal fun preflight(sourcePath: Path): VerifiedJfrConfiguration {
            val canonical = sourcePath.toRealPath()
            require(canonical == sourcePath && Files.isRegularFile(canonical)) {
                "JFR configuration must be a canonical regular file: $sourcePath"
            }
            val bytes = Files.readAllBytes(canonical)
            return VerifiedJfrConfiguration(
                sourcePath = canonical,
                sha256 = ContentHasher.sha256(bytes),
                capturedBytes = bytes.copyOf(),
            )
        }
    }
}

internal data class JfrAllocationEvent(
    val name: String,
    val fields: Map<String, Long>,
)

internal fun interface JfrEventSource {
    fun forEach(recordingFile: Path, consumer: (JfrAllocationEvent) -> Unit)
}

/** Reads reserved in-TLAB bytes plus outside-TLAB object bytes from one JDK 21 JFR file. */
class JfrAllocationReader
internal constructor(private val eventSource: JfrEventSource) {
    constructor() : this(RecordingFileEventSource)

    /** Reads and validates the exact recording/configuration identity supplied by the worker. */
    fun read(
        recordingFile: Path,
        configurationFile: Path,
        resultConfigurationSha256: String,
    ): JfrAllocationMeasurement {
        require(Files.isRegularFile(recordingFile)) {
            "JFR recording must be a regular file: $recordingFile"
        }
        require(Files.isRegularFile(configurationFile)) {
            "JFR configuration must be a regular file: $configurationFile"
        }
        val actualConfigurationSha256 = ContentHasher.sha256(configurationFile)
        require(actualConfigurationSha256 == resultConfigurationSha256) {
            "JFR configuration SHA-256 differs from result metadata: " +
                "expected=$actualConfigurationSha256, actual=$resultConfigurationSha256"
        }
        var total = 0L
        var allocationEventCount = 0
        try {
            eventSource.forEach(recordingFile) { event ->
                val allocation =
                    when (event.name) {
                        NEW_TLAB_EVENT -> event.requiredLong(NEW_TLAB_SIZE_FIELD)
                        OUTSIDE_TLAB_EVENT -> event.requiredLong(OUTSIDE_TLAB_SIZE_FIELD)
                        else -> null
                    }
                allocation?.let { bytes ->
                    require(bytes >= 0) { "JFR ${event.name} allocation size must not be negative" }
                    total = Math.addExact(total, bytes)
                    allocationEventCount++
                }
            }
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("JFR allocation byte sum overflow", failure)
        }
        require(allocationEventCount > 0) {
            "JFR recording contains no supported allocation event"
        }
        return JfrAllocationMeasurement(
            provider = PROVIDER_ID,
            providerConfigurationSha256 =
                ContentHasher.sha256(
                    "$PROVIDER_ID\u0000$actualConfigurationSha256".toByteArray(UTF_8)
                ),
            recordingConfigurationSha256 = actualConfigurationSha256,
            allocatedBytes = total,
        )
    }

    private fun JfrAllocationEvent.requiredLong(field: String): Long =
        requireNotNull(fields[field]) { "JFR $name event is missing required $field field" }

    companion object {
        const val PROVIDER_ID: String = "jdk21-jfr-tlab-reserved-plus-outside/v1"
        internal const val NEW_TLAB_EVENT: String = "jdk.ObjectAllocationInNewTLAB"
        internal const val OUTSIDE_TLAB_EVENT: String = "jdk.ObjectAllocationOutsideTLAB"
        internal const val NEW_TLAB_SIZE_FIELD: String = "tlabSize"
        internal const val OUTSIDE_TLAB_SIZE_FIELD: String = "allocationSize"
    }
}

private object RecordingFileEventSource : JfrEventSource {
    override fun forEach(recordingFile: Path, consumer: (JfrAllocationEvent) -> Unit) {
        RecordingFile(recordingFile).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                when (event.eventType.name) {
                    JfrAllocationReader.NEW_TLAB_EVENT ->
                        consumer(
                            JfrAllocationEvent(
                                name = event.eventType.name,
                                fields =
                                    mapOf(
                                        JfrAllocationReader.NEW_TLAB_SIZE_FIELD to
                                            event.getLong(JfrAllocationReader.NEW_TLAB_SIZE_FIELD)
                                    ),
                            )
                        )
                    JfrAllocationReader.OUTSIDE_TLAB_EVENT ->
                        consumer(
                            JfrAllocationEvent(
                                name = event.eventType.name,
                                fields =
                                    mapOf(
                                        JfrAllocationReader.OUTSIDE_TLAB_SIZE_FIELD to
                                            event.getLong(JfrAllocationReader.OUTSIDE_TLAB_SIZE_FIELD)
                                    ),
                            )
                        )
                }
            }
        }
    }
}

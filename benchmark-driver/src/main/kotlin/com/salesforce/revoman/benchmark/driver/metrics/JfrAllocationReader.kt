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
import jdk.jfr.consumer.RecordingFile

/** Immutable allocation evidence read from one cold JDK 21 flight recording. */
data class JfrAllocationMeasurement(
    val provider: String,
    val providerConfigurationSha256: String,
    val recordingConfigurationSha256: String,
    val allocatedBytes: Long,
)

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

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.json

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.salesforce.revoman.benchmark.driver.host.ControlledHostPolicy
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RetainedCheckpoint
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.VerifiedArtifactStamp
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object BenchmarkJson {
    private const val PROTOCOL_VERSION: Int = 1
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val moshi = Moshi.Builder().build()
    private val dynamicJsonAdapter = moshi.adapter(Any::class.java)

    inline fun <reified T : Any> read(path: Path): T = read(path, T::class.java)

    inline fun <reified T : Any> decode(bytes: ByteArray, source: String): T =
        decode(bytes, source, T::class.java)

    inline fun <reified T : Any> encode(value: T): ByteArray = encode(value, T::class.java)

    inline fun <reified T : Any> write(path: Path, value: T): Unit =
        write(path, value, T::class.java)

    fun validateSchema(path: Path, schemaResource: String) {
        validateSchema(Files.readAllBytes(path), path.toString(), schemaResource)
    }

    fun validateSchema(bytes: ByteArray, source: String, schemaResource: String) {
        val schemaStream =
            requireNotNull(BenchmarkJson::class.java.getResourceAsStream(schemaResource)) {
                "Schema resource does not exist: $schemaResource"
            }
        val messages =
            schemaStream.use {
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(it)
                    .validate(bytes.toString(UTF_8), InputFormat.JSON)
            }

        require(messages.isEmpty()) {
            "JSON at $source does not satisfy $schemaResource: $messages"
        }
    }

    @PublishedApi
    internal fun <T : Any> read(path: Path, type: Class<T>): T =
        decode(Files.readAllBytes(path), path.toString(), type)

    @PublishedApi
    internal fun <T : Any> decode(bytes: ByteArray, source: String, type: Class<T>): T =
        requireNotNull(adapter(type).fromJson(bytes.toString(UTF_8))) { "JSON at $source is null" }
            .also(::validate)

    @PublishedApi
    internal fun <T : Any> encode(value: T, type: Class<T>): ByteArray {
        val normalized = type.cast(normalize(value))
        validate(normalized)
        return dynamicJsonAdapter
            .toJson(canonicalize(adapter(type).toJsonValue(normalized)))
            .toByteArray(UTF_8)
    }

    @PublishedApi
    internal fun <T : Any> write(path: Path, value: T, type: Class<T>) {
        val encoded = encode(value, type)
        val parent = requireNotNull(path.parent) { "Output path must have a parent: $path" }
        Files.createDirectories(parent)
        val temporaryFile = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")

        try {
            Files.write(temporaryFile, encoded)
            moveAtomically(temporaryFile, path)
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun <T : Any> adapter(type: Class<T>): JsonAdapter<T> =
        moshi.adapter(type).failOnUnknown()

    private fun moveAtomically(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun canonicalize(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                value.entries
                    .associate {
                        require(it.key is String) { "JSON object keys must be strings" }
                        it.key as String to canonicalize(it.value)
                    }
                    .toSortedMap()
            is List<*> -> value.map(::canonicalize)
            else -> value
        }

    private fun validate(value: Any) {
        when (value) {
            is ControlledHostPolicy -> value.validate()
            is BenchmarkResultV1 -> value.validate()
            is HostHealthSnapshot -> value.validate("hostHealthSnapshot")
            is JmhBenchmarkResultV1 -> value.validate()
            is TargetManifest -> value.validate()
            is TargetForkCommand -> validateCommand(value)
            is TargetForkResult -> validateResult(value)
            is WorkloadManifest -> value.validate()
        }
    }

    private fun normalize(value: Any): Any =
        when (value) {
            is ControlledHostPolicy -> value.canonicalized()
            is BenchmarkResultV1 -> value.canonicalized()
            is JmhBenchmarkResultV1 -> value.canonicalized()
            is WorkloadManifest -> value.canonicalized()
            else -> value
        }

    private fun validateCommand(command: TargetForkCommand) {
        require(command.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported protocol version: ${command.protocolVersion}"
        }
        validateVerification(command.verification)
        requireNonBlank("adapterId", command.adapterId)
        validateWorkload(command.workload)
        command.expectedDigest?.let(::validateDigest)
        require(command.warmupIterations >= 0) { "warmupIterations must not be negative" }
        require(command.measurementIterations >= 0) {
            "measurementIterations must not be negative"
        }
        requireNonBlank("resultFile", command.resultFile)
        validateCommandMode(command)
    }

    private fun validateVerification(verification: TargetVerificationToken) {
        requireNonBlank("targetManifest", verification.targetManifest)
        require(verification.targetManifestSha256.matches(sha256Pattern)) {
            "targetManifestSha256 must be a 64-character SHA-256 hash"
        }
        require(verification.targetClasspathSha256.matches(sha256Pattern)) {
            "targetClasspathSha256 must be a 64-character SHA-256 hash"
        }
        verification.artifactStamps.forEach(::validateArtifactStamp)
        val stampLogicalIds = verification.artifactStamps.map(VerifiedArtifactStamp::logicalId)
        require(stampLogicalIds.distinct().size == stampLogicalIds.size) {
            "artifactStamps logical IDs must be unique"
        }
    }

    private fun validateArtifactStamp(stamp: VerifiedArtifactStamp) {
        requireNonBlank("artifact logicalId", stamp.logicalId)
        requireNonBlank("artifact executionPath", stamp.executionPath)
        require(stamp.sizeBytes >= 0) { "artifact sizeBytes must not be negative" }
        require(stamp.lastModifiedMillis >= 0) {
            "artifact lastModifiedMillis must not be negative"
        }
    }

    private fun validateWorkload(workload: WorkloadRequest) {
        requireNonBlank("workload id", workload.id)
        require(workload.contractVersion > 0) { "workload contractVersion must be positive" }
        requireNonBlank("fixtureRoot", workload.fixtureRoot)
        requireNonBlank("baseUrl", workload.baseUrl)
        require(workload.parameters.keys.none(String::isBlank)) {
            "workload parameter names must not be blank"
        }
    }

    private fun validateResult(result: TargetForkResult) {
        require(result.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported protocol version: ${result.protocolVersion}"
        }
        require(result.warmupIterations >= 0) { "warmupIterations must not be negative" }
        require(result.measurementIterations >= 0) {
            "measurementIterations must not be negative"
        }
        require(result.processId > 0) { "processId must be positive" }
        result.jfrConfigurationSha256?.let { hash ->
            require(hash.matches(sha256Pattern)) {
                "jfrConfigurationSha256 must be a 64-character SHA-256 hash"
            }
        }
        result.retainedCheckpoint?.let(::validateRetainedCheckpoint)
        when (result.retainedCheckpoint) {
            null -> require(result.measurementIterations == result.samples.size) {
                "measurementIterations must match samples.size"
            }
            else -> {
                require(result.warmupIterations == 0) {
                    "retained result warmupIterations must be zero"
                }
                require(result.measurementIterations == 0) {
                    "retained result measurementIterations must be zero"
                }
                require(result.samples.isEmpty()) { "retained result samples must be empty" }
                require(result.jfrConfigurationSha256 == null) {
                    "retained result cannot carry JFR configuration metadata"
                }
            }
        }
        result.samples.forEach(::validateSample)
    }

    private fun validateCommandMode(command: TargetForkCommand) {
        val hasJfrConfiguration = command.jfrConfigurationFile != null
        val hasJfrRecording = command.jfrRecordingFile != null
        require(hasJfrConfiguration == hasJfrRecording) {
            "jfrConfigurationFile and jfrRecordingFile must be supplied together"
        }
        command.jfrConfigurationFile?.let { requireNonBlank("jfrConfigurationFile", it) }
        command.jfrRecordingFile?.let { requireNonBlank("jfrRecordingFile", it) }
        when (command.metricPass) {
            MetricPass.RETAINED -> {
                require(command.mode == RunMode.RETAINED) {
                    "RETAINED metric pass requires RETAINED mode"
                }
                require(command.warmupIterations == 0 && command.measurementIterations == 0) {
                    "RETAINED metric pass requires zero warmup and measurement iterations"
                }
                require((command.retainedExecutionCount ?: 0) > 0) {
                    "RETAINED metric pass requires a positive retainedExecutionCount"
                }
                require(!hasJfrConfiguration) { "RETAINED metric pass cannot configure JFR" }
            }
            MetricPass.ALLOCATION -> {
                require(command.mode == RunMode.COLD) {
                    "worker ALLOCATION metric pass requires COLD mode"
                }
                require(hasJfrConfiguration) {
                    "cold ALLOCATION metric pass requires JFR configuration and recording files"
                }
                require(command.retainedExecutionCount == null) {
                    "ALLOCATION metric pass cannot set retainedExecutionCount"
                }
            }
            MetricPass.LATENCY,
            MetricPass.PEAK_RSS,
            -> {
                require(command.mode != RunMode.RETAINED) {
                    "${command.metricPass} metric pass cannot use RETAINED mode"
                }
                require(!hasJfrConfiguration) {
                    "${command.metricPass} metric pass cannot configure JFR"
                }
                require(command.retainedExecutionCount == null) {
                    "${command.metricPass} metric pass cannot set retainedExecutionCount"
                }
            }
        }
    }

    private fun validateRetainedCheckpoint(checkpoint: RetainedCheckpoint) {
        require(checkpoint.executionCount > 0) {
            "retained checkpoint executionCount must be positive"
        }
        require(checkpoint.usedHeapBytes >= 0) {
            "retained checkpoint usedHeapBytes must not be negative"
        }
        require(checkpoint.completedGcCycles >= 2) {
            "retained checkpoint completedGcCycles must be at least two"
        }
        checkpoint.weakReferences.forEachIndexed { index, outcome ->
            requireNonBlank("retained checkpoint weakReferences[$index].type", outcome.type)
            require(outcome.created >= 0) {
                "retained checkpoint weakReferences[$index].created must not be negative"
            }
            require(outcome.cleared in 0..outcome.created) {
                "retained checkpoint weakReferences[$index].cleared must be between zero and created"
            }
        }
    }

    private fun validateSample(sample: TargetSample) {
        require(sample.iteration >= 0) { "sample iteration must not be negative" }
        require(sample.latencyNanos >= 0) { "sample latencyNanos must not be negative" }
        validateDigest(sample.digest)
    }

    private fun validateDigest(digest: ExecutionDigest) {
        require(digest.executedSteps >= 0) { "digest executedSteps must not be negative" }
        require(digest.failureCount >= 0) { "digest failureCount must not be negative" }
    }

    private fun requireNonBlank(name: String, value: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
    }
}

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
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import com.salesforce.revoman.benchmark.driver.model.TargetSample
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.VerifiedArtifactStamp
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

  inline fun <reified T : Any> read(path: Path): T = read(path, T::class.java)

  inline fun <reified T : Any> write(path: Path, value: T): Unit = write(path, value, T::class.java)

  fun validateSchema(path: Path, schemaResource: String) {
    val schemaStream = requireNotNull(BenchmarkJson::class.java.getResourceAsStream(schemaResource)) {
      "Schema resource does not exist: $schemaResource"
    }
    val messages = schemaStream.use {
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(it)
        .validate(Files.readString(path, UTF_8), InputFormat.JSON)
    }

    require(messages.isEmpty()) { "JSON at $path does not satisfy $schemaResource: $messages" }
  }

  @PublishedApi
  internal fun <T : Any> read(path: Path, type: Class<T>): T =
    requireNotNull(adapter(type).fromJson(Files.readString(path, UTF_8))) { "JSON at $path is null" }
      .also(::validate)

  @PublishedApi
  internal fun <T : Any> write(path: Path, value: T, type: Class<T>) {
    validate(value)
    val encoded = moshi.adapter(Any::class.java).toJson(canonicalize(adapter(type).toJsonValue(value)))
    val parent = requireNotNull(path.parent) { "Output path must have a parent: $path" }
    Files.createDirectories(parent)
    val temporaryFile = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")

    try {
      Files.writeString(temporaryFile, encoded, UTF_8)
      moveAtomically(temporaryFile, path)
    } finally {
      Files.deleteIfExists(temporaryFile)
    }
  }

  private fun <T : Any> adapter(type: Class<T>): JsonAdapter<T> = moshi.adapter(type).failOnUnknown()

  private fun moveAtomically(source: Path, target: Path): Unit {
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

  private fun validate(value: Any): Unit =
    when (value) {
      is TargetForkCommand -> validateCommand(value)
      is TargetForkResult -> validateResult(value)
      else -> Unit
    }

  private fun validateCommand(command: TargetForkCommand) {
    require(command.protocolVersion == PROTOCOL_VERSION) { "Unsupported protocol version: ${command.protocolVersion}" }
    validateVerification(command.verification)
    requireNonBlank("adapterId", command.adapterId)
    validateWorkload(command.workload)
    require(command.warmupIterations >= 0) { "warmupIterations must not be negative" }
    require(command.measurementIterations >= 0) { "measurementIterations must not be negative" }
    requireNonBlank("resultFile", command.resultFile)
  }

  private fun validateVerification(verification: TargetVerificationToken) {
    requireNonBlank("targetManifest", verification.targetManifest)
    require(verification.targetManifestSha256.matches(sha256Pattern)) { "targetManifestSha256 must be a 64-character SHA-256 hash" }
    require(verification.targetClasspathSha256.matches(sha256Pattern)) { "targetClasspathSha256 must be a 64-character SHA-256 hash" }
    val logicalIds = verification.artifactStamps.map(VerifiedArtifactStamp::logicalId)
    require(logicalIds == logicalIds.sorted() && logicalIds.distinct().size == logicalIds.size) {
      "artifactStamps must follow manifest logical ID order"
    }
    verification.artifactStamps.forEach(::validateArtifactStamp)
  }

  private fun validateArtifactStamp(stamp: VerifiedArtifactStamp) {
    requireNonBlank("artifact logicalId", stamp.logicalId)
    requireNonBlank("artifact executionPath", stamp.executionPath)
    require(stamp.sizeBytes >= 0) { "artifact sizeBytes must not be negative" }
    require(stamp.lastModifiedMillis >= 0) { "artifact lastModifiedMillis must not be negative" }
  }

  private fun validateWorkload(workload: WorkloadRequest) {
    requireNonBlank("workload id", workload.id)
    require(workload.contractVersion > 0) { "workload contractVersion must be positive" }
    requireNonBlank("fixtureRoot", workload.fixtureRoot)
    requireNonBlank("baseUrl", workload.baseUrl)
    require(workload.parameters.keys.none(String::isBlank)) { "workload parameter names must not be blank" }
  }

  private fun validateResult(result: TargetForkResult) {
    require(result.protocolVersion == PROTOCOL_VERSION) { "Unsupported protocol version: ${result.protocolVersion}" }
    require(result.warmupIterations >= 0) { "warmupIterations must not be negative" }
    require(result.measurementIterations >= 0) { "measurementIterations must not be negative" }
    require(result.measurementIterations == result.samples.size) {
      "measurementIterations must match samples.size"
    }
    result.samples.forEach(::validateSample)
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

  private fun requireNonBlank(name: String, value: String): Unit =
    require(value.isNotBlank()) { "$name must not be blank" }
}

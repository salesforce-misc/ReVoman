/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.jmh.ADAPTER_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.FIXTURE_ROOT_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.TARGET_MANIFEST_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.TARGET_TOKEN_PROPERTY
import com.salesforce.revoman.benchmark.driver.jmh.TARGET_TOKEN_SHA256_PROPERTY
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.TargetAdapterRegistry
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.VerifiedTargetManifest
import java.nio.file.Files
import java.nio.file.Path

internal class PreparedTargetState : AutoCloseable {
    private lateinit var runtime: TargetRuntime
    private lateinit var workload: PreparedWorkload
    private lateinit var operations: Map<String, TargetOperation>

    fun prepare(operationIds: List<String>, parameters: Map<String, String> = emptyMap()) {
        check(!this::runtime.isInitialized) { "JMH target state is already prepared" }
        require(operationIds.isNotEmpty()) { "operationIds must not be empty" }
        val tokenPath = requiredCanonicalFile(TARGET_TOKEN_PROPERTY)
        val expectedTokenHash = requiredProperty(TARGET_TOKEN_SHA256_PROPERTY)
        val actualTokenHash = ContentHasher.sha256(tokenPath)
        require(actualTokenHash == expectedTokenHash) {
            "JMH target token SHA-256 mismatch: expected=$expectedTokenHash, actual=$actualTokenHash"
        }
        val verification = BenchmarkJson.read<TargetVerificationToken>(tokenPath)
        val suppliedManifest = requiredCanonicalFile(TARGET_MANIFEST_PROPERTY)
        require(verification.targetManifest == suppliedManifest.toString()) {
            "JMH target manifest differs from verified token"
        }
        val request =
            WorkloadRequest(
                id = "jmh.component-operations.v1",
                contractVersion = 1,
                fixtureRoot = requiredCanonicalDirectory(FIXTURE_ROOT_PROPERTY).toString(),
                baseUrl = "http://127.0.0.1",
                parameters = parameters,
            )
        val command =
            TargetForkCommand(
                verification = verification,
                adapterId = requiredProperty(ADAPTER_PROPERTY),
                mode = RunMode.WARM,
                metricPass = MetricPass.LATENCY,
                workload = request,
                expectedDigest = null,
                warmupIterations = 0,
                measurementIterations = 1,
                resultFile = tokenPath.resolveSibling("unused-target-result.json").toString(),
            )
        val verified = VerifiedTargetManifest.fromWorkerCommand(command)
        runtime = TargetRuntime.open(verified)
        try {
            workload = TargetAdapterRegistry.require(command.adapterId).prepare(runtime, request)
            operations = operationIds.associateWith(workload::operation)
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }

    fun operation(id: String): TargetOperation {
        check(this::operations.isInitialized) { "JMH target state is not prepared" }
        return requireNotNull(operations[id]) { "JMH target operation was not prepared: $id" }
    }

    override fun close() {
        if (!this::runtime.isInitialized) return
        var failure: Throwable? = null
        if (this::workload.isInitialized) {
            try {
                workload.close()
            } catch (closeFailure: Throwable) {
                failure = closeFailure
            }
        }
        try {
            runtime.close()
        } catch (closeFailure: Throwable) {
            failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
        }
        failure?.let { throw it }
    }
}

private fun requiredCanonicalFile(name: String): Path =
    Path.of(requiredProperty(name)).toAbsolutePath().normalize().also { path ->
        require(Files.isRegularFile(path)) { "$name must identify a regular file: $path" }
        require(path.toRealPath() == path) { "$name must be canonical: $path" }
    }

private fun requiredCanonicalDirectory(name: String): Path =
    Path.of(requiredProperty(name)).toAbsolutePath().normalize().also { path ->
        require(Files.isDirectory(path)) { "$name must identify a directory: $path" }
        require(path.toRealPath() == path) { "$name must be canonical: $path" }
    }

private fun requiredProperty(name: String): String =
    requireNotNull(System.getProperty(name)) { "Missing required system property: $name" }
        .also { require(it.isNotBlank()) { "System property $name must not be blank" } }

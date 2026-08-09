/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TargetAdapterContractTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `registry requires exact baseline or major adapter id`() {
        assertThat(TargetAdapterRegistry.require("baseline-83f3cd70").descriptor)
            .isEqualTo(AdapterDescriptor(id = "baseline-83f3cd70", surfaceVersion = 1))
        assertThat(TargetAdapterRegistry.require("major-v1").descriptor)
            .isEqualTo(AdapterDescriptor(id = "major-v1", surfaceVersion = 1))

        val caseFailure = assertThrows<IllegalStateException> { TargetAdapterRegistry.require("MAJOR-V1") }
        val prefixFailure =
            assertThrows<IllegalStateException> { TargetAdapterRegistry.require("baseline") }

        assertThat(caseFailure).hasMessageThat().isEqualTo("Unknown target adapter: MAJOR-V1")
        assertThat(prefixFailure).hasMessageThat().isEqualTo("Unknown target adapter: baseline")
    }

    @Test
    fun `baseline and major fake surfaces produce the same scalar digest`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val baselineDigest = execute(builder, builder.baselineJar(), "baseline-83f3cd70")
        val majorDigest = execute(builder, builder.majorJar(), "major-v1")

        assertThat(baselineDigest)
            .isEqualTo(ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0))
        assertThat(majorDigest).isEqualTo(baselineDigest)
    }

    @Test
    fun `adapter never returns a target class across the seam`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.baselineJar()
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))

        TargetRuntime.open(verified).use { runtime ->
            val targetLoader = runtime.loadClass("com.salesforce.revoman.ReVoman").classLoader
            TargetAdapterRegistry.require("baseline-83f3cd70").prepare(runtime, request()).use { prepared ->
                val digest = prepared.execute()
                val operation = prepared.operation("lifecycle.no-script-one-step.v1")

                assertThat(operation.invoke()).isEqualTo(31)
                assertThat(prepared::class.java.classLoader).isNotSameInstanceAs(targetLoader)
                assertThat(operation::class.java.classLoader).isNotSameInstanceAs(targetLoader)
                assertThat(digest::class.java.classLoader).isNotSameInstanceAs(targetLoader)
                digest::class.java.declaredFields.forEach { field ->
                    assertThat(field.type.classLoader).isNotSameInstanceAs(targetLoader)
                }
            }
        }
    }

    @Test
    fun `major adapter rejects unsupported component operation ids explicitly`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.majorJar()
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))

        TargetRuntime.open(verified).use { runtime ->
            TargetAdapterRegistry.require("major-v1").prepare(runtime, request()).use { prepared ->
                val failure =
                    assertThrows<UnsupportedOperationException> {
                        prepared.operation("regex.mixed-strings")
                    }

                assertThat(failure)
                    .hasMessageThat()
                    .isEqualTo("major-v1 does not support target operation: regex.mixed-strings")
            }
        }
    }

    @Test
    fun `baseline component surface exposes cached scalar operations and closes resources`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.componentJar()
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))
        val componentRoot = Files.createDirectory(temporaryDirectory.resolve("components"))
        Files.writeString(componentRoot.resolve("composite-response.json"), "{\"ok\":true}")
        Files.writeString(componentRoot.resolve("postman-test-script.js"), "pm.environment.set('id', 42);")
        Files.writeString(
            componentRoot.resolve("regex-inputs.json"),
            """{"environment":{"policyId":"abc","value":"{{policyId}}"},"mixedStrings":["{{policyId}}","x"]}""",
        )
        val request =
            WorkloadRequest(
                id = "jmh.component-operations.v1",
                contractVersion = 1,
                fixtureRoot = componentRoot.toRealPath().toString(),
                baseUrl = "http://127.0.0.1:8080",
                parameters = mapOf("steps" to "3"),
            )

        TargetRuntime.open(verified).use { runtime ->
            val prepared = TargetAdapterRegistry.require("baseline-83f3cd70").prepare(runtime, request)
            val operationIds =
                listOf(
                    "smoke.sum-range",
                    "regex.mixed-strings",
                    "regex.large-environment",
                    "marshalling.composite-from-json",
                    "marshalling.composite-to-json",
                    "sandbox.postman-test-script",
                    "environment.accumulate-and-snapshot",
                    "graal.open-engine",
                )

            val firstPass = operationIds.associateWith { prepared.operation(it).invoke() }
            val secondPass = operationIds.associateWith { prepared.operation(it).invoke() }

            assertThat(firstPass.keys).containsExactlyElementsIn(operationIds)
            assertThat(firstPass).isEqualTo(secondPass)
            assertThat(firstPass.values).doesNotContain(0L)
            assertThat(firstPass["regex.mixed-strings"]).isEqualTo(4)
            assertThat(firstPass["regex.large-environment"]).isEqualTo(2)
            prepared.close()
            val sandbox = runtime.loadClass("com.salesforce.revoman.internal.postman.sandbox.PmSandbox")
            val legacyContext = runtime.loadClass("org.graalvm.polyglot.Context")
            assertThat(sandbox.getField("closedCount").getInt(null)).isEqualTo(1)
            assertThat(legacyContext.getField("closed").getInt(null)).isEqualTo(1)
            assertThrows<IllegalStateException> { prepared.operation("smoke.sum-range") }
        }
    }

    private fun execute(
        builder: FakeTargetJarBuilder,
        jar: Path,
        adapterId: String,
    ): ExecutionDigest {
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))
        return TargetRuntime.open(verified).use { runtime ->
            TargetAdapterRegistry.require(adapterId).prepare(runtime, request()).use { it.execute() }
        }
    }

    private fun request(): WorkloadRequest {
        Files.writeString(temporaryDirectory.resolve("collection.postman_collection.json"), "{}")
        return WorkloadRequest(
            id = "lifecycle.no-script-one-step.v1",
            contractVersion = 1,
            fixtureRoot = temporaryDirectory.toRealPath().toString(),
            baseUrl = "http://127.0.0.1:8080",
        )
    }
}

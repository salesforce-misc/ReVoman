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
import com.salesforce.revoman.benchmark.driver.target.major.MajorV1BindingContract
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

        val caseFailure =
            assertThrows<IllegalStateException> { TargetAdapterRegistry.require("MAJOR-V1") }
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
            TargetAdapterRegistry.require("baseline-83f3cd70").prepare(runtime, request()).use {
                prepared ->
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
    fun `major binding descriptor selects the exact target method type`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.majorJar()))

        TargetRuntime.open(verified).use { runtime ->
            val target = ReflectiveTarget(runtime)

            val configured = runtime.withTargetContext {
                MajorV1BindingContract.configure.bind(target).call0().invoke()
            }
            val driftedDescriptor =
                MajorV1BindingContract.configure.copy(descriptor = "()Ljava/lang/Object;")
            val failure = assertThrows<IllegalArgumentException> { driftedDescriptor.bind(target) }

            assertThat(configured!!::class.java.name)
                .isEqualTo("com.salesforce.revoman.input.config.Kick\$Builder")
            assertThat(failure).hasMessageThat().contains("descriptor")
        }
    }

    @Test
    fun `major binding rejects static and virtual invocation drift`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.majorJar()))

        TargetRuntime.open(verified).use { runtime ->
            val target = ReflectiveTarget(runtime)
            val staticAsVirtual =
                MajorV1BindingContract.configure.copy(
                    invocation = MajorV1BindingContract.Invocation.VIRTUAL
                )
            val virtualAsStatic =
                MajorV1BindingContract.executedStepCount.copy(
                    invocation = MajorV1BindingContract.Invocation.STATIC
                )

            val staticFailure =
                assertThrows<IllegalArgumentException> { staticAsVirtual.bind(target) }
            val virtualFailure =
                assertThrows<IllegalArgumentException> { virtualAsStatic.bind(target) }

            assertThat(staticFailure).hasMessageThat().contains("expected VIRTUAL")
            assertThat(virtualFailure).hasMessageThat().contains("expected STATIC")
        }
    }

    @Test
    fun `prepared target methods cache arity specific fast invokers`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.majorJar()))

        TargetRuntime.open(verified).use { runtime ->
            val target = ReflectiveTarget(runtime)
            val firstBinding = MajorV1BindingContract.configure.bind(target)
            val secondBinding = MajorV1BindingContract.configure.bind(target)
            val firstInvoker = firstBinding.call0()
            val secondInvoker = firstBinding.call0()

            val configured = runtime.withTargetContext { firstInvoker.invoke() }

            assertThat(secondBinding).isSameInstanceAs(firstBinding)
            assertThat(secondInvoker).isSameInstanceAs(firstInvoker)
            assertThat(configured!!::class.java.name)
                .isEqualTo("com.salesforce.revoman.input.config.Kick\$Builder")
            assertThrows<IllegalArgumentException> { firstBinding.call1() }
        }
    }

    @Test
    fun `baseline component surface exposes cached scalar operations and closes resources`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.componentJar()
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))
        val componentRoot = Files.createDirectory(temporaryDirectory.resolve("components"))
        Files.writeString(componentRoot.resolve("composite-response.json"), "{\"ok\":true}")
        Files.writeString(
            componentRoot.resolve("postman-test-script.js"),
            "pm.environment.set('id', 42);",
        )
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
            val prepared =
                TargetAdapterRegistry.require("baseline-83f3cd70").prepare(runtime, request)
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
            operationIds.forEach { id ->
                assertThat(prepared.operation(id)).isSameInstanceAs(prepared.operation(id))
            }
            assertThat(firstPass.values).doesNotContain(0L)
            assertThat(firstPass["smoke.sum-range"]).isEqualTo(5_050)
            assertThat(firstPass["regex.mixed-strings"]).isEqualTo(4)
            assertThat(firstPass["regex.large-environment"]).isEqualTo(2)
            prepared.close()
            val sandbox =
                runtime.loadClass("com.salesforce.revoman.internal.postman.sandbox.PmSandbox")
            val legacyContext = runtime.loadClass("org.graalvm.polyglot.Context")
            assertThat(sandbox.getField("closedCount").getInt(null)).isEqualTo(1)
            assertThat(legacyContext.getField("closed").getInt(null)).isEqualTo(1)
            assertThrows<IllegalStateException> { prepared.operation("smoke.sum-range") }
        }
    }

    @Test
    fun `baseline component preparation supplies the mutable environment required by target`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.componentJar()))
        val componentRoot = componentFixtureRoot("mutable-environment-components")

        TargetRuntime.open(verified).use { runtime ->
            TargetAdapterRegistry.require("baseline-83f3cd70")
                .prepare(runtime, componentRequest(componentRoot))
                .use { prepared ->
                    assertThat(prepared.operation("regex.large-environment").invoke()).isEqualTo(1)
                }
        }
    }

    @Test
    fun `component close attempts every resource and suppresses later failures`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.componentJar()))
        val componentRoot = componentFixtureRoot("close-failure-components")

        TargetRuntime.open(verified).use { runtime ->
            val sandbox =
                runtime.loadClass("com.salesforce.revoman.internal.postman.sandbox.PmSandbox")
            val legacyContext = runtime.loadClass("org.graalvm.polyglot.Context")
            sandbox.getField("failClose").setBoolean(null, true)
            legacyContext.getField("failClose").setBoolean(null, true)
            val prepared =
                TargetAdapterRegistry.require("baseline-83f3cd70")
                    .prepare(runtime, componentRequest(componentRoot))

            val failure = assertThrows<IllegalStateException> { prepared.close() }

            assertThat(failure).hasMessageThat().isEqualTo("sandbox close failed")
            assertThat(failure.suppressed.asList()).hasSize(1)
            assertThat(failure.suppressed.single())
                .hasMessageThat()
                .isEqualTo("context close failed")
            assertThat(sandbox.getField("closedCount").getInt(null)).isEqualTo(1)
            assertThat(legacyContext.getField("closed").getInt(null)).isEqualTo(1)
            assertThrows<IllegalStateException> { prepared.operation("smoke.sum-range") }
            prepared.close()
            assertThat(sandbox.getField("closedCount").getInt(null)).isEqualTo(1)
        }
    }

    @Test
    fun `component preparation rolls back resources acquired before a late binding failure`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.componentJar(failPreparationAfterResources = true)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))
        val componentRoot = componentFixtureRoot("rollback-components")

        TargetRuntime.open(verified).use { runtime ->
            val sandbox =
                runtime.loadClass("com.salesforce.revoman.internal.postman.sandbox.PmSandbox")
            val legacyContext = runtime.loadClass("org.graalvm.polyglot.Context")
            sandbox.getField("failClose").setBoolean(null, true)
            legacyContext.getField("failClose").setBoolean(null, true)
            val failure =
                assertThrows<Throwable> {
                    TargetAdapterRegistry.require("baseline-83f3cd70")
                        .prepare(runtime, componentRequest(componentRoot))
                }

            assertThat(failure).hasMessageThat().contains("create")
            assertThat(failure.suppressed.asList()).hasSize(1)
            val rollbackFailure = failure.suppressed.single()
            assertThat(rollbackFailure).hasMessageThat().isEqualTo("sandbox close failed")
            assertThat(rollbackFailure.suppressed.asList()).hasSize(1)
            assertThat(rollbackFailure.suppressed.single())
                .hasMessageThat()
                .isEqualTo("context close failed")
            assertThat(sandbox.getField("closedCount").getInt(null)).isEqualTo(1)
            assertThat(legacyContext.getField("closed").getInt(null)).isEqualTo(1)
        }
    }

    @Test
    fun `graal operation attempts close when target close fails`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.componentJar()))
        val componentRoot = componentFixtureRoot("engine-failure-components")

        TargetRuntime.open(verified).use { runtime ->
            val engine = runtime.loadClass("org.graalvm.polyglot.Engine")
            val prepared =
                TargetAdapterRegistry.require("baseline-83f3cd70")
                    .prepare(runtime, componentRequest(componentRoot))
            engine.getField("failClose").setBoolean(null, true)

            val failure =
                assertThrows<IllegalStateException> {
                    prepared.operation("graal.open-engine").invoke()
                }

            assertThat(failure).hasMessageThat().isEqualTo("engine close failed")
            assertThat(engine.getField("opened").getInt(null)).isEqualTo(1)
            assertThat(engine.getField("closed").getInt(null)).isEqualTo(1)
            prepared.close()
        }
    }

    private fun execute(
        builder: FakeTargetJarBuilder,
        jar: Path,
        adapterId: String,
    ): ExecutionDigest {
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))
        return TargetRuntime.open(verified).use { runtime ->
            TargetAdapterRegistry.require(adapterId).prepare(runtime, request()).use {
                it.execute()
            }
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

    private fun componentFixtureRoot(name: String): Path =
        Files.createDirectory(temporaryDirectory.resolve(name)).also { root ->
            Files.writeString(root.resolve("composite-response.json"), "{\"ok\":true}")
            Files.writeString(
                root.resolve("postman-test-script.js"),
                "pm.environment.set('id', 42);",
            )
            Files.writeString(
                root.resolve("regex-inputs.json"),
                """{"environment":{"policyId":"abc"},"mixedStrings":["{{policyId}}"]}""",
            )
        }

    private fun componentRequest(root: Path): WorkloadRequest =
        WorkloadRequest(
            id = "jmh.component-operations.v1",
            contractVersion = 1,
            fixtureRoot = root.toRealPath().toString(),
            baseUrl = "http://127.0.0.1:8080",
            parameters = mapOf("steps" to "3"),
        )
}

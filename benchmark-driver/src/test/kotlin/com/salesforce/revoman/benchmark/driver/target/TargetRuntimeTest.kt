/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TargetRuntimeTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `platform parent isolates target classes and restores context classloader`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.runtimeJar()))
        val previous = Thread.currentThread().contextClassLoader

        TargetRuntime.open(verified).use { runtime ->
            val targetLoader = runtime.loadClass("fake.target.First").classLoader

            assertThat(targetLoader.parent).isSameInstanceAs(ClassLoader.getPlatformClassLoader())
            assertThrows<ClassNotFoundException> { targetLoader.loadClass(javaClass.name) }
            assertThat(runtime.withTargetContext { Thread.currentThread().contextClassLoader })
                .isSameInstanceAs(targetLoader)
            assertThrows<DeliberateFailure> {
                runtime.withTargetContext {
                    assertThat(Thread.currentThread().contextClassLoader).isSameInstanceAs(targetLoader)
                    throw DeliberateFailure()
                }
            }
            assertThat(Thread.currentThread().contextClassLoader).isSameInstanceAs(previous)
        }
    }

    @Test
    fun `closing target runtime closes its URL classloader`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.runtimeJar()))
        val runtime = TargetRuntime.open(verified)
        runtime.loadClass("fake.target.First")

        runtime.close()

        assertThrows<ClassNotFoundException> { runtime.loadClass("fake.target.Second") }
    }

    @Test
    fun `closed target classloader becomes collectable`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(builder.runtimeJar()))

        val loaderReference = closeAndRelease(verified)

        repeat(100) {
            if (loaderReference.get() == null) return@repeat
            System.gc()
            Thread.sleep(10)
        }
        assertThat(loaderReference.get()).isNull()
    }

    @Test
    fun `target preflight rejects a classpath file whose bytes no longer match the manifest`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.runtimeJar()
        val manifest = builder.manifestFor(jar)
        mutateBytesPreservingCheapStamp(jar)

        val failure = assertThrows<IllegalArgumentException> { VerifiedTargetManifest.preflight(manifest) }

        assertThat(failure).hasMessageThat().contains("SHA-256")
    }

    @Test
    fun `worker reconstruction and timed open perform no content hashing after preflight`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.runtimeJar()
        val manifest = builder.manifestFor(jar)
        val controllerVerified = VerifiedTargetManifest.preflight(manifest)
        val command = commandFor(manifest, controllerVerified)
        mutateBytesPreservingCheapStamp(jar)

        val workerVerified = VerifiedTargetManifest.fromWorkerCommand(command)

        TargetRuntime.open(workerVerified).close()
        assertThat(workerVerified.manifestSha256).isEqualTo(controllerVerified.manifestSha256)
        assertThat(workerVerified.classpathSha256).isEqualTo(controllerVerified.classpathSha256)
    }

    @Test
    fun `worker reconstruction requires exact controller identities`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val manifest = builder.manifestFor(builder.runtimeJar())
        val verified = VerifiedTargetManifest.preflight(manifest)
        val command = commandFor(manifest, verified)

        val manifestFailure =
            assertThrows<IllegalArgumentException> {
                VerifiedTargetManifest.fromWorkerCommand(
                    command.copy(
                        verification =
                            command.verification.copy(targetManifestSha256 = "0".repeat(64))
                    )
                )
            }
        val classpathFailure =
            assertThrows<IllegalArgumentException> {
                VerifiedTargetManifest.fromWorkerCommand(
                    command.copy(
                        verification =
                            command.verification.copy(targetClasspathSha256 = "0".repeat(64))
                    )
                )
            }

        assertThat(manifestFailure).hasMessageThat().contains("manifest SHA-256")
        assertThat(classpathFailure).hasMessageThat().contains("classpath SHA-256")
    }

    @Test
    fun `worker reconstruction requires artifact stamp IDs in manifest order`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val manifest = builder.manifestFor(builder.runtimeJar())
        val verified = VerifiedTargetManifest.preflight(manifest)
        val command = commandFor(manifest, verified)
        val stamp = command.verification.artifactStamps.single()

        val substituted = assertThrows<IllegalArgumentException> {
            VerifiedTargetManifest.fromWorkerCommand(
                command.copy(
                    verification =
                        command.verification.copy(
                            artifactStamps = listOf(stamp.copy(logicalId = "substituted.jar"))
                        )
                )
            )
        }
        val duplicated = assertThrows<IllegalArgumentException> {
            VerifiedTargetManifest.fromWorkerCommand(
                command.copy(
                    verification = command.verification.copy(artifactStamps = listOf(stamp, stamp))
                )
            )
        }

        assertThat(substituted).hasMessageThat().contains("artifact stamp IDs/order")
        assertThat(duplicated).hasMessageThat().contains("artifact stamp IDs/order")
    }

    @Test
    fun `preflight compares the expected target with its one verified manifest snapshot`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val manifestPath = builder.manifestFor(builder.runtimeJar())
        val expected = com.salesforce.revoman.benchmark.driver.json.BenchmarkJson.read<TargetManifest>(manifestPath)
        com.salesforce.revoman.benchmark.driver.json.BenchmarkJson.write(
            manifestPath,
            expected.copy(targetId = "same-classpath-replacement"),
        )

        val failure = assertThrows<IllegalArgumentException> {
            VerifiedTargetManifest.preflight(manifestPath, expected)
        }

        assertThat(failure).hasMessageThat().contains("expected target")
    }

    @Test
    fun `postflight rejects a same-classpath manifest replacement`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val manifestPath = builder.manifestFor(builder.runtimeJar())
        val verified = VerifiedTargetManifest.preflight(manifestPath)
        val replacement = verified.manifest.copy(targetId = "same-classpath-replacement")
        com.salesforce.revoman.benchmark.driver.json.BenchmarkJson.write(manifestPath, replacement)

        val failure = assertThrows<IllegalStateException> { verified.postflight() }

        assertThat(failure).hasMessageThat().contains("manifest SHA-256 changed")
    }

    @Test
    fun `worker rejects a same-classpath manifest replacement after controller preflight`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val manifestPath = builder.manifestFor(builder.runtimeJar())
        val verified = VerifiedTargetManifest.preflight(manifestPath)
        val command = commandFor(manifestPath, verified)
        com.salesforce.revoman.benchmark.driver.json.BenchmarkJson.write(
            manifestPath,
            verified.manifest.copy(targetId = "same-classpath-replacement"),
        )

        val failure = assertThrows<IllegalArgumentException> {
            VerifiedTargetManifest.fromWorkerCommand(command)
        }

        assertThat(failure).hasMessageThat().contains("manifest SHA-256")
    }

    @Test
    fun `postflight byte change invalidates the whole campaign`() {
        val builder = FakeTargetJarBuilder(temporaryDirectory)
        val jar = builder.runtimeJar()
        val verified = VerifiedTargetManifest.preflight(builder.manifestFor(jar))
        mutateBytesPreservingCheapStamp(jar)

        val failure = assertThrows<IllegalStateException> { verified.postflight() }

        assertThat(failure).hasMessageThat().contains("campaign invalid")
        assertThat(failure).hasMessageThat().contains("target.jar")
    }

    private fun commandFor(
        manifestPath: Path,
        verified: VerifiedTargetManifest,
    ): TargetForkCommand =
        TargetForkCommand(
            verification =
                TargetVerificationToken(
                    targetManifest = manifestPath.toRealPath().toString(),
                    targetManifestSha256 = verified.manifestSha256,
                    targetClasspathSha256 = verified.classpathSha256,
                    artifactStamps = verified.artifactStamps,
                ),
            adapterId = "baseline-83f3cd70",
            mode = RunMode.WARM,
            metricPass = MetricPass.LATENCY,
            workload =
                WorkloadRequest(
                    id = "lifecycle.no-script-one-step.v1",
                    contractVersion = 1,
                    fixtureRoot = temporaryDirectory.toString(),
                    baseUrl = "http://127.0.0.1:1",
                ),
            expectedDigest = null,
            warmupIterations = 0,
            measurementIterations = 1,
            resultFile = temporaryDirectory.resolve("result.json").toString(),
        )

    private fun mutateBytesPreservingCheapStamp(path: Path) {
        val lastModified = Files.getLastModifiedTime(path)
        val bytes = Files.readAllBytes(path)
        val mutationIndex = bytes.indices.first { bytes[it].toInt() != 0 }
        bytes[mutationIndex] = (bytes[mutationIndex].toInt() xor 1).toByte()
        Files.write(path, bytes)
        Files.setLastModifiedTime(path, FileTime.fromMillis(lastModified.toMillis()))
    }

    private fun closeAndRelease(verified: VerifiedTargetManifest): WeakReference<ClassLoader> {
        val runtime = TargetRuntime.open(verified)
        val loader = runtime.loadClass("fake.target.First").classLoader
        return WeakReference(loader).also { runtime.close() }
    }

    private class DeliberateFailure : RuntimeException()
}

/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.fixture

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.GateId
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

class DeterministicHttpFixtureTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `lifecycle workload materializes as one exact verified fixture tree`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("exact"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))

        DeterministicHttpFixture.verifyFixture(manifest, fixtureRoot)

        assertThat(manifest.id).isEqualTo(WORKLOAD_ID)
        assertThat(manifest.contractVersion).isEqualTo(1)
        assertThat(manifest.files.map { it.executionPath })
            .containsExactly(COLLECTION, HANDLER)
            .inOrder()
        assertThat(manifest.files.map { it.sizeBytes }).containsExactly(431L, 258L).inOrder()
        assertThat(manifest.files.map { it.sha256 })
            .containsExactly(
                "baacf0d7e9067c41848edf172aad8508b612528133d6576707f47da534c0ea86",
                "12c15383ba5a0aa6aef1e32f409a86dc5168223ad18ccb17270506e625b105ef",
            )
            .inOrder()
        assertThat(manifest.files.map { ContentHasher.sha256(fixtureRoot.resolve(it.executionPath)) })
            .containsExactlyElementsIn(manifest.files.map { it.sha256 })
            .inOrder()
        assertThat(
                ContentHasher.treeSha256(
                    fixtureRoot,
                    manifest.files.map { fixtureRoot.resolve(it.executionPath) },
                )
        )
            .isEqualTo(manifest.fixtureTreeSha256)
        assertThat(manifest.fixtureTreeSha256)
            .isEqualTo("31af0229163ef1ed544189f9b1f1dbd9a80607ffd024a2e5bd09cddfae919c92")
        assertThat(manifest.operationIds).containsExactly(WORKLOAD_ID)
        assertThat(manifest.expectedDigest)
            .isEqualTo(ExecutionDigest(checksum = 31, executedSteps = 1, failureCount = 0))
        assertThat(manifest.requiredGatesByMode.getValue(RunMode.COLD))
            .containsExactly(
                GateId.COLD_MEDIAN,
                GateId.COLD_P95,
                GateId.COLD_ALLOCATION,
                GateId.COLD_PEAK_RSS,
            )
            .inOrder()
        assertThat(manifest.requiredGatesByMode.getValue(RunMode.WARM))
            .containsExactly(GateId.WARM_MEDIAN, GateId.WARM_P95, GateId.WARM_ALLOCATION)
            .inOrder()
        assertThat(manifest.requiredGatesByMode.getValue(RunMode.RETAINED))
            .containsExactly(GateId.RETAINED_SLOPE)
    }

    @Test
    fun `fixture verification rejects an extra file`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("extra"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))
        Files.writeString(fixtureRoot.resolve("unregistered.json"), "{}", UTF_8)

        val failure = assertThrows<IllegalArgumentException> {
            DeterministicHttpFixture.verifyFixture(manifest, fixtureRoot)
        }

        assertThat(failure).hasMessageThat().contains("fixture file set")
        assertThat(failure).hasMessageThat().contains("unregistered.json")
    }

    @Test
    fun `fixture verification rejects a missing file`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("missing"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))
        Files.delete(fixtureRoot.resolve(HANDLER))

        val failure = assertThrows<IllegalArgumentException> {
            DeterministicHttpFixture.verifyFixture(manifest, fixtureRoot)
        }

        assertThat(failure).hasMessageThat().contains("fixture file set")
        assertThat(failure).hasMessageThat().contains(HANDLER)
    }

    @Test
    fun `fixture verification rejects one changed response byte`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("changed"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))
        val handler = fixtureRoot.resolve(HANDLER)
        Files.write(handler, Files.readAllBytes(handler) + '\n'.code.toByte())

        val failure = assertThrows<IllegalArgumentException> {
            DeterministicHttpFixture.verifyFixture(manifest, fixtureRoot)
        }

        assertThat(failure).hasMessageThat().contains("SHA-256")
        assertThat(failure).hasMessageThat().contains(HANDLER)
    }

    @Test
    fun `lifecycle gates reject reordered or duplicate required gates`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("gates"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))
        val reordered =
            manifest.copy(
                requiredGatesByMode =
                    manifest.requiredGatesByMode +
                        (RunMode.COLD to
                            listOf(
                                GateId.COLD_P95,
                                GateId.COLD_MEDIAN,
                                GateId.COLD_ALLOCATION,
                                GateId.COLD_PEAK_RSS,
                            ))
            )
        val duplicated =
            manifest.copy(
                requiredGatesByMode =
                    manifest.requiredGatesByMode +
                        (RunMode.WARM to
                            listOf(
                                GateId.WARM_MEDIAN,
                                GateId.WARM_P95,
                                GateId.WARM_P95,
                                GateId.WARM_ALLOCATION,
                            ))
            )

        val reorderedFailure = assertThrows<IllegalArgumentException> { reordered.validate() }
        val duplicateFailure = assertThrows<IllegalArgumentException> { duplicated.validate() }

        assertThat(reorderedFailure).hasMessageThat().contains("requiredGatesByMode.COLD")
        assertThat(duplicateFailure).hasMessageThat().contains("requiredGatesByMode.WARM")
    }

    @Test
    fun `registered route binds to loopback and returns exact contract bytes`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("response"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))

        DeterministicHttpFixture.open(manifest).use { fixture ->
            fixture.resetExecution("response")
            val responses = List(10) { request(fixture.baseUrl, "/small") }

            assertThat(URI(fixture.baseUrl).host).isEqualTo("127.0.0.1")
            assertThat(fixture.localAddress.address.hostAddress).isEqualTo("127.0.0.1")
            responses.forEach { response ->
                assertThat(response.statusCode()).isEqualTo(200)
                assertThat(observableHeaders(response.headers().map()))
                    .containsExactlyEntriesIn(
                        mapOf(
                            "content-length" to listOf("11"),
                            "content-type" to listOf("application/json"),
                            "date" to listOf(TRANSPORT_DATE_SENTINEL),
                        )
                    )
                assertThat(response.body()).isEqualTo("{\"ok\":true}".toByteArray(UTF_8))
            }
            assertThat(fixture.requestCount("response")).isEqualTo(10)
        }
    }

    @Test
    fun `fixture test JVM launches with JDK server TCP no delay`() {
        assertThat(System.getProperty(JDK_HTTP_SERVER_NO_DELAY_PROPERTY)).ignoringCase().isEqualTo("true")
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun `fixture rejects disabled JDK server TCP no delay before binding`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("tcp-no-delay"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))
        val previous = System.setProperty(JDK_HTTP_SERVER_NO_DELAY_PROPERTY, "false")

        try {
            val failure = assertThrows<IllegalStateException> {
                DeterministicHttpFixture.open(manifest).use {
                    error("fixture accepted a disabled server transport option")
                }
            }

            assertThat(failure).hasMessageThat().contains(JDK_HTTP_SERVER_NO_DELAY_PROPERTY)
        } finally {
            if (previous == null) {
                System.clearProperty(JDK_HTTP_SERVER_NO_DELAY_PROPERTY)
            } else {
                System.setProperty(JDK_HTTP_SERVER_NO_DELAY_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `handler rejects duplicate or transport owned response headers`() {
        val body = HandlerBody(encoding = "UTF-8", text = "{\"ok\":true}")
        val duplicate =
            HandlerRoute(
                method = "GET",
                path = "/small",
                status = 200,
                headers =
                    linkedMapOf(
                        "Content-Type" to "application/json",
                        "content-type" to "application/problem+json",
                    ),
                body = body,
            )
        val duplicateFailure = assertThrows<IllegalArgumentException> {
            duplicate.validate("route")
        }
        val transportFailures =
            listOf("Connection", "Content-Length", "Date", "Transfer-Encoding").map { header ->
                assertThrows<IllegalArgumentException> {
                    HandlerRoute(
                            method = "GET",
                            path = "/small",
                            status = 200,
                            headers = mapOf(header to "reserved"),
                            body = body,
                        )
                        .validate("route")
                }
            }

        assertThat(duplicateFailure).hasMessageThat().contains("case-insensitively unique")
        transportFailures.forEach { failure ->
            assertThat(failure).hasMessageThat().contains("transport-owned")
        }
    }

    @Test
    fun `request counters reset independently for each execution id`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("counters"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))

        DeterministicHttpFixture.open(manifest).use { fixture ->
            fixture.resetExecution("first")
            request(fixture.baseUrl, "/small")
            request(fixture.baseUrl, "/small")
            fixture.resetExecution("second")
            request(fixture.baseUrl, "/small")

            assertThat(fixture.requestCount("first")).isEqualTo(2)
            assertThat(fixture.requestCount("second")).isEqualTo(1)

            fixture.resetExecution("first")

            assertThat(fixture.requestCount("first")).isEqualTo(0)
            assertThat(fixture.requestCount("second")).isEqualTo(1)
        }
    }

    @Test
    fun `unregistered route returns 500 and invalidates the fixture contract`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("unknown"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))

        val failure = assertThrows<IllegalStateException> {
            DeterministicHttpFixture.open(manifest).use { fixture ->
                fixture.resetExecution("unknown")
                val response = request(fixture.baseUrl, "/not-registered")

                assertThat(response.statusCode()).isEqualTo(500)
                assertThat(fixture.requestCount("unknown")).isEqualTo(1)
            }
        }

        assertThat(failure).hasMessageThat().contains("GET /not-registered")
    }

    @Test
    fun `unregistered method returns 500 and invalidates the fixture contract`() {
        val fixtureRoot = materializeFixture(temporaryDirectory.resolve("wrong-method"))
        val manifest = BenchmarkJson.read<WorkloadManifest>(fixtureRoot.resolve(MANIFEST))

        val failure = assertThrows<IllegalStateException> {
            DeterministicHttpFixture.open(manifest).use { fixture ->
                fixture.resetExecution("wrong-method")
                val response = request(fixture.baseUrl, "/small", "POST")

                assertThat(response.statusCode()).isEqualTo(500)
                assertThat(fixture.requestCount("wrong-method")).isEqualTo(1)
            }
        }

        assertThat(failure).hasMessageThat().contains("POST /small")
    }

    private fun request(baseUrl: String, path: String, method: String = "GET") =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            BodyHandlers.ofByteArray(),
        )

    private fun observableHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            when (name) {
                "date" ->
                    values.map { value ->
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        TRANSPORT_DATE_SENTINEL
                    }
                else -> values
            }
        }

    private fun materializeFixture(destination: Path): Path {
        Files.createDirectories(destination)
        listOf(MANIFEST, COLLECTION, HANDLER).forEach { fileName ->
            val resource = "$RESOURCE_ROOT/$fileName"
            requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing resource: $resource" }
                .use { input -> Files.copy(input, destination.resolve(fileName)) }
        }
        return destination.toRealPath()
    }

    private companion object {
        const val WORKLOAD_ID = "lifecycle.no-script-one-step.v1"
        const val RESOURCE_ROOT = "/workloads/v1/$WORKLOAD_ID"
        const val MANIFEST = "manifest.json"
        const val COLLECTION = "collection.postman_collection.json"
        const val HANDLER = "handler.json"
        const val TRANSPORT_DATE_SENTINEL = "<jdk-httpserver-rfc1123-date>"
    }
}

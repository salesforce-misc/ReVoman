/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.fixture

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.model.WorkloadManifest
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A byte-stable loopback HTTP fixture whose request counts are isolated by execution ID. */
class DeterministicHttpFixture private constructor(
    private val server: HttpServer,
    private val executor: ThreadPoolExecutor,
    private val routes: Map<RouteKey, PreparedRoute>,
) : AutoCloseable {
    private val activeExecution = AtomicReference<String>()
    private val requestCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val contractViolations = ConcurrentLinkedQueue<String>()
    private val closed = AtomicBoolean()

    /** The exact loopback address and ephemeral port selected for this fixture. */
    val localAddress: InetSocketAddress
        get() = server.address

    /** Base URL supplied to a materialized workload through its dynamic environment. */
    val baseUrl: String
        get() = "http://127.0.0.1:${localAddress.port}"

    /** Selects [executionId] for subsequent requests and resets only that execution's counter. */
    fun resetExecution(executionId: String) {
        check(!closed.get()) { "Deterministic HTTP fixture is closed" }
        require(executionId.isNotBlank()) { "executionId must not be blank" }
        requestCounters.computeIfAbsent(executionId) { AtomicInteger() }.set(0)
        activeExecution.set(executionId)
    }

    /** Returns the number of requests attributed to [executionId] since its most recent reset. */
    fun requestCount(executionId: String): Int = requestCounters[executionId]?.get() ?: 0

    /** Stops the server and fails if any request violated the declared route contract. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeExecution.set(null)
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
            "Deterministic HTTP fixture executor did not terminate"
        }
        check(contractViolations.isEmpty()) {
            "Deterministic HTTP fixture contract violated: ${contractViolations.joinToString()}"
        }
    }

    private fun handle(exchange: HttpExchange) {
        val executionId = activeExecution.get()
        executionId?.let { requestCounters.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
        val key = RouteKey(exchange.requestMethod, exchange.requestURI.rawPath)
        val route = routes[key]

        when {
            executionId == null -> reject(exchange, "${key.method} ${key.path} before execution reset")
            route == null -> reject(exchange, "${key.method} ${key.path}")
            else -> respond(exchange, route)
        }
    }

    private fun reject(exchange: HttpExchange, violation: String) {
        contractViolations.add(violation)
        exchange.sendResponseHeaders(CONTRACT_VIOLATION_STATUS, NO_RESPONSE_BODY)
        exchange.close()
    }

    private fun respond(exchange: HttpExchange, route: PreparedRoute) {
        route.headers.forEach(exchange.responseHeaders::set)
        exchange.sendResponseHeaders(route.status, route.body.size.toLong())
        exchange.responseBody.use { it.write(route.body) }
    }

    companion object {
        private const val HANDLER_FILE = "handler.json"
        private const val MANIFEST_FILE = "manifest.json"
        private const val CONTRACT_VIOLATION_STATUS = 500
        private const val NO_RESPONSE_BODY = -1L
        private val handlerAdapter =
            Moshi.Builder().build().adapter(HandlerContract::class.java).failOnUnknown()

        /** Starts one loopback server using the exact route contract bundled for [manifest]. */
        fun open(manifest: WorkloadManifest): DeterministicHttpFixture {
            manifest.validate()
            val routes =
                readHandlerContract(manifest).validatedRoutes().mapValues { (_, route) ->
                    route.prepare()
                }
            val executor = fixtureExecutor()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            val fixture = DeterministicHttpFixture(server, executor, routes)
            server.createContext("/", fixture::handle)
            server.start()
            return fixture
        }

        /** Verifies that [fixtureRoot] contains exactly the bytes declared by [manifest]. */
        fun verifyFixture(manifest: WorkloadManifest, fixtureRoot: Path) {
            manifest.validate()
            val root = fixtureRoot.toRealPath()
            val expectedPaths =
                manifest.files.mapIndexed { index, artifact ->
                    normalizedRelativePath("files[$index].executionPath", artifact.executionPath)
                }
            require(expectedPaths.distinct().size == expectedPaths.size) {
                "workload fixture execution paths must be unique"
            }
            val expectedNames = expectedPaths.map(::portablePath).sorted()
            val actualNames =
                Files.walk(root).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .map(root::relativize)
                        .map(::portablePath)
                        .filter { it != MANIFEST_FILE }
                        .sorted()
                        .toList()
                }
            require(actualNames == expectedNames) {
                "workload fixture file set differs: expected=$expectedNames, actual=$actualNames"
            }

            val files =
                manifest.files.zip(expectedPaths).map { (artifact, relativePath) ->
                    val file = root.resolve(relativePath).toRealPath()
                    require(file.startsWith(root)) {
                        "workload fixture path escapes root: ${artifact.executionPath}"
                    }
                    val actualSize = Files.size(file)
                    val actualHash = ContentHasher.sha256(file)
                    require(actualSize == artifact.sizeBytes && actualHash == artifact.sha256) {
                        "workload fixture size/SHA-256 differs for ${artifact.executionPath}: " +
                            "expectedSize=${artifact.sizeBytes}, actualSize=$actualSize, " +
                            "expectedSha256=${artifact.sha256}, actualSha256=$actualHash"
                    }
                    file
                }
            val actualTreeHash = ContentHasher.treeSha256(root, files)
            require(actualTreeHash == manifest.fixtureTreeSha256) {
                "workload fixture tree SHA-256 differs: " +
                    "expected=${manifest.fixtureTreeSha256}, actual=$actualTreeHash"
            }
        }

        private fun readHandlerContract(manifest: WorkloadManifest): HandlerContract {
            val artifact =
                requireNotNull(manifest.files.singleOrNull { it.executionPath == HANDLER_FILE }) {
                    "Workload ${manifest.id} must declare exactly one $HANDLER_FILE"
                }
            val resource = "/workloads/v1/${manifest.id}/$HANDLER_FILE"
            val bytes =
                requireNotNull(DeterministicHttpFixture::class.java.getResourceAsStream(resource)) {
                        "Missing deterministic handler resource: $resource"
                    }
                    .use { it.readAllBytes() }
            require(bytes.size.toLong() == artifact.sizeBytes && ContentHasher.sha256(bytes) == artifact.sha256) {
                "Bundled deterministic handler differs from workload manifest: $resource"
            }
            return requireNotNull(handlerAdapter.fromJson(bytes.toString(UTF_8))) {
                "Deterministic handler resource is JSON null: $resource"
            }
        }

        private fun fixtureExecutor(): ThreadPoolExecutor {
            val executor =
                ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    LinkedBlockingQueue(),
                ) { task ->
                    Thread.ofPlatform()
                        .name("revoman-deterministic-http-fixture")
                        .unstarted(task)
                        .apply { contextClassLoader = ClassLoader.getPlatformClassLoader() }
                }
            check(executor.prestartCoreThread()) {
                "Deterministic HTTP fixture executor did not start"
            }
            return executor
        }

        private fun normalizedRelativePath(name: String, value: String): Path {
            val path = Path.of(value)
            require(!path.isAbsolute && path.normalize() == path && path.nameCount > 0) {
                "$name must be normalized and relative: $value"
            }
            return path
        }

        private fun portablePath(path: Path): String = path.map(Path::toString).joinToString("/")
    }
}

@JsonClass(generateAdapter = true)
internal data class HandlerContract(val routes: List<HandlerRoute>) {
    fun validatedRoutes(): Map<RouteKey, HandlerRoute> {
        require(routes.isNotEmpty()) { "handler routes must not be empty" }
        routes.forEachIndexed { index, route -> route.validate("routes[$index]") }
        val routesByKey = routes.associateBy { RouteKey(it.method, it.path) }
        require(routesByKey.size == routes.size) { "handler method/path pairs must be unique" }
        return routesByKey
    }
}

@JsonClass(generateAdapter = true)
internal data class HandlerRoute(
    val method: String,
    val path: String,
    val status: Int,
    val headers: Map<String, String>,
    val body: HandlerBody,
) {
    fun validate(location: String) {
        require(method.matches(Regex("[A-Z]+"))) { "$location.method must be uppercase" }
        require(path.startsWith('/') && '?' !in path && '#' !in path) {
            "$location.path must be an absolute path without query or fragment"
        }
        require(status in 100..599) { "$location.status must be an HTTP status" }
        require(headers.isNotEmpty() && headers.all { it.key.isNotBlank() && it.value.isNotBlank() }) {
            "$location.headers must contain nonblank names and values"
        }
        val normalizedHeaderNames = headers.keys.map { it.lowercase(Locale.ROOT) }
        require(normalizedHeaderNames.distinct().size == normalizedHeaderNames.size) {
            "$location.headers must be case-insensitively unique"
        }
        require(normalizedHeaderNames.none(TRANSPORT_OWNED_HEADERS::contains)) {
            "$location.headers must not declare transport-owned headers: $TRANSPORT_OWNED_HEADERS"
        }
        body.validate("$location.body")
    }

    fun prepare(): PreparedRoute =
        PreparedRoute(status = status, headers = headers, body = body.text.toByteArray(UTF_8))
}

@JsonClass(generateAdapter = true)
internal data class HandlerBody(val encoding: String, val text: String) {
    fun validate(location: String) {
        require(encoding == UTF_8.name()) { "$location.encoding must be UTF-8" }
    }
}

internal data class RouteKey(val method: String, val path: String)

internal class PreparedRoute(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private val TRANSPORT_OWNED_HEADERS =
    setOf("connection", "content-length", "date", "transfer-encoding")

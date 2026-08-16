# Public Mock HTTP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Java- and Kotlin-friendly real-wire mock HTTP server in the existing ReVoman
artifact, then migrate every non-benchmark repository example from its private loopback server to
that public feature.

**Architecture:** Four public types in `com.salesforce.revoman.testing.http` form a narrow facade
over a JDK `HttpServer`, http4k's public `HttpExchangeHandler`, an owned virtual-thread-per-task
executor, an immutable request ledger, and deterministic close-time failure aggregation. Internal
files split request publication, startup/transport adaptation, and lifecycle ownership so each
concurrency boundary can be tested directly without widening the public API.

**Tech Stack:** Java 21 virtual threads and `HttpServer`, Kotlin, Java, http4k 6.57.2.0, Kotlin
Logging, Gradle 9.7, JUnit 5, Truth, MockK, Kotlin binary-compatibility-validator, Detekt, Spotless,
Qodana, and Antora.

## Global Constraints

- Work only in `/Users/gopala.akshintala/code-clones/work/revoman-root/.worktrees/mock-http-server`
  on branch `codex/mock-http-server`; do not edit, merge, or push the parallel main checkout.
- Treat
  `docs/superpowers/specs/2026-08-15-public-mock-http-server-design.md` as the authoritative
  behavioral contract.
- Keep the consumer baseline at Java 21. Do not migrate to Java 25 and do not add
  `kotlinx-coroutines` or any other dependency.
- Add exactly four public feature types: `MockHttpHandler`, `RecordedNameValue`,
  `RecordedHttpRequest`, and `MockHttpServer` in `com.salesforce.revoman.testing.http`.
- Keep both public class constructors non-user-constructible. `RecordedNameValue` is the only
  consumer-constructible feature value.
- Expose only `MockHttpServer.start(MockHttpHandler)`. Do not add an http4k `HttpHandler` overload,
  builder, bind option, executor option, TLS option, fixed port, count, filter, signature, reset,
  wait, assertion, expectation, or matching API.
- Bind only literal `127.0.0.1:0`; validate the actual bound address as IPv4 `127.0.0.1` with a
  positive port, and format `baseUrl` without a trailing slash.
- Use `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("revoman-mock-http-", 0).factory())`.
  Handler state may be accessed concurrently and must be documented as the caller's thread-safety
  responsibility.
- Materialize a request body once, finish constructing its immutable snapshot, publish snapshot and
  ordinal at one ledger linearization point, then pass a replayable body to the handler.
- Catch `Exception`, never `Throwable`, at the handler boundary. An explicit `4xx` or `5xx` response
  is normal; a thrown `Exception` or Java `null` response produces an empty `500`, remains recorded,
  and is deferred to `close()`.
- Do not copy or reimplement http4k's `HttpExchangeHandler`. Its response-write exceptions are not
  observable by ReVoman, remain outside the handler-failure ledger, and retain http4k's cleanup
  behavior.
- The first closer must call `stop(0)`, `shutdown()`, await five seconds, call `shutdownNow()` when
  needed, and await five more seconds. Restore interruption, attempt all cleanup, and aggregate
  failures only after cleanup. Concurrent losing closers wait and never repeat the winner's failure.
- State in public KDoc that interruption is cooperative: after the bounded ten-second shutdown,
  `close()` reports a failure, but Java cannot forcibly kill a handler that ignores interruption.
- Log start/stop at debug and handler/capture failures with method, path, and throwable. Never log
  request headers or bodies.
- Keep
  `api/cs2-baseline-revoman-root.api`, `api/cs2-baseline-revoman-root.jvm.tsv`,
  `api/cs2-migration-map.tsv`, and `Cs2JvmSurfaceAdditions.kt` byte-identical.
- Leave `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/fixture/DeterministicHttpFixture.kt`
  and its consumers unchanged.
- Preserve the Java `revoman-simple-demo` and Pokemon documentation tags. Do not rewrite dated
  historical specs, plans, reports, or landing evidence.
- Follow RED/GREEN/refactor. Use latches, barriers, futures with timeouts, and socket connect
  timeouts; do not use timing sleeps or virtual-thread enumeration.
- Add public KDoc and follow `STYLE.md`: four-space Kotlin indentation, natural-language backtick
  test names, immutable data, and narrowly documented static-analysis suppressions only at required
  boundary catches.

---

## File and Responsibility Map

### Production

```text
src/main/kotlin/com/salesforce/revoman/testing/http/
├── MockHttpHandler.kt              Java-friendly handler SAM only
├── RecordedNameValue.kt            Java record-compatible name/value occurrence
├── RecordedHttpRequest.kt          immutable public request snapshot and body copies
├── MockHttpServer.kt               four-operation public facade
└── internal/
    ├── RequestLedger.kt             ordinal, snapshot, and handler-failure linearization
    ├── JdkMockHttpServer.kt         exact bind, adapter, body replay, startup transaction
    └── MockHttpServerLifecycle.kt   stop protocol and close-time aggregation
```

### Public contract tests

```text
src/test/kotlin/com/salesforce/revoman/testing/http/
├── RecordedHttpRequestContractTest.kt
├── MockHttpServerWireContractTest.kt
├── MockHttpServerConcurrencyTest.kt
├── MockHttpServerFailureTest.kt
├── MockHttpServerStartupTest.kt
└── MockHttpServerLifecycleTest.kt

src/test/java/com/salesforce/revoman/testing/http/
└── MockHttpServerJavaContractTest.java
```

### Compatibility fixtures and gates

```text
src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinMockHttpServerApiFixture.kt
src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaMockHttpServerApiFixture.java
src/test/kotlin/com/salesforce/revoman/compat/MockHttpServerJvmSurfaceAdditions.kt
src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt
src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt
api/revoman-root.api
```

### Repository migration

The root fixture file is deleted only after its tests have moved:

```text
src/test/kotlin/com/salesforce/revoman/testsupport/LoopbackHttpFixture.kt
```

The integration fixture becomes a domain-only handler:

```text
src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/
├── DeterministicMockApi.kt
└── DeterministicMockApiTest.kt
```

---

### Task 1: Add the public handler and immutable request values

**Files:**

- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpHandler.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/RecordedNameValue.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/RecordedHttpRequest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/testing/http/RecordedHttpRequestContractTest.kt`

**Interfaces:**

- Consumes: http4k `Method`, `Request`, and `Response`; Java `Charset` and immutable `List` copies.
- Produces: `MockHttpHandler.handle(Request): Response`, record-shaped `RecordedNameValue`, and the
  complete immutable `RecordedHttpRequest` observation API used by every later task.

- [ ] **Step 1: Write the request-value contract test**

Create `RecordedHttpRequestContractTest.kt`. Construct snapshots through the module-visible factory
and prove value preservation, collection immutability, body defensiveness, and both string decoders:

```kotlin
class RecordedHttpRequestContractTest {
    @Test
    fun `recorded values and body remain immutable`() {
        val query = mutableListOf(RecordedNameValue("tag", "first"))
        val headers = mutableListOf(RecordedNameValue("X-Repeat", "one"))
        val sourceBody = "café".toByteArray(Charsets.UTF_8)
        val request =
            RecordedHttpRequest.create(Method.POST, "/items", query, headers, sourceBody)

        query += RecordedNameValue("tag", "second")
        headers.clear()
        sourceBody.fill(0)
        val firstCopy = request.bodyBytes()
        firstCopy.fill(0)

        assertThat(request.method).isEqualTo(Method.POST)
        assertThat(request.path).isEqualTo("/items")
        assertThat(request.queryParameters)
            .containsExactly(RecordedNameValue("tag", "first"))
        assertThat(request.headers)
            .containsExactly(RecordedNameValue("X-Repeat", "one"))
        assertThat(request.bodyString()).isEqualTo("café")
        assertThat(request.bodyBytes()).isEqualTo("café".toByteArray(Charsets.UTF_8))
        assertThrows<UnsupportedOperationException> {
            (request.queryParameters as MutableList).add(RecordedNameValue("x", "y"))
        }
    }

    @Test
    fun `body string accepts an explicit charset`() {
        val request =
            RecordedHttpRequest.create(
                Method.GET,
                "/encoded",
                emptyList(),
                emptyList(),
                "snowman ☃".toByteArray(Charsets.UTF_16LE),
            )

        assertThat(request.bodyString(Charsets.UTF_16LE)).isEqualTo("snowman ☃")
    }
}
```

- [ ] **Step 2: Run the focused test and require RED**

Run:

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.RecordedHttpRequestContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: compilation fails because the three public feature types do not exist. A failure caused
only by a misspelled package or test import is not accepted.

- [ ] **Step 3: Implement the public SAM and record value**

Create `MockHttpHandler.kt` and `RecordedNameValue.kt` with license headers and public KDoc. Their
declarations are exact:

```kotlin
fun interface MockHttpHandler {
    @Throws(Exception::class)
    fun handle(request: Request): Response
}
```

```kotlin
@JvmRecord
data class RecordedNameValue(val name: String, val value: String?)
```

- [ ] **Step 4: Implement the immutable request snapshot**

Use a private constructor, eager `List.copyOf`, two body copies, and a module-only synthetic factory:

```kotlin
class RecordedHttpRequest private constructor(
    val method: Method,
    val path: String,
    queryParameters: List<RecordedNameValue>,
    headers: List<RecordedNameValue>,
    body: ByteArray,
) {
    val queryParameters: List<RecordedNameValue> = List.copyOf(queryParameters)
    val headers: List<RecordedNameValue> = List.copyOf(headers)
    private val body = body.copyOf()

    fun bodyBytes(): ByteArray = body.copyOf()

    @JvmOverloads
    fun bodyString(charset: Charset = Charsets.UTF_8): String = String(body, charset)

    internal companion object {
        @JvmSynthetic
        internal fun create(
            method: Method,
            path: String,
            queryParameters: List<RecordedNameValue>,
            headers: List<RecordedNameValue>,
            body: ByteArray,
        ): RecordedHttpRequest =
            RecordedHttpRequest(method, path, queryParameters, headers, body)
    }
}
```

Document decoded query semantics, adapter-visible header semantics, fresh body copies, and why the
factory is non-public.

- [ ] **Step 5: Run GREEN and format the slice**

Run:

```bash
./gradlew spotlessApply
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.RecordedHttpRequestContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: both tests pass and `git diff --check` prints nothing.

- [ ] **Step 6: Commit the immutable public values**

```bash
git add \
  src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpHandler.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/RecordedNameValue.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/RecordedHttpRequest.kt \
  src/test/kotlin/com/salesforce/revoman/testing/http/RecordedHttpRequestContractTest.kt
git diff --cached --check
git commit -m "feat: add mock HTTP request contracts"
```

---

### Task 2: Start the real-wire server and record concurrent requests

**Files:**

- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpServer.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/RequestLedger.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/JdkMockHttpServer.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/MockHttpServerLifecycle.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerWireContractTest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerConcurrencyTest.kt`

**Interfaces:**

- Consumes: Task 1's three types, `HttpServer`, `HttpExchangeHandler`, and the existing
  `prepareHttpClient(insecureHttp = false)` test client.
- Produces: `MockHttpServer.start(MockHttpHandler)`, `baseUrl`, `requests()`, basic close ownership,
  `RequestLedger.publish(RecordedHttpRequest): Long`, and the internal startup/recording seams used
  by Tasks 3 and 4.

- [ ] **Step 1: Write the real-wire request/response contract**

Create `MockHttpServerWireContractTest.kt`. The first test must start two simultaneous servers,
parse both URLs, send binary content over a real socket, and assert only stable header guarantees:

```kotlin
@Test
fun `real wire preserves request and response on exact ephemeral IPv4 loopback`() {
    val requestBody = byteArrayOf(0, 1, 2, 127, -1)
    val handlerBody = AtomicReference<ByteArray>()
    MockHttpServer.start { request ->
            handlerBody.set(request.body.stream.readBytes())
            Response(CREATED)
                .header("X-Reply", "first")
                .header("X-Reply", "second")
                .body("accepted")
        }
        .use { server ->
            MockHttpServer.start { Response(OK) }.use { second ->
                val firstUri = URI.create(server.baseUrl)
                val secondUri = URI.create(second.baseUrl)
                assertThat(firstUri.scheme).isEqualTo("http")
                assertThat(firstUri.host).isEqualTo("127.0.0.1")
                assertThat(firstUri.port).isGreaterThan(0)
                assertThat(secondUri.port).isNotEqualTo(firstUri.port)
            }

            val response =
                prepareHttpClient(insecureHttp = false)(
                    Request(POST, "${server.baseUrl}/wire?term=hello%20world&tag=a&tag=b&flag&empty=")
                        .header("X-Repeat", "first")
                        .header("X-Repeat", "second")
                        .body(Body(ByteBuffer.wrap(requestBody)))
                )

            assertThat(response.status).isEqualTo(CREATED)
            assertThat(response.headerValues("X-Reply"))
                .containsExactly("first", "second").inOrder()
            assertThat(response.bodyString()).isEqualTo("accepted")
            val recorded = server.requests().single()
            assertThat(recorded.method).isEqualTo(POST)
            assertThat(recorded.path).isEqualTo("/wire")
            assertThat(recorded.queryParameters)
                .containsExactly(
                    RecordedNameValue("term", "hello world"),
                    RecordedNameValue("tag", "a"),
                    RecordedNameValue("tag", "b"),
                    RecordedNameValue("flag", null),
                    RecordedNameValue("empty", ""),
                ).inOrder()
            assertThat(
                    recorded.headers
                        .filter { it.name.equals("X-Repeat", ignoreCase = true) }
                        .mapNotNull(RecordedNameValue::value)
                )
                .containsExactly("first", "second").inOrder()
            assertThat(recorded.bodyBytes()).isEqualTo(requestBody)
            assertThat(handlerBody.get()).isEqualTo(requestBody)
        }
}
```

Add a second test that saves `closedUrl`, closes twice, verifies `baseUrl` and the last snapshot stay
readable, then uses `Socket.connect(address, 500)` and requires `IOException`.

- [ ] **Step 2: Write immutable snapshot and concurrent-handler tests**

Create `MockHttpServerConcurrencyTest.kt` with these three deterministic checks:

```kotlin
@Test
fun `requests snapshots are stable and unmodifiable`() {
    MockHttpServer.start { Response(OK) }.use { server ->
        val client = prepareHttpClient(insecureHttp = false)
        client(Request(GET, "${server.baseUrl}/first"))
        val firstSnapshot = server.requests()
        client(Request(GET, "${server.baseUrl}/second"))

        assertThat(firstSnapshot.map { it.path }).containsExactly("/first")
        assertThat(server.requests().map { it.path })
            .containsExactly("/first", "/second").inOrder()
        assertThrows<UnsupportedOperationException> {
            (firstSnapshot as MutableList).clear()
        }
    }
}
```

For overlap, submit two client calls to a test-owned executor. Each handler stores
`Thread.currentThread().threadId()`, asserts `Thread.currentThread().isVirtual`, counts down an
`entered` latch, and waits on a `release` latch. Require both handlers to enter within five seconds,
require two distinct IDs, observe both requests before releasing them, then resolve both futures
with five-second timeouts. For capture order, let `/first` enter before submitting `/second`; require
`requests().map { it.path } == listOf("/first", "/second")` before either completes.

- [ ] **Step 3: Run both tests and require RED**

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerWireContractTest' \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerConcurrencyTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: compilation fails because `MockHttpServer` does not exist.

- [ ] **Step 4: Implement the ledger linearization point**

Create `RequestLedger.kt` around one `ReentrantLock`:

```kotlin
internal data class HandlerFailure(val ordinal: Long, val failure: Exception)

internal class RequestLedger {
    private val lock = ReentrantLock()
    private var nextOrdinal = 0L
    private val records = mutableListOf<Pair<Long, RecordedHttpRequest>>()
    private val failures = mutableListOf<HandlerFailure>()

    fun publish(request: RecordedHttpRequest): Long = lock.withLock {
        val ordinal = nextOrdinal++
        records += ordinal to request
        ordinal
    }

    fun recordHandlerFailure(ordinal: Long, failure: Exception) = lock.withLock {
        failures += HandlerFailure(ordinal, failure)
    }

    fun requests(): List<RecordedHttpRequest> = lock.withLock {
        List.copyOf(records.map { it.second })
    }

    fun handlerFailures(): List<HandlerFailure> = lock.withLock {
        List.copyOf(failures.sortedBy(HandlerFailure::ordinal))
    }
}
```

Never assign the ordinal before the complete `RecordedHttpRequest` has been built.

- [ ] **Step 5: Implement body capture, replay, exact startup, and facade**

In `JdkMockHttpServer.kt`, define an injectable internal starter and a recording handler. The
default executor and server factories are exact:

```kotlin
private val logger = KotlinLogging.logger {}

internal class MockHttpServerStarter(
    private val executorFactory: () -> ExecutorService = {
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("revoman-mock-http-", 0).factory()
        )
    },
    private val serverFactory: (InetSocketAddress) -> HttpServer = { address ->
        HttpServer.create(address, 0)
    },
) {
    fun start(handler: MockHttpHandler): MockHttpServerLifecycle {
        var executor: ExecutorService? = null
        var server: HttpServer? = null
        try {
            val ledger = RequestLedger()
            val ownedExecutor = executorFactory().also { executor = it }
            val ownedServer =
                serverFactory(InetSocketAddress("127.0.0.1", 0)).also { server = it }
            ownedServer.createContext("/", HttpExchangeHandler(recordingHandler(handler, ledger)))
            ownedServer.executor = ownedExecutor
            ownedServer.start()
            val address = ownedServer.address
            check(address.address is Inet4Address) {
                "Mock HTTP server must bind IPv4 loopback, got ${address.address}"
            }
            check(address.address.hostAddress == "127.0.0.1") {
                "Mock HTTP server must bind 127.0.0.1, got ${address.address.hostAddress}"
            }
            check(address.port > 0) { "Mock HTTP server must select a positive port" }
            val baseUrl = "http://127.0.0.1:${address.port}"
            logger.debug { "Started mock HTTP server at $baseUrl" }
            return MockHttpServerLifecycle(baseUrl, ownedServer, ownedExecutor, ledger)
        } catch (failure: Throwable) {
            val startupFailure =
                IllegalStateException("Failed to start mock HTTP server", failure)
            server?.let { ownedServer ->
                try {
                    ownedServer.stop(0)
                } catch (cleanupFailure: Throwable) {
                    startupFailure.addSuppressed(cleanupFailure)
                }
            }
            executor?.let { ownedExecutor ->
                try {
                    ownedExecutor.shutdownNow()
                } catch (cleanupFailure: Throwable) {
                    startupFailure.addSuppressed(cleanupFailure)
                }
            }
            throw startupFailure
        }
    }
}
```

For Task 2, implement the capture-before-handler order exactly:

```kotlin
private fun recordingHandler(
    handler: MockHttpHandler,
    ledger: RequestLedger,
): HttpHandler = { request ->
    val (recorded, replayable) = request.capture()
    ledger.publish(recorded)
    handler.handle(replayable)
}
```

Task 3 adds the specified failure boundary while preserving this order.

The recording conversion must use decoded http4k queries and adapter-visible headers:

```kotlin
private fun Request.capture(): Pair<RecordedHttpRequest, Request> {
    val buffer = body.payload.asReadOnlyBuffer()
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    val recorded =
        RecordedHttpRequest.create(
            method,
            uri.path,
            uri.queries().map { (name, value) -> RecordedNameValue(name, value) },
            headers.map { (name, value) -> RecordedNameValue(name, value) },
            bytes,
        )
    return recorded to body(Body(ByteBuffer.wrap(bytes)))
}
```

Create executor, server at `InetSocketAddress("127.0.0.1", 0)`, root context with
`HttpExchangeHandler(recordingHandler)`, assign the executor, call `start()`, then validate
`address.address is Inet4Address`, `address.address.hostAddress == "127.0.0.1"`, and
`address.port > 0`. Return `MockHttpServerLifecycle` only after validation. Catch startup
`Throwable`, attempt `stop(0)` and executor termination, then throw `IllegalStateException` whose
cause is the original and whose suppressed entries are cleanup failures.

Create `MockHttpServer.kt` with exactly this facade:

```kotlin
class MockHttpServer private constructor(
    private val lifecycle: MockHttpServerLifecycle,
) : AutoCloseable {
    val baseUrl: String = lifecycle.baseUrl

    fun requests(): List<RecordedHttpRequest> = lifecycle.requests()

    override fun close() = lifecycle.close()

    companion object {
        @JvmStatic
        fun start(handler: MockHttpHandler): MockHttpServer =
            MockHttpServer(MockHttpServerStarter().start(handler))
    }
}
```

Create the lifecycle with the exact constructor consumed by the facade and starter:

```kotlin
internal class MockHttpServerLifecycle(
    val baseUrl: String,
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val ledger: RequestLedger,
) {
    private val closed = AtomicBoolean()

    fun requests(): List<RecordedHttpRequest> = ledger.requests()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
                "Mock HTTP handler work did not stop within 10 seconds"
            }
        }
    }
}
```

Its KDoc must identify it as buffered, real-wire, test-only IPv4 loopback infrastructure; state that
the handler can run concurrently on virtual threads; require caller-owned mutable handler state and
blocking work to be thread-safe and interruption-cooperative; define stable `baseUrl`; and define
`requests()` as an unmodifiable point-in-time capture-order snapshot.

Give `MockHttpServerLifecycle` a working initial `stop(0)` plus executor shutdown implementation;
Task 4 replaces that small close body with the complete tested state machine. Cache `baseUrl` in
the lifecycle constructor so it remains stable after closure.

- [ ] **Step 6: Run GREEN and check for accidental platform-thread use**

```bash
./gradlew spotlessApply
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerWireContractTest' \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerConcurrencyTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
rg -n 'newCachedThreadPool|newFixedThreadPool|newSingleThreadExecutor' \
  src/main/kotlin/com/salesforce/revoman/testing/http
git diff --check
```

Expected: contract tests pass, and `rg` returns no production match.

- [ ] **Step 7: Commit the real-wire vertical slice**

```bash
git add \
  src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpServer.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/RequestLedger.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/JdkMockHttpServer.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/MockHttpServerLifecycle.kt \
  src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerWireContractTest.kt \
  src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerConcurrencyTest.kt
git diff --cached --check
git commit -m "feat: serve and record mock HTTP traffic"
```

---

### Task 3: Convert handler failures into wire responses and teardown evidence

**Files:**

- Modify: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/JdkMockHttpServer.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/RequestLedger.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/MockHttpServerLifecycle.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerFailureTest.kt`
- Create: `src/test/java/com/salesforce/revoman/testing/http/MockHttpServerJavaContractTest.java`

**Interfaces:**

- Consumes: Task 2's recording handler, ordinal returned by `RequestLedger.publish`, and lifecycle
  close hook.
- Produces: empty `500` conversion for handler `Exception`/Java `null`, ordered deferred failure
  aggregation, explicit-error-response pass-through, and a Java lambda/try-with-resources contract.

- [ ] **Step 1: Write sequential failure and explicit-500 tests**

Create `MockHttpServerFailureTest.kt`. Use two named exception instances and manually close so the
aggregate can be inspected:

```kotlin
@Test
fun `handler exceptions return empty 500 and close reports capture order`() {
    val first = IOException("first handler failure")
    val second = IllegalStateException("second handler failure")
    val server = MockHttpServer.start { request ->
        if (request.uri.path == "/first") throw first else throw second
    }
    val client = prepareHttpClient(insecureHttp = false)

    val firstResponse = client(Request(GET, "${server.baseUrl}/first"))
    val secondResponse = client(Request(GET, "${server.baseUrl}/second"))

    assertThat(firstResponse.status).isEqualTo(INTERNAL_SERVER_ERROR)
    assertThat(firstResponse.bodyString()).isEmpty()
    assertThat(secondResponse.status).isEqualTo(INTERNAL_SERVER_ERROR)
    assertThat(server.requests().map { it.path })
        .containsExactly("/first", "/second").inOrder()
    val failure = assertThrows<IllegalStateException> { server.close() }
    assertThat(failure).hasMessageThat().contains("2 mock HTTP handler failures")
    assertThat(failure.cause).isSameInstanceAs(first)
    assertThat(failure.suppressed.asList()).containsExactly(second).inOrder()
    server.close()
}
```

Add a separate server returning `Response(INTERNAL_SERVER_ERROR).body("intentional")`; require the
wire body and status to survive and `close()` not to throw.

- [ ] **Step 2: Add direct-boundary tests for capture failure and `Error`**

Make the recording function `internal` so the test can invoke it without the http4k adapter. Feed a
MockK `Request` whose `method` is `GET`, whose `uri` is `Uri.of("/capture")`, and whose `body` getter
throws a pre-created `IOException`; require an empty `500`, an empty request snapshot, and no
handler-failure entry. Add a second MockK request with readable body/method/URI/query/header getters
whose `body(Body)` replacement method throws after publication; require the snapshot to remain,
the user handler not to run, and the handler-failure ledger to remain empty. Feed a handler that
throws a pre-created `AssertionError`; require `assertThrows<AssertionError>` to return that same
instance and require the failure ledger to remain empty. Do not test `Error` through a client
because the exception belongs to the exchange task, not the test thread.

- [ ] **Step 3: Add the Java contract including a null handler result**

Create `MockHttpServerJavaContractTest.java`:

```java
@Test
void javaLambdaAndTryWithResourcesUseThePublicServer() throws Exception {
  try (var server = MockHttpServer.start(request -> Response.create(Status.OK).body("java"))) {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/java")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("java");
    assertThat(server.requests()).hasSize(1);
    assertThat(server.requests().getFirst().getPath()).isEqualTo("/java");
  }
}

@SuppressWarnings("DataFlowIssue")
@Test
void javaNullResponseBecomesAnEmpty500AndDeferredFailure() throws Exception {
  var server = MockHttpServer.start(request -> null);
  var response =
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/null")).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
  assertThat(response.statusCode()).isEqualTo(500);
  assertThat(response.body()).isEmpty();
  var failure = assertThrows(IllegalStateException.class, server::close);
  assertThat(failure).hasCauseThat().isInstanceOf(NullPointerException.class);
}
```

Import JDK `HttpClient`, `HttpRequest`, `HttpResponse`, `URI`, and `StandardCharsets`; do not depend
on a Kotlin-internal ReVoman test client from Java.

- [ ] **Step 4: Run the failure contracts and require RED**

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerFailureTest' \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerJavaContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: thrown handlers currently reach the adapter without close-time evidence, and the Java
null case does not satisfy the required aggregate.

- [ ] **Step 5: Implement the handler boundary without catching `Error`**

After capture and publication, use a nullable response expression to make Java null explicit:

```kotlin
@Suppress("TooGenericExceptionCaught")
internal fun recordingHandler(
    handler: MockHttpHandler,
    ledger: RequestLedger,
): HttpHandler = { request ->
    val capture = captureRequestOrNull(request, ledger)
    if (capture == null) {
        Response(INTERNAL_SERVER_ERROR)
    } else {
        val (ordinal, replayable) = capture
        try {
            val response: Response? = handler.handle(replayable)
            response ?: throw NullPointerException("MockHttpHandler returned null")
        } catch (failure: Exception) {
            logger.error(failure) {
                "Mock HTTP handler failed for ${request.method} ${request.uri.path}"
            }
            ledger.recordHandlerFailure(ordinal, failure)
            Response(INTERNAL_SERVER_ERROR)
        }
    }
}
```

The suppression belongs only on this boundary and its KDoc must say `Exception` is intentional so
every `Error` escapes. Capture/replay conversion has its own `Exception` catch, logs method/path,
returns empty `500`, and never calls `recordHandlerFailure`. A complete record remains published if
failure happens after publication; an incomplete record is never published. Implement that ordering
as:

```kotlin
@Suppress("TooGenericExceptionCaught")
private fun captureRequestOrNull(
    request: Request,
    ledger: RequestLedger,
): Pair<Long, Request>? {
    val method = request.method
    val path = request.uri.path
    return try {
        val buffer = request.body.payload.asReadOnlyBuffer()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        val recorded =
            RecordedHttpRequest.create(
                method,
                path,
                request.uri.queries().map { (name, value) -> RecordedNameValue(name, value) },
                request.headers.map { (name, value) -> RecordedNameValue(name, value) },
                bytes,
            )
        val ordinal = ledger.publish(recorded)
        ordinal to request.body(Body(ByteBuffer.wrap(bytes)))
    } catch (failure: Exception) {
        logger.error(failure) { "Mock HTTP request capture failed for $method $path" }
        null
    }
}
```

- [ ] **Step 6: Add deterministic close aggregation**

Add this aggregation shape to `RequestLedger`:

```kotlin
fun aggregateCloseFailure(shutdownFailures: List<Throwable>): IllegalStateException? {
    val handlerFailures = handlerFailures().map(HandlerFailure::failure)
    val orderedFailures = handlerFailures + shutdownFailures
    if (orderedFailures.isEmpty()) return null
    val message =
        if (handlerFailures.isEmpty()) {
            "Mock HTTP server shutdown failed"
        } else {
            "${handlerFailures.size} mock HTTP handler failures"
        }
    return IllegalStateException(message, orderedFailures.first()).apply {
        orderedFailures.drop(1).forEach(::addSuppressed)
    }
}
```

The first handler exception is the cause, remaining handler exceptions are suppressed in ordinal
order, and shutdown failures follow them. With no handler exception, the first shutdown failure is
the cause. The owner close path throws only after cleanup; repeat close calls return.

- [ ] **Step 7: Run GREEN and commit**

```bash
./gradlew spotlessApply
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerFailureTest' \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerJavaContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/JdkMockHttpServer.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/RequestLedger.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/MockHttpServerLifecycle.kt \
  src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerFailureTest.kt \
  src/test/java/com/salesforce/revoman/testing/http/MockHttpServerJavaContractTest.java
git diff --cached --check
git commit -m "feat: surface mock handler failures on close"
```

---

### Task 4: Make startup and shutdown transactional

**Files:**

- Modify: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/JdkMockHttpServer.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/testing/http/internal/MockHttpServerLifecycle.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerStartupTest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerLifecycleTest.kt`

**Interfaces:**

- Consumes: `MockHttpServerStarter` factories, `RequestLedger.aggregateCloseFailure`, JDK
  `HttpServer`, and `ExecutorService`.
- Produces: fully transactional startup, exact 5+5-second shutdown, interrupt restoration,
  first-closer ownership, and deterministic startup/shutdown failure ordering.

- [ ] **Step 1: Write startup cleanup tests through the internal factories**

Use relaxed MockK instances of `HttpServer` and `ExecutorService`, with explicit stubs for
`address`, `start`, `stop`, `shutdown`, `shutdownNow`, `awaitTermination`, and context creation.
Cover these cases separately:

```text
executor factory throws                    no server is created; original is cause
server factory throws                      executor receives shutdownNow
createContext or executor assignment fails server stops; executor receives shutdownNow
start throws                               server stops; executor receives shutdownNow
address is 0.0.0.0                         startup rejected and both resources cleaned
address is IPv6 loopback                   startup rejected and both resources cleaned
address port is zero                       startup rejected and both resources cleaned
cleanup itself throws                      cleanup exception is suppressed after original
```

For the successful seam, expose `MockHttpServerStarter.start(handler)` and assert the returned
`baseUrl` is exactly `http://127.0.0.1:43210` for a stubbed IPv4 address.

- [ ] **Step 2: Write lifecycle state-machine tests**

Build `MockHttpServerLifecycle` directly with mocks and a real `RequestLedger`. Required tests:

- two sequential closes call `server.stop(0)` and `executor.shutdown()` once;
- a winner blocked in `awaitTermination` keeps a concurrent loser blocked until the winner releases,
  then the loser returns without rethrowing the winner's handler failure;
- interrupting that losing closer does not let it overtake the winner; after the winner releases,
  the loser returns with its interrupt flag restored;
- first await returning `false` calls `shutdownNow()` and performs a second five-second await;
- second await returning `false` becomes a shutdown failure whose message names the ten-second bound;
- an `InterruptedException` during await still calls `shutdownNow`, finishes cleanup, and restores
  `Thread.currentThread().isInterrupted` before throwing its aggregate;
- a thrown `stop`, `shutdown`, `shutdownNow`, or await failure does not prevent later cleanup calls;
- handler failure is the cause and shutdown failure follows the later handler exceptions in
  `suppressed` order.

Use `CountDownLatch` and `Future.get(5, SECONDS)` for concurrent-close tests. Clear the test thread's
interrupt status in `finally` with `Thread.interrupted()` so it cannot contaminate later tests.

- [ ] **Step 3: Run lifecycle tests and require RED**

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerStartupTest' \
  --tests 'com.salesforce.revoman.testing.http.MockHttpServerLifecycleTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: at least the injected invalid-address/startup cleanup and second-timeout cases fail
against Task 3's partial lifecycle.

- [ ] **Step 4: Implement startup as one fail-closed transaction**

Track nullable `executor` and `server` locals. On any startup `Throwable`, stop a created server,
call `shutdownNow()` on a created executor, await termination without exceeding the same internal
five-second constant, attach every cleanup failure to the wrapper, and throw:

```kotlin
IllegalStateException("Failed to start mock HTTP server", originalFailure)
```

Validate the actual address only after `start()`. Log the final stable base URL at debug only after
all validation succeeds.

- [ ] **Step 5: Implement first-closer ownership and bounded teardown**

Use `AtomicBoolean closeStarted` plus `CountDownLatch closeFinished`. The winner gathers shutdown
failures in a `MutableList<Throwable>` while performing every action; the loser waits
uninterruptibly, remembers interruption,
restores its flag, and returns. The winner's core sequence is exact:

```kotlin
server.stop(0)
executor.shutdown()
if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
    executor.shutdownNow()
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        shutdownFailures +=
            IllegalStateException("Mock HTTP handler work did not stop within 10 seconds")
    }
}
```

Each line must be guarded so one failure does not suppress later cleanup. If an await is interrupted,
record the interruption as a shutdown failure, continue with `shutdownNow`, complete the second
wait, count down `closeFinished` in `finally`, restore the interrupt flag, then throw the aggregate.
Log stop at debug after the listener stop attempt.

Use this control-flow skeleton; `attempt` catches `Throwable`, appends it, and returns without
skipping the next cleanup action:

```kotlin
fun close() {
    if (!closeStarted.compareAndSet(false, true)) {
        awaitFirstCloser()
        return
    }
    val shutdownFailures = mutableListOf<Throwable>()
    var interrupted = false
    try {
        attempt(shutdownFailures) { server.stop(0) }
        logger.debug { "Stopped mock HTTP server at $baseUrl" }
        attempt(shutdownFailures) { executor.shutdown() }
        var terminated =
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS)
            } catch (failure: InterruptedException) {
                interrupted = true
                shutdownFailures += failure
                false
            }
        if (!terminated) {
            attempt(shutdownFailures) { executor.shutdownNow() }
            terminated =
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS)
                } catch (failure: InterruptedException) {
                    interrupted = true
                    shutdownFailures += failure
                    false
                }
            if (!terminated) {
                shutdownFailures +=
                    IllegalStateException("Mock HTTP handler work did not stop within 10 seconds")
            }
        }
        ledger.aggregateCloseFailure(shutdownFailures)?.let { throw it }
    } finally {
        closeFinished.countDown()
        if (interrupted) Thread.currentThread().interrupt()
    }
}
```

Define the two helpers in the lifecycle file:

```kotlin
private fun awaitFirstCloser() {
    var interrupted = false
    while (true) {
        try {
            closeFinished.await()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private inline fun attempt(
    failures: MutableList<Throwable>,
    action: () -> Unit,
) {
    try {
        action()
    } catch (failure: Throwable) {
        failures += failure
    }
}
```

The losing closer never reads or rethrows the winner's aggregate.

- [ ] **Step 6: Run every public contract test**

```bash
./gradlew spotlessApply
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.*' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: all request, wire, concurrency, failure, startup, lifecycle, and Java tests pass.

- [ ] **Step 7: Commit transactional ownership**

```bash
git add \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/JdkMockHttpServer.kt \
  src/main/kotlin/com/salesforce/revoman/testing/http/internal/MockHttpServerLifecycle.kt \
  src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerStartupTest.kt \
  src/test/kotlin/com/salesforce/revoman/testing/http/MockHttpServerLifecycleTest.kt
git diff --cached --check
git commit -m "feat: make mock server lifecycle fail closed"
```

---

### Task 5: Lock the Java, Kotlin, and raw-JVM public surface

**Files:**

- Create: `src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinMockHttpServerApiFixture.kt`
- Create: `src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaMockHttpServerApiFixture.java`
- Create: `src/test/kotlin/com/salesforce/revoman/compat/MockHttpServerJvmSurfaceAdditions.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt:113-139,227-350`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt:170-235`
- Modify: `api/revoman-root.api`

**Interfaces:**

- Consumes: the built root JAR and Task 4's finished public API.
- Produces: external Kotlin/Java compile proof, exact Kotlin ABI dump, a feature-owned literal raw-JVM
  addition set, and compatibility tests that keep CS2 facts independent.

- [ ] **Step 1: Add external Kotlin and Java consumers**

The Kotlin fixture must compile both direct-lambda and existing-http4k-handler adaptation:

```kotlin
fun consumeMockHttpServerFromKotlin(existing: HttpHandler) {
    val adapted: MockHttpHandler = MockHttpHandler(existing)
    MockHttpServer.start(adapted).use { server ->
        val baseUrl: String = server.baseUrl
        val requests: List<RecordedHttpRequest> = server.requests()
        requests.forEach { request ->
            val method: Method = request.method
            val path: String = request.path
            val query: List<RecordedNameValue> = request.queryParameters
            val headers: List<RecordedNameValue> = request.headers
            val bytes: ByteArray = request.bodyBytes()
            val utf8: String = request.bodyString()
            val utf16: String = request.bodyString(Charsets.UTF_16)
            listOf(baseUrl, method, path, query, headers, bytes, utf8, utf16)
        }
    }
    MockHttpServer.start { Response(OK) }.close()
}
```

The Java fixture must use a lambda, `AutoCloseable`, typed request/value lists, bean getters,
record accessors, and both body overloads:

```java
static void consumeMockHttpServerFromJava() throws Exception {
  MockHttpHandler checkedHandler = request -> {
    throw new IOException("checked handler contract");
  };
  try (MockHttpServer server =
      MockHttpServer.start(request -> Response.create(Status.OK).body("ok"))) {
    String baseUrl = server.getBaseUrl();
    List<RecordedHttpRequest> requests = server.requests();
    for (RecordedHttpRequest request : requests) {
      Method method = request.getMethod();
      String path = request.getPath();
      List<RecordedNameValue> query = request.getQueryParameters();
      List<RecordedNameValue> headers = request.getHeaders();
      byte[] bytes = request.bodyBytes();
      String utf8 = request.bodyString();
      String utf16 = request.bodyString(StandardCharsets.UTF_16);
    }
    RecordedNameValue value = new RecordedNameValue("flag", null);
    String name = value.name();
    String nullableValue = value.value();
  }
  MockHttpServer checkedServer = MockHttpServer.start(checkedHandler);
  checkedServer.close();
}
```

- [ ] **Step 2: Run external compilation and require the active ABI gate to fail**

```bash
./gradlew apiCompatibilityTestClasses externalConsumerClasspathCheck checkKotlinAbi \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: external consumer compilation passes against the built JAR, while `checkKotlinAbi` fails
because `api/revoman-root.api` has not accepted the four types.

- [ ] **Step 3: Update and inspect the active Kotlin ABI only**

```bash
./gradlew updateKotlinAbi
git diff -- api/revoman-root.api
git diff --exit-code -- \
  api/cs2-baseline-revoman-root.api \
  api/cs2-baseline-revoman-root.jvm.tsv \
  api/cs2-migration-map.tsv
```

Require the active dump additions to be owned only by
`com/salesforce/revoman/testing/http/{MockHttpHandler,RecordedNameValue,RecordedHttpRequest,MockHttpServer}`
and compiler-generated companions for the private factories. Reject unrelated declarations.

- [ ] **Step 4: Separate feature raw rows from CS2's frozen migration facts**

Create `MockHttpServerJvmSurfaceAdditions.kt` with an intentionally empty set for the first run:

```kotlin
internal val MOCK_HTTP_SERVER_RAW_JVM_ADDITIONS: Set<String> = emptySet()

internal val APPROVED_RAW_JVM_ADDITIONS: Set<String> =
    CS2_TASK7_RAW_JVM_ADDITIONS + MOCK_HTTP_SERVER_RAW_JVM_ADDITIONS
```

Change the two whole-active-surface assertions to compare additions with
`APPROVED_RAW_JVM_ADDITIONS`, while removals remain exactly
`CS2_TASK7_RAW_JVM_REMOVALS`. Keep the constants `549`, `447`, and `28` scoped to CS2; never add the
feature to `api/cs2-migration-map.tsv`.

In `assertExactCs2aAbiProjections`, compute feature Kotlin additions separately and require every
owner to start with `com/salesforce/revoman/testing/http/`. Subtract feature source-callable JVM
keys from `supportedJavaAdditions` before comparing the remainder to CS2's 28 approved class rows.

In `JvmSurfaceVisibilityTest`, keep the Task 7 test filtered to
`CS2_TASK7_RAW_JVM_ADDITIONS`, add a feature-specific exact assertion, and scope the historical
"no added companion" check to CS2 rows so deliberate mock-server companions are allowed only when
present in the feature-owned set. Introduce these two projections immediately after calculating
`additions`, and use `cs2Additions` for every existing Task 3-7 assertion in that test:

```kotlin
val cs2Additions = additions.filter { it.render() in CS2_TASK7_RAW_JVM_ADDITIONS }
val mockServerAdditions =
    additions.filter { it.render() in MOCK_HTTP_SERVER_RAW_JVM_ADDITIONS }
assertThat(cs2Additions.map(JvmSurfaceEntry::render))
    .containsExactlyElementsIn(CS2_TASK7_RAW_JVM_ADDITIONS)
assertThat(mockServerAdditions.map(JvmSurfaceEntry::render))
    .containsExactlyElementsIn(MOCK_HTTP_SERVER_RAW_JVM_ADDITIONS)
assertThat(additions.map(JvmSurfaceEntry::render))
    .containsExactlyElementsIn(APPROVED_RAW_JVM_ADDITIONS)
```

- [ ] **Step 5: Capture and review the compiler's exact raw-JVM additions**

Run:

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.compat.ApiBaselineInventoryTest' \
  --tests 'com.salesforce.revoman.compat.JvmSurfaceVisibilityTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: RED lists every actual active-minus-frozen row absent from the empty feature set. Copy
each emitted row verbatim into a literal Kotlin set in
`MockHttpServerJvmSurfaceAdditions.kt`, sorted by the rendered row. Review class, constructor,
field, method, bridge, synthetic, record/data-class, `@JvmOverloads`, and companion rows; reject any
owner outside `com/salesforce/revoman/testing/http/`. This is a compiler-derived inventory, not a
hand-estimated count.

- [ ] **Step 6: Assert the source-callable shape directly**

Add a test that filters `JvmSurfaceInventory.readJar(configuredRootJar())` to the four public owners
and their compiler-generated companion carriers, then requires:

```text
MockHttpHandler       one source-callable handle(Request): Response method
RecordedNameValue     one constructor; name/value record accessors; expected data-class
                      component/copy/equality/hash/string methods; no mutable setter
RecordedHttpRequest   no source-callable constructor; four getters; bodyBytes; two bodyString methods
MockHttpServer        no source-callable constructor; getBaseUrl; requests; close; static start;
                      companion start carrier generated by @JvmStatic
```

Require no source-callable internal implementation owner below
`com/salesforce/revoman/testing/http/internal/`. The `RecordedHttpRequest` companion factory may
create synthetic/internal raw rows but must not be source-callable. The literal feature set remains
the exhaustive guard for record/data-class and companion-generated rows, so this semantic test must
not erase or ignore those rows.

- [ ] **Step 7: Run all compatibility gates GREEN and commit**

```bash
./gradlew checkKotlinAbi apiCompatibilityTestClasses externalConsumerClasspathCheck
./gradlew test \
  --tests 'com.salesforce.revoman.compat.ApiBaselineInventoryTest' \
  --tests 'com.salesforce.revoman.compat.JvmSurfaceVisibilityTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --exit-code -- \
  api/cs2-baseline-revoman-root.api \
  api/cs2-baseline-revoman-root.jvm.tsv \
  api/cs2-migration-map.tsv
git diff --check
git add \
  src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinMockHttpServerApiFixture.kt \
  src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaMockHttpServerApiFixture.java \
  src/test/kotlin/com/salesforce/revoman/compat/MockHttpServerJvmSurfaceAdditions.kt \
  src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  api/revoman-root.api
git diff --cached --check
git commit -m "test: lock mock HTTP server API surface"
```

---

### Task 6: Replace the root loopback test fixture

**Files:**

- Delete: `src/test/kotlin/com/salesforce/revoman/testsupport/LoopbackHttpFixture.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/ControlFlowE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/ControlFlowLedgerE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/LedgerSkipE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/MultiKickEnvTypesE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/PmTestFailureE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/PmTestPhaseTagE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/RunbookExeE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/RunbookLegibilityE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/ScriptHookPhaseBarrierE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSessionE2ETest.kt`
- Modify when its named source changes: `detekt/baseline-source-sha256sums.txt`

**Interfaces:**

- Consumes: the finished public `MockHttpServer` and ordinary collection projections.
- Produces: all ten root E2E consumers on the public server and removal of duplicated loopback
  transport/recorder code.

- [ ] **Step 1: Delete the old fixture and establish migration RED**

Delete `LoopbackHttpFixture.kt`; its two lifecycle/recording tests are now superseded by Tasks 1-4.
Run:

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.ControlFlowE2ETest' \
  --tests 'com.salesforce.revoman.ControlFlowLedgerE2ETest' \
  --tests 'com.salesforce.revoman.LedgerSkipE2ETest' \
  --tests 'com.salesforce.revoman.MultiKickEnvTypesE2ETest' \
  --tests 'com.salesforce.revoman.PmTestFailureE2ETest' \
  --tests 'com.salesforce.revoman.PmTestPhaseTagE2ETest' \
  --tests 'com.salesforce.revoman.RunbookExeE2ETest' \
  --tests 'com.salesforce.revoman.RunbookLegibilityE2ETest' \
  --tests 'com.salesforce.revoman.ScriptHookPhaseBarrierE2ETest' \
  --tests 'com.salesforce.revoman.internal.runtime.ExecutionSessionE2ETest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Require compilation failure at the ten stale imports/usages.

- [ ] **Step 2: Apply the exact lifecycle and assertion conversions**

In all ten files, replace the import, field/local type, and factory call:

```kotlin
import com.salesforce.revoman.testing.http.MockHttpServer

private lateinit var fixture: MockHttpServer

fixture = MockHttpServer.start { Response(OK).body("{}") }
```

Apply these literal observation conversions at each occurrence:

```text
fixture.hitCount()                  -> fixture.requests().size
fixture.hitCount("/path")           -> fixture.requests().count { it.path == "/path" }
fixture.requests("/path")           -> fixture.requests().filter { it.path == "/path" }
recorded.queries                    -> recorded.queryParameters
recorded.body.decodeToString()      -> recorded.bodyString()
```

Replace `headerValues("X-Phase")` with:

```kotlin
recorded.headers
    .filter { it.name.equals("X-Phase", ignoreCase = true) }
    .mapNotNull { it.value }
```

In `ScriptHookPhaseBarrierE2ETest`, require its query assertion to use
`RecordedNameValue("phase", "one")`, and select the request with
`fixture.requests().single { it.path == "/phase-one" }`.

Keep `RunbookExeE2ETest`'s unrelated atomic `countHits` domain helper unchanged. Update KDoc links in
`ControlFlowLedgerE2ETest`, `LedgerSkipE2ETest`, and `MultiKickEnvTypesE2ETest`; say
"external-network-free" rather than "without real I/O" because the fixture uses a kernel socket.

- [ ] **Step 3: Run the ten migrated root tests GREEN**

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.ControlFlowE2ETest' \
  --tests 'com.salesforce.revoman.ControlFlowLedgerE2ETest' \
  --tests 'com.salesforce.revoman.LedgerSkipE2ETest' \
  --tests 'com.salesforce.revoman.MultiKickEnvTypesE2ETest' \
  --tests 'com.salesforce.revoman.PmTestFailureE2ETest' \
  --tests 'com.salesforce.revoman.PmTestPhaseTagE2ETest' \
  --tests 'com.salesforce.revoman.RunbookExeE2ETest' \
  --tests 'com.salesforce.revoman.RunbookLegibilityE2ETest' \
  --tests 'com.salesforce.revoman.ScriptHookPhaseBarrierE2ETest' \
  --tests 'com.salesforce.revoman.internal.runtime.ExecutionSessionE2ETest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

- [ ] **Step 4: Refresh only changed Detekt-bound source fingerprints**

Run `shasum -a 256` for every changed file already named by
`detekt/baseline-source-sha256sums.txt`. Replace exactly those hash values and retain every path and
all unaffected hashes. `detekt/baseline.xml` remains unchanged unless Detekt reports a genuinely new
finding rather than a fingerprint mismatch.

- [ ] **Step 5: Prove the duplicate fixture is gone and commit**

```bash
rg -n 'LoopbackHttpFixture' src/main src/test src/integrationTest
git diff --check
```

Expected: `rg` returns no live source match. Then:

```bash
git add \
  src/test/kotlin/com/salesforce/revoman/testsupport/LoopbackHttpFixture.kt \
  src/test/kotlin/com/salesforce/revoman/ControlFlowE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/ControlFlowLedgerE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/LedgerSkipE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/MultiKickEnvTypesE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/PmTestFailureE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/PmTestPhaseTagE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/RunbookExeE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/RunbookLegibilityE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/ScriptHookPhaseBarrierE2ETest.kt \
  src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSessionE2ETest.kt \
  detekt/baseline-source-sha256sums.txt
git diff --cached --check
git commit -m "test: use public mock server in root suites"
```

---

### Task 7: Extract the deterministic integration domain handler

**Files:**

- Modify temporarily: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt`
- Delete: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServerTest.kt`
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApi.kt`
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiTest.kt`

**Interfaces:**

- Consumes: `MockHttpHandler`, public `MockHttpServer`, existing Moshi JSON helpers, and the current
  object/Pokemon route semantics.
- Produces: a socket-free, executor-free, recorder-free, thread-safe `DeterministicMockApi` domain
  handler and real-wire domain tests. A temporary compatibility facade keeps Task 8's consumers
  compiling but delegates all transport and recording to `MockHttpServer`.

- [ ] **Step 1: Rewrite the domain test class against the public server and add concurrency RED**

Rename the test class/file. Replace each `DeterministicMockApiServer.start()` with:

```kotlin
MockHttpServer.start(DeterministicMockApi()).use { server ->
    val response =
        prepareHttpClient(insecureHttp = false)(Request(GET, "${server.baseUrl}/objects"))
    assertThat(response.status).isEqualTo(OK)
}
```

Rename the former lifecycle test to `empty object list is served through the public mock server`.
Keep its `200`, `[]`, and recorded `GET /objects` assertions; remove port, worker-name, repeated-close,
and post-close socket checks because the public contract suite owns them.

Preserve all sixteen route-behavior tests. Replace signature assertions with projections over
`server.requests()`. For the decoded query test, require this exact list:

```kotlin
assertThat(server.requests().single().queryParameters)
    .containsExactly(
        RecordedNameValue("z", "2"),
        RecordedNameValue("tag", "first"),
        RecordedNameValue("tag", "second"),
        RecordedNameValue("flag", null),
        RecordedNameValue("empty", ""),
    ).inOrder()
```

Add a test that submits 20 concurrent `POST /objects` requests, waits on every future with a
five-second timeout, extracts every returned `local-object-N`, and requires the set of IDs to equal
`1..20` exactly. Then `GET /objects` must return 20 entries. This test catches compound-state races.

- [ ] **Step 2: Run the renamed domain selector and require RED**

```bash
./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: compilation fails because `DeterministicMockApi` does not exist; the still-present old
server keeps unrelated integration consumers compilable during this focused RED/GREEN slice.

- [ ] **Step 3: Implement the domain-only handler with one state lock**

Create this class shell and move the existing Moshi/Pokemon behavior into its route handler:

```kotlin
class DeterministicMockApi : MockHttpHandler {
    private val stateLock = ReentrantLock()
    private val objects = linkedMapOf<String, Map<String, Any?>>()
    private var nextObjectId = 0

    private val routesHandler = routes(
        "/objects" bind GET to { listObjects() },
        "/objects" bind POST to { request -> createObject(request) },
        "/objects/{id}" bind PATCH to { request -> patchObject(request) },
        "/objects/{id}" bind GET to { request -> getObject(request) },
        "/objects/{id}" bind PUT to { request -> putObject(request) },
        "/pokemon" bind GET to { request -> pokemonIndex(request) },
        "/pokemon/bulbasaur" bind GET to {
            mapOf("id" to 1, "name" to "bulbasaur").toJsonResponse()
        },
        "/pokemon-species/bulbasaur" bind GET to {
            mapOf("id" to 1, "name" to "bulbasaur").toJsonResponse()
        },
    )

    override fun handle(request: Request): Response =
        routesHandler(request).let { response ->
            if (response.status == METHOD_NOT_ALLOWED) Response(NOT_FOUND) else response
        }

    private fun listObjects(): Response {
        val snapshot = stateLock.withLock {
            objects.toSortedMap().values.map { LinkedHashMap(it) }
        }
        return snapshot.toJsonResponse()
    }

    private fun createObject(request: Request): Response {
        val fields = request.toJsonObject() ?: return Response(BAD_REQUEST)
        val created = stateLock.withLock {
            val id = "local-object-${++nextObjectId}"
            linkedMapOf<String, Any?>("id" to id)
                .apply { putAll(fields - "id") }
                .also { objects[id] = it }
                .let { LinkedHashMap(it) }
        }
        return created.toJsonResponse()
    }

    private fun patchObject(request: Request): Response {
        val id = requireNotNull(request.path("id"))
        val fields = request.toJsonObject() ?: return Response(BAD_REQUEST)
        val updated = stateLock.withLock {
            objects[id]?.let { existing ->
                existing.toMutableMap()
                    .apply {
                        listOf("name", "data").forEach { field ->
                            if (field in fields) put(field, fields[field])
                        }
                    }
                    .also { objects[id] = it }
                    .let { LinkedHashMap(it) }
            }
        } ?: return objectNotFoundResponse()
        return updated.toJsonResponse()
    }

    private fun getObject(request: Request): Response {
        val id = requireNotNull(request.path("id"))
        val snapshot = stateLock.withLock { objects[id]?.let { LinkedHashMap(it) } }
            ?: return objectNotFoundResponse()
        return snapshot.toJsonResponse()
    }

    private fun putObject(request: Request): Response {
        val id = requireNotNull(request.path("id"))
        val fields = request.toJsonObject() ?: return Response(BAD_REQUEST)
        val replacement = stateLock.withLock {
            if (id !in objects) return@withLock null
            linkedMapOf<String, Any?>("id" to id)
                .apply { putAll(fields - "id") }
                .also { objects[id] = it }
                .let { LinkedHashMap(it) }
        } ?: return objectNotFoundResponse()
        return replacement.toJsonResponse()
    }

    private fun pokemonIndex(request: Request): Response =
        if (request.uri.queries() != listOf("limit" to "5")) {
            Response(NOT_FOUND)
        } else {
            mapOf(
                "results" to
                    listOf("bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon")
                        .map { name -> mapOf("name" to name) }
            ).toJsonResponse()
        }
}
```

Every object transition must occur under `stateLock.withLock`. Allocate IDs and insert the object in
the same critical section. PATCH and PUT must read, copy, and replace in one critical section. List
must copy `objects.toSortedMap().values.toList()` before releasing the lock and serializing. GET must
copy its selected map before releasing the lock. Each handler instance begins at
`local-object-1`.

Move the current `jsonAdapter`, `Request.toJsonObject`, `Any.toJsonResponse`, and
`objectNotFoundResponse` declarations unchanged into `DeterministicMockApi.kt`; these preserve
lenient Moshi parsing, malformed JSON `400`, and exact object-not-found JSON. The class itself
contains no `HttpServer`, executor, base URL, request queue, record type, count, signature, start,
or close member.

- [ ] **Step 4: Reduce the old server to a temporary public-server facade**

Replace the old file's JDK server, executor, recorder, and route implementation with this
integration-only delegation shape:

```kotlin
class DeterministicMockApiServer private constructor(
    private val server: MockHttpServer,
) : AutoCloseable {
    val baseUrl: String
        get() = server.baseUrl

    fun requestSignatures(): List<String> = server.requests().map { request ->
        buildString {
            append(request.method)
            append(' ')
            append(request.path)
            if (request.queryParameters.isNotEmpty()) {
                append('?')
                append(
                    request.queryParameters.joinToString("&") { (name, value) ->
                        if (value == null) name else "$name=$value"
                    }
                )
            }
        }
    }

    fun hitCount(path: String): Int = server.requests().count { it.path == path }

    override fun close() = server.close()

    companion object {
        @JvmStatic
        fun start(): DeterministicMockApiServer =
            DeterministicMockApiServer(MockHttpServer.start(DeterministicMockApi()))
    }
}
```

This facade exists for one commit only and is deleted after Task 8 migrates its callers. It must
contain no `HttpServer`, executor, mutable recorder, or domain route.

- [ ] **Step 5: Run domain GREEN and commit the extraction**

```bash
./gradlew spotlessApply
./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApi.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiTest.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServerTest.kt
git diff --cached --check
git commit -m "test: extract deterministic mock API handler"
```

---

### Task 8: Migrate all deterministic API examples

**Files:**

- Delete: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt`
- Modify: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevKtTest.kt`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java`
- Modify: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevKtTest.kt`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevV3Test.java`
- Modify: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/LedgerRoundTripKtTest.kt`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`

**Interfaces:**

- Consumes: Task 7's `DeterministicMockApi` and the public server's `baseUrl`/`requests()`.
- Produces: every V2/V3 REST, ledger, and Pokemon example on the shipped transport with local
  collection-based assertions.

- [ ] **Step 1: Establish consumer RED after the fixture split**

Delete the temporary `DeterministicMockApiServer.kt` facade, then run:

```bash
./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevV3Test' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.LedgerRoundTripKtTest' \
  --tests 'com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Require compilation failure at imports and calls to the deleted facade; this proves all stale
consumers are in the gate.

- [ ] **Step 2: Convert the Kotlin examples**

Replace the old fixture import with both
`com.salesforce.revoman.integration.testsupport.DeterministicMockApi` and
`com.salesforce.revoman.testing.http.MockHttpServer`.

For V2 and V3, apply these exact ownership replacements while leaving each existing `Kick`
configuration expression byte-for-byte unchanged except for its base-URL variable:

```diff
- DeterministicMockApiServer.start().use { api ->
+ val api = DeterministicMockApi()
+ MockHttpServer.start(api).use { server ->

- api.baseUrl
+ server.baseUrl

- api.requestSignatures()
+ server.requests().map { "${it.method} ${it.path}" }
```

Keep the existing four exact expected strings. Apply the same replacement in both ledger scopes;
the warm ledger test intentionally keeps one handler and one server across cold and warm
executions, then requires its existing cumulative seven method/path entries. It must not reset the
public recorder.

- [ ] **Step 3: Convert the Java examples and preserve documentation tags**

Replace the old fixture import with `DeterministicMockApi`, `MockHttpServer`, and, in the Pokemon
test, `RecordedNameValue`.

Use these exact ownership and projection replacements in V2 and V3 Java tests while leaving the
existing `Kick` call chain unchanged except for `server.getBaseUrl()`:

```diff
- try (final var api = DeterministicMockApiServer.start()) {
+ final var api = new DeterministicMockApi();
+ try (final var server = MockHttpServer.start(api)) {

- api.getBaseUrl()
+ server.getBaseUrl()

- api.requestSignatures()
+ server.requests().stream()
+     .map(request -> request.getMethod() + " " + request.getPath())
+     .toList()
```

Keep `// tag::revoman-simple-demo[]` and `// end::revoman-simple-demo[]` around a complete compiling
example.

For Pokemon, retain its five method/path assertions and separately require the first request's query
list to contain exactly `new RecordedNameValue("limit", "5")`. Preserve `pm-sandbox-asserts` tags.

- [ ] **Step 4: Run all seven consumer methods GREEN**

```bash
./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevV3Test' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.LedgerRoundTripKtTest' \
  --tests 'com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

- [ ] **Step 5: Prove no live old domain server remains and commit**

```bash
rg -n 'DeterministicMockApiServer' src/main src/test src/integrationTest README.adoc docs/modules
git diff --check
```

At this stage the source tree must have no match; current docs are updated in Task 9, so a README or
Antora prose match is allowed only until that task. Then:

```bash
git add \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevKtTest.kt \
  src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevKtTest.kt \
  src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevV3Test.java \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/LedgerRoundTripKtTest.kt \
  src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java
git diff --cached --check
git commit -m "test: migrate examples to public mock server"
```

---

### Task 9: Publish the feature and run every completion gate

**Files:**

- Modify: `README.adoc:48-68`
- Modify: `docs/modules/ROOT/pages/getting-started.adoc:3-4,41-60`
- Modify: `docs/modules/ROOT/pages/index.adoc:19`
- Verify included region: `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java:24-65`
- Verify included region: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`
- Modify changed entries only: `detekt/baseline-source-sha256sums.txt`

**Interfaces:**

- Consumes: the finished API, migrated tagged Java example, all focused gates, and repository
  publication/static-analysis workflows.
- Produces: public documentation, complete local evidence, mutation evidence for critical
  boundaries, and a clean committed feature branch ready for review.

- [ ] **Step 1: Update README and Antora prose with the shipped composition**

Explain that `MockHttpServer` is shipped by ReVoman while `DeterministicMockApi` is this repository's
application-specific example handler. The code must show explicit URL injection and resource
ownership:

```java
final var api = new DeterministicMockApi();
try (final var server = MockHttpServer.start(api)) {
  final var rundown =
      ReVoman.revUp(
          Kick.configure()
              .templatePath("pm-templates/v2/restfulapidev/restful-api.dev.postman_collection.json")
              .environmentPath("pm-templates/v2/restfulapidev/restful-api.dev.postman_environment.json")
              .dynamicEnvironment("baseUrl", server.getBaseUrl())
              .nodeModulesPath("js")
              .off());
}
```

State that handlers may run concurrently and mutable state must be thread-safe, bodies are buffered
in memory, the listener is test-only exact IPv4 loopback, and blocking handlers must cooperate with
interruption during close. Do not describe the server as implicitly discovered or started by
ReVoman.

Update `getting-started.adoc` around its existing Java include and update the `index.adoc` comment
from a private loopback fixture to public-server/domain-handler composition. Leave
`scripts-and-pm-apis.adoc` content unchanged because its Pokemon tagged region does not show server
setup; verify the tag still resolves.

- [ ] **Step 2: Run the focused feature and migration suites together**

```bash
./gradlew test \
  --tests 'com.salesforce.revoman.testing.http.*' \
  --tests 'com.salesforce.revoman.ControlFlowE2ETest' \
  --tests 'com.salesforce.revoman.ControlFlowLedgerE2ETest' \
  --tests 'com.salesforce.revoman.LedgerSkipE2ETest' \
  --tests 'com.salesforce.revoman.MultiKickEnvTypesE2ETest' \
  --tests 'com.salesforce.revoman.PmTestFailureE2ETest' \
  --tests 'com.salesforce.revoman.PmTestPhaseTagE2ETest' \
  --tests 'com.salesforce.revoman.RunbookExeE2ETest' \
  --tests 'com.salesforce.revoman.RunbookLegibilityE2ETest' \
  --tests 'com.salesforce.revoman.ScriptHookPhaseBarrierE2ETest' \
  --tests 'com.salesforce.revoman.internal.runtime.ExecutionSessionE2ETest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevV3Test' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.LedgerRoundTripKtTest' \
  --tests 'com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

- [ ] **Step 3: Run five manual mutation checks and restore after each RED**

Make one local mutation at a time, run the named focused test, require the stated RED, and restore
the mutation with `apply_patch` before proceeding:

```text
127.0.0.1 bind -> wildcard bind       MockHttpServerStartupTest or wire contract rejects address
virtual executor -> platform executor MockHttpServerConcurrencyTest rejects non-virtual handler
omit ledger publication               wire/snapshot contract reports missing request
swallow close handler failures        MockHttpServerFailureTest misses required aggregate
omit shutdownNow after timeout        MockHttpServerLifecycleTest misses forced interruption
```

After restoration, rerun `./gradlew test --tests 'com.salesforce.revoman.testing.http.*'` and require
GREEN.

- [ ] **Step 4: Run formatting, ABI, static analysis, and full build**

```bash
./gradlew spotlessApply
./gradlew checkKotlinAbi apiCompatibilityTestClasses externalConsumerClasspathCheck
./gradlew test \
  --tests 'com.salesforce.revoman.compat.ApiBaselineInventoryTest' \
  --tests 'com.salesforce.revoman.compat.JvmSurfaceVisibilityTest' \
  --tests 'com.salesforce.revoman.compat.DetektBaselineIntegrityTest'
./gradlew detekt spotlessCheck
./gradlew build
git diff --exit-code -- \
  api/cs2-baseline-revoman-root.api \
  api/cs2-baseline-revoman-root.jvm.tsv \
  api/cs2-migration-map.tsv
```

If Detekt reports only source-ledger drift, refresh the exact changed named hashes with
`shasum -a 256`, rerun `DetektBaselineIntegrityTest`, then rerun `detekt`. Do not regenerate or weaken
`detekt/baseline.xml` to hide new findings.

- [ ] **Step 5: Build the published documentation**

```bash
npm ci
npx antora antora-playbook.yml
```

Require zero missing includes/tags and inspect generated getting-started output for the public
`MockHttpServer` imports, `server.getBaseUrl()`, and try-with-resources block.

- [ ] **Step 6: Commit documentation and verified fingerprints**

```bash
git add \
  README.adoc \
  docs/modules/ROOT/pages/getting-started.adoc \
  docs/modules/ROOT/pages/index.adoc \
  detekt/baseline-source-sha256sums.txt
git diff --cached --check
git commit -m "docs: publish mock HTTP server examples"
```

Omit `detekt/baseline-source-sha256sums.txt` from `git add` if it is unchanged. Require
`git status --short` to be empty after the commit; if formatting or a required gate changed another
intentional feature file, stage that exact file and include it in this verified checkpoint.

- [ ] **Step 7: Run Qodana from the committed tree**

Precompile exactly as `DEVELOPMENT.md` requires:

```bash
colima start
./gradlew kaptKotlin classes \
  :benchmark-driver:kaptKotlin \
  :benchmark-driver:classes
./gradlew qodanaScan
```

If Qodana cannot consume a linked worktree `.git` file, create a disposable ordinary clone at the
exact `HEAD` SHA, run the same precompile and scan there, and record the SHA. Do not skip Qodana or
scan a different tree.

- [ ] **Step 8: Perform the final scope audit**

```bash
rg -n 'LoopbackHttpFixture|DeterministicMockApiServer' \
  src/main src/test src/integrationTest README.adoc docs/modules
git diff --exit-code origin/master -- \
  benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/fixture/DeterministicHttpFixture.kt \
  api/cs2-baseline-revoman-root.api \
  api/cs2-baseline-revoman-root.jvm.tsv \
  api/cs2-migration-map.tsv
git diff --check
git status --short --branch
```

Expected: no live old-fixture references; benchmark and frozen compatibility files have no diff;
no whitespace errors; and a clean worktree.

- [ ] **Step 9: Request independent code review before integration**

Use `superpowers:requesting-code-review` from the `origin/master` merge base through `HEAD`. Require
reviewers to check
the approved public surface, failure taxonomy, lifecycle linearization, immutable snapshots,
external Java/Kotlin compilation, exact raw-JVM additions, complete fixture migration, and unchanged
benchmark/frozen ABI files. Address accepted findings with `superpowers:receiving-code-review`, rerun
the affected focused gate plus `./gradlew build`, and commit each correction separately.

Do not push, merge, remove the worktree, or modify the main checkout unless the user explicitly
authorizes that integration action.

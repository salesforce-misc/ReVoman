# Public Mock HTTP Server Design

**Date:** 2026-08-15

**Status:** Approved for implementation

**Base commit:** `b4a643d7`

**Target:** Existing `com.salesforce.revoman:revoman` artifact on Java 21+

## Summary

ReVoman will ship a small, generic, real-wire mock HTTP server for examples and consumer tests.
The public module will accept a Java-friendly handler backed by http4k request and response types,
bind an ephemeral port on exact IPv4 loopback, run concurrent exchanges on owned Java 21 virtual
threads, record immutable request snapshots, release the listener deterministically, and perform a
bounded shutdown of every owned executor task.

The feature extracts the reusable transport and recorder responsibilities currently duplicated by
`DeterministicMockApiServer` and `LoopbackHttpFixture`. Domain behavior remains in test handlers.
All non-benchmark repository examples that currently start either fixture will use the shipped
feature, including the Java and Kotlin RESTful API examples named in the request. ReVoman execution
remains independent: callers explicitly inject the server's `baseUrl` into a Kick or runbook.

This is a recorder, not an expectation framework. Its deliberately small observation surface is
one point-in-time request snapshot list; callers use their existing assertion libraries and
collection operations for matching, counting, and verification.

## Goals

1. Give Java and Kotlin consumers a supported mock server in the existing ReVoman library.
2. Exercise the production HTTP stack through a real kernel socket rather than direct handler
   invocation.
3. Contain listening to exact `127.0.0.1` on an OS-selected ephemeral port.
4. Preserve immutable method, path, query, header, and body evidence without exposing a live
   http4k request body.
5. Support concurrent requests with lightweight, server-owned Java 21 virtual threads.
6. Turn unexpected handler failures into client-visible `500` responses and deterministic
   teardown failures.
7. Remove the repository's two copies of loopback lifecycle and request-recording logic.
8. Keep the new public JVM surface intentional, Java-friendly, ABI-checked, and documented.

## Non-goals

- No expectation, assertion, stubbing, verification, or request-matching DSL.
- No built-in counts, signatures, filtering, reset, checkpoint, or await-until operations.
- No TLS, fixed ports, non-loopback binding, custom executors, or public shutdown tuning.
- No suspend handler and no `kotlinx-coroutines` dependency.
- No JSON fixtures, domain simulation, or ReVoman/Kick-specific server behavior.
- No transport service-provider interface while only the JDK server implementation exists.
- No migration of the specialized benchmark-driver server.
- No Java 25 requirement or unrelated platform migration.
- No production or internet-facing server use; requests are buffered in memory and the listener is
  intentionally test-only loopback infrastructure.

## Selected architecture

Add a public package-level module, not a new Gradle subproject, at:

```text
src/main/kotlin/com/salesforce/revoman/testing/http/
├── MockHttpHandler.kt
├── MockHttpServer.kt
├── RecordedHttpRequest.kt
└── RecordedNameValue.kt
```

These four types are the entire public feature. `MockHttpServer` hides the JDK `HttpServer`, exact
bind checks, http4k exchange adapter, virtual-thread executor, concurrent request ledger, replayable
body conversion, failure ledger, and shutdown protocol. None of those implementation types leak
through the public API.

The module lives in the current artifact because http4k is already an API dependency and a mock
server directly supports the library's documented execution examples. A new artifact would add
dependency and release complexity without isolating any new third-party dependency.

There is no public transport interface. A single implementation behind one concrete facade keeps
the module deep and leaves freedom to change its internals. A transport abstraction can be added
later if a second implementation creates a real substitution need.

## Public API

The following API-signature sketch is not implementation source: it lists every public operation
and construction boundary while omitting private state. Both concrete classes have private
constructors, and `RecordedHttpRequest` is created only by a non-public factory owned by the server.

```kotlin
package com.salesforce.revoman.testing.http

import java.nio.charset.Charset
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response

fun interface MockHttpHandler {
  @Throws(Exception::class)
  fun handle(request: Request): Response
}

@JvmRecord
data class RecordedNameValue(
  val name: String,
  val value: String?,
)

class RecordedHttpRequest private constructor() {
  val method: Method
  val path: String
  val queryParameters: List<RecordedNameValue>
  val headers: List<RecordedNameValue>

  fun bodyBytes(): ByteArray

  @JvmOverloads
  fun bodyString(charset: Charset = Charsets.UTF_8): String
}

class MockHttpServer private constructor() : AutoCloseable {
  val baseUrl: String

  fun requests(): List<RecordedHttpRequest>

  override fun close()

  companion object {
    @JvmStatic
    fun start(handler: MockHttpHandler): MockHttpServer
  }
}
```

`MockHttpHandler` is ReVoman-owned so Java receives an ordinary SAM instead of Kotlin's
`Function1`. Kotlin may pass a lambda directly or adapt an existing http4k handler with
`MockHttpHandler(app)`; Java may pass a lambda directly. A second `HttpHandler` overload is omitted
because it would add ambiguous Kotlin lambda overloads and does not improve the Java boundary.

`RecordedNameValue` replaces Kotlin `Pair` in public collections so Java callers receive named
record accessors. Query parameters preserve URI order, duplicates, a missing value (`null`), and an
explicit empty value (`""`) after decoding. Header snapshots retain every name/value occurrence
exposed by the http4k adapter, but callers must not rely on header-name casing or global header
ordering because HTTP and the JDK transport do not guarantee them.

`path` is the http4k-adapted URI path, begins with `/`, and excludes the query string. `baseUrl` is
a stable origin without a trailing slash before and after close.

`RecordedHttpRequest` never exposes its internal body array. `bodyBytes()` returns a fresh copy on
every call, and `bodyString()` decodes that immutable captured content. The request's collections
are unmodifiable. Neither Kotlin nor Java consumers can construct a recorded request.

`requests()` returns an unmodifiable point-in-time snapshot. Later traffic does not mutate a
previously returned list or any request already in it. Matching, filtering, signatures, and hit
counts are intentionally ordinary collection operations outside the server.

## Binding and startup

`start()` performs the following transaction:

1. Create an owned virtual-thread-per-task executor.
2. Create a JDK HTTP server requested at literal `127.0.0.1:0`.
3. Install one root exchange adapter backed by the supplied `MockHttpHandler`.
4. Start the server and read its actual bound address.
5. Prove that the actual address is exact IPv4 loopback and that the selected port is nonzero.
6. Return only after the listener is ready, with `baseUrl` formatted as
   `http://127.0.0.1:<selected-port>`.

If any step fails, startup stops any partially created server, shuts down its executor, and throws
`IllegalStateException` with the original failure as its cause. Startup does not expose a checked
exception or a public exception hierarchy.

The exact address and ephemeral-port policy is fixed rather than configurable. It prevents
accidental LAN exposure, removes port collisions between parallel tests, and gives tests one
portable contract.

## Request flow and concurrency

For each exchange, the adapter:

1. Materializes the incoming body exactly once.
2. Captures method, path, decoded query occurrences, header occurrences, and copied body bytes.
3. Assigns an internal monotonic capture ordinal and appends the immutable record.
4. Reconstructs a replayable http4k `Request` containing the captured body.
5. Calls the user's handler.
6. Writes the resulting http4k `Response` through the real socket.

Recording occurs after the body is materialized and before the handler runs. Consequently failed
requests remain observable and handlers may read the body normally. Capture order, not kernel
arrival or response-completion order, defines the order returned by `requests()`. Sequential calls
therefore have exact order; concurrent tests should assert only ordering their synchronization
establishes. Ordinal assignment and ledger publication form one linearization point, and
`requests()` snapshots linearize against that same ledger so they contain no partial record or
ordinal reordering.

Each accepted exchange runs on an owned Java 21 virtual thread. The server may invoke the handler
concurrently. Mutable handler or domain-fixture state is the caller's responsibility and must be
thread-safe. This rule is part of the public KDoc and examples. The server's ledgers, ordinal
assignment, snapshots, and lifecycle state are thread-safe internally.

Coroutines are not used in version 1. The handler contract and http4k adapter are synchronous, so a
coroutine bridge would add a dependency, dispatcher ownership, cancellation rules, and blocking
bridges without providing a suspend API. Java 21 virtual threads supply the required concurrency
directly. Running the same Java 21-targeted library on Java 25 may receive JVM runtime improvements
without raising the consumer baseline; a broader Java 25 migration needs its own design.

## Failure semantics

An explicit handler response is always normal, including any `4xx` or `5xx` status.

If the handler throws an `Exception`, or Java violates the non-null contract by returning `null`,
the server:

- retains the already captured request;
- stores the failure with its capture ordinal;
- logs the method and path without logging request headers or body; and
- returns a sanitized empty `500 Internal Server Error` to the client when the exchange remains
  writable.

The server catches `Exception`, not `Throwable`, at the handler boundary. Every `Error`, including
`AssertionError`, `LinkageError`, `ThreadDeath`, and `VirtualMachineError`, escapes the handler task
without conversion or aggregation. A body-capture or snapshot-construction exception publishes no
partial request. A later replay exception retains any complete snapshot already published. In
either case the handler is not called, an empty `500` is attempted if the exchange remains
writable, and every failure visible at ReVoman's recording/handler boundary is logged rather than
added to the handler-failure aggregate. The public http4k `HttpExchangeHandler` catches
response-write and other adapter-owned `Exception`s without exposing a callback; those failures
retain http4k's empty-500 and exchange-cleanup behavior but cannot be logged or aggregated by
ReVoman without copying that adapter. ReVoman must use the public adapter rather than maintain a
divergent copy.

On `close()`, retained handler failures are sorted by capture ordinal. Teardown throws one
`IllegalStateException` describing the failure count. The earliest request failure is its primary
cause. Each later original exception is attached directly to that aggregate as a suppressed
exception in capture order. Any shutdown failure joins the same aggregate after handler failures;
if there are no handler failures, the shutdown failure becomes primary.

This deferred propagation lets a test exercise the client's real response path while ensuring an
unexpected server-side exception cannot silently pass. Kotlin `use` and Java try-with-resources
retain their standard suppression behavior when the test body and teardown both fail.

## Shutdown and post-close state

The first `close()` call:

1. Atomically transitions the server out of the running state.
2. Stops the JDK server with zero transport delay so it accepts no new exchanges and releases the
   listener.
3. Shuts down the virtual-thread executor and waits up to five seconds for in-flight handler work.
4. Interrupts remaining work and waits up to five more seconds.
5. Restores the caller's interrupt flag if teardown is interrupted.
6. Attempts every remaining cleanup action before reporting aggregated failures.

Resource cleanup is fail-closed and completes as far as possible even if one teardown operation
fails. The timeout is an internal safety bound, not public configuration.

Java interruption is cooperative: `shutdownNow()` cannot kill a handler that ignores interruption.
After the second five-second wait, `close()` reports a shutdown failure while the listener remains
released; public KDoc requires blocking handlers to cooperate with interruption, and tests must not
claim that an arbitrary non-cooperative virtual thread can be forcibly terminated.

Closing is idempotent: only the first call owns teardown and reports retained failures. A concurrent
close waits for that teardown to finish and then returns without repeating its failure; calls made
after closure are no-ops. `baseUrl` and recorded snapshots remain readable after close, but the
server cannot be restarted and the old URL refuses new connections. Start and stop events are
logged at debug level.

## Repository migration

### Domain fixture split

The integration-only `DeterministicMockApiServer` becomes a domain-only
`DeterministicMockApi : MockHttpHandler`. It retains deterministic object/Pokemon routing, state,
and any domain-specific request assertions, but owns no socket, executor, base URL, or generic
recorder. Its mutable state must be made safe for concurrent handler invocation.

Integration tests start the public server around that handler:

```kotlin
val api = DeterministicMockApi()
MockHttpServer.start(api).use { server ->
  // inject server.baseUrl into ReVoman and assert server.requests()
}
```

Java tests use try-with-resources and the same public API. The following examples migrate:

- V2 `RestfulAPIDevKtTest.kt` and `RestfulAPIDevTest.java`;
- V3 Kotlin and Java RESTful API tests;
- both ledger round-trip executions; and
- `PokemonSandboxApiTest.java`.

The domain fixture contract tests continue to drive the handler over a real public
`MockHttpServer`, so route behavior and transport composition remain covered together.

### Root test fixture removal

Delete `src/test/.../testsupport/LoopbackHttpFixture.kt` after its contract tests move to the public
module and these consumers use `MockHttpServer`:

- `ControlFlowE2ETest`;
- `ControlFlowLedgerE2ETest`;
- `LedgerSkipE2ETest`;
- `MultiKickEnvTypesE2ETest`;
- `PmTestFailureE2ETest`;
- `PmTestPhaseTagE2ETest`;
- `RunbookExeE2ETest`;
- `RunbookLegibilityE2ETest`;
- `ScriptHookPhaseBarrierE2ETest`; and
- `internal.runtime.ExecutionSessionE2ETest`.

Their custom routes remain local http4k handlers; their lifecycle and request evidence come from
the public server.

The benchmark-driver server remains unchanged because it is specialized benchmark infrastructure,
not duplicated consumer/demo support.

### Documentation

Replace README code that currently imports the integration-only `DeterministicMockApiServer` with
a compilable public `MockHttpServer` example. Update
`docs/modules/ROOT/pages/getting-started.adoc` and its included Java integration-test tag so the
published getting-started example demonstrates the same shipped API. Documentation distinguishes
the generic server from the application-specific deterministic handler used by this repository.
Examples show explicit `baseUrl` injection, try-with-resources/`use`, and thread-safe handler state.
They do not suggest that ReVoman starts or discovers the server implicitly.

## Compatibility contract

This is an additive public API in the existing artifact. Implementation must:

- update the active Kotlin ABI dump at `api/revoman-root.api`;
- leave frozen historical ABI and raw-JAR baselines byte-identical;
- extend the exact active-minus-frozen raw-JAR addition allowlist with a feature-owned set rather
  than misclassifying the additions in the CS2 migration ledger;
- add Kotlin and Java external-consumer fixtures under `src/apiCompatibilityTest`; and
- compile those consumers against the built JAR, not the main source-set output.

Consumer fixtures must prove Kotlin direct-lambda use, Kotlin adaptation of an existing http4k
handler, Java direct-lambda use, `AutoCloseable`, Java record accessors, recorded body access, and
the server/request collection types.

## Verification strategy

Implementation follows test-driven development. Public contract tests are added before production
types, then advanced in focused vertical slices: startup, request/response conversion, recording,
concurrency, failure propagation, and shutdown. Migration tests change before their old fixtures
are removed.

The public server contract suite uses real HTTP clients and kernel sockets to prove:

- exact IPv4 loopback binding, nonzero ephemeral ports, and two simultaneous distinct listeners;
- methods, paths, ordered duplicate/null/empty queries, repeated headers, binary bodies, and
  response status/header/body conversion;
- single materialization plus replayable handler bodies;
- immutable point-in-time request lists, unmodifiable collections, and defensive body copies;
- concurrent handler invocation using barriers or latches rather than timing sleeps;
- capture ordering established by explicit synchronization;
- thrown exceptions becoming empty `500` responses and deterministic close aggregation;
- explicit returned `500` responses not poisoning close;
- idempotent close, listener refusal, in-flight cleanup, and worker termination; and
- startup bind/start cleanup through a deterministic non-public lifecycle test seam.

Repository verification includes focused public contract tests, all migrated root tests, the full
integration suite, external Java/Kotlin API consumers, `checkKotlinAbi`, raw-JAR compatibility
tests, `build`, Spotless, Detekt, Qodana, and Antora/documentation validation. Tests must avoid
arbitrary sleeps and must not depend on header ordering that HTTP does not guarantee.

## Rejected alternatives

1. **Keep a fixed domain server as the public feature.** Rejected because it exports repository
   demo behavior instead of reusable capability and leaves generic transport duplicated.
2. **Accept public http4k `HttpHandler` directly.** Rejected because its Kotlin function type is
   awkward from Java; the ReVoman SAM keeps Java lambda use ordinary while preserving http4k data
   types.
3. **Ship a builder, request journal, filters, waits, and verification helpers.** Rejected because
   no approved use case needs the broader surface, and each method becomes long-lived ABI.
4. **Expose signatures and hit counts on the server.** Rejected because they are shallow
   projections of immutable requests and force policy for paths, queries, and matching semantics.
5. **Use coroutines for concurrency.** Rejected because the public handler is synchronous and Java
   21 virtual threads meet the requirement without another runtime or lifecycle model.
6. **Require Java 25.** Rejected because this feature needs no Java 25-only API; Java 21 already
   supplies stable virtual threads, while relevant Java 25 structured-concurrency APIs remain an
   unsuitable basis for this public lifecycle.
7. **Create a separate artifact.** Rejected because no dependency boundary is gained and examples
   would require additional publication and dependency setup.

## Success criteria

The feature is complete when:

- consumers can start the public server from Java and Kotlin using the documented API;
- real requests are contained to exact IPv4 loopback and recorded as immutable snapshots;
- concurrent requests, handler failures, and shutdown obey this specification;
- every non-benchmark repository loopback example uses the public feature;
- `LoopbackHttpFixture` and `DeterministicMockApiServer` are removed, with only the domain-specific
  `DeterministicMockApi` retained in integration tests;
- README/current documentation references only APIs actually shipped in the artifact;
- active ABI, exact raw-JAR additions, and external consumer gates agree; and
- focused, full, static-analysis, Qodana, and documentation gates pass from one committed tree.

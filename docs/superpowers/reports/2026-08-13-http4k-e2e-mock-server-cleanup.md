# http4k E2E mock-server cleanup

## Outcome

Task 9 replaces twelve private JDK `HttpServer` contexts in ten root E2E test classes with one
test-only real-wire `LoopbackHttpFixture`. The fixture still binds a real kernel socket at the exact
IPv4 loopback address and an ephemeral port, but adapts the request to the existing http4k
`HttpHandler` contract through the public `HttpExchangeHandler` API. No production source,
dependency, ABI surface, benchmark identity, Task 8 evidence, or benchmark-driver code changed.

The work started from clean Task 8 implementation head
`df5886a8a9f0660aa5facbc0b78930f6312a035f`. The user explicitly authorized this separate task
while Task 8's exact external integration gate remained blocked by the documented
`restful-api.dev` public daily quota. This cleanup does not satisfy or weaken that outstanding gate.

## Adapter spike and fixture contract

The resolved `http4k-core` version is `6.57.1.0`. Context7 documentation and the resolved source JAR
were checked before selecting the adapter. Stock `SunHttp` was rejected because it constructs an
`InetSocketAddress(port)` wildcard bind and owns a work-stealing executor whose immediate stop mode
does not provide the fixture's required deterministic executor shutdown.

The selected fixture therefore owns:

- a JDK `HttpServer` bound to literal `127.0.0.1:0`;
- one root context backed by http4k's public `HttpExchangeHandler`;
- a uniquely named executor whose non-daemon workers are shut down and awaited on close;
- a concurrent request ledger with exact total and per-path counts; and
- one-time request-body materialization followed by a replayable body for the downstream handler.

The focused contract sends traffic through ReVoman's production HTTP client and the real socket. It
asserts method, decoded ordered queries, repeated request and response headers, raw binary body,
status, response body, exact counts, simultaneous ephemeral ports, loopback-only binding, socket
refusal after close, and worker termination. Its RED/GREEN evidence was:

- missing fixture: test compilation failed as expected;
- consumed streaming body: the downstream handler returned `500`, proving that recording without
  replay was invalid;
- replayable implementation: `2/2` fixture contract tests passed.

Every required mutant was applied to its production test-fixture call site, executed with the
focused selector, observed RED, and immediately reverted:

| Mutant | Executed rejection |
|---|---|
| `/fail` route renamed to `/renamed` | `1/1` failed: expected `500`, observed fallback `200` |
| `/fail` status changed from `500` to `200` | `1/1` failed on the exact status assertion |
| `/fail` body changed from `boom` to `mutant` | `1/1` failed on the exact body assertion |
| executor shutdown and bounded await removed | fixture contract failed because the owned non-daemon worker remained alive |
| live server replaced by a stopped/no-socket handler endpoint | `2/2` failed with connection-refused `503` responses |
| bind changed from loopback to wildcard | fixture contract failed on `0:0:0:0:0:0:0:0` versus `127.0.0.1` |
| ephemeral port `0` changed to fixed port `31415` | simultaneous-fixture contract failed with `BindException` |
| request-ledger insertion removed | `2/2` failed on missing request and exact-count assertions |

Each focused mutant command ended `BUILD FAILED` as required. The restored implementation then ran
the complete focused and root gates below.

## Twelve-context migration inventory

| Class | Previous contexts | Preserved handler behavior |
|---|---:|---|
| `ControlFlowE2ETest` | 1 | catch-all `200 {}` plus per-path counts |
| `ControlFlowLedgerE2ETest` | 1 | catch-all `200 {}` plus per-path counts |
| `LedgerSkipE2ETest` | 1 | catch-all `200 {}` plus total counts |
| `MultiKickEnvTypesE2ETest` | 1 | concurrent-capable catch-all `200 {}` |
| `PmTestFailureE2ETest` | 1 | catch-all `200 {}` |
| `PmTestPhaseTagE2ETest` | 1 | catch-all `200 {}` |
| `RunbookExeE2ETest` | 3 | `/` `200 {}`, `/fail*` `500` error JSON, `/count*` counted `200 {}` |
| `RunbookLegibilityE2ETest` | 1 | catch-all `200 {}` |
| `ScriptHookPhaseBarrierE2ETest` | 1 | exact POST/query/header/body capture and JSON echo; `202` for `/phase-one` |
| `ExecutionSessionE2ETest` | 1 | test-local catch-all `200 {}` |

The first bounded migration group passed `19/19` tests after the refactor. The second group first
established a pre-change `28/28` baseline, then passed the same `28/28` combined fixture and E2E
tests after migration. Both groups passed scoped static review; the first also received an
independent Standards and Spec PASS with no Critical or Important finding.

## Integrity boundaries

The three migrated tests already covered by Task 8's Detekt source-fingerprint inventory have only
their existing SHA-256 rows refreshed after final formatting. `detekt/baseline.xml` is unchanged.
`DetektBaselineIntegrityTest` remains the authoritative completeness and byte-integrity gate.

The excluded benchmark fixture's before- and after-task SHA-256 values are byte-identical:

```text
before  940f8a9e9a7007bb341afb32549eb4a5f8f456f4419b082980de1415d46636f5
after   940f8a9e9a7007bb341afb32549eb4a5f8f456f4419b082980de1415d46636f5
```

## Final verification

The exact fixture plus ten-class selector passed `52/52` tests in 22.5 seconds after all migrations:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :test \
  --tests 'com.salesforce.revoman.testsupport.LoopbackHttpFixtureContractTest' \
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

The refreshed Detekt fingerprint inventory passed both integrity tests. The complete root gate then
passed `834/834` tests in 7 minutes 36 seconds (`BUILD SUCCESSFUL` in 7 minutes 52 seconds); the same invocation also passed Detekt, both external
consumer compilers, Kotlin ABI validation, and Spotless:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :test compileApiCompatibilityTestKotlin compileApiCompatibilityTestJava \
  checkKotlinAbi detekt spotlessCheck \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

The IntelliJ index was synchronized after the external edits. Closed-file batch diagnostics found
zero errors in all eleven changed Kotlin files, and the prior Gradle run reported no build or test
failure. The fixed ten-class range contains no `com.sun.net.httpserver`, `HttpServer.create`, or
`createContext` reference. `git diff --check` passed.

Independent bounded reviews passed the shared fixture, each migration group, and the final routing
and lifecycle behavior. The final fixed-range Standards and Spec reviews both returned PASS with no
Critical or Important finding before the separate commit.

No remote host, privileged command, benchmark measurement, push, or Task 8 evidence path was used.

## Limitations

This fixture intentionally remains root-test infrastructure. The benchmark driver's
`DeterministicHttpFixture` keeps its purpose-built bind, executor, response-byte, counter, shutdown,
distribution, and benchmark-evidence contract unchanged. The shared fixture also deliberately
asserts logical repeated-header values rather than wire casing or global header order, which HTTP
does not guarantee.

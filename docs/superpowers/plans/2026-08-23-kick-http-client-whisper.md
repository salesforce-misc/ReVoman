# Kick `httpClient` (whisper) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional `Kick.httpClient(HttpHandler)` so one `revUp` can run in-process against a stub instead of Apache, without binding a port.

**Architecture:** At the start of `revUpInternal`, resolve a single `org.http4k.core.HttpHandler`: `kick.httpClient()` if present, otherwise `prepareHttpClient(kick.insecureHttp())`. Pass that handler into `fireHttpRequest` and `executePolling` (replace their `insecureHttp: Boolean` parameter). Unset `httpClient` keeps today’s secure Apache client. `MockHttpServer` loopback tests stay unchanged.

**Tech Stack:** Kotlin / JDK 21, Immutables `KickDef` (`Kick.configure()` / `.off()`, `depluralize = true`), http4k 6.58 `HttpHandler`, JUnit 5, Truth (E2E) and Kotest matchers (`KickTest`, `PollingTest`), Gradle `./gradlew test`.

## Global Constraints

Copied from `docs/superpowers/specs/2026-08-23-kick-http-client-whisper-design.md` (Approved):

- Public seam is **only** `Kick.httpClient(HttpHandler)`. Type is `org.http4k.core.HttpHandler`. No `MockHttpHandler` overload, no `httpMode`, no recording wrapper, no fake delay.
- `insecureHttp()` default stays **false**. When `httpClient` is set, **ignore** `insecureHttp()` and do not call `prepareHttpClient`.
- Scope is that `revUp` / that Kick instance. Not a static process override.
- Handler throws → existing `HttpRequestFailure` (polling: existing `PollingRequestFailure`). Java `null` return → NPE on `invoke`, wrapped the same way. Do **not** map client-side null to HTTP 500 (`MockHttpServer` owns that).
- Env still needs an absolute URI after overlay. In-process tests use dummy `baseUrl` `http://whisper.invalid` (RFC 2606 `.invalid`).
- **Do not** add, move, or delete JMH, kotlinx-benchmarks, or `src/jmh`. **Do not** shrink `MockHttpServer`. **Do not** implement `pm.sendRequest`.
- Nullable Kick style: `fun nodeModulesPath(): String?` has no `@Value.Default`; absent builder value is `null`. Same for `httpClient()`.
- Generated setter name is `.httpClient(...)` (`depluralize = true`).
- Log once with `RevomanLog.info` when a custom handler is selected.
- Test names use backticks. Copyright banner matching sibling files.
- Production Kick with no `httpClient` must stay behavior-identical (existing `ControlFlowE2ETest` + other `MockHttpServer` tests).

---

## File structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt` | Optional `httpClient(): HttpHandler?` next to `insecureHttp()` |
| `src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt` | `resolveHttpClient`; `fireHttpRequest` takes `HttpHandler` |
| `src/main/kotlin/com/salesforce/revoman/internal/exe/Polling.kt` | `executePolling` takes `HttpHandler` instead of `Boolean` |
| `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` | Resolve once in `revUpInternal`; thread through `executeStepsSerially` → `runStep` |
| `src/test/kotlin/com/salesforce/revoman/input/config/KickTest.kt` | Default null; setter round-trip; `insecureHttp()` still false |
| `src/test/java/com/salesforce/revoman/input/config/KickHttpClientJavaTest.java` | Java lambda on the builder |
| `src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt` | Kick-level whisper: invoke, throw, ignore `insecureHttp()`, polling |
| `src/test/kotlin/com/salesforce/revoman/internal/exe/PollingTest.kt` | Pass `HttpHandler`; delete `mockkStatic(::prepareHttpClient)` |

Existing collection (do **not** add a new one): `src/test/resources/pm-templates/v3/single-ok/` — step name `o`, URL `{{baseUrl}}/ok`, GET.

---

### Task 1: Kick `httpClient()` field

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/input/config/KickTest.kt`
- Create: `src/test/java/com/salesforce/revoman/input/config/KickHttpClientJavaTest.java`

**Interfaces:**
- Consumes: Immutables `KickDef` / `Kick.configure()` / `.off()`
- Produces: `fun httpClient(): HttpHandler?` (nullable, default `null`); builder `.httpClient(HttpHandler)`

- [ ] **Step 1: Write the failing Kick tests**

Add to `KickTest.kt` (existing Kotest `shouldBe` + JUnit). Imports: `org.http4k.core.HttpHandler`, `org.http4k.core.Response`, `org.http4k.core.Status.Companion.OK`.

```kotlin
  @Test
  fun `httpClient defaults to null and insecureHttp defaults to false`() {
    val kick = Kick.configure().templatePath("x").off()
    kick.httpClient() shouldBe null
    kick.insecureHttp() shouldBe false
  }

  @Test
  fun `httpClient setter round-trips`() {
    val handler: HttpHandler = { Response(OK).body("{}") }
    val kick = Kick.configure().templatePath("x").httpClient(handler).off()
    kick.httpClient() shouldBe handler
    kick.insecureHttp() shouldBe false
  }
```

Create `src/test/java/com/salesforce/revoman/input/config/KickHttpClientJavaTest.java`:

```java
/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.config;

import static com.google.common.truth.Truth.assertThat;

import org.http4k.core.Response;
import org.http4k.core.Status;
import org.junit.jupiter.api.Test;

class KickHttpClientJavaTest {
  @Test
  void javaLambdaSetsHttpClient() {
    final var kick =
        Kick.configure()
            .templatePath("x")
            .httpClient(req -> Response.create(Status.OK).body("{}"))
            .off();
    assertThat(kick.httpClient()).isNotNull();
    assertThat(kick.insecureHttp()).isFalse();
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests "com.salesforce.revoman.input.config.KickTest" --tests "com.salesforce.revoman.input.config.KickHttpClientJavaTest"
```

Expected: compile failure (`Unresolved reference: httpClient` / Java `cannot find symbol: method httpClient`).

- [ ] **Step 3: Add the KickDef member**

In `KickDef.kt` add:

```kotlin
import org.http4k.core.HttpHandler
```

Immediately after `@Value.Default fun insecureHttp(): Boolean = false` insert (no `@Value.Default`, same as `nodeModulesPath()`):

```kotlin
  /**
   * Optional in-process HTTP handler for this `revUp` only. When set, ReVoman invokes it instead of
   * Apache and ignores [insecureHttp]. When unset, Apache is used (secure unless [insecureHttp] is
   * true).
   */
  fun httpClient(): HttpHandler?
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.salesforce.revoman.input.config.KickTest" --tests "com.salesforce.revoman.input.config.KickHttpClientJavaTest"
```

Expected: BUILD SUCCESSFUL. If kapt/Immutables rejects the Kotlin function type, keep the same method name and type; only add what Immutables requires (no second overload).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt \
  src/test/kotlin/com/salesforce/revoman/input/config/KickTest.kt \
  src/test/java/com/salesforce/revoman/input/config/KickHttpClientJavaTest.java
git commit -m "$(cat <<'EOF'
feat: add optional Kick.httpClient(HttpHandler) seam

Let a revUp supply an in-process http4k handler later without changing
Apache when the field is unset.
EOF
)"
```

---

### Task 2: Resolve handler once; `fireHttpRequest` / `executePolling` take it

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/Polling.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/exe/PollingTest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt`

**Interfaces:**
- Consumes: `Kick.httpClient(): HttpHandler?`, `prepareHttpClient(insecureHttp: Boolean): HttpHandler`
- Produces:
  - `internal fun resolveHttpClient(custom: HttpHandler?, insecureHttp: Boolean): HttpHandler`
  - `fireHttpRequest(currentStep, httpRequest, httpClient: HttpHandler, moshiReVoman)`
  - `executePolling(..., httpClient: HttpHandler)` (drop `insecureHttp: Boolean`)

- [ ] **Step 1: Write the failing Kick-level tests**

Create `src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class KickHttpClientTest {

  private val whisperBase = "http://whisper.invalid"

  @Test
  fun `custom httpClient is invoked and no listen port is required`() {
    val uris = mutableListOf<String>()
    val handler: HttpHandler = { request ->
      uris += request.uri.toString()
      Response(OK).body("""{"ok":true}""")
    }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .httpClient(handler)
          .off()
      )
    val report = rundown.reportForStepName("o")!!
    assertThat(report.isSuccessful).isTrue()
    assertThat(uris).containsExactly("$whisperBase/ok")
    assertThat(report.responseInfo!!.get().httpMsg.bodyString()).contains("ok")
  }

  @Test
  fun `handler throw is HttpRequestFailure`() {
    val handler: HttpHandler = { throw RuntimeException("whisper boom") }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .httpClient(handler)
          .off()
      )
    val report = rundown.reportForStepName("o")!!
    assertThat(report.isSuccessful).isFalse()
    assertThat(report.exeTypeForFailure).isEqualTo(HTTP_REQUEST)
    assertThat(report.exeFailure).isInstanceOf(HttpRequestFailure::class.java)
    assertThat(report.exeFailure!!.failure.message).contains("whisper boom")
  }

  @Test
  fun `insecureHttp has no effect when httpClient is set`() {
    var calls = 0
    val handler: HttpHandler = { _ ->
      calls++
      Response(OK).body("{}")
    }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .insecureHttp(true)
          .httpClient(handler)
          .off()
      )
    assertThat(rundown.reportForStepName("o")!!.isSuccessful).isTrue()
    assertThat(calls).isEqualTo(1)
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.salesforce.revoman.KickHttpClientTest"
```

Expected: FAIL with connection / unknown-host against `whisper.invalid` (Apache still dials). `httpClient` exists from Task 1 but `revUpInternal` ignores it.

- [ ] **Step 3: Implement resolve, change signatures, thread through ReVoman, fix PollingTest**

**`HttpRequest.kt`** — add `resolveHttpClient`; change `fireHttpRequest` to take `httpClient` and invoke it. Do **not** change the `runCatching` / `HttpRequestFailure` / `TxnInfo` shape:

```kotlin
internal fun resolveHttpClient(custom: HttpHandler?, insecureHttp: Boolean): HttpHandler =
  custom ?: prepareHttpClient(insecureHttp)

@JvmSynthetic
internal fun fireHttpRequest(
  currentStep: Step,
  httpRequest: Request,
  httpClient: HttpHandler,
  moshiReVoman: MoshiReVoman,
): Either<HttpRequestFailure, TxnInfo<Response>> =
  runCatching(currentStep, HTTP_REQUEST) { httpClient(httpRequest) }
    .mapLeft { HttpRequestFailure(it, TxnInfo(httpMsg = httpRequest, moshiReVoman = moshiReVoman)) }
    .map { TxnInfo(httpMsg = it, moshiReVoman = moshiReVoman) }
```

Leave `prepareHttpClient` as-is.

**`Polling.kt`** — change the last parameter; delete `val httpClient: HttpHandler = prepareHttpClient(insecureHttp)`:

```kotlin
internal fun executePolling(
  pollingConfigs: List<PollingConfig>,
  currentStepReport: StepReport,
  rundown: Rundown,
  pm: PostmanSDK,
  httpClient: HttpHandler,
): Either<PollingFailure, PollingReport?> {
```

The rest of the function already uses `httpClient(pollRequest)`.

**`ReVoman.kt`** — in `revUpInternal`, immediately before `executeStepsSerially`:

```kotlin
    val httpClient = resolveHttpClient(kick.httpClient(), kick.insecureHttp())
    if (kick.httpClient() != null) {
      RevomanLog.info { "Using caller-supplied HttpHandler for this revUp; insecureHttp is ignored" }
    }
```

Pass `httpClient` as the last argument of `executeStepsSerially` and of `runStep`. Import `resolveHttpClient`.

Replace:

```kotlin
fireHttpRequest(step, httpRequest, kick.insecureHttp(), moshiReVoman)
```

with:

```kotlin
fireHttpRequest(step, httpRequest, httpClient, moshiReVoman)
```

Replace:

```kotlin
executePolling(kick.pollingConfig(), sr, pm.rundown, pm, kick.insecureHttp())
```

with:

```kotlin
executePolling(kick.pollingConfig(), sr, pm.rundown, pm, httpClient)
```

**`PollingTest.kt`** — remove `mockkStatic(::prepareHttpClient)`, `unmockkStatic`, `every { prepareHttpClient(...) }`, and the `@BeforeEach` / `@AfterEach` that only existed for that mock.

Add:

```kotlin
private val unusedClient: HttpHandler = { error("httpClient should not be called") }

private val okClient: HttpHandler = { Response(OK).body("done") }
```

Replace every `insecureHttp = false` with `httpClient = ...`:

| Test | `httpClient` |
| --- | --- |
| `returns null when step is not successful` | `unusedClient` |
| `returns null when no config matches` | `unusedClient` |
| `returns PollingReport on first attempt` | `okClient` |
| `returns PollingReport after multiple attempts` | `okClient` |
| `returns PollingRequestFailure when requestBuilder throws` | `unusedClient` |
| `returns PollingRequestFailure when httpClient throws` | `{ throw RuntimeException("http call failed") }` |
| `returns PollingTimeoutFailure when timeout expires` | `{ Response(OK).body("still pending") }` |
| `completionPredicate exception is swallowed and treated as false` | `okClient` |

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.salesforce.revoman.KickHttpClientTest" --tests "com.salesforce.revoman.internal.exe.PollingTest" --tests "com.salesforce.revoman.ControlFlowE2ETest"
```

Expected: BUILD SUCCESSFUL. Whisper tests pass with no server. `ControlFlowE2ETest` still uses `MockHttpServer` + unset `httpClient`. `PollingTest` green without mockk on `prepareHttpClient`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt \
  src/main/kotlin/com/salesforce/revoman/internal/exe/Polling.kt \
  src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
  src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt \
  src/test/kotlin/com/salesforce/revoman/internal/exe/PollingTest.kt
git commit -m "$(cat <<'EOF'
feat: honor Kick.httpClient for in-process HTTP in revUp

Resolve one HttpHandler per run and pass it to fireHttpRequest and
executePolling so stubs skip Apache and insecureHttp.
EOF
)"
```

---

### Task 3: Same handler for a polling step

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt`

**Interfaces:**
- Consumes: `executePolling(..., httpClient: HttpHandler)` already wired in Task 2; `Kick.pollingConfig(...)`; `PollingConfig.poll(...).request(...).every(...).timeout(...).until(...)`
- Produces: Kick-level proof that the **same** stub serves the collection request **and** the poll request

- [ ] **Step 1: Write the polling test**

Append to `KickHttpClientTest.kt`. Imports: `com.salesforce.revoman.input.config.PollingConfig`, `java.time.Duration`, `org.http4k.core.Method`, `org.http4k.core.Request`.

```kotlin
  @Test
  fun `same httpClient is used for a polling step`() {
    val paths = mutableListOf<String>()
    val handler: HttpHandler = { request ->
      paths += request.uri.path
      Response(OK).body("""{"status":"done"}""")
    }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .httpClient(handler)
          .pollingConfig(
            PollingConfig.poll { _, _ -> true }
              .request { _, _ -> Request(Method.GET, "$whisperBase/poll") }
              .every(Duration.ofMillis(10))
              .timeout(Duration.ofSeconds(2))
              .until { _, _ -> true }
          )
          .off()
      )
    val report = rundown.reportForStepName("o")!!
    assertThat(report.isSuccessful).isTrue()
    assertThat(report.pollingReport).isNotNull()
    assertThat(report.pollingReport!!.pollAttempts).isEqualTo(1)
    assertThat(paths).containsExactly("/ok", "/poll").inOrder()
  }
```

If `PollingConfig.poll { _, _ -> true }` does not compile (needs an explicit `PostTxnStepPick`), use `PollingConfig.poll(PostTxnStepPick { _, _ -> true })` with import `com.salesforce.revoman.input.config.StepPick.PostTxnStepPick`.

- [ ] **Step 2: Run the new test**

```bash
./gradlew test --tests "com.salesforce.revoman.KickHttpClientTest"
```

Expected: PASS if Task 2 already passed `httpClient` into `executePolling`. FAIL (unknown host / timeout on `/poll`, `paths` only `/ok`) if `runStep` still calls `executePolling(..., kick.insecureHttp())` — then switch that call to the resolved `httpClient`.

- [ ] **Step 3: Run the related suite, then all unit tests**

```bash
./gradlew test --tests "com.salesforce.revoman.KickHttpClientTest" --tests "com.salesforce.revoman.input.config.KickTest" --tests "com.salesforce.revoman.input.config.KickHttpClientJavaTest" --tests "com.salesforce.revoman.internal.exe.PollingTest" --tests "com.salesforce.revoman.ControlFlowE2ETest"
```

Expected: BUILD SUCCESSFUL.

```bash
./gradlew test
```

Expected: all unit tests green. Do not change `src/jmh`. Do not run Qodana unless you are about to push.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt
git commit -m "$(cat <<'EOF'
test: prove Kick.httpClient serves collection steps and polling

Later engine benches can stub HTTP the same way without MockHttpServer.
EOF
)"
```

---

## Self-review

**Spec coverage**

| Spec requirement | Task |
| --- | --- |
| `Kick.httpClient(HttpHandler)`, http4k type only | 1 |
| Default absent; `insecureHttp()` stays false | 1 |
| Ignore `insecureHttp()` when client set | 2 |
| Resolve once per `revUp`; one `HttpHandler` into fire + poll | 2 |
| Dummy `baseUrl` / no listen port | 2 |
| Handler throw → `HttpRequestFailure` | 2 |
| Java null → NPE wrapped, not HTTP 500 | 2 (`httpClient(request)` NPEs; `runCatching` wraps) |
| Polling uses the same handler | 3 |
| Unset client still Apache | 2 regression `ControlFlowE2ETest` |
| No JMH / kotlinx-benchmarks / `src/jmh` | No task touches them |
| No recording / httpMode / delay / MockHttpServer shrink / `pm.sendRequest` | No tasks |

**Placeholder scan:** none.

**Type consistency:** `HttpHandler` throughout; `resolveHttpClient(HttpHandler?, Boolean): HttpHandler`; `fireHttpRequest(..., httpClient: HttpHandler, ...)`; `executePolling(..., httpClient: HttpHandler)`; Kick getter `httpClient()`.

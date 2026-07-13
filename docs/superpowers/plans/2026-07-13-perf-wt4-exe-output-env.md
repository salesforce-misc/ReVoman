# Perf WT-4: Exe Engine + Output + Persistent Env Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the six behavior-preserving exe-engine/output micro-fixes (D1–D6) and then back the Postman environment with a persistent map (E2) so each `pmEnvSnapshot` is an O(1) structural share instead of an O(E) copy — turning per-step env accumulation from O(M·E) into O(M) — without changing any consumer-visible behavior or the deliberate FP style.

**Architecture:** One memoized Apache http4k client per TLS variant (secure/insecure) replaces per-request client construction; picked pre/post hooks are materialized to a `List` once so pick predicates run once; the response body + content-type are decoded once in `unmarshallResponse`; the two `Step`-keyed ledger-capture maps in `PostmanEnvironment` are re-keyed internally by `step.path` (the existing unique step identity used by the ledger) behind unchanged `Step`-taking method signatures; and finally the env is backed by a `PersistentMap` via a swap-on-write `MutableMap` adapter so a snapshot merely captures the current immutable reference (O(1)) while the external mutable-map contract that `RegexReplacer.setItBackInEnvironment` and `putAll`/`env[k]=v` rely on is fully preserved.

**Tech Stack:** Kotlin, http4k/Apache, kotlinx.collections.immutable, Moshi, JMH

## Global Constraints
- JDK 21+; branched off WT-0 (has `libs.kotlinx.collections.immutable` [implementation] + the `src/jmh` source set and the `./gradlew jmh -Pjmh.includes=<Name>` invocation contract).
- Owns ONLY these 9 files — touch no other source file:
  `ReVoman.kt`, `HttpRequest.kt`, `Polling.kt`, `PreStepHook.kt`, `PostStepHook.kt`, `UnmarshallResponse.kt`, `PostmanEnvironment.kt`, `TxnInfo.kt`, `StepReport.kt` (plus new test files and one new `src/jmh` benchmark).
- `PostmanSDK.kt` is WT-1's; `RegexReplacer.kt` is WT-3's; `PmJsEval.kt` is not ours. Keep `PostmanEnvironment`'s **primary-ctor shape** (`mutableEnv: MutableMap<String, ValueT>` first, positional) and its **method signatures** (`producedKeysFor(step: Step)`, `consumedKeysFor(step: Step)`, `valuesForKeysStartingWith(type, prefix)`) stable, and keep it IS-A `MutableMap`, so WT-1/WT-3 callers and `pm.environment.mutableEnv` reads (`PmJsEval.kt`) are unaffected.
- Correctness gate: `./gradlew test integrationTest` green (non-core) — **RUN AFTER EVERY TASK** (highest-risk worktree). Core (`PQE2EWithSMTest`, WFS) tests need a live org and are out of scope for the gate; run them opportunistically only.
- Preserve FP style (STYLE.md) AND the deliberate immutability contract — E2 keeps the env's mutable-map external interface, swaps a persistent map internally.
- **E2 (persistent env) is the LAST task group so it can be reverted independently** (see Task Ordering Note).
- `./gradlew spotlessApply` before every commit.
---

## Task Ordering Note (why this order)

Ordered low-risk → high-risk so each commit is independently revertible and the correctness gate localizes any regression:

1. **D5, D3, D1, D2, D6** — pure, local, byte-for-byte behavior-preserving. No shared state, no cross-file coupling.
2. **D4** (`Step`-key → `step.path`) — touches the two `private` ledger-capture maps in `PostmanEnvironment` only; method signatures unchanged. Medium risk: looped-step (same path, N iterations) semantics.
3. **JMH `EnvAccumBenchmark`** — added here so it can be run on the post-D4 tree to MEASURE D4, then again after E2 to MEASURE E2. Not gated.
4. **E2** split into **E2a** (introduce the persistent-backed adapter + unit-test it in isolation, wired to nothing) and **E2b** (flip the live env seed + O(1) snapshot). If E2b destabilizes, revert E2b (and E2a) alone; D1–D4 stay landed.

Each task: write characterization/behavior test (REAL Kotlin) → run it (exact cmd + expected) → implement (REAL before/after) → run the new test + `./gradlew test integrationTest` (expected PASS) → `./gradlew spotlessApply` + commit.

---

## Task D5: `TxnInfo.containsHeader` — avoid `headers.toMap()` allocation (behavior-preserving)

**File:** `src/main/kotlin/com/salesforce/revoman/output/report/TxnInfo.kt` (~line 71)

**Nuance (READ FIRST):** the spec suggests `httpMsg.header(key) != null`, but that is NOT byte-for-byte:
- `headers.toMap().containsKey(key)` is **case-SENSITIVE** (exact key) and true even for a present-but-null-valued header.
- http4k's `header(key)` is **case-INSENSITIVE** and returns the first value, so `header(key) != null` is `false` for a present null-valued header and `true` for a differently-cased key.

The behavior-preserving fix that also kills the `toMap()` allocation is `httpMsg.headers.any { it.first == key }` (case-sensitive, present-regardless-of-value, single pass, no map built). Use that.

- [ ] **Step 1: Characterization test.** Create `src/test/kotlin/com/salesforce/revoman/output/report/TxnInfoContainsHeaderTest.kt`:
  ```kotlin
  package com.salesforce.revoman.output.report

  import com.google.common.truth.Truth.assertThat
  import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
  import org.http4k.core.Method.GET
  import org.http4k.core.Request
  import org.junit.jupiter.api.Test

  class TxnInfoContainsHeaderTest {
      private val moshiReVoman = initMoshi()

      private fun txn(request: Request): TxnInfo<Request> =
          TxnInfo(httpMsg = request, moshiReVoman = moshiReVoman)

      @Test
      fun `containsHeader true when header present`() {
          val info = txn(Request(GET, "https://x.y/z").header("X-Trace", "abc"))
          assertThat(info.containsHeader("X-Trace")).isTrue()
      }

      @Test
      fun `containsHeader false when header absent`() {
          val info = txn(Request(GET, "https://x.y/z").header("X-Trace", "abc"))
          assertThat(info.containsHeader("X-Missing")).isFalse()
      }

      @Test
      fun `containsHeader is case-sensitive on the key (preserves toMap semantics)`() {
          val info = txn(Request(GET, "https://x.y/z").header("X-Trace", "abc"))
          assertThat(info.containsHeader("x-trace")).isFalse()
      }
  }
  ```
- [ ] **Step 2: Run against current code.** `./gradlew test --tests "com.salesforce.revoman.output.report.TxnInfoContainsHeaderTest"` — Expected: **all 3 PASS** (they pin the current `toMap().containsKey` semantics, which the test asserts).
- [ ] **Step 3: Implement.** In `TxnInfo.kt`:
  - BEFORE:
    ```kotlin
    fun containsHeader(key: String): Boolean = httpMsg.headers.toMap().containsKey(key)
    ```
  - AFTER:
    ```kotlin
    fun containsHeader(key: String): Boolean = httpMsg.headers.any { it.first == key }
    ```
  Leave the `containsHeader(key, value)` overload (line 73) untouched.
- [ ] **Step 4: Re-run the new test + gate.** `./gradlew test --tests "...TxnInfoContainsHeaderTest"` then `./gradlew test integrationTest` — Expected: **PASS** (case-sensitive semantics preserved).
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(D5): TxnInfo.containsHeader avoids headers.toMap() allocation"
  ```

---

## Task D3: `unmarshallResponse` — decode body + content-type once

**File:** `src/main/kotlin/com/salesforce/revoman/internal/exe/UnmarshallResponse.kt` (~line 35, 49)

Currently `httpResponse.bodyString()` is called twice (line 35 guard + line 49 decode) and `httpResponse.contentType()` twice (line 36 + line 69). Hoist both.

- [ ] **Step 1: Characterization test.** Create `src/test/kotlin/com/salesforce/revoman/internal/exe/UnmarshallResponseBodyTest.kt`. A JSON body unmarshals once to the configured/`Any` type; a blank body / non-JSON content-type yields `isJson = false`. Use the existing `Kick` builder + a minimal `StepReport` (mirror `PollingTest.kt`/`StepReportTest.kt` construction — `StepReport(step = ..., responseInfo = Right(TxnInfo(...)), requestInfo = Right(TxnInfo(...)), pmEnvSnapshot = PostmanEnvironment())`). Assert:
  - JSON `Response(OK).header("Content-Type","application/json").body("""{"a":1}""")` → `Right`, `txnObj` is a `Map` with `a`.
  - Blank body → `Right`, `isJson == false`.
  - Non-JSON content-type with a body → `Right`, `isJson == false`.
  Keep the test at the `unmarshallResponse(kick, moshiReVoman, currentStepReport, rundown)` entry point (build `rundown` as the other exe tests do).
- [ ] **Step 2: Run against current code.** `./gradlew test --tests "...UnmarshallResponseBodyTest"` — Expected: **PASS** (pins current behavior).
- [ ] **Step 3: Implement.** In `unmarshallResponse`, right after `val httpResponse = ...`:
  - BEFORE (the `when` head + decode + else log):
    ```kotlin
    val httpResponse = currentStepReport.responseInfo!!.get().httpMsg
    return when {
      httpResponse.bodyString().isNotBlank() &&
        APPLICATION_JSON.value.equals(httpResponse.contentType()?.value, true) -> {
        ...
        runCatching(currentStep, UNMARSHALL_RESPONSE) {
            moshiReVoman.fromJson<Any>(httpResponse.bodyString(), responseType)
          }
        ...
      }
      else -> {
        logger.info {
          "${currentStepReport.step} Blank Response body or ${httpResponse.contentType()?.value} didn't match ${APPLICATION_JSON.value}"
        }
        ...
      }
    }
    ```
  - AFTER:
    ```kotlin
    val httpResponse = currentStepReport.responseInfo!!.get().httpMsg
    val body: String = httpResponse.bodyString()
    val contentType: String? = httpResponse.contentType()?.value
    return when {
      body.isNotBlank() && APPLICATION_JSON.value.equals(contentType, true) -> {
        ...
        runCatching(currentStep, UNMARSHALL_RESPONSE) {
            moshiReVoman.fromJson<Any>(body, responseType)
          }
        ...
      }
      else -> {
        logger.info {
          "${currentStepReport.step} Blank Response body or $contentType didn't match ${APPLICATION_JSON.value}"
        }
        ...
      }
    }
    ```
    (Replace both `httpResponse.bodyString()` uses with `body` and both `httpResponse.contentType()?.value` uses with `contentType`.)
- [ ] **Step 4: Re-run new test + gate.** `./gradlew test --tests "...UnmarshallResponseBodyTest"` then `./gradlew test integrationTest` — Expected: **PASS**.
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(D3): unmarshallResponse decodes body + content-type once"
  ```

---

## Task D1: Memoize one Apache http client per TLS variant

**Files:** `src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt` (~line 40, 62); `Polling.kt` (~line 43, benefits transitively).

`prepareHttpClient(insecureHttp)` currently builds a fresh `ApacheClient` (secure) or a fresh pooled `ApacheClient(client = insecureApacheHttpClient())` (insecure) on EVERY request and Polling iteration, discarding it (per-run client leak). Auth is carried per-`Request`, so a single client per variant is safe to share.

- [ ] **Step 1: Behavior test (shared client serves two requests).** Create `src/test/kotlin/com/salesforce/revoman/internal/exe/PrepareHttpClientTest.kt`. Stand up an in-process http4k server (dependency `org.http4k:http4k-core` is on the classpath; use `org.http4k.server` if a server backend is available, else assert client identity + a live call to a public echo is out of scope). Minimal, network-free assertion that is robust:
  ```kotlin
  package com.salesforce.revoman.internal.exe

  import com.google.common.truth.Truth.assertThat
  import org.junit.jupiter.api.Test

  class PrepareHttpClientTest {
      @Test
      fun `secure variant returns the same memoized handler across calls`() {
          assertThat(prepareHttpClient(false)).isSameInstanceAs(prepareHttpClient(false))
      }

      @Test
      fun `insecure variant returns the same memoized handler across calls`() {
          assertThat(prepareHttpClient(true)).isSameInstanceAs(prepareHttpClient(true))
      }

      @Test
      fun `secure and insecure are distinct handlers`() {
          assertThat(prepareHttpClient(false)).isNotSameInstanceAs(prepareHttpClient(true))
      }
  }
  ```
  (Two-requests-succeed-with-shared-client is covered end-to-end by the existing `PokemonTest`/`ApigeeKtTest` integration tests, which fire multiple real requests through `prepareHttpClient`; the gate exercises the shared client for real.)
- [ ] **Step 2: Run against current code.** `./gradlew test --tests "...PrepareHttpClientTest"` — Expected: the two "same instance" tests **FAIL** (current code builds fresh each call), the "distinct" test PASSES. This is the RED that proves the fix is needed.
- [ ] **Step 3: Implement.** In `HttpRequest.kt`:
  - BEFORE:
    ```kotlin
    internal fun prepareHttpClient(insecureHttp: Boolean): HttpHandler =
      if (insecureHttp) ApacheClient(client = insecureApacheHttpClient()) else ApacheClient()
    ```
  - AFTER:
    ```kotlin
    // One http4k/Apache client per TLS variant, memoized for the life of the JVM. Auth is carried
    // per-Request (each Request builds its own Authorization header), so a single shared, pooled
    // client is safe across steps/runs — and avoids building + discarding a pooled client (and its
    // connection manager) on every request, which also fixes the per-run client leak.
    private val secureHttpClient: HttpHandler by lazy { ApacheClient() }

    private val insecureHttpClient: HttpHandler by lazy {
      ApacheClient(client = insecureApacheHttpClient())
    }

    internal fun prepareHttpClient(insecureHttp: Boolean): HttpHandler =
      if (insecureHttp) insecureHttpClient else secureHttpClient
    ```
    Also delete the now-stale per-step comment block above the `prepareHttpClient(insecureHttp)(httpRequest)` call in `fireHttpRequest` (the "Preparing httpClient for each step" NOTE at ~line 37) since it no longer describes the code; replace with a one-line note that the client is shared and auth is per-Request. `Polling.kt:43` (`val httpClient = prepareHttpClient(insecureHttp)`) needs no change — it now receives the memoized handler.
- [ ] **Step 4: Re-run new test + gate.** `./gradlew test --tests "...PrepareHttpClientTest"` (Expected: all 3 PASS) then `./gradlew test integrationTest` (Expected: PASS — Pokemon/Apigee fire multiple real requests through the shared client).
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(D1): memoize one Apache http client per TLS variant (fixes per-request client leak)"
  ```

---

## Task D2: Materialize picked hooks to a `List` once (Pre + Post)

**Files:** `PreStepHook.kt` (~line 46), `PostStepHook.kt` (~line 47).

`pickPreStepHooks`/`pickPostStepHooks` return a `Sequence`; the `.also { ... it.iterator().hasNext(); it.count(); it.count() }` re-runs the filter/map pick predicate up to 3× per step. Materialize to a `List` once and use `.isNotEmpty()`/`.size`.

- [ ] **Step 1: Characterization test (predicate runs once; count correct).** Create `src/test/kotlin/com/salesforce/revoman/internal/exe/PickHooksMaterializeTest.kt`. Build a `Kick` with two `PreStepHook`s whose `PreTxnStepPick` increments an `AtomicInteger` and returns true, and assert after `preStepHookExe` that (a) the picked count set on `currentStep.preStepHookCount == 2` and (b) each pick predicate ran exactly once (counter == number of configured hooks, not 3–4×). Mirror the existing hook-config construction in `RundownScopesTest`/`StepReportTest`; use `Item(name=...)` for the `Step` as `PostmanEnvironmentEnvVarsTest` does. If wiring a full `Kick` is heavy, assert the invariant at the `pickPreStepHooks`/`pickPostStepHooks` boundary via a focused test that counts predicate invocations.
- [ ] **Step 2: Run against current code.** `./gradlew test --tests "...PickHooksMaterializeTest"` — Expected: the "predicate runs once" assertion **FAILS** (current sequence re-evaluates), confirming the waste.
- [ ] **Step 3: Implement.** In `PreStepHook.kt`:
  - BEFORE:
    ```kotlin
    private fun pickPreStepHooks(...): Sequence<PreStepHook> =
      preStepHooks
        .asSequence()
        .filter { (it.pick as PreTxnStepPick).pick(currentStep, requestInfo, rundown) }
        .map { it.stepHook as PreStepHook }
        .also {
          if (it.iterator().hasNext()) {
            logger.info { "$currentStep Picked Pre hook count : ${it.count()}" }
            currentStep.preStepHookCount = it.count()
          }
        }
    ```
  - AFTER:
    ```kotlin
    private fun pickPreStepHooks(...): List<PreStepHook> =
      preStepHooks
        .filter { (it.pick as PreTxnStepPick).pick(currentStep, requestInfo, rundown) }
        .map { it.stepHook as PreStepHook }
        .also {
          if (it.isNotEmpty()) {
            logger.info { "$currentStep Picked Pre hook count : ${it.size}" }
            currentStep.preStepHookCount = it.size
          }
        }
    ```
  The consumer `preStepHookExe` chains `.map { ... }.firstOrNull { it.isLeft() }?.leftOrNull()` — unchanged, works identically on a `List`. Apply the same shape to `PostStepHook.kt` (`pickPostStepHooks` → `List<PostStepHook>`, `it.isNotEmpty()`, `it.size`, `currentStepReport.step.postStepHookCount = it.size`).
- [ ] **Step 4: Re-run new test + gate.** `./gradlew test --tests "...PickHooksMaterializeTest"` (Expected: PASS) then `./gradlew test integrationTest` (Expected: PASS).
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(D2): materialize picked pre/post hooks to a List once"
  ```

---

## Task D6: `valuesForKeysStartingWith(type, prefix)` delegates to the vararg sibling

**File:** `PostmanEnvironment.kt` (~line 214 single-prefix; ~line 217 vararg sibling).

The single-prefix overload builds a whole `PostmanEnvironment` via `mutableEnvCopyWithKeysStartingWith` (filter + mapValues + toMutableMap + object alloc) then reads `.mutableEnv.values.toSet()` — a double pass plus a throwaway object. The vararg sibling already does a single sequence pass. Delegate.

- [ ] **Step 1: Characterization test.** Create `src/test/kotlin/com/salesforce/revoman/output/postman/ValuesForKeysStartingWithTest.kt`:
  ```kotlin
  package com.salesforce.revoman.output.postman

  import com.google.common.truth.Truth.assertThat
  import org.junit.jupiter.api.Test

  class ValuesForKeysStartingWithTest {
      private val env =
          PostmanEnvironment<Any?>(
              mutableMapOf("saId1" to "a", "saId2" to "b", "other" to "c", "num" to 1))

      @Test
      fun `single prefix returns typed values for matching keys`() {
          assertThat(env.valuesForKeysStartingWith(String::class.java, "saId"))
              .containsExactly("a", "b")
      }

      @Test
      fun `single prefix filters by type`() {
          assertThat(env.valuesForKeysStartingWith(Integer::class.java, "num")).containsExactly(1)
      }

      @Test
      fun `single prefix no match is empty`() {
          assertThat(env.valuesForKeysStartingWith(String::class.java, "zzz")).isEmpty()
      }
  }
  ```
- [ ] **Step 2: Run against current code.** `./gradlew test --tests "...ValuesForKeysStartingWithTest"` — Expected: **PASS** (pins current output).
- [ ] **Step 3: Implement.** In `PostmanEnvironment.kt`:
  - BEFORE:
    ```kotlin
    fun <T> valuesForKeysStartingWith(type: Class<T>, prefix: String): Set<T> =
      mutableEnvCopyWithKeysStartingWith(type, prefix).mutableEnv.values.toSet()
    ```
  - AFTER:
    ```kotlin
    fun <T> valuesForKeysStartingWith(type: Class<T>, prefix: String): Set<T> =
      valuesForKeysStartingWith(type, *arrayOf(prefix))
    ```
  (The vararg overload at ~line 217 returns `Set<T>` via a single `asSequence().filter{}.mapNotNull{}.toSet()`. Signature of the single-prefix overload is unchanged — external callers unaffected.)
- [ ] **Step 4: Re-run new test + gate.** `./gradlew test --tests "...ValuesForKeysStartingWithTest"` then `./gradlew test integrationTest` — Expected: **PASS**.
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(D6): single-prefix valuesForKeysStartingWith delegates to vararg sibling (single pass)"
  ```

---

## Task D4: Key the two ledger-capture maps by `step.path` internally

**File:** `PostmanEnvironment.kt` (~line 36–37 map decls; ~line 39/41 readers; ~line 46 `recordConsumed`; ~line 64 `set`; ~line 74 `unset`).

`producedKeysByStep`/`consumedKeysByStep` are `private MutableMap<Step, MutableSet<String>>`. `Step`'s `hashCode`/`equals` (data class) recurse through `rawPMStep: Item` — the full request body + all JS scripts — so every map op hashes the entire step. `step.path` is the existing unique step identity: the ledger keys `learnedLedger` by `step.path` (ReVoman.kt:189), looks up `ledger.steps[step.path]` (ReVoman.kt:343), and tracks `iterationByPath` by path — so the whole engine already treats path as the step key. Re-key these two maps by `String` (`step.path`) internally, keeping the `producedKeysFor(step: Step)` / `consumedKeysFor(step: Step)` **signatures unchanged**.

**Coupling check (verified against real code):** these two maps are the ONLY `producedKeysByStep`/`consumedKeysByStep` in the repo (grep-confirmed) and are `private`, reached only via `set`/`unset`/`recordConsumed` (all use the internal `currentStep` field or a passed `Step`) and the two `*For(step)` readers. WT-1's `PostmanSDK.kt` does NOT reference these maps (its line-96 map is `pmTestAssertionsByStep`, a separate `Step`-keyed map that stays WT-1's concern). So D4 here needs **no** signature change and **no** WT-1 coordination.

**Looped-step risk:** the existing comment documents that these maps are NOT reset between iterations, so a step running twice accumulates the UNION of both iterations' keys. In the sequencer the SAME `Step` instance (`pickedSteps[cursor]`) is reused across iterations, and its `path` is stable, so path-keying yields the IDENTICAL union accumulation. The characterization test must prove this.

- [ ] **Step 1: Characterization test (looped step; union across iterations by path).** Add to `src/test/kotlin/com/salesforce/revoman/output/postman/PostmanEnvironmentEnvVarsTest.kt` (or a new `PostmanEnvironmentPathKeyTest.kt`):
  ```kotlin
  @Test
  fun `two set()s on the same step accumulate the union (path-keyed)`() {
      val env = PostmanEnvironment<Any?>()
      val step = Step(index = "1", rawPMStep = Item(name = "create-sa"))
      env.currentStep = step
      env.set("saId1", "a")
      env.set("saId2", "b")
      assertThat(env.producedKeysFor(step)).containsExactly("saId1", "saId2")
  }

  @Test
  fun `producedKeysFor resolves by path (distinct Step instance, same path)`() {
      val env = PostmanEnvironment<Any?>()
      val step1 = Step(index = "1", rawPMStep = Item(name = "create-sa"))
      env.currentStep = step1
      env.set("saId1", "a")
      // A different Step instance with the SAME path (the engine reuses the same instance, but this
      // pins that lookups are by path identity, matching the ledger's step.path keying).
      val step1Again = Step(index = "1", rawPMStep = Item(name = "create-sa"))
      assertThat(env.producedKeysFor(step1Again)).containsExactly("saId1")
  }
  ```
  (Keep the existing `PostmanEnvironmentEnvVarsTest` tests — they pass a step whose path equals its name for root steps, so they remain green.)
- [ ] **Step 2: Run against current code.** `./gradlew test --tests "...PostmanEnvironmentEnvVarsTest" --tests "...PostmanEnvironmentPathKeyTest"` — Expected: the "distinct Step instance, same path" test **FAILS** under the current `Step`-keyed map (two equal-by-value data-class instances actually ARE `.equals()`, so it may pass — if so, this test documents the equivalence; the union test passes). The union test PASSES on current code. Note in the ledger the reused-instance case is what runs; this test locks path-identity behavior for the refactor.
- [ ] **Step 3: Implement.** In `PostmanEnvironment.kt`:
  - Map decls (BEFORE → AFTER):
    ```kotlin
    private val producedKeysByStep: MutableMap<Step, MutableSet<String>> = mutableMapOf()
    private val consumedKeysByStep: MutableMap<Step, MutableSet<String>> = mutableMapOf()
    ```
    →
    ```kotlin
    // Keyed by step.path (String), NOT the whole Step: Step's data-class hashCode/equals recurse
    // through rawPMStep (request body + every JS script). path is the step's unique identity used
    // everywhere else by the ledger (learnedLedger, ledger.steps lookup, iterationByPath).
    private val producedKeysByStepPath: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val consumedKeysByStepPath: MutableMap<String, MutableSet<String>> = mutableMapOf()
    ```
  - Readers (signatures UNCHANGED, body switches to `step.path`):
    ```kotlin
    fun producedKeysFor(step: Step): Set<String> =
      producedKeysByStepPath[step.path]?.toSet() ?: emptySet()

    fun consumedKeysFor(step: Step): Set<String> =
      consumedKeysByStepPath[step.path]?.toSet() ?: emptySet()
    ```
  - `recordConsumed` (BEFORE `consumedKeysByStep.getOrPut(currentStep)`):
    ```kotlin
    if (::currentStep.isInitialized) {
      consumedKeysByStepPath.getOrPut(currentStep.path) { mutableSetOf() }.add(key)
    }
    ```
  - `set` (BEFORE `producedKeysByStep.getOrPut(step)`):
    ```kotlin
    if (step != null) producedKeysByStepPath.getOrPut(step.path) { mutableSetOf() }.add(key)
    ```
  - `unset` (BEFORE `producedKeysByStep[step]?.remove(key)`):
    ```kotlin
    if (step != null) producedKeysByStepPath[step.path]?.remove(key)
    ```
  (The `set`/`unset`/log lines still interpolate the `Step?` object for the log message — leave that; only the map key changes.)
- [ ] **Step 4: Re-run tests + gate.** `./gradlew test --tests "...PostmanEnvironmentEnvVarsTest" --tests "...PostmanEnvironmentPathKeyTest"` (Expected: PASS) then `./gradlew test integrationTest` — Expected: **PASS**. Pay attention to `LedgerRoundTripKtTest` (integration) and `RundownScopesTest`/`StepReportEnvVarsTest` which exercise produced/consumed capture — they must stay green.
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(D4): key PostmanEnvironment ledger-capture maps by step.path (avoids Step deep-hash)"
  ```

---

## Task JMH: `EnvAccumBenchmark` — measure D4 (and, after E2, E2)

**File (new):** `src/jmh/kotlin/com/salesforce/revoman/benchmark/EnvAccumBenchmark.kt` (source set + `./gradlew jmh -Pjmh.includes=<Name>` provided by WT-0).

Simulates M steps of `set` + `pmEnvSnapshot` against a growing env of E entries — the exact O(M·E)→O(M) shape D4+E2 target. MEASURED, not gated.

- [ ] **Step 1: Create the benchmark.**
  ```kotlin
  package com.salesforce.revoman.benchmark

  import com.salesforce.revoman.internal.postman.template.Item
  import com.salesforce.revoman.output.postman.PostmanEnvironment
  import com.salesforce.revoman.output.report.Step
  import java.util.concurrent.TimeUnit
  import org.openjdk.jmh.annotations.Benchmark
  import org.openjdk.jmh.annotations.BenchmarkMode
  import org.openjdk.jmh.annotations.Fork
  import org.openjdk.jmh.annotations.Measurement
  import org.openjdk.jmh.annotations.Mode
  import org.openjdk.jmh.annotations.OutputTimeUnit
  import org.openjdk.jmh.annotations.Param
  import org.openjdk.jmh.annotations.Scope
  import org.openjdk.jmh.annotations.Setup
  import org.openjdk.jmh.annotations.State
  import org.openjdk.jmh.annotations.Warmup
  import org.openjdk.jmh.infra.Blackhole

  /**
   * M steps, each: set a fresh key + capture a per-step env snapshot (mirrors ReVoman.runStep's
   * pmEnvSnapshot). Measures env-accumulation cost — O(M*E) with a copy-on-snapshot backing,
   * O(M) once E2's persistent map makes the snapshot an O(1) structural share.
   */
  @State(Scope.Thread)
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  @Warmup(iterations = 3, time = 1)
  @Measurement(iterations = 5, time = 1)
  @Fork(1)
  open class EnvAccumBenchmark {
      @Param("50", "200", "800") open var steps: Int = 0

      private lateinit var stepList: List<Step>

      @Setup
      fun setUp() {
          stepList = (0 until steps).map { Step(index = "$it", rawPMStep = Item(name = "s$it")) }
      }

      @Benchmark
      open fun accumulateAndSnapshot(bh: Blackhole) {
          val env = PostmanEnvironment<Any?>()
          stepList.forEach { step ->
              env.currentStep = step
              env.set("key_${step.name}", step.name)
              // Mirror ReVoman.runStep's per-step snapshot capture:
              bh.consume(env.copy(mutableEnv = env.mutableEnv.toMutableMap()))
          }
          bh.consume(env)
      }
  }
  ```
  (After E2b lands, update the snapshot line to the O(1) helper — see E2b Step 4 — and re-run to record the delta.)
- [ ] **Step 2: Compile + run.** `./gradlew compileJmhKotlin` (Expected: BUILD SUCCESSFUL), then `./gradlew jmh -Pjmh.includes=EnvAccumBenchmark` — Expected: a results table with `EnvAccumBenchmark.accumulateAndSnapshot` rows per `steps` param; snapshot per-run under `build/results/jmh/results.txt`. Record the numbers (this is the post-D4 baseline for the snapshot cost). Not a gate — do not fail the task on timing.
- [ ] **Step 3:** commit:
  ```
  git add src/jmh/kotlin/com/salesforce/revoman/benchmark/EnvAccumBenchmark.kt
  git commit -m "test(jmh): EnvAccumBenchmark for env set + snapshot (measures D4/E2)"
  ```

---

## Task E2a: Introduce the persistent-map-backed `MutableMap` adapter (wired to nothing)

**File:** `PostmanEnvironment.kt` (new internal class, no behavior wiring yet — lowest-risk first half of E2).

The adapter implements `MutableMap<String, ValueT>` over a `var current: PersistentMap<String, ValueT>`, swapping on every write, so that a captured reference is a true point-in-time immutable view. Critical design points learned from the code:
- `pm.environment.keys` is read EVERY step (ReVoman.kt:344, then `containsAll`/iterate). `keys`/`entries`/`values` MUST be non-copying read-through views (mutators throw — nothing in the codebase mutates through them, grep-verified) so per-step `.keys` stays O(1) and E2 actually reaches O(M).
- Must support null VALUES (env stores `key5 to null`; ledger keeps existing values). kotlinx `persistentMapOf`/`PersistentMap.put(k, null)` accepts null values — the E2a test pins this.
- `equals`/`hashCode` follow the `Map` contract (delegate to `current`) so `data class PostmanEnvironment` equality and `shouldContainExactly`/`.mutableEnv shouldContainExactly` assertions still hold.

- [ ] **Step 1: Unit test the adapter in isolation.** Create `src/test/kotlin/com/salesforce/revoman/output/postman/PersistentBackedMutableMapTest.kt`:
  ```kotlin
  package com.salesforce.revoman.output.postman

  import com.google.common.truth.Truth.assertThat
  import org.junit.jupiter.api.Test

  class PersistentBackedMutableMapTest {
      @Test
      fun `put get remove behave like a map`() {
          val m = PersistentBackedMutableMap<Any?>()
          m["a"] = 1
          m["b"] = 2
          assertThat(m["a"]).isEqualTo(1)
          assertThat(m.size).isEqualTo(2)
          m.remove("a")
          assertThat(m.containsKey("a")).isFalse()
      }

      @Test
      fun `supports null values`() {
          val m = PersistentBackedMutableMap<Any?>()
          m["k"] = null
          assertThat(m.containsKey("k")).isTrue()
          assertThat(m["k"]).isNull()
      }

      @Test
      fun `snapshot is an O(1) point-in-time view unaffected by later writes`() {
          val m = PersistentBackedMutableMap<Any?>()
          m["a"] = 1
          val snap = m.snapshotView()
          m["b"] = 2 // mutate the live map AFTER snapshot
          assertThat(snap).containsExactlyEntriesIn(mapOf("a" to 1))
          assertThat(m).containsExactlyEntriesIn(mapOf("a" to 1, "b" to 2))
      }

      @Test
      fun `equals and hashCode follow Map contract`() {
          val m = PersistentBackedMutableMap<Any?>()
          m["a"] = 1
          assertThat(m).isEqualTo(mapOf("a" to 1))
          assertThat(m.hashCode()).isEqualTo(mapOf("a" to 1).hashCode())
      }

      @Test
      fun `keys view reflects live state without copying`() {
          val m = PersistentBackedMutableMap<Any?>()
          m["a"] = 1
          val keys = m.keys
          m["b"] = 2
          // read-through view: sees the new key
          assertThat(keys).containsExactly("a", "b")
      }
  }
  ```
- [ ] **Step 2: Run (RED — class does not exist).** `./gradlew test --tests "...PersistentBackedMutableMapTest"` — Expected: **compile error / FAIL** (adapter not yet written).
- [ ] **Step 3: Implement the adapter** in `PostmanEnvironment.kt` (top-level `internal class`, plus imports `kotlinx.collections.immutable.PersistentMap`, `kotlinx.collections.immutable.persistentMapOf`, `kotlinx.collections.immutable.toPersistentMap`):
  ```kotlin
  /**
   * A [MutableMap] backed by an immutable [PersistentMap] that is SWAPPED on every write. Because the
   * backing is immutable, [snapshotView] captures the current reference in O(1) and later writes to
   * this instance (which install a NEW persistent map) never mutate that captured view — the
   * point-in-time invariant [StepReport.pmEnvSnapshot] relies on. Reads/keys/entries/values are
   * non-copying read-through views over the current backing (mutators on those views throw; nothing
   * in ReVoman mutates through them), so the per-step `pm.environment.keys` access stays O(1).
   */
  internal class PersistentBackedMutableMap<V>
  private constructor(private var current: PersistentMap<String, V>) : MutableMap<String, V> {

      constructor() : this(persistentMapOf())

      constructor(seed: Map<String, V>) : this(seed.toPersistentMap())

      /** O(1): a NEW instance sharing this instance's current immutable backing. */
      fun snapshotView(): PersistentBackedMutableMap<V> = PersistentBackedMutableMap(current)

      override val size: Int get() = current.size
      override fun isEmpty(): Boolean = current.isEmpty()
      override fun containsKey(key: String): Boolean = current.containsKey(key)
      override fun containsValue(value: V): Boolean = current.containsValue(value)
      override fun get(key: String): V? = current[key]

      override fun put(key: String, value: V): V? {
          val prev = current[key]
          current = current.put(key, value)
          return prev
      }

      override fun remove(key: String): V? {
          val prev = current[key]
          current = current.remove(key)
          return prev
      }

      override fun putAll(from: Map<out String, V>) {
          current = current.putAll(from)
      }

      override fun clear() {
          current = persistentMapOf()
      }

      // Read-through views over the current backing. kotlinx immutable views are Set/Collection; wrap
      // them so the MutableMap type is satisfied. Mutators throw — no ReVoman code path mutates
      // through env.keys/entries/values (grep-verified); the swap-on-write API above is the only
      // supported mutation surface.
      override val keys: MutableSet<String>
          get() = object : AbstractMutableSet<String>() {
              override val size: Int get() = current.size
              override fun iterator(): MutableIterator<String> =
                  current.keys.iterator().asReadOnlyMutable()
              override fun add(element: String): Boolean = throw UnsupportedOperationException()
          }

      override val values: MutableCollection<V>
          get() = object : AbstractMutableCollection<V>() {
              override val size: Int get() = current.size
              override fun iterator(): MutableIterator<V> =
                  current.values.iterator().asReadOnlyMutable()
              override fun add(element: V): Boolean = throw UnsupportedOperationException()
          }

      override val entries: MutableSet<MutableMap.MutableEntry<String, V>>
          get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, V>>() {
              override val size: Int get() = current.size
              override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, V>> =
                  current.entries
                      .map { it as MutableMap.MutableEntry<String, V> }
                      .iterator()
                      .asReadOnlyMutable()
              override fun add(element: MutableMap.MutableEntry<String, V>): Boolean =
                  throw UnsupportedOperationException()
          }

      override fun equals(other: Any?): Boolean = current == other
      override fun hashCode(): Int = current.hashCode()
      override fun toString(): String = current.toString()
  }

  private fun <T> Iterator<T>.asReadOnlyMutable(): MutableIterator<T> =
      object : MutableIterator<T> {
          override fun hasNext(): Boolean = this@asReadOnlyMutable.hasNext()
          override fun next(): T = this@asReadOnlyMutable.next()
          override fun remove(): Unit = throw UnsupportedOperationException()
      }
  ```
  (If `AbstractMutableSet`/`AbstractMutableCollection` prove awkward with the read-only iterator under the compiler's contract checks, fall back to the simplest correct form: return `current.keys.toMutableSet()` etc. ONLY as a last resort, and if so RE-CONFIRM via the JMH benchmark that per-step `.keys` did not reintroduce O(E) — the read-through view is the intended design precisely because `.keys` is on the per-step hot path.)
- [ ] **Step 4: Run adapter test + gate.** `./gradlew test --tests "...PersistentBackedMutableMapTest"` (Expected: PASS) then `./gradlew test integrationTest` (Expected: PASS — adapter is not yet wired into `PostmanEnvironment`, so this only proves it compiles and nothing broke).
- [ ] **Step 5:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(E2a): add PersistentBackedMutableMap adapter (swap-on-write, O(1) snapshotView)"
  ```

---

## Task E2b: Flip the live env to the persistent backing + O(1) snapshots

**Files:** `ReVoman.kt` (seed at ~line 168; snapshot at ~line 498), `StepReport.kt` (snapshots at ~line 152, 171), `PostmanEnvironment.kt` (add an O(1) snapshot helper).

**Seam (verified):** the live env's seed originates in `ReVoman.kt:168` — `PostmanSDK(..., environment.toMutableMap())` — and `PostmanSDK.kt:40` forwards that `MutableMap` unchanged into `PostmanEnvironment(mutableEnv, moshiReVoman)`, which delegates `MutableMap by mutableEnv`. So seeding a `PersistentBackedMutableMap` from `ReVoman.kt` makes the LIVE env persistent-backed WITHOUT touching WT-1's `PostmanSDK.kt`. Snapshots then capture the current immutable reference in O(1). `collectionVariables`/`globals` (seeded `mutableMapOf()` in PostmanSDK) stay plain-backed — they are never per-step snapshotted, so no perf concern and identical behavior.

- [ ] **Step 1: Snapshot-immutability characterization test (the E2 invariant).** Create `src/test/kotlin/com/salesforce/revoman/output/postman/EnvSnapshotImmutabilityTest.kt`:
  ```kotlin
  package com.salesforce.revoman.output.postman

  import com.google.common.truth.Truth.assertThat
  import org.junit.jupiter.api.Test

  class EnvSnapshotImmutabilityTest {
      @Test
      fun `snapshot is a point-in-time view - later env writes do not change it`() {
          val env = PostmanEnvironment<Any?>(PersistentBackedMutableMap(mapOf("a" to 1)))
          val snapshot = env.o1Snapshot()
          env["b"] = 2 // mutate live env AFTER the snapshot (regex write-back path)
          env.putAll(mapOf("c" to 3))
          assertThat(snapshot.mutableEnv).containsExactlyEntriesIn(mapOf("a" to 1))
          assertThat(env.mutableEnv).containsExactlyEntriesIn(mapOf("a" to 1, "b" to 2, "c" to 3))
      }

      @Test
      fun `o1Snapshot preserves value including nulls`() {
          val env = PostmanEnvironment<Any?>(PersistentBackedMutableMap(mapOf("n" to null)))
          val snapshot = env.o1Snapshot()
          assertThat(snapshot.containsKey("n")).isTrue()
          assertThat(snapshot["n"]).isNull()
      }
  }
  ```
- [ ] **Step 2: Run (RED — `o1Snapshot` missing).** `./gradlew test --tests "...EnvSnapshotImmutabilityTest"` — Expected: **compile error / FAIL**.
- [ ] **Step 3: Add the O(1) snapshot helper** to `PostmanEnvironment.kt`:
  ```kotlin
  /**
   * A point-in-time snapshot of this environment. When the backing is a [PersistentBackedMutableMap]
   * this is O(1) — the snapshot shares the current immutable persistent map and later writes to this
   * (live) env install a new map, leaving the snapshot untouched. Falls back to a defensive copy for
   * a plain map backing (e.g. collectionVariables/globals or test-constructed envs), preserving the
   * historical `copy(mutableEnv = mutableEnv.toMutableMap())` semantics.
   */
  fun o1Snapshot(): PostmanEnvironment<ValueT> =
    copy(
      mutableEnv =
        (mutableEnv as? PersistentBackedMutableMap<ValueT>)?.snapshotView()
          ?: mutableEnv.toMutableMap()
    )
  ```
- [ ] **Step 4: Replace the O(E) snapshot copies** with `o1Snapshot()` and seed the live env persistently:
  - `ReVoman.kt:168` (BEFORE → AFTER):
    ```kotlin
    val pm = PostmanSDK(moshiReVoman, kick.nodeModulesPath(), regexReplacer, environment.toMutableMap())
    ```
    →
    ```kotlin
    // Persistent-backed so every per-step pmEnvSnapshot is an O(1) structural share (see E2). The
    // MutableMap contract is preserved, so PostmanSDK/RegexReplacer writes are unaffected.
    val pm =
      PostmanSDK(
        moshiReVoman,
        kick.nodeModulesPath(),
        regexReplacer,
        PersistentBackedMutableMap(environment),
      )
    ```
    (add `import com.salesforce.revoman.output.postman.PersistentBackedMutableMap`; `environment` is a `Map<String, Any?>` so the `Map`-seed ctor applies. `PostmanSDK`'s param is `MutableMap<String, Any?>` and `PersistentBackedMutableMap<Any?>` IS-A one — no PostmanSDK change.)
  - `ReVoman.kt:498` (inside `runStep`'s final `.copy(...)`):
    ```kotlin
    pmEnvSnapshot = pm.environment.copy(mutableEnv = pm.environment.mutableEnv.toMutableMap()),
    ```
    →
    ```kotlin
    pmEnvSnapshot = pm.environment.o1Snapshot(),
    ```
  - `StepReport.kt:152` and `:171` (in `ledgerSkipped` / `requestSkipped`):
    ```kotlin
    pmEnvSnapshot = env.copy(mutableEnv = env.mutableEnv.toMutableMap()),
    ```
    →
    ```kotlin
    pmEnvSnapshot = env.o1Snapshot(),
    ```
  - Update `EnvAccumBenchmark.accumulateAndSnapshot` to call `env.o1Snapshot()` instead of the `copy(mutableEnv = ...toMutableMap())` line, and seed via `PostmanEnvironment(PersistentBackedMutableMap<Any?>())` so it measures the persistent path.
- [ ] **Step 5: Run the invariant test + FULL gate.** `./gradlew test --tests "...EnvSnapshotImmutabilityTest"` (Expected: PASS) then `./gradlew test integrationTest` — Expected: **PASS**. Watch especially:
  - `PostmanEnvironmentKtTest` / `PostmanEnvironmentUnsteppedTest` (`.mutableEnv shouldContainExactly`, null values, JSON format) — the adapter's `equals`/null-handling must satisfy these.
  - `RundownJsonWriterTest` / `RundownScopesTest` / `StepReport*Test` (they construct `PostmanEnvironment()` directly — those stay plain-backed via the `o1Snapshot` fallback, no behavior change).
  - `LedgerRoundTripKtTest`, `ControlFlowE2ETest`, `LedgerSkipE2ETest`, `MultiKickEnvTypesE2ETest` (integration) — the per-step snapshots feed VERBOSE JSON + sinks + multi-kick env threading; the immutability invariant is exactly what they depend on.
- [ ] **Step 6: Re-measure.** `./gradlew jmh -Pjmh.includes=EnvAccumBenchmark` — Expected: `accumulateAndSnapshot` time now scales ~O(M) (flat per-step snapshot cost across `steps=50/200/800`) versus the pre-E2 ~O(M·E) growth. Record the delta in the commit message; not a gate.
- [ ] **Step 7:** `./gradlew spotlessApply` then commit:
  ```
  git commit -am "perf(E2): back env with a persistent map — O(1) pmEnvSnapshot (O(M*E) -> O(M))"
  ```

---

## Final verification
- [ ] `./gradlew spotlessApply build` — Expected: BUILD SUCCESSFUL (compiles, spotless clean, unit tests pass).
- [ ] `./gradlew test integrationTest` — Expected: green (non-core).
- [ ] `./gradlew jmh -Pjmh.includes=EnvAccumBenchmark` — capture final numbers; attach the D4+E2 delta to the worktree summary.
- [ ] Confirm no file outside the 9 owned source files (+ new tests + the one benchmark) was modified: `git diff --name-only <wt0-base>..HEAD`.

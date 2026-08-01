# Unit Test Coverage Audit & Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Kover's coverage number honest (exclude generated/JMH/test-only code), then add unit tests closing the real hand-written gaps, and raise the regression floor.

**Architecture:** Two mechanical build-config edits bracket six pure-unit-test additions. First calibrate the gauge (Kover `filters`/`excludedSourceSets`), then write JUnit-Jupiter + Google-Truth tests for the confirmed 0–54% classes, then ratchet `minBound` to the new honest total.

**Tech Stack:** Kotlin, Gradle (Kotlin DSL), Kover 0.9.9, JUnit Jupiter, Google Truth (`com.google.common.truth.Truth.assertThat`), Moshi, Immutables, http4k, Vavr `Either`.

## Global Constraints

- JDK 21 required (`export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn` if needed).
- Four-space indentation in all `.kt`/`.kts` files.
- Every new test file starts with the repo's Apache-2.0 license header block (copy verbatim from any existing test file).
- Test methods use backtick natural-language names (e.g. `` fun `round-trips a UUID`() ``).
- New assertions use Google Truth `assertThat(...)` (matches the adapter tests being extended).
- Run formatting before any build that runs `spotlessCheck`: `./gradlew spotlessApply`.
- Unit tests live under `src/test/kotlin`; run with `./gradlew test`.
- Do NOT write tests for generated code (`Kick`, `Pojo`, `JsonFile`, `JsonString`), JMH benchmarks, or org-gated `integration.core.*`.
- Spec: `docs/superpowers/specs/2026-08-01-unit-test-coverage-audit-design.md`.

---

## File Structure

**Modify:**
- `build.gradle.kts` — Kover `filters { excludes }` + `currentProject { sources { excludedSourceSets } }` (Task 1); `minBound` recalibration (Task 8).

**Create (all under `src/test/kotlin/com/salesforce/revoman/`):**
- `internal/json/adapters/TypeAdapterTest.kt` (Task 2)
- `internal/json/adapters/UUIDAdapterTest.kt` (Task 2)
- `input/config/RequestConfigTest.kt` (Task 3)
- `input/config/ResponseConfigTest.kt` (Task 4)
- `output/report/failure/RequestFailureTest.kt` (Task 5)
- `output/report/failure/ResponseFailureTest.kt` (Task 5)
- `input/config/StepPickPickUtilsTest.kt` (Task 6)
- `input/json/JsonWriterUtilsTest.kt` (Task 7)

---

## Task 1: Kover measurement hygiene (excludes)

**Files:**
- Modify: `build.gradle.kts` (the `kover { }` block, currently ~lines 248-264)

**Interfaces:**
- Consumes: nothing.
- Produces: an honest baseline coverage % that Task 8 reads. No code symbols.

- [ ] **Step 1: Read the current Kover block**

Run: `sed -n '248,264p' build.gradle.kts`
Expected: the `kover { reports { total { html { onCheck = true }; verify { rule { minBound(69) } } } } }` block.

- [ ] **Step 2: Replace the `kover { }` block with the excludes-augmented version**

Replace the entire existing `kover { ... }` block with:

```kotlin
kover {
  currentProject {
    sources {
      // The JMH benchmark source set is a perf harness, never unit-tested by design (like the
      // opt-in core-IT tests). Keep it out of the coverage denominator.
      excludedSourceSets.addAll("jmh")
    }
  }
  reports {
    filters {
      excludes {
        // Generated Immutables (Kick, Pojo, JsonFile, JsonString + their builders) carry
        // @org.immutables.value.Generated — codegen, not hand-written, so not our coverage debt.
        annotatedBy("org.immutables.value.Generated")
        // Test source sets leak into the denominator as if they were production code:
        // integration.pokemon inflates it to 100%, while org-gated integration.core.wfs/pq/bt2bs
        // deflate it to 0% (they only run under -PincludeCoreIT). Neither is production code.
        classes("com.salesforce.revoman.integration.**")
        // Moshi-generated JSON adapters (…JsonAdapter) — codegen, not hand-written.
        classes("*JsonAdapter")
      }
    }
    total {
      html { onCheck = true }
      // Coverage regression ratchet. Floor recalibrated (Task 8) against the honest baseline that
      // remains AFTER the excludes above strip generated/JMH/test-only code. Raise toward 90% as
      // tests are added. Wired into `check`, so `./gradlew build` (local + CI) enforces it.
      verify {
        rule {
          minBound(69) // total LINE coverage %; recalibrated below
        }
      }
    }
  }
}
```

- [ ] **Step 3: Format**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Regenerate the report and verify the excludes fire**

Run: `./gradlew test koverHtmlReport`
Then inspect the produced report's class list:
Run: `python3 -c "import re,html,glob; d=open('build/reports/kover/html/index.html',encoding='utf-8').read(); rows=re.findall(r'<tr>(.*?)</tr>',d,re.S); print('\n'.join(html.unescape(re.sub('<[^>]+>',' ',r)).split()[0] for r in rows if r.strip()))"` (rough package list)

Expected: NO rows for `com.salesforce.revoman.benchmark`, `com.salesforce.revoman.integration.*`. Confirm `Kick`, `Pojo`, `JsonFile`, `JsonString` no longer appear as low-coverage classes (open `build/reports/kover/html/index.html` in a browser and check the `input.config` / `input.json` package pages).

**Fallback (only if a check fails):**
- If `benchmark.**` still appears → replace `excludedSourceSets.addAll("jmh")` with `classes("com.salesforce.revoman.benchmark.**")` inside `excludes`.
- If `Kick`/`Pojo` still appear → `annotatedBy` didn't resolve under Kover 0.9.9; add explicit `classes("com.salesforce.revoman.input.config.Kick", "com.salesforce.revoman.input.json.Pojo", "com.salesforce.revoman.input.json.JsonFile", "com.salesforce.revoman.input.json.JsonString")`.
- Re-run Step 4 after any fallback.

- [ ] **Step 5: Record the honest baseline**

Run: `python3 -c "import re,html; d=open('build/reports/kover/html/index.html',encoding='utf-8').read(); f=re.search(r'<tfoot>(.*?)</tfoot>',d,re.S).group(1); print([html.unescape(re.sub('<[^>]+>',' ',c)).strip() for c in re.findall(r'<td[^>]*>(.*?)</td>',f,re.S)])"`
Expected: prints the totals row (Class/Method/Branch/Line/Instruction). **Write down the LINE % (4th value)** — this is the honest baseline for Task 8.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "build(kover): exclude generated/JMH/test-only code from coverage denominator"
```

---

## Task 2: TypeAdapter + UUIDAdapter tests (0% → covered)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/internal/json/adapters/TypeAdapterTest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/internal/json/adapters/UUIDAdapterTest.kt`

**Interfaces:**
- Consumes: `com.salesforce.revoman.internal.json.adapters.TypeAdapter` (object; `toJson(type: Type): String`, `fromJson(ignore: String): Type?`); `UUIDAdapter` (object; `toJson(uuid: UUID): String`, `fromJson(uuidStr: String): UUID`).
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write `TypeAdapterTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TypeAdapterTest {
  @Test
  fun `toJson renders a Type as its toString`() {
    assertThat(TypeAdapter.toJson(String::class.java)).isEqualTo("class java.lang.String")
  }

  @Test
  fun `fromJson always returns null (types are never deserialized)`() {
    assertThat(TypeAdapter.fromJson("anything")).isNull()
  }
}
```

- [ ] **Step 2: Write `UUIDAdapterTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UUIDAdapterTest {
  @Test
  fun `toJson renders a UUID as its canonical string`() {
    val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    assertThat(UUIDAdapter.toJson(uuid)).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
  }

  @Test
  fun `fromJson round-trips a canonical UUID string`() {
    val uuid = UUID.randomUUID()
    assertThat(UUIDAdapter.fromJson(uuid.toString())).isEqualTo(uuid)
  }

  @Test
  fun `fromJson throws on a malformed UUID string`() {
    assertThrows<IllegalArgumentException> { UUIDAdapter.fromJson("not-a-uuid") }
  }
}
```

- [ ] **Step 3: Run to verify they compile+pass**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.json.adapters.TypeAdapterTest" --tests "com.salesforce.revoman.internal.json.adapters.UUIDAdapterTest"`
Expected: PASS (5 tests). (These call pure functions directly — no red phase needed since the code already exists; the "failing" state is only that the tests don't yet exist.)

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/json/adapters/TypeAdapterTest.kt src/test/kotlin/com/salesforce/revoman/internal/json/adapters/UUIDAdapterTest.kt
git commit -m "test(json): cover TypeAdapter and UUIDAdapter"
```

---

## Task 3: RequestConfig factory tests (0% → covered)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/input/config/RequestConfigTest.kt`

**Interfaces:**
- Consumes: `RequestConfig.unmarshallRequest(preTxnStepPick, requestType)`; `unmarshallRequest(preTxnStepPick, requestType, customTypeAdapter: JsonAdapter<out Any>)`; `unmarshallRequest(preTxnStepPick, requestType, customTypeAdapterFactory: JsonAdapter.Factory)`. Fields: `preTxnStepPick`, `requestType`, `customTypeAdapter: Either<JsonAdapter<out Any>, JsonAdapter.Factory>?`. `PreTxnStepPick` is a functional interface: `PreTxnStepPick { _, _, _ -> true }`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write `RequestConfigTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.squareup.moshi.JsonAdapter
import org.junit.jupiter.api.Test

class RequestConfigTest {
  private val pick = PreTxnStepPick { _, _, _ -> true }

  @Test
  fun `unmarshallRequest without adapter leaves customTypeAdapter null`() {
    val config = RequestConfig.unmarshallRequest(pick, String::class.java)
    assertThat(config.preTxnStepPick).isSameInstanceAs(pick)
    assertThat(config.requestType).isEqualTo(String::class.java)
    assertThat(config.customTypeAdapter).isNull()
  }

  @Test
  fun `unmarshallRequest with a JsonAdapter stores it on the left`() {
    val adapter: JsonAdapter<out Any> = JsonAdapter.Factory { _, _, _ -> null }.let { _ ->
      object : JsonAdapter<String>() {
        override fun fromJson(reader: com.squareup.moshi.JsonReader): String? = null
        override fun toJson(writer: com.squareup.moshi.JsonWriter, value: String?) {}
      }
    }
    val config = RequestConfig.unmarshallRequest(pick, String::class.java, adapter)
    assertThat(config.customTypeAdapter!!.isLeft).isTrue()
    assertThat(config.customTypeAdapter!!.left).isSameInstanceAs(adapter)
  }

  @Test
  fun `unmarshallRequest with a Factory stores it on the right`() {
    val factory = JsonAdapter.Factory { _, _, _ -> null }
    val config = RequestConfig.unmarshallRequest(pick, String::class.java, factory)
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
    assertThat(config.customTypeAdapter!!.get()).isSameInstanceAs(factory)
  }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.RequestConfigTest"`
Expected: PASS (3 tests). If the `Either` accessor names differ, check the import — this repo uses `io.vavr.control.Either` whose API is `isLeft`/`isRight`/`getLeft()`/`get()`. If `.left` fails to resolve, use `.left` → `.getLeft()` and `.get()` for the right value.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/input/config/RequestConfigTest.kt
git commit -m "test(config): cover RequestConfig factory overloads"
```

---

## Task 4: ResponseConfig factory tests (30% → covered)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/input/config/ResponseConfigTest.kt`

**Interfaces:**
- Consumes: `ResponseConfig.unmarshallResponse(...)`, `unmarshallSuccessResponse(...)`, `unmarshallErrorResponse(...)` (each has plain / `JsonAdapter` / `Factory` overloads). Fields: `postTxnStepPick`, `ifSuccess: Boolean?`, `responseType`, `customTypeAdapter`. `PostTxnStepPick` is functional: `PostTxnStepPick { _, _ -> true }`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write `ResponseConfigTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.squareup.moshi.JsonAdapter
import org.junit.jupiter.api.Test

class ResponseConfigTest {
  private val pick = PostTxnStepPick { _, _ -> true }
  private val factory = JsonAdapter.Factory { _, _, _ -> null }

  @Test
  fun `unmarshallResponse sets ifSuccess null`() {
    val config = ResponseConfig.unmarshallResponse(pick, String::class.java)
    assertThat(config.ifSuccess).isNull()
    assertThat(config.responseType).isEqualTo(String::class.java)
    assertThat(config.customTypeAdapter).isNull()
  }

  @Test
  fun `unmarshallSuccessResponse sets ifSuccess true`() {
    assertThat(ResponseConfig.unmarshallSuccessResponse(pick, String::class.java).ifSuccess).isTrue()
  }

  @Test
  fun `unmarshallErrorResponse sets ifSuccess false`() {
    assertThat(ResponseConfig.unmarshallErrorResponse(pick, String::class.java).ifSuccess).isFalse()
  }

  @Test
  fun `success response with a Factory stores it on the right`() {
    val config = ResponseConfig.unmarshallSuccessResponse(pick, String::class.java, factory)
    assertThat(config.ifSuccess).isTrue()
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
  }

  @Test
  fun `error response with a Factory stores it on the right`() {
    val config = ResponseConfig.unmarshallErrorResponse(pick, String::class.java, factory)
    assertThat(config.ifSuccess).isFalse()
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
  }

  @Test
  fun `plain response with a Factory keeps ifSuccess null`() {
    val config = ResponseConfig.unmarshallResponse(pick, String::class.java, factory)
    assertThat(config.ifSuccess).isNull()
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
  }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.ResponseConfigTest"`
Expected: PASS (6 tests).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/input/config/ResponseConfigTest.kt
git commit -m "test(config): cover ResponseConfig success/error/plain factory overloads"
```

---

## Task 5: RequestFailure + ResponseFailure tests (38% / 54% → covered)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/output/report/failure/RequestFailureTest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/output/report/failure/ResponseFailureTest.kt`

**Interfaces:**
- Consumes: `RequestFailure.PreReqJSFailure/UnmarshallRequestFailure/HttpRequestFailure(failure: Throwable, requestInfo: TxnInfo<Request>)` with `exeType` = `PRE_REQ_JS`/`UNMARSHALL_REQUEST`/`HTTP_REQUEST`. `ResponseFailure.PostResJSFailure/UnmarshallResponseFailure(failure, requestInfo, responseInfo: TxnInfo<Response>)` with `exeType` = `POST_RES_JS`/`UNMARSHALL_RESPONSE`. `TxnInfo` built via `TxnInfo(txnObjType=..., txnObj=..., httpMsg=..., moshiReVoman=...)`; `initMoshi()` from `MoshiReVoman.Companion`. Build a request http msg via `Request(method=POST.toString(), url=Url("...")).toHttpRequest(moshiReVoman)`; response via `Response(OK)`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write `RequestFailureTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.PreReqJSFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.UnmarshallRequestFailure
import org.http4k.core.Method.POST
import org.junit.jupiter.api.Test

class RequestFailureTest {
  private val moshiReVoman = initMoshi()

  private fun requestInfo(): TxnInfo<org.http4k.core.Request> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "req",
      httpMsg = Request(method = POST.toString(), url = Url("https://x.test/a")).toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )

  @Test
  fun `each RequestFailure subtype reports its exeType`() {
    val boom = RuntimeException("boom")
    assertThat(PreReqJSFailure(boom, requestInfo()).exeType).isEqualTo(PRE_REQ_JS)
    assertThat(UnmarshallRequestFailure(boom, requestInfo()).exeType).isEqualTo(UNMARSHALL_REQUEST)
    assertThat(HttpRequestFailure(boom, requestInfo()).exeType).isEqualTo(HTTP_REQUEST)
  }

  @Test
  fun `RequestFailure data-class equality holds for equal fields`() {
    val boom = RuntimeException("boom")
    val info = requestInfo()
    assertThat(HttpRequestFailure(boom, info)).isEqualTo(HttpRequestFailure(boom, info))
  }

  @Test
  fun `RequestFailure exposes its failure and requestInfo`() {
    val boom = RuntimeException("boom")
    val info = requestInfo()
    val failure = PreReqJSFailure(boom, info)
    assertThat(failure.failure).isSameInstanceAs(boom)
    assertThat(failure.requestInfo).isSameInstanceAs(info)
  }
}
```

- [ ] **Step 2: Write `ResponseFailureTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.UnmarshallResponseFailure
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class ResponseFailureTest {
  private val moshiReVoman = initMoshi()

  private fun requestInfo(): TxnInfo<org.http4k.core.Request> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "req",
      httpMsg = Request(method = POST.toString(), url = Url("https://x.test/a")).toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )

  private fun responseInfo(): TxnInfo<Response> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "res",
      httpMsg = Response(OK),
      moshiReVoman = moshiReVoman,
    )

  @Test
  fun `each ResponseFailure subtype reports its exeType`() {
    val boom = RuntimeException("boom")
    assertThat(PostResJSFailure(boom, requestInfo(), responseInfo()).exeType).isEqualTo(POST_RES_JS)
    assertThat(UnmarshallResponseFailure(boom, requestInfo(), responseInfo()).exeType)
      .isEqualTo(UNMARSHALL_RESPONSE)
  }

  @Test
  fun `ResponseFailure exposes failure, requestInfo and responseInfo`() {
    val boom = RuntimeException("boom")
    val req = requestInfo()
    val res = responseInfo()
    val failure = UnmarshallResponseFailure(boom, req, res)
    assertThat(failure.failure).isSameInstanceAs(boom)
    assertThat(failure.requestInfo).isSameInstanceAs(req)
    assertThat(failure.responseInfo).isSameInstanceAs(res)
  }
}
```

- [ ] **Step 3: Run**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.failure.RequestFailureTest" --tests "com.salesforce.revoman.output.report.failure.ResponseFailureTest"`
Expected: PASS (5 tests). If `TxnInfo`'s constructor params differ from `(txnObjType, txnObj, httpMsg, moshiReVoman)`, open `src/main/kotlin/com/salesforce/revoman/output/report/TxnInfo.kt` and match the actual primary-constructor parameter names.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/output/report/failure/RequestFailureTest.kt src/test/kotlin/com/salesforce/revoman/output/report/failure/ResponseFailureTest.kt
git commit -m "test(failure): cover Request/ResponseFailure subtypes"
```

---

## Task 6: StepPick OOTB pick tests (45% → covered)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/input/config/StepPickPickUtilsTest.kt`

**Interfaces:**
- Consumes:
  - `StepPick.ExeStepPick.PickUtils`: `withName(stepName)`, `inFolder(folderPath)`, `stepEndingWithURIPathOfAny(vararg)`, `stepContainingURIPathOfAny(vararg)` — each returns an `ExeStepPick` with `pick(step: Step): Boolean`.
  - `StepPick.PreTxnStepPick.PickUtils`: `beforeStepName`, `beforeStepContainingURIPathOfAny`, `beforeStepEndingWithURIPathOfAny` — `pick(currentStep, currentRequestInfo: TxnInfo<Request>, rundown: Rundown): Boolean`.
  - `StepPick.PostTxnStepPick.PickUtils`: `afterStepName`, `afterStepContainingURIPathOfAny` — `pick(currentStepReport: StepReport, rundown: Rundown): Boolean`.
  - `Step(index="1", rawPMStep = Item(name = "...", request = Request(url = Url("https://x.test/objects/foo"))))`. `Item`/`Request`/`Url` from `com.salesforce.revoman.internal.postman.template`.
  - `TxnInfo` and `Rundown` fixtures as in Task 5 / `PickHooksMaterializeTest` (`Rundown(mutableEnv = PostmanEnvironment(), haltOnFailureOfTypeExcept = emptyMap(), providedStepsToExecuteCount = 0)`).
  - `StepReport(step, Right(requestInfo), null, Right(responseInfo), pmEnvSnapshot = PostmanEnvironment())` using `arrow.core.Either.Right`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write `StepPickPickUtilsTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import arrow.core.Either.Right
import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.stepContainingURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.stepEndingWithURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.withName
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepName
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.PickUtils.beforeStepEndingWithURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.PickUtils.beforeStepName
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class StepPickPickUtilsTest {
  private val moshiReVoman = initMoshi()

  private fun step(name: String, url: String = "https://x.test/v1/objects/foo") =
    Step(index = "1", rawPMStep = Item(name = name, request = Request(url = Url(url))))

  private fun requestInfo(url: String = "https://x.test/v1/objects/foo"): TxnInfo<org.http4k.core.Request> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "req",
      httpMsg = Request(method = POST.toString(), url = Url(url)).toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )

  private fun responseInfo(): TxnInfo<Response> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "res",
      httpMsg = Response(OK),
      moshiReVoman = moshiReVoman,
    )

  private fun rundown(): Rundown =
    Rundown(
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  private fun stepReport(name: String, url: String = "https://x.test/v1/objects/foo"): StepReport =
    StepReport(
      step(name, url),
      Right(requestInfo(url)),
      null,
      Right(responseInfo()),
      pmEnvSnapshot = PostmanEnvironment(),
    )

  @Test
  fun `ExeStepPick withName matches by exact step name`() {
    assertThat(withName("login").pick(step("login"))).isTrue()
    assertThat(withName("login").pick(step("logout"))).isFalse()
  }

  @Test
  fun `ExeStepPick uri picks match by raw URL`() {
    assertThat(stepEndingWithURIPathOfAny("objects/foo").pick(step("s"))).isTrue()
    assertThat(stepContainingURIPathOfAny("v1/objects").pick(step("s"))).isTrue()
    assertThat(stepContainingURIPathOfAny("nope").pick(step("s"))).isFalse()
  }

  @Test
  fun `PreTxnStepPick picks match name and uri`() {
    assertThat(beforeStepName("login").pick(step("login"), requestInfo(), rundown())).isTrue()
    assertThat(beforeStepEndingWithURIPathOfAny("objects/foo").pick(step("s"), requestInfo(), rundown()))
      .isTrue()
  }

  @Test
  fun `PostTxnStepPick picks match name and uri`() {
    assertThat(afterStepName("login").pick(stepReport("login"), rundown())).isTrue()
    assertThat(afterStepContainingURIPathOfAny("v1/objects").pick(stepReport("s"), rundown())).isTrue()
  }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.StepPickPickUtilsTest"`
Expected: PASS (4 tests). If `StepReport`'s constructor arity/param names differ, open `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt` and match (also see `PickHooksMaterializeTest.kt:113-120` for a working `StepReport(...)` call).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/input/config/StepPickPickUtilsTest.kt
git commit -m "test(config): cover StepPick OOTB pick utilities"
```

---

## Task 7: JsonWriterUtils tests (45% → covered)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/input/json/JsonWriterUtilsTest.kt`

**Interfaces:**
- Consumes (extension fns on `JsonWriter`, package `com.salesforce.revoman.input.json`): `JsonWriter.objW(name, obj, fn)`, `JsonWriter.string(name, value)`, `JsonWriter.bool(name, value)`, `JsonWriter.integer(name, value)`, `JsonWriter.doubl(name, value)`, `JsonWriter.lng(name, value)`, `listW(name, list, writer, block)`, `JsonWriter.mapW(map, dynamicJsonAdapter)`, `JsonWriter.writeProps(pojoType, bean, excludePropTypes, dynamicJsonAdapter)`. `NestedNodeWriter<T>` is a functional interface `{ write(t) }`.
- Build a writer over an okio Buffer: `val buffer = Buffer(); val writer = JsonWriter.of(buffer)`; read back with `buffer.readUtf8()`. Get a dynamic adapter via `initMoshi()` — `initMoshi().adapter(Any::class.java)` — confirm the accessor name against `MoshiReVoman`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Confirm the dynamic-adapter accessor**

Run: `grep -n "fun adapter\|fun <T> adapter\|dynamicJsonAdapter\|fun toJson\|class MoshiReVoman" src/main/kotlin/com/salesforce/revoman/internal/json/MoshiReVoman.kt`
Expected: find the method that yields a `JsonAdapter<Any>`. Note its exact name for Step 2 (`mapW`/`writeProps` need it). If MoshiReVoman wraps Moshi, get the underlying `com.squareup.moshi.Moshi` and call `.adapter(Any::class.java)`.

- [ ] **Step 2: Write `JsonWriterUtilsTest.kt` (failing)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okio.Buffer
import org.junit.jupiter.api.Test

class JsonWriterUtilsTest {
  private val anyAdapter = Moshi.Builder().build().adapter(Any::class.java)

  private fun write(block: JsonWriter.() -> Unit): String {
    val buffer = Buffer()
    val writer = JsonWriter.of(buffer)
    writer.beginObject()
    writer.block()
    writer.endObject()
    return buffer.readUtf8()
  }

  @Test
  fun `string writes value and null`() {
    assertThat(write { string("k", "v") }).isEqualTo("""{"k":"v"}""")
    assertThat(write { string("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `bool writes value and null`() {
    assertThat(write { bool("k", true) }).isEqualTo("""{"k":true}""")
    assertThat(write { bool("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `integer writes value and null`() {
    assertThat(write { integer("k", 7) }).isEqualTo("""{"k":7}""")
    assertThat(write { integer("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `doubl writes value and null`() {
    assertThat(write { doubl("k", 1.5) }).isEqualTo("""{"k":1.5}""")
    assertThat(write { doubl("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `lng writes value and null`() {
    assertThat(write { lng("k", 9L) }).isEqualTo("""{"k":9}""")
    assertThat(write { lng("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `objW writes a nested object and a null`() {
    assertThat(write { objW("k", "x") { string("inner", "y") } })
      .isEqualTo("""{"k":{"inner":"y"}}""")
    assertThat(write<Unit> { objW<String>("k", null) { } }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `listW writes a list and a null`() {
    val buffer = Buffer()
    val writer = JsonWriter.of(buffer)
    writer.beginObject()
    listW("k", listOf("a", "b"), writer) { writer.value(it) }
    writer.endObject()
    assertThat(buffer.readUtf8()).isEqualTo("""{"k":["a","b"]}""")

    val buffer2 = Buffer()
    val writer2 = JsonWriter.of(buffer2)
    writer2.beginObject()
    writer2.name("k")
    listW(null, writer2) { writer2.value(it as String) }
    writer2.endObject()
    assertThat(buffer2.readUtf8()).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `mapW writes entries and null`() {
    assertThat(write { mapW(mapOf("a" to "1"), anyAdapter) }).isEqualTo("""{"a":"1"}""")
    assertThat(write { name("k"); mapW(null, anyAdapter) }).isEqualTo("""{"k":null}""")
  }
}
```

- [ ] **Step 3: Run**

Run: `./gradlew test --tests "com.salesforce.revoman.input.json.JsonWriterUtilsTest"`
Expected: PASS (8 tests). If the `objW<String>("k", null) { }` generic call fails to infer, annotate: `objW<String>("k", null, fn = { })`. If number formatting differs (e.g. `1.5` vs `1.5E0`), adjust the expected string to what Moshi's `JsonWriter` actually emits — run once, read the failure's actual value, correct the literal.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/input/json/JsonWriterUtilsTest.kt
git commit -m "test(json): cover JsonWriterUtils writer helpers and null branches"
```

---

## Task 8: Recalibrate the coverage ratchet

**Files:**
- Modify: `build.gradle.kts` (the `minBound(69)` line inside `kover { reports { total { verify { rule } } } }`)

**Interfaces:**
- Consumes: the honest baseline LINE % recorded in Task 1 Step 5, plus the coverage gained by Tasks 2-7.
- Produces: the enforced floor for `./gradlew build`.

- [ ] **Step 1: Regenerate the report after all tests exist**

Run: `./gradlew test koverHtmlReport`
Then read the new total LINE %:
Run: `python3 -c "import re,html; d=open('build/reports/kover/html/index.html',encoding='utf-8').read(); f=re.search(r'<tfoot>(.*?)</tfoot>',d,re.S).group(1); print([html.unescape(re.sub('<[^>]+>',' ',c)).strip() for c in re.findall(r'<td[^>]*>(.*?)</td>',f,re.S)])"`
Expected: LINE % now ≈ 85% or higher. **Record the integer floor value `N` = floor(measured %) − 1** (one-point safety margin, matching the existing "loose regression floor" intent).

- [ ] **Step 2: Update `minBound`**

Edit `build.gradle.kts`: change `minBound(69)` to `minBound(N)` (the value from Step 1). Update the trailing comment to state the new calibration date and that the floor tracks the honest (post-exclude) total.

- [ ] **Step 3: Format**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify the gate holds under a full build**

Run: `./gradlew test koverVerify`
Expected: BUILD SUCCESSFUL (the unit-only total clears the new floor). If it fails by a hair, lower `N` by 1 and re-run (the floor must not exceed the actual unit-only total).

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build(kover): raise line-coverage floor to honest post-exclude baseline"
```

---

## Task 9: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests green (including the 8 new files).

- [ ] **Step 2: Run spotless + kover check**

Run: `./gradlew spotlessCheck koverVerify`
Expected: BUILD SUCCESSFUL — formatting clean, coverage floor met.

- [ ] **Step 3: Confirm the new classes moved**

Open `build/reports/kover/html/index.html`; confirm `RequestConfig`, `ResponseConfig`, `TypeAdapter`, `UUIDAdapter`, `StepPick`, `JsonWriterUtils`, `RequestFailure`, `ResponseFailure` are all substantially higher than the audit baseline (RequestConfig/TypeAdapter/UUIDAdapter should be ~100%).

- [ ] **Step 4 (optional): note residual gaps**

If the total didn't reach ~85%, note which majority-covered classes (`JsonPojoUtils`, `Rundown`, `RundownJsonWriter`) remain — these were explicitly deferred in the spec and are candidate follow-ups, not part of this plan.

---

## Self-Review Notes

- **Spec coverage:** §1 excludes → Task 1. §2 tests 1-8 → Tasks 2-7 (adapters folded into one task, failures folded into one task, per "fold setup into the deliverable" right-sizing). §3 ratchet → Task 8. Full-suite verification → Task 9. All spec sections mapped.
- **Placeholder scan:** every code step has full code; every run step has an exact command + expected result; fallbacks are concrete.
- **Type consistency:** `TxnInfo(txnObjType, txnObj, httpMsg, moshiReVoman)`, `Step(index, rawPMStep)`, `Rundown(mutableEnv, haltOnFailureOfTypeExcept, providedStepsToExecuteCount)`, `StepReport(step, Right(reqInfo), null, Right(resInfo), pmEnvSnapshot)`, Vavr `Either` (`isLeft`/`isRight`/`get()`) — all taken from real fixtures in `PickHooksMaterializeTest.kt` / `RundownJsonWriterTest.kt`. Each task flags the one file to open if a signature differs.

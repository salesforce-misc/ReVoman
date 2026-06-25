# Failing `pm.test` fails the step (and Rundown) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A failing `pm.test(...)` assertion makes its step (and therefore the Rundown) report FAILURE by default, feeding the existing failure / halt / ignore machinery, while preserving the full per-assertion record and surfacing failures in JSON output.

**Architecture:** Model a failing assertion as a new `ExeFailure` subtype `PmTestFailure` (phase-tagged via the existing `PRE_REQ_JS` / `POST_RES_JS` `ExeType`s). Feed it into `StepReport.failure(...)` at **lowest precedence** so `isSuccessful` / `shouldHaltExecution` / `haltOnFailureOfTypeExcept` work unchanged. Additionally expose an always-computed `StepReport.pmTestFailure: List<PmTestFailure>` so a co-occurring HTTP failure AND a pm.test failure are BOTH surfaced. Phase is stamped onto each `PmTestAssertion` at record time in `PmJsEval` (the `ScriptTarget` is already in scope).

**Tech Stack:** Kotlin, Gradle, Kotest + JUnit5 (`src/test`), Truth + JUnit5 (`src/integrationTest`), Moshi `JsonWriter`, GraalJS Postman sandbox, http4k, Vavr `Either` (report types), arrow `Either` (executor).

## Global Constraints

- JDK 21+. Kotlin, four-space indent. Functional style (map/fold over `when`/loops where natural); follow the file you are editing.
- Postman/newman parity: a failing `pm.test` makes the run report failure but execution CONTINUES to the next step by default (only `haltOnAnyFailure=true` halts).
- Backward compatibility: `PmTestAssertion`'s new field MUST be defaulted (`@JvmOverloads` already present) so existing constructions and external callers still compile.
- Precedence: existing failures (request / pre-hook / response / non-2xx HTTP / post-hook / polling) keep priority in the single `failure` Either. `PmTestFailure` is LOWEST precedence; within it, pre-req before post-res.
- `skipped` assertions (`pm.test.skip`) NEVER contribute to failure.
- License header block at the top of every new Kotlin file (copy verbatim from any sibling file in the same package).
- Build/verify: `./gradlew test` (unit), `./gradlew integrationTest` (integration), `./gradlew spotlessApply` before commit.

---

## File Structure

- `src/main/kotlin/com/salesforce/revoman/output/report/PmTestAssertion.kt` — **Modify**: add `exeType` field.
- `src/main/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailure.kt` — **Create**: sealed `ExeFailure` subtype + builder.
- `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt` — **Modify**: `pmTestFailure` field + `failure()` precedence.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt` — **Modify**: stamp `exeType` at record time.
- `src/main/kotlin/com/salesforce/revoman/output/RundownJsonWriter.kt` — **Modify**: serialize `pmTestAssertions` array.
- `src/test/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailureTest.kt` — **Create**.
- `src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt` — **Create**.
- `src/test/kotlin/com/salesforce/revoman/PmTestPhaseTagE2ETest.kt` — **Create** (loopback, proves stamping).
- `src/test/kotlin/com/salesforce/revoman/PmTestFailureE2ETest.kt` — **Create** (loopback, proves step FAILED + continue + JSON).
- `src/test/kotlin/com/salesforce/revoman/output/RundownJsonWriterTest.kt` — **Modify**: add assertions-array test.
- `src/test/resources/pm-templates/v3/pm-test-phases/...` — **Create** fixture (passing assertions, both phases).
- `src/test/resources/pm-templates/v3/pm-test-fail/...` — **Create** fixture (one failing assertion).

---

## Task 1: Add `exeType` phase tag to `PmTestAssertion`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/report/PmTestAssertion.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt` (created here, extended in Task 3)

**Interfaces:**
- Consumes: `com.salesforce.revoman.output.ExeType`
- Produces: `PmTestAssertion(name: String, passed: Boolean, skipped: Boolean = false, error: String? = null, exeType: ExeType = ExeType.POST_RES_JS)`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepReportPmTestTest {

  @Test
  fun `PmTestAssertion exeType defaults to POST_RES_JS and is settable`() {
    PmTestAssertion("t", passed = true).exeType shouldBe POST_RES_JS
    PmTestAssertion("t", passed = false, exeType = PRE_REQ_JS).exeType shouldBe PRE_REQ_JS
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.StepReportPmTestTest"`
Expected: COMPILE FAILURE — `PmTestAssertion` has no `exeType` parameter.

- [ ] **Step 3: Add the field**

In `PmTestAssertion.kt`, add the import and the field. The full file becomes:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.output.ExeType

/**
 * The result of a single `pm.test(name, fn)` assertion block reported by the Postman sandbox.
 * Attached to [StepReport.pmTestAssertions]. A failing assertion is DATA here (not a thrown error):
 * [passed] is false and [error] carries the chai/AssertionError message. [skipped] is true for
 * `pm.test.skip(...)`. [exeType] records which script phase produced it ([ExeType.PRE_REQ_JS] for a
 * pre-request script, [ExeType.POST_RES_JS] for a test script).
 */
data class PmTestAssertion
@JvmOverloads
constructor(
  @JvmField val name: String,
  @JvmField val passed: Boolean,
  @JvmField val skipped: Boolean = false,
  @JvmField val error: String? = null,
  @JvmField val exeType: ExeType = ExeType.POST_RES_JS,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.StepReportPmTestTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/report/PmTestAssertion.kt \
        src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt
git commit -m "feat(pm): tag PmTestAssertion with its script phase (exeType)"
```

---

## Task 2: New `PmTestFailure` ExeFailure subtype + builder

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailure.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailureTest.kt`

**Interfaces:**
- Consumes: `ExeFailure` (abstract `exeType: ExeType`, `failure: Throwable`), `PmTestAssertion`, `ExeType.PRE_REQ_JS`, `ExeType.POST_RES_JS`
- Produces:
  - `sealed class PmTestFailure : ExeFailure()` with `abstract val failedAssertions: List<PmTestAssertion>`
  - `data class PmTestFailure.PreReqJsTestFailure(failure: AssertionError, failedAssertions: List<PmTestAssertion>)` → `exeType = PRE_REQ_JS`
  - `data class PmTestFailure.PostResJsTestFailure(failure: AssertionError, failedAssertions: List<PmTestAssertion>)` → `exeType = POST_RES_JS`
  - `fun buildPmTestFailures(assertions: List<PmTestAssertion>): List<PmTestFailure>` (top-level in the same file) — pre-req entry first, post-res second; empty when none failed; `skipped` excluded.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailureTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.failure.PmTestFailure.PostResJsTestFailure
import com.salesforce.revoman.output.report.failure.PmTestFailure.PreReqJsTestFailure
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PmTestFailureTest {

  @Test
  fun `no failed assertions yields no PmTestFailure`() {
    buildPmTestFailures(
      listOf(
        PmTestAssertion("ok", passed = true, exeType = POST_RES_JS),
        PmTestAssertion("skipped", passed = false, skipped = true, exeType = POST_RES_JS),
      )
    ) shouldHaveSize 0
  }

  @Test
  fun `a failed post-res assertion yields one PostResJsTestFailure with a descriptive message`() {
    val failures =
      buildPmTestFailures(
        listOf(
          PmTestAssertion("status is 200", passed = false, error = "expected 500 to equal 200", exeType = POST_RES_JS)
        )
      )
    failures shouldHaveSize 1
    val f = failures.single()
    f.shouldBeInstanceOf<PostResJsTestFailure>()
    f.exeType shouldBe POST_RES_JS
    f.failedAssertions shouldHaveSize 1
    f.failure.message!! shouldContain "status is 200"
    f.failure.message!! shouldContain "expected 500 to equal 200"
  }

  @Test
  fun `failures from both phases produce pre-req first then post-res`() {
    val failures =
      buildPmTestFailures(
        listOf(
          PmTestAssertion("pre fail", passed = false, exeType = PRE_REQ_JS),
          PmTestAssertion("post fail", passed = false, exeType = POST_RES_JS),
        )
      )
    failures shouldHaveSize 2
    failures[0].shouldBeInstanceOf<PreReqJsTestFailure>()
    failures[1].shouldBeInstanceOf<PostResJsTestFailure>()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.failure.PmTestFailureTest"`
Expected: COMPILE FAILURE — `PmTestFailure` / `buildPmTestFailures` do not exist.

- [ ] **Step 3: Create the failure type + builder**

Create `src/main/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailure.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.PmTestAssertion

/**
 * A failing `pm.test(...)` assertion modeled as an [ExeFailure] so it feeds the same
 * failure/halt/ignore machinery as every other failure. [failedAssertions] are the `passed=false`,
 * non-skipped assertions of this phase; [failure] is a synthesized [AssertionError] whose message
 * concatenates their names + chai errors (mirrors how `PollingFailure` synthesizes its Throwable).
 */
sealed class PmTestFailure : ExeFailure() {
  abstract val failedAssertions: List<PmTestAssertion>

  data class PreReqJsTestFailure(
    override val failure: AssertionError,
    override val failedAssertions: List<PmTestAssertion>,
  ) : PmTestFailure() {
    override val exeType = PRE_REQ_JS
  }

  data class PostResJsTestFailure(
    override val failure: AssertionError,
    override val failedAssertions: List<PmTestAssertion>,
  ) : PmTestFailure() {
    override val exeType = POST_RES_JS
  }
}

private fun message(failed: List<PmTestAssertion>): String =
  failed.joinToString("; ") { "${it.name}: ${it.error ?: "assertion failed"}" }

/**
 * Groups the FAILED (`passed=false`, non-skipped) assertions by phase into 0–2 [PmTestFailure]
 * entries, pre-request first then test (the order a step's scripts run). `skipped` assertions never
 * contribute.
 */
fun buildPmTestFailures(assertions: List<PmTestAssertion>): List<PmTestFailure> {
  val failed = assertions.filter { !it.passed && !it.skipped }
  val preReq = failed.filter { it.exeType == PRE_REQ_JS }
  val postRes = failed.filter { it.exeType == POST_RES_JS }
  return listOfNotNull(
    preReq.takeIf { it.isNotEmpty() }?.let {
      PmTestFailure.PreReqJsTestFailure(AssertionError(message(it)), it)
    },
    postRes.takeIf { it.isNotEmpty() }?.let {
      PmTestFailure.PostResJsTestFailure(AssertionError(message(it)), it)
    },
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.failure.PmTestFailureTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailure.kt \
        src/test/kotlin/com/salesforce/revoman/output/report/failure/PmTestFailureTest.kt
git commit -m "feat(pm): add PmTestFailure ExeFailure subtype + builder"
```

---

## Task 3: Wire `pmTestFailure` into `StepReport` + `failure()` precedence (core behavior)

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt` (extend Task 1's file)

**Interfaces:**
- Consumes: `buildPmTestFailures(...)`, `PmTestFailure`, existing `pmTestAssertions` ctor param (`StepReport.kt:41`)
- Produces:
  - `StepReport.pmTestFailure: List<PmTestFailure>` (computed `val`, always populated from `pmTestAssertions`)
  - `failure` now non-null (and `isSuccessful=false`, `exeTypeForFailure` set) when a pm.test failed and no higher-precedence failure exists.

**Note on construction in tests:** the test fixtures use the *secondary* (arrow.core) constructor, which has no `pmTestAssertions` param. Set assertions via `.copy(pmTestAssertions = ...)` on the resulting report — `copy` re-runs the primary constructor, recomputing `failure`/`pmTestFailure`/`isSuccessful` (the same path `ReVoman.kt:357` relies on).

- [ ] **Step 1: Write the failing tests**

Append to `src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt` (add imports at top, methods in the class):

```kotlin
// add these imports
import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.failure.HttpStatusUnsuccessful
import com.salesforce.revoman.output.report.failure.PmTestFailure.PostResJsTestFailure
import com.salesforce.revoman.output.report.failure.PmTestFailure.PreReqJsTestFailure
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
```

```kotlin
  private val moshiReVoman = initMoshi()

  private fun okReport(status: org.http4k.core.Status = OK): StepReport {
    val rawRequest = Request(method = GET.toString(), url = Url("https://test.example.com/x"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "req",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "res",
        httpMsg = Response(status).body("{}"),
        moshiReVoman = moshiReVoman,
      )
    return StepReport(
      Step("1", Item(request = rawRequest)),
      Right(requestInfo),
      null,
      Right(responseInfo),
      pmEnvSnapshot = PostmanEnvironment(),
    )
  }

  @Test
  fun `all assertions passing keeps the step successful`() {
    val report =
      okReport().copy(pmTestAssertions = listOf(PmTestAssertion("ok", passed = true, exeType = POST_RES_JS)))
    report.isSuccessful shouldBe true
    report.pmTestFailure shouldHaveSize 0
  }

  @Test
  fun `a failing post-res assertion fails the step`() {
    val report =
      okReport().copy(
        pmTestAssertions =
          listOf(PmTestAssertion("status is 200", passed = false, error = "expected 200", exeType = POST_RES_JS))
      )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe POST_RES_JS
    report.exeFailure.shouldBeInstanceOf<PostResJsTestFailure>()
    report.pmTestFailure shouldHaveSize 1
  }

  @Test
  fun `a failing pre-req assertion fails the step tagged PRE_REQ_JS`() {
    val report =
      okReport().copy(
        pmTestAssertions = listOf(PmTestAssertion("pre", passed = false, exeType = PRE_REQ_JS))
      )
    report.isSuccessful shouldBe false
    report.exeTypeForFailure shouldBe PRE_REQ_JS
    report.exeFailure.shouldBeInstanceOf<PreReqJsTestFailure>()
  }

  @Test
  fun `both phases failing - failure is pre-req first, pmTestFailure lists both`() {
    val report =
      okReport().copy(
        pmTestAssertions =
          listOf(
            PmTestAssertion("pre", passed = false, exeType = PRE_REQ_JS),
            PmTestAssertion("post", passed = false, exeType = POST_RES_JS),
          )
      )
    report.exeFailure.shouldBeInstanceOf<PreReqJsTestFailure>()
    report.pmTestFailure shouldHaveSize 2
  }

  @Test
  fun `HTTP non-2xx takes precedence but pm test failure is still surfaced (surface both)`() {
    val report =
      okReport(BAD_REQUEST).copy(
        pmTestAssertions = listOf(PmTestAssertion("body check", passed = false, exeType = POST_RES_JS))
      )
    report.isSuccessful shouldBe false
    // Primary cause is the HTTP status, NOT the assertion.
    report.failure!!.isRight shouldBe true
    report.failure!!.get().shouldBeInstanceOf<HttpStatusUnsuccessful>()
    // ...yet the assertion failure is independently visible.
    report.pmTestFailure shouldHaveSize 1
  }

  @Test
  fun `skipped failing assertions do not fail the step`() {
    val report =
      okReport().copy(
        pmTestAssertions =
          listOf(PmTestAssertion("skipme", passed = false, skipped = true, exeType = POST_RES_JS))
      )
    report.isSuccessful shouldBe true
    report.pmTestFailure shouldHaveSize 0
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.StepReportPmTestTest"`
Expected: COMPILE FAILURE — `StepReport.pmTestFailure` does not exist; behavior assertions fail because `failure()` ignores assertions.

- [ ] **Step 3: Wire into StepReport**

In `StepReport.kt`:

(a) Add imports near the other `failure.*` imports (lines 14-20):

```kotlin
import com.salesforce.revoman.output.report.failure.PmTestFailure
import com.salesforce.revoman.output.report.failure.buildPmTestFailures
```

(b) Add the `pmTestFailure` computed `val` **before** `failure` (it must be initialized first — Kotlin property init follows declaration order). Insert immediately before the existing `failure` declaration at line 72:

```kotlin
  /**
   * pm.test failures of this step, grouped by phase (0–2 entries, pre-request first). ALWAYS
   * populated when any assertion failed, INDEPENDENT of [failure]'s precedence — so a co-occurring
   * HTTP/transport failure (which wins [failure]) does not hide the assertion failure.
   */
  @JvmField val pmTestFailure: List<PmTestFailure> = buildPmTestFailures(pmTestAssertions)

```

(c) Pass it into the `failure(...)` call (replace lines 73-74):

```kotlin
  @JvmField
  val failure: Either<ExeFailure, HttpStatusUnsuccessful>? =
    failure(
      requestInfo,
      preStepHookFailure,
      responseInfo,
      postStepHookFailure,
      pollingFailure,
      pmTestFailure,
    )
```

(d) Extend the private `failure(...)` helper. Change its signature (line 120-126) to add a parameter, and add the lowest-precedence branch in the deepest `else` (currently `pollingFailure != null -> left(pollingFailure)` then `else -> null`, lines 142-146):

```kotlin
    private fun failure(
      requestInfo: Either<out ExeFailure, TxnInfo<Request>>? = null,
      preStepHookFailure: PreStepHookFailure? = null,
      responseInfo: Either<out ExeFailure, TxnInfo<Response>>? = null,
      postStepHookFailure: PostStepHookFailure? = null,
      pollingFailure: PollingFailure? = null,
      pmTestFailure: List<PmTestFailure> = emptyList(),
    ): Either<ExeFailure, HttpStatusUnsuccessful>? =
```

and the innermost branch becomes:

```kotlin
                          when {
                            postStepHookFailure != null -> left(postStepHookFailure)
                            pollingFailure != null -> left(pollingFailure)
                            pmTestFailure.isNotEmpty() -> left(pmTestFailure.first())
                            else -> null
                          }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.StepReportPmTestTest"`
Expected: PASS (all methods)

- [ ] **Step 5: Run the full report-package suite to confirm no regression**

Run: `./gradlew test --tests "com.salesforce.revoman.output.report.*"`
Expected: PASS (existing StepReportTest / StepReportPollingTest / StepReportEnvVarsTest still green)

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/report/StepReport.kt \
        src/test/kotlin/com/salesforce/revoman/output/report/StepReportPmTestTest.kt
git commit -m "feat(pm): failing pm.test fails the step; surface both vs HTTP failure"
```

---

## Task 4: Stamp `exeType` at record time in `PmJsEval` + phase-tag E2E

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt`
- Create: `src/test/resources/pm-templates/v3/pm-test-phases/check.request.yaml`
- Create: `src/test/resources/pm-templates/v3/pm-test-phases/.resources/definition.yaml`
- Create: `src/test/kotlin/com/salesforce/revoman/PmTestPhaseTagE2ETest.kt`

**Interfaces:**
- Consumes: `runSandboxScript`'s `target: ScriptTarget` (`PmJsEval.kt:106`), `ScriptTarget.PRE_REQUEST` / `ScriptTarget.TEST`, `ExeType.PRE_REQ_JS` / `ExeType.POST_RES_JS`
- Produces: every recorded `PmTestAssertion` carries the phase's `ExeType`.

- [ ] **Step 1: Write the failing E2E test + fixture**

Create `src/test/resources/pm-templates/v3/pm-test-phases/.resources/definition.yaml`:

```yaml
$kind: collection
```

Create `src/test/resources/pm-templates/v3/pm-test-phases/check.request.yaml`:

```yaml
$kind: http-request
name: check
url: "{{baseUrl}}/check"
method: GET
scripts:
  - type: beforeRequest
    code: |-
      pm.test('pre-req assertion runs', () => pm.expect(1).to.eql(1));
    language: text/javascript
  - type: afterResponse
    code: |-
      pm.test('post-res assertion runs', () => pm.expect(pm.response.code).to.eql(200));
    language: text/javascript
order: 1000
```

Create `src/test/kotlin/com/salesforce/revoman/PmTestPhaseTagE2ETest.kt`:

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
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Proves PmJsEval stamps each pm.test assertion with the phase that produced it. Network-free. */
class PmTestPhaseTagE2ETest {
  @Test
  fun `assertions are tagged with their script phase`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/pm-test-phases")
          .dynamicEnvironment("baseUrl", baseUrl)
          .insecureHttp(true)
          .off()
      )
    val report = rundown.stepReports.single()
    val byName = report.pmTestAssertions.associateBy { it.name }
    assertThat(byName["pre-req assertion runs"]!!.exeType).isEqualTo(PRE_REQ_JS)
    assertThat(byName["post-res assertion runs"]!!.exeType).isEqualTo(POST_RES_JS)
    // All passed -> step is successful.
    assertThat(report.isSuccessful).isTrue()
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll @JvmStatic fun stopServer() = server.stop(0)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.PmTestPhaseTagE2ETest"`
Expected: FAIL — pre-req assertion's `exeType` is the default `POST_RES_JS`, not `PRE_REQ_JS`.

- [ ] **Step 3: Stamp the phase in PmJsEval**

In `PmJsEval.kt`, add the imports (near lines 21-23):

```kotlin
import com.salesforce.revoman.output.ExeType
```

Then in `runSandboxScript` replace the `recordPmTestAssertions` call (lines 140-143) so each assertion carries the phase derived from `target`:

```kotlin
  // Surface pm.test results + setNextRequest onto the StepReport (read by the executor fold).
  val phaseExeType = if (target == ScriptTarget.PRE_REQUEST) ExeType.PRE_REQ_JS else ExeType.POST_RES_JS
  pm.recordPmTestAssertions(
    step,
    result.assertions.map { PmTestAssertion(it.name, it.passed, it.skipped, it.error, phaseExeType) },
  )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.PmTestPhaseTagE2ETest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt \
        src/test/resources/pm-templates/v3/pm-test-phases/ \
        src/test/kotlin/com/salesforce/revoman/PmTestPhaseTagE2ETest.kt
git commit -m "feat(pm): stamp pm.test assertions with their script phase at record time"
```

---

## Task 5: Serialize `pmTestAssertions` in `RundownJsonWriter`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/RundownJsonWriter.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/RundownJsonWriterTest.kt`

**Interfaces:**
- Consumes: `StepReport.pmTestAssertions`, `listW`/`objW`/`string`/`bool` helpers (`JsonWriterUtils.kt`), `Verbosity`
- Produces: each step report object in `stepReports` carries a `pmTestAssertions` array (only when non-empty) of `{name, passed, skipped, error, exeType}`. The existing `writeFailure` already renders a pm.test failure as the step's `failure` (it flows through `StepReport.failure`), so no change there.

- [ ] **Step 1: Write the failing test**

Append to `src/test/kotlin/com/salesforce/revoman/output/RundownJsonWriterTest.kt` (the helpers `newRequest`, `newRequestInfo`, `newResponseInfo`, `parseJson` already exist in the file):

```kotlin
  @Test
  fun `STANDARD serializes pmTestAssertions array with passed and exeType`() {
    val rawRequest = newRequest()
    val requestInfo = newRequestInfo(rawRequest)
    val responseInfo = newResponseInfo(OK, "{}")
    val report =
      StepReport(
          Step("1", Item(name = "Check", request = rawRequest)),
          Right(requestInfo),
          responseInfo = Right(responseInfo),
          pmEnvSnapshot = PostmanEnvironment(),
        )
        .copy(
          pmTestAssertions =
            listOf(
              com.salesforce.revoman.output.report.PmTestAssertion(
                "ok",
                passed = true,
                exeType = com.salesforce.revoman.output.ExeType.POST_RES_JS,
              ),
              com.salesforce.revoman.output.report.PmTestAssertion(
                "bad",
                passed = false,
                error = "boom",
                exeType = com.salesforce.revoman.output.ExeType.POST_RES_JS,
              ),
            )
        )
    val rundown =
      Rundown(
        stepReports = listOf(report),
        mutableEnv = PostmanEnvironment(),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 1,
      )
    val parsed = parseJson(rundown.toJson(Verbosity.STANDARD))
    val steps = parsed["stepReports"] as List<*>
    val assertions = (steps[0] as Map<*, *>)["pmTestAssertions"] as List<*>
    assertions shouldBe assertions // present
    (assertions[0] as Map<*, *>)["name"] shouldBe "ok"
    (assertions[0] as Map<*, *>)["passed"] shouldBe true
    (assertions[0] as Map<*, *>)["exeType"] shouldBe "post-res-js"
    (assertions[1] as Map<*, *>)["passed"] shouldBe false
    (assertions[1] as Map<*, *>)["error"] shouldBe "boom"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RundownJsonWriterTest"`
Expected: FAIL — `pmTestAssertions` key absent (`ClassCastException`/null on the cast).

- [ ] **Step 3: Add the writer**

In `RundownJsonWriter.kt`, add the `listW` import is already present (line 14). Add a writer function and call it from `writeStepReport`.

Add the call inside `writeStepReport` (after `writePollingFailure(writer, verbosity)`, line 80):

```kotlin
    writePmTestAssertions(writer)
```

Add the new function (place after `writePollingFailure`, before `writeFailure`):

```kotlin
private fun StepReport.writePmTestAssertions(writer: JsonWriter) {
  if (pmTestAssertions.isEmpty()) return
  listW("pmTestAssertions", pmTestAssertions, writer) { a ->
    objW(a, writer) { assertion ->
      string("name", assertion.name, writer)
      bool("passed", assertion.passed, writer)
      bool("skipped", assertion.skipped, writer)
      string("error", assertion.error, writer)
      string("exeType", assertion.exeType.toString(), writer)
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RundownJsonWriterTest"`
Expected: PASS (new + existing tests)

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/RundownJsonWriter.kt \
        src/test/kotlin/com/salesforce/revoman/output/RundownJsonWriterTest.kt
git commit -m "feat(pm): serialize pmTestAssertions into the JSON Rundown"
```

---

## Task 6: End-to-end — a failing `pm.test` fails the Rundown, run continues by default, JSON carries it

**Files:**
- Create: `src/test/resources/pm-templates/v3/pm-test-fail/a-fails.request.yaml`
- Create: `src/test/resources/pm-templates/v3/pm-test-fail/b-after.request.yaml`
- Create: `src/test/resources/pm-templates/v3/pm-test-fail/.resources/definition.yaml`
- Create: `src/test/kotlin/com/salesforce/revoman/PmTestFailureE2ETest.kt`

**Interfaces:**
- Consumes: `ReVoman.revUp(Kick)`, `Rundown.stepReports` / `reportForStepName` / `areAllStepsSuccessful` / `toJson`, `Kick.haltOnAnyFailure`, `StepReport.isSuccessful` / `exeTypeForFailure` / `pmTestFailure`
- Produces: nothing consumed downstream — terminal verification task.

- [ ] **Step 1: Write the failing E2E test + fixtures**

Create `src/test/resources/pm-templates/v3/pm-test-fail/.resources/definition.yaml`:

```yaml
$kind: collection
```

Create `src/test/resources/pm-templates/v3/pm-test-fail/a-fails.request.yaml` (a 200 response, but an assertion that deliberately fails):

```yaml
$kind: http-request
name: a-fails
url: "{{baseUrl}}/a"
method: GET
scripts:
  - type: afterResponse
    code: |-
      pm.test('intentionally fails', () => pm.expect(pm.response.code).to.eql(999));
    language: text/javascript
order: 1000
```

Create `src/test/resources/pm-templates/v3/pm-test-fail/b-after.request.yaml` (proves the run CONTINUED to the next step):

```yaml
$kind: http-request
name: b-after
url: "{{baseUrl}}/b"
method: GET
scripts:
  - type: afterResponse
    code: |-
      pm.test('b ran', () => pm.expect(pm.response.code).to.eql(200));
    language: text/javascript
order: 2000
```

Create `src/test/kotlin/com/salesforce/revoman/PmTestFailureE2ETest.kt`:

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
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Network-free E2E: a failing pm.test fails the step + Rundown, but the run continues by default. */
class PmTestFailureE2ETest {
  private val collection = "pm-templates/v3/pm-test-fail"

  @Test
  fun `failing pm test fails its step and Rundown, run continues to next step by default`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(collection)
          .dynamicEnvironment("baseUrl", baseUrl)
          .insecureHttp(true)
          .off()
      )
    // Both steps executed (run did NOT halt on the failing assertion).
    assertThat(rundown.stepReports).hasSize(2)
    val a = rundown.reportForStepName("a-fails")!!
    val b = rundown.reportForStepName("b-after")!!

    // The failing-assertion step is FAILED, tagged POST_RES_JS, with a pmTestFailure entry.
    assertThat(a.isSuccessful).isFalse()
    assertThat(a.exeTypeForFailure).isEqualTo(POST_RES_JS)
    assertThat(a.pmTestFailure).hasSize(1)

    // The following step ran and passed -> proves default continue.
    assertThat(b.isSuccessful).isTrue()

    // Rundown reflects the failure.
    assertThat(rundown.areAllStepsSuccessful).isFalse()

    // JSON output carries the failed assertion.
    val json = rundown.toJson()
    assertThat(json).contains("intentionally fails")
    assertThat(json).contains("post-res-js")
  }

  @Test
  fun `haltOnAnyFailure halts after the failing pm test step`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(collection)
          .dynamicEnvironment("baseUrl", baseUrl)
          .insecureHttp(true)
          .haltOnAnyFailure(true)
          .off()
      )
    // Halted after the first (failing) step -> the second step never executed.
    assertThat(rundown.stepReports).hasSize(1)
    assertThat(rundown.reportForStepName("a-fails")!!.isSuccessful).isFalse()
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll @JvmStatic fun stopServer() = server.stop(0)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.PmTestFailureE2ETest"`
Expected: FAIL on first run only if a prior task is incomplete; with Tasks 1–5 done it should PASS. If implementing strictly TDD, run BEFORE Task 3/4 wiring to observe the failure (`isSuccessful` true). Since prior tasks are committed, this task is the integration gate — expect PASS. If it FAILS, the failure message localizes the gap.

- [ ] **Step 3: No new production code** — this task is the end-to-end gate over Tasks 1–5. If it fails, fix the implicated task's code, re-run.

- [ ] **Step 4: Run the whole unit suite**

Run: `./gradlew test`
Expected: PASS (all green, including the new fixtures' tests)

- [ ] **Step 5: Run integration tests to confirm the behavior change is safe**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest"`
Expected: PASS — the pokemon collection's assertions all pass, so default-on does not flip it to FAILED. (If rate-limited by the live API per the known restful-api.dev / pokeapi limits, re-run later; that is environmental, not a regression.)

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/test/resources/pm-templates/v3/pm-test-fail/ \
        src/test/kotlin/com/salesforce/revoman/PmTestFailureE2ETest.kt
git commit -m "test(pm): E2E - failing pm.test fails the Rundown, run continues by default"
```

---

## Task 7: Documentation

**Files:**
- Modify: the Antora docs page covering pm.* / pm.test behavior (locate with the grep below).

- [ ] **Step 1: Locate the docs page**

Run: `grep -rln "pm.test\|pmTestAssertions\|pm\\.\\*" docs/ --include=*.adoc`
Expected: one or more `.adoc` pages under the Antora module (e.g. a "pm APIs" or "post-response scripts" page).

- [ ] **Step 2: Document the behavior**

Add a short subsection to the located page stating:
- A failing `pm.test(...)` now fails its step (and the Rundown) by default — Postman/newman parity.
- Execution continues to the next step by default; set `haltOnAnyFailure(true)` to stop, or exempt via `haltOnFailureOfTypeExcept(POST_RES_JS) { ... }` / `haltOnFailureOfTypeExcept(PRE_REQ_JS) { ... }`.
- Both an HTTP/transport failure and a pm.test failure are surfaced: `StepReport.failure` carries the higher-precedence (HTTP) cause; `StepReport.pmTestFailure` independently lists the assertion failures.
- Per-assertion results (incl. `exeType`) appear in the JSON Rundown under each step's `pmTestAssertions`.

Match the surrounding `.adoc` heading levels, admonition style, and code-block formatting of the page you are editing.

- [ ] **Step 3: Commit**

```bash
git add docs/
git commit -m "docs(pm): document failing pm.test fails the step + halt/ignore knobs"
```

---

## Self-Review

**Spec coverage:**
- Decision 1 (default-on, new `ExeFailure`) → Tasks 2, 3.
- Decision 2 (reuse `PRE_REQ_JS`/`POST_RES_JS`) → Tasks 1, 4; failure type's `exeType` (Task 2).
- Decision 3 (surface both) → Task 3 `pmTestFailure` field + the "HTTP non-2xx takes precedence but pm test failure is still surfaced" test.
- Decision 4 (`pmTestFailure` is a List, 0–2 entries) → Task 2 builder + Task 3 field/tests.
- Phase differentiation at record time → Task 4 + `PmTestPhaseTagE2ETest`.
- JSON output gap → Task 5.
- Halt parity (continue by default, `haltOnAnyFailure` halts, `haltOnFailureOfTypeExcept` exempts) → Task 6 E2E (continue + halt) and Task 7 docs (exempt knob); the exempt path is automatic because `exeTypeForFailure` is `PRE_REQ_JS`/`POST_RES_JS` and `isStepIgnoredForFailure` already keys off it (`Rundown.kt:89`).
- Migration safety (pokemon all-pass) → Task 6 Step 5.
- Tests (unit + integration) → Tasks 1–6.

**Placeholder scan:** none — every code/test step shows complete content.

**Type consistency:** `PmTestAssertion(name, passed, skipped, error, exeType)` used identically across Tasks 1, 4, 5. `buildPmTestFailures(List<PmTestAssertion>): List<PmTestFailure>` defined in Task 2, consumed in Task 3. `PmTestFailure.PreReqJsTestFailure` / `PostResJsTestFailure` names consistent across Tasks 2, 3. `failure(...)` gains exactly one `pmTestFailure: List<PmTestFailure>` parameter (Task 3). `writePmTestAssertions` name consistent in Task 5.

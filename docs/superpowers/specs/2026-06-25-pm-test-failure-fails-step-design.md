# Failing `pm.test` fails the step (and Rundown) — Design

**Date:** 2026-06-25
**Status:** Approved (pending spec review)

## Problem

Today a failing `pm.test(...)` assertion is captured as DATA and silently ignored:

- The chai `AssertionError` from a failed `pm.expect` is decoded into a `PmTestAssertion`
  with `passed=false` (`SandboxBridge.kt`), recorded per-step on
  `StepReport.pmTestAssertions` (`StepReport.kt:41`), and never thrown.
- `StepReport.failure` (`StepReport.kt:73`) is computed by `failure(...)` from
  request / pre-hook / response / HTTP-status / post-hook / polling **only** — it never
  reads `pmTestAssertions`. So `isSuccessful = failure == null` (`StepReport.kt:80`)
  stays `true` even when every assertion failed.
- `shouldHaltExecution` (`ExeUtils.kt:168`) keys off `isSuccessful`, so the run never
  halts and the step's `Outcome` is `SUCCESS` (`ReVoman.kt:381`).
- `RundownJsonWriter` has zero references to `pmTestAssertions` — failures never reach
  the JSON Rundown either.

Net: a Postman collection whose assertions fail still reports a fully green run. This
diverges from Postman/newman, where a failing `pm.test` makes the run report failure
(the run still continues to the next request unless configured otherwise).

## Goal

A failing `pm.test` makes its step report FAILURE by default, feeding the existing
failure / halt / ignore machinery, while preserving the full per-assertion record and
surfacing failures in the JSON output. Execution continues by default (Postman/newman
parity); `haltOnAnyFailure` still halts.

## Decisions (locked)

1. **Default-on, modeled as a new `ExeFailure`.** No opt-in flag. A failing `pm.test`
   participates in `failure()` / `isSuccessful` like any other failure.
2. **Reuse existing `ExeType`s** — `PRE_REQ_JS` for pre-request-script assertions,
   `POST_RES_JS` for test-script assertions. No new `ExeType`. This is the knob users
   already have for `haltOnFailureOfTypeExcept`.
3. **Surface BOTH** a co-occurring HTTP/transport failure and a pm.test failure.
   - Transport/HTTP/hook/polling keep precedence in the single `failure` Either (so a
     redundant `pm.test('status 200')` does not mask the real non-2xx cause).
   - pm.test failures are *also* surfaced independently via a dedicated field, regardless
     of precedence.
4. **`pmTestFailure` is a `List`** (0–2 entries: at most one per phase), since a single
   step runs both pre-request and test scripts and either/both can fail assertions.

## Phase differentiation — how it works (no inference needed)

Phase is known at record time. `PmJsEval.runSandboxScript` is called with a
`ScriptTarget` that already distinguishes the two phases:

- `executePreReqJS` → `ScriptTarget.PRE_REQUEST` (`PmJsEval.kt:30,44`)
- `executePostResJS` → `ScriptTarget.TEST` (`PmJsEval.kt:58,70`)

`recordPmTestAssertions` (`PmJsEval.kt:140`) is invoked inside `runSandboxScript`, where
`target` is in scope. We stamp each `PmTestAssertion` with its `ExeType` at that point:
`PRE_REQUEST → PRE_REQ_JS`, `TEST → POST_RES_JS`. The per-step accumulator
(`PostmanSDK.recordPmTestAssertions`, `PostmanSDK.kt:159`) concatenates both phases into
one tagged list. Grouping the `passed=false` entries by `exeType` yields the
`pmTestFailure` list — no guessing.

## Design

### 1. `PmTestAssertion` gains a phase tag

```kotlin
data class PmTestAssertion
@JvmOverloads
constructor(
  @JvmField val name: String,
  @JvmField val passed: Boolean,
  @JvmField val skipped: Boolean = false,
  @JvmField val error: String? = null,
  @JvmField val exeType: ExeType = ExeType.POST_RES_JS, // defaulted → source/binary compatible
)
```

`PmJsEval.runSandboxScript` maps `target` → `exeType` when constructing each assertion
(`PmJsEval.kt:142`).

### 2. New failure type: `PmTestFailure`

```kotlin
sealed class PmTestFailure : ExeFailure() {
  abstract val failedAssertions: List<PmTestAssertion>

  data class PreReqJsTestFailure(
    override val failure: AssertionError,
    override val failedAssertions: List<PmTestAssertion>,
  ) : PmTestFailure() { override val exeType = ExeType.PRE_REQ_JS }

  data class PostResJsTestFailure(
    override val failure: AssertionError,
    override val failedAssertions: List<PmTestAssertion>,
  ) : PmTestFailure() { override val exeType = ExeType.POST_RES_JS }
}
```

`failure` (the synthesized `Throwable`, required by the `ExeFailure` contract) is an
`AssertionError` whose message concatenates the failed assertions' names + chai `error`
strings — mirroring how `PollingFailure` synthesizes its `Throwable`.

A small builder maps the step's failed-assertion list (grouped by `exeType`) into 0–2
`PmTestFailure` entries.

### 3. `StepReport` — two parallel surfaces

```kotlin
// existing single halting cause — UNCHANGED type
@JvmField val failure: Either<ExeFailure, HttpStatusUnsuccessful>? = ...

// NEW: always-populated record of pm.test failures, independent of precedence
@JvmField val pmTestFailure: List<PmTestFailure> = emptyList()
```

`failure(...)` gains the failed assertions and slots `PmTestFailure` in at **lowest
precedence** (after polling), pre-req-first when both phases fail:

```
requestInfo Left
  → preStepHookFailure
    → responseInfo Left
      → non-2xx HTTP status
        → postStepHookFailure
          → pollingFailure
            → PmTestFailure (pre-req before post-res)   // NEW, lowest precedence
              → null (success)
```

So:
- A lone pm.test failure flips `failure` → non-null → `isSuccessful=false`.
- HTTP-fail + assertion-fail: `failure` carries the HTTP cause (precedence), and
  `pmTestFailure` still independently lists the assertion failure(s) → both visible.

`pmTestFailure` is built from `pmTestAssertions.filter { !it.passed && !it.skipped }`
grouped by `exeType`, populated in the same trailing `.copy(...)` that already sets
`pmTestAssertions` (`ReVoman.kt:357-368`).

### 4. Halt / ignore wiring — free

`isSuccessful = failure == null` unchanged → `shouldHaltExecution` (`ExeUtils.kt:168`)
and `haltOnFailureOfTypeExcept` work unmodified. Because pm.test failures carry
`PRE_REQ_JS` / `POST_RES_JS`, users exempt them from halting with
`haltOnFailureOfTypeExcept[POST_RES_JS] = <pick>` exactly as for script errors. Default
(`haltOnAnyFailure=false`, no except map) → run continues, step marked FAILED
(Postman/newman parity).

### 5. JSON output

`RundownJsonWriter.writeStepReport` (`RundownJsonWriter.kt:69`) gains:
- a `pmTestAssertions` array — `{name, passed, skipped, error, exeType}` per entry
- the `pmTestFailure` entries via the existing `writeFailure` path (lowest precedence is
  already reflected in `failure`, so the summary failure rendering picks it up; the
  detailed assertions array carries the full per-assertion breakdown).

Closes the "silently swallowed in JSON" gap. Gated by verbosity consistent with the
surrounding fields (assertions array in VERBOSE; failure summary always).

## Migration / blast radius

- Only one full-pipeline fixture uses `pm.test` (`pokemon-sandbox-api`), and all its
  assertions pass → default-on does not break it.
- `PokemonSandboxApiTest` already asserts `allMatch(a -> a.passed)` per step — still true.
- Risk: any consumer relying on a step staying `isSuccessful` despite a failing
  assertion. None found in-repo. (Documented as a behavior change in release notes.)
- `PmTestAssertion`'s new field is defaulted (`@JvmOverloads`) → source/binary compatible
  for external callers constructing it.

## Testing (TDD — tests first)

**Unit (`src/test`):**
- Lone failing post-res assertion → `isSuccessful=false`, `failure` is
  `PostResJsTestFailure`, `pmTestFailure` has 1 entry tagged `POST_RES_JS`.
- All assertions pass → `isSuccessful=true`, `pmTestFailure` empty.
- Failing pre-req assertion → `failure` is `PreReqJsTestFailure`, tagged `PRE_REQ_JS`.
- Both phases fail → `failure` is pre-req (precedence), `pmTestFailure` has 2 entries.
- HTTP non-2xx + failing assertion → `failure` is `HttpStatusUnsuccessful`,
  `pmTestFailure` still lists the assertion failure (surface-both).
- `haltOnAnyFailure=true` → `shouldHaltExecution` true on a pm.test failure.
- Default → `shouldHaltExecution` false (run continues), step FAILED.
- `haltOnFailureOfTypeExcept[POST_RES_JS]` exempts a post-res pm.test failure from halting.
- `skipped` assertions (`pm.test.skip`) never contribute to failure.

**Integration (`src/integrationTest`):**
- Extend the pokemon collection with a dedicated step carrying a deliberately-failing
  assertion; assert the Rundown marks that step FAILED, `areAllStepsSuccessful=false`,
  and the JSON output carries the failed assertion + `pmTestFailure`.

**JSON writer test:** a step with mixed passed/failed assertions serializes the
`pmTestAssertions` array with correct `passed`/`exeType` per entry.

## Out of scope

- Honoring `pm.execution.setNextRequest` (already a separate captured-only Phase 2 item).
- Per-assertion timing.
- Changing `haltOnAnyFailure` default.

## Files touched (anticipated)

- `output/report/PmTestAssertion.kt` — add `exeType`
- `output/report/failure/PmTestFailure.kt` — NEW sealed type
- `output/report/StepReport.kt` — `pmTestFailure` field, `failure()` precedence
- `internal/exe/PmJsEval.kt` — stamp `exeType` at record time
- `output/RundownJsonWriter.kt` — serialize assertions + failure
- Tests as above

# Unit Test Coverage Audit & Remediation — Design

Date: 2026-08-01
Status: Approved (brainstorming)

## Problem

ReVoman's Kover line-coverage total reads **69.8%** (4001/5731 lines), which looks low for a
library this heavily tested (~100 test files against 94 production files). The audit below shows
the number is mostly a **measurement artifact**, not a genuine coverage shortfall — but there are a
handful of real hand-written gaps worth closing.

Goal (user-selected): **fix the measurement AND write tests** for the real gaps, targeting **~85%**
honest production line coverage, then raise the regression ratchet.

## Audit findings

Source: existing Kover HTML report (`build/reports/kover/html`), generated 2026-08-01 17:21 from a
combined `test` + `integrationTest` run. Kover has **no `filters`/`excludes`** configured, so the
denominator counts code that should not count against production quality.

### Distortion 1 — test source sets measured as production code (biggest)

Kover measures the `integrationTest` (and any other test) source set as if it were production code:

- `com.salesforce.revoman.integration.pokemon` — 100% (inflates the total)
- `com.salesforce.revoman.integration.core.wfs` — 0% (0/214 lines), `…core.pq` 0%, `…core.bt2bs` 0%
  — these are **org-gated** (`-PincludeCoreIT`), never run in a normal `build`, so they score 0 and
  **deflate** the total.
- `JsonPojoUtils2Test` (an `integrationTest` Java class) shows as measured code at 100%.

### Distortion 2 — generated Immutables

`@Value.Immutable` interfaces generate Immutables classes (via kapt) that are not hand-written:

- `Kick` — 20.7% line (68/328). Confirmed `@org.immutables.value.Generated` in
  `build/generated/source/kapt/main/.../Kick.java`.
- `JsonFile`, `JsonString`, `Pojo`, `PojoDef` (in `input.json`) — 3–5% line, ~656 lines combined.
- Builders (`PollingConfigBuilder`, `RunbookBuilder`, `KickDef`) — partial.

### Distortion 3 — JMH benchmarks

`com.salesforce.revoman.benchmark` (`src/jmh`) — 0% (0/50 lines, 0/409 instr). A perf harness, never
unit-tested by design (analogous to the opt-in core-IT tests).

### Net

Stripping the three distortions, real hand-written production line coverage is **~85%**, not 69.8%.
The library is well-tested; the gauge is miscalibrated.

### Genuine hand-written gaps (ranked)

All confirmed as small, pure, trivially testable (no external calls, no mocking beyond fixtures):

1. `input.config.RequestConfig` — **0%** (3 `@JvmStatic unmarshallRequest` factory overloads unused
   in tests).
2. `internal.json.adapters.TypeAdapter` — **0%**, `UUIDAdapter` — **0%** (tiny Moshi adapters).
3. `input.config.ResponseConfig` — **30%** (9 factory overloads; only the plain ones are hit).
4. `input.config.StepPick` — **45%** (OOTB `@JvmStatic` picks `withName`/`inFolder`/`before*`/
   `after*` never exercised).
5. `input.json.JsonWriterUtils` — **45%** (`string`/`bool`/`integer`/`doubl`/`lng`/`listW`/`mapW`/
   `writeProps` helpers + null branches).
6. `output.report.failure.RequestFailure` — **38%**, `ResponseFailure` — **54%** (data-class
   `equals`/`copy`/`exeType` on subtypes not constructed in tests).

Left alone (majority-covered, diminishing returns): `JsonPojoUtils` (70%), `output.Rundown` (57%),
`output.RundownJsonWriter` (68%). Revisit only if the ~85% floor isn't met after the above.

## Approach

**A — hygiene first, then tests, then ratchet** (chosen over "tests first" and "hygiene only").
Rationale: a ratchet on a polluted number is meaningless, and tests written against the raw
denominator waste effort chasing generated code. Fix the gauge first so every later % is trustworthy.

## Section 1 — Measurement hygiene (Kover excludes)

Add to the `kover {}` block in `build.gradle.kts`:

```kotlin
kover {
  currentProject {
    sources {
      // JMH benchmark source set is a perf harness, never unit-tested by design.
      excludedSourceSets.addAll("jmh")
    }
  }
  reports {
    filters {
      excludes {
        // 1. Generated Immutables (Kick, Pojo, JsonFile, JsonString + builders) — not hand-written.
        annotatedBy("org.immutables.value.Generated")
        // 2. Test source sets leaking into the denominator (integration.* pokemon inflates 100%,
        //    core.wfs/pq/bt2bs deflate 0% since they're org-gated).
        classes("com.salesforce.revoman.integration.**")
        // 3. Moshi-generated adapters (…JsonAdapter) — codegen, not hand-written.
        classes("*JsonAdapter")
      }
    }
    total {
      html { onCheck = true }
      verify { rule { minBound(69) } } // recalibrated in Section 3
    }
  }
}
```

**Verification checkpoints (test, don't assume):**

- Confirm `Kick`, `Pojo`, `JsonFile`, `JsonString`, `integration.**`, and `benchmark.**` classes are
  absent from the HTML report's class list after the change.
- Confirm `excludedSourceSets.addAll("jmh")` is the correct Kover 0.9.9 knob for the JMH source set;
  if JMH classes still appear, fall back to `classes("com.salesforce.revoman.benchmark.**")`.
- If `annotatedBy` or the `*JsonAdapter` glob doesn't fire under Kover 0.9.9, fall back to explicit
  `classes("...")` patterns and re-check.

Expected effect: honest production line total surfaces at ~83–85%.

## Section 2 — Test plan for real gaps

New unit tests under `src/test/kotlin`, Kotest-style, backtick natural-language names (house
convention). One file per target:

| # | Test file | Target | Covers |
|---|---|---|---|
| 1 | `input/config/RequestConfigTest.kt` | `RequestConfig` | 3 `unmarshallRequest` overloads → `Either` left/right + fields |
| 2 | `input/config/ResponseConfigTest.kt` | `ResponseConfig` | 9 factory overloads → `ifSuccess` null/true/false + adapter side |
| 3 | `internal/json/adapters/TypeAdapterTest.kt` | `TypeAdapter` | `toJson`=type string, `fromJson`=null |
| 4 | `internal/json/adapters/UUIDAdapterTest.kt` | `UUIDAdapter` | round-trip; bad string throws |
| 5 | `input/config/StepPickPickUtilsTest.kt` | `StepPick` | OOTB picks (`withName`/`inFolder`/`before*`/`after*`) true/false via `Step`/`StepReport` fixtures |
| 6 | `input/json/JsonWriterUtilsTest.kt` | `JsonWriterUtils` | `string`/`bool`/`integer`/`doubl`/`lng`/`listW`/`mapW`/`writeProps` value + null branch, over a real `JsonWriter` buffer |
| 7 | `output/report/failure/RequestFailureTest.kt` | `RequestFailure` | 3 subtypes: `exeType` + equals/copy |
| 8 | `output/report/failure/ResponseFailureTest.kt` | `ResponseFailure` | 2 subtypes: `exeType` + equals/copy |

Write order: 3, 4, 1, 2, 7, 8 (trivial) → then 5, 6 (fixture-heavier).

**Scope guards (YAGNI):** no tests for generated `Kick`/`Pojo` (excluded in §1); no new integration
tests (all pure-function unit tests); leave majority-covered classes unless the floor isn't met.

## Section 3 — Ratchet + verification

1. Land §1 excludes → `./gradlew koverHtmlReport` → record honest baseline.
2. Write tests §2 → re-run → record new total.
3. Raise `minBound(69)` → `minBound(N)`, `N` = measured unit-only total − 1 (one-point margin,
   matching the existing "loose regression floor" intent). The `build` gate runs on the *combined*
   total (≥ unit-only), so `N` off the unit-only number stays a safe, non-false-failing floor.

Update the Kover comment block to document the excludes and the recalibrated floor.

**Verification:**

- After excludes: distortion classes gone from report (see §1 checkpoints).
- After tests: `./gradlew test` green; each target class's line% risen.
- Final: `./gradlew build` passes with raised `minBound`.

## Logging

Test/build infra only — no runtime feature — so no new production logging. AGENTS.md logging
requirement is satisfied by updating the Kover config comment to explain the excludes + floor.

## Non-goals

- Covering generated code, JMH, or org-gated core-IT (excluded, not tested).
- Refactoring production code.
- Chasing 90%+ (user chose the pragmatic ~85% "close obvious gaps" target).

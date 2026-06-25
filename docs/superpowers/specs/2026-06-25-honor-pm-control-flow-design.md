# Honor Postman control-flow directives — Design

**Date:** 2026-06-25
**Branch:** `feat/honor-pm-control-flow` (off `master`)
**Status:** Approved design — ready for implementation plan

## Problem

ReVoman captures three Postman control-flow directives but does NOT act on them:

- `pm.execution.setNextRequest(name)` — jump to a named request after this step.
- `pm.execution.setNextRequest(null)` — stop the run.
- `pm.execution.skipRequest()` — skip the current request's HTTP dispatch (pre-request only).

Today the sequencer `executeStepsSerially` (`ReVoman.kt:210`) is a strictly linear
fold:

```kotlin
pickedSteps.asSequence().takeWhile { !haltExecution }.fold(emptyList()) { reports, step -> ... }
```

`nextRequest` is captured onto `StepReport.nextRequest` (`ReVoman.kt:368`,
`PostmanSDK.nextRequestFor`), but the sequencer ignores it. `PmJsEval.kt:150-153`
logs a warning that "ReVoman does not yet reorder steps … Phase 2 will honor it."
`skipRequest` lives in `PmExecutionResult.skipRequest` but is never captured from
the sandbox.

This is Phase 2: honor the directives.

## Guiding invariant — NO compromise to the common path

Directives are rare. A collection that never calls `setNextRequest`/`skipRequest`
MUST execute **identically** to today:

- Same `StepReport`s, same order, same halt semantics.
- Same ledger warm-path behavior (skip+inject, collision guard).
- `stopReason == COMPLETED`.

The full existing suite (340 tests + `LedgerSkipE2ETest`) is the regression guard
and must pass unchanged. **Every new branch is lazily activated** — gated behind
"a directive actually fired this run." A run with no directives never touches a
new code path.

## Decisions (settled in brainstorming)

| Topic | Decision |
|---|---|
| Scope | Honor all three: `setNextRequest(name)` incl. backward jumps (loops), `setNextRequest(null)` = stop, `skipRequest`. Full Postman parity. |
| Loop guard | Global execution budget = `pickedSteps.size × factor`, default `factor=10`, Kick-configurable. Halt + fail when exceeded. |
| Ledger reconciliation | One-way latch: once control flow diverges (real jump/skip), bypass the ledger warm-path **from the divergence point onward**. Linear prefix still ledger-skips. |
| Pick interaction | Jump targets resolve **within the picked-steps universe** via `Step.stepNameMatches`. Unresolved (filtered out OR no match) → warn + continue linearly (no halt). |
| Report shape | One `StepReport` per execution. A looped step yields N reports. `reportForStepName` returns the LAST (most-recent-wins). |
| Terminal reason | `Rundown.stopReason: StopReason` enum: `COMPLETED` (default), `STOPPED_BY_DIRECTIVE`, `HALTED_ON_FAILURE`, `LOOP_BUDGET_EXCEEDED`. |
| Budget default | `factor=10`, Kick knob `maxStepExecutionFactor()`. |

## Architecture

### Component 1 — Close the `skipRequest` capture gap

`skipRequest` is a **separate guest event** `execution.skipRequest.<id>` (NOT carried
on `execution.return`), gated by the sandbox `allowSkipRequest` option — already wired
`true` for `PRE_REQUEST` only (`SandboxBridge.kt:170`), matching Postman (skip is
pre-request-only).

- `SandboxBridge.decodeResult`: detect the `execution.skipRequest.$id` emit → set
  `PmExecutionResult.skipRequest = true`.
- **Risk to verify (first RED test):** confirm a skip still yields a terminal
  `execution.result` emit so `dispatchExecute`'s loop drain does not hang. The
  bootcode calls `U.terminate` after dispatching the skip event; the test asserts
  the bridge returns rather than blocking.

### Component 2 — Distinguish `setNextRequest(null)` = STOP from "never called"

Today both decode to `nextRequest == null` (see `PostmanSDK.recordNextRequest`
KDoc: "intentionally indistinguishable"). To honor STOP we must distinguish them.
Fix at the decode source by reading **key presence** in `execution.return`:

- `PmExecutionResult` gains `nextRequestSet: Boolean` alongside `nextRequest: String?`.
- `StepReport` gains `nextRequestSet: Boolean = false` (default = back-compat).
- Interpretation (the directive derivation):
  - `!set` → `None` (no directive).
  - `set && (null or blank)` → `Stop`.
  - `set && name` → `Jump(name)`.
- The public `nextRequest: String?` field stays intact.

### Component 3 — Sequencer: linear-by-default, jump-driven only when needed

The step lifecycle body of today's fold (pre-req → unmarshall → pre-hooks → HTTP →
post-res → unmarshall-response → post-hooks → polling → capture) is extracted
**byte-for-byte** into a `runStep(step, ...) : StepReport`. Only the driver around
it changes from a `fold` to an index-driven `while`:

```kotlin
var cursor = 0
var executions = 0
val budget = pickedSteps.size * kick.maxStepExecutionFactor()   // default 10×
val reports = mutableListOf<StepReport>()
var stopReason = StopReason.COMPLETED
var controlFlowDiverged = false                 // one-way ledger latch (Component 5)
val iterationByPath = mutableMapOf<String, Int>()

while (cursor in pickedSteps.indices) {
    val step = pickedSteps[cursor]
    val iteration = iterationByPath.getOrDefault(step.path, 0)
    val report = runStep(step, iteration, controlFlowDiverged, ...)
    iterationByPath[step.path] = iteration + 1
    reports += report

    if (++executions > budget) { stopReason = LOOP_BUDGET_EXCEEDED; break }
    if (shouldHaltExecution(report, kick, rundown)) { stopReason = HALTED_ON_FAILURE; break }

    cursor = when (val d = report.directive) {                  // derived in Component 2
        Stop      -> { stopReason = STOPPED_BY_DIRECTIVE; -1 }  // out of indices -> loop exits
        is Jump   -> {
            controlFlowDiverged = true
            resolveTarget(d.name, pickedSteps, cursor)          // index, or cursor+1 + warn
        }
        None      -> cursor + 1                                 // IDENTICAL to today's linear order
    }
}
```

No-directive guarantees:
- Every iteration takes the `None -> cursor + 1` branch → exact linear traversal,
  identical reports, identical halt behavior (`while`-over-`cursor+1` ≡ the old
  `fold` + `takeWhile { !haltExecution }`).
- `executions`/`budget` only ever cause an **early exit**; a normal N-step run does
  N executions, far under `N×10`.
- `controlFlowDiverged` stays `false` → ledger path untouched (Component 5).

`resolveTarget(name, pickedSteps, cursor)`: first index whose
`Step.stepNameMatches(name)` is true. None matches (incl. a step filtered out by
picks) → log a warning and return `cursor + 1` (continue linearly; no halt).

### Component 4 — `skipRequest` semantics

Pre-request-only "don't send this step's HTTP request; move on." Inside `runStep`:

- Pre-req JS runs normally. If it returned `skipRequest = true`, short-circuit the
  rest of the lifecycle: NO unmarshall-request / pre-hooks / HTTP / post-res /
  unmarshall-response / post-hooks / polling.
- Emit a dedicated report via new factory `StepReport.requestSkipped(step, env, iteration)`:
  `isSuccessful == true` (a skip is not a failure), no `requestInfo`/`responseInfo`.
- New `isRequestSkipped: Boolean` flag distinguishes it from `isLedgerSkipped`
  (both lack req/resp). A skipped step produces no env vars → no ledger entry → no
  warm-path contamination.
- A skip sets `controlFlowDiverged = true` (Component 5).
- The pre-req may ALSO call `setNextRequest` in the same script (Postman allows
  both); the cursor honors that directive after the skip, else `cursor + 1`.

### Component 5 — Ledger reconciliation (one-way latch)

`shadowedProducerPaths` (`ExeUtils.kt:122`) and `ledgerSkipDecision` assume linear
execution order (the collision guard is computed once, in order). Jumps/loops break
that assumption.

- The moment the run takes a real **Jump** or a **skip**, set
  `controlFlowDiverged = true` (one-way; once set, never cleared — already-skipped
  steps cannot be retroactively un-skipped, so this is the only sound model).
- While `controlFlowDiverged`, `runStep` **bypasses** the ledger warm-path:
  no skip+inject, no `shadowedPaths` consultation. Every subsequent step dispatches
  fresh. Correctness over the warm-run optimization.
- The **linear prefix before the first divergence still ledger-skips normally** —
  its ordering held, so its provenance is sound.
- No-directive runs never latch → ledger behaves exactly as today. This is the
  crux of the no-compromise invariant.
- Documented in code with a comment block mirroring the existing ledger-contract
  comments.

### Component 6 — Report shape & Rundown terminal reason

- One `StepReport` per execution (the `while` appends each iteration). A looped step
  yields N reports in execution order.
- `StepReport` gains `iteration: Int = 0` (0 for the common single-run case) so the
  N reports for one step are distinguishable. Default 0 → no behavioral change.
- `Rundown.reportForStepName`: change `firstOrNull` → `lastOrNull` (most-recent-wins).
  For no-loop runs `first == last == only`, so zero behavior change. Add a plural
  `reportsForStepName(name): List<StepReport>` returning all N for callers wanting
  loop history.
- `Rundown` gains `stopReason: StopReason = StopReason.COMPLETED`. The enum:
  `COMPLETED`, `STOPPED_BY_DIRECTIVE`, `HALTED_ON_FAILURE`, `LOOP_BUDGET_EXCEEDED`.
  Defaulted so every existing `Rundown(...)` construction compiles untouched.

### Component 7 — Kick knob

- `KickDef`: `@Value.Default fun maxStepExecutionFactor(): Int = 10`. Budget =
  `pickedSteps.size × factor`. Immutables generates the builder setter; the default
  means no existing `Kick.builder()` call changes.
- `@Value.Check`: `require(maxStepExecutionFactor() >= 1)`.

### Component 8 — Logging & cleanup

- Delete the stale "ReVoman does not yet reorder steps … Phase 2 will honor it"
  warning (`PmJsEval.kt:150-153`).
- New `StepEvent`s: `Jumped(fromPath, toPath)`, `RunStopped(reason)`,
  `RequestSkipped(path)`, `LoopBudgetExceeded(budget)`. Info-level per directive
  honored; warn on unresolved jump target.
- Update `StepReport.nextRequest` KDoc (currently "CAPTURED ONLY") to state it is
  now honored.

## Testing strategy

TDD, RED first for each. Network-free loopback E2E fixtures under
`src/test/resources/pm-templates/v3/`, modeled on the `pm-test-fail` fixtures.

E2E:
1. **Forward jump** — A `setNextRequest("C")` skips B; assert B has no report, order A→C.
2. **Backward jump / loop** — env counter, loop until N; assert N reports for the
   looped step with `iteration` 0..N-1.
3. **Loop budget exceeded** — unbounded backward jump → `stopReason == LOOP_BUDGET_EXCEEDED`,
   run terminates.
4. **`setNextRequest(null)`** — `stopReason == STOPPED_BY_DIRECTIVE`, later steps unrun.
5. **`skipRequest`** — HTTP not sent, `isRequestSkipped`, run continues.
6. **Unresolved target** — `setNextRequest("nope")` → warn, linear continue.
7. **Jump + ledger** — warm run with a jump; assert post-divergence steps dispatch
   fresh, linear prefix still ledger-skips.
8. **Regression** — existing suite + `LedgerSkipE2ETest` green unchanged (the
   no-compromise proof).

Unit:
- `resolveTarget` (match, no-match, filtered-out).
- Budget arithmetic and early-exit.
- Directive derivation: `nextRequestSet × value → None / Jump / Stop`.
- `decodeResult` skip-event parsing (no hang).

## Build / verify

```bash
./gradlew test integrationTest
./gradlew spotlessApply   # before commit
```

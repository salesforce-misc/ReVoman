# PM Sandbox — Full Wiring of Script-Only `pm` APIs + Coverage Tests

**Date:** 2026-06-11
**Status:** Design — approved, pending spec review
**Builds on:** `docs/superpowers/specs/2026-06-04-pm-api-sandbox-integration-design.md` (Phase 1 sandbox boot)
**Related plan:** `docs/superpowers/plans/2026-06-04-pm-sandbox-phase1.md`

## Goal

Make every **script-only** `pm` API the user listed observably work **end-to-end** through a real
`ReVoman.revUp(...)` run, and lock that behavior with unit + integration tests:

- **Variables:** `pm.collectionVariables` / `.get` / `.set` / `.toObject`
- **Environment:** `pm.environment`, `pm.environment.name`
- **Request/Response:** `pm.request.body`, `pm.response.code` / `.json` / `.text` / `.to.have.status`
- **Testing:** `pm.test`, `pm.expect`
- **Execution control:** `pm.execution.setNextRequest` (**captured + surfaced this cycle; executing
  sequencer deferred to its own cycle**)

## Problem / Current State

The real postman-sandbox bootcode already supports all these APIs (proven by
`PmSandboxApiCoverageTest`, 11 green). The gap is **ReVoman's exe-layer plumbing**: in a real run,
`PmJsEval.runSandboxScript` only threads `environment` (+ request/response) and reads back only
`environment`. So four capabilities run inside the sandbox but are **dropped** before reaching the
caller / `Rundown`:

| Capability | In a real `revUp()` run today |
| --- | --- |
| `pm.environment.get/set/unset` | ✅ flows (diffed back into `mutableEnv`) |
| `pm.request.body`, `pm.response.*` | ✅ fed into context |
| `pm.collectionVariables.*` | ❌ not threaded in, not read back |
| `pm.environment.name` | ❌ name discarded by `mergeEnvs` |
| `pm.test` / `pm.expect` pass/fail | ❌ assertions decoded by the bridge, then dropped |
| `pm.execution.setNextRequest` | ❌ captured by the bridge, then dropped |

An integration test that "uses" the bottom four today would assert nothing — a failing `pm.test` is
invisible. Full wiring makes them observable on `StepReport` / `Rundown` so the test is honest.

## Non-Goals (explicit scope guards)

1. **`setNextRequest` execution (the jump-capable sequencer).** Deferred to its own cycle. Reason:
   honoring the directive rewrites `executeStepsSerially`'s linear `fold` into an index-driven
   driver that must reconcile with ledger skip/inject (`shadowedPaths`, `ledgerSkipDecision`),
   `haltOnFailure`, name→index resolution, and an infinite-loop guard. That is a correctness-risky
   redesign of the hottest loop and deserves a dedicated brainstorm→plan→TDD. **This cycle captures
   and surfaces the directive (the exact data that future sequencer will consume) and logs a
   `RevomanLog.warn` when a script sets it**, so a captured-but-unexecuted directive is never a
   silent failure.
2. **`pm.sendRequest`.** Unchanged — still the explicit `UnsupportedOperationException` from the
   bridge. (Separate subsystem; needs an http4k responder on `execution.request`.)
3. **Collection-root `variable[]` parsing.** `Template` parses only `item` + `auth`; root
   `variable` is not modeled. `pm.collectionVariables` is therefore **script-seeded only** this
   cycle — a producer step's script `.set`s a collection variable, a downstream step's script
   `.get`s it (the common real-world pattern). Seeding from a collection file is out of scope.

## Architecture / Data Flow

```
Collection scripts ─▶ PmJsEval.runSandboxScript ─▶ PmSandbox ─▶ SandboxBridge ─▶ real bootcode
                          │  builds PmExecutionContext IN:                │
                          │   environment(+name), collectionVariables,    │ PmExecutionResult OUT:
                          │   request, response                           │  environment,
                          │                                               │  collectionVariables,
                          ▼                                               │  assertions, nextRequest
   diff env  ──▶ PostmanSDK.environment.set/unset (ledger path, unchanged)│
   diff cVars ─▶ PostmanSDK.collectionVariables.set/unset                 ▼
   stash assertions + nextRequest per step on PostmanSDK ───────────────▶ read at report build
                          │
                          ▼
   StepReport.{pmTestAssertions, nextRequest}  ─▶  Rundown (integration test asserts here)
```

## Components (each independently testable)

### 1. `PostmanSDK` — collection-variable store, env name, per-step script capture
- `@JvmField val collectionVariables: PostmanEnvironment<Any?>` — reuse the existing env wrapper as a
  plain key→value store (gives set/unset/`toMap` for free; ledger capture is env-specific and not
  triggered here). Seeded empty.
- `var environmentName: String?` — the environment's display name, exposed via `pm.environment.name`.
- Per-step capture (keyed by `Step`, mirroring `PostmanEnvironment.producedKeysByStep`):
  - `pmTestAssertionsByStep: MutableMap<Step, List<PmTestAssertion>>`
  - `nextRequestByStep: MutableMap<Step, String?>`
  - accessor `pmTestAssertionsFor(step)` / `nextRequestFor(step)` read at report-build time.
  - A pre-req and a post-res script are two sandbox executions; assertions **accumulate** across
    both, `nextRequest` is **last-write-wins** (post-res overrides pre-req — matches Postman).

### 2. Public assertion type — `output.report.PmTestAssertion`
- The bridge's `PmAssertion` is `internal` in the sandbox package; do not leak it onto the public
  `StepReport` API. Add a public `data class PmTestAssertion(name, passed, skipped, error)` in
  `output.report` and map sandbox→public in `PmJsEval`.

### 3. `Environment.mergeEnvs` + `ReVoman.kt` — capture & thread env name
- `mergeEnvs` currently returns only the merged value-map (name discarded). Change it to also return
  the chosen environment name (the V3 yaml `name:` / V2 json `"name"`). Precedence when multiple env
  sources: last non-null wins, consistent with the existing value-merge order
  (yaml → json → streams → dynamic). Thread the name into `PostmanSDK.environmentName`.

### 4. `PmJsEval.runSandboxScript` — thread the new scopes in, diff the new scope out, stash capture
- Build `PmExecutionContext` with:
  - `environment = PmScope("environment", before, name = pm.environmentName)`
  - `collectionVariables = PmScope("collectionVariables", pm.collectionVariables.toMap())`
- After `execute`:
  - env diff back — unchanged.
  - `diffScopes(beforeCVars, result.collectionVariables)` → apply produced/unset to
    `pm.collectionVariables`.
  - stash `result.assertions` (mapped to `PmTestAssertion`) and `result.nextRequest` on `PostmanSDK`
    for the current step.
  - if `result.nextRequest != null`: `RevomanLog.warn { "pm.execution.setNextRequest('$next') was
    captured but ReVoman does not yet reorder steps (linear execution); directive recorded on
    StepReport.nextRequest only." }`

### 5. `SandboxBridge` — DONE
- `scopeToProxy` forwards `name`; `decodeResult` captures `execution.return.nextRequest`. ✅ (already
  TDD'd and green; no change.)

### 6. `StepReport` — two new read-only fields
- `@JvmField val pmTestAssertions: List<PmTestAssertion> = emptyList()`
- `@JvmField val nextRequest: String? = null`
- Populated in the `executeStepsSerially` fold's terminal `.copy(...)` (same site that reads
  `producedKeysFor(step)`), from the PostmanSDK per-step capture.

### 7. `Rundown` — no change needed
- `Rundown.reportForStepName(stepName): StepReport?` already exists; the integration test uses it to
  fetch a step's report by name and read `.nextRequest` / `.pmTestAssertions`.

### 8. Creative integration test — `PokemonSandboxApiTest.java` (live, free APIs)
- A **new V2 collection** `pm-templates/v2/pokemon-sandbox-api/` whose scripts exercise every listed
  API end-to-end. Steps (all GET unless noted; pokeapi.co is read-only):
  1. **all-pokemon** `GET {{baseUrl}}/pokemon?limit={{limit}}` — post-res script:
     `pm.response.to.have.status(200)`, `pm.test` with `pm.expect(pm.response.json().results)`,
     `pm.environment.set('pokemonName', json.results[0].name)`,
     `pm.collectionVariables.set('firstPokemon', name)`,
     `pm.test('env name', () => pm.expect(pm.environment.name).to.be.a('string'))`.
  2. **pokemon-by-name** `GET {{baseUrl}}/pokemon/{{pokemonName}}` — post-res:
     `pm.expect(pm.response.code).to.eql(200)`, `pm.response.text()`,
     `pm.collectionVariables.get('firstPokemon')` + `toObject()` assertions,
     `pm.execution.setNextRequest('pokemon-species')`.
  3. **pokemon-species** `GET {{baseUrl}}/pokemon-species/{{pokemonName}}` — assertions on chained
     collection variables.
  4. **PUT step** against `restful-api.dev` (`PUT /objects/{id}`) demonstrating `pm.request.body`:
     pre-req sets a body var, post-res asserts `pm.response.json().data` echoes it. (pokeapi has no
     write endpoint; restful-api.dev is the project's existing free write API.)
- **Assertions on `Rundown`:**
  - `firstUnIgnoredUnsuccessfulStepReport()` is null (all HTTP + scripts succeeded).
  - `rundown.mutableEnv` contains the produced env keys (`pokemonName`, …).
  - collection variables produced by step 1 are visible to steps 2–3 (cross-step `get`).
  - every `stepReport.pmTestAssertions` entry has `passed == true`.
  - `rundown.reportForStepName("pokemon-by-name").nextRequest == "pokemon-species"` **with an inline
    comment that ReVoman does not yet reorder — Phase 2** (the linear order is unchanged; we assert
    the directive was *captured*, not that it changed execution).

## Testing Strategy (TDD throughout)

- **Unit (extend existing):**
  - `PmSandboxApiCoverageTest` — already covers all 12 APIs at the sandbox layer (green).
  - New: `PostmanSDK` collection-variable round-trip; `mergeEnvs` returns env name; `PmJsEval`
    maps + stashes assertions/nextRequest; `StepReport` carries the new fields.
- **Integration (live):** `PokemonSandboxApiTest` above. Live HTTP, consistent with `PokemonTest` /
  `RestfulAPIDevTest`.
- Every change RED → GREEN → REFACTOR; watch each test fail first.

## Risks / Mitigations

- **Live-API flakiness.** Mitigation: keep assertions structural (status, shape, types) not
  brittle exact-value (pokeapi data is stable, but assert on `results.length`/`code`/type, not on a
  specific pokemon attribute that could change).
- **Ledger interaction with collectionVariables.** Collection vars do NOT feed the ledger (ledger is
  env-keyed). Confirm `diffScopes` on collection vars never calls the env producer-capture path —
  it writes a separate `PostmanEnvironment` instance whose `currentStep` is never set, so producer
  capture is a no-op. Add a unit assertion for this.
- **Public API surface growth (`StepReport`).** Two additive nullable/empty-default fields; no
  breaking change to existing constructors used by tests.

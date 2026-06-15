# PM Variable Scopes — Faithful `pm.globals` + `pm.collectionVariables` + Aggregate `pm.variables`

**Date:** 2026-06-15
**Status:** Design — approved, pending spec review
**Builds on:** `docs/superpowers/specs/2026-06-11-pm-sandbox-api-coverage-design.md` (collectionVariables wiring + assertion surfacing)
**Approach:** A — three sibling `PostmanEnvironment` peers on `PostmanSDK` + a thin precedence resolver

## Goal

Support all three persistent Postman variable scopes faithfully, end-to-end through a real
`ReVoman.revUp(...)` run, and lock the behavior with **exhaustive** unit + integration tests:

- **`pm.environment`** — already works; unchanged. Backs `Rundown.mutableEnv`.
- **`pm.collectionVariables`** — already round-trips cross-step; gains `{{}}` resolution + `Rundown`
  exposure.
- **`pm.globals`** — currently half-wired (sandbox scaffolding exists, ReVoman drops it between
  steps). Becomes a first-class peer: persists cross-step, resolves in `{{}}`, exposed on `Rundown`.
- **`pm.variables`** — the aggregate **read** accessor: `get`/`has` resolve across the three scopes
  by Postman precedence (environment → collectionVariables → globals).

## Postman semantics this honors

Postman variable scopes, broadest → narrowest: **Global → Collection → Environment → Data → Local**.
**Narrowest wins.** ReVoman models the three persistent scopes (no Data/Local stores), so the
effective precedence for a `{{key}}` or `pm.variables.get(key)` is:

```
environment  ▸  collectionVariables  ▸  globals      (environment wins)
```

- **Reads** (`pm.variables.get`, `{{}}`) walk that chain; first scope that *has* the key wins.
- **Writes** are always scope-routed: `pm.environment.set` / `pm.collectionVariables.set` /
  `pm.globals.set` each target their own store. There is no "write to the aggregate".
- `pm.variables.set(k,v)` in real Postman writes the ephemeral **local** scope (dies after the
  script). ReVoman has no local scope; see Decision D1.

## Problem / Current State

The real postman-sandbox bootcode (running under GraalJS) **already implements** `pm.globals`,
`pm.collectionVariables`, and the `pm.variables` aggregate internally. `SandboxBridge` already
forwards all three scopes *into* every script (`SandboxBridge.kt:152-154`) and reads all three
*back* (`SandboxBridge.kt:246-248`). **Every gap is on ReVoman's exe/SDK side:**

| Scope | SDK store | Persists cross-step? | Resolved in `{{}}`? | On `Rundown`? |
| --- | --- | --- | --- | --- |
| `pm.environment` | backs `mutableEnv` (caller-supplied) | ✅ diffed back | ✅ `RegexReplacer.kt:47` | ✅ `Rundown.mutableEnv` |
| `pm.collectionVariables` | own private map (`PostmanSDK.kt:49-51`) | ✅ diffed back (`PmJsEval.kt:124-128`) | ❌ never resolved | ❌ not exposed |
| `pm.globals` | **no SDK field** | ❌ dropped after each script | ❌ never resolved | ❌ not exposed |

`globals` is weakest: `PmExecutionContext.globals`, `PmExecutionResult.globals`, and the
`SandboxBridge` forward/read-back paths exist, but `PmJsEval.runSandboxScript`
(`PmJsEval.kt:106-128`) never passes globals *into* the context nor diffs it *out*, and `PostmanSDK`
has no `globals` store. A script's `pm.globals.set(...)` survives only within that one script run.

## Non-Goals (explicit scope guards)

1. **Seeding `collectionVariables` from collection-root `variable[]`.** The `Template` parser models
   only `item` + `auth`; root `variable` is unmodeled. `collectionVariables` stays **script-seeded
   only** (a producer step `.set`s, a consumer step `.get`s / `{{}}`-reads). Deferred to a later
   cycle.
2. **Seeding `globals` from Kick input.** No new input surface this cycle. `globals` is script-seeded
   only, exactly like `collectionVariables`.
3. **A Data scope / `pm.iterationData`.** Not modeled. Precedence chain is the three persistent
   scopes only.
4. **A true Local scope for `pm.variables.set`.** ReVoman has no per-script ephemeral store; see
   Decision D1 for how `pm.variables.set` is handled.
5. **Ledger participation for collection/global.** Warm-run ledger (produced/consumed keys, source
   hash, skip/inject) stays **environment-only**, matching today's `collectionVariables` behavior.
   Reason: the ledger's contract is the env scope that feeds cross-run replay; widening it is a
   separate, correctness-risky change.

## Key Decisions

- **D1 — `pm.variables.set` routes to environment.** Postman writes the ephemeral *local* scope;
  ReVoman has none. Routing to `environment` (the closest persistent scope) preserves today's exact
  behavior (the existing `Variables.set` already writes env) and avoids inventing a phantom store. A
  real Local scope is a possible later cycle (Q1).
- **D2 — Ledger / `recordConsumed` stays environment-only.** A `{{}}` hit from collectionVariables or
  globals does not record-consume and does not set-back into env. Only env hits do, exactly as today.
  Keeps warm-run replay's contract unchanged.
- **D3 — Aggregate is read-only.** `pm.variables` exposes `get`/`has` only. There is no aggregate
  write — every write is scope-routed. Matches Postman.
- **D4 — collection + global stores are sandbox-safe-valued.** String/Number/Boolean/null only (via
  `sandboxSafeEnv`), same as collectionVariables. Typed POJOs remain env-exclusive.

## Architecture / Data Flow

Approach **A**: `globals` joins `environment` and `collectionVariables` as a third **peer**
`PostmanEnvironment` on `PostmanSDK`. A small precedence helper backs both the `pm.variables`
aggregate accessor and `{{}}` resolution.

```
                         ┌──────────────────── PostmanSDK ────────────────────┐
   collection scripts    │  environment          (PostmanEnvironment, ↔ mutableEnv) │
   pm.<scope>.set/get ──▶│  collectionVariables  (PostmanEnvironment)               │
                         │  globals              (PostmanEnvironment)   ← NEW        │
                         │  variables            (aggregate READ accessor) ← extended│
                         └──────┬───────────────────────────────────────────────────┘
                                │ resolveScoped(key): walk env ▸ cv ▸ globals
        ┌───────────────────────┼────────────────────────────────┐
        ▼                       ▼                                 ▼
  RegexReplacer            pm.variables.get/has              Rundown
  {{key}} resolves         (aggregate read)                 .mutableEnv          (= environment)
  via precedence                                            .collectionVariables (NEW)
                                                            .globals             (NEW)
```

Per-script flow inside `PmJsEval.runSandboxScript` (mirrors the existing collectionVariables block):

```
before: snapshot env, collectionVariables, globals  (sandboxSafe)
        ─▶ PmExecutionContext(environment, collectionVariables, globals)
        ─▶ PmSandbox.execute ─▶ real bootcode
after:  diffScopes(beforeGlobals, result.globals)
        ─▶ pm.globals.set/unset per the diff   (NEW — identical shape to cVarDiff)
```

## Components & Changes (by file)

### 1. `PostmanSDK.kt` — add `globals` peer + extend `Variables`

- **New field** (mirror `collectionVariables.kt:49-51`):
  ```kotlin
  @JvmField
  val globals: PostmanEnvironment<Any?> = PostmanEnvironment(mutableMapOf(), moshiReVoman)
  ```
  Dormant ledger: `currentStep` never set on it, so `set()` logs `Step: null` and captures no
  produced keys — same property `collectionVariables` already relies on.

- **One precedence helper** (single source of truth for "which scope owns this key"):
  ```kotlin
  /** Postman precedence: environment ▸ collectionVariables ▸ globals. null if unknown in all. */
  internal fun resolveScopedValue(key: String): Any? = when {
      environment.containsKey(key)           -> environment[key]
      collectionVariables.containsKey(key)   -> collectionVariables[key]
      globals.containsKey(key)               -> globals[key]
      else                                   -> null
  }
  internal fun hasScopedValue(key: String): Boolean =
      environment.containsKey(key) ||
      collectionVariables.containsKey(key) ||
      globals.containsKey(key)
  ```

- **Extend the existing `Variables` inner class** (`PostmanSDK.kt:165-187`, currently env-only) so
  `pm.variables` is the true aggregate:
  ```kotlin
  inner class Variables {
      fun has(key: String): Boolean = hasScopedValue(key)
      fun get(key: String): Any? = resolveScopedValue(key)
      // set/unset: see Decision D1 — keep writing environment (closest persistent analog
      // to Postman's local scope), behavior unchanged from today.
  }
  ```

### 2. `PmJsEval.kt` — thread `globals` in + diff it back

In `runSandboxScript` (`PmJsEval.kt:106-128`), add the globals snapshot to the context and a
diff-back block identical in shape to the collectionVariables one:
```kotlin
val beforeGlobals: Map<String, Any?> = sandboxSafeEnv(pm.globals.mutableEnv)
// context: globals = PmScope("globals", beforeGlobals)
val gVarDiff = diffScopes(beforeGlobals, result.globals)
gVarDiff.produced.forEach { key -> pm.globals.set(key, result.globals[key]) }
gVarDiff.unset.forEach { key -> pm.globals.unset(key) }
```

### 3. `RegexReplacer.kt` — `{{}}` resolves via precedence

Today the env-lookup arm (`RegexReplacer.kt:47-51`) reads `pm.environment` only. Replace that single
arm with a precedence-aware lookup that consults env → collectionVariables → globals, using the same
`resolveScopedValue` helper. Preserve existing behavior exactly when only env holds the key:
- still `recordConsumed` **only** when the hit came from the environment scope (ledger stays
  env-only — Decision D2);
- still `setItBackInEnvironment` for env hits (type-coercion + warm cache, unchanged);
- for a collection/global hit, resolve the value but do **not** write it into env and do **not**
  record-consume (it isn't an env variable).

Custom-dynamic and dynamic-variable arms are untouched and keep their current priority (they precede
the scoped lookup, matching the documented order at `RegexReplacer.kt:23-30`).

### 4. `Rundown.kt` + `ReVoman.kt` — expose the two new scopes

- Add to `Rundown` (`Rundown.kt:16-22`), defaulted empty for backward-compatible construction:
  ```kotlin
  @JvmField val collectionVariables: PostmanEnvironment<Any?> = PostmanEnvironment(),
  @JvmField val globals: PostmanEnvironment<Any?> = PostmanEnvironment(),
  ```
- Pass `pm.collectionVariables` + `pm.globals` at both `Rundown(...)` build sites
  (`ReVoman.kt:181` final, `ReVoman.kt:273` per-step snapshot).

`mutableEnv` is untouched everywhere — still `pm.environment`, still the POJO store / ledger source /
direct-write target, all `mutableEnvCopyWith*` consumers unaffected.

## Error Handling

- **No new failure modes.** globals diff-back reuses `diffScopes` + `set/unset`, which never throw on
  sandbox-safe values. A script error still surfaces via `result.error` (unchanged path).
- **Unknown `{{key}}`** in all three scopes → unchanged fallback: the literal `{{key}}` is left in
  place (`RegexReplacer.kt:52`), no throw.
- **Type safety:** collection + global stores hold only sandbox-safe values (String/Number/Boolean/
  null) via `sandboxSafeEnv`, same as collectionVariables today. Typed POJOs live only in env.

## Testing — exhaustive

The existing tests under-specify; this cycle covers each layer in isolation **and** end-to-end. New
test classes, all matching house Kotest style (`shouldBe`, real-sandbox where behavior is in JS):

### A. `PostmanSDKVariableScopesTest` (unit — SDK store + resolver)
- `globals` store round-trips `set`/`toMap`/`unset` (mirror collectionVariables test).
- `globals.set` logs `Step: null` (dormant ledger — assert no produced keys captured).
- **Precedence matrix** (`pm.variables.get`), one test per cell:
  - key only in env → env value
  - key only in collectionVariables → cv value
  - key only in globals → global value
  - key in env+cv → env wins
  - key in env+globals → env wins
  - key in cv+globals → cv wins
  - key in all three → env wins
  - key in none → `null`
- `pm.variables.has` true/false for each of: env-only, cv-only, globals-only, none.
- `null`-valued key present in a scope: `has` true, `get` returns null (presence ≠ value).
- `resolveScopedValue` / `hasScopedValue` direct unit coverage (same matrix, helper-level).

### B. `RegexReplacerScopesTest` (unit — `{{}}` precedence)
- `{{k}}` resolves from env / cv / globals when only that scope has it.
- Precedence: `{{k}}` with k in env+cv → env value; cv+globals → cv value; all three → env.
- **Ledger guard:** a `{{k}}` hit from cv or globals does **NOT** `recordConsumed` (only env does).
  Assert via `producedKeysFor`/`consumedKeysFor` on a stepped env.
- **Setback guard:** a cv/global hit does **NOT** mutate `pm.environment` (env stays without the
  key); an env hit still does (type-coercion preserved).
- Type coercion on env setback unchanged (Int/Long/Double/Float/Boolean) — regression-lock.
- Unknown key in all scopes → literal `{{k}}` preserved.
- Custom-dynamic + dynamic generators still take priority over a scoped value of the same key.
- Recursive resolution: `{{a}}`→`{{b}}`→value across mixed scopes.

### C. `PmJsEvalGlobalsDiffTest` (unit — diff-back via real sandbox)
- Script `pm.globals.set('g','1')` → `pm.globals` holds `g=1` after run.
- Script unsets a seeded global → removed from `pm.globals`.
- Seeded global readable in script (`pm.globals.get`), set-back of a changed value.
- globals + collectionVariables + environment mutated in one script → all three diffed back to the
  right peer, no cross-contamination.
- Non-sandbox-safe value never leaks into a scope (sandboxSafe filter holds).

### D. `Rundown` exposure (unit)
- `Rundown` default construction leaves `collectionVariables`/`globals` empty (backward compat).
- Built `Rundown` carries the SDK's collectionVariables + globals (both build sites).

### E. Integration — extend the Pokemon sandbox collection (real `revUp`)
Augment `pokemon-sandbox-api` collection + `PokemonSandboxApiTest` (currently assertion-light):
- A step sets a **global** in its script; a later step reads it back via `pm.globals.get` inside a
  `pm.test` (cross-step persistence proof — the thing impossible today).
- A `{{globalKey}}` and a `{{collectionVarKey}}` used in a request URL/body resolve correctly
  (precedence proof through the real regex path).
- After `revUp`, assert directly on `rundown.globals` and `rundown.collectionVariables` (direct
  assertion — impossible today), plus the existing `rundown.mutableEnv` checks.
- A precedence collision: same key in env + global, request uses `{{key}}` → env value wins
  (end-to-end precedence proof).
- Keep the `isNotEmpty()` assertion-presence guards (vacuous-allMatch trap, per existing comment).

## Backward Compatibility

- `mutableEnv` semantics, type, and all consumers: **unchanged**.
- `Rundown` gains two **defaulted** fields → existing constructor calls and Java/Kotlin callers
  compile unchanged.
- `{{}}` resolution: behavior identical whenever only env holds a key (the universal case today,
  since cv/globals were never resolved). New resolution only activates for keys that exist *solely*
  in cv/globals — previously those rendered as literal `{{k}}`, a strict capability gain.
- Ledger / warm-run replay: untouched (env-only).

## Open Questions

- **Q1 (D1 surfaced):** Is routing `pm.variables.set` → environment acceptable long-term, or should a
  later cycle add a real ephemeral Local scope? Current choice preserves today's behavior and avoids
  a phantom store; flagged for confirmation.
- **Q2:** Should `{{}}` resolution emit a debug log when a key resolves from cv/globals (vs env), to
  aid the "where did this value come from" troubleshooting that motivated this work? Leaning yes
  (cheap, high diagnostic value); will add a `RevomanLog.debug` unless told otherwise.

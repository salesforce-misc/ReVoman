# Design: Integrate Postman's Real `pm` Sandbox API (Zero-Drift)

**Date:** 2026-06-04
**Status:** Phase 1 implemented & verified (2026-06-04). Phases 2–3 pending.
**Author:** Gopal S Akshintala (with Claude)
**Phase 1 plan:** `docs/superpowers/plans/2026-06-04-pm-sandbox-phase1.md`

## Phase 1 Completion Note (2026-06-04)

Phase 1 (script-only pm APIs via the real sandbox; `pm.sendRequest` + control flow stubbed) is
implemented and verified:

- New `internal.postman.sandbox` package: `SandboxEventLoop`, `Flatted`, `SandboxResources`,
  `PmExecutionContext`/result model, `SandboxBridge` (boots the real bootcode), `PmSandbox` facade,
  `diffScopes`. Vendored resources under `src/main/resources/postman-sandbox/` (+ `generatePmSandbox`
  Gradle task). `PmJsEval` routes pre-req/test scripts through `PmSandbox`; `ReVoman` owns one
  sandbox per run.
- **Verification:** `./gradlew build -x integrationTest` BUILD SUCCESSFUL (compile, spotlessCheck,
  detekt, Kover, all 191 unit tests). Integration: every test not depending on the external
  `api.restful-api.dev` passes — incl. PokemonTest, Pokemon V2-vs-V3 env/step equivalence, and both
  ledger round-trip tests (the ledger parity gate, green when the external API was healthy). The 5
  red integration tests are all the `api.restful-api.dev` public demo API returning HTTP 405 to all
  callers (confirmed via raw `curl`) — an external outage, unrelated to this change.
- **Env-sync refinement (beyond original plan):** only sandbox-safe env values
  (String/Number/Boolean/null) cross the bridge; ReVoman's typed POJOs stay in the Kotlin env.
  Integral JSON numbers are narrowed back to `Int`/`Long` on scope read-back (JSON has no int/double
  distinction) so values round-trip with their original type and the diff doesn't spuriously flag
  untouched numeric keys. Regression tests added.

### Phase 1 known limitations (carry into Phase 2/3)

- `pm.sendRequest` raises `UnsupportedOperationException("...Phase 2")` (no host HTTP responder yet).
- `pm.execution.setNextRequest`/`skipRequest` decoded into the result model but not wired to the
  sequencer.
- `pm.test` assertions are decoded into `PmExecutionResult.assertions` but not yet attached to
  `StepReport` (Phase 2).
- Sandbox `timeoutMs` is forwarded but enforced in virtual time only (no host wall-clock bound).
- `pm.cookies`/`pm.vault`/`pm.datasets` out of scope.
- Old `PostmanSDK.evaluateJS` shim retained for back-compat (`EvalJsTest`); removal is Phase 3.

## Problem

ReVoman today reimplements a *subset* of the Postman `pm` scripting API by hand in
`src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt` (env/variables,
`request`/`response` with `.json()`/`.text()`, `info`, `xml2Json`, dynamic-variable
generators). This subset:

- Covers only a fraction of the real `pm` surface (no full `pm.test`/`pm.expect` chai
  assertions, no `pm.sendRequest`, no `pm.execution.setNextRequest`/`skipRequest`, partial
  variable scopes, etc.).
- **Drifts** from upstream Postman: every behavior is reimplemented, so signatures and
  semantics must be manually chased as Postman evolves.

**Goal:** Support the *entire* `pm` API and eliminate long-term drift, **without rewriting**
those APIs in ReVoman, while staying **JVM-first** (consumers install nothing extra — no
Node, no Newman).

## Key Insight (validated by spike)

Postman's scripting sandbox is open source (`postman-sandbox` on npm). It ships a **prebuilt,
browserified `bootcode`** (`.cache/bootcode.browser.js`, ~2 MB resolved) that contains the
**real** `pm` API plus `postman-collection`, `lodash`, and `xml2js` — bundled into a single
self-contained string with **zero `require('vm'|'fs'|'child_process'|'module')`** references.
It only needs a handful of browser-ish globals.

A feasibility spike (preserved at
`docs/superpowers/specs/assets/pm-sandbox-spike/`, to be folded into a permanent test) booted
this real bootcode inside the **GraalJS context ReVoman already embeds** and drove it through
Postman's own `uvm` bridge protocol from Java. Result:

```
pm.environment.set('spikeKey', 'spikeVal-2')  -> round-trips in execution.result
pm.test(...) + pm.expect(...).to.eql(...)     -> 3 assertion events, correct passed flags
                                                 (2 pass, 1 intentional fail w/ error)
full async lifecycle (async-IIFE -> setImmediate completion) drained via a Java event loop
```

**SPIKE PASS.** Postman's real `pm` code runs under embedded GraalJS. This makes `pm.*`
*literally Postman's own JS* — there is no behavior to keep in sync. Upgrades become a file
swap.

## Chosen Approach: Real bootcode + minimal Kotlin bridge

Bundle Postman's prebuilt browser `bootcode` as a classpath resource and replace `uvm`'s
Node-worker transport with a small Kotlin bridge. Because GraalJS is single-threaded and
in-process, we **skip** `uvm`'s worker/`postMessage` layer entirely and talk to the guest
`bridge` object directly.

### Rejected alternatives

- **Bundle a real Node runtime (Javet / child process)** — truest fidelity but heavy native
  dep, breaks pure-JVM portability, violates the no-consumer-install constraint.
- **Delegate to Newman** — maximal fidelity but reshapes ReVoman's JVM-native orchestration
  and couples to Newman's lifecycle.
- **Keep the shim, code-gen from Postman types** — still reimplements behavior; only
  signatures track upstream. Fails the zero-drift goal.

## Architecture

New package: `com.salesforce.revoman.internal.postman.sandbox`

```
sandbox/
  PmSandbox.kt          // internal entry: boot once, execute(script, target, context) -> result
  SandboxBridge.kt      // owns GraalJS Context; host<->guest event routing (bridge.emit / __uvm_emit)
  SandboxEventLoop.kt   // single-threaded timer queue backing setTimeout/setImmediate/setInterval
  BrowserGlobals.kt     // closure-injected shims: timers, atob/btoa, Blob/File/FileReader/FormData
  Flatted.kt            // circular-safe (de)serialize for guest->host dispatch payloads
  SandboxResources.kt   // loads bootcode.js + bridge-client.js via ClasspathResolver (okio gotcha)
  ExecutionResult.kt    // typed view over execution.result: env/globals scopes, assertions, nextRequest
  events/
    SendRequestHandler.kt    // execution.request.<id> -> http4k client -> response back
    ControlFlowHandler.kt    // execution.skipRequest.<id> + pm.execution.setNextRequest
    AssertionHandler.kt      // execution.assertion -> StepReport (pm.test results)
    ConsoleHandler.kt        // console.* -> KotlinLogging
```

### Boundary contract

`PmSandbox` exposes a single method; all GraalJS/bridge/Flatted detail lives behind it:

```kotlin
fun execute(
  script: String,
  target: ScriptTarget,        // PRE_REQUEST | TEST
  context: PmExecutionContext, // env, globals, collectionVars, request, response
): PmExecutionResult           // mutated scopes + assertions + control-flow directives
```

`PmJsEval.kt` (current caller) only builds the context and reads the result — no polyglot
leakage into the rest of ReVoman.

### Replace / Stays

- **Replaces:** `PostmanSDK`'s hand-rolled `variables`, `request.json()`,
  `response.json()/text()`, `info`, `xml2Json`, dynamic-variable generators (`{{$guid}}`,
  `{{$randomInt}}`, `{{$timestamp}}`, … now come from real Postman code).
- **Stays:** `PostmanEnvironment` (reporting/ledger model) — now *synced from* the sandbox's
  returned scopes rather than mutated in-JS. `RegexReplacer` stays for `{{var}}` substitution
  in request templates (ReVoman's own pre-send templating, separate from sandbox scripts).

### Key properties

Pure-JVM (no Node/Newman; drops the runtime `nodeModulesPath`/npm-for-pm dependency). One
in-process GraalJS context reused across steps (Postman's persistent-scope semantics; matches
today's single `jsContext`). Zero-drift on `pm.*`.

## Bridge Internals & Lifecycle

### Boot (once per ReVoman run, lazily on first `execute`)

1. Build GraalJS `Context` (today's options + `ecmascript-version=2024`, `host-access=ALL`).
2. Inject Java host fns: `__java_setTimer`, `__java_clearTimer`, `__java_emit`, `__java_atob`,
   `__java_btoa`.
3. Eval a **globals-installer IIFE** that closure-captures those host fns (so they survive the
   bootcode's `recreatingTheUniverse()` global wipe), installs
   `setTimeout/setImmediate/setInterval/clear*`, `queueMicrotask`, `atob/btoa`,
   `Blob/File/FileReader/FormData`, `__uvm_emit`, `__uvm_setTimeout` — then evals the
   `bridge-client.js` string.
4. **Capture the guest `bridge` Value** before bootcode deletes the global.
5. Eval `bootcode.js`; drain the event loop.
6. `bridge.emit("initialize", {})`; drain. Sandbox ready.

> Why the closure: `recreatingTheUniverse()` deletes every global except an allowlist
> (`bridge`, `__uvm_emit`, `__uvm_setTimeout`, `setTimeout`, …). Host helpers named `__java_*`
> are NOT allowlisted, so they must be closed over, not left global. (Spike confirmed both the
> failure and the fix.)

### Per-execute (host → guest)

```
bridge.emit("execute", id, eventObj, contextObj, optionsObj)   // host ProxyObjects, no Flatted
drain event loop until execution.result.<id> seen (or timeout)
```

- `id` — monotonic per-step string.
- `eventObj` — `{listen: "prerequest"|"test", script: {type:"text/javascript", exec:[scriptString]}}`.
- `contextObj` — `{environment, globals, collectionVariables, _variables, request, response}`
  as `ProxyObject`/`ProxyArray` over the current `PmExecutionContext`. Variable scopes are
  `{id, values:[{key,value}, …]}`.
- `optionsObj` — `{timeout, cursor:{}, allowSkipRequest: target == PRE_REQUEST}`.

### Guest → host (`__uvm_emit(flattedString)` path)

Java collects each emit, `Flatted.parse` → `(eventName, ...args)`, routed by prefix:

| Event                        | Handler            | Action |
|------------------------------|--------------------|--------|
| `execution.assertion.<id>`   | AssertionHandler   | append `pm.test` results to StepReport |
| `execution.request.<id>`     | SendRequestHandler | run via http4k → `bridge.emit("execution.response.<id>", eventId, err, res)` |
| `execution.skipRequest.<id>` | ControlFlowHandler | mark step skipped |
| `execution.error.<id>`       | error              | fail step (PreReq/PostRes failure) |
| `execution.result.<id>`      | result             | terminal: read back scopes, finish |
| `console` / `execution.console` | ConsoleHandler  | KotlinLogging |

### Async correctness

`pm.sendRequest` and timer callbacks are async. The sandbox wraps user scripts in an
`async` IIFE and dispatches `execution.result.<id>` via `setImmediate` after it settles
(confirmed by spike). The event loop drains until that terminal event fires or a host
watchdog hits `options.timeout`; the sandbox also self-enforces timeout and emits
`execution.error`.

### Lifecycle / isolation / threading

One shared `Context` per ReVoman run, reused across steps, disposed at run end. Env/globals
are carried explicitly in each `execute` context, so step-to-step state is deterministic, not
leaked JS globals. `<id>`-suffixed bridge listeners are auto-cleaned by the sandbox on
completion (per `execute.js`). GraalJS `Context` is single-threaded; `PmSandbox` confines all
eval + loop draining to the calling thread. ReVoman runs steps serially (`fold`), so this
fits. Parallel steps would need a context pool — **out of scope**.

## Data Flow & Env Sync

### Per-step sequence (in `PmJsEval.kt`; call sites unchanged)

```
PRE_REQUEST:
  build PmExecutionContext{env, globals, collectionVars, request(from template)}
  PmSandbox.execute(preReqScript, PRE_REQUEST, ctx) -> result
  apply result.scopes back to PostmanEnvironment   (ledger capture sees produced/consumed)
  apply result.request mutations (header/url/body) -> outgoing http request
  if result.skipRequest -> mark step skipped, no HTTP fired

(HTTP fires via existing fireHttpRequest)

TEST (post-response):
  build PmExecutionContext{env, globals, ..., request, response(code/status/body)}
  PmSandbox.execute(testScript, TEST, ctx) -> result
  apply result.scopes back to PostmanEnvironment
  append result.assertions to StepReport
  if result.nextRequest -> hand to sequencer
```

### Env-sync model (critical for the ledger)

Today scripts mutate env in-JS via `pm.environment.set`, and `Variables.set` logs produced
keys live. With the real sandbox, mutations happen in the guest and return in
`execution.result`'s scopes. Sync becomes a **diff**:

- Before execute: snapshot env/globals/collectionVars keys+values.
- After execute: read returned `VariableScope.values`; **diff** vs snapshot → `produced`
  (new/changed) and `unset` (removed).
- Detect `consumed` via the scope's mutation tracker if available; otherwise keep the current
  consumed-detection heuristic.
- Apply the diff to `PostmanEnvironment` through the same code path that records ledger
  `produced`/`consumed`, so **the ledger keeps working unchanged**.

This is the single place real behavior shifts: produced-key capture moves from a live `set()`
callback to a post-execution diff. The produced set should be identical; **this is the
highest test priority** (ledger parity goldens).

### `pm.execution.setNextRequest(name|null)` → sequencer

Today's loop is a linear `fold` over `pickedSteps` with `takeWhile { !haltExecution }`. To
honor control flow:

- Result carries `nextRequest: String?` and a stop sentinel for `setNextRequest(null)`.
- The traversal consults it: if set, jump to the named step (Postman semantics: affects only
  the next hop, intra-collection). Resolve to an index lookup; unknown name → step failure
  with a clear message.
- `skipRequest` (pre-request only) → step marked skipped, HTTP + test skipped, loop continues.

To preserve STYLE.md's immutable-flow guideline, traversal is modeled as a small
recursive/iterative driver over an index with an explicit "next index" derived from the
result, rather than mutating shared state.

### Failure mapping (surface unchanged)

`execution.error` during pre-req → `PreReqJSFailure`; during test → `PostResJSFailure`;
sandbox boot failure → new `SandboxBootFailure` surfaced once per run. **Assertion failures
(`pm.test` with failed expect) are NOT exceptions** — recorded as failed assertions in the
StepReport, matching Postman/Newman.

## Build, Bundling & Resources

- Vendor two classpath resources in the published jar: `bootcode.js` (~2 MB resolved browser
  bundle) + `bridge-client.js`, under `src/main/resources/postman-sandbox/`.
- A Gradle task `generatePmSandbox` (reuses the existing `node`/`npmInstall` setup and `js/`
  project already in `build.gradle.kts`) regenerates these from a **pinned** `postman-sandbox`
  version (extract the resolved bundle via the package's `bundler` callback) and writes them
  to resources. **Upgrade = bump version, rerun task, commit.** This is the zero-drift lever.
- Also emit `pm-sandbox-version.txt` recording the bundled version, logged at boot.
- Load resources via the project's `ClasspathResolver` helpers, **not** okio
  `FileSystem.RESOURCES` (known classloader gotcha — okio's RESOURCES binds to okio's own
  classloader).

### Dependency cleanup

The per-consumer `nodeModulesPath` + `commonjs-require` path existed to supply
`lodash`/`moment`/`xml2js` to the old shim. The real bootcode bundles its own. So
`nodeModulesPath` becomes a **no-op for the pm path**: keep the public `Kick.nodeModulesPath()`
API for back-compat but document it as no-op for sandbox scripts. The `js/` node project +
`npmInstall` wiring remain only as the **generator input**, not a runtime dependency.

## Testing Strategy

1. **Sandbox-unit (new, Kotest):** boot once; exercise each pm area — `pm.test/expect`
   (pass+fail+skip), `pm.environment`/`globals`/`collectionVariables` get/set/unset/has/toObject,
   `pm.variables.replaceIn`, `pm.request`/`pm.response` (`.json()`, `.text()`, `.code`,
   `pm.response.to.have.status`), `pm.info`, dynamic vars (`{{$guid}}`, `{{$randomInt}}`,
   `{{$timestamp}}`), `xml2Json`, `JSON.parse`. Assert via returned events.
2. **Bridge-protocol tests:** Flatted round-trip; event-routing table; event-loop draining
   (async `setTimeout`, `pm.sendRequest` callback ordering); timeout watchdog.
3. **sendRequest integration:** stub http4k server; assert script-initiated GET/POST hits it
   and `pm.response` in the callback is correct.
4. **Control flow:** `setNextRequest` reorders; `setNextRequest(null)` stops; `skipRequest`
   skips; unknown name → failure.
5. **Ledger parity (highest priority):** golden tests asserting produced/consumed/unset diff
   matches the old shim's ledger output on existing collections — guards the env-sync-by-diff
   shift.
6. **Regression:** full existing `test` + `integrationTest` suites stay green (real
   collections, e.g. Pokemon, end-to-end).
7. **Boot canary:** fold the throwaway Java spike into a permanent Kotest asserting the bundled
   bootcode boots under GraalJS.

## Logging

Boot logs bundled `postman-sandbox` version. Per-step debug logs of dispatched/received events
behind a flag. Script `console.*` routed to logger at info. Produced/consumed diff logged as
today.

## Rollout

Single release; guarded during dev.

- **Phase 1:** bridge + script-only pm APIs + env-sync diff; `pm.sendRequest` / `setNextRequest`
  stubbed (throw "not yet supported"). Run full suite, fix parity.
- **Phase 2:** enable `pm.sendRequest` (reuse http4k `ApacheClient`, **bypass** ReVoman step
  hooks/ledger/reporting — Postman semantics: a raw side-channel, not a collection step) +
  control flow.
- **Phase 3:** remove old `PostmanSDK` shim internals (it's `internal`; full removal expected).
  **Out of scope (throw clear "unsupported"):** `pm.cookies`/cookieJar, `pm.vault`,
  `pm.datasets`.

## Risks & Mitigations

- **Browser-global gaps beyond the spike set** (e.g. fuller `Blob`/`FormData` for
  `pm.sendRequest` file bodies) → add shims as integration tests surface them; maintain a
  documented known-limits list.
- **GraalJS interpreter-only perf** (no JVMCI in the embedding) → boot once per run amortizes
  the ~2 MB parse; benchmark vs current shim in a test.
- **Flatted edge cases** (deeply circular execution objects) → port `uvm`'s exact Flatted and
  test against its fixtures.

## Decisions (from brainstorming)

- Runtime: **GraalJS only** (recommend accepted after spike proved feasibility).
- Host scope: **`pm.sendRequest` + control flow (`setNextRequest`/`skipRequest`)**; cookies /
  vault / datasets **out of scope**.
- `pm.sendRequest`: **reuse http4k client, bypass hooks**.
- Context lifecycle: **one Context per ReVoman run**.
- De-risk: **spike first** — done, PASS.

## Out of Scope

`pm.cookies` / cookieJar, `pm.vault`, `pm.datasets`; parallel-step context pooling;
cross-run Context caching.

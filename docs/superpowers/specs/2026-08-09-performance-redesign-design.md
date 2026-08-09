# ReVoman Major-Version Performance Redesign

**Date:** 2026-08-09

**Status:** Approved interactively; pending written-spec review

**Target:** Next major ReVoman release
**Baseline revision:** 83f3cd70

## Summary

ReVoman will replace its scattered per-run mutable state with one explicit internal
ExecutionSession created by the outermost revUp call. Each kick runs in a child KickExecution.
The outer session owns input freshness, environment carry-forward, accumulated final Rundowns,
logging attachment, and top-level cleanup; the child owns live progress, kick-local scopes and
journals, codecs, polling state, sandbox state, and kick cleanup. Nested kicks driven by a list or
runbook share the outer session but never share kick-local state. Independent public revUp calls do
not share either boundary.

The redesign serves two workloads equally:

- repeated short executions inside a long-lived host JVM; and
- one standalone execution per process.

Shared reuse is limited to immutable, safe runtime resources. Per-execution cost must fall in both
modes. There is no global standalone/embedded mode switch; configuration is limited to narrow,
bounded policy choices.

This is a major-version redesign. Breaking APIs and observable behavior are permitted where they
remove ambiguous lifecycle semantics, unbounded retention, or hidden mutation.

## Goals

1. Remove the unused legacy Graal context created for every kick.
2. Eliminate environment-wide copying, serialization, and writeback from ordinary step execution.
3. Replace quadratic partial-Rundown maintenance with O(1) progress updates.
4. Make disabled logging genuinely lazy and file logging boundary-buffered.
5. Bound polling memory and enforce a real monotonic deadline.
6. Replace repeated and leaking input reads with one execution-scoped immutable input snapshot.
7. Replace mutable Moshi adapter registration with immutable reusable codecs.
8. Remove StepReport.pmEnvSnapshot without relocating a full environment snapshot elsewhere.
9. Repair the benchmark harness and enforce both cold and warm regression gates.
10. Preserve immediate bidirectional visibility between scripts and host hooks at every phase
    transition.

## Non-goals

- No distributed cache such as Redis or Valkey.
- No cross-execution parsed-input cache in the first implementation.
- No pooled or shared Graal Context until a complete isolation-safe reset protocol is proven.
- No general event-sourced rewrite of the full runtime.
- No asynchronous revUp API redesign.
- No automatic full environment history.
- No removal of exceptional custom JSON decoding.
- No de-functionalization of the existing Either-based execution pipeline merely for microseconds.

## Audit evidence

The design responds to the following current-code findings:

- PostmanSDK.kt constructs JSEvaluator and a Graal Context eagerly for every kick, although real
  Postman scripts execute through PmSandbox. The legacy Context has no deterministic close path.
- RegexReplacer.replaceVariablesInEnv copies the full environment, rebuilds another map, and the
  executor writes the whole result back before every step.
- Every sandbox phase copies all three variable scopes, builds proxy objects per entry, receives
  full scopes back, and diffs them on the host.
- ReVoman seeds a partial Rundown with stepReportsSoFar plus the current report, while syncProgress
  reconstructs the growing list up to three more times per step.
- the default RunLogSink.NoOp is installed as a real sink, forcing disabled narration lambdas to be
  evaluated. Environment mutation also logs pretty-printed values at INFO.
- FileRunLogSink flushes after every small write and rewrites the entire finished file to duplicate
  a performance summary.
- Polling retains every full Response and blocks without a hard per-request remaining-deadline cap.
- four PostmanSDK capture maps remain keyed by the deep Step data class.
- repeated custom typed reads rebuild and extend the mutable Moshi factory chain.
- V3 detection/loading repeats resolution and definition parsing and does not consistently close
  owned buffered sources.
- the JMH fat jar loses required multi-release metadata, benchmark fork failures do not fail the
  Gradle task, and INFO logging contaminates environment benchmark results.

### Finding ownership

| Audited bottleneck/opportunity | Owning change set | Proof |
|---|---|---|
| eager legacy JSEvaluator Context and weak boot-Source retention | CS2a | zero legacy Context counter; cold no-script workload |
| quadratic report/current-Rundown and multi-kick list copies | CS4 | prefix-copy counter; 200–3,200-step slope |
| full environment replacement and static-entry writeback | CS3 | candidate/write counters; large-static-env workload |
| full host/guest scope serialization and diffing | CS3 | mutation/read-sized bridge counters and phase tests |
| unbounded synchronous sandbox execution/event loop | CS3 | forked infinite-script/timer cancellation tests |
| deep Step map keys and repeated control-flow target scans | CS2b | identity/hash counters and jump workload |
| NoOp/effectively-disabled eager log rendering | CS2d | lazy-supplier evaluation counters |
| per-line file flush and close-time whole-file summary rewrite | CS2d | flush/write/footer counters |
| unbounded polling history, final sleep, and in-flight request | CS5 | retention and absolute-deadline cancellation tests |
| shared mutable Moshi adapter/factory growth | CS2c | immutable-chain/cache tests |
| eager/redundant typed HTTP JSON conversion | CS4 | zero-conversion counter when no typed consumer exists |
| duplicate V3 resolution/parsing and unclosed sources | CS6 | physical-read/parse/close counters |
| reusable consumed InputStreams and unbounded JAR filesystem registry | CS6 | source/lease lifecycle and reachability tests |
| invalid JMH packaging, silent fork errors, and log contamination | CS1 | harness self-tests and clean baseline capture |

## Settled design decisions

The following decisions are authoritative:

1. Breaking changes are allowed for the major release.
2. Cold standalone and warm repeated execution are equal design targets.
3. Remove PostmanSDK.evaluateJS, jsonStrToObj, and JSEvaluator.
4. File logs flush at step/runbook boundaries and close, not after every line.
5. The performance summary appears once at the footer; no close-time whole-file rewrite.
6. Hooks observe live state through a distinct RunProgress type. Retained progress may advance.
7. Historical state is frozen only through explicit snapshot calls or an opt-in ExecutionTrace.
8. The final Rundown is immutable.
9. Host and guest mutations cross an immediate bidirectional barrier at every host/guest phase
   transition.
10. Polling retains the terminal response, aggregate status counts, and at most a configured number
    of recent intermediate responses. Unbounded retention is unavailable.
11. Custom JSON decoding remains supported through immutable run codecs, explicit one-off adapters,
    and reusable derived codecs.
12. Inputs are fresh between outer executions and stable within an execution.
13. No global runtime profile. Lazy defaults serve both modes; only narrow bounded policies are
    configurable.

## Architecture

~~~mermaid
flowchart TD
    A["Public revUp(Kick)"] --> S["ExecutionSession"]
    B["Public revUp(List<Kick>)"] --> S
    C["Public revUp(Runbook)"] --> S

    S --> I["ExecutionInputs"]
    S --> E["Carried environment"]
    S --> RB["Final Rundown builder"]
    S --> K["KickExecution, one at a time"]
    S --> L["RunLog dispatcher"]

    K --> P["Kick-local RunProgress"]
    K --> V["Kick-local stores and journals"]
    K --> J["Kick-local immutable JsonCodec"]
    K --> Q["Kick-local polling controller"]
    K --> C["Lazy PmSandbox Context"]

    R["Process-wide immutable resources"] --> G["Shared Graal Engine"]
    R --> BS["Strongly retained boot Source"]
    G --> S
    BS --> S

    S --> F["Immutable final Rundown"]
    K --> PR["Terminal-first PollingReport"]
    S --> LOG["Boundary-flushed logs"]
~~~

### ExecutionSession boundary

The outermost public revUp call creates one ExecutionSession and closes it in a top-level finally.
Each internal kick creates and closes exactly one KickExecution child.

- revUp(Kick) creates a session for that kick.
- revUp(List<Kick>) creates one session and passes it explicitly to every internal kick execution.
- revUp(Runbook) creates one session and passes it explicitly to every internal runbook kick.
- framework code must not implement list/runbook execution by recursively calling the public
  revUp(Kick) overload.
- a user-initiated reentrant public revUp call from a hook creates an independent child execution
  session. It does not implicitly share the caller's mutable state.

The session is passed explicitly through internal functions. It is not discovered through a new
ThreadLocal. Existing logging ThreadLocal behavior may remain temporarily behind RunLogDispatcher,
but session ownership itself is lexical and explicit.

ExecutionSession owns:

- ExecutionInputs and their top-level cleanup registry;
- the carried environment between kicks;
- the append-only builder of finalized kick Rundowns;
- RunLogDispatcher plus ownership metadata for attached sinks; and
- creation, ordering, and deterministic closure of each KickExecution.

KickExecution owns:

- one kick-local RunProgress builder;
- environment, collection-variable, and global working stores plus mutation journals;
- the kick-derived immutable JsonCodec;
- the lazily created PmSandbox and its revision epoch;
- polling observers and bounded buffers; and
- every other resource whose lifetime ends with the kick.

Only the environment is carried to the next kick. Collection variables, globals, progress,
sandbox globals, revision epochs, polling buffers, and derived codecs start fresh for every kick.
The next kick is seeded with the previous kick's finalized environment as its highest-precedence
dynamic layer. Existing merge precedence remains: ledger values are the lowest-precedence floor;
YAML paths are overlaid by JSON paths, then environment streams, then dynamic values; and the
carried environment wins over that kick's own dynamic values on collision. Post-execution
environment mutations are finalized before this carry. Collection/global mutations never carry.

Process-wide retained execution-data caches are limited to the immutable Graal Engine and boot
Source. Shared infrastructure may include the pooled HTTP client and an active-lease registry for
JAR filesystems, but it may not retain completed execution data. No Context, input snapshot, report
builder, mutable scope, derived codec, log buffer, or polling history enters a process-wide cache.

Sink ownership is explicit. A sink supplied by a caller is borrowed: the session may dispatch and
flush it, but never closes it. A sink opened by the session is owned and closed in the session
finally. This preserves consumers that intentionally span setup, body, and cleanup executions with
one sink while still making session-opened resources deterministic.

## Public state model

### RunProgress

RunProgress is a distinct read-only live type:

~~~kotlin
interface RunProgress {
    val completedReports: List<StepReport>
    val currentReport: StepReport?
    val scopes: VariableScopesView
    fun snapshot(): RundownSnapshot
}
~~~

- completedReports is an unmodifiable live view over the internal append-only builder.
- currentReport is a live pointer replaced in O(1) as a step advances.
- scopes exposes FrozenValue reads only; it never returns a mutable consumer object.
- retaining RunProgress does not freeze it.
- snapshot explicitly freezes reports, current progress, and every variable scope.
- RunProgress belongs to one KickExecution, not the outer multi-kick session.
- it is execution-thread-confined while live. A callback that needs cross-thread or historical use
  calls snapshot and publishes the immutable result.
- after kick success or failure, a retained RunProgress is detached from the mutable builder and
  becomes a stable terminal view. Terminalization appends any failed current report, sets
  currentReport to null, swaps reports and scopes to immutable terminal data, and severs every
  reference to KickExecution, working stores, journals, codec, sandbox, and input graph. It never
  advances into the next kick.
- snapshot observes one phase-consistent state. Callback entry points are quiescent phase barriers,
  so the execution-thread-confined implementation copies pointer/list/scopes only between commits
  and needs no cross-thread lock on the hot path.

### Frozen snapshots and values

RundownSnapshot and final Rundown contain VariableScopesSnapshot rather than mutable
PostmanEnvironment objects.

~~~kotlin
data class RundownSnapshot(
    val completedReports: List<StepReport>,
    val currentReport: StepReport?,
    val scopes: VariableScopesSnapshot,
)

data class VariableScopesSnapshot(
    val environment: Map<String, FrozenValue>,
    val collectionVariables: Map<String, FrozenValue>,
    val globals: Map<String, FrozenValue>,
)
~~~

FrozenValue is a sealed, deeply immutable JSON-compatible value algebra:

- Null, StringValue, BooleanValue, IntValue, LongValue, BigIntegerValue, DecimalValue, ListValue,
  and ObjectValue;
- finite integral guest numbers normalize to Int when in range, then Long, then BigInteger;
  non-integral values use immutable BigDecimal/DecimalValue and preserve their canonical decimal
  lexeme. Existing BigInteger/BigDecimal inputs therefore do not lose precision; decoding to Double
  is an explicit potentially lossy target-type choice. NaN and infinities are rejected;
- ObjectValue keys are strings and preserve insertion order; other map-key types are rejected;
- lists and maps are copied recursively, with a maximum nesting depth of 128;
- identity-based cycle detection reports the variable key and value path; shared acyclic references
  are copied as values rather than preserving object identity;
- supported POJOs first pass through the kick codec, then the result is defensively frozen again so
  a custom adapter cannot smuggle a mutable list or map into a snapshot; and
- an unsupported value fails the responsible set/snapshot/finalization operation with a clear
  type, key, and value-path error rather than retaining an opaque mutable reference.

VariableScopeEditor freezes or codec-converts on ingress, before returning from set, so later
mutation of the caller's original object cannot bypass journals. Frozen values support typed
decoding through an explicitly supplied JsonCodec or JsonAdapter. A final Rundown does not retain
the KickExecution codec; callers that need custom post-run decoding deliberately retain or rebuild
their immutable derived codec.

### Final Rundown

The final Rundown is materialized exactly once after the post-execution hook:

- StepReport uses immutable report DTOs and defensively copied collections;
- request/response reports retain HttpRequestSnapshot/HttpResponseSnapshot values. Headers are
  defensively copied ordered immutable lists and bodies are immutable Okio ByteString values;
  neither snapshot exposes ByteArray without copying, a body stream, a pooled connection, a live
  http4k message, a parsed txnObj value, or a codec reference;
- typed request/response conversion is on demand through a caller-supplied codec or adapter;
- all three scopes are immutable snapshots;
- learned ledger, stop reason, counts, and failures are final;
- no mutableEnv field, mutable-map delegation, parsed template node, or execution resource remains;
- diagnostic Throwable causes may be retained outside structural equality and serialized output,
  but returned report data is otherwise transitively immutable.

### Optional ExecutionTrace

Automatic historical snapshots are disabled by default. A caller that requires full history may
install an ExecutionTrace sink or call RunProgress.snapshot at selected boundaries. ExecutionTrace
must itself be bounded or streaming; this design does not provide an unbounded in-memory trace
implementation.

## Hook and pick APIs

Picks are observational. Hooks receive mutation capability explicitly.

~~~kotlin
interface VariableScopeEditor {
    fun set(key: String, value: Any?)
    fun unset(key: String)
}

data class VariableScopeEditors(
    val environment: VariableScopeEditor,
    val collectionVariables: VariableScopeEditor,
    val globals: VariableScopeEditor,
)

interface StepHookContext {
    val progress: RunProgress
    val scopes: VariableScopeEditors
}
~~~

Rules:

- pre- and post-transaction picks receive RunProgress and cannot mutate it;
- pre-step hooks receive the current Step, request info, and StepHookContext;
- post-step hooks receive the current StepReport and StepHookContext;
- every public set/unset enters the appropriate host journal immediately;
- editor capabilities are synchronous, callback-scoped, and execution-thread-confined. Retaining
  an editor and using it after its callback returns, after kick finalization, or from another thread
  fails with IllegalStateException;
- scope reads return FrozenValue and editor set freezes/copies its input before returning;
- environment writes are ledger-aware; collection/global writes remain outside the ledger;
- raw index mutation and mutable-map views are removed from public APIs;
- internal ledger reuse has a private injection operation that records reuse, not production;
- writes made before a host hook throws remain applied, matching current direct-mutation behavior;
- hook execution remains ordered and stops after the first hook failure.

The post-execution hook changes from receiving a mutable Rundown to a PostExecutionContext:

~~~kotlin
interface PostExecutionContext {
    val progress: RunProgress
    val priorRundowns: List<Rundown>
    val scopes: VariableScopeEditors
}
~~~

It runs after the current kick's steps finish but before the kick Rundown is frozen. Its mutations
therefore appear in the finalized Rundown and seed the next kick. priorRundowns excludes the current
kick by construction; progress.snapshot supplies the explicit current-kick snapshot. This replaces
the ambiguous old callback list that included a mutable current Rundown.

## Report accumulation

Each KickExecution keeps:

- an ArrayList-like completed report builder;
- one current StepReport pointer; and
- an unmodifiable live completed-report view exposed through RunProgress.

Beginning a step assigns currentReport without copying completed reports. syncProgress is replaced
by O(1) currentReport replacement. Completing a step appends once and clears currentReport.

RunProgress.snapshot copies the completed reports and current report explicitly. Finalization copies
the completed reports once. Multi-kick and runbook Rundown accumulation uses the outer
ExecutionSession's mutable builder and freezes once.

The required complexity is linear in step count: no implementation may rely on repeated List.plus
or dropLast-plus reconstruction in the execution loop. A structural counter proves zero prefix
copies in ordinary CI; the controlled 200-through-3,200-step benchmark must have log-log latency
slope no greater than 1.15 and per-step allocation variance no greater than 10%.

### Step identity

Introduce a type-safe precomputed identity:

~~~kotlin
@JvmInline
value class StepId(val value: String)
~~~

StepId is an execution-local occurrence identity, not a path identity. It is derived from the kick
namespace, configured source occurrence ordinal, and flattened item ordinal. Executing the same
template path twice therefore creates distinct StepIds, while backward jumps/loop iterations reuse
the same StepId and carry a separate iteration number. User-facing path/name matching remains a
separate concern.

All assertion, control-flow, produced/consumed, and skip capture maps use StepId. Deep Step
data-class hashing is not permitted on the hot path. Durable ledger identity uses a separate
LedgerStepKey composed from stable logical source identity, configured occurrence ordinal, and
flattened item path. Filesystem location is represented by the declared workspace-relative/logical
source id rather than a checkout-specific absolute path; reopenable custom sources must supply a
stable logical id. sourceHash remains in LedgerEntry as the compared staleness fingerprint, never
inside the key. Its JSON migration is versioned for the major release.

KickExecution precomputes a jump-target index over the picked execution universe. It preserves the
current first-match Step.stepNameMatches semantics for duplicate names but resolves every later
setNextRequest without rescanning the step list.

Reports and progress do not retain the execution template object graph:

~~~kotlin
data class StepDescriptor(
    val id: StepId,
    val index: String,
    val name: String,
    val path: String,
    val method: String,
    val rawUri: String,
    val sourceHash: String,
)

internal data class ExecutableStep(
    val descriptor: StepDescriptor,
    val template: Item,
)
~~~

StepReport and public picks use StepDescriptor plus deliberately exposed lightweight immutable
request metadata. Request bodies, script source, folder trees, and parsed template nodes remain on
ExecutableStep. Because setNextRequest can jump backward, executable graphs remain until their
owning kick terminates unless a later proven liveness analysis establishes an earlier last use;
they are then released. The migration guide must replace custom picks that reached through
Step.rawPMStep with descriptor/request-metadata APIs.

## Removing pmEnvSnapshot

StepReport.pmEnvSnapshot is removed. A complete environment snapshot must not be relocated into a
replacement per-step field.

StepReport records only journal-derived effects:

~~~kotlin
data class StepEnvironmentEffects(
    val producedValues: Map<String, FrozenValue> = emptyMap(),
    val consumedKeys: Set<String> = emptySet(),
    val unsetKeys: Set<String> = emptySet(),
    val reusedValues: Map<String, FrozenValue> = emptyMap(),
)
~~~

Semantics:

- producedValues contains the final frozen environment value created or changed by the completed
  step after coalescing its committed production writes;
- consumedKeys contains environment keys actually resolved through request templating, Postman
  variable precedence, guest pm.environment/pm.variables reads, or hook scope reads during that
  step. Reads of collection/global values remain in the synchronization journal but are not
  projected into this environment-only report type;
- unsetKeys contains net environment removals caused by the completed step;
- reusedValues contains values injected from a warm ledger skip;
- request-skipped reports have empty effects for ledger attribution even though successful
  pre-request scope mutations remain committed and visible to later steps;
- a ledger-skipped report records reuse, not production;
- placeholder materialization and kick initialization are journal causes but never production;
- writes committed before a later hook/script/request failure are included in that failed report;
- only touched values are frozen;
- step logs show produced/reused values and consumed/unset keys; consumed values are not retained;
- learned-ledger key sets derive from the effects;
- verbose Rundown JSON emits variableEffects and the final Rundown scopes, not envSnapshot.

Every journal operation carries scope, key, sequence, and cause: INITIALIZATION, MATERIALIZATION,
PRODUCTION, REUSE, or EXTERNAL. StepEnvironmentEffects is a projection of the ordered environment
operations plus the step-start value:

- repeated sets retain only the final value;
- set then unset of a previously absent key has no net produced/unset effect;
- set then unset of a previously present key yields unset only;
- unset then set yields produced only when the final value differs from the step-start value;
- same-value writes are synchronization events when needed but are not production;
- REUSE is mutually exclusive with normal execution effects; and
- reads are de-duplicated by key without retaining the value.

Consumers needing full typed state at a selected point must call RunProgress.snapshot from a hook.
The optional ExecutionTrace owns any requested automatic historical snapshots.

This eliminates inconsistent semantics in which an initial StepReport points at the live environment
while a final StepReport carries a structural snapshot.

## Variable-store and placeholder design

The environment store maintains a placeholder-candidate index. An entry enters the index when its
key or string template contains the Postman placeholder prefix. set, unset, key replacement, and
journal application update this index.

Before a step:

1. iterate only candidate entries;
2. resolve their stored templates using the existing precedence and cycle-detection rules;
3. compare the resolved key/value with the currently materialized value;
4. write only actual changes; and
5. retain template metadata for entries that must be reevaluated on later steps.

Static entries are never copied, associated into a new map, or written back.

The guest projection is explicit and separate from the host FrozenValue store. Guest-safe values
are null, strings, booleans, and numbers that round-trip losslessly through a finite IEEE-754
JavaScript Number. Lists, objects, oversized integers, non-lossless decimals, and typed POJOs remain
host-only, matching the existing scalar Postman-variable boundary. Projection membership is part of
the versioned state: safe-to-host-only emits a guest unset, host-only-to-safe emits a set, and
host-only changes emit no bridge value. Guest writes are scalar-safe and pass the numeric
normalization rule before host commit. Acknowledgements track projected revisions, so a stale guest
key cannot survive a type transition.

Dynamic and dependency-bearing templates are reevaluated on every step. This is explicit
major-version behavior: the store preserves the source template separately from its latest resolved
value. A direct host/script set replaces both the materialized value and template metadata.

Existing key-template alias behavior is preserved with bounded ownership. Each source template owns
at most one generated alias. When reevaluation produces a different alias, the store retires the
old generated alias only if no independent host/script write or other template owns it. It never
deletes a distinct user-authored key. Tests pin collisions, ownership transfer, dynamic values such
as $guid/timestamps, and repeated resolution so alias count cannot grow per step.

## Sandbox design

### Remove the legacy evaluator

PostmanSDK.evaluateJS, PostmanSDK.jsonStrToObj, JSEvaluator, and the second Graal Context are removed.
Normal execution uses only the real Postman sandbox and the immutable JsonCodec.

PostmanSDK ceases to be a public state container. Its remaining host-scope, assertion, directive,
and request/response responsibilities move into focused execution-scoped components owned by
KickExecution. The old class may be deleted once those responsibilities have moved; it must not
survive as another aggregate with an ambiguous lifecycle.

### Runtime resources

- one shared immutable Graal Engine per process;
- one strongly retained immutable boot Source per process;
- one lazily created PmSandbox Context per KickExecution;
- deterministic Context close at kick cleanup, including cancellation after a guest timeout;
- no Context sharing across kicks because guest globals must not bleed between collections.

The existing script timeout becomes a real SandboxBudget rather than only virtual event-loop time.
Each script phase has a wall-clock deadline, maximum scheduled-timer count, and maximum event-loop
turn count. Guest execution runs on a kick-owned virtual thread. Deadline expiry cancels the active
Graal evaluation with Context.close(true), marks that Context unusable, and reports a sandbox
timeout; recursive timers/event turns fail at their configured bound. A forced close is never
returned to a pool. Forked-JVM tests execute synchronous `while (true)` and recursively scheduled
timers and must terminate within a small fixed overshoot bound, so cleanup cannot depend on guest
cooperation.

### Stage 1: guest-output journals

Stage 1 retains current host-authoritative input seeding but changes sandbox output:

- each script receives current script-safe host scopes;
- guest mutations are recorded as set/unset journals;
- guest reads return a de-duplicated read set for environment-effect attribution;
- the guest returns journals rather than full post-execution scopes;
- the host validates scope and value normalization before changing any store;
- a successful script commits the journal atomically before the next host phase;
- a script error discards that script's staged Postman-scope, directive, assertion, and read journal,
  matching current scope behavior. Arbitrary bare JavaScript global mutations are not rolled back
  inside a surviving kick Context;
- host-side full-scope diffing is removed.

### Stage 2: guest-resident scopes

After Stage 1 is correct and benchmarked, script-safe scopes remain resident inside the kick's
PmSandbox Context.

Each kick starts a new epoch. Each scope has a monotonically increasing host revision, and the guest
tracks the last acknowledged host revision. The first script lazily boots the Context and receives
one full seed at the current revisions; host operations before first boot are already coalesced in
that seed and no historical journal is retained.

At every later phase barrier:

1. coalesce host operations since the guest acknowledgement by scope/key and send them as one
   all-scope batch with epoch and base revisions;
2. validate the epoch/base and apply the host batch to the guest resident base;
3. execute the script against a copy-on-write guest overlay, leaving the resident base unchanged;
4. return a prepared token containing guest mutations, read set, and the base host revisions;
5. validate every operation and fully build an immutable HostCommit containing one replacement
   HostScopeState root, revisions, effects, directives, and assertions without publishing it;
6. commit the prepared guest overlay by token;
7. publish HostCommit with one non-allocating, no-callback HostScopeState reference swap, advance
   acknowledgements, and expose its already-built directive/assertion/effect data; and
8. prune acknowledged host operations and the prepared token.

The host mutation buffer is bounded by distinct keys since the last acknowledgement, not operation
count. If no script ever boots, it holds no synchronization history. A script error discards its
overlay. A revision, normalization, prepared-token, or guest-commit failure invalidates/closes the
Context and fails the phase before host publication; execution never continues with divergent host
and guest state. The runtime is single-threaded per kick, so revision mismatch is a lifecycle defect,
not a conflict to merge.

There is no recoverable operation between guest commit and HostScopeState publication. If injected
test faulting or an unexpected non-fatal exception occurs in that narrow interval, finally publishes
the already-built HostCommit, invalidates the Context, and reports a kick-fatal protocol failure;
host and guest therefore converge before either is observed. Fatal VM errors are outside the
recoverable runtime contract.

Guest `pm.environment`, collectionVariables, and globals objects are stable phase-aware facades,
never references to an overlay. Their getters/mutators route to the currently active overlay token;
outside an active script phase they reject access. A facade or bound method retained in a bare JS
global may be used in a later active phase, but it operates only on that later phase's overlay and
cannot resurrect a discarded one.

Any failure that forcibly closes or invalidates the Context is kick-fatal regardless of ordinary
per-step halt policy. The current step appends one failed StepReport containing prior committed
effects but no rejected overlay, the sequencer sets StopReason.SANDBOX_RUNTIME_INVALIDATED, and no
later step in that kick runs. The host-only post-execution hook still receives terminal progress and
may finalize environment state; cleanup preserves the sandbox failure as primary. A following kick,
if the outer API's existing cross-kick policy permits it, starts a fresh KickExecution/Context and
cannot observe guest globals from the failed kick.

Required synchronization barriers:

- after pre-request JS, before request materialization and pre-hooks;
- after pre-hooks, before the next sandbox phase;
- after post-response JS, before response conversion and post-hooks; and
- after post-hooks when another sandbox phase can follow through polling or control flow.

No optimization may batch synchronization until the end of a whole step.

Required tests cover:

- JS mutation visible to the following host hook;
- hook mutation visible to the following JS phase;
- unsets in both directions;
- environment, collectionVariables, and globals isolation;
- numeric normalization;
- produced, consumed, unset, and reused ledger effects;
- script-error journal rollback;
- failed validation/commit and an injected post-guest-commit fault cannot leave authoritative host
  and guest scopes divergent;
- retained scope facades/bound mutators route to only the active overlay across successful and
  failed phases;
- scalar-to-host-only and host-only-to-scalar transitions unset/set guest keys without staleness;
- bridge payload size scales with changed/read keys rather than total scope size; and
- synchronous guest and recursive-timer budgets terminate and close the Context.

## Immutable JSON codecs

MoshiReVoman.addAdapters and shared per-call mutation are removed.

~~~kotlin
interface JsonCodec {
    fun <T> decode(value: FrozenValue, type: Type): T?
    fun <T> decode(value: FrozenValue, adapter: JsonAdapter<T>): T?
    fun <T> encode(value: T?, type: Type): FrozenValue
    fun <T> encode(value: T?, adapter: JsonAdapter<T>): FrozenValue
    fun withAdapters(config: AdapterConfig): JsonCodec
}

data class TypedJsonAdapter(
    val type: Type,
    val adapter: JsonAdapter<*>,
)

data class AdapterConfig(
    val typedAdapters: List<TypedJsonAdapter> = emptyList(),
    val factories: List<JsonAdapter.Factory> = emptyList(),
    val ignoredTypes: Set<Type> = emptySet(),
)
~~~

Behavior:

- common adapters, factories, and ignored types are registered once at execution construction;
- reified Kotlin and Class/Type Java extensions delegate to the type-bearing codec operations;
- getObj<T>(key) uses the kick environment's immutable codec while that kick is live;
- a one-off overload accepts an explicit JsonAdapter<T>;
- environment.codec.withAdapters returns an immutable reusable derived codec;
- AdapterConfig defensively copies its collections at construction. typedAdapters and factories
  preserve declared order and that order is semantic; ignoredTypes is an immutable set. Exact typed
  adapters precede its factories; the derived configuration precedes the fixed base registry; and
  a supplied one-off adapter always wins;
- FrozenValue.Null encodes from a null input and decodes to null only through a nullable/null-safe
  target contract; a non-null target receiving Null fails with a typed conversion error;
- withAdapters returns a new derived codec and never interns it globally. The caller reuses it when
  desired, and Moshi's normal adapter cache remains local to that immutable codec;
- repeated identical conversions do not append factories, mutate the base codec, or invalidate
  another codec's adapter cache; repeated withAdapters calls may create independent codecs but
  cannot grow a shared chain;
- JsonCodec is safe for concurrent reads after construction when every supplied adapter/factory is
  thread-safe. Built-ins satisfy this contract; caller-supplied implementations must do so or use
  the provided serialized-adapter wrapper. Defensive collection copies alone do not claim to make
  a stateful adapter thread-safe;
- no execution mutates a process-wide codec;
- final Rundown typed accessors require an explicit codec/type or JsonAdapter and therefore do not
  retain the kick codec or its adapter classloader.

## Logging and file output

### Sink capabilities

~~~kotlin
data class RunLogCapabilities(
    val narrationLevels: Set<LogLevel> = emptySet(),
    val stepMetadata: Boolean = false,
    val httpExchange: Boolean = false,
    val variableValues: Boolean = false,
    val topology: Boolean = false,
    val runbookEvents: Boolean = false,
    val outcome: Boolean = false,
    val performanceTimings: Boolean = false,
)

enum class SinkAttachmentPolicy { EXCLUSIVE, CONCURRENT_SAFE }
~~~

- RunLogSink exposes defensively copied immutable capabilities and an attachment policy;
- NoOp has no capabilities and is not installed into RunLogContext.
- RunLogDispatcher unions composite-sink capabilities.
- a narration lambda is evaluated only when the normal logger is enabled for that level or at least
  one real sink accepts it;
- cheap StepEvent metadata is constructed independently from expensive detail;
- HTTP wire rendering and variable-value freezing are provided through capability-specific lazy
  suppliers;
- a composite computes each requested detail at most once and fans out the result;
- a diagram sink requests topology and step metadata, not HTTP bodies;
- a FileRunLogSink with steps disabled does not cause body or variable rendering;
- runbook-only output requests runbookEvents/outcome without implicitly requesting step bodies;
- outcome and performance timing suppliers are independent of step-detail capabilities; and
- per-variable set/unset narration moves to DEBUG. TRACE is not added by this redesign.

Every built-in sink declares a deterministic, tested capability formula. NoOp requests none;
diagram output requests topology plus its configured step-metadata flag; Console requests only its
configured narration levels, steps, outcome, and performance flags; and FileRunLogSink derives
`stepMetadata = steps`, `httpExchange = steps && http`, `variableValues = steps && variables`,
`runbookEvents = runbook`, `outcome = outcome`, and `performanceTimings = performance`. Composite
sinks union defensively copied capabilities once at attachment time.

Borrowed sinks are EXCLUSIVE by default. ExecutionSession acquires an identity-based attachment
lease and rejects a concurrent attachment of the same sink, while sequential reuse remains valid;
the lease registry drops the identity on detach. A custom sink may opt into CONCURRENT_SAFE only by
making dispatch, flush(boundary), and close coordination thread-safe. Composite attachment is as
restrictive as its least-concurrent child. This prevents two independent sessions from interleaving
writes or flushes through a non-thread-safe caller-owned sink.

### File buffering

FileRunLogSink writes through a bounded BufferedWriter; it never accumulates a whole step in an
unbounded StringBuilder. The default STEP_BOUNDARY policy flushes after StepFinished,
RequestSkipped, LedgerSkipped, RunbookStepFinished, and the final outcome/footer, plus explicit
flush/owned close. CLOSE_ONLY flushes only when the caller explicitly invokes flush or close, or
when an owned sink is closed by the session; a borrowed CLOSE_ONLY sink may intentionally buffer
across executions until its owner flushes/closes it. Appending the footer itself does not force an
extra pre-footer flush. Per-line flushing is removed.

RunLogSink gains an explicit flush(boundary) operation. ExecutionSession invokes it on both owned
and borrowed sinks, but invokes close only on owned sinks.

The performance summary is written once at the footer. close does not read, concatenate, or rewrite
the completed log. latest.log repoint behavior remains.

Logging, observer, render, and close failures remain best-effort diagnostics and cannot replace the
primary execution failure.

## Polling design

~~~kotlin
data class PollingReport(
    val pollAttempts: Int,
    val totalDuration: Duration,
    val terminalResponse: HttpResponseSnapshot,
    val statusCounts: Map<Int, Int>,
    val recentResponses: List<HttpResponseSnapshot>,
)
~~~

This keeps the approved terminal-first shape but uses HttpResponseSnapshot instead of a live http4k
Response so PollingReport can be retained inside an immutable final Rundown without holding a stream
or connection. The snapshot preserves status, ordered headers, protocol metadata, and full body
bytes.

PollingConfig adds:

- recentResponsesLimit, default 0, validated non-negative;
- PollingAttemptObserver, default no-op;
- an optional requestTimeout whose effective value is capped by the remaining overall deadline;
  and
- maxAttempts, default 10,000, validated positive, so sub-millisecond/mock polling cannot consume
  unbounded CPU or request count before a long wall deadline.

~~~kotlin
data class PollingAttemptEvent(
    val attempt: Int,
    val startedAt: Duration,
    val duration: Duration,
    val outcome: PollingAttemptOutcome,
)

enum class PollingStage { REQUEST_BUILD, TRANSPORT, PREDICATE, OBSERVER, SLEEP, MAX_ATTEMPTS }

data class FailureDescriptor(val type: String, val message: String?)

sealed interface TransportOutcome {
    data class Received(val response: HttpResponseSnapshot) : TransportOutcome
    data class Failed(val failure: FailureDescriptor) : TransportOutcome
}

sealed interface PollingAttemptOutcome {
    data class ResponseReceived(
        val response: HttpResponseSnapshot,
        val terminal: Boolean,
    ) : PollingAttemptOutcome
    data class RequestBuildFailed(val failure: FailureDescriptor) : PollingAttemptOutcome
    data class TransportFailed(val failure: FailureDescriptor) : PollingAttemptOutcome
    data class PredicateFailed(
        val response: HttpResponseSnapshot,
        val failure: FailureDescriptor,
    ) : PollingAttemptOutcome
    data class DeadlineExceeded(
        val stage: PollingStage,
        val lastResponse: HttpResponseSnapshot?,
    ) : PollingAttemptOutcome
}

fun interface PollingAttemptObserver {
    fun onAttempt(event: PollingAttemptEvent)
}

internal interface PollingRequestHandle : AutoCloseable {
    fun awaitUntil(absoluteDeadlineNanos: Long): TransportOutcome
    fun cancel()
}

internal interface PollingHttpExecutor {
    fun start(request: Request, absoluteDeadlineNanos: Long): PollingRequestHandle
}
~~~

Rules:

- terminalResponse is always retained on success;
- every retained HttpResponseSnapshot is detached from transport and body-materialized as immutable
  ByteString before observation or storage; no streaming handle, mutable byte array, http4k Response,
  or pooled connection is retained by PollingReport;
- recentResponses contains intermediate responses only, ordered oldest to newest, and never
  duplicates terminalResponse. Terminal detection occurs before ring insertion;
- a fixed-size ring buffer retains at most recentResponsesLimit entries;
- no negative or sentinel value enables unbounded retention;
- statusCounts covers every received response;
- pollAttempts counts attempts beginning before request construction, including construction and
  transport failures; maxAttempts exhaustion is a bounded PollingFailure;
- exactly one observer event is emitted synchronously after every completed attempt, with a
  response, request-build failure, transport failure, predicate failure, or deadline outcome;
- an observer failure is recorded diagnostically and polling continues;
- observer time counts against the overall timeout;
- callers wanting asynchronous observation supply their own asynchronous sink;
- elapsed time and deadline use System.nanoTime in production and an injected monotonic clock plus
  sleeper in deterministic tests;
- when requestTimeout is absent, every request component uses remainingDeadline; when present, its
  effective timeout is min(configuredRequestTimeout, remainingDeadline);
- the controller starts one handle, waits only until the absolute deadline, calls cancel on timeout
  or abandonment, and closes the handle in finally. The production HTTP adapter applies the deadline
  to connection-pool acquisition, DNS/connect, TLS, response headers, and body reads without closing
  the shared client;
- sleep/park duration is clamped to remaining time and preserves sub-millisecond intervals;
- timeout/request failures carry counts, bounded history, and last response when present;
- request construction, completion predicates, and observers are synchronous user code. Their time
  counts toward the configured polling budget and overrun is detected immediately afterward, but
  ReVoman does not forcibly interrupt arbitrary user callbacks. The public hard deadline guarantee
  is explicitly transport-and-sleep bounded return, not arbitrary callback preemption;
- a completion predicate or observer returning after the deadline loses to DeadlineExceeded even if
  it reports terminal success; a predicate exception is PredicateFailed, while an observer exception
  remains diagnostic unless its elapsed time also crosses the deadline;
- a slow or hung transport is cancelled at the remaining deadline without closing the pooled
  client. Before the API promise lands, Change Set 5 must spike the actual Apache adapter and prove
  bounded caller return plus bounded leftover DNS/connect/TLS/body work. If the current adapter
  cannot meet this, polling uses an isolated cancellable async client/resolver with bounded workers
  rather than weakening the guarantee. Blocking-handler tests verify a small fixed overshoot bound.

Default memory is O(1) in attempt count.

## ExecutionInputs and V3 loading

### InputSource

Reusable configuration no longer stores already-open InputStream instances.

~~~kotlin
interface InputSource {
    val identity: InputIdentity
    fun open(): BufferedSource
}
~~~

Provided forms include:

- canonical filesystem path;
- classpath resource identity;
- immutable bytes keyed by content hash; and
- caller-supplied reopenable source with an explicit stable identity.

Each opened source is owned by the execution and read inside use/close semantics.

V3 directories use a TreeInputSource rather than pretending a directory is one byte stream. Its
execution snapshot captures the canonical root, ordered child manifest and metadata, all request
documents, and the required ancestor definition chain used for inherited authentication. Directory
enumeration and ancestor discovery happen once per execution. Raw V3 documents are parsed once;
context-dependent inherited-auth assembly may reuse those parsed definitions for each logical root.

### Execution snapshot

At outer execution start:

1. collect the sources referenced by every known kick/runbook step;
2. canonicalize identities;
3. deduplicate physical reads and parser artifacts for aliases while preserving every ordered
   logical source occurrence;
4. read mutable filesystem and supplied sources once;
5. parse each identity/parser-options pair once into an execution-ready object graph;
6. compute sourceHash and complete every known parser-options variant before dropping raw bytes;
7. construct a distinct ExecutableStep/StepId for every logical occurrence, including duplicate
   paths that intentionally execute twice;
8. retain each executable graph through the end of its owning kick because dynamic backward jumps
   make earlier last-use inference unsafe, then release it; and
9. clear remaining references in the top-level finally.

No file is watched, restatted, reopened, or reloaded during an execution. The next outer execution
builds a fresh snapshot and therefore observes filesystem changes between runs.

Classpath/JAR resources are deployment-immutable and may be loaded lazily inside the snapshot.
ClasspathResolver returns a resolver lease. A process-wide URI registry reference-counts only active
JAR filesystem leases, removes/closes a filesystem when the final internally owned lease closes,
and never closes an externally owned filesystem. It is transient concurrency infrastructure, not
an indefinitely growing cross-execution cache.

V3 loading receives an already resolved tree snapshot, parsed root/ancestor definitions, and parsed
request documents. The recursive walk passes parsed definitions down rather than rereading them for
ordering and again for recursion. Detection and load do not independently resolve the same
resource. Every owned BufferedSource and resolver lease is closed.

### Future cache tier

No cross-execution cache ships initially. If dual-mode benchmarks later show parsing is material, a
separate design may add a bounded JVM-local Caffeine cache keyed by:

- content hash;
- parser version;
- schema version; and
- relevant configuration version.

Each execution must still read and hash mutable inputs to preserve freshness. Distributed caching
requires separate evidence that network lookup and versioned deserialization beat local parsing
across multiple hosts.

## Execution lifecycle

For each step:

1. reset step capture and begin a fresh cause-tagged journal;
2. expose currentReport through RunProgress;
3. resolve only placeholder candidates and apply only changes;
4. execute pre-request JS if present;
5. commit the successful guest journal immediately;
6. materialize the hook-visible request once and record the scope revision;
7. run pre-hooks and journal their host mutations;
8. if a pre-hook changed a scope that can affect templating, rematerialize the final wire request
   exactly once; otherwise reuse the first materialization. With no pre-hooks, materialize only the
   final wire request;
9. dispatch HTTP through the shared pooled client;
10. synchronize host mutations into the guest;
11. execute post-response JS and commit its successful journal;
12. retain an immutable response/body snapshot and run post-hooks; parse JSON/typed txn objects only
   when a configured typed consumer actually requests them;
13. poll with bounded retention when configured;
14. project committed journal operations into StepEnvironmentEffects, including writes committed
   before a later failure while excluding request-skip production by the stated rule;
15. append the completed StepReport in O(1);
16. emit capability-gated events; and
17. flush configured file sinks at the step boundary.

This preserves current pre-hook semantics: an environment mutation made by a pre-hook affects the
actual dispatched request. It also bounds request construction to one materialization normally and
at most two when a pre-hook mutates relevant state.

After a kick:

1. run the post-execution hook against PostExecutionContext;
2. finalize immutable scopes and Rundown once;
3. append the Rundown to the multi-kick/runbook builder;
4. terminalize RunProgress onto immutable reports/scopes and sever all KickExecution references;
5. close the kick sandbox, invalidate editor capabilities, and release that kick's executable input
   graphs; and
6. carry only the finalized environment into the next KickExecution.

At outer execution termination:

1. preserve the original success/failure result;
2. write the single footer performance summary and outcome;
3. close any incompletely closed KickExecution and owned sinks, then perform policy-defined flushes
   and detach borrowed sinks without closing them;
4. clear input, outer Rundown-builder, carried-environment, and observer references; and
5. attach cleanup errors as suppressed diagnostics without replacing the original failure.

## Configuration philosophy

There is no embedded versus standalone execution profile.

Defaults are:

- lazy sandbox boot;
- no legacy evaluator;
- no automatic ExecutionTrace;
- no retained intermediate polling responses;
- step-boundary file flushing;
- no cross-execution parsed-input cache; and
- immutable codecs.

Narrow policy knobs are:

- polling recentResponsesLimit;
- polling observer, request timeout, and maxAttempts;
- SandboxBudget wall-clock/timer/event-loop limits;
- file flush policy STEP_BOUNDARY or CLOSE_ONLY;
- optional bounded/streaming ExecutionTrace.

Mode-specific switches must not appear unless a measured conflict cannot be solved by lazy or
bounded behavior.

## API removals and migrations

The major-version migration guide must include:

| Removed API/behavior | Replacement |
|---|---|
| PostmanSDK.evaluateJS / jsonStrToObj / JSEvaluator | Real PmSandbox plus JsonCodec |
| StepReport.pmEnvSnapshot | StepEnvironmentEffects; RunProgress.snapshot for full state |
| Rundown.mutableEnv and mutable peer scopes | Immutable VariableScopesSnapshot |
| Hooks receiving mutable Rundown | RunProgress plus explicit VariableScopeEditors |
| PostExeHook receiving mutable current Rundown | PostExecutionContext before finalization |
| PostExeHook accumulated list including current mutable Rundown | PostExecutionContext.priorRundowns plus progress.snapshot for current state |
| Picks, dynamic-variable generators, polling builders/predicates receiving mutable environment/Rundown | Purpose-specific read-only context plus callback-scoped editors only where mutation is authorized |
| Direct PostmanEnvironment construction, Map delegation, mutable copy/query/JSON helpers | VariableScopesView, VariableScopeEditor, VariableScopesSnapshot, and explicit JsonCodec helpers |
| Step reports/picks retaining Step.rawPMStep | StepDescriptor and lightweight request metadata |
| StepEnvVars | StepEnvironmentEffects |
| Deep Step keys and path-only ledger keys | execution-local StepId plus durable versioned LedgerStepKey |
| TxnInfo.txnObj, live HttpMessage, and embedded MoshiReVoman | immutable request/response snapshots and on-demand decoding with an explicit codec/adapter |
| PollingReport.responses | terminalResponse, statusCounts, bounded recentResponses |
| Reusable raw InputStream configuration | Reopenable InputSource or immutable bytes |
| Per-call lists that mutate environment Moshi | immutable run codec, explicit adapter, or derived codec |
| Unbounded in-memory execution history | explicit bounded/streaming ExecutionTrace |
| Per-line file flush | step-boundary or close-only flush |
| Duplicated performance summary | one footer summary |
| Custom RunLogSink without declared capabilities/flush/concurrency contract | RunLogSink with immutable capabilities, flush(boundary), and EXCLUSIVE or CONCURRENT_SAFE attachment; caller-supplied sinks remain borrowed |
| Unversioned Rundown JSON fields such as envSnapshot | major schemaVersion 2 with variableEffects and final scopes |

RunbookRundown remains an immutable ordered List<Rundown>; only the nested Rundown/report/value
schema changes. Before Change Set 2, the build generates a baseline public-surface inventory and
the migration guide maps every removed/changed Kotlin and Java symbol plus every serialized field.
Kotlin and Java consumer compile fixtures cover hooks, picks, dynamic generators, polling callbacks,
PostmanEnvironment replacements, TxnInfo access, custom sinks, ledgers, and RunbookRundown. Rundown
JSON emits `schemaVersion: 2`; the major release does not silently parse or emit the old shape.

### Normative serialized schemas

Before CS3/CS4/CS5 change persisted shapes, the repository checks in JSON Schema Draft 2020-12
contracts and golden round-trip fixtures for SIMPLE and VERBOSE Rundown, RunbookRundown, polling
success/failure, and ledger documents:

- every standalone root document has `schemaVersion: 2` regardless of verbosity;
- Rundowns nested in a RunbookRundown inherit the root version and do not repeat it;
- FrozenValue uses ordinary JSON null/string/boolean/array/object values and exact numeric lexemes;
  the ReVoman parser reads numbers as BigInteger/BigDecimal first and applies the Int/Long/
  BigInteger/Decimal normalization, never through Double;
- HttpRequestSnapshot/HttpResponseSnapshot encode ordered headers as arrays of name/value pairs and
  body bytes as base64 with explicit length/media-type metadata;
- statusCounts encodes as an ordered array of `{status, count}` objects rather than stringifying
  integer map keys;
- failures encode stable FailureDescriptor fields (type, phase/stage, message, optional causeType),
  never a Throwable object graph;
- StepEnvironmentEffects encodes its four named produced/consumed/unset/reused fields and never an
  envSnapshot;
- LedgerStepKey encodes logicalSourceId, sourceOccurrence, and itemPath; LedgerEntry separately
  encodes sourceHash, frozen produced values, and consumed keys; and
- PollingConfig schema and migration notes declare `recentResponsesLimit = 0` and
  `maxAttempts = 10_000` defaults and reject legacy retain-all sentinels.

Schema validation plus byte-for-byte canonical golden output gates the migrations. The generated
public-surface inventory includes the complete PostmanSDK container and nested public types, new
RunLogSink capabilities/flush/attachment methods, all PollingConfig defaults, and both Kotlin/Java
compile fixtures.

## Benchmark foundation

Benchmark correctness is Change Set 1 and lands before performance implementation.

Required harness repairs:

1. stop executing benchmarks from the shaded JMH uber jar; run generated benchmark classes through
   a JavaExec task whose runtime classpath contains the original dependency jars, preserving Graal
   multi-release metadata intact;
2. fail the Gradle task when any selected benchmark fork fails;
3. fail when a requested benchmark produces no result row;
4. install a benchmark-only logging configuration with hot-path narration disabled;
5. emit machine-readable results containing environment, JVM, commit, forks, confidence intervals,
   allocation, and memory metadata;
6. add a cold end-to-end runner that launches fresh JVMs; and
7. add a warm end-to-end runner plus focused JMH microbenchmarks.

### Measurement protocol

- Baseline is fixed at revision 83f3cd70. If it must change, this design is amended and reviewed;
  a run cannot choose an "approved implementation base" ad hoc.
- Change Set 1 produces a standalone benchmark-driver distribution at its own pinned harness commit.
  The driver launches target ReVoman distributions supplied on the classpath and contains versioned
  adapters for the baseline and major-release APIs, so revision 83f3cd70 itself is never patched.
  Only results with the same harness commit, workload-contract hash, and fixture hash are comparable;
  the adapter source hashes for both target versions are recorded.
- Result JSON uses a versioned `revoman-benchmark/v1` schema and records git tree, JDK distribution
  and full version, JVM flags, OS/kernel, CPU model/governor, memory, harness/adapter/fixture hashes,
  workload id/hash, fork/sample counts, raw observations or histogram, metric provider, and units.
- Baseline and candidate are built in separate clean checkouts with the same Gradle/JDK inputs and
  run in randomized alternating blocks on one controlled, idle, mains-powered host. Thermal or
  background-load-invalid blocks are discarded by a predeclared host-health rule, never by observed
  benchmark outcome; no other outlier deletion is allowed.
- Cold latency uses fresh JVMs. Warm end-to-end latency records per-execution samples after warmup;
  JMH microbenchmarks use SampleTime when percentile claims are made and AverageTime only for mean
  hot-path claims.
- The comparator reports candidate/baseline ratios and paired hierarchical-bootstrap 95% confidence
  intervals with 10,000 deterministic resamples: resample alternating host blocks first, then forks
  within blocks, then warm iterations within forks. This avoids treating correlated iterations as
  independent. JMH fork intervals remain secondary evidence; raw fork data enters the same release
  comparator.
- Allocation uses JFR allocation events for cold macros and JMH `-prof gc` bytes/op for warm
  benchmarks. Peak memory uses the controlled host's process peak-RSS provider. A comparison is
  valid only when baseline and candidate use the same providers.
- Retained-memory tests use at least five fresh forks at each of 1,000, 2,000, and 4,000 executions,
  drop returned Rundowns, force the same two-full-GC protocol, and measure live bytes plus weak
  references to ExecutionSession/KickExecution. The per-fork Theil-Sen slope enters the hierarchical
  bootstrap. All weak references must clear, and the upper 95% bound of retained-byte slope must be
  no more than 1 KiB per execution.
- For the 200, 400, 800, 1,600, and 3,200-step report workload, `allocatedBytes(n) / n` is computed
  after the same fixed harness setup. Across 800–3,200 steps, max divided by min must be no more than
  1.10; this is the normative "per-step allocation variance no greater than 10%" formula.
- Every timing workload is network-free and uses checked-in, versioned fixture bytes plus deterministic
  local handlers. Fixture parameters and content hashes are frozen before baseline capture.

### Workload matrix

| Workload | Cold | Warm | Primary owners/metrics |
|---|---:|---:|---|
| one no-script/no-log step | yes | yes | lifecycle startup, zero legacy Contexts |
| 3,200 fast linear steps | yes | yes | report accumulation and per-step allocation |
| jump-heavy loop with duplicate names/paths | yes | yes | StepId and zero execution-time target rescans |
| large mostly-static environment | yes | yes | candidate index, writes and allocation |
| no-op and one-mutation sandbox phases | yes | yes | Context boot and bridge bytes per mutation |
| large HTTP bodies with NoOp, diagram-only, and steps-disabled sinks | yes | yes | lazy suppliers, body conversion, allocations |
| no hook, read-only pre-hook, and mutating pre-hook requests | yes | yes | one-versus-two request materializations |
| default, one-off-adapter, and reused derived-codec conversions | yes | yes | codec construction/cache/factory-chain cost |
| step-heavy file output | yes | yes | write/flush counts and footer behavior |
| polling with large bodies, sub-ms intervals, and blocking transport | yes | yes | bounded retention, attempt/deadline limits |
| aliased V2/V3 inputs, duplicate logical paths, inherited V3 auth | yes | yes | read/parse/close counts and retained graphs |
| 4,000 discarded repeated executions | no | yes | retained-memory slope and weak-reference clearing |

### Cold release gates

Use at least 50 fresh-process samples on the controlled benchmark host.

- median end-to-end latency: no more than 5% regression;
- p95 end-to-end latency: no more than 10% regression;
- allocation per execution: no more than 5% regression;
- peak memory: no more than 5% regression.

### Warm release gates

Use at least five independent forks after warmup.

- median latency: no more than 3% regression;
- p95 latency: no more than 5% regression;
- allocation per execution: no more than 3% regression;
- retained memory must not grow proportionally with execution count.

### Targeted improvement rule

A targeted optimization must improve its relevant metric by at least 15% cold or 20% warm, unless
it satisfies an exact deterministic structural/asymptotic invariant.

For non-regression, the confidence interval's upper bound must remain inside the allowed regression
limit. Before claiming an improvement, the confidence interval's lower bound must exceed the
improvement target.

Timing gates run only on a controlled benchmark host. Ordinary CI enforces deterministic
correctness and structural invariants.

## Deterministic verification

Ordinary CI must prove:

1. normal revUp creates zero legacy JSEvaluator contexts;
2. an instrumented report store observes one append and bounded current-report assignments per
   step, with no prefix-copy operation; the controlled benchmark separately confirms approximately
   linear scaling from 200 through 3,200 steps;
3. a counting variable store proves that per-step resolution visits only placeholder candidates and
   writes only changed entries;
4. polling retains no more than terminalResponse plus recentResponsesLimit;
5. NoOp and disabled log lambdas are never evaluated;
6. file flush count scales with step/runbook boundaries, not emitted lines;
7. identical codec configurations do not grow a shared factory chain;
8. each execution input is read/parsed once per parser key;
9. every opened input source is closed;
10. JS mutations are visible to following hooks;
11. hook mutations are visible to following JS phases;
12. unsets propagate in both directions;
13. all three variable scopes remain isolated;
14. numeric normalization remains stable;
15. produced, consumed, unset, and reused ledger effects remain correct;
16. script-error journals are discarded;
17. final Rundown and explicit snapshots cannot be mutated;
18. ExecutionTrace is off and allocation-free by default;
19. one ExecutionSession exists per outer public call and one KickExecution per kick; list/runbook,
    duplicate-path, and reentrant-public-call tests prove scope carry/isolation and cleanup;
20. retained RunProgress becomes terminal after its kick, has null currentReport, and does not stop
    a weakly referenced KickExecution from being collected; editors reject escaped/cross-thread use,
    and post-execution environment writes appear in the finalized Rundown and next kick;
21. duplicate configured source occurrences receive distinct StepIds/LedgerStepKeys, loop iterations
    reuse StepId, jump resolution preserves first-match behavior, an instrumented resolver records
    zero execution-time step-list scans, and hot maps never invoke deep Step.hashCode;
22. disabled HTTP/value/timing suppliers remain unevaluated for NoOp, diagram-only, runbook-only,
    and steps-disabled sinks;
23. exactly one footer summary is appended, close performs no whole-file read/rewrite, buffer size is
    bounded, and caller-owned sink reuse across executions never closes the sink;
24. after the one permitted initial full seed, sandbox payload counters scale with changed/read keys,
    not full-scope size; a separate counter records exactly one initial seed per boot/epoch;
25. synchronous infinite JS and recursive timers are cancelled in forked JVMs within the budget
    overshoot bound and the Context closes;
26. polling transport cancellation respects an absolute deadline, does not close the shared client,
    preserves observer ordering, and stays within the deadline overshoot bound under a blocking
    handler and fake-clock/fake-sleeper tests;
27. StepEnvironmentEffects coalescing/cause/read rules cover set-unset permutations, same-value
    writes, placeholder materialization, request skips, failures, reuse, and all read paths;
28. every physical input alias is read once per parser key while every logical duplicate still
    executes; V3 manifests/ancestor definitions are captured once; input/report reachability tests
    prove executable graphs and JAR leases are releasable after kick/session close; and
29. immutable report DTOs contain no Item, Step.rawPMStep, live HttpMessage, txnObj, mutable
    collection, KickExecution codec, Context, or input handle;
30. request construction counters prove one materialization for no/read-only hooks and no more than
    two for a relevant pre-hook mutation, with the dispatched request containing the mutation; and
31. typed conversion counters prove zero JSON decodes when no configured consumer requests a typed
    value and exactly one cached decode per requested type/body/configuration.

Correctness gates for each change set:

- focused unit and integration tests for the affected invariants;
- ./gradlew test integrationTest;
- ./gradlew build;
- ./gradlew qodanaScan before pushing, following DEVELOPMENT.md.

External-org Core integration tests remain opt-in and are run where credentials are available.

## Delivery decomposition

This umbrella design authorizes six planned and reviewed change sets. A change set is a workstream,
not permission for one oversized PR: the ordered sub-changes below land independently. Parallel
development does not change merge prerequisites. The complete landing DAG is:

- CS1 lands first;
- CS2a depends on CS1;
- CS2b, CS2c, and CS2d each depend on CS2a and may then land in parallel;
- CS3 depends on CS2a + CS2b + CS2c;
- CS4 depends on CS3 + CS2b + CS2c;
- CS5 depends on CS2a + CS2b + CS2c; and
- CS6 depends on CS2a + CS2b.

The workstreams must not be collapsed into one implementation PR.

### Change Set 1 — Benchmark foundation

- repair JMH packaging/failure/logging;
- implement cold/warm harnesses;
- capture the fixed baseline at 83f3cd70.

### Change Set 2 — Runtime lifecycle and observability

- **2a lifecycle:** introduce ExecutionSession/KickExecution, route every public/list/runbook/reentrant
  path through the explicit boundary, remove the legacy evaluator, retain boot Source, establish
  focused component seams around the still-transitional PostmanSDK, and prove scope carry,
  capability lifetime, and cleanup;
- **2b identity/report seam:** introduce occurrence-safe StepId and LedgerStepKey, precompute the
  jump-target index, split StepDescriptor from ExecutableStep, and define immutable HTTP snapshot
  DTOs shared by later step/polling reports;
- **2c codec:** introduce immutable JsonCodec and the FrozenValue conversion seam without yet
  changing final report APIs; and
- **2d logging:** introduce sink capabilities/lazy details, bounded writer buffering, explicit
  owned/borrowed handles, and one footer.

### Change Set 3 — Environment and sandbox journals

- land the host cause-tagged mutation/read journal foundation first;
- add placeholder-candidate indexing, bounded generated-alias ownership, and changed-only writeback;
- land Stage 1 guest-output/read journals and prove phase-barrier correctness;
- add enforceable SandboxBudget cancellation and event/timer bounds; and
- then land Stage 2 guest-resident versioned scopes as a separate benchmarked sub-change.

### Change Set 4 — Progress and reporting

- introduce kick-local RunProgress, RundownSnapshot, frozen final Rundown, and callback-scoped
  editors on the journal foundation from Change Set 3;
- replace report reconstruction with O(1) builders;
- remove pmEnvSnapshot and project journal operations into StepEnvironmentEffects;
- replace live TxnInfo/HttpMessage/txnObj retention with immutable DTOs and on-demand typed
  conversion; and
- migrate hooks and post-execution hooks; after CS3/CS4 have moved every remaining responsibility,
  delete the transitional PostmanSDK aggregate.

### Change Set 5 — Bounded polling

- introduce terminal-first reports and bounded history;
- add observers, maxAttempts, monotonic deadlines, and absolute-deadline transport cancellation;
- migrate JSON and documentation.

### Change Set 6 — Execution inputs and V3 loading

- add InputSource and ExecutionInputs;
- migrate stream APIs;
- deduplicate physical reads while preserving logical occurrences;
- add V3 tree snapshots and remove duplicate V3 reads;
- replace the unbounded JAR filesystem map with active reference-counted resolver leases;
- close every source/lease and prove parsed graph release; and
- benchmark before considering any cross-execution cache.

After this written design is approved, the writing-plans workflow starts with Change Set 1. Each
later change set receives its own implementation plan and review gate while referencing this
umbrella design.

## Rejected alternatives

### Independent surgical patches only

This would land quickly but leave lifecycle ownership distributed across ReVoman, PostmanSDK,
PmSandbox, RunLogContext, and loaders. It also makes versioned scope journals and deterministic
cleanup harder to reason about.

### Full event-sourced runtime rewrite

A single journal for scopes, reports, logs, polling, and all control flow is theoretically coherent
but expands beyond the measured bottlenecks and creates excessive correctness and rollout risk.

### Shared or pooled Graal Context

Context pooling risks guest-global and module state leakage. Engine and Source sharing provide the
safe reuse tier. Context pooling requires a separate isolation design and proof.

### Global standalone/embedded modes

The chosen lazy and bounded architecture serves both modes without divergent code paths. A global
mode would multiply configuration and test combinations without evidence that it is needed.

### Unbounded diagnostics

Unbounded polling response history, automatic full environment history, and per-line file flushing
are intentionally unavailable. Streaming or bounded opt-in diagnostics replace them.

### Cross-execution or distributed input cache in the first release

Fresh execution snapshots solve duplicate reads without stale-data or operations complexity. A
bounded content-addressed JVM cache is a later measured tier; distributed caching requires separate
multi-host evidence.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Major hook API migration affects consumers | Publish a mechanical migration table and Kotlin/Java examples |
| Frozen final scopes remove direct POJO references | Provide explicit codec-based typed decoding |
| Guest-resident scopes drift from host | Versioned journals and mandatory phase-barrier tests |
| Template reevaluation changes dynamic-value behavior | Document it as major-version semantics and pin repeated-resolution tests |
| Step effects omit full historical state | Explicit snapshots and optional bounded/streaming ExecutionTrace |
| Observer blocks polling | Synchronous contract is explicit; observer time counts against deadline |
| Input snapshot retains large parsed graphs | Conservative kick-end release and top-level reference clearing |
| Benchmark noise creates false regressions | Controlled host, sufficient samples/forks, confidence-interval gates |
| Capability aggregation computes unused details | Union capabilities once and test supplier evaluation counts |
| Cleanup hides root failures | Primary failure wins; cleanup failures are suppressed diagnostics |

## Documentation requirements

Update:

- development documentation for cold/warm benchmark commands and controlled-host policy;
- mutable-environment documentation to replace pmEnvSnapshot with StepEnvironmentEffects,
  RunProgress.snapshot, and final scopes;
- polling documentation for terminal-first bounded reports and observers;
- hook and pick documentation for RunProgress and scope editors;
- input documentation for InputSource freshness boundaries;
- logging documentation for capability gating and boundary flush behavior;
- JSON documentation for immutable codecs and derived-codec usage;
- normative v2 JSON Schemas and canonical golden fixtures for every verbosity, polling, and ledger;
- the major-version migration guide with Kotlin and Java examples.

## Success criteria

The redesign is complete only when:

- all six change sets have their deterministic gates and dual-mode measurements;
- every audited finding has a code owner and a test or benchmark proving its resolution;
- public migration documentation exists for every removed API;
- full non-Core tests, build, and Qodana are green;
- controlled-host cold and warm gates pass;
- the final state contains no legacy evaluator, mutable Rundown scopes, pmEnvSnapshot, unbounded
  polling history, per-line file flush, mutable shared codec registration, or repeated per-execution
  input reads; and
- standalone execution has no meaningful new startup or memory overhead while long-lived hosts
  retain no execution-scoped state across runs.

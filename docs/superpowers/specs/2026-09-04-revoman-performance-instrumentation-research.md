# ReVoman Performance Instrumentation: Research and Accepted Design

**Date:** 2026-09-04

**Status:** Accepted for implementation. Polling-attempt instrumentation is deferred from v1.

## Accepted v1 design

ReVoman will use three disabled-by-default custom JFR duration events hidden behind one internal
object with three inline wrappers: a generic semantic operation, a kick, and a step. The event
schema is fixed and low-cardinality. Semantic call sites supply only stable operation kinds;
kick- and step-specific wrappers derive bounded counts and domain outcomes after the operation
returns. There is no public listener, annotation layer, correlation-ID allocation, or telemetry
dependency.

The generic operations cover meaningful run, collection, and step phases. Every operation kind has
an explicit expected frequency (`RUN`, `COLLECTION`, `STEP`, or `RUNBOOK_STEP`). The kick and step
events add the scale and outcome dimensions needed to interpret those phases: source and step
counts, environment size, script and hook counts, request and response sizes, HTTP status, stop
reason, skips, returned domain failures, thrown failures, and cancellation. Stack capture is off.
User data such as paths, names, URLs, bodies, environment keys or values, exception messages, and
generated IDs is excluded.

The disabled path first checks whether Flight Recorder is initialized and then whether the relevant
event type is enabled. Event construction and metadata reads occur only on the enabled side. This
keeps ordinary library execution to a predictable branch at each meaningful boundary and avoids
initializing JFR solely because ReVoman was called. Only that guard is inlined. The enabled event
lifecycle sits behind a cold helper so its try/finally and metadata code is not duplicated across
the execution engine; the bridge lambda is constructed only after the event is enabled.

Polling remains represented by the existing coarse `POLLING` step phase in v1. There are no
attempt-level events, attempt counters, or polling-specific scale dimensions. That split can be
added later if a profile shows that polling deserves its own study.

### Native benchmark integration

The existing `kotlinx-benchmark` executable is a JMH 1.37 runner, so profiling is integrated with
JMH's native `-prof` mechanism
([kotlinx-benchmark task overview](https://github.com/Kotlin/kotlinx-benchmark/blob/v0.5.0/docs/tasks-overview.md),
[JMH 1.37 async-profiler integration](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/AsyncProfiler.java)).
Each profile fork loads two built-in JMH profilers:

1. `async` records CPU, allocation, lock, or wall samples as JFR.
2. `jfr` records the three ReVoman semantic event types with a generated minimal `.jfc` file.

Both profilers observe the same fork and iteration, but write separate recordings. A follow-up
spike found this more reliable than passing async-profiler's raw `jfrsync` option through JMH:
under the repository's JMH 1.37 and async-profiler 4.5 combination, the synchronized file retained
semantic events but lost all async-profiler samples. The two-profiler form retained both sides.

The reporting module joins the recordings by absolute event time and Java thread ID. A sample is
assigned to the deepest enclosing same-thread semantic interval. Samples that occur during JVM
startup, profiler attachment, native-only work, another thread, or outside every interval remain
visible in an `UNATTRIBUTED` row rather than being guessed into a phase. Direct child interval
unions produce self-duration without double-subtracting overlaps. Every profiler pair must contain
both semantic intervals and native samples or the run is rejected.

Attribution is emitted through the existing Kotlin DataFrame reporting path as one CSV per
benchmark method and profiler mode, alongside the native sample JFR, semantic JFR, and conventional
JFR summary. The manifest records all four artifacts. CPU weights are sample counts, allocation
weights are bytes, and lock and wall weights are nanoseconds. Wall sampling pins a 10 ms interval.
async-profiler batches idle wall samples into a `profiler.WallClockSample`; the reader expands each
batch across its recorded time span before attribution, preserving the lower-overhead profiler
mode without treating the whole batch as one point
([async-profiler wall batching design](https://github.com/async-profiler/async-profiler/issues/1007)).

## Question

What modern Kotlin/JVM instrumentation mechanisms could give ReVoman low-overhead semantic
phase boundaries that can be correlated with profiler CPU, allocation, lock, and wall-time data?

The mechanism must help distinguish work performed once per `revUp` call, once per collection,
and once per step execution. It must also preserve the
outcome of failure, timeout, skip, cancellation, and cleanup paths. The default path must remain
close to free, and the solution must not expose a broad public listener API.

This note preserves the ecosystem comparison that led to the accepted design and records the
results of the integration spike.

## Repository baseline

The current checkout targets JDK 25 and Kotlin 2.4.20-RC3. It has no OpenTelemetry, Micrometer, or
`kotlinx-coroutines` dependency ([version catalog](../../../gradle/libs.versions.toml)). JFR is
already used by `benchmark-reporting` to read allocation recordings.

ReVoman already has two useful but different diagnostic mechanisms:

1. The internal `timed` wrapper measures eight step operations with `measureTimedValue` and stores
   their elapsed durations in each `StepReport`: pre-request script, request unmarshalling,
   pre-step hook, HTTP request, post-response script, response unmarshalling, post-step hook, and
   polling
   ([`ExeUtils.kt`](../../../revoman/src/main/kotlin/com/salesforce/revoman/internal/exe/ExeUtils.kt),
   [`ReVoman.kt`](../../../revoman/src/main/kotlin/com/salesforce/revoman/ReVoman.kt)). These are
   wall-time result fields, not markers in a profiler recording. They do not cover collection
   loading, flattening and selection, environment setup or rebuild, SDK and sandbox setup,
   progress synchronization, final report capture, ledger construction, multi-kick hooks, or
   cleanup.
2. `RunLogSink`, `StepEvent`, and `RunLogContext` provide rich human diagnostics and lifecycle
   events. The sink is public, its events can contain large request, response, and environment
   payloads, and its purpose is consumer-facing narration
   ([`RunLogSink.kt`](../../../revoman/src/main/kotlin/com/salesforce/revoman/output/log/RunLogSink.kt),
   [`StepEvent.kt`](../../../revoman/src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt),
   [`RunLogContext.kt`](../../../revoman/src/main/kotlin/com/salesforce/revoman/internal/log/RunLogContext.kt)).
   Expanding this contract into a profiler listener would mix two responsibilities and enlarge a
   hot-path public API.

The scorecard currently creates separate async-profiler JFR files for `cpu`, `alloc`, and `lock`
with an agent option of the form `start,event=...,file=...,loglevel=warn`. It does not currently
enable async-profiler's `jfrsync` option
([`ScorecardCommands.kt`](../../../benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ScorecardCommands.kt)).

## Evaluation criteria

The relevant criteria are:

- Disabled cost: branches, calls, allocations, clocks, thread-local lookups, and metadata work when
  no recording is active.
- Enabled cost: event allocation, timestamping, stack walking, buffering, handler fan-out, and
  exporter work.
- Profiler correlation: whether semantic intervals and async-profiler samples can share one JFR
  clock and recording.
- Lifecycle fidelity: nested run, collection, step, and attempt scopes, including exceptional and
  domain-level outcomes.
- Schema discipline: stable low-cardinality phase names and numeric scale drivers without request,
  response, environment, or secret values.
- Encapsulation: an internal deep module with a small call-site vocabulary, no annotations across
  every method, and no general public listener surface.
- Dependency and compatibility cost for the published library.

## Option matrix

| Mechanism | Disabled path | Enabled data and correlation | Main tradeoff for ReVoman |
| --- | --- | --- | --- |
| Compile-time custom JFR `Event` subclasses | JDK says a disabled event costs at most an allocation, and the JIT may eliminate even that. An explicit outer enabled check can avoid constructing the event, but its cost still needs measurement. | Native start time, duration, thread, typed fields, optional stack trace, thresholds, and direct placement beside profiler samples when the recording is configured correctly. JDK 25 contextual fields can annotate same-thread events occurring inside the interval. | Strong profiler-native schema with no third-party dependency. Fixed JVM field restrictions and active event cost must be designed and measured. |
| JFR `EventFactory` | Each emitted event is created through a runtime factory and populated through indexed `Object` values. | Same JFR recording model, with a schema that can be created and registered at runtime. | JDK explicitly prefers compile-time event classes when the layout is known because the JVM can optimize them. Dynamic schema is flexibility ReVoman does not currently require. |
| JFR `RecordingStream` | No cost if absent. Starting a stream creates an active recording and consumer lifecycle. | Live callbacks for current-JVM events, asynchronous processing, and optional dumps. | This is a consumer of JFR events, not the hot-path emission seam. A default in-process stream would add machinery that offline scorecard analysis does not need. |
| OpenTelemetry spans | The API supplies a no-op implementation, but callers still pay for any attributes or objects computed before `Span.isRecording()` checks. Builder and scope calls remain at call sites. | Excellent trace nesting, context propagation, status, exceptions, and export. It does not by itself put semantic intervals into the same JFR recording as CPU, allocation, and lock samples. | Good when vendor-neutral telemetry export is the primary goal. It adds an API dependency and needs a separate JFR bridge for the stated profiler-correlation goal. |
| Micrometer Observation | A no-op registry fast-returns a singleton and skips a lazily supplied context. Wrapper calls remain. Active observations create mutable context and dispatch lifecycle handlers. | One instrumentation can feed timers, traces, logs, and custom handlers, with start, stop, error, and event lifecycle. No built-in same-recording JFR correlation. | Flexible observability pipeline, but broader than a profiler marker module. It adds dependencies and handler, convention, filter, and cardinality concepts. |
| async-profiler 4.5 `Span` API | Officially static and allocation-free, with calls becoming no-ops when the profiler is absent. Caller-created tag strings can still allocate. | Emits a current-thread `profiler.Span` interval in exactly the profiler sample clock. `endIfProfiled` drops intervals containing no sample. | The narrowest direct profiler marker. It has one string tag, no hierarchy or typed fields, and no built-in outcome model. It introduces an async-profiler API dependency into production code. |
| Small internal no-op recorder | Can be an inlined identity or enabled branch with no metadata construction on the disabled side. Exact behavior is controlled by ReVoman and must be benchmarked. | No format by itself. A backend can emit static JFR events or adapt to another system. | Provides the deep-module boundary and test seam, but does not replace the choice of recording format. A poorly shaped token or context API could still allocate once per step. |

The last row is compositional rather than mutually exclusive. An internal recorder could hide one of
the concrete backends from semantic call sites.

## JFR findings

### Static event classes

The JDK's `Event` API has been available since JDK 9. An event can be allocated, begun, ended, and
committed. If `end()` is omitted, `commit()` ends a begun event. A recorded event exposes start
time, end time, nanosecond duration, event type, commit thread, and an optional commit-time stack
trace. This directly models a semantic duration without scattered `System.nanoTime` calls
([JDK 25 `Event`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Event.html),
[JDK 25 `RecordedEvent`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedEvent.html)).

The default matters: a custom event is enabled by default whenever a recording uses that setting.
`@Enabled(false)` makes it opt-in. Oracle documents that a disabled event then has at most the cost
of its allocation, or no cost when the JIT eliminates the allocation
([JDK 25 `Enabled`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Enabled.html)).
If ReVoman requires instrumentation to stay off during unrelated JFR recordings, an explicit
disabled-by-default setting is therefore a design requirement, not an optional optimization.

`EventType.isEnabled()` reports whether the event is enabled in at least one running recording. It
could support a branch before event construction. The standard JFR idiom instead constructs the
event and relies on the disabled-event optimization. Which shape is cheaper in ReVoman's real hot
loops is an empirical question
([JDK 25 `EventType`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/EventType.html)).

`shouldCommit()` is distinct from `isEnabled()`: after timing has begun and ended, it also applies
the configured duration threshold. It should guard expensive late metadata, not cheap fields needed
to identify the event. Thresholds can suppress short events without changing call sites
([JDK 25 `Event`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Event.html),
[JDK 25 `Threshold`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Threshold.html)).

JFR event fields are intentionally narrow. Supported values are JVM primitives, `String`,
`Thread`, and `Class`. Arrays, enums, and arbitrary objects are silently omitted. Static and
transient fields are not recorded. This favors numeric counts, stable string or integer codes, and
an exception class instead of exception objects, stack strings, request bodies, or environment
maps. `@Name` should supply a stable qualified identifier because the JDK warns that production
consumers and configuration files depend on it
([JDK 25 `Event`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Event.html),
[JDK 25 `Name`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Name.html)).

JFR stack traces are configurable and default on. The trace represents the `commit()` site, not
all CPU owners during the interval. `@StackTrace(false)` avoids that default capture when the same
recording already contains async-profiler samples. A recording can still override the setting when
a commit-site trace is useful
([JDK 25 `StackTrace`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/StackTrace.html),
[JDK 25 `EventSettings`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/EventSettings.html)).

JDK 25 adds `@Contextual` for an event field whose value applies to all events on the same thread
between the semantic event's start and end. Oracle's example shows a trace ID and order ID attached
to a `jdk.JavaMonitorEnter` event, which can directly expose which semantic operation owned a lock
stall. `@Relational` instead defines a join relationship between fields across event types. A field
can use both. Contextual data is thread-bound and makes parsers track active context, so Oracle says
to use it sparingly
([JDK 25 `Contextual`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Contextual.html),
[JDK 25 `Relational`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Relational.html)).

This is a meaningful JDK 25 alternative to performing every join by timestamp after recording. It
does not solve child-thread or coroutine migration, and compatibility with each async-profiler event
type under `jfrsync` needs a recording-level spike. The official example demonstrates a JDK lock
event, not every async-profiler CPU, allocation, lock, or wall event.

### `EventFactory`

`EventFactory` has also existed since JDK 9. It defines and registers event layouts at runtime,
creates events with `newEvent()`, and populates fields by descriptor index through
`Event.set(int, Object)`. The JDK strongly advises compile-time event classes when the layout is
known so the JVM can optimize the code and possibly remove inactive instrumentation entirely
([JDK 25 `EventFactory`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/EventFactory.html)).

ReVoman's lifecycle and scale-driver schema is knowable at compile time. Runtime event factories
would become relevant only if consumers could introduce new event layouts, which would conflict
with the desired small internal surface. Indexed `Object` writes also make primitive boxing and
field-index mistakes concerns that static fields avoid.

### Labels, categories, and streaming

JFR annotations such as `@Label`, `@Description`, `@Category`, and unit annotations enrich event
metadata for JDK Mission Control and other consumers. `@Name` is the stable machine contract;
categories are presentation metadata and can change without breaking settings
([JDK 25 `Name`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Name.html),
[JDK 25 `Category`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Category.html)).

`RecordingStream`, available since JDK 14, can enable events from the current JVM and process them
synchronously or asynchronously through callbacks. It is useful for an optional live diagnostic
tool or test, but it is not needed for emission or offline DataFrame reporting. Running it by
default would add a recording, callbacks, and lifecycle management inside the library
([JDK 25 `RecordingStream`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/consumer/RecordingStream.html)).

### Correlation with async-profiler

An async-profiler file in JFR format does not automatically include application custom JFR events.
The profiler's `--jfrsync CONFIG` option starts JDK Flight Recorder with the profiler so one output
contains regular JFR events while execution samples come from async-profiler. `CONFIG` may be a
named profile, a `.jfc` file, or an event-name list beginning with `+`
([async-profiler profiler options](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilerOptions.md)).
The implementation creates a `Recording`, enables every name in a `+` list, sends both streams to
the same destination, and disables duplicate built-in sampling events when a named JFR profile is
used
([async-profiler `JfrSync.java`](https://github.com/async-profiler/async-profiler/blob/master/src/helper/one/profiler/JfrSync.java)).

async-profiler supports CPU, allocation, lock, and wall-clock modes. Wall-clock samples include
threads whether they are running, sleeping, or blocked; lock mode measures wait time; multiple
event modes can share a JFR output. ReVoman can retain separate diagnostic recordings if that
produces cleaner evidence. The relevant requirement is that each recording used for semantic
correlation must also enable the custom event names
([async-profiler profiling modes](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md)).

### async-profiler's native span API

async-profiler 4.5 adds a separate Java `one.profiler.Span` API for latency-sensitive intervals. Its
official integration guide describes it as static and allocation-free, with no-op calls when the
profiler is not running. `Span.start()` returns a timestamp, while `end()` emits a `profiler.Span`
event with duration, event thread, and one deduplicated string tag. `endIfProfiled()` emits only if
the current thread received at least one sample during the interval. The timestamp uses exactly the
profiler clock, and the API connects automatically when the profiler is loaded
([async-profiler 4.5 integration guide](https://github.com/async-profiler/async-profiler/blob/v4.5/docs/IntegratingAsyncProfiler.md),
[async-profiler 4.5 `Span.java`](https://github.com/async-profiler/async-profiler/blob/v4.5/src/api/one/profiler/Span.java)).

This is a modern direct answer for a profiler-only marker, so it belongs in a ReVoman spike. It is
also materially narrower than the requested model: one current-thread interval plus one tag has no
typed scale fields, parent IDs, explicit nesting, or distinct success, failure, timeout, skip, and
cleanup outcomes. Encoding all of that into tags would add tag construction and weaken the schema.
It also couples the published library to async-profiler's Java API rather than the JDK. A small
internal recorder could hide that coupling, but the backend's narrow data model would remain.

#### Temporary integration spike

A disposable process was run with a repository-compatible local platform, Corretto JDK 25.0.4.1
and its bundled async-profiler 4.5. A disabled-by-default event named `research.Phase` was emitted
once.

| Agent configuration | `event.isEnabled()` | Events in output |
| --- | ---: | --- |
| Existing scorecard shape: `start,event=cpu,file=...` | `false` | CPU samples were present; `research.Phase` was absent. |
| `start,event=cpu,jfrsync=+research.Phase,file=...` | `true` | One `research.Phase` event was present with its duration and outcome field. |

This was an inclusion test, not an overhead benchmark. The workload was intentionally too short to
draw sampling or performance conclusions. It confirms that a future JFR design also needs an
explicit profiler-launch and reporting integration change. Merely adding event classes to the
library would leave the current scorecard recordings semantically unlabelled.

A second spike exercised the real executable `kotlinx-benchmark` JAR through JMH rather than a
standalone JVM. With `jfrsync`, the resulting file contained the ReVoman events but zero
async-profiler execution samples and reported data loss. A control run without `jfrsync` contained
samples. Changing warmup, profiler order, and JFC shape did not repair it. Running JMH's built-in
`async` and `jfr` profilers together in one fork produced both expected outputs: the sample
recording contained async-profiler events and the semantic recording contained nested ReVoman
operation, step, and kick intervals. This is why the accepted design uses paired recordings rather
than a synchronized single file.

## OpenTelemetry spans

The current OpenTelemetry Java page documents API version 1.65.0. It describes the API artifact as
appropriate for direct library dependencies, with zero transitive dependencies, Java 8+ support,
and a no-op implementation when an application does not install an SDK. Libraries should depend
only on the API and leave SDK and exporter configuration to applications
([OpenTelemetry Java API](https://opentelemetry.io/docs/languages/java/api/)).

Spans naturally represent nested operations and provide explicit start/end, status, exception,
events, parent context, and trace identifiers. `Span.isRecording()` tells instrumentation when
attributes and events would be discarded and should guard expensive metadata. The no-op API itself
is intended to have no performance impact, but the official Java documentation warns that callers
still pay for attribute values and other telemetry data they compute or allocate before the no-op
receives them
([OpenTelemetry trace API specification](https://opentelemetry.io/docs/specs/otel/trace/api/),
[OpenTelemetry Java no-op documentation](https://opentelemetry.io/docs/languages/java/api/#no-op-implementation)).

An SDK sampler's drop decision is not the same as using the API's no-op implementation. The SDK
sampling flow receives a trace ID, span name, kind, attributes, links, and parent context, so caller
and identifier work can occur before the decision. Disabled-path measurements must distinguish
these two configurations
([OpenTelemetry trace SDK sampling](https://opentelemetry.io/docs/specs/otel/trace/sdk/#sampling)).

OpenTelemetry context defaults to thread-local storage. Its Java documentation lists a Kotlin
extension artifact for propagating context into coroutines. This matters if ReVoman later suspends
or moves work between threads, but the current checkout has no coroutine dependency
([OpenTelemetry Java context documentation](https://opentelemetry.io/docs/languages/java/api/#context)).

There is also a newer Kotlin Multiplatform OpenTelemetry API. Its current documentation requires
Kotlin 2.0+ and JDK 11+ on JVM, but says most symbols require `ExperimentalApi` opt-in and may break
without notice. ReVoman satisfies the platform floor, but an experimental instrumentation API is a
different compatibility commitment from the stable Java API. The 0.7.0 release also fixed swallowed
`CancellationException`, evidence that cancellation preservation must be tested rather than assumed
([OpenTelemetry Kotlin getting started](https://opentelemetry.io/docs/languages/kotlin/getting-started/),
[OpenTelemetry Kotlin 0.7.0 release](https://github.com/open-telemetry/opentelemetry-kotlin/releases/tag/v0.7.0)).

Spans answer distributed observability questions well, but JFR and async-profiler do not natively
assign samples to OpenTelemetry span IDs. ReVoman would need a second bridge that writes span or
phase identity into JFR, or a profiler/exporter integration with equivalent semantics. That creates
two schemas and two lifecycle mechanisms for the stated in-process profiling goal. It remains a
plausible optional adapter if external trace export becomes a separate requirement.

The official OpenTelemetry Java contrib repository contains such a JFR bridge. It emits span events
with operation, trace, parent-span, and span IDs, plus scope events. Its documentation notes that a
span event's thread and stack trace belong to the thread that ended the span, which may differ from
the creation thread. This proves a bridge is possible, while also exposing the same thread-ownership
ambiguity and adding another component to configure
([OpenTelemetry JFR events bridge](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/jfr-events)).

## Micrometer Observation

Micrometer Observation handlers have existed since Micrometer 1.10. The current 1.17.1 reference
describes an `Observation` created through a registry with a mutable, map-like `Context`. Start,
error, event, scope, and stop operations dispatch to configured handlers. Predicates can return a
no-op observation; filters and conventions can mutate or derive metadata. Meter handlers translate
observations into timers and counters
([Micrometer Observation components](https://docs.micrometer.io/micrometer/reference/observation/components.html)).

The current source has a useful disabled-path optimization: `createNotStarted` checks for a null or
no-op registry before invoking the context supplier, then returns a singleton no-op observation.
It still leaves observation creation and lifecycle method calls at every semantic site. Predicate
suppression happens after the context supplier runs, so it is not equivalent to a no-op registry
([Micrometer `Observation.java`](https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-observation/src/main/java/io/micrometer/observation/Observation.java)).

Observation can feed metrics, tracing, logging, or a custom JFR handler, but the custom handler
would still need a JFR event schema. Its active path includes mutable context and handler fan-out,
which is useful generality rather than a direct requirement here. Micrometer also distinguishes low
and high cardinality and warns that high-cardinality meter tags can explode a metrics backend.
Per-run IDs and step paths would need careful treatment. Its annotation mode requires AOP such as
AspectJ, so that mode conflicts directly with the requirement to instrument selected operations
rather than annotate methods broadly
([Micrometer cardinality and annotation guidance](https://docs.micrometer.io/micrometer/reference/observation/components.html#_using_annotations_with_observed_and_observationkeyvalue)).

## A small internal recorder seam

This option is a ReVoman architecture boundary, not a telemetry standard. It can keep semantic call
sites unaware of JFR, OpenTelemetry, or Micrometer and prevent the backend from becoming a public
listener contract. Two interface shapes remain worth spiking after design approval:

1. A small internal inline scope wrapper that branches before constructing backend state, executes
   the operation, and always closes the scope in `finally`.
2. An internal begin/end token for call sites whose outcome is decided after the operation returns.
   The disabled token would need to be a singleton or otherwise allocation-free.

The second concern is important in this codebase. Many ReVoman failures are returned as
`Either.Left`, not thrown. A generic wrapper that only catches `Throwable` would mark those phases
as successful. Request skips and ledger skips are also normal return paths. The seam therefore
needs a way to record a domain outcome without forcing every call site to know backend fields.

The recorder must also be fail-safe. Instrumentation failure must not change a collection result,
mask the original exception, or prevent cleanup. That does not imply swallowing failures silently
in tests: backend and lifecycle tests should make instrumentation faults visible while production
execution preserves the original outcome.

## Semantic schema inputs

The accepted coverage is:

| Frequency | Meaningful operation candidates | Scale and outcome inputs |
| --- | --- | --- |
| Once per public invocation | Multi-kick orchestration, runbook orchestration, accumulated environment threading, post-execution hook | Kick count, completed kick count, runbook phase, success, failure, halted, cleanup failure |
| Once per kick or collection | Parse/load inputs, flatten/select steps, merge environment, initialize adapters/SDK/sandbox/client, execute sequence, finalize rundown/ledger, close sandbox | Source count, total and selected steps, environment size, script presence/count, custom adapter count, stop reason |
| Once per step execution | Ledger decision/injection, environment rewrite, pre-script, request construction, pre-hook, dispatch, post-script, response construction, post-hook, progress/final capture | Step ordinal, loop iteration, environment size, request/response byte counts if cheaply available, success, domain failure, request skip, ledger skip, halt, jump |
| Deferred from v1 | Polling decision and individual repeated HTTP attempts | Add only after a polling profile justifies attempt-level ownership. |
| Cleanup | Sandbox close and other owned-resource close operations that can fail or dominate tail latency | Completion and failure class. |

The selected fields are cheap and bounded:

- Step ordinal and loop iteration.
- Stable phase and outcome codes.
- Counts such as sources, selected and executed steps, environment entries, script lines, hooks,
  and request and response bytes.
- HTTP status, stop reason, request skip, and ledger skip.
- Exception class only on failure and only when recording is enabled.

Avoid collection names, full step paths, URLs, request or response content, environment keys or
values, exception messages, and arbitrary tags in the core event stream. Besides privacy risk,
those values add allocation, recording size, and cardinality. A separate opt-in diagnostic layer
can retain human-readable details.

## What correlation can and cannot prove

A duration event gives a semantic time window. CPU samples, sampled allocations, and lock events in
the paired JFR recording can be assigned to the deepest matching phase interval by absolute time
and thread identity. Wall time is the phase duration itself, while wall-clock profiler samples help
separate running, sleeping, and blocked time.

This is temporal attribution, not automatic causation. A custom event records the thread that
commits it. Work delegated to another thread is not unambiguously owned by the parent interval
unless a correlation ID is also propagated to that work. Concurrent overlapping phases can also
double-count samples if analysis only uses timestamps. The current `revUp` control flow is
synchronous, but HTTP implementations or future parallelism can cross threads. Any future coroutine
path has the same issue because a coroutine can suspend on one thread and resume on another
([Kotlin coroutine context and dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html)).

The analysis and DataFrame schema must state its attribution rule, including how it handles nested
intervals, child threads, unmatched samples, and overlapping scopes.

## Kotlin/JVM implementation concerns

- A Kotlin higher-order function normally creates function objects and captured closures. A small
  `internal inline` scope wrapper can eliminate those allocations and virtual calls. Inlining large
  bodies grows bytecode, and public inline bodies introduce binary-compatibility constraints, so
  the wrapper should stay small and internal
  ([Kotlin inline functions](https://kotlinlang.org/docs/inline-functions.html)).
- Inline lambdas allow non-local returns. A `finally` block still must close the instrumentation
  scope. `crossinline` is needed only if the implementation stores or invokes the block from another
  execution context. The design should test return, throw, and early-skip control flow explicitly.
- Metadata expressions must stay on the enabled side of the branch. `mapOf`, data classes, string
  interpolation, exception rendering, and lambda capture can otherwise allocate even when the
  backend is inactive.
- JFR ignores enum-typed event fields. A Kotlin enum can remain the internal type, but the backend
  must write a supported stable string or integer representation. Arrays and arbitrary collections
  have the same restriction.
- Kotlin properties normally compile to JVM backing fields plus accessors. JFR records fields, not
  Kotlin property accessors. Field types and annotation use-site targets such as `@field:Label`
  should be verified by reading recorded metadata. A local JDK 25 spike confirmed that JFR records a
  supported private JVM field, matching Kotlin's usual private backing-field shape. `@JvmField` is
  available when Java-visible field exposure is desired, but should not be assumed necessary
  ([Kotlin `JvmField`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.jvm/-jvm-field/)).
- Generic interfaces and nullable or value-class tokens can introduce boxing. Source-level `inline`
  is not proof of zero allocation across an interface boundary. Bytecode inspection and allocation
  profiling are required.
- A scope wrapper should catch only to classify, commit in `finally`, and rethrow the identical
  `Throwable`. It must not allocate a formatted stack trace or change ReVoman's error contract.
- If coroutines are introduced later, `CancellationException` represents cancellation and must be
  rethrown unchanged after recording a cancellation outcome. Thread-local-only correlation will not
  follow coroutine dispatch
  ([Kotlin coroutine exception handling](https://kotlinlang.org/docs/exception-handling.html)).

## Version and platform constraints

| Facility | Relevant floor | Current research baseline |
| --- | --- | --- |
| JFR `Event`, `EventFactory`, `RecordingFile` | JDK 9 | ReVoman requires JDK 25, so all are available. |
| JFR `RecordingStream` | JDK 14 | Available, but optional consumer functionality. |
| JFR contextual fields | JDK 25 | Available, but deliberately unused in v1 because profiler samples live in the paired recording and are joined explicitly. |
| async-profiler `jfrsync` | Profiler-specific option, not a JDK contract | A standalone inclusion spike worked, but the real JMH 1.37 integration lost profiler samples. It is not the selected launcher. |
| async-profiler `Span` API | async-profiler 4.5 | Direct profiler API, not part of JDK Flight Recorder. Production use needs its Java API on the library classpath. |
| OpenTelemetry Java API | Official current API supports Java 8+ | Documentation observed at API 1.65.0 on 2026-09-04. |
| OpenTelemetry Kotlin API | Kotlin 2.0+, JDK 11+ on JVM | Current API is experimental and subject to breaking changes; 0.7.0 was the current release examined. |
| Micrometer Observation handlers | Micrometer 1.10 | Documentation observed at Micrometer 1.17.1 on 2026-09-04. |
| Kotlin coroutine propagation | Requires additional coroutine/context integration | The current ReVoman dependency graph contains no `kotlinx-coroutines`. |

## Implementation acceptance evidence

The implementation acceptance pass covers these checks:

1. Disabled path: compare the untouched revision and candidate on the reusable consumer-journey
   workloads with JMH's GC profiler. Treat overlapping intervals as no detected regression, not as
   proof of zero cost.
2. Lifecycle correctness: record real `revUp` calls and assert success, returned HTTP failure,
   identical rethrown exceptions, cancellation, request skip, ledger skip, multi-kick frequency,
   sandbox boot, and sandbox close. Verify an unrelated active JFR recording leaves all ReVoman
   events disabled.
3. Profiler integration: verify native async-profiler samples and ReVoman semantic intervals are
   both non-empty in a real dual-profiler JMH fork. The reporting path rejects either half when it
   is empty.
4. Attribution: use JFR fixtures with the exact CPU, allocation, lock, and wall event layouts; test
   deepest same-thread ownership, nested self-duration, and cross-thread/unmatched samples; render
   the result through DataFrame.
5. Repository checks: run the focused tests, all module tests, Detekt, formatting, the root `check`,
   and Qodana under the repository's required JDK.

### Recorded acceptance results

The disabled-path check compared the candidate with untouched revision
`1f5d6875a0633664179cf8924e11c16f5a02b984` through the existing executable JMH JAR and GC
profiler. The all-journey pass used three forks and 30 measurements per workload. The more
sensitive script-free pass used five forks, 50 measurements per workload, longer iterations, and
reverse run order. Every 99.9% latency and normalized-allocation interval overlapped. In the
sensitive pass, latency point deltas were -0.06%, -2.84%, and +0.86%; normalized-allocation deltas
were +0.35%, -1.09%, and +0.38%. This is evidence of no detected regression in a bounded local
check, not a claim of literally zero cost or a published scorecard run.

The initial inline implementation copied the entire enabled JFR lifecycle into each call site and
made `ReVoman` bytecode about 46% larger than the baseline. Moving the enabled path behind the cold
helper reduced that growth to about 11% and removed the suspicious fast-path movement in the
higher-sample comparison. Bytecode inspection confirmed that captured bridge lambdas are created
only after the enabled check.

A final native JMH spike recorded 89 CPU samples beside 1,344 operation, 120 step, and 12 kick
events. Its wall counterpart recorded 354 batched wall events beside 1,120 operation, 100 step,
and 10 kick events. The real reporting reader attributed 13 CPU samples and 24 expanded wall
samples to same-thread semantic intervals; 76 CPU and 2,150 wall samples from attachment, compiler,
JVM, and background threads remained `UNATTRIBUTED`. The paired timestamps used different rendered
offsets (`Z` and the machine's local offset) but normalized to equal Java `Instant` values.

## Design-gate decisions

1. The first-class output is a profiler-native JFR schema behind a strictly internal seam.
2. The schema uses a small family of static `Operation`, `Kick`, and `Step` events. There is no
   attempt event in v1.
3. Operations are placed at semantic orchestration, setup, execution, response, and cleanup
   boundaries. A profiler study, not method count, is the criterion for adding another split.
4. Events are disabled by default and retain all enabled outcomes without a duration threshold.
   Stack traces remain disabled because sampled stacks come from async-profiler.
5. The step wrapper derives domain outcomes from `StepReport`; generic operation sites do not know
   about Arrow or reporting internals.
6. Existing `StepReport.exeTimings` remain unchanged. Their `measureTimedValue` block sits outside
   the JFR event commit so public timings do not include instrumentation bookkeeping.
7. No external observability adapter or listener is exposed in v1.

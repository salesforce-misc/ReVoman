# Self-documenting sequence diagrams from the run-log event stream

**Date:** 2026-07-19
**Status:** Design approved, pending spec review
**Related:** [[2026-07-19-file-run-log-sink-library-design]], `RunLogSink` / `RunLogRenderer` / `StepEvent`

## Motivation

http4k's [TracerBullet](https://www.http4k.org/ecosystem/http4k/reference/tracerbullet/)
generates self-documenting sequence diagrams from tests: apps emit `Events`, distributed
tracing (Zipkin/B3) stitches a cross-service trace tree, and renderers emit PlantUML/Mermaid
plus inefficiency analysis. The question that prompted this design: can ReVoman leverage
TracerBullet to enhance the library?

### Feasibility read (why we borrow the idea, not the package)

TracerBullet is built for **http4k apps you control** — you wire
`ServerFilters.RequestTracing()` into your servers and `ClientFilters.RequestTracing()` into
your clients, and every hop emits an http4k `Event` carrying B3 headers that TracerBullet
stitches into a cross-service tree.

ReVoman is the opposite shape: a **black-box client** firing at *remote* servers it does not
instrument (Salesforce core APIs, pokeapi, restful-api.dev, …). Two consequences make direct
adoption a poor fit:

1. **Cross-service B3 stitching won't work.** Remote servers won't propagate/echo B3 or emit
   `Incoming` events, so TracerBullet's marquee auto-discovered multi-service topology degrades
   to a flat `User → host` list. The topology ReVoman *can* build — `User → each distinct
   host`, plus data-flow edges where a runbook's produced env value feeds a later call — is
   knowledge ReVoman already has and raw TracerBullet does not.
2. **Core fat-jar constraint.** ReVoman ships to Salesforce Core as a fat `java_import` jar
   with **no transitive dependencies** (see `DEVELOPMENT.md`). Adopting
   `http4k-testing-tracerbullet` means bundling it + transitives into the fat jar or pinning
   them in Core's Maven graph — heavy, for a *testing* module.

The genuinely valuable parts of TracerBullet — **self-documenting sequence diagrams** and
**inefficiency detection** — don't need its machinery. ReVoman already emits a richer event
stream than http4k's generic `Events` (`StepEvent`: produced/consumed env keys, phases,
runbook intent, under-test flags) and already has a clean sink architecture (`RunLogSink` +
`RunLogRenderer` as single source of truth). So we **borrow the pattern**: a new diagram sink
fed by the existing `StepEvent` stream. No new dependency, zero Core-jar impact, and the
diagrams show env data-flow that TracerBullet cannot.

## Goals

Deliver all three, from the existing event stream:

1. **Visual sequence diagrams** — auto-render each run/runbook as a Mermaid `sequenceDiagram`
   (`User → API host` per HTTP step), self-documenting output alongside today's text run-log.
2. **Multi-service topology** — distinct request hosts become distinct participants; runbook
   phases group the timeline; produced→consumed env linkage renders as data-flow notes.
3. **Inefficiency detection** — flag duplicate `(method, host, path)` calls within a run.

### Non-goals

- No http4k `ClientFilters`/`ServerFilters` tracing, no B3/Zipkin headers, no second event
  pipeline.
- No `http4k-testing-tracerbullet` dependency (Core fat-jar constraint).
- No PlantUML output (Mermaid only; a PUML variant behind the same renderer interface is a
  future extension, YAGNI now).
- No live/streaming diagram — a sequence diagram is a whole-run artifact, rendered on `close()`.

## Architecture

A new **`DiagramRunLogSink`** in `src/main/kotlin/com/salesforce/revoman/output/log/`, a
sibling to `ConsoleRunLogSink` / `FileRunLogSink`, implementing the existing `RunLogSink`
interface. It is installed per-`revUp` through the mechanism that already exists
(`Kick.runLogSink` → `RunLogContext`), pays nothing when unused, and honors the never-throw
contract.

Key decisions:

- **Reuse the `StepEvent` stream** — not a second pipeline. The diagram is text we generate,
  exactly as `RunLogRenderer` generates the text spine.
- **Accumulate, render on `close()`** — unlike the per-event live line/spine sinks, the diagram
  sink buffers structured interactions during the run and renders the whole diagram at the end.
- **CompositeRunLogSink for coexistence** — a run has one `RunLogSink` slot. A small
  `CompositeRunLogSink` fans each event to N delegates (e.g. file + diagram), keeping each sink
  single-purpose and independently testable (chosen over folding into `FileRunLogSink`, which
  is already `TooManyFunctions`-suppressed).

### Data flow

```
revUp
  └─ emitStepFinished(step, report)            // ReVoman.kt — now also extracts method/host/path
       └─ RevomanLog.event(StepFinished)
            └─ RunLogContext.current()          // active sink for this run
                 └─ CompositeRunLogSink.event   // fans out
                      ├─ FileRunLogSink.event    // live text (unchanged)
                      └─ DiagramRunLogSink.event // append RunInteraction, track phase/intent
run ends
  └─ close()
       └─ DiagramRunLogSink.close()
            └─ DiagramRenderer.render(interactions)  // pure → Mermaid text
                 └─ write <logsDir>/<runLabel>/<timestamp>.mmd  + repoint latest.mmd
```

## Components

### 1. `StepEvent.StepFinished` — +3 structured fields (the data source)

Add `method: String?`, `host: String?`, `path: String?` (all default `null`). Populated at
`ReVoman.emitStepFinished` from the http4k `Request` already in hand
(`report.requestInfo.get().httpMsg`): `.method.name`, `.uri.authority`, `.uri.path`. Null for a
step with no HTTP request (skipped / hook-only). Existing sinks ignore them; only the diagram
sink reads them. Nullable + default → no behavior change for `RunLogRenderer`, `FileRunLogSink`,
`ConsoleRunLogSink`, or their tests.

Rationale: a sequence diagram needs actors = hosts, but today `StepFinished` carries only
pre-rendered wire text (`requestMsg`). Extract structure at the emit site rather than re-parse
wire text in the sink.

### 2. `RunInteraction` — internal accumulation model

A `data class` in `output/log/` (internal to the diagram feature) the sink appends per HTTP
`StepFinished`:

```kotlin
data class RunInteraction(
    val seq: Int,
    val from: String,          // always "User" — ReVoman is the black-box client
    val to: String,            // request host
    val label: String,         // "<method> <path>"
    val status: Int?,          // HTTP status code
    val tookMs: Long,
    val outcome: Outcome,
    val produced: Set<String>, // env keys this step produced
    val consumed: Set<String>, // env keys this step consumed
    val phase: String?,        // enclosing runbook phase, if any
    val intent: String?,       // enclosing runbook step intent, if any
    val underTest: Boolean,
)
```

`phase` / `intent` / `underTest` come from the enclosing `PhaseEntered` / `RunbookStepStarted`
the sink tracks as it streams events.

### 3. `DiagramRenderer` — pure, stateless, single grammar source

Mirrors `RunLogRenderer`'s design: an `object` with `@JvmStatic fun render(interactions:
List<RunInteraction>): String`. Pure (no I/O). This is where the three goals land:

- **Sequence diagram** — `User ->> <host>: <method> <path>` then
  `<host> -->> User: <status> (<ms>ms)`, one `participant` per distinct host.
- **Topology** — distinct hosts → distinct participants; runbook phases → Mermaid
  `Note over` / grouping; produced→consumed env linkage → a note on the edge where a value
  flows from the step that produced it to the step that consumes it.
- **Inefficiency detection** — a post-pass counts duplicate `(method, host, path)` tuples and
  emits a flag (e.g. `Note over <host>: ⚠ 3× GET /pokemon/ditto`) plus a trailing summary.
- **Empty run** — returns a minimal valid `sequenceDiagram` (no participants) rather than
  malformed output.

Output is a Mermaid `sequenceDiagram` block. Chosen over PlantUML because Mermaid renders
natively in GitHub, IDEs, and the repo's mermaid MCP toolchain, and needs no external
`plantuml.jar`.

### 4. `DiagramRunLogSink` — the `RunLogSink` impl

- `event(event)` — on `StepFinished` with a non-null host, append a `RunInteraction`
  (assigning the next `seq`); on `PhaseEntered` / `RunbookStepStarted`, update the tracked
  current phase / intent / under-test; ignore other events. Wrapped in `runCatching` +
  `logger.debug` breadcrumb.
- `close()` — `DiagramRenderer.render(interactions)`, write
  `<logsDir>/<runLabel>/<timestamp>.mmd`, repoint `latest.mmd` (symlink, fall back to pointer
  file — same helper shape as `FileRunLogSink.repointLatest`). All I/O swallowed + logged.
- Factory mirrors `FileRunLogSink`: `open(logsDir, runLabel, startedAt)` /
  `openOrNoOp(...)` never-throw variant.

### 5. `CompositeRunLogSink` — fan-out

```kotlin
class CompositeRunLogSink(private val delegates: List<RunLogSink>) : RunLogSink {
    override fun line(level, message) { delegates.forEach { guard { it.line(level, message) } } }
    override fun event(event)         { delegates.forEach { guard { it.event(event) } } }
    override fun close()              { delegates.forEach { guard { it.close() } } }
}
```

A throwing delegate is caught (`guard` = `runCatching` + breadcrumb) so it never stops the
others, and never fails the run. `hasActiveSink()` stays true (a Composite of real sinks is not
`NoOp`), so the `emitStepFinished` capture path still runs.

## Configuration

Add `diagram: Boolean = false` to the existing `FileRunLogConfig` — the established
content-toggle home (`steps` / `runbook` / `perf` / `libLogs` / `outcome`). Default `false` →
zero behavior change for every existing consumer.

The `RunLogSink` is **caller-supplied** via `Kick.runLogSink` — the consumer (e.g. Core's
per-test sink) constructs it, today by calling the library factory `FileRunLogSink.openOrNoOp`.
So composition lives behind that factory boundary, not at a hidden internal site: the library
exposes a factory (either extending `FileRunLogSink.openOrNoOp` or a sibling
`RunLogSinks.openOrNoOp`) that, given `logsDir` / `runLabel` / `mode` / `startedAt` / `config`,
returns:

- the `FileRunLogSink` alone when `config.diagram == false` (exactly as today), or
- `CompositeRunLogSink(listOf(fileSink, DiagramRunLogSink.open(...)))` when `config.diagram ==
  true`.

The consumer flips one toggle (`FileRunLogConfig(diagram = true)`) and passes the factory result
to `Kick.runLogSink`; the library owns the composition, keeping it a single source. Because a
`CompositeRunLogSink` of real sinks is not `NoOp`, `RunLogContext.hasActiveSink()` stays true and
the `emitStepFinished` capture path runs unchanged.

## Error handling

Same discipline as `FileRunLogSink` (see its class doc): `event()` and `close()` wrap work in
`runCatching`, swallow, and leave a `logger.debug` breadcrumb. A diagram is a convenience, never
fails a run — ReVoman invokes sinks on the hot execution path. `DiagramRenderer` is pure, so it
cannot fail on I/O; a rendering bug surfaces only at the sink boundary, mirroring how
`FileRunLogSink.renderAndWrite` guards `RunLogRenderer`.

## Testing (Kotest — all new code covered per AGENTS.md)

- **`DiagramRendererTest`** (the bulk — renderer is pure, so exhaustive + fast). Given a
  `List<RunInteraction>`, assert exact Mermaid output for: single host; multi-host topology;
  runbook phase grouping; env produced→consumed data-flow note; duplicate-call inefficiency
  flag; empty run.
- **`DiagramRunLogSinkTest`** — feed a `StepEvent` sequence; assert accumulated interactions,
  that `close()` writes the `.mmd` and repoints `latest.mmd`, and never-throw on a forced write
  error.
- **`CompositeRunLogSinkTest`** — assert fan-out to both delegates, and that one throwing
  delegate does not stop the other or fail the call.
- **Regression** — existing `RunLogRendererTest` / `FileRunLogSinkTest` /
  `ConsoleRunLogSinkTest` unaffected (new fields nullable, `diagram` default off).
- **Integration** — one existing pokemon FTest opts in `diagram=true`, asserts a non-empty,
  well-formed `.mmd` is produced.

## Files

New:
- `src/main/kotlin/com/salesforce/revoman/output/log/RunInteraction.kt`
- `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRenderer.kt`
- `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSink.kt`
- `src/main/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSink.kt`
- `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRendererTest.kt`
- `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSinkTest.kt`
- `src/test/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSinkTest.kt`

Modified:
- `src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt` — +3 fields on `StepFinished`
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — populate method/host/path in
  `emitStepFinished`
- `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt` — +`diagram` toggle
- the library sink factory (`FileRunLogSink.openOrNoOp` or a sibling `RunLogSinks` factory) —
  return a `CompositeRunLogSink(file + diagram)` when `config.diagram == true`, the file sink
  alone otherwise

## Core-jar impact

None. No new runtime dependency — the diagram is text ReVoman generates. Nothing to bundle in
the fat jar, nothing to pin in Core's Maven graph.
```

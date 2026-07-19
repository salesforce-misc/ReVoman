# FileRunLogSink — move per-test file-log orchestration into the library

**Date:** 2026-07-19
**Status:** Approved (design), pending spec review
**Repos:** `revoman-root` (PRIMARY, library) · `loki-core` (CONSUMER, thin adapter)

## Problem

Today `loki-core`'s `PerTestRunLogSink.java` (368 lines) owns per-test file-sink
orchestration that is mostly **generic**: the file layout, the live flush-per-write
writer, the banner + legend, the outcome footer + stacktrace, the `latest.log`
symlink, content-toggle gating, the never-throw guarantee, and the perf splice.

Only a thin slice is genuinely consumer-specific: WHERE the config lives
(`~/.revoman/config.yaml`), the logs-dir PATH, the `OrgMode` mode string, and the
perf CONTENT (`ReVomanPerf` — bound to `BaseTest` reflection + Core lifecycle stages).

Because the generic bulk lives on the consumer, it drifts from the library's
`RunLogRenderer`. Last session the banner **legend** — which describes the line
grammar that `RunLogRenderer` produces — drifted and had to be hand-resynced on
Core. That resync-on-the-consumer is direct proof the code is mis-placed: the
legend belongs beside the grammar it documents.

## Goal

A new library `FileRunLogSink` (in `revoman-root`, package
`com.salesforce.revoman.output.log`, beside `RunLogRenderer`) owns everything
generic. `loki-core` shrinks to a thin adapter that passes in only its own data:
config VALUES, the logs-dir PATH, and identity facts (mode string, bound org id).
The legend now lives one file away from the grammar it describes and is edited
together with it, so it cannot drift again.

## The new boundary

```
revoman-root (PRIMARY, Kotlin, output.log)          loki-core (CONSUMER, Java, thin adapter)
┌────────────────────────────────────────┐         ┌──────────────────────────────────────┐
│ RunLogRenderer   (grammar, unchanged)   │         │ RunLogConfig  = YAML reader:           │
│ RunLogSink/NoOp/StepEvent  (unchanged)  │         │   ~/.revoman/config.yaml `logs:`       │
│ ConsoleRunLogSink          (unchanged)  │         │   → (enabled, FileRunLogConfig)        │
│                                         │         │                                        │
│ NEW  FileRunLogConfig  (value type:     │◀─values─│ ReVomanFTest orchestration:            │
│   5 toggles + heaviestSteps)            │         │   • autobuild gate + enabled switch    │
│ NEW  FileRunLogSink : RunLogSink        │◀─path───│   • FileRunLogSink.openOrNoOp(logsDir, │
│   ─ banner + LEGEND (beside grammar)    │◀─mode───│       runLabel, mode, startedAt, cfg)  │
│   ─ live writer, flush-per-write        │         │   • recordRunFact("org", boundOrgId()) │
│   ─ footer + stacktrace/cause chain     │         │   • ReVomanPerf tee → sink.perfLine    │
│   ─ latest.log, never-throw, gating     │         │   • renderHeaviestSteps→recordPerf     │
│   ─ recordRunFact / footer              │         │       Summary → footer → close         │
│   ─ perf HOOKS: perfLine /              │         │ ReVomanPerf  (BaseTest reflection,     │
│     renderHeaviestSteps(n) /            │         │   OrgMode, [ReVomanPerf] lines — stays)│
│     recordPerfSummary(block)+splice     │         │ PerTestRunLogSink.java  → DELETED       │
└────────────────────────────────────────┘         │ RunLogSinkHandle.java    → DELETED       │
                                                     └──────────────────────────────────────┘
```

## Settled seam decisions

Three pivotal seams were decided during brainstorming:

1. **Perf coupling → generic hooks, content on Core.** The library sink owns three
   content-agnostic capabilities; `ReVomanPerf` stays entirely on Core and feeds
   them its own rendered text:
   - accumulate step timings from the `StepEvent` stream it already sees, and
     `renderHeaviestSteps(topN)` from them — Core stops re-deriving this;
   - `recordPerfSummary(block)` — live-write any pre-rendered block AND store it so
     `close()` splices the identical block below the header banner;
   - `perfLine(line)` — tee a raw line, gated by the perf toggle.

2. **Config → library value type; Core reads YAML.** The library defines
   `FileRunLogConfig` (the 5 content toggles + `heaviestSteps`). Core keeps a thin
   YAML reader mapping `~/.revoman/config.yaml`'s `logs:` block onto it. The
   `enabled` master switch + the autobuild gate stay Core orchestration — they
   decide real-sink-vs-nothing BEFORE opening, so they never enter the library.

3. **Identity → mode at open + generic `recordRunFact(key, value)`.** `open()` takes
   the mode string (banner line 1, known at open). A generic
   `recordRunFact(key, value)` appends a tagged `[run] key=value` line for anything
   learned later; Core calls `recordRunFact("org", boundOrgId())` once the org binds
   mid-setUp. The library never learns the Salesforce word "org".

## Language

Kotlin. `revoman-root/src/main` is 100% Kotlin; the peer sinks (`ConsoleRunLogSink`,
`RunLogRenderer`) are Kotlin. `@JvmStatic` factories plus a nullable `openOrNoOp`
return make Java consumption clean — the same pattern `RunLogRenderer` already uses
for its Java consumers.

## Library surface (new, `com.salesforce.revoman.output.log`)

### `FileRunLogConfig`
A data class value type carrying only the content knobs:

```kotlin
data class FileRunLogConfig(
  val libLogs: Boolean,
  val steps: Boolean,
  val perf: Boolean,
  val outcome: Boolean,
  val runbook: Boolean,
  val heaviestSteps: Int,
) {
  companion object {
    const val DEFAULT_HEAVIEST_STEPS: Int = 10
    @JvmField val DEFAULT_ALL: FileRunLogConfig =
      FileRunLogConfig(true, true, true, true, true, DEFAULT_HEAVIEST_STEPS)
  }
}
```

**No `enabled` field** — the master switch is Core orchestration (real-vs-nothing is
decided before the sink is opened).

### `FileRunLogSink : RunLogSink`
Private constructor; opened via the companion factories. Owns:

- **`line(level, message)` / `event(event)` / `close()`** — the `RunLogSink`
  contract. `line` applies libLogs gating (OFF drops INFO+DEBUG; WARN/ERROR always
  pass). `event` accumulates per-path step timings (before any gate), renders coarse
  runbook events under the `runbook` toggle and per-step events under the `steps`
  toggle, all via `RunLogRenderer`. `close()` = flush + close writer, splice stored
  perf summary below banner, repoint `latest.log`.
- **`recordRunFact(key, value)`** → writes `[run] key=value`. Generic; replaces
  `recordOrgBinding`. `value` null renders `(unset)`.
- **`footer(passed, failingStep, failure: Throwable?)`** — outcome-toggle gated;
  one-line error summary + full stacktrace with `Caused by:`/`Suppressed:` chain via
  `Throwable.printStackTrace` (JDK-generic; no Salesforce coupling). Trace never
  trimmed.
- **Perf hooks (perf-toggle gated, content-agnostic):**
  - `perfLine(line)` — tee one raw line.
  - `renderHeaviestSteps(topN): String` — the `--- perf: heaviest steps ---` table
    from accumulated timings, slowest first, path column truncated for alignment.
  - `recordPerfSummary(block)` — live-write `block` at the footer AND store it so
    `close()` splices the identical block below the banner's closing rule.
- **Companion factories (`@JvmStatic`):**
  - `open(logsDir, runLabel, mode, startedAt, config): FileRunLogSink` — creates
    `<logsDir>/<runLabel>/<timestamp>.log`, writes the banner at line 1, throws on
    I/O failure.
  - `openOrNoOp(logsDir, runLabel, mode, startedAt, config): FileRunLogSink?` — never
    throws; returns `null` on disabled/open-failure. **Nullability is the `isReal`
    signal**, so `RunLogSinkHandle` is deleted and the consumer no longer casts.

Note the signature change from today's `(logsDir, testClass, method, ...)` to
`(logsDir, runLabel, ...)`: the `testClass + "." + method` join moves to the Core
adapter, keeping the library free of the test-identity convention. The file layout
`<logsDir>/<runLabel>/<timestamp>.log` and the `latest.log` repoint are unchanged.

### Banner + legend
The banner (line 1, self-describing) and its legend move verbatim into
`FileRunLogSink`, now one file away from `RunLogRenderer`. The legend text is the
canonical description of the grammar `RunLogRenderer` emits; co-locating them means a
grammar change and its legend line are edited together.

## Core adapter (loki-core, Java) — the shrink

- **`PerTestRunLogSink.java` — DELETED** (368 lines; logic reborn in the library).
- **`RunLogSinkHandle.java` — DELETED** (nullability replaces the `isReal` flag).
- **`RunLogConfig.java`** — shrinks to a pure YAML reader. Reads
  `~/.revoman/config.yaml` `logs:` and returns `(boolean enabled, FileRunLogConfig
  content)`. Keeps the never-throw / default-all-on contract (missing file/block/any
  error ⇒ enabled + `DEFAULT_ALL`). Exact return shape (a small record vs two
  accessors) is an implementation detail for the plan.
- **`ReVomanFTest.java`** — `buildRunLogSink` keeps the autobuild gate + `enabled`
  switch, then calls
  `FileRunLogSink.openOrNoOp(RevomanHome.logsDir(), getClass().getSimpleName() + "." +
  getTestMethodName(), mode, startedAt, cfg.content())`. Holds a
  `@Nullable FileRunLogSink` (no handle); wires `sink != null ? sink : RunLogSink.NoOp.INSTANCE`
  into the runner and `ReVomanPerf.setSink(sink)`. `recordOrgBinding(boundOrgId())`
  becomes `recordRunFact("org", boundOrgId())`. Teardown perf/footer/close calls
  retarget to `FileRunLogSink`; the null-check replaces every `runLogHandle.isReal()`
  + cast.
- **`ReVomanPerf.java`** — `ThreadLocal<PerTestRunLogSink>` → `ThreadLocal<FileRunLogSink>`;
  `setSink` / `teePerf` retyped. Perf CONTENT (`renderBreakdown`, `summary`,
  `BaseTest` reflection, `[ReVomanPerf]` lines) stays entirely on Core.

## Testing & verification

- **Library:** port the 26-case `PerTestRunLogSinkTest.java` → a Kotlin
  `FileRunLogSinkTest` (matching `ConsoleRunLogSinkTest.kt` / `RunLogRendererTest.kt`
  style, Kotest + `@TempDir`), covering banner/legend, `recordRunFact`, footer +
  cause chain, every content toggle, `latest.log` symlink + pointer fallback,
  `openOrNoOp` null-on-failure, heaviest-steps sort/cap, and perf-summary
  splice-below-banner. Add a small `FileRunLogConfig` defaults test. `./gradlew test`
  green.
- **Core:** retarget `RunLogConfigTest` to the reader → `FileRunLogConfig` mapping;
  DELETE `PerTestRunLogSinkTest.java` (its coverage now lives in the library).
- **E2E:** rebuild the consumable jar per DEVELOPMENT.md, restart the Core server,
  run one real ReVoman FTest, and confirm the `.log` renders banner + legend + steps
  + perf block (both copies) + footer, and `latest.log` repoints — the same
  end-to-end check that closed last session.
- **Version/publish:** left as an explicit OPTIONAL final step. Core E2E consumes
  ReVoman via `.bazelrc-local` sources (no jar publish required for the source path),
  so a Maven publish is not assumed by this design.

## Execution method

`writing-plans` to produce the implementation plan, then
`subagent-driven-development` (implementer + reviewer per task), per the brief.

## Out of scope / non-goals

- No change to `RunLogRenderer`, `RunLogSink`, `ConsoleRunLogSink`, `StepEvent`, or
  the wire grammar.
- No change to the `~/.revoman/config.yaml` `logs:` schema or the `latest.log` /
  `<logsDir>/<runLabel>/<timestamp>.log` file layout — a consumer's existing logs and
  configs keep working.
- Perf breakdown rendering stays on Core; it is NOT moved into the library.
- No Maven version bump/publish unless explicitly requested as a follow-up.

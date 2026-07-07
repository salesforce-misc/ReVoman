# ConsoleRunLogSink — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorming)

## Problem

`RunLogSink` (`src/main/kotlin/com/salesforce/revoman/output/log/RunLogSink.kt`) ships with
exactly one concrete implementation: `NoOp`. Its own KDoc says it is "reusable by ANY consumer",
yet any consumer that actually wants to *see* the step exchange must hand-roll an anonymous
implementation.

The WFS integration-test config does exactly that: `PRINT_SINK`
(`src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java:136-166`)
is an anonymous `RunLogSink` that renders each `StepEvent.StepFinished` (path, HTTP status,
outcome, request/response wire text) to `System.out` so the exchange lands in the JUnit/Gradle
log. There is nothing WFS-specific about it — it is a generic console renderer living in a test
file, untestable in isolation, and duplicated by every future consumer with the same need.

## Goal

Promote a console/stdout `RunLogSink` into the library as a first-class, reusable, unit-tested
implementation — the natural companion to `NoOp`. Migrate the WFS config to consume it.

Non-goals: no change to the `RunLogSink` interface, `StepEvent` contract, or the emit path in
`RunLogContext`/`ReVoman`. No new logging framework. No redirect of KotlinLogging output.

## Design

### New component

`src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt` (Kotlin, package
`com.salesforce.revoman.output.log`, beside `RunLogSink.kt` / `StepEvent.kt`).

```kotlin
class ConsoleRunLogSink(private val out: PrintStream = System.out) : RunLogSink
```

- **Constructor:** takes a `PrintStream`, defaulting to `System.out`. The default keeps the common
  case a one-liner (`ConsoleRunLogSink()`); the injectable stream is what makes it unit-testable
  (a `ByteArrayOutputStream`-backed stream) — the whole reason for a named class over a
  `System.out`-hardcoded singleton.
- **`line(level, message)`** → no-op. KotlinLogging already emits every teed line; rendering it
  again to the stream would duplicate that output. (Matches `PRINT_SINK`'s behavior.)
- **`event(event)`** → `when` over the sealed `StepEvent`, one render per subtype (see formats
  below). All 7 subtypes handled — the reusable value over the WFS version, which renders only
  `StepFinished`.
- **`close()`** → no-op. The `RunLogSink` contract states ReVoman never calls `close()` and the
  caller owns the sink's lifecycle; a caller-supplied `PrintStream` (e.g. `System.out`) must not
  be closed by the sink.
- **Never throws:** the interface mandates implementations must not fail the run from
  `line`/`event`/`close`. Rendering is wrapped so an I/O error on the stream is swallowed, never
  propagated to the hot execution path.

### Render formats

`event()` dispatches on the `StepEvent` subtype:

```
→  STEP  <path> (<name>)                              # StepStarted
── STEP  <path> [<httpStatus>] <outcome> (<tookMs>ms) # StepFinished — header
   produced=<produced>  consumed=<consumed>           #   omitted when both sets empty
REQ:
<requestMsg>                                           #   block omitted when requestMsg == null
RESP:
<responseMsg>                                          #   block omitted when responseMsg == null
↩  LEDGER-SKIP <path> reused=<reused>                 # LedgerSkipped
⤫  REQ-SKIP    <path>                                 # RequestSkipped
↪  JUMP        <path> → <toPath>                        # Jumped
■  STOP        <path>: <reason>                         # RunStopped
✖  LOOP-BUDGET <path> budget=<budget>                  # LoopBudgetExceeded
```

`StepFinished` keeps parity with today's `PRINT_SINK` (header line + `REQ:`/`RESP:` blocks) and
adds `tookMs` and the produced/consumed key sets — rich fields the anonymous version drops. Null
`requestMsg`/`responseMsg` omit their block; empty produced+consumed omit the keys line.

## Testing

`src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt` (Kotest), rendering
into a `ByteArrayOutputStream`-backed `PrintStream`:

- Each of the 7 `StepEvent` subtypes renders its expected text.
- `StepFinished` variants: null req/resp omit their blocks; present req/resp emit them; empty
  produced+consumed omits the keys line; populated sets emit it.
- `line(level, message)` writes nothing to the stream.
- `close()` does not throw and does NOT close the injected stream (a subsequent write still
  succeeds).

## Migration (WFS config)

After `ConsoleRunLogSink` is on `master` and the branch is rebased onto it:

- Delete the `PRINT_SINK` anonymous class and its leading comment
  (`ReVomanConfigForWfs.java:131-166`).
- `ReVomanConfigForWfs.java:631`: `.runLogSink(PRINT_SINK)` → `.runLogSink(new ConsoleRunLogSink())`.
- Add import `com.salesforce.revoman.output.log.ConsoleRunLogSink`; drop now-unused imports
  (`LogLevel`, `StepEvent`) if no other reference remains.

No republish: `src/main` and `src/integrationTest` are the same Gradle module, so the branch
consumes `ConsoleRunLogSink` from source directly after the rebase.

## Workflow

1. Create a git worktree off `master`. Implement `ConsoleRunLogSink` + its Kotest suite there.
   `spotlessApply`, build, test. Push to `master`.
2. Rebase `wfs/scheduler-vs-unified-1x-parity` onto the updated `master`. Apply the WFS migration
   above. Build/test. Force-push the branch.

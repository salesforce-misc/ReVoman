# Runbook log — verbose per-request nesting under each step (implementation spec)

Status: APPROVED, ready for implementation plan. Date: 2026-07-18.
Supersedes the loki-core diagnosis note
`~/core-public/core/loki-core/.../revoman/docs/superpowers/specs/2026-07-18-runbook-log-verbose-nesting-design.md`
(the diagnosis stands; this spec revises the *approach* per three user steers — see "Approach change" below).
Related: `2026-07-13-runbook-legibility-design.md` (the coarse-tree layer this extends).

## Problem

The Runbook adoption gave the per-test run-log a **coarse tree** — phase rules, per-step `┌ … └`
brackets with `⟵ consumes` / `⟶ produces` glyphs and `★ UNDER TEST`. But *inside* each bracket the
HTTP request/response is rendered **flush-left, no `│` gutter**, so it visually floats instead of
reading as nested under the step, and it is buried under a flood of `[DEBUG] {{x}} resolved from
scope` narration (appearing twice per request). User report:

> "the log should be more of a verbose runbook that shows request/response [nested under each step]."

Plus a later steer: **"Use appropriate TUI / ASCII to enhance clarity in the log."**

## Root cause (confirmed, code-level)

Two sinks render the `StepEvent` stream, and **the grammar is duplicated between them**:

- **library** `revoman-root/src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt`
  — `render(event)` (L63) is a big `when` over every `StepEvent`; `renderFinished` (L131) guttered
  only the `│ REQ:` / `│ RESP:` **labels**, letting the JSON body lines float flush-left.
- **Core** `loki-core/.../revoman/runtime/PerTestRunLogSink.java` — `renderCoarse` (L247),
  `renderFinished` (L293), `phaseRule` (L281), `RULE_WIDTH`, and the glyph literals re-implement the
  SAME grammar in Java, and drift: `renderFinished` dumps a flush-left `{"event":"finished"}` JSON
  record + `--- request ---` / `--- response ---` dividers (no gutter at all), and `StepStarted`
  falls through to a `{"event":"started"}` JSON block via `toJsonLine` (L440).
- **DEBUG flood**: `PerTestRunLogSink.line()` (L203) gates only INFO behind `libLogs()`; **DEBUG
  passes unconditionally** — that's the `{{x}} resolved from scope` narration (from
  `RegexReplacer.kt:91`), which also appears twice (double template pass). The library sink's
  `line()` is a no-op, so this is a **Core-only** problem.

## Approach change (three user steers)

The original diagnosis note proposed fixing `PerTestRunLogSink.java` alone. Three steers reshape it:

1. **"Keep most of the logic in the library so all consumers benefit; only necessary logic on Core."**
2. **"Transfer any existing loki-core revoman-framework logic to revoman-root if it's generic."**
3. **"Use appropriate TUI / ASCII to enhance clarity."**

So this is a **dedup-into-the-library + enhance**, not a Core-only patch. The event→string grammar is
generic → it moves to the library. Only file/OrgMode/toggle policy stays on Core.

## Architecture

```
revoman-root (library)                          loki-core (Core wrapper)
──────────────────────                          ────────────────────────
RunLogRenderer  (NEW, pure object)              PerTestRunLogSink.java
  • glyphs, spine │, corners ┌ └                  KEEPS (necessary Core policy):
  • RULE_WIDTH, phaseRule                            • file layout / latest.log symlink
  • render(StepEvent): String                        • banner + legend (OrgMode-aware)
  • gutter(block): prefix EVERY line with "│ "       • footer + full stacktrace
  ▲         ▲                                         • RunLogConfig toggle gating
  │ uses    │ uses                                    • perf splice + live flush
ConsoleRunLogSink.kt          PerTestRunLogSink        • line() DEBUG gate  ◄── Core-only fix
  event() → RunLogRenderer      event() → RunLogRenderer.render(event)
  (console gains enhanced      DROPS (now the library's job):
   grammar for free)             renderCoarse, renderFinished, phaseRule,
                                 RULE_WIDTH, glyph literals, toJsonLine,
                                 jsonStringMap, jsonStrings  → deleted
```

### New: `RunLogRenderer` (library, pure)

`com.salesforce.revoman.output.log.RunLogRenderer` — a stateless Kotlin `object` (methods
`@JvmStatic` so Java can call them). Single source of truth for the `StepEvent → String` grammar.

- `render(event: StepEvent): String` — the `when` currently inside `ConsoleRunLogSink.render`, moved
  here verbatim then enhanced (below).
- `phaseRule(name: String): String` and `RULE_WIDTH = 52` — moved here (both sinks had copies).
- `gutter(block: String): String` — prefix **every** line of a multi-line block with `"│ "`. This is
  the enhancement that makes the body truly nest (fixes the flush-left float in BOTH sinks).

### Enhanced single-spine grammar (rendered by `RunLogRenderer`)

```
┌ ◆ schedule                      ⟵ —   ★ UNDER TEST     step open  (heavy corner)
│ ▸ schedule-single                                       child started (was "│ · ")
│   200 OK · 4836ms  ✔                                    compact finished header
│   ⟵ ∅   ⟶ saId=08pxx0000004CiWAAU                       values (∅ = empty side)
│ ── REQ ─────────────────────                            light sub-rule
│ POST https://…/actions/schedule HTTP/1.1                every body line spine-prefixed
│ {
│   "appointments": [ … ]
│ }
│ ── RESP ────────────────────
│ HTTP/1.1 200 OK
│ { … }
└ ✔ schedule                      ⟶ saId=08pxx0000004CiWAAU   step close
```

Three visual levels: `━━ PHASE ━━` phase rules > `┌ … └` heavy step corners > `│`-spine child
exchange with light `── REQ ── / ── RESP ──` sub-rules. Concrete changes vs today's library sink:

- child `StepStarted`: `│ · <name>` → **`│ ▸ <name>`** (heavier caret reads as a nested request).
- finished header: `│   <status> <OK|FAIL|SKIP> <ms>ms` → **`│   <status> <OK|FAIL|SKIP> · <ms>ms  <✔|✘|⊘>`**
  (mid-dot separator + trailing outcome glyph; glyph `✔` SUCCESS / `✘` FAILED / `⊘` SKIPPED).
- values line: unify on **values**, not key-sets (see decision D5) — `│   ⟵ <consumedValues> ⟶ <producedValues>`,
  with `∅` for an empty side. Falls back to the key-set when values are absent.
- REQ/RESP: `│ REQ:` label + flush-left body → **`│ ── REQ ──…` sub-rule + `gutter(body)`** so every
  body line carries the `│ ` spine.

### What stays on Core (necessary only)

`PerTestRunLogSink.java` keeps everything that is Core/OrgMode/file policy, and delegates all grammar:

- `event()` routes to `RunLogRenderer.render(event)` for the grammar, but keeps the **toggle gating**
  (`config.runbook()` for coarse events, `config.steps()` for per-request) and the `stepTimings`
  accumulation (feeds the heaviest-steps table — Core perf feature, not generic).
- `line()` keeps the narration tee AND gains the **DEBUG gate** (decision D3): DEBUG is gated behind
  `libLogs()` exactly like INFO; WARN/ERROR always pass (diagnostics). This is the Core-only fix for
  the `{{x}} resolved` flood.
- banner/legend, footer+stacktrace, perf splice, `renderHeaviestSteps`, `latest.log`, live flush —
  all unchanged (all Core policy).
- DELETED as now-dead: `renderCoarse`, `renderFinished`, `phaseRule`, `RULE_WIDTH`, `toJsonLine`,
  `jsonStringMap`, `jsonStrings`, `esc` (if unused after) — the JSON-record path is gone.

## Decisions (settled with user)

- **D1 — nesting is automatic when `steps` is on.** No new `verboseRunbook` toggle. The spine shape
  is strictly more legible; the flush-left JSON record is dropped (values preserved in `⟵ ⟶` form).
- **D3 — gate DEBUG behind existing `libLogs`.** `libLogs=false` drops INFO **and** DEBUG;
  WARN/ERROR always pass. No new toggle.
- **D-scope — loki-core + revoman-root, but NO double-resolve fix.** Gating DEBUG hides the flood and
  its duplicate. The genuine double template pass in `RegexReplacer.kt` is a **separate follow-up**
  (perf/correctness), out of scope here.
- **D-grammar — enhanced single-spine** (every inner line `│ `-prefixed, 3-level TUI hierarchy),
  not a verbatim library-mirror (which left bodies flush-left).
- **D-parity — fix BOTH sinks by construction.** The renderer lives in the library; both sinks call
  it, so they cannot re-diverge. Library console sink gains the enhanced grammar for free.
- **D5 — unify `StepFinished` values.** Renderer prefers `producedValues`/`consumedValues`, falls
  back to key-sets when absent. Library console sink gains values (was key-sets only).

## Repos touched

- **revoman-root** (PRIMARY): new `RunLogRenderer` + `RunLogRendererTest`; `ConsoleRunLogSink`
  delegates; `ConsoleRunLogSinkTest` assertions updated to the enhanced shape.
- **loki-core**: `PerTestRunLogSink` delegates to `RunLogRenderer`, drops the duplicated grammar,
  gains the DEBUG gate; banner legend rewritten; `PerTestRunLogSinkTest` assertions updated;
  new DEBUG-gate test.

## Build / deploy note (corrects the prior handoff)

Core compiles ReVoman **sources** via `~/core-public/core/.bazelrc-local`, so loki-core compiles
against the new `RunLogRenderer` immediately (no jar publish for compilation). BUT the running E2E
server holds **jars**, so to see the new grammar LIVE:
1. clear stale `~/code-clones/work/revoman-root/build/libs/revoman-*.jar` (the BUILD.bazel glob grabs all),
2. `gradle clean build` in revoman-root to regenerate jars,
3. restart the Core server (USER runs builds/restarts).

## TDD plan (test-first, red → green)

1. **revoman-root** `RunLogRendererTest` (NEW): assert the enhanced grammar per event —
   `gutter()` prefixes every body line with `│ `; `▸` child; `│   200 OK · 4836ms  ✔` header;
   `│ ── REQ ──` / `│ ── RESP ──` sub-rules; `∅` empty side; values line; phase rule width;
   all coarse events (`┌ ◆ … ★ UNDER TEST`, `└ ✔/✘ …`, `⚠ CONTRACT`), and the control-flow events
   (`↺`/`⊘`/`↪`/`■ STOP`/`✖ LOOP-BUDGET`).
2. **revoman-root** `ConsoleRunLogSinkTest`: rewrite assertions pinning the OLD shape
   (`│ · `, `200 OK 42ms`, `│ REQ:\n…` flush-left body, `│   ⟵ [token]  ⟶ [saId]` key-sets) to the
   new shape. `line writes nothing` and `close` tests unchanged.
3. **loki-core** `PerTestRunLogSinkTest`: rewrite assertions pinning the OLD shape
   (`"event": "finished"`, `"step": "…"`, `--- request ---\n`, `--- response ---\n`, `"httpStatus"`)
   to the new spine shape; keep the toggle-gating tests (steps off ⇒ no exchange; runbook off ⇒ no
   coarse; outcome off ⇒ no footer) — retarget their anchors. ADD a DEBUG-gate test:
   `libLogs=false` ⇒ a `line(DEBUG, …)` is dropped, `line(WARN, …)` passes.
4. **Live verify**: re-run a scheduling-perf lifecycle FTest, read
   `~/.revoman/logs/SchedulingPerfLifecycleE2ETest.<method>/latest.log`; confirm each `┌ … └` bracket
   holds a `│`-guttered REQ/RESP exchange and no `[DEBUG] {{x}} resolved` flood.

## Non-goals

- NOT changing the coarse-tree glyphs' *meaning* or the summary table (correct + shipped) — only the
  per-request rendering inside the brackets and the spine discipline.
- NOT fixing the double template resolve at source (`RegexReplacer`) — separate follow-up.
- NOT moving `renderHeaviestSteps` / perf splice to the library (Core perf feature, coupled to
  Core's `stepTimings` + banner splice; not clearly generic).
- NOT changing what data is captured — only how the already-captured req/resp is rendered and which
  narration is gated.

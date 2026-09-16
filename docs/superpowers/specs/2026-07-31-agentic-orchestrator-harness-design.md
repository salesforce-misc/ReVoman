# Agentic API-Orchestration Harness — Design

*Author: Gopal S Akshintala. Date: 2026-07-31. Status: design, pending review.*

## Purpose

Build a **local, runnable, end-to-end** agentic API-orchestration harness that puts every production concept from [`production-system-design.md`](file:///Users/gopala.akshintala/code-clones/my-github/overfullstack/life/career/gg/projects/async-orchestrator/production-system-design.md) "in action", so the design can be demoed and defended (see the companion `l6-interview-qa-prep.md`). The bias is **seeing it work over completeness**.

The one idea the whole thing rests on (from the design doc): the LLM does exactly two probabilistic jobs — **pick a graph** and **fill its input slots**. Everything downstream is deterministic ReVoman execution, testable like ordinary software. This harness makes that split literal and runnable.

## Hard constraints

1. **Local-first, no Salesforce org.** A mock HTTP CPQ target (configure / price / quote) stands in for a real org, so ReVoman graphs execute against something real.
2. **Never modify the ReVoman library.** The harness is a new, isolated Gradle module depending on the library as a normal project dependency.
3. **LLM is pluggable behind an interface.** A deterministic **STUB** impl runs in tests/CI with no API key; a **REAL** impl (Claude via koog's Anthropic client) runs the live demo, gated on `ANTHROPIC_API_KEY`. Tests never require the key.
4. **TDD throughout**, using the repo's Kotest setup.
5. **Each stage runs on its own** and demonstrates its design-doc concepts with real command output.

## Locked decisions

| Decision | Choice | Why |
|---|---|---|
| Framework for the probabilistic layer | **koog 1.1.1** (JetBrains, Kotlin-native) | Ships an Anthropic client, an OpenTelemetry GenAI feature (`invoke_agent`/`execute_tool`/`chat` spans), and structured output. Matches ReVoman's Kotlin/Gradle idiom. Stage 1 needs no framework at all. |
| Module location | **New Gradle subproject** `agentic-harness/` via `include(...)` | The same author's `ds-algo` repo already uses `include()` + `buildSrc` conventions. The library's `build.gradle.kts` and `src/` stay untouched; koog/LLM deps never leak into the published `revoman-*.jar`. |
| Workflow representation | **A graph = a real ReVoman V3 collection directory** | Mirrors `src/integrationTest/resources/pm-templates/v3/core` exactly: `.resources/definition.yaml` + ordered `*.request.yaml`, edges are `{{var}}` threading. No invented format. |
| Registry model | **Collapse to one graph registry** at v1 | One V3 collection = one graph = one LLM-selectable tool. Multi-graph intents are the router picking a *sequence* (graded as trajectory). No separate junction registry until workflows compose graphs across personas. Matches the doc's "static tool list at v1" YAGNI. |
| Graph selection | **Static tool list** | Three graphs fit in context. Retrieval (hybrid + deferred loading) is a documented later drop-in, not built now. |

## How agentic workflows are represented

Each graph is a **ReVoman V3 collection directory**, the same format the library's own core integration templates use:

- `.resources/definition.yaml` — `$kind: collection`, plus the graph's bearer auth (`token: "{{accessToken}}"`).
- one `*.request.yaml` per API — `$kind: http-request` with `url` / `method` / `headers` / `body`, an `afterResponse` `scripts` block, and an `order:` field for sequencing.
- **Edges = `{{var}}` environment-threading.** A step's `afterResponse` script does `pm.environment.set("configId", ...)`; a later step references `{{configId}}` in its URL or body. `order:` + the shared environment *is* the dependency graph. There is no `@{ref.id}` operator.

The harness graphs (`configure`, `price`, `quote`) are authored in exactly this format but point `{{baseUrl}}` at the local mock server instead of a Salesforce org, so no org and no `{{accessToken}}` round-trip is required. We mirror the *format*, not the core endpoints.

This representation is what feeds the probabilistic layer in Stage 2:

- graph/folder name + `definition.yaml` description → seed for `when_to_use`
- **unfilled `{{placeholders}}`** in request bodies/URLs → the input slots the slot-filler fills
- **`pm.environment.set(...)` keys** → the graph's outputs / internal edges — the LLM never fills these (deterministic threading; the design's core claim)
- a small hand-written OAS per endpoint → types for schema validation before execution

## Architecture

```
agentic-harness/  (new Gradle module, depends on :revoman + koog)
  Stage 1 — deterministic spine (no LLM, no koog)
    MockCpqServer         http4k in-memory server: /configure /price /quote + a mock "DB" map
    graphs/*              V3 collections (configure, price, quote) chaining via {{var}}
    GraphRunner           wraps ReVoman.revUp(Kick): Rundown; prints Rundown.toJson
    Layer1ContractTest    asserts on Rundown (all steps successful, stopReason)
  Stage 2 — tool-def gen + probabilistic layer (koog)
    ToolDefGenerator      pure fn: graph metadata + OAS -> 4-field tool def
    LlmClient (interface) StubLlmClient (deterministic) | ClaudeLlmClient (koog Anthropic, key-gated)
    Router                intent -> chosen graph
    SlotFiller            fill placeholders, validate vs schema BEFORE revUp
    Orchestrator          route -> slot-fill -> revUp -> Rundown-as-context
  Stage 3 — evals + calibration
    EvalSet               labeled utterance -> expected graph
    ConfusionMatrix       run router over set, print matrix, show a miss move after a fix
    BfclCheck             predicted slot-fill vs gold
    TauBenchCheck         assert final mock-DB state
    LlmJudge              fuzzy metric + deterministic ground-truth alongside
  Stage 4 — observability + flywheel
    Telemetry             koog OpenTelemetry feature -> GenAI spans to console/collector
    ConfirmGate           confirm/edit/reject -> positive/correction-pair/negative labels
    NightlyBatch          append confirmed turns to eval set; draft when_not_to_use from confusion pairs
```

### Concept-to-component map

| # | Design-doc concept | Component | Stage |
|---|---|---|---|
| 1 | Deterministic worker | `MockCpqServer` + V3 graphs chaining via `{{var}}` | 1 |
| 2 | `revUp(Kick): Rundown` | `GraphRunner` | 1 |
| 3 | Evals Layer 1 (deterministic contract test) | `Layer1ContractTest` asserts on `Rundown` | 1 |
| 4 | Tool-def auto-generation (4 fields) | `ToolDefGenerator` (pure, unit-tested) | 2 |
| 5 | Router + slot-filler, schema validation | `Router`, `SlotFiller`, `LlmClient` | 2 |
| 6 | Orchestrator-workers loop | `Orchestrator` | 2 |
| 7 | Confusion matrix / prompt calibration | `ConfusionMatrix` eval runner | 3 |
| 8 | BFCL + tau-bench idioms | `BfclCheck`, `TauBenchCheck` | 3 |
| 9 | LLM-as-judge, bounded | `LlmJudge` + deterministic ground-truth | 3 |
| 10 | OTel GenAI spans | `Telemetry` (koog OpenTelemetry) | 4 |
| 11 | HITL confirm gate + feedback flywheel | `ConfirmGate`, `NightlyBatch` | 4 |

## Stage 1 — deterministic spine (smallest runnable slice)

**Goal:** the deterministic worker runs end-to-end, no LLM, no koog. This is the "evals Layer 1" beat.

**Components:**

1. **`MockCpqServer`** — http4k (already a `:revoman` `api` dependency, so no new deps for this stage). Three endpoints backed by an in-memory `MutableMap` "DB" (Stage 3's tau-bench asserts against it):
   - `POST /configure` → `{ "configId": "..." }`
   - `POST /price` (reads `configId`) → `{ "priceId": "...", "total": <n> }`
   - `POST /quote` (reads `priceId`) → `{ "quoteId": "...", "status": "DRAFT" }`
2. **V3 graph collections** — `configure`, `price`, `quote`, authored in the V3 format above, pointing at the mock server. Chaining is pure `{{var}}` threading: `configure` sets `{{configId}}`, `price` consumes it and sets `{{priceId}}`, `quote` consumes that.
3. **`GraphRunner`** — builds `Kick.configure().templatePath(...).environmentPath(...)...off()`, calls `ReVoman.revUp(kick)`, returns the `Rundown`, and prints `rundown.toJson(SUMMARY)`.
4. **`Layer1ContractTest`** (Kotest) — asserts `rundown.firstUnIgnoredUnsuccessfulStepReport() == null` (the library's confirmed happy-path check) and verifies the `stopReason` value. (The design doc names `stopReason == COMPLETED`; the exact enum symbol will be confirmed against the library API during implementation rather than assumed.)

**Runnable proof:** a `main()` that boots the mock server, runs the three-graph chain, and prints the `Rundown`; plus `./gradlew :agentic-harness:test` green.

## Stage 2 — tool-def generation + probabilistic layer

**`ToolDefGenerator`** (pure function, unit-tested) reads a graph's `definition.yaml` + `*.request.yaml` files + a small hand-written OAS and produces the four fields the design mandates:

- `when_to_use` — intents this graph serves (seeded from name + description)
- `when_not_to_use` — near-miss intents that belong elsewhere (the field that stops `configure`/`price` confusion; grown in Stage 3)
- `example_queries` — three to five real utterances
- `input_examples` — filled parameter examples (Anthropic's 72%→90% lever)

**`LlmClient` interface** — one method surface for both jobs (route, slot-fill). Two impls:

- `StubLlmClient` — deterministic keyword/rule routing and slot extraction; no network, no key; the default in tests/CI.
- `ClaudeLlmClient` — koog Anthropic client; constructed only when `ANTHROPIC_API_KEY` is present; used for the live demo.

**`Router`** turns intent into a chosen graph. **`SlotFiller`** fills the graph's unfilled `{{placeholders}}` and **validates every argument against the OAS schema before ReVoman fires** — hallucinated/malformed args are rejected at the door (the design's runtime hallucination guard).

**`Orchestrator`** wires the loop: `route → slot-fill → GraphRunner.revUp → Rundown.toJson back as context`. This is the orchestrator-workers pattern made literal.

## Stage 3 — evals + calibration in action

- **`EvalSet`** — a labeled `utterance → expected graph` dataset.
- **`ConfusionMatrix`** — run the router over the set, compute graph-selection accuracy, print a confusion matrix. Demo beat: surface an off-diagonal miss, add a `when_not_to_use` clause, re-run, show the number move. That is prompt calibration, live.
- **`BfclCheck`** — BFCL-style comparison of the predicted slot-fill call against gold.
- **`TauBenchCheck`** — tau-bench-style assertion on the final mock-DB state (task success, not transcript).
- **`LlmJudge`** — LLM-as-judge on one fuzzy metric (e.g. response helpfulness), with a deterministic ground-truth check alongside to show the judge only screens.

## Stage 4 — observability + feedback flywheel

- **`Telemetry`** — koog's OpenTelemetry feature emits GenAI-convention spans (`invoke_agent` per turn, `execute_tool` per graph run nesting Rundown stats, `chat` per LLM call with prompt version) to the console or a local collector.
- **`ConfirmGate`** — simulates the HITL confirm gate on the one write (`quote`): confirm → positive label; edit-then-confirm → correction pair; reject → negative label.
- **`NightlyBatch`** — a function that appends confirmed turns to the eval set and drafts `when_not_to_use` clauses from confusion pairs, demonstrating the CI gate tightening itself over time.

## Testing strategy

- **TDD per component**, Kotest (the repo convention), in `agentic-harness/src/test`.
- Stage 1 is fully deterministic — no LLM, no key.
- Stages 2–4 default to `StubLlmClient`, so the whole suite runs in CI with no `ANTHROPIC_API_KEY`. The real Claude path is exercised only in a manual/live demo run when the key is present, and is skipped (not failed) otherwise.
- The mock server binds an ephemeral port per test to keep runs isolated and parallel-safe.

## Explicitly out of scope (YAGNI for this harness)

Retrieval-based selection, code-mode, real OAuth / RFC 8693 token exchange, ReBAC, Einstein Trust Layer, fine-tuning/DPO, Prompt Builder, multi-provider cross-session context, and load-testing ReVoman concurrency. Each is a documented later phase in the design doc; none is needed to prove the thesis locally. The registry is structured so retrieval can drop in without a rewrite, per the design.

## Runnable proof per stage

| Stage | Command | What it shows |
|---|---|---|
| 1 | `./gradlew :agentic-harness:test` + a `main()` run | Mock server up; three graphs chain via `{{var}}`; `Rundown` printed; Layer-1 contract green |
| 2 | orchestrator `main()` (stub) + `./gradlew :agentic-harness:test` | intent → graph → slots (schema-validated) → `revUp` → Rundown context |
| 3 | eval runner `main()` | printed confusion matrix; a miss moving after a `when_not_to_use` fix; BFCL + tau-bench checks |
| 4 | demo `main()` (optionally with key + collector) | OTel spans on console; confirm-gate labels; nightly-batch appends + drafts |

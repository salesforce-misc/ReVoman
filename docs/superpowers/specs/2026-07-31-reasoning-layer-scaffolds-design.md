# Reasoning-Layer Scaffolds — Design (Delta on `agentic-harness`)

*Author: Gopal S Akshintala. Date: 2026-07-31. Status: design, pending review. Companion to `reasoning-layer.md`, `production-system-design.md`, `industry-scaffolding-research.md`.*

## Purpose

Complete the **reasoning layer** — the one probabilistic part of the agentic API-orchestration system — as the six reliability scaffolds named in `reasoning-layer.md`. Stages 1–4 of the `agentic-harness` module already built ~60% of these (tool-def generator, slot-filler + schema validation, router, eval set + confusion matrix, confirm-gate labels, OTel spans). This spec is the **delta**: the genuinely-missing scaffolds, built on the existing module, so the layer is complete and every reliability control is runnable and measurable locally.

The layer's contract (from `reasoning-layer.md`): map `(user intent + API-graph descriptions) → {chosen graph, filled slots}` **reliably**, or — when uncertain — **ask instead of guess**. MCP is transport; this reasoning is the owned layer.

## What already exists (reuse as-is, all green)

| Scaffold | Component (built) |
|---|---|
| 1. Tool-def generator (4-field) | `tooldef.ToolDefGenerator` (+ `GraphOas`, `GraphMetadataParser`) |
| 3. Slot-filler, validate-before-execute | `orchestrator.SlotFiller` → `FillResult.Valid/Invalid` |
| 7. Eval set + confusion matrix | `eval.RouterEvaluator`, `eval.ConfusionMatrix`, `eval.EvalSet` |
| 8a. Slot-fill valid rate (BFCL) | `eval.BfclCheck` |
| Router (base) | `orchestrator.Router` + `llm.ScoringLlmClient` (calibration-aware, deterministic) |
| Confirm-gate labels | `feedback.ConfirmGate` (confirm/edit/reject → labels) |
| Deterministic execution | `GraphRunner.runChain` → `ReVoman.revUp` → `Rundown` |
| Tracing | `telemetry.GenAiTracer` (invoke_agent/chat/execute_tool) |

## What this spec builds (the delta)

| # | Scaffold (reasoning-layer.md) | New component |
|---|---|---|
| 2 | Router returns `{graph_id, confidence}` | add `confidence`/`margin` to `RouteDecision`; `ScoringLlmClient` exposes per-graph scores |
| 4 | Retrieval pre-filter (top-K) | `retrieval.RetrievalPreFilter` (embedding stub; no-op at 3, seam proven at 10) |
| 5 | Confidence / disambiguation gate (**the core**) | `reasoning.ConfidencePolicy` + `reasoning.DisambiguationGate` → ask-don't-guess |
| 6 | Confirm gate returns a preview object | `reasoning.ActionPreview` + confirm-gate wiring (preview, execute only on confirm) |
| — | End-to-end wiring | `reasoning.ReasoningLayer.handle(intent): ReasoningOutcome` |
| 8b | Low-margin → ask (not guess) test | test in `DisambiguationGateTest` / `ReasoningLayerTest` |
| — | Native strict tool-use (real client) | `ClaudeLlmClient` sends OAS as Anthropic tool-use `input_schema` (claude source set, key-gated) |

## Hard constraints

- **Local-first, no Salesforce org.** Mock LLM + the 3 existing V3 graphs (configure / price / quote).
- **LLM behind the existing `LlmClient` interface.** Deterministic STUB path (keyword/score, no key) drives all tests/CI. Real Claude path (koog, `ANTHROPIC_API_KEY`) stays in the isolated `claude` source set the default `test`/`build`/`check` never compiles. **Tests never require the key.**
- **Structured output = "constrained decoding + validate-before-execute", implemented directly** (no Python libs). Real client uses Anthropic native tool-use `input_schema`; hand-rolled JSON-schema validation against the OAS runs on both paths before execute (the existing `SlotFiller` validation is that check, reused).
- **Never modify the ReVoman library.** All work inside `agentic-harness/`.
- **TDD throughout.** Every scaffold gets a failing test first. Stages 1–4 stay green after every change.
- No koog / kotlinx.coroutines / OTel-SDK dependency in `src/main` or `src/test`.

## Design decisions (locked)

1. **Build mode: delta on the existing `agentic-harness` module.** Reuse the 60% already green; no duplicate tool-def gen / slot-filler / eval harness.
2. **Confidence source: score-margin from `ScoringLlmClient`.** `confidence` = normalized top score; `margin` = (top1 − top2) normalized. Deterministic, already computed, and the gate triggers when `margin < threshold`. (Note: the client's `when_not_to_use` penalty requires ≥2 matching trigger tokens — an existing hardening; the confidence work only *reads* the resulting scores, it does not change scoring.)
3. **Structured output: real client uses Anthropic tool-use `input_schema`; stub stays keyword/regex.** Validation-before-execute applies uniformly via the existing schema check.

## Architecture

```
agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/
  llm/
    LlmClient.kt            RouteDecision gains: confidence: Double, margin: Double (defaults keep old callers compiling)
    ScoringLlmClient.kt     add fun scores(utterance, tools): Map<String, Int>; route() fills confidence+margin
  retrieval/
    RetrievalPreFilter.kt   topK(intent, tools, k): List<ToolDef> — token-overlap cosine stub; k>=size => all (no-op)
  reasoning/
    ConfidencePolicy.kt     thresholds (per-graph + default), writeGraphs set; writeGraphs default 0.90
    ActionPreview.kt        data class: graph, slots, chain (graphs to run), isWrite
    ReasoningOutcome.kt     sealed: NoMatch | Clarify | ConfirmRequired | Proceed
    DisambiguationGate.kt   decide(routeDecision, fill, policy): ReasoningOutcome  (ask-don't-guess)
    ReasoningLayer.kt       handle(intent): ReasoningOutcome — [retrieve]->route->fill->gate; confirm(preview)->execute
  Stage5Demo.kt             runnable: 4 utterances (proceed / confirm / ask / no-match) + trace + confusion matrix
  (claude source set)
    ClaudeLlmClient.kt      add native Anthropic tool-use input_schema for slot-fill; validate before return
```

### Data flow

```
intent
  -> RetrievalPreFilter.topK(intent, allTools, K)          # narrows N->K (no-op at 3)
  -> Router.route(intent, candidates) : RouteDecision{graph, confidence, margin, rationale}
  -> (graph == null)                    => ReasoningOutcome.NoMatch
  -> (margin < policy.threshold(graph)) => ReasoningOutcome.Clarify(question, top-2 candidates)
  -> SlotFiller.fill(intent, tool)
       is Invalid                       => ReasoningOutcome.Clarify(question about missing/invalid slot)
       is Valid ->
         (graph in writeGraphs)         => ReasoningOutcome.ConfirmRequired(ActionPreview)   # no execution yet
         else (read graph)              => ReasoningOutcome.Proceed(graph, slots)            # caller executes
  # confirm path:
  ReasoningLayer.confirm(preview, baseUrl) -> GraphRunner.runChain(baseUrl, preview.chain, preview.slots) -> Rundown
```

### `ReasoningOutcome` (sealed)

- `NoMatch(intent)` — router returned no graph.
- `Clarify(question, candidates)` — top-2 within margin, OR a required slot missing/low-confidence. **The ask-don't-guess result.**
- `ConfirmRequired(preview: ActionPreview)` — write graph, confident, awaiting human; nothing executed.
- `Proceed(graph, slots)` — read graph, confident; safe to auto-execute.

### `ConfidencePolicy`

`ConfidencePolicy(defaultThreshold: Double = 0.60, perGraphThreshold: Map<String, Double> = emptyMap(), writeGraphs: Set<String> = setOf("quote"))`. `fun threshold(graph): Double`. Write-graph default **0.90** (financial-services band, `industry-scaffolding-research.md` §5). Tunable: the demo shows lowering `quote`'s threshold changes ask-vs-confirm behavior.

### `ActionPreview`

`data class ActionPreview(val graph: String, val slots: Map<String, String>, val chain: List<String>, val isWrite: Boolean)`. Rendered for the human at the confirm gate; carries exactly what `GraphRunner.runChain` needs, so `confirm()` executes without re-deriving anything.

## Retrieval pre-filter (scaffold 4)

`object RetrievalPreFilter { fun topK(intent: String, tools: List<ToolDef>, k: Int): List<ToolDef> }`. Embedding stub = bag-of-tokens cosine similarity between the intent and each tool's `whenToUse + exampleQueries`. Returns the top-`k` by similarity; when `k >= tools.size` returns all (the **no-op at 3 graphs**, seam intact). Unit test proves it narrows a synthetic 10-graph set to the relevant 3.

## Native structured output (real client, scaffold 3/4 pattern)

In the `claude` source set only: `ClaudeLlmClient.fillSlots` builds an Anthropic tool-use `tool` whose `input_schema` is the graph's OAS (slot names, types, enums) and calls the model with `tool_choice` forcing that tool, so the model returns strict structured arguments (constrained decoding). The returned args then go through the **same** hand-rolled JSON-schema validation the stub path uses (`SlotFiller` over the `SlotSchema`) before execution — validate-before-execute, uniform across paths. This stays key-gated and CI-isolated; no automated test depends on it.

## Measurable proof (scaffolds 7 + 8, extended)

- **Graph-selection accuracy + confusion matrix** — already runnable (Stage 3 `runStage3Demo`), unchanged.
- **Calibration live** — off-diagonal miss → `when_not_to_use` clause → 6/7 → 7/7, already proven.
- **Slot-fill schema-valid rate** — `BfclCheck`, already runnable.
- **NEW — low-margin → ask, not guess** — a test: an utterance whose top-2 graphs fall within the margin yields `ReasoningOutcome.Clarify`, and the mock DB stays empty (nothing executed). This is the single most important reliability rule, made a passing test.
- **NEW — write-graph → confirm, not execute** — a `quote` (write) intent yields `ConfirmRequired(preview)` with an empty DB; only `confirm()` executes.

## Testing strategy

- TDD per scaffold, JUnit5 + Google Truth, in `agentic-harness/src/test`.
- All new scaffolds are deterministic and koog-free — full suite runs in CI with no key.
- `Orchestrator` (Stage 4) is left intact; `ReasoningLayer` is the new richer entry point that adds retrieval + the confidence gate around the same Router/SlotFiller. (The Stage-2 `Orchestrator` remains for the orchestrator-workers demos; `ReasoningLayer` is the reasoning-layer-complete entry point.)
- Each new component is independently testable: retrieval, confidence math, gate decisions (table of cases), preview shape, end-to-end wiring.

## Out of scope (YAGNI)

Real embeddings/vector store (stub cosine suffices to prove the seam), reranking, multi-turn clarification dialogue state (the `Clarify` outcome is returned; managing the follow-up turn is the host's job per `reasoning-layer.md` open questions), production OTLP export, and any change to the ReVoman library.

## Runnable proof

| What | Command |
|---|---|
| Full suite green (all stages + new scaffolds), no key | `./gradlew :agentic-harness:test` |
| Reasoning-layer demo: proceed / confirm-preview / **ask** / no-match, traced | `./gradlew :agentic-harness:runStage5Demo -q` |
| Confusion-matrix calibration (existing) | `./gradlew :agentic-harness:runStage3Demo -q` |
| Real Claude structured tool-use (key-gated, isolated) | `./gradlew :agentic-harness:compileClaudeKotlin` (+ `claudeDemo` with key) |

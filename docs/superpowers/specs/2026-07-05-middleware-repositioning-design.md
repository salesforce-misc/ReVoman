# ReṼoman Repositioning: Middleware Between Agents and API-Graphs

**Date:** 2026-07-05
**Scope:** Documentation only (no code/API changes)
**Status:** Design approved, pending spec review

## 1. Goal

Reposition ReṼoman's top-level identity from *"an API Orchestration Engine / API automation tool"* to
**the middleware between an agent and your API-Graphs**.

Orchestration becomes the *mechanism* (how the middleware executes), and API test automation
becomes an *application* (in fact, the proof the substrate ships today) — not the identity.

## 2. The Identity (the one sentence)

> ReṼoman is the **middleware between an agent and your API-Graphs** — it takes an API-Graph
> in, executes it deterministically on the JVM, and hands back a `Rundown` the agent reasons over.

Load-bearing definitions that keep this honest **today**:

- **"Agent" = any caller acting as one** — an AI agent, *or* your own JVM/test code. Test
  automation is simply "you are the agent." This unifies every use under one identity without
  making the headline aspirational.
- **"API-Graph" = the unit** — one workflow of interdependent API calls (executable API
  metadata), exactly as defined in existing docs. No broadening of this term.

## 3. The Honesty Line (non-negotiable)

The single most important constraint. The docs today deliberately fence the agentic story as
"roadmap, not shipped." Making middleware the headline must NOT erode that.

- **Ships today = the middleware *substrate*:** API-Graph in → deterministic execution on the
  JVM → `Rundown` (= Context Information) out. Real, shipped, provable via `Rundown.toJson()`.
- **Roadmap = the AI-agent *glue*:** MCP tool-definition autogen (from OAS + API-Graph metadata),
  dynamic coupling of agentic workflows to API-Graphs at runtime. These make an *AI* agent a
  first-class caller with less wiring — but the substrate does not wait on them.
- **Why the headline is honest:** test code is the caller-acting-as-agent that proves the
  substrate runs today; the AI agent is the *same port*, with glue pending. "Middleware between
  AI agents and API-Graphs" therefore describes a role that is real now, not a promise.

Guardrails applied everywhere:

1. "Middleware" claims only the substrate. Never claims ReṼoman chooses/decides which graph runs.
2. Every AI-agent-glue mention stays explicitly roadmap-fenced. Do not soften existing disclaimers.
3. The verb stays **"executes the graph"** — never "orchestrates" / "decides." The agent
   orchestrates (chooses & couples graphs); ReṼoman executes a given graph.
4. Wherever "AI agent" leads a sentence, pair it with "— an AI agent, or your own JVM code acting
   as one —" so the claim is true in the present tense.

## 4. Per-File Changes

### A. `docs/modules/ROOT/pages/index.adoc` (landing hero) — biggest lift

- **Eyebrow:** `API Orchestration Engine · JVM` → `Middleware · Agent ↔ API-Graph · JVM`.
- **Title:** keep `Execute your API graph. / Get back a Rundown.` (still true).
- **Lede:** re-lead with the middleware role: "The middleware between an agent — an AI agent, or
  your own JVM code acting as one — and your API-Graphs. Hand it a graph; it executes
  deterministically on the JVM and hands back a `Rundown` the agent reasons over."
- **"How the engine works" band:** mechanics unchanged (graph in → Rundown out); reframe the
  framing text as "how the middleware executes."
- **App grid (3 cards):** reorder + relabel — Agentic first (tag: `the caller`), Test Automation
  second (tag: `proof it ships today`, replacing `most-used today`), Orchestrated Workflows third.
- **Ships-today / roadmap band:** rewrite so "ships today" = the middleware substrate; roadmap =
  the AI-agent glue (MCP autogen, dynamic coupling). Sharpen: substrate ≠ glue.
- **`:page-description:`** meta: rewrite to lead with the middleware identity.

### B. `docs/modules/ROOT/pages/why-revoman.adoc`

- New opening frame: the "why" is the middleware role (an agent needs deterministic API execution
  plus structured context). The REST-Assured / "Mul-T-verse" pain becomes *evidence for why a
  middleware layer is needed*, not the top-line story.
- Recast "A runner is not an orchestration engine" → "A runner is not middleware": Newman/Postman
  CLI emit a pass/fail report; middleware returns Context Information a caller builds on. Keep the
  JVM-native / type-safety / richer-execution-model points.

### C. `docs/modules/ROOT/pages/agentic-orchestration.adoc`

- This page already carries the middleware line (`[.lead]`, line 4). Promote it: this page becomes
  the canonical statement of the identity, no longer framed as "one application."
- Add the **caller = agent OR your code** generalization explicitly.
- Keep the "Execution vs. orchestration: staying precise" section and the roadmap fence intact —
  they are the honesty backbone.

### D. `docs/modules/ROOT/nav.adoc` + metadata

- Move `agentic-orchestration.adoc` **out of `.Applications`**, up near the top (under Overview /
  beside `why-revoman`) — it is now identity, not application.
- Test Automation + Orchestrated Workflows stay under `.Applications`.
- Propagate middleware-first `:page-description:` strings across the touched pages.

### E. `docs/modules/ROOT/pages/applications.adoc` (small touch-up, in scope)

- Line 5 currently calls agentic orchestration "*the headline*" while listing it as an application.
  Rewrite to point *up* at the identity (the agentic role now lives at the top level), rather than
  claiming the headline from within the Applications list.

## 5. Testing / Verification (docs → build + render + link integrity)

1. Docs/Antora build passes (locate the docs build task; verify no errors).
2. No broken `xref`s after the nav move — agentic page relocated; `applications.adoc` and any
   inbound links updated.
3. Grep sweep: no orphaned "API Orchestration Engine as *identity*" phrasings left contradicting
   the new headline; no present-tense claims on roadmap glue.
4. Visual spot-check: hero eyebrow/lede, app-grid order, ships/roadmap band render correctly in
   both light and dark themes.

## 6. Out of Scope (YAGNI)

- No new pages.
- No SVG redraws — existing flow/loop SVGs already fit the reframe.
- No code / API / product changes. Documentation only.
- No broadening of "API-Graph" beyond the existing single-workflow unit.

# ReṼoman Positioning Repitch — API Orchestration Engine for Agentic API Orchestration

**Date:** 2026-06-13
**Status:** Design — pending user review
**Canonical source:** Patent — *"Agentic API Orchestration through Dynamic Coupling of
Agentic-Workflows with API-Graphs"*, Gopal S Akshintala, 1 May 2025
(https://docs.google.com/document/d/1hcc7rkhe-xj5fTuBq5Sh7gDSjymFZ1GcMCCm7nyXZG8)
**Paired spec:** `2026-06-13-antora-docs-site-design.md` (the docs site that this positioning drives)

## Goal

Reposition ReṼoman's public narrative without rewriting its features. Two shifts the user asked
for:

1. **Postman is not center stage.** Postman V2/V3 is the *data format* ReṼoman reads — a neutral
   schema choice, exactly like "we use JSON over XML." ReṼoman is not "a Postman tool"; it has no
   affinity to the Postman app or ecosystem.
2. **Headline = Agentic API Orchestration.** ReṼoman is an **API Orchestration Engine** (the
   patent's "API Execution Engine"). **API test automation is one application**, not the identity.
   De-emphasize low-code testing as the lead.

This is a **reframe, not a rewrite**: every existing factual claim and feature survives; only their
ordering and framing change.

## Non-Goals

- No feature changes, no code changes. Positioning/narrative only.
- Do **not** claim format-agnosticism in the present tense (it is roadmap).
- Do **not** imply MCP tool-definition autogen or dynamic agent↔graph coupling ship today (roadmap).
- Slides/talks/patent rewording are out of scope; this governs repo docs (README + Antora pages).

## The Headline Reframe

**Current:** *"ReṼoman is an API orchestration/automation tool for JVM... think of it as Postman for
JVM... emulates the Postman Collection Runner."*

**Proposed:**

> ReṼoman is an **API Orchestration Engine** for the JVM. You describe a workflow of interdependent
> API calls as **executable API metadata**; ReṼoman executes that graph — resolving data
> dependencies between calls, handling auth, polling, and type-safe payloads — and returns a
> structured **Rundown** of everything that happened. This makes it a building block for **Agentic
> API Orchestration**, with API test automation as one powerful application.

Three moves:

1. **"API Orchestration Engine"** replaces "Postman for JVM." Names the differentiated value (graph
   orchestration, not just firing HTTP). Matches the patent's title-level framing and the existing
   "orchestration/automation" tagline.
2. **"Executable API metadata"** becomes the central noun. Postman collections demote from *identity*
   to *input format*.
3. **"Agentic API Orchestration"** is the headline use case; **testing is "one application."**

### Headline noun: chosen with eyes open

The patent draws a two-level distinction: *orchestration* = the agentic layer choosing which
API-Graph to invoke and coupling it to Nodes; *execution* = ReṼoman running a given API-Graph and
returning Context Information. Strictly, ReṼoman is the **execution** engine.

We deliberately use **"API Orchestration Engine"** as the product headline (the value a user feels is
orchestration), held precise by the sentence underneath that says ReṼoman **executes the graph**.
Marketing noun + patent-precise verb coexist. The patent's literal term "API Execution Engine" is
used when describing the agentic architecture (so the patent reader sees continuity).

## The Postman-as-Format Framing (critical, easy to get wrong)

Treat the Postman V2/V3 collection the way a service treats JSON:

- ReṼoman reads **executable API metadata** that is **serialized in the Postman V2/V3 schema**.
- A service that serializes in JSON is not "a JSON tool." ReṼoman that reads Postman-schema metadata
  is not "a Postman tool."
- **Decoupled from:** Postman the **app/SDK** — no licensed Postman SDKs, no Postman install, native
  JVM. (README already states "NO licensed Postman SDKs.")
- **Roadmap, present-tense-forbidden:** format-agnosticism. Other authoring fronts (IntelliJ HTTP
  Client, Bruno, Cucumber) feeding the same engine are **future work**. Lean on the existing README
  line: *"ReṼoman is modular, and the implementation is not coupled with any Postman related
  contracts... In the future, we can think of supporting more template formats."*

**"Why Postman format?"** section is kept but reframed: the format is a pragmatic, proven, graph-like
serialization with a mature authoring UI — chosen as a starting format, not as an allegiance.

## Reframe-Not-Rewrite Mapping

| Today's framing | Reframed as |
| --- | --- |
| "Postman for JVM" identity | Executes **executable API metadata**, today in **Postman V2/V3 format** (a JSON-over-XML-style schema choice); decoupled from Postman the app/SDK (native JVM, no install). Format-agnostic is **roadmap**. |
| "Postman Collection Runner emulation" | Deterministic execution of an API-Graph on the JVM. |
| `toJson()` "useful for AI agents via MCP" (buried in Rundown section) | **Promoted to headline capability**: the `Rundown`/`toJson()` IS the patent's "Context Information" an agentic Node consumes. |
| Low-code testing = the lead USP | Demoted to **"Application: API Test Automation"** — intact, strong, no longer the identity. |
| "Why not Newman/Postman CLI?" | Kept, reframed: not "a better Postman runner" → "a Postman runner cannot be an orchestration engine inside your JVM/agentic stack." |
| Variables / hooks / mutable env / polling | Reframed as **orchestration primitives** (dependency-passing and control between graph steps), not just "Postman script conveniences." |

Nothing factual is deleted. The testing USPs (≈89% less code, low learning curve, CI/CD
integrability, VCS-resident collections) all survive under "Application: Test Automation."

## New Section: "ReṼoman as an Agentic Orchestration building block"

A new top-level section that explains the loop:

```
executable API metadata (API-Graph)
        │
        ▼
   ReṼoman executes the graph  ──▶  Rundown / toJson(VERBOSITY)  =  "Context Information"
        │ (resolves deps, auth, polling, type-safe payloads)            │
        ▼                                                               ▼
   deterministic, JVM-native                              consumed by an agentic-workflow Node
```

- Grounded in the **real** `toJson(VERBOSE)` output, which already emits the `stepReports` /
  `mutableEnv` / `stats` structure the patent's "Context information returned for the Agentic
  workflow" sample shows. This is a **proof point that ships today**, not aspiration.
- Light use of the patent's restaurant-kitchen analogy (API-Graph = pre-planned menu set; ReṼoman =
  the kitchen manager orchestrating teams and returning a summary to the head chef).
- A clearly-labeled **Roadmap** subsection: MCP tool-definition autogen from OAS + API-Graph
  metadata; dynamic coupling of agentic workflows to API-Graphs. Marked as where it's heading.

## Reframed "Applications" Section

Replaces the single-minded testing pitch. Ordered:

1. **Agentic API Orchestration** (headline). Today: engine + Context Information (`toJson`). Roadmap:
   MCP tool-def autogen, dynamic agent↔graph coupling.
2. **API Test Automation** (the current low-code/USP material, intact, repositioned as an
   application — this is where the 89%-less-code, learning-curve, CI/CD, persona-based-testing
   content lives).
3. **Orchestrated workflows / test-data setup** (already hinted in the current USP section).

## Today-vs-Roadmap Honesty Rule

A single NOTE/CAUTION admonition near the top, and discipline throughout:

- **Claim as today:** API Orchestration Engine; executable API metadata in Postman V2/V3 format;
  deterministic JVM execution; `Rundown`/`toJson` Context Information; type safety, hooks, polling,
  timing, v2/v3, PM script APIs.
- **Frame as roadmap (never present-tense):** format-agnosticism; MCP tool-definition autogen;
  dynamic agentic-workflow↔API-graph coupling.

## Testing / Verification (for a narrative spec)

- Cross-check every reframed claim against the live README and verified code (e.g. `toJson` verbosity
  levels, `StepReport` fields) so no claim outruns the implementation.
- Self-review pass: grep the final docs for present-tense "format-agnostic", "any format",
  "auto-generates MCP" — these must not appear as shipped claims.

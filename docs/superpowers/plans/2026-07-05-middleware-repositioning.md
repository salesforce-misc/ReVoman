# Middleware Repositioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reposition ReṼoman's documentation so its top-level identity is *"the middleware between an agent and your API-Graphs,"* with orchestration demoted to mechanism and API test automation demoted to an application.

**Architecture:** Documentation-only change across 5 AsciiDoc pages + 1 nav file in `docs/modules/ROOT/`. No code, no SVG redraws, no new pages. Each task edits one file with concrete before→after copy, verified by grep sweeps and an Antora build.

**Tech Stack:** AsciiDoc, Antora 3.1 (`npx antora antora-playbook.yml`), custom `home` layout in `docs/supplemental-ui/`.

## Global Constraints

Copied verbatim from the spec (`docs/superpowers/specs/2026-07-05-middleware-repositioning-design.md`) — every task's requirements implicitly include these:

- **Identity sentence:** "ReṼoman is the **middleware between an agent and your API-Graphs** — it takes an API-Graph in, executes it deterministically on the JVM, and hands back a `Rundown` the agent reasons over."
- **"Agent" = any caller acting as one** — an AI agent, *or* your own JVM/test code. Wherever "AI agent" leads a sentence, pair it with "— an AI agent, or your own JVM code acting as one —".
- **"API-Graph" = the unit** (one workflow of interdependent calls). Do NOT broaden this term.
- **Ships today = the middleware *substrate*** (API-Graph in → deterministic JVM execution → `Rundown`/Context Information out). **Roadmap = AI-agent *glue*** (MCP tool-def autogen, dynamic coupling). Never soften existing roadmap fences; never claim glue in the present tense.
- **Verb discipline:** ReṼoman **"executes the graph"** — never "orchestrates"/"decides." The agent orchestrates (chooses & couples graphs); ReṼoman executes a given graph.
- **Docs-only.** No code/API/product changes. No new pages. No SVG redraws.

## Build & Verify Notes

- Antora reads from git **`HEAD`** (`antora-playbook.yml` → `branches: HEAD`), so **content must be committed before it renders**. Commit each task, then build.
- Build command: `npx antora antora-playbook.yml` → output in `./build/site`. Zero errors/warnings about missing xref targets = pass.
- Work happens on branch `docs/middleware-repositioning` (already created; spec already committed there).

---

### Task 1: Promote `agentic-orchestration.adoc` to the canonical identity page

Do this first — it is the source of truth the other pages point at. It already carries the middleware line; this task makes it the identity statement and adds the caller generalization.

**Files:**
- Modify: `docs/modules/ROOT/pages/agentic-orchestration.adoc`

**Interfaces:**
- Produces: the canonical identity phrasing and the "caller = agent OR your code" generalization that Tasks 2–5 reference. Anchor/xref target `agentic-orchestration.adoc` stays the same filename (nav moves it in Task 5, but the file is not renamed).

- [ ] **Step 1: Update the `[.lead]` and page-description to state the identity**

Replace the current lead (line ~3-4):

```asciidoc
[.lead]
The middleware an AI agent calls to execute an API-Graph — and get back the Context Information it reasons over.
```

with:

```asciidoc
:page-description: ReṼoman is the middleware between an agent — an AI agent, or your own JVM code acting as one — and your API-Graphs: it executes a given API-Graph deterministically on the JVM and returns the Rundown (Context Information) the caller reasons over.

[.lead]
ReṼoman is the *middleware between an agent and your API-Graphs*. Hand it an API-Graph; it executes that graph deterministically on the JVM and hands back the `Rundown` — the Context Information the agent reasons over.
```

- [ ] **Step 2: Add the "caller = agent OR your code" generalization**

Immediately after the opening paragraph that begins `ReṼoman is an *API Orchestration Engine*:` (line ~6), insert a new short subsection:

```asciidoc
== Who the agent is

"Agent" here is *any caller that reasons over the Rundown to decide what runs next* — an AI-agent Node, or your own JVM/test code acting as one. When your JVM code feeds ReṼoman an API-Graph and asserts on the returned Rundown, you *are* the agent; the same substrate serves an AI-agent Node. This is why the middleware role is real today, not aspirational: the caller port ships now, and the AI-agent conveniences (see Roadmap) are glue on top of it.
```

- [ ] **Step 3: Keep the honesty backbone intact — verify, do not edit**

Confirm these sections still read verbatim (they are the honesty backbone — do NOT weaken): "This ships today, not aspiration", "Execution vs. orchestration: staying precise", "Roadmap". Leave them unchanged.

- [ ] **Step 4: Grep-verify verb discipline on this page**

Run: `grep -nE "orchestrat|decides" docs/modules/ROOT/pages/agentic-orchestration.adoc`
Expected: every hit is either (a) describing the *agent's* orchestration, or (b) the "Execution vs. orchestration" precision section. No line claims ReṼoman orchestrates/decides.

- [ ] **Step 5: Commit**

```bash
git add docs/modules/ROOT/pages/agentic-orchestration.adoc
git commit -m "docs: promote agentic-orchestration to canonical middleware identity page"
```

---

### Task 2: Reframe the landing hero (`index.adoc`)

**Files:**
- Modify: `docs/modules/ROOT/pages/index.adoc`

**Interfaces:**
- Consumes: identity phrasing from Task 1.
- Produces: the front-door copy. No new CSS classes — reuse existing `.rv-eyebrow`, `.rv-hero__lede`, `.rv-appcard__tag`, `.rv-honest__*`.

- [ ] **Step 1: Rewrite `:page-description:` (line 3)**

Replace:

```asciidoc
:page-description: ReṼoman is an API Orchestration Engine for the JVM — execute a graph of interdependent API calls described as executable API metadata, and get back a structured Rundown. The execution layer beneath Agentic API Orchestration.
```

with:

```asciidoc
:page-description: ReṼoman is the middleware between an agent and your API-Graphs — hand it an API-Graph, it executes deterministically on the JVM and returns a structured Rundown the agent reasons over. Orchestration is the mechanism; JVM API test automation is one application.
```

- [ ] **Step 2: Rewrite the eyebrow (line ~28)**

Replace `<p class="rv-eyebrow"><span class="rv-eyebrow__dot"></span>API Orchestration Engine · JVM</p>`
with `<p class="rv-eyebrow"><span class="rv-eyebrow__dot"></span>Middleware · Agent ↔ API-Graph · JVM</p>`

- [ ] **Step 3: Rewrite the hero lede (line ~30-35)**

Replace the `<p class="rv-hero__lede">…</p>` block with:

```html
    <p class="rv-hero__lede">
      The <strong>middleware between an agent and your API-Graphs</strong> — an AI agent, or your own JVM code acting as one.
      Describe a workflow of interdependent API calls as <strong>executable API metadata</strong>; ReṼoman executes that graph
      on the JVM — resolving data dependencies, auth, and polling — and hands back a structured <a href="agentic-orchestration.html">Rundown</a>
      the agent reasons over.
    </p>
```

(Keep the `<h1 class="rv-hero__title">` unchanged — "Execute your API graph. / Get back a Rundown." is still true.)

- [ ] **Step 4: Reframe the "how the engine works" band subtitle (line ~92-96)**

Change the `<p class="rv-section-marker">// how the engine works</p>` to `<p class="rv-section-marker">// how the middleware executes</p>`. Leave the three `rv-move` steps and the flow SVG unchanged (mechanics are accurate).

- [ ] **Step 5: Reorder + relabel the app grid (line ~152-171)**

The grid already lists Agentic first — keep that order. Change tags only:
- Agentic card: tag `execution layer` → `the caller port`.
- Test Automation card: tag `most-used today` → `proof it ships today`.
- Orchestrated Workflows card: tag `setup & data` → unchanged.

- [ ] **Step 6: Sharpen the ships-today / roadmap band (line ~174-195)**

In `rv-honest__col--today`, change the heading intent to "the middleware substrate." Replace the first `<li>` with:

```html
        <li>The middleware substrate: an API-Graph in → deterministic execution on the JVM → a <code>Rundown</code> out</li>
```

In `rv-honest__col--road`, retitle the list intent to "AI-agent glue" and keep all three roadmap items. Replace the `<p class="rv-honest__foot">` text with:

```html
      <p class="rv-honest__foot">The substrate ships today; the AI-agent glue above is direction, not present-tense. ReṼoman is honest about the line.</p>
```

- [ ] **Step 7: Commit**

```bash
git add docs/modules/ROOT/pages/index.adoc
git commit -m "docs: re-lead landing hero with the middleware identity"
```

---

### Task 3: Reframe `why-revoman.adoc`

**Files:**
- Modify: `docs/modules/ROOT/pages/why-revoman.adoc`

**Interfaces:**
- Consumes: identity phrasing from Task 1.
- Produces: the "why a middleware layer" narrative. Keeps the REST-Assured pain as *evidence*, not headline.

- [ ] **Step 1: Add a leading frame above "The Problem"**

Immediately after the `= Why ReṼoman?` title (line 1), insert:

```asciidoc

[.lead]
An agent — an AI agent, or your own JVM code acting as one — needs two things to act on APIs: something that *executes* a workflow of interdependent calls deterministically, and *structured context* about what happened. ReṼoman is the middleware that provides both. The pain below is *why* that middleware layer is needed.
```

- [ ] **Step 2: Recast the "A runner is not an orchestration engine" heading**

Change the section heading (line ~25) from `== A runner is not an orchestration engine` to `== A runner is not middleware`.

- [ ] **Step 3: Update that section's closing sentence (line ~34)**

Replace:

```asciidoc
In short, a runner emits a pass/fail report; an orchestration engine executes an API-Graph, resolves the dependencies between steps, and returns structured Context Information your JVM code — or an agentic Node — can build on. See xref:applications.adoc[] for what that unlocks.
```

with:

```asciidoc
In short, a runner emits a pass/fail report; middleware executes an API-Graph, resolves the dependencies between steps, and returns structured Context Information the caller — your JVM code, or an agentic Node — builds on. See xref:agentic-orchestration.adoc[] for the identity in full, and xref:applications.adoc[] for what it unlocks.
```

- [ ] **Step 4: Grep-verify no stray "orchestration engine = identity" claim remains**

Run: `grep -n "orchestration engine" docs/modules/ROOT/pages/why-revoman.adoc`
Expected: no line presents "API Orchestration Engine" as the top-line *identity*. Mechanism-level mentions are fine; identity-level are not.

- [ ] **Step 5: Commit**

```bash
git add docs/modules/ROOT/pages/why-revoman.adoc
git commit -m "docs: reframe why-revoman around the middleware role"
```

---

### Task 4: Touch up `applications.adoc`

**Files:**
- Modify: `docs/modules/ROOT/pages/applications.adoc`

**Interfaces:**
- Consumes: identity now lives at top level (Task 1 + Task 5 nav move).

- [ ] **Step 1: Rewrite the opening paragraph (line 3)**

Replace:

```asciidoc
ReṼoman is an xref:index.adoc[API Orchestration Engine]. Executing a graph of API metadata and returning a structured xref:rundown.adoc[Rundown] enables several applications — each gets its own page:
```

with:

```asciidoc
ReṼoman is the xref:agentic-orchestration.adoc[middleware between an agent and your API-Graphs]. Executing a graph of API metadata and returning a structured xref:rundown.adoc[Rundown] enables several applications — each gets its own page:
```

- [ ] **Step 2: Rewrite the agentic bullet (line 5) so it points UP at the identity, not claim "the headline" from within the list**

Replace:

```asciidoc
* xref:agentic-orchestration.adoc[Agentic API Orchestration] — *the headline*. The engine executes the API-Graph deterministically on the JVM and returns the Context Information (`Rundown`/`toJson`) an agentic-workflow Node consumes.
```

with:

```asciidoc
* xref:agentic-orchestration.adoc[Agentic API Orchestration] — *this is the identity*, documented at the top level: the middleware executes the API-Graph deterministically on the JVM and returns the Context Information (`Rundown`/`toJson`) an agentic-workflow Node consumes.
```

- [ ] **Step 3: Commit**

```bash
git add docs/modules/ROOT/pages/applications.adoc
git commit -m "docs: point applications overview up at the middleware identity"
```

---

### Task 5: Move `agentic-orchestration` out of Applications in the nav

**Files:**
- Modify: `docs/modules/ROOT/nav.adoc`

**Interfaces:**
- Consumes: all prior tasks (agentic page is now identity, not application).

- [ ] **Step 1: Add agentic-orchestration near the top, remove it from Applications**

Current top (lines 1-2):

```asciidoc
.xref:index.adoc[Overview]
* xref:why-revoman.adoc[Why ReṼoman?]
```

Change to:

```asciidoc
.xref:index.adoc[Overview]
* xref:why-revoman.adoc[Why ReṼoman?]
* xref:agentic-orchestration.adoc[Middleware: Agent ↔ API-Graph]
```

Then in the `.Applications` block (lines ~23-28), delete the line:

```asciidoc
* xref:agentic-orchestration.adoc[Agentic API Orchestration]
```

- [ ] **Step 2: Grep-verify agentic-orchestration appears exactly once in nav**

Run: `grep -c "agentic-orchestration" docs/modules/ROOT/nav.adoc`
Expected: `1`

- [ ] **Step 3: Commit**

```bash
git add docs/modules/ROOT/nav.adoc
git commit -m "docs: lift agentic-orchestration out of Applications into top-level nav"
```

---

### Task 6: Full-site build + cross-page consistency sweep

**Files:**
- No edits unless the sweep finds a contradiction (then fix in the owning file and re-commit).

- [ ] **Step 1: Build the site**

Run: `npx antora antora-playbook.yml`
Expected: completes with no ERROR lines and no "target not found" / unresolved xref warnings. (Antora reads committed `HEAD` — all 5 prior commits must be in place.)

- [ ] **Step 2: Grep for orphaned identity contradictions across all pages**

Run: `grep -rn "API Orchestration Engine" docs/modules/ROOT/pages/`
Expected: remaining hits describe orchestration as *mechanism* only (e.g. inside SVG labels, the "how the middleware executes" band, why-revoman's runner comparison). No page uses it as the top-line *identity*. Fix any that do in the owning file, re-commit.

- [ ] **Step 3: Grep for present-tense roadmap-glue leaks**

Run: `grep -rniE "MCP tool.def|dynamic coupling" docs/modules/ROOT/pages/`
Expected: every hit sits under a Roadmap / "not shipped" / "direction" fence. No present-tense claim.

- [ ] **Step 4: Grep for un-paired "AI agent" lead claims**

Run: `grep -rn "AI agent" docs/modules/ROOT/pages/`
Expected: prominent/lead occurrences are paired with the "or your own JVM code acting as one" generalization. Body-level mentions inside already-fenced sections are fine.

- [ ] **Step 5: Visual spot-check the rendered landing page**

Open `build/site/revoman/index.html` in a browser. Confirm: eyebrow reads `Middleware · Agent ↔ API-Graph · JVM`; lede leads with middleware; app-grid tags updated; ships/roadmap band reads substrate-vs-glue. Toggle light/dark — both render cleanly (no unstyled/overflowing text).

- [ ] **Step 6: Final commit (if the sweep changed anything)**

```bash
git add -A docs/
git commit -m "docs: consistency sweep for middleware repositioning" || echo "nothing to commit — sweep clean"
```

---

## Self-Review

**Spec coverage:**
- §2 Identity → Task 1 (canonical page) + Task 2 (hero). ✓
- §3 Honesty line (substrate/glue, verb discipline, caller pairing) → Global Constraints + Task 1 Steps 2-4, Task 2 Step 6, Task 6 Steps 3-4. ✓
- §4A index.adoc → Task 2. ✓
- §4B why-revoman.adoc → Task 3. ✓
- §4C agentic-orchestration.adoc → Task 1. ✓
- §4D nav + metadata → Task 5 (nav) + page-description in Tasks 1 & 2. ✓
- §4E applications.adoc → Task 4. ✓
- §5 Verification (build, xref integrity, grep sweep, light/dark) → Task 6. ✓
- §6 Out of scope (no new pages/SVG/code) → honored; no task creates any. ✓

**Placeholder scan:** No TBD/TODO. Every edit shows exact before→after copy. ✓

**Type/name consistency:** Filename `agentic-orchestration.adoc` never renamed (nav label changes, target does not) — all xrefs in Tasks 3, 4 resolve. Tag strings in Task 2 match existing CSS classes (`rv-appcard__tag`, `rv-honest__foot`) verified against index.adoc. ✓

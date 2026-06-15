# ReṼoman Docs Site — Antora Migration + Content Backfill

**Date:** 2026-06-13
**Status:** Design — pending user review
**Depends on:** `2026-06-13-positioning-repitch-design.md` (positioning drives page content + framing)

## Goal

Move ReṼoman documentation from a single 999-line `README.adoc` to a multi-page **Antora** site,
while:

1. Preserving the AsciiDoc superpowers the README relies on: live `include::` of test/source code,
   `<1>` callouts, and version attribute substitution (`{revoman-version}`).
2. Re-deriving page content from the **new positioning** (API Orchestration Engine; Postman = input
   format; testing = one application).
3. Backfilling the one real doc gap: the **PM-sandbox script APIs** (`pm.test`, `pm.expect`,
   `pm.collectionVariables`, `pm.execution.setNextRequest`) and the `StepReport.pmTestAssertions` /
   `.nextRequest` fields shipped in PR #365.
4. Keeping the repo **agent-friendly**: focused one-concept-per-file pages, semantic filenames, a
   thin README that doubles as an agent signpost, and built HTML kept off `master`.

## Why Antora (decision record)

- Raw GitHub rendering of split `.adoc` files **breaks `include::`** (GitHub does not resolve include
  directives across files). That would kill the live-code property — a regression. Rejected.
- Antora runs a real Asciidoctor build, so `include::`, callouts, and attrs all resolve; it adds nav,
  full-text search, and (later) versioned docs. Chosen.
- The one cost: a Node-based build (`@antora/cli` + site-generator via `npx`) and a GitHub Actions
  deploy. Accepted.

## Critical mechanism: live `include::` survives via `example$` symlinks

Antora sandboxes `include::` to a module's `examples/` resources; it will not pull arbitrary
filesystem paths. Antora's **documented** pattern for code living elsewhere in the repo is a
symlink into `examples/`:

```
docs/modules/ROOT/examples/it    -> ../../../../src/integrationTest/java
docs/modules/ROOT/examples/test  -> ../../../../src/test/java
docs/modules/ROOT/examples/main  -> ../../../../src/main/kotlin
```

Pages then include live, test-verified code by tag:

```asciidoc
include::example$it/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java[tag=revoman-simple-demo]
```

Callouts (`<1>`) and `{revoman-version}` substitution work unchanged. Symlinks need
`git config core.symlinks true` on clone (default on macOS/Linux; flag for Windows contributors).

## Repo Layout

```
ReVoman/
├── README.adoc                      ← shrinks to landing page + agent signpost
├── antora-playbook.yml              ← build config (content sources, output, UI bundle)
├── docs/
│   ├── antora.yml                   ← component descriptor (name: revoman, version: current, nav)
│   └── modules/ROOT/
│       ├── nav.adoc                 ← left-sidebar nav tree (5 groups)
│       ├── pages/*.adoc             ← the split content (see page map)
│       ├── images/                  ← docs/images/* moved here
│       └── examples/
│           ├── it   -> ../../../../src/integrationTest/java   (symlink)
│           ├── test -> ../../../../src/test/java              (symlink)
│           └── main -> ../../../../src/main/kotlin            (symlink)
└── .github/workflows/docs.yml       ← build Antora, deploy to gh-pages on push to master
```

- **Build locally:** `npx antora antora-playbook.yml` → `build/site/`, open `index.html`.
- **Toolchain:** `npx`, no committed `node_modules`, no Gradle coupling.
- **Hosting:** GitHub Pages via Actions; URL `salesforce-misc.github.io/ReVoman`.
- **Versioning:** single `current` version now; real version branches deferred (fast-moving 0.9.x).
- **Built HTML never committed to `master`** — Actions deploys to `gh-pages` only. Keeps `master`
  grep-clean for agents.

## Page Map (re-derived from the new pitch)

One concept/feature per file. **Overview pages lead with the orchestration-engine framing, not
Postman.**

| Page | From README section(s) | Nav group | Framing notes |
| --- | --- | --- | --- |
| `index.adoc` | new headline, hybrid-tool, principle | Overview | Leads "API Orchestration Engine for JVM"; executable-API-metadata; agentic building block; testing = one application |
| `why-revoman.adoc` | Why ReṼoman (Problem/Solution), Newman comparison | Overview | Newman section reframed: runner ≠ orchestration engine |
| `getting-started.adoc` | Artifact, Simple Example | Get Started | `{revoman-version}` attrs; `include::` the RestfulAPIDev demo by tag |
| `configuration.adoc` | `revUp()`, Kick, Config management (override/from) | Get Started | |
| `executable-api-metadata.adoc` | Template-Driven Testing, "Why Postman Templates", v2/v3 + detection | Concepts | **Reframed**: Postman V2/V3 as a data format (JSON-over-XML analogy); no app affinity; format-agnostic = roadmap |
| `rundown.adoc` | Rundown, `toJson()`/Verbosity | Concepts | `toJson` promoted; cross-links agentic-orchestration |
| `step-procedure.adoc` | Step Procedure, Failure Hierarchy, IDE debugger view | Concepts | |
| `variables.adoc` | Variables, precedence, custom dynamic vars | Concepts | framed as orchestration dependency-passing |
| `type-safety.adoc` | Moshi marshalling, request/response config, adapters, JSON utils | Features | |
| `hooks.adoc` | pre/post hooks, step picks, response validation, custom JVM | Features | |
| `scripts-and-pm-apis.adoc` | Pre-req/Post-res scripts, npm, **+ PM-sandbox API backfill** | Features | gap fill (see below) |
| `polling.adoc` | Polling for async steps | Features | |
| `timing-metrics.adoc` | Execution timing metrics | Features | |
| `mutable-environment.adoc` | Mutable env, read-as-POJO, snapshots | Features | framed as cross-step context store |
| `execution-control.adoc` | halt/run/skip, modular executions | Features | |
| `applications.adoc` | **new** — applications overview/landing | Applications | Short landing: links the 3 applications + Performance; today-vs-roadmap NOTE |
| `agentic-orchestration.adoc` | **new** (from positioning spec) | Applications | Engine→Context Information→agent-Node loop; grounded in real `toJson(VERBOSE)`; roadmap subsection. *The headline application* |
| `application-test-automation.adoc` | USP material (low-code, learning curve, CI/CD, persona-based, VCS) | Applications | Reframed as one application, not the identity |
| `application-orchestrated-workflows.adoc` | data-setup/orchestration material | Applications | step-interleaved JVM code + mutable-env dependency passing |
| `performance.adoc` | Perf (~75-step, 122s) | Applications | standalone perf proof point |
| `troubleshooting.adoc` | Logging, FAQs | Help | |
| `contributing.adoc` | Consume-Collaborate-Contribute | Help | |

~22 pages, 6 nav groups (Overview, Get Started, Concepts, Features, Applications, Help). The
Applications group splits the formerly-single `applications.adoc` into an overview + one page per
application (Agentic Orchestration moved here from Overview) + a standalone Performance page.
Internal `<<anchor>>` refs become Antora `xref:page.adoc#anchor[]`.

## PM-Sandbox Backfill (the real content gap)

Lands in `scripts-and-pm-apis.adoc`. Verified against shipped code (PR #365):

- Enumerate the supported script-only `pm` APIs: `pm.environment` (+ `.name`),
  `pm.collectionVariables` (`.get`/`.set`/`.toObject`), `pm.request.body`,
  `pm.response.code`/`.json`/`.text`/`.to.have.status`, `pm.test`, `pm.expect`,
  `pm.execution.setNextRequest`.
- Document the two new `StepReport` fields:
  - `pmTestAssertions: List<PmTestAssertion>` — `pm.test(...)` results across pre-req + post-res
    scripts; `PmTestAssertion(name, passed, skipped, error)`.
  - `nextRequest: String?` — **captured only**; ReṼoman executes linearly and does NOT yet honor the
    directive to reorder/skip (Phase 2). Must state this honestly (today-vs-roadmap rule).
- State the current limits honestly: `pm.sendRequest` unsupported; collection `variable[]` not
  parsed (script-seeded only).
- `include::example$it/.../PokemonSandboxApiTest.java[tag=...]` — requires adding tag markers to that
  test (it has none today; see Open Question).

## Thin README (landing + agent signpost)

`README.adoc` shrinks to: new headline pitch, artifact/version, a minimal quickstart, link to the
site, and an explicit **page map for agents**:

```
Docs live at <site URL>. Source: docs/modules/ROOT/pages/.
- Getting started → docs/modules/ROOT/pages/getting-started.adoc
- Hooks           → docs/modules/ROOT/pages/hooks.adoc
- Polling         → docs/modules/ROOT/pages/polling.adoc
- PM script APIs  → docs/modules/ROOT/pages/scripts-and-pm-apis.adoc
... (full map)
```

So a naive agent that reads only README is pointed straight at the right focused file. No `llms.txt`
or markdown mirror this pass (decided: signpost-only).

## Verification

- `npx antora antora-playbook.yml` builds with **zero broken xrefs/includes** (Antora fails loudly on
  bad includes — the build is the test).
- Spot-check rendered pages: includes resolved (real code, not literal `include::` lines), callouts
  numbered, `{revoman-version}` substituted.
- `grep` the built/source pages for forbidden present-tense claims ("format-agnostic", "any format",
  "auto-generates MCP") per the positioning honesty rule.
- Confirm no HTML committed to `master`; gh-pages deploy succeeds via Actions.
- All existing image references resolve from the new `images/` location.

## Open Questions (resolve in plan/execution)

1. **Test tag markers:** `PokemonSandboxApiTest.java` has no `// tag::...` markers today. Either add
   them (touches a test file — minor) or inline a trimmed snippet in the page. Prefer adding tags
   (anti-rot, consistent with existing demos).
2. **Antora UI bundle:** use the default Antora UI, or a lightly themed one? Default for first pass.
3. **`docs/superpowers/`** specs/plans stay where they are (internal design history); not part of the
   published site.

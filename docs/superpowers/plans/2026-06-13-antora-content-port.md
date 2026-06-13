# Antora Docs Site — Content Port, Repitch & Backfill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the 999-line `README.adoc` into ~19 focused Antora pages written in the NEW positioning (API Orchestration Engine; Postman = input data format; testing = one application), backfill the PM-sandbox script-API gap, and shrink `README.adoc` to a landing + agent signpost.

**Architecture:** Builds on the scaffold plan (`2026-06-13-antora-scaffold.md`, which must be DONE first). Each README section maps to one focused page under `docs/modules/ROOT/pages/`. Content is reframed per the positioning spec, not rewritten. Live `include::` (via the `example$` symlinks the scaffold created), callouts, and `{revoman-version}` attrs carry over. The build (`npx antora`) is the verification gate — it fails loudly on broken xrefs/includes.

**Tech Stack:** Antora 3.x, AsciiDoc, the two design specs dated 2026-06-13.

---

## Pre-flight context for the implementer

**READ BOTH SPECS FIRST — they are the source of truth for voice and content:**
- `docs/superpowers/specs/2026-06-13-positioning-repitch-design.md` — the new pitch, the reframe-not-rewrite mapping table, the today-vs-roadmap honesty rule, the Postman-as-format (JSON-over-XML) analogy.
- `docs/superpowers/specs/2026-06-13-antora-docs-site-design.md` — the page map, nav groups, backfill scope.

**Hard rules (from the positioning spec — violating these is a plan failure):**
1. **Never claim format-agnosticism in present tense.** Postman V2/V3 is the input *format* (like "we use JSON over XML"). Format-agnostic = roadmap. No affinity to the Postman app/ecosystem.
2. **Never imply MCP tool-def autogen or dynamic agent↔graph coupling ship today.** Roadmap only.
3. **Headline noun = "API Orchestration Engine for the JVM"**, with a sentence underneath that says ReṼoman *executes the graph* (patent-precise).
4. **Testing is one application, not the identity.** `toJson` is promoted to a headline capability (= the patent's "Context Information").
5. **Reframe, don't delete.** Every factual claim and feature in the current README survives somewhere — only ordering/framing change.

**Mechanics reminders (same as scaffold plan):**
- **Antora reads from git HEAD.** Commit each page before relying on a build to show it.
- **Source material** is the current `README.adoc` (read it in full before porting — line ranges below are a guide, not a substitute for reading).
- **Existing include tags (verified):** `revoman-simple-demo` (RestfulAPIDevTest.java), `pq-e2e-with-revoman-config-demo` (PQE2EWithSMTest.java). `PokemonSandboxApiTest.java` has NO tags — Task 9 adds them.
- **Internal refs:** the README uses `<<anchor>>`; in Antora these become `xref:target-page.adoc[]` or `xref:target-page.adoc#section-id[]`. Every xref must resolve or the build errors.
- **13 images** to relocate (Task 1).

---

## File Structure (final state)

Pages under `docs/modules/ROOT/pages/` (one concept per file):

| Page | Source README section(s) | Nav group |
| --- | --- | --- |
| `index.adoc` (replace scaffold placeholder) | headline (NEW), lead paras, hybrid-tool, principle | Overview |
| `agentic-orchestration.adoc` (NEW) | positioning spec "building block" section | Overview |
| `why-revoman.adoc` | Why ReṼoman, Problem/Solution, Newman comparison | Overview |
| `getting-started.adoc` | Artifact, A Simple Example | Get Started |
| `configuration.adoc` | revUp/Kick, Config management | Get Started |
| `executable-api-metadata.adoc` | Template-Driven Testing, Why Postman Templates, v2/v3 + detection | Concepts |
| `rundown.adoc` | Rundown, toJson/Verbosity | Concepts |
| `step-procedure.adoc` | Step Procedure, Failure Hierarchy, IDE debugger view | Concepts |
| `variables.adoc` | Variables, precedence, custom dynamic vars | Concepts |
| `type-safety.adoc` | Type Safety / Moshi (all subsections) | Features |
| `hooks.adoc` | Dynamic Pre/Post hooks, step picks, response validation, custom JVM | Features |
| `scripts-and-pm-apis.adoc` | Pre-req/Post-res scripts, npm + **PM-sandbox backfill** | Features |
| `polling.adoc` | Polling for Async Steps | Features |
| `timing-metrics.adoc` | Execution Timing Metrics | Features |
| `mutable-environment.adoc` | Mutable Environment | Features |
| `execution-control.adoc` | Execution Control, Compose Modular Executions | Features |
| `applications.adoc` (NEW) | USP + Perf, reorganized | Applications |
| `troubleshooting.adoc` | Logging, FAQs | Help |
| `contributing.adoc` | Consume-Collaborate-Contribute | Help |

- Modify: `docs/modules/ROOT/nav.adoc` (full 6-group nav).
- Create: `docs/modules/ROOT/images/` (move from `docs/images/`).
- Modify: `README.adoc` (shrink to landing + signpost).
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java` (add include tags).

---

## Task 1: Relocate images into the module

**Files:** move `docs/images/*` → `docs/modules/ROOT/images/`.

- [ ] **Step 1: Move the images with git**

```bash
mkdir -p docs/modules/ROOT/images
git mv docs/images/*.png docs/modules/ROOT/images/
```

- [ ] **Step 2: Verify all 13 expected images landed**

Run: `ls docs/modules/ROOT/images/ | wc -l`
Expected: `13` (cognitive-complexity, failure-hierarchy, hybrid-tool, manual-to-automation, node_modules, postman-run, resfulapi-dev-pm, revoman-demo-thumbnail, mutable-env, pq-revoman-test-time, rundown, step-procedure, step-report — plus any others present; ≥13).

- [ ] **Step 3: Commit**

```bash
git add -A docs/images docs/modules/ROOT/images
git commit -m "docs(antora): relocate images into ROOT module"
```

Note for later pages: reference images as `image::name.png[]` (Antora resolves from the module's
`images/` dir automatically — do NOT prefix with `docs/images/`).

---

## Task 2: Full navigation tree

**Files:** Modify `docs/modules/ROOT/nav.adoc`.

- [ ] **Step 1: Replace nav.adoc with the 6-group tree**

```asciidoc
.xref:index.adoc[Overview]
* xref:agentic-orchestration.adoc[Agentic API Orchestration]
* xref:why-revoman.adoc[Why ReṼoman?]

.Get Started
* xref:getting-started.adoc[Getting Started]
* xref:configuration.adoc[Configuration]

.Concepts
* xref:executable-api-metadata.adoc[Executable API Metadata]
* xref:rundown.adoc[Rundown]
* xref:step-procedure.adoc[Step Procedure]
* xref:variables.adoc[Variables]

.Features
* xref:type-safety.adoc[Type Safety]
* xref:hooks.adoc[Hooks]
* xref:scripts-and-pm-apis.adoc[Scripts & pm APIs]
* xref:polling.adoc[Polling]
* xref:timing-metrics.adoc[Timing Metrics]
* xref:mutable-environment.adoc[Mutable Environment]
* xref:execution-control.adoc[Execution Control]

.Applications
* xref:applications.adoc[Applications]

.Help
* xref:troubleshooting.adoc[Troubleshooting & FAQ]
* xref:contributing.adoc[Contributing]
```

- [ ] **Step 2: Commit (build will fail until pages exist — that's expected; do NOT build yet)**

```bash
git add docs/modules/ROOT/nav.adoc
git commit -m "docs(antora): full nav tree (pages added in following tasks)"
```

---

## Tasks 3–8: Port pages group by group

> **Method for every page below (the repeatable recipe):**
> 1. Read the cited README section(s) in full.
> 2. Create the page file with a `= Title` heading.
> 3. Move the prose over, applying the framing changes noted for that page.
> 4. Convert every `<<anchor>>` to `xref:other-page.adoc[]` / `xref:other-page.adoc#id[]`.
> 5. Convert `image::x.png[]` references (drop any path prefix; Antora finds `images/`).
> 6. Carry `include::example$...[tag=...]` blocks verbatim where the README had them.
> 7. Keep `{revoman-version}` attribute references as-is.
> 8. Commit the page.
> 9. After each *group* (task), run `npx antora antora-playbook.yml` and fix any broken xref/include
>    the build reports. The build is the test: **zero include/xref warnings = pass.**

### Task 3: Overview group

**Files:** Create `index.adoc` (replace scaffold placeholder), `agentic-orchestration.adoc`, `why-revoman.adoc`.

- [ ] **Step 1: `index.adoc`** — Replace the scaffold placeholder. Lead with the approved headline
  (positioning spec §"The Headline Reframe"):
  > ReṼoman is an *API Orchestration Engine* for the JVM. You describe a workflow of interdependent
  > API calls as *executable API metadata*; ReṼoman executes that graph — resolving data dependencies
  > between calls, handling auth, polling, and type-safe payloads — and returns a structured
  > *Rundown* of everything that happened. This makes it a building block for *Agentic API
  > Orchestration*, with API test automation as one powerful application.

  Then the "NO licensed Postman SDKs" note, the hybrid-tool framing (README lines ~40–52), and the
  fundamental-principle paras — reframed so the *engine* is the subject, not "Postman for JVM". Carry
  images `postman-run.png`, `manual-to-automation.png`, `hybrid-tool.png`. Add a top NOTE admonition
  stating the today-vs-roadmap split (positioning spec §"Today-vs-Roadmap Honesty Rule"). Link
  `xref:agentic-orchestration.adoc[]` and `xref:getting-started.adoc[]`.

- [ ] **Step 2: `agentic-orchestration.adoc`** (NEW page) — Write from positioning spec §"New
  Section: ReṼoman as an Agentic Orchestration building block". Include the engine→Context
  Information→agent-Node ascii diagram from that spec. Ground it in the REAL `toJson(VERBOSE)` output:
  state that `Rundown.toJson(Verbosity.VERBOSE)` emits `stepReports`/`mutableEnv`/`stats` — the exact
  shape an agentic Node consumes. Light restaurant-kitchen analogy (API-Graph = menu set; ReṼoman =
  kitchen manager). End with a clearly-labeled `== Roadmap` subsection: MCP tool-def autogen, dynamic
  agent↔graph coupling — marked as direction, not shipped. xref to `rundown.adoc`.

- [ ] **Step 3: `why-revoman.adoc`** — Port "Why ReṼoman?" Problem/Solution (README ~99–127) and the
  Newman/Postman-CLI comparison (~54–64). Reframe the Newman section per the mapping table: not "a
  better Postman runner" but "a runner cannot be an orchestration engine inside your JVM/agentic
  stack." Carry `cognitive-complexity.png`. xref to `executable-api-metadata.adoc`,
  `type-safety.adoc`, `hooks.adoc`, `applications.adoc`.

- [ ] **Step 4: Build + fix + commit**

  Run: `npx antora antora-playbook.yml` → fix any reported xref to not-yet-created pages by leaving
  them (they resolve once those pages land) OR verify they point to planned filenames. Commit:
  ```bash
  git add docs/modules/ROOT/pages/index.adoc docs/modules/ROOT/pages/agentic-orchestration.adoc docs/modules/ROOT/pages/why-revoman.adoc
  git commit -m "docs(antora): Overview pages in new orchestration-engine voice"
  ```
  (xref warnings to pages created in later tasks are acceptable mid-port; Task 11 is the zero-warning
  gate.)

### Task 4: Get Started group

**Files:** Create `getting-started.adoc`, `configuration.adoc`.

- [ ] **Step 1: `getting-started.adoc`** — Port Artifact (Maven/Bazel/Gradle, README ~66–92, keep
  `{revoman-version}` attr subs) + A Simple Example (~150–200). Carry the live include:
  ```asciidoc
  include::example$it/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java[tag=revoman-simple-demo]
  ```
  with its `<1>`–`<5>` callouts. Note: drop the README's `ifdef::env-github`/`ifndef` split — Antora
  always resolves includes, so keep ONLY the `include::` version (not the duplicated github-fallback
  block). Carry `resfulapi-dev-pm.png`. xref to `configuration.adoc`, `rundown.adoc`.

- [ ] **Step 2: `configuration.adoc`** — Port `revUp()`/`Kick.configure()` intro (~137–148) +
  Config management (~447–483: immutability, `override...()`, `from()`, `plus()`). xref to
  `hooks.adoc`.

- [ ] **Step 3: Build + commit**
  ```bash
  npx antora antora-playbook.yml
  git add docs/modules/ROOT/pages/getting-started.adoc docs/modules/ROOT/pages/configuration.adoc
  git commit -m "docs(antora): Get Started pages (artifact, simple example, config)"
  ```

### Task 5: Concepts group

**Files:** Create `executable-api-metadata.adoc`, `rundown.adoc`, `step-procedure.adoc`, `variables.adoc`.

- [ ] **Step 1: `executable-api-metadata.adoc`** — THE most reframed page. Port Template-Driven
  Testing + "Why Postman Templates" (~130–148) + Postman Collection v3 support (~202–248). Apply the
  Postman-as-format framing: open by defining *executable API metadata* (patent's API-Graph), then
  "ReṼoman reads this metadata in the Postman V2/V3 *format* — a serialization choice like JSON over
  XML, with no tie to the Postman app or SDK." Keep the v2/v3 detection table + layout + known
  limitations verbatim (factual). Add a short "Why this format?" (pragmatic/proven/graph-like/has a
  UI) and a one-line roadmap pointer (other formats = future). NO present-tense format-agnostic claim.

- [ ] **Step 2: `rundown.adoc`** — Port Rundown (~250–281) + `toJson()`/Verbosity (~283–306). Promote
  `toJson`: add a sentence linking it to `xref:agentic-orchestration.adoc[Context Information]`. Keep
  the `Rundown`/`StepReport` Kotlin block with its `<1>`–`<5>` callouts. xref to `step-procedure.adoc`,
  `timing-metrics.adoc`.

- [ ] **Step 3: `step-procedure.adoc`** — Port Step Procedure (~503–519), Failure Hierarchy
  (~582–589), IDE debugger view (~486–501). Carry `rundown.png`, `step-report.png`, `mutable-env.png`,
  `step-procedure.png`, `failure-hierarchy.png`. xref to `execution-control.adoc`, `polling.adoc`.

- [ ] **Step 4: `variables.adoc`** — Port Variables + nested + dynamic + precedence (~428–445) and
  Custom Dynamic variables (~847–856). Frame as orchestration dependency-passing between graph steps.
  Carry the `DynamicVariableGenerator.kt` source links as `xref` or external links (these are
  `link:{sourcedir}/...` in README — convert to GitHub URLs since source isn't a doc page; see Task 10
  note on source links). xref to `mutable-environment.adoc`.

- [ ] **Step 5: Build + commit**
  ```bash
  npx antora antora-playbook.yml
  git add docs/modules/ROOT/pages/executable-api-metadata.adoc docs/modules/ROOT/pages/rundown.adoc docs/modules/ROOT/pages/step-procedure.adoc docs/modules/ROOT/pages/variables.adoc
  git commit -m "docs(antora): Concepts pages (metadata, rundown, step procedure, variables)"
  ```

### Task 6: Features group — part A

**Files:** Create `type-safety.adoc`, `hooks.adoc`.

- [ ] **Step 1: `type-safety.adoc`** — Port the entire Type Safety / Moshi section (~602–678:
  globalSkipTypes, requestConfig, responseConfig, globalCustomTypeAdapters, DiMorphicAdapter,
  reader/writer utils, JSON POJO utils). Convert `link:{sourcedir}/...`/`link:{testdir}/...` source
  links to GitHub blob URLs (Task 10 note).

- [ ] **Step 2: `hooks.adoc`** — Port Dynamic Pre/Post hooks (~707–763), Response Validations
  (~765–770), Plug-in custom JVM code (~752–763). Carry the advanced PQ example with its full
  `<1>`–`<14>` callouts:
  ```asciidoc
  include::example$it/com/salesforce/revoman/integration/core/pq/PQE2EWithSMTest.java[tag=pq-e2e-with-revoman-config-demo]
  ```
  (drop the `ifdef`/`ifndef` github split; keep include-only.) xref to `mutable-environment.adoc`,
  `type-safety.adoc`, `execution-control.adoc`.

- [ ] **Step 3: Build + commit**
  ```bash
  npx antora antora-playbook.yml
  git add docs/modules/ROOT/pages/type-safety.adoc docs/modules/ROOT/pages/hooks.adoc
  git commit -m "docs(antora): Features pages (type safety, hooks)"
  ```

### Task 7: Features group — part B

**Files:** Create `polling.adoc`, `timing-metrics.adoc`, `mutable-environment.adoc`, `execution-control.adoc`.

- [ ] **Step 1: `polling.adoc`** — Port Polling for Async Steps (~858–914) verbatim (factual);
  keep the PollingConfig Java block + `<1>`–`<5>` callouts. xref to `step-procedure.adoc`,
  `hooks.adoc`.

- [ ] **Step 2: `timing-metrics.adoc`** — Port Execution Timing Metrics (~521–580) including the
  ExeType list and the JSON serialization example. xref to `rundown.adoc`.

- [ ] **Step 3: `mutable-environment.adoc`** — Port Mutable Environment (~815–846: postmanEnvJSONFormat,
  read-as-strong-type, pmEnvSnapshot). Frame as the cross-step context store of the orchestration.
  xref to `variables.adoc`, `hooks.adoc`.

- [ ] **Step 4: `execution-control.adoc`** — Port Execution Control (~680–699) + Compose Modular
  Executions (~700–705). xref to `step-procedure.adoc`.

- [ ] **Step 5: Build + commit**
  ```bash
  npx antora antora-playbook.yml
  git add docs/modules/ROOT/pages/polling.adoc docs/modules/ROOT/pages/timing-metrics.adoc docs/modules/ROOT/pages/mutable-environment.adoc docs/modules/ROOT/pages/execution-control.adoc
  git commit -m "docs(antora): Features pages (polling, timing, mutable env, execution control)"
  ```

### Task 8: Applications + Help group

**Files:** Create `applications.adoc`, `troubleshooting.adoc`, `contributing.adoc`.

- [ ] **Step 1: `applications.adoc`** (NEW, reorganized) — Per positioning spec §"Reframed
  Applications Section". Three ordered subsections:
  1. `== Agentic API Orchestration` — headline; today (engine + `toJson` Context Information) vs
     roadmap (MCP autogen). xref to `agentic-orchestration.adoc`.
  2. `== API Test Automation` — ALL the current USP material (README ~916–953: low-code 89%, low
     learning curve, low setup tax, CI/CD, VCS-resident collections, persona-based testing) lands
     here, intact, framed as an application. Carry the PQ low-code example link.
  3. `== Orchestrated Workflows & Test-Data Setup` — the data-setup hint from USP.
  Then a `== Performance` subsection porting Perf (~954–962) with `pq-revoman-test-time.png`.

- [ ] **Step 2: `troubleshooting.adoc`** — Port Logging (~591–598) + FAQs (~977–993). Convert the
  `<<anchor>>` refs in FAQs to xrefs.

- [ ] **Step 3: `contributing.adoc`** — Port Consume-Collaborate-Contribute (~995–1000) + the
  `link:CONTRIBUTING.adoc` reference (external link to the repo file) + Slack link.

- [ ] **Step 4: Build + commit**
  ```bash
  npx antora antora-playbook.yml
  git add docs/modules/ROOT/pages/applications.adoc docs/modules/ROOT/pages/troubleshooting.adoc docs/modules/ROOT/pages/contributing.adoc
  git commit -m "docs(antora): Applications + Help pages"
  ```

---

## Task 9: PM-sandbox backfill (the real content gap) — add tags first, then document

**Files:**
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`
- Modify: `docs/modules/ROOT/pages/scripts-and-pm-apis.adoc` (created in this task)

- [ ] **Step 1: Add include tag markers to the sandbox test**

The test has no tags. Wrap the `revUp(...)` config block and a representative assertion block in
AsciiDoc tags using `// tag::`/`// end::` line comments. In
`PokemonSandboxApiTest.java`, around the existing `final Rundown rundown = ReVoman.revUp(...)` call
(currently ~line 36) add:

```java
    // tag::pm-sandbox-revup[]
    final Rundown rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(PM_COLLECTION_PATH)
                .environmentPath(PM_ENVIRONMENT_PATH)
                .nodeModulesPath("js")
                .off());
    // end::pm-sandbox-revup[]
```

and wrap the assertions on `pmTestAssertions` / `nextRequest` (currently ~lines 56–66):

```java
    // tag::pm-sandbox-asserts[]
    final StepReport byName = rundown.reportForStepName("pokemon-by-name");
    assertThat(byName.pmTestAssertions.stream().allMatch(a -> a.passed)).isTrue();
    // pm.execution.setNextRequest is CAPTURED, not executed (linear execution; Phase 2 reorders):
    assertThat(byName.nextRequest).isEqualTo("pokemon-species");
    // end::pm-sandbox-asserts[]
```

(Place tags around the EXISTING lines — do not duplicate logic. Keep the surrounding assertions
intact.)

- [ ] **Step 2: Verify the test still compiles and passes**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest"`
Expected: BUILD SUCCESSFUL, 1 test passing. (Comment-only tag markers do not change behavior.)
If it fails on the live-API rate limit (restful-api.dev 50-req/24h), that is environmental — re-run
later; the tag edit itself cannot break compilation.

- [ ] **Step 3: Commit the test tags**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java
git commit -m "test(sandbox): add asciidoc include tags for docs"
```

- [ ] **Step 4: Write `scripts-and-pm-apis.adoc`**

Create the page with two parts:

*Part 1 — Pre-req and Post-res scripts* (port README ~772–813): what they are, execution order, npm
modules via `nodeModulesPath`/`require`, the moment/lodash example, the jar-path caveat, the
node_modules tip (carry `node_modules.png`), the "keep scripts simple" CAUTION.

*Part 2 — Supported `pm` script APIs (NEW backfill)* — document, per the antora-docs spec §"PM-Sandbox
Backfill" and verified against PR #365 code:

```asciidoc
== Supported `pm` script APIs

ReṼoman runs your collection's pre-request and post-response JavaScript on the JVM through a
real Postman-compatible sandbox. The following script-only `pm` APIs are supported and surface
their effects on the xref:rundown.adoc[Rundown]:

[cols="1,2"]
|===
| API | Behavior in ReṼoman

| `pm.environment` (+ `pm.environment.name`) | Reads/writes flow into the mutable environment.
| `pm.collectionVariables` (`.get` / `.set` / `.toObject`) | Collection-scoped key/value store; values set in one step's script are visible to later steps' scripts.
| `pm.request.body`, `pm.response.code` / `.json` / `.text` / `.to.have.status` | Request/response fed into the script context.
| `pm.test`, `pm.expect` | Assertion results recorded on `StepReport.pmTestAssertions`.
| `pm.execution.setNextRequest` | Captured on `StepReport.nextRequest` (see note below).
|===

Two read-only fields on each `StepReport` expose script outcomes:

* `pmTestAssertions: List<PmTestAssertion>` — every `pm.test(...)` result across the step's
  pre-request and post-response scripts. `PmTestAssertion` carries `name`, `passed`, `skipped`,
  and `error`.
* `nextRequest: String?` — the value passed to `pm.execution.setNextRequest(...)`, if any.

[CAUTION]
====
`nextRequest` is *captured only*. ReṼoman executes steps linearly and does *not yet* honor the
directive to reorder or skip steps (that is planned). Treat it as an observed signal, not proof
of a jump.
====

.Current limitations
* `pm.sendRequest` is not supported.
* Collection-root `variable[]` is not parsed; `pm.collectionVariables` is script-seeded only.
```

Then a live include of the sandbox test using the tags from Step 1:

```asciidoc
.End-to-end: script-only pm APIs through a real run
[source,java,indent=0,tabsize=2,options="nowrap"]
----
include::example$it/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java[tag=pm-sandbox-asserts]
----
```

xref to `rundown.adoc`, `mutable-environment.adoc`, `hooks.adoc`.

- [ ] **Step 5: Build and verify the backfill resolves**

Run: `npx antora antora-playbook.yml`
Expected: `Site generation complete!`, no include errors.

Run: `grep -c 'pmTestAssertions' build/site/revoman/scripts-and-pm-apis.html`
Expected: `≥ 1` (both the prose and the inlined test code mention it).

- [ ] **Step 6: Commit**

```bash
git add docs/modules/ROOT/pages/scripts-and-pm-apis.adoc
git commit -m "docs(antora): scripts page + PM-sandbox script-API backfill (PR #365)"
```

---

## Task 10: Source-link helper (DRY the GitHub blob links)

The README used `link:{sourcedir}/...`, `link:{testdir}/...`, `link:{integrationtestdir}/...` to link
to source files relative to the GitHub-rendered README. On the Antora site those relative links break.
Replace them with absolute GitHub blob URLs via a reusable attribute.

**Files:** Modify `docs/antora.yml`.

- [ ] **Step 1: Add base-URL attributes to the component descriptor**

In `docs/antora.yml` under `asciidoc.attributes`, add:

```yaml
    gh-blob: https://github.com/salesforce-misc/ReVoman/blob/master
    sourcedir: src/main/kotlin
    testdir: src/test/java
    integrationtestdir: src/integrationTest/java
```

Pages then write source links as, e.g.:
`{gh-blob}/{integrationtestdir}/com/salesforce/revoman/integration/core/pq/PQE2EWithSMTest.java[PQE2EWithSMTest]`.

- [ ] **Step 2: Sweep the ported pages for raw `link:{sourcedir}`-style links and fix them**

Run: `grep -rn 'link:{sourcedir}\|link:{testdir}\|link:{integrationtestdir}\|link:{pmtemplates}' docs/modules/ROOT/pages/`
For each hit, prefix with `{gh-blob}/` so it becomes an absolute URL. (If a page already used the
`{gh-blob}` form, skip.)

- [ ] **Step 3: Build + verify no broken relative source links**

Run: `npx antora antora-playbook.yml`
Run: `grep -rc 'href="src/' build/site/revoman/*.html | grep -v ':0' || echo "NO_RELATIVE_SRC_LINKS"`
Expected: `NO_RELATIVE_SRC_LINKS` (no link points at a relative `src/...` path that would 404).

- [ ] **Step 4: Commit**

```bash
git add docs/antora.yml docs/modules/ROOT/pages
git commit -m "docs(antora): absolute GitHub source links via gh-blob attribute"
```

---

## Task 11: Zero-warning build gate + positioning honesty check

**Files:** none (verification + targeted fixes).

- [ ] **Step 1: Clean build, capture warnings**

Run: `rm -rf build/site && npx antora --log-level=warn antora-playbook.yml 2>&1 | tee /tmp/antora-build.log`
Expected: `Site generation complete!`. Then:
Run: `grep -iE 'warn|error|not found|unresolved' /tmp/antora-build.log || echo "CLEAN"`
Expected: `CLEAN`. Any `target of xref not found` / `target of include not found` → fix that page's
ref and rebuild until CLEAN.

- [ ] **Step 2: Positioning honesty grep (the hard rules)**

Run:
```bash
grep -rniE 'format[- ]agnostic|works with any (template|format)|auto-generate[s]? (the )?(mcp|tool) (def|schema)' docs/modules/ROOT/pages/
```
Expected: any hit is either absent OR sits inside an explicitly roadmap/future context. A
present-tense capability claim here is a FAIL — reword to roadmap framing. Manually read each hit.

- [ ] **Step 3: Confirm no orphan pages (every page is in nav)**

Run: `for f in docs/modules/ROOT/pages/*.adoc; do b=$(basename "$f"); grep -q "$b" docs/modules/ROOT/nav.adoc || echo "ORPHAN: $b"; done`
Expected: no `ORPHAN` lines.

- [ ] **Step 4: Commit any fixes**

```bash
git add docs/modules/ROOT
git commit -m "docs(antora): clean build gate — resolve xrefs, enforce roadmap framing" || echo "nothing to fix"
```

---

## Task 12: Shrink README to landing + agent signpost

**Files:** Modify `README.adoc`.

- [ ] **Step 1: Replace the README body**

Reduce `README.adoc` to: the title/author header, the NEW headline pitch (same wording as
`index.adoc`), the artifact/version block (Maven/Gradle/Bazel with `{revoman-version}` — keep,
people land here first), a one-line "Full documentation:" link to the site
(`https://salesforce-misc.github.io/ReVoman`), and the agent signpost page map. Keep the demo
thumbnail + Slack/contributing links. Remove all the deep feature sections (now on the site).

The agent signpost block (verbatim structure, fill the full page list):

```asciidoc
== Documentation

Full docs: https://salesforce-misc.github.io/ReVoman

Source pages live in `docs/modules/ROOT/pages/` (one concept per file):

[cols="1,2"]
|===
| Topic | Page

| Getting started        | `docs/modules/ROOT/pages/getting-started.adoc`
| Agentic orchestration  | `docs/modules/ROOT/pages/agentic-orchestration.adoc`
| Configuration          | `docs/modules/ROOT/pages/configuration.adoc`
| Executable API metadata| `docs/modules/ROOT/pages/executable-api-metadata.adoc`
| Rundown                | `docs/modules/ROOT/pages/rundown.adoc`
| Hooks                  | `docs/modules/ROOT/pages/hooks.adoc`
| Scripts & pm APIs      | `docs/modules/ROOT/pages/scripts-and-pm-apis.adoc`
| Polling                | `docs/modules/ROOT/pages/polling.adoc`
| Type safety            | `docs/modules/ROOT/pages/type-safety.adoc`
| ... (one row per page)
|===
```

- [ ] **Step 2: Verify README still renders as valid AsciiDoc**

Run: `npx --yes asciidoctor -o /tmp/readme.html README.adoc && echo RENDER_OK`
Expected: `RENDER_OK`, no fatal errors. (Warnings about missing `include::` are fine only if you
removed all includes — confirm none remain: `grep -c 'include::' README.adoc` → `0`.)

- [ ] **Step 3: Confirm README shrank substantially**

Run: `wc -l README.adoc`
Expected: well under 150 lines (was 999).

- [ ] **Step 4: Commit**

```bash
git add README.adoc
git commit -m "docs: shrink README to landing page + agent signpost; full docs on Antora site"
```

---

## Task 13: Final end-to-end verification

**Files:** none.

- [ ] **Step 1: Full clean build**

Run: `rm -rf build/site && npx antora antora-playbook.yml`
Expected: `Site generation complete!`, CLEAN log (per Task 11).

- [ ] **Step 2: All 19 pages built**

Run: `ls build/site/revoman/*.html | wc -l`
Expected: `≥ 19`.

- [ ] **Step 3: The three superpowers still hold on a real content page**

Run:
```bash
grep -q '0.9.14' build/site/revoman/getting-started.html && echo ATTR_OK
grep -q 'class="conum"' build/site/revoman/getting-started.html && echo CALLOUT_OK
grep -q 'revUp' build/site/revoman/getting-started.html && ! grep -q 'include::' build/site/revoman/getting-started.html && echo INCLUDE_OK
```
Expected: `ATTR_OK`, `CALLOUT_OK`, `INCLUDE_OK`.

- [ ] **Step 4: Search index covers the new pages**

Run: `grep -c 'agentic\|orchestration' build/site/search-index.js`
Expected: `≥ 1`.

- [ ] **Step 5: No build output tracked**

Run: `git status --porcelain build/ | head`
Expected: empty.

- [ ] **Step 6: Human review checkpoint**

Run: `open build/site/revoman/index.html`. Confirm: headline reads "API Orchestration Engine",
testing is framed as an application, nav has all 6 groups, search works, a feature page shows inlined
test code. Report the deployed Pages URL once CI runs (and the human has set Pages Source = GitHub
Actions per scaffold Task 5 Step 4).
```
```

---

## Notes carried from specs

- This plan assumes the scaffold plan is merged. If `docs/modules/ROOT/examples/it` symlink or the
  playbook is missing, STOP and run the scaffold plan first.
- `docs/superpowers/` specs/plans are NOT published — they stay internal. Do not add them to nav.
- If a README factual detail has no obvious page, default to the closest Concepts/Features page rather
  than dropping it — the reframe-don't-delete rule applies.

# Antora Docs Site — Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a buildable Antora site skeleton for ReṼoman that preserves live `include::` of test/source code, callouts, and version attrs — with CI deploy to GitHub Pages — WITHOUT yet porting content.

**Architecture:** A single Antora component (`revoman`, version `current`) rooted at `docs/`. Live code includes work via `example$` symlinks from `docs/modules/ROOT/examples/` to the repo's source trees. A local playbook (`url: .`, `branches: HEAD`) builds to `build/site/`. A GitHub Actions workflow builds and deploys to GitHub Pages on push to `master`. This plan proves the pipeline end-to-end with ONE smoke page that includes real test code; the full content port is a separate plan.

**Tech Stack:** Antora 3.x (`antora` CLI + `@antora/site-generator`), `@antora/lunr-extension` (search), AsciiDoc, GitHub Actions + GitHub Pages, Node 18+ via `npx`.

---

## Pre-flight context for the implementer

- **Repo:** `salesforce-misc/ReVoman`, default branch `master` (NOT `main` — the official Antora workflow templates say `main`; you MUST change it to `master`).
- **Antora reads from git, not the raw working tree.** With `url: .` and `branches: HEAD`, Antora builds the content committed at `HEAD` of the current branch. Practical consequence: **a page or symlink you create shows up in the build only after it is committed** (or staged — Antora 3.1+ reads the worktree for the current branch's files, but committing is the reliable path). Every "build and verify" step below assumes you committed the preceding step. If a build shows stale content, the fix is almost always "you didn't commit."
- **No `node_modules` in the repo.** Use `npx`/ephemeral installs. Do not commit `package-lock.json` unless a later task explicitly creates a `package.json` (this plan does not — it uses `npx` directly).
- **Symlinks:** macOS/Linux create them natively. The repo will need `git config core.symlinks true` honored on clone (default true on macOS/Linux).
- **Existing images** live at `docs/images/`. This plan does NOT move them (content port plan does); the smoke page uses no images.
- This plan must NOT touch `README.adoc` or delete any existing doc content. It is purely additive.

---

## File Structure

- Create: `docs/antora.yml` — component descriptor (name, version, nav).
- Create: `docs/modules/ROOT/nav.adoc` — minimal nav (one entry for the smoke page).
- Create: `docs/modules/ROOT/pages/index.adoc` — smoke page proving include + attr + callout.
- Create: `docs/modules/ROOT/examples/it` — symlink → `../../../../src/integrationTest/java`.
- Create: `docs/modules/ROOT/examples/test` — symlink → `../../../../src/test/java`.
- Create: `docs/modules/ROOT/examples/main` — symlink → `../../../../src/main/kotlin`.
- Create: `antora-playbook.yml` — local build config (repo root).
- Create: `.github/workflows/docs.yml` — build + deploy to Pages.
- Modify: `.gitignore` — ignore `build/site/` and Antora cache.

---

## Task 1: Component descriptor + smoke page (no includes yet)

**Files:**
- Create: `docs/antora.yml`
- Create: `docs/modules/ROOT/nav.adoc`
- Create: `docs/modules/ROOT/pages/index.adoc`

- [ ] **Step 1: Create the component descriptor**

Create `docs/antora.yml`:

```yaml
name: revoman
version: ~
title: ReṼoman
nav:
- modules/ROOT/nav.adoc
asciidoc:
  attributes:
    revoman-version: 0.9.14@
    hide-uri-scheme: '@'
    table-caption: false
```

(`version: ~` means an unversioned "current" component — no version segment in URLs. The trailing
`@` on attribute values makes them soft-defaults that a page can override.)

- [ ] **Step 2: Create the minimal nav**

Create `docs/modules/ROOT/nav.adoc`:

```asciidoc
* xref:index.adoc[Home]
```

- [ ] **Step 3: Create the smoke page (attr + callout, no include yet)**

Create `docs/modules/ROOT/pages/index.adoc`:

```asciidoc
= ReṼoman

ReṼoman is an API Orchestration Engine for the JVM. (placeholder — real pitch comes in the content plan.)

Current version: {revoman-version}

[source,java]
----
var x = 1; // <1>
----
<1> A callout, proving callout rendering works.
```

- [ ] **Step 4: Commit**

```bash
git add docs/antora.yml docs/modules/ROOT/nav.adoc docs/modules/ROOT/pages/index.adoc
git commit -m "docs(antora): add component descriptor, nav, smoke page"
```

---

## Task 2: Local playbook + first successful build

**Files:**
- Create: `antora-playbook.yml`
- Modify: `.gitignore`

- [ ] **Step 1: Create the playbook**

Create `antora-playbook.yml` at the repo root:

```yaml
site:
  title: ReṼoman
  start_page: revoman::index.adoc
content:
  sources:
  - url: .
    branches: HEAD
    start_path: docs
ui:
  bundle:
    url: https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/HEAD/raw/build/ui-bundle.zip?job=bundle-stable
    snapshot: true
output:
  dir: ./build/site
```

(`url: .` + `branches: HEAD` = build this repo's current branch. `start_path: docs` points Antora at
the component root. Default Antora UI bundle for the first pass.)

- [ ] **Step 2: Ignore build output**

Append to `.gitignore`:

```
# Antora docs site build output
build/site/
.cache/antora/
```

- [ ] **Step 3: Build the site**

Run: `npx antora antora-playbook.yml`
Expected: Antora downloads the CLI/generator + UI bundle on first run, then prints
`Site generation complete!` and produces `build/site/index.html`. Zero `include::` errors (there are
no includes yet).

If you see `error: Cannot find module` → run `npm i -g @antora/cli @antora/site-generator` once, or
let `npx antora` install on demand. If you see `the start page ... could not be resolved` → the
component name in `start_page` must match `docs/antora.yml` `name:` (`revoman`).

- [ ] **Step 4: Verify rendered output**

Run: `grep -c '0.9.14' build/site/revoman/index.html`
Expected: `≥ 1` (the `{revoman-version}` attribute was substituted — NOT the literal
`{revoman-version}`).

Run: `grep -c 'class="conum"' build/site/revoman/index.html`
Expected: `≥ 1` (the `<1>` callout rendered as a conum, not literal text).

- [ ] **Step 5: Commit**

```bash
git add antora-playbook.yml .gitignore
git commit -m "docs(antora): add local playbook; first clean build"
```

---

## Task 3: Live code include via example$ symlinks

This is the make-or-break task: proving Antora pulls real, test-verified source through a symlink.

**Files:**
- Create: `docs/modules/ROOT/examples/it` (symlink)
- Create: `docs/modules/ROOT/examples/test` (symlink)
- Create: `docs/modules/ROOT/examples/main` (symlink)
- Modify: `docs/modules/ROOT/pages/index.adoc`

- [ ] **Step 1: Confirm the include target + tag exists**

Run: `grep -n 'tag::revoman-simple-demo\|end::revoman-simple-demo' src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java`
Expected: two lines — a `tag::revoman-simple-demo` and `end::revoman-simple-demo`. This tag is
already used by the current README, so it exists. If it does not, STOP and report — do not invent a
tag.

- [ ] **Step 2: Create the three symlinks**

Run from the repo root:

```bash
mkdir -p docs/modules/ROOT/examples
ln -s ../../../../src/integrationTest/java docs/modules/ROOT/examples/it
ln -s ../../../../src/test/java docs/modules/ROOT/examples/test
ln -s ../../../../src/main/kotlin docs/modules/ROOT/examples/main
```

- [ ] **Step 3: Verify the symlinks resolve**

Run: `cat docs/modules/ROOT/examples/it/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java | head -5`
Expected: the first 5 lines of the real test file (license header). If "No such file or directory",
the relative symlink target is wrong — count the `../` (from `examples/` you go up 4: `examples` →
`ROOT` → `modules` → `docs` → repo root).

- [ ] **Step 4: Add the live include to the smoke page**

Replace the placeholder `[source,java]` block in `docs/modules/ROOT/pages/index.adoc` with a real
tagged include. The full file becomes:

```asciidoc
= ReṼoman

ReṼoman is an API Orchestration Engine for the JVM. (placeholder — real pitch comes in the content plan.)

Current version: {revoman-version}

.Live include from a passing integration test
[source,java,indent=0,tabsize=2,options="nowrap"]
----
include::example$it/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java[tag=revoman-simple-demo]
----
<1> `revUp` is the method to call passing a configuration.
```

- [ ] **Step 5: Commit (required — Antora reads HEAD)**

```bash
git add docs/modules/ROOT/examples docs/modules/ROOT/pages/index.adoc
git commit -m "docs(antora): live include of RestfulAPIDevTest via example\$ symlink"
```

- [ ] **Step 6: Rebuild and verify the include resolved**

Run: `npx antora antora-playbook.yml`
Expected: `Site generation complete!` with NO `target of include not found` or
`include target ... not found` warnings.

Run: `grep -c 'revUp' build/site/revoman/index.html`
Expected: `≥ 1` (the real test code was inlined into the HTML, not left as a literal `include::`
line).

Run: `grep -c 'include::example' build/site/revoman/index.html`
Expected: `0` (no unresolved include directive leaked into output).

- [ ] **Step 7: Confirm the symlink is committed as a symlink (not a copy)**

Run: `git ls-files -s docs/modules/ROOT/examples/it`
Expected: mode `120000` (git symlink), not `100644`. If it is `100644`, git stored a regular file —
remove, run `git config core.symlinks true`, recreate, recommit.

---

## Task 4: Add search (Lunr extension)

**Files:**
- Modify: `antora-playbook.yml`

- [ ] **Step 1: Enable the Lunr extension in the playbook**

Add a top-level `antora:` key to `antora-playbook.yml` (place it above `site:`):

```yaml
antora:
  extensions:
  - require: '@antora/lunr-extension'
```

- [ ] **Step 2: Build with the extension**

Run: `npx antora --extension=@antora/lunr-extension antora-playbook.yml`
(If `@antora/lunr-extension` is not installed, run `npm i @antora/lunr-extension` first, then the
plain `npx antora antora-playbook.yml`.)
Expected: `Site generation complete!` and a `build/site/search-index.js` file is produced.

Run: `test -f build/site/search-index.js && echo OK`
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add antora-playbook.yml
git commit -m "docs(antora): enable Lunr full-text search"
```

---

## Task 5: CI build + deploy to GitHub Pages

**Files:**
- Create: `.github/workflows/docs.yml`

- [ ] **Step 1: Create the workflow (note: branch is master, not main)**

Create `.github/workflows/docs.yml`:

```yaml
name: Publish Docs to GitHub Pages
on:
  push:
    branches: [master]
  workflow_dispatch:
concurrency:
  group: github-pages
  cancel-in-progress: false
permissions:
  contents: read
  pages: write
  id-token: write
jobs:
  build:
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
    - name: Checkout repository
      uses: actions/checkout@v5
      with:
        # Antora needs full history for url: . content sources
        fetch-depth: 0
    - name: Configure Pages
      uses: actions/configure-pages@v5
    - name: Install Node.js
      uses: actions/setup-node@v5
      with:
        node-version: '18'
    - name: Install Antora + Lunr extension
      run: npm i antora @antora/lunr-extension
    - name: Generate Site
      run: npx antora antora-playbook.yml
    - name: Upload Artifacts
      uses: actions/upload-pages-artifact@v4
      with:
        path: build/site
    - name: Deploy to GitHub Pages
      id: deployment
      uses: actions/deploy-pages@v4
```

- [ ] **Step 2: Set the site URL in the playbook**

Add `url` under `site:` in `antora-playbook.yml` so generated links are absolute:

```yaml
site:
  title: ReṼoman
  url: https://salesforce-misc.github.io/ReVoman
  start_page: revoman::index.adoc
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/docs.yml antora-playbook.yml
git commit -m "ci(docs): build Antora and deploy to GitHub Pages on push to master"
```

- [ ] **Step 4: Manual one-time repo setting (cannot be scripted here — flag to the human)**

In GitHub repo Settings → Pages → "Build and deployment" → Source = **GitHub Actions**. Note this in
the handoff; without it the deploy job fails with "Pages site not found". Do NOT attempt via `gh`
unless the human asks — it is a one-time admin toggle.

- [ ] **Step 5: Verify the workflow is valid YAML**

Run: `npx --yes js-yaml .github/workflows/docs.yml > /dev/null && echo VALID`
Expected: `VALID` (no parse error). If `js-yaml` is unavailable, use
`python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/docs.yml')); print('VALID')"`.

---

## Task 6: Final scaffold verification

**Files:** none (verification only).

- [ ] **Step 1: Clean build from scratch**

Run: `rm -rf build/site && npx antora antora-playbook.yml`
Expected: `Site generation complete!`, zero ERROR/WARN about includes or xrefs.

- [ ] **Step 2: Assert the three superpowers survived**

Run:
```bash
grep -q '0.9.14' build/site/revoman/index.html && echo "ATTR_OK"
grep -q 'class="conum"' build/site/revoman/index.html && echo "CALLOUT_OK"
grep -q 'revUp' build/site/revoman/index.html && ! grep -q 'include::example' build/site/revoman/index.html && echo "INCLUDE_OK"
```
Expected: `ATTR_OK`, `CALLOUT_OK`, `INCLUDE_OK` all print.

- [ ] **Step 3: Assert no build output is tracked by git**

Run: `git status --porcelain build/site | head`
Expected: empty output (build/site is gitignored).

- [ ] **Step 4: Open the site locally (optional human check)**

Run: `open build/site/revoman/index.html` (macOS).
Expected: page renders with the version, a numbered callout, and the inlined test code, plus a search
box (top-right) from the Lunr extension.

---

## Scaffold done → hand to content plan

At this point the pipeline is proven: Antora builds, includes/callouts/attrs work, search is on, CI
is wired. The smoke `index.adoc` is intentionally a placeholder. The next plan
(`2026-06-13-antora-content-port.md`) replaces it and adds the ~19 real pages in the new voice.

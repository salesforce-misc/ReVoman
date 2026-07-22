# Qodana Setup + Inspection Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the JetBrains Qodana Gradle plugin into ReVoman as a local pre-push static-analysis gate with a GitHub Actions backstop, then fix all Critical + High inspection findings on a single feature branch.

**Architecture:** Add the `org.jetbrains.qodana` plugin via the version catalog (repo convention), rewrite the stale `qodana.yaml` for the free Community linter + JDK 21, add a `qodana.yml` CI workflow that reports to Qodana Cloud + GitHub code scanning, document the dev loop in `DEVELOPMENT.md`. Then run `./gradlew qodanaScan` locally (Docker via colima), parse the SARIF, bucket Critical+High findings by category, and fix each bucket in an isolated git worktree, merging all fixes back to one branch with grouped commits.

**Tech Stack:** Gradle 9.7 (Kotlin DSL), Qodana Gradle plugin `2026.1.3`, Qodana CLI + `jetbrains/qodana-jvm-community` Docker linter, colima (local Docker), GitHub Actions (`JetBrains/qodana-action`, `github/codeql-action/upload-sarif`), Kotlin/Java, Kotest + JUnit.

## Global Constraints

- **JDK:** 21+ (repo requires it; `qodana.yaml` `projectJDK: "21"`).
- **Gradle floor:** plugin needs 6.6+ (repo is on 9.7 — fine).
- **Linter:** free `jetbrains/qodana-jvm-community:latest` ONLY. No paid Ultimate/Ultimate-Plus. No free Ultimate for OSS exists.
- **Plugin version pin:** `qodana = "2026.1.3"` in the version catalog; CI `qodana-action` pinned to `v2026.1` to match the linter major.
- **Version-catalog convention:** all plugin/dep versions go through `gradle/libs.versions.toml`, referenced via `libs.plugins.*` / `libs.*` — never inline version strings in `build.gradle.kts`.
- **`qodanaScan` stays opt-in:** NOT wired into `check`/`build` (would force Docker on every build), mirroring the `-PincludeCoreIT` opt-in pattern.
- **No suppressions:** fixes must resolve the real issue, not silence a finding via annotations/baseline/exclude. If unfixable, leave it and report why.
- **Style:** Kotlin follows `STYLE.md` (functional, four-space indent, ktfmt googleStyle via `spotlessApply`). Any `.java` edit follows the `my-java-coding-style` skill.
- **Secret:** `QODANA_TOKEN` lives ONLY in a GitHub Actions secret — never committed. The chat-exposed token must be rotated after setup.
- **Branch:** all work lands on `chore/qodana-setup-and-fixes` (already created). One commit per finding-category; one PR at the end.
- **CI convention:** existing workflows pin actions with `@main` and use `distribution: jetbrains` / `java-version: 21` for setup-java; follow that except for `qodana-action` (version-pinned).

---

# Phase A — Setup + Scan (fully specified)

### Task 1: Wire the Qodana Gradle plugin via the version catalog

**Files:**
- Modify: `gradle/libs.versions.toml` (`[versions]` ~line 35, `[plugins]` ~line 100-112)
- Modify: `build.gradle.kts:11-21` (plugins block), and add a `qodana { }` block

**Interfaces:**
- Produces: the `qodanaScan` Gradle task (registered by the plugin) and a `qodana { }` extension with `projectPath`/`resultsPath`/`cachePath`/`qodanaPath` string properties. Later tasks consume `qodanaScan` and the SARIF at `build/qodana/results/qodana.sarif.json`.

- [ ] **Step 1: Add version + plugin to the catalog**

In `gradle/libs.versions.toml`, under `[versions]` (after `champeau-jmh = "0.7.3"` at line 35), add:

```toml
qodana = "2026.1.3"
```

Under `[plugins]` (after the `test-retry` line, ~line 112), add:

```toml
qodana = { id = "org.jetbrains.qodana", version.ref = "qodana" }
```

- [ ] **Step 2: Apply the plugin + configure the extension**

In `build.gradle.kts`, add to the `plugins { }` block (after `alias(libs.plugins.test.retry)` at line 20):

```kotlin
  alias(libs.plugins.qodana)
```

Then add a `qodana { }` config block (place it after the `kover { ... }` line at line 233):

```kotlin
// Qodana static analysis. Opt-in like the Core-IT tests — NOT wired into `check`/`build`, since
// `qodanaScan` needs Docker (the CLI runs the free `jetbrains/qodana-jvm-community` linter in a
// container). Run locally before pushing with `colima start && ./gradlew qodanaScan`; results
// (incl. qodana.sarif.json) land in `build/qodana/results`. See DEVELOPMENT.md > Static Analysis.
qodana {
  // Persist the linter image/cache outside `build/` so `clean` doesn't force a re-pull every run.
  cachePath.set(layout.projectDirectory.dir(".qodana/cache").asFile.absolutePath)
}
```

- [ ] **Step 3: Verify the plugin resolves and the task exists**

Run: `./gradlew help --task qodanaScan`
Expected: prints the `qodanaScan` task's help (Detailed usage / description "Starts Qodana Inspections..."). If the plugin fails to resolve behind the SFDC proxy, the `nexusGradlePlugins` fallback in `settings.gradle.kts` handles it (no action needed on a public machine).

- [ ] **Step 4: Format**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL; `build.gradle.kts` reformatted if needed.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build(qodana): wire org.jetbrains.qodana plugin via version catalog"
```

---

### Task 2: Rewrite `qodana.yaml` for the free Community linter + JDK 21

**Files:**
- Modify: `qodana.yaml` (full rewrite)

**Interfaces:**
- Produces: the analysis config `qodanaScan` (local) and `qodana-action` (CI) both read. Sets linter, JDK, bootstrap, excludes.

- [ ] **Step 1: Replace the file contents**

Overwrite `qodana.yaml` with:

```yaml
#-------------------------------------------------------------------------------#
#               Qodana analysis is configured by qodana.yaml file               #
#             https://www.jetbrains.com/help/qodana/qodana-yaml.html            #
#-------------------------------------------------------------------------------#
version: "1.0.0"

# Free Community linter (analysis runs in Docker; Qodana Cloud only stores reports/baselines).
# The paid Ultimate/Ultimate-Plus linters add Spring/SQL/taint/SCA — NOT used here (no license,
# and there is no free Ultimate for OSS). See docs/superpowers/specs/2026-07-22-qodana-setup-design.md.
linter: jetbrains/qodana-jvm-community:latest

# ReVoman requires JDK 21 (was wrongly '11').
projectJDK: "21"

profile:
  name: qodana.recommended

# Generate kapt/Immutables sources before analysis so Qodana doesn't flag false "unresolved
# reference" errors against generated code.
bootstrap: ./gradlew kaptKotlin classes

# Disabled inspections (kept from the prior config).
exclude:
  - name: DataClassPrivateConstructor
  - name: UnusedSymbol
  - name: All
    paths:
      - build
      - js
      - "**/generated/**"

# Report findings without failing the run initially (high threshold). Tighten later once the
# Critical+High backlog is cleared.
failureConditions:
  severityThresholds:
    any: 100000
```

- [ ] **Step 2: Validate YAML parses**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('qodana.yaml')); print('qodana.yaml OK')"`
Expected: `qodana.yaml OK`

- [ ] **Step 3: Commit**

```bash
git add qodana.yaml
git commit -m "build(qodana): modernize qodana.yaml — community linter, JDK 21, kapt bootstrap"
```

---

### Task 3: Add the GitHub Actions Qodana workflow (CI backstop)

**Files:**
- Create: `.github/workflows/qodana.yml`

**Interfaces:**
- Consumes: `qodana.yaml` (Task 2), repo secret `QODANA_TOKEN` (set in Task 4).
- Produces: a CI job that runs the Community linter in the runner's Docker, uploads SARIF to GitHub code scanning, reports to Qodana Cloud.

- [ ] **Step 1: Create the workflow**

Create `.github/workflows/qodana.yml` with:

```yaml
name: 'Qodana'

on:
  push:
    branches: [master]
  pull_request:
    branches: [master]

# One in-flight run per branch/PR; a newer push cancels the older run.
concurrency:
  group: qodana-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read
  # Required to upload SARIF to GitHub code scanning (free for public repos).
  security-events: write
  # Required by qodana-action to post PR annotations.
  pull-requests: write
  checks: write

jobs:
  qodana:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      # qodana-action needs full history for baseline/diff; fetch-depth: 0.
      - uses: actions/checkout@main
        with:
          fetch-depth: 0
          ref: ${{ github.event.pull_request.head.sha }}

      - uses: actions/setup-java@main
        with:
          distribution: jetbrains
          java-version: 21

      # Community linter runs in the runner's preinstalled Docker — no colima needed on CI.
      # QODANA_TOKEN (repo secret) reports to the free Qodana Cloud tier. args pins the linter
      # so it matches the local qodana.yaml / plugin version.
      - name: 'Qodana Scan'
        uses: JetBrains/qodana-action@v2026.1
        env:
          QODANA_TOKEN: ${{ secrets.QODANA_TOKEN }}
        with:
          args: --linter,jetbrains/qodana-jvm-community:latest
          upload-result: true

      # Upload SARIF to GitHub code scanning (Security tab + inline PR annotations, free for
      # public repos). Runs even if the scan reports problems.
      - name: 'Upload SARIF to code scanning'
        uses: github/codeql-action/upload-sarif@main
        if: ${{ !cancelled() }}
        with:
          sarif_file: ${{ runner.temp }}/qodana/results/qodana.sarif.json
```

- [ ] **Step 2: Validate YAML parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/qodana.yml')); print('qodana.yml OK')"`
Expected: `qodana.yml OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/qodana.yml
git commit -m "ci(qodana): add Qodana workflow — community linter + code-scanning SARIF upload"
```

---

### Task 4: Set the `QODANA_TOKEN` repo secret (out-of-band, not committed)

**Files:** none (GitHub repo settings).

**Interfaces:**
- Produces: repo secret `QODANA_TOKEN` consumed by Task 3's workflow.

- [ ] **Step 1: Set the secret from an env var (never hardcode in a command that lands in history)**

The user provides the token via env. Run:

```bash
gh secret set QODANA_TOKEN --repo salesforce-misc/ReVoman --body "$QODANA_TOKEN_VALUE"
```

Where `QODANA_TOKEN_VALUE` is exported in the shell first (`export QODANA_TOKEN_VALUE=...`), so the raw token isn't inlined in the command.
Expected: `✓ Set Actions secret QODANA_TOKEN for salesforce-misc/ReVoman`

- [ ] **Step 2: Verify (name only; value is never shown)**

Run: `gh secret list --repo salesforce-misc/ReVoman`
Expected: a `QODANA_TOKEN` row.

- [ ] **Step 3: Remind the user to rotate**

⚠️ The token was pasted in chat → treat as compromised. After this task, regenerate it in Qodana Cloud (Project → Settings) and re-run Step 1 with the new value. No commit for this task.

---

### Task 5: Document the dev loop in `DEVELOPMENT.md` + `AGENTS.md`

**Files:**
- Modify: `DEVELOPMENT.md` (add a "Static Analysis / Qodana" section after "Commands to Build and Verify")
- Modify: `AGENTS.md` (add one pointer line near "## Test Automation" at line 21)

- [ ] **Step 1: Add the Qodana section to `DEVELOPMENT.md`**

Insert after the "Commands to Build and Verify" code block (before "## Continuous Integration"):

````markdown
## Static Analysis (Qodana)

ReVoman uses the [JetBrains Qodana](https://www.jetbrains.com/qodana/) Gradle plugin
(`org.jetbrains.qodana`) for static analysis. Run it **locally before pushing** — it's the
primary quality gate; CI (`.github/workflows/qodana.yml`) is only a backstop.

```bash
colima start                 # Qodana runs its linter in Docker; start the daemon first
./gradlew qodanaScan         # downloads the Qodana CLI + free community linter image, then scans
```

- Results (including `qodana.sarif.json`) land in `build/qodana/results`; the linter
  image/cache is kept in `.qodana/cache` so `clean` doesn't force a re-pull.
- The **free** `jetbrains/qodana-jvm-community` linter is used (configured in `qodana.yaml`).
  The paid Ultimate/Ultimate-Plus linters add Spring/SQL/taint/dependency-vulnerability
  inspections — not used here (no license; there is no free Ultimate for open source).
- `qodanaScan` is **opt-in** — it is NOT part of `./gradlew build` (which stays Docker-free),
  the same way the `integration.core.*` org tests are opt-in via `-PincludeCoreIT`.
- Docker needs ≥4 GB memory for the linter. If colima's VM is smaller, recreate it larger
  (e.g. `colima start --memory 6`).
````

- [ ] **Step 2: Add the pointer line to `AGENTS.md`**

Under `## Test Automation` (line 21), add a final bullet:

```markdown
- Run `./gradlew qodanaScan` (Qodana static analysis) before pushing — see @DEVELOPMENT.md > Static Analysis
```

- [ ] **Step 3: Format the docs**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL (the `documentation` spotless target trims `*.md`).

- [ ] **Step 4: Commit**

```bash
git add DEVELOPMENT.md AGENTS.md
git commit -m "docs(qodana): document local qodanaScan dev loop + CI backstop"
```

---

### Task 6: Run the scan and produce the Critical+High findings list

**Files:**
- Produces (untracked, gitignored): `build/qodana/results/qodana.sarif.json`
- Modify: `.gitignore` (ensure `.qodana/` is ignored)

**Interfaces:**
- Consumes: `qodanaScan` (Task 1), `qodana.yaml` (Task 2).
- Produces: `docs/superpowers/plans/2026-07-22-qodana-findings.md` — the bucketed Critical+High list that seeds Phase B.

- [ ] **Step 1: Ignore the Qodana cache dir**

Ensure `.gitignore` contains `.qodana/` (add it if absent). `build/` is already ignored.

- [ ] **Step 2: Start Docker**

Run: `colima start --memory 6`
Expected: colima status running (verify `docker info` succeeds).

- [ ] **Step 3: Run the scan**

Run: `./gradlew qodanaScan`
Expected: CLI downloads, linter image pulls (first run only), scan completes; SARIF at `build/qodana/results/qodana.sarif.json`. A non-zero "problems found" count is expected and does NOT fail the build (high `severityThresholds`).

- [ ] **Step 4: Extract + bucket Critical+High findings**

Parse the SARIF and group by rule + severity. Run:

```bash
python3 - <<'PY'
import json, collections
sarif = json.load(open("build/qodana/results/qodana.sarif.json"))
buckets = collections.defaultdict(list)
for run in sarif.get("runs", []):
    rules = {r["id"]: r for r in run.get("tool", {}).get("driver", {}).get("rules", [])}
    for res in run.get("results", []):
        sev = (res.get("properties", {}) or {}).get("qodanaSeverity") \
              or res.get("level", "")
        if sev.lower() not in ("critical", "high", "error"):
            continue
        rule = res.get("ruleId", "?")
        loc = res.get("locations", [{}])[0].get("physicalLocation", {})
        f = loc.get("artifactLocation", {}).get("uri", "?")
        line = loc.get("region", {}).get("startLine", "?")
        buckets[rule].append(f"{f}:{line}")
for rule, hits in sorted(buckets.items(), key=lambda kv: -len(kv[1])):
    print(f"\n## {rule} ({len(hits)})")
    for h in hits: print(f"  - {h}")
PY
```

Expected: a per-rule breakdown of Critical+High hits with file:line locations.

- [ ] **Step 5: Write the findings doc**

Write `docs/superpowers/plans/2026-07-22-qodana-findings.md`: group the Step-4 rules into
**fix categories** (e.g. null-safety, dead-code, probable-bug, resource-leak, API-misuse,
exception-handling). For each category list: the Qodana rule id(s), the file:line hits, and a
one-line fix approach. This doc is the input to Phase B — one category = one worktree agent.

- [ ] **Step 6: Commit the findings doc**

```bash
git add .gitignore docs/superpowers/plans/2026-07-22-qodana-findings.md
git commit -m "docs(qodana): record Critical+High findings bucketed by fix category"
```

**⏸ CHECKPOINT — stop here for the human to review the findings before Phase B.** The exact fix
tasks depend on what the scan surfaced; Phase B below is the template applied per category.

---

# Phase B — Fix loop (templated; instantiated per category after Task 6)

> Phase B cannot be fully specified until Task 6 produces the findings doc. After the checkpoint,
> instantiate one **Task B-N** per fix category from `2026-07-22-qodana-findings.md`, each run by a
> subagent in its own git worktree (`isolation: worktree`) so parallel edits don't collide. Fixes
> merge back to `chore/qodana-setup-and-fixes`, one commit per category. Use
> `superpowers:dispatching-parallel-agents` to fan out.

### Task B-N template: Fix category `<CATEGORY>` (e.g. null-safety)

**Files:**
- Modify: the file:line hits listed under `<CATEGORY>` in `2026-07-22-qodana-findings.md`
- Test: the corresponding `src/test/**` or `src/integrationTest/**` covering the changed code

**Interfaces:**
- Consumes: the findings doc's `<CATEGORY>` bucket.
- Produces: those findings resolved (real fixes, no suppressions), tests green.

- [ ] **Step 1: Reproduce/understand the finding**

For each hit, read the flagged code and the Qodana rule description. Confirm it's a real defect
(not a false positive). If false positive, note it in the findings doc and skip (do NOT suppress).

- [ ] **Step 2: Write/adjust a failing test where behavior is at stake**

If the fix changes runtime behavior (e.g. a null-safety or probable-bug fix), add a test that
fails against the current code. For pure dead-code/redundancy removals with no behavior change,
rely on the existing suite instead.

Run the new test: `./gradlew test --tests "<TestClass>"` (unit) or
`./gradlew integrationTest --tests "<TestClass>"` (integration)
Expected: FAIL (for behavior-change fixes).

- [ ] **Step 3: Apply the fix**

Fix the real issue. Kotlin: follow `STYLE.md` (functional, monadic ops, immutable flow). Java:
follow the `my-java-coding-style` skill (functional, `final var`, Vavr `Either`/`Try`, no bare
null). No annotations/baseline/exclude to silence the finding.

- [ ] **Step 4: Verify tests pass + finding gone**

Run: `./gradlew spotlessApply` then the relevant `test`/`integrationTest` filter.
Expected: PASS.
Then re-scan the touched scope if practical (or defer to the final full re-scan in Task 7).

- [ ] **Step 5: Commit (one per category)**

```bash
git add <changed files>
git commit -m "fix(qodana): resolve <CATEGORY> findings (<rule-ids>)"
```

---

### Task 7: Integrate, verify, and open the PR

**Files:** none new (integration of Phase B worktrees).

- [ ] **Step 1: Merge all worktree branches back to `chore/qodana-setup-and-fixes`**

Collect each Phase-B worktree's category commit onto the single feature branch (fast-forward or
cherry-pick the category commits). Resolve any incidental conflicts.

- [ ] **Step 2: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — unit (`test`) + `integrationTest` + `spotlessCheck` + `detekt` + `kover` all pass.

- [ ] **Step 3: Re-scan to confirm the backlog dropped**

Run: `./gradlew qodanaScan` then re-run the Task-6 Step-4 extractor.
Expected: Critical+High count materially lower (ideally 0 except documented false positives).

- [ ] **Step 4: Push + open one PR**

```bash
git push -u origin chore/qodana-setup-and-fixes
gh pr create --repo salesforce-misc/ReVoman --base master \
  --title "chore(qodana): set up Qodana static analysis + fix Critical/High findings" \
  --body "Wires the org.jetbrains.qodana plugin (local pre-push gate + CI backstop), modernizes qodana.yaml (community linter, JDK 21), and fixes all Critical+High inspection findings. See docs/superpowers/specs/2026-07-22-qodana-setup-design.md."
```

Expected: PR created; the `Qodana` workflow runs on it → SARIF into code scanning, loop closed.

- [ ] **Step 5: Confirm CI + code scanning**

Run: `gh pr checks --repo salesforce-misc/ReVoman` (once CI starts)
Expected: the `Qodana` and `Build and Scan` checks appear; Security tab shows the code-scanning results.

---

## Self-Review

**Spec coverage:**
- Spec §1 Plugin wiring → Task 1 ✅
- Spec §2 `qodana.yaml` rewrite → Task 2 ✅
- Spec §3 GH Actions workflow → Task 3 ✅; secret handling → Task 4 ✅
- Spec §4 Docs (DEVELOPMENT.md/AGENTS.md) → Task 5 ✅
- Spec §5 Run the scan → Task 6 ✅
- Spec §6 Parallel worktree fix teams → Phase B template + `dispatching-parallel-agents` note ✅
- Spec §7 Integration & delivery (one branch, grouped commits, re-scan, one PR) → Task 7 ✅
- Spec Testing (build green, reduced findings, tests for changed code) → Task 7 Steps 2-3 + Task B-N Step 2 ✅
- Spec Risks (Docker mem, kapt bootstrap, volume, token rotation) → Task 6 Step 2 (`--memory 6`), Task 2 bootstrap, Phase B staging note, Task 4 Step 3 ✅

**Placeholder scan:** Phase B is intentionally templated (findings unknowable pre-scan) with a
hard checkpoint after Task 6 — this is a staged dependency, not a placeholder. All Phase A steps
carry exact file paths, code, and commands.

**Type consistency:** task name `qodanaScan`, extension props (`cachePath`), SARIF path
(`build/qodana/results/qodana.sarif.json` local; `${runner.temp}/qodana/results/...` on CI —
different because the CI action uses its own workdir), linter `jetbrains/qodana-jvm-community:latest`,
and branch `chore/qodana-setup-and-fixes` are consistent throughout.

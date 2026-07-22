# Qodana Setup + Inspection Fix — Design

**Date:** 2026-07-22
**Status:** Approved for planning
**Author:** Gopal S Akshintala (with Claude)

## Goal

Wire the JetBrains Qodana Gradle plugin into ReVoman following project conventions,
make a local `qodanaScan` the pre-push static-analysis gate in the dev loop, add a
GitHub Actions backstop, run the inspections, and fix the Critical + High findings via
parallel worktree agents landing on a single feature branch.

## Ground-truth facts (verified against the plugin jar `2026.1.3` and the repo)

- **Plugin:** `org.jetbrains.qodana`, latest `2026.1.3`. Requires Gradle 6.6+.
- **Task:** `qodanaScan` (the old `runInspections` task from stale docs no longer exists).
  The task downloads the Qodana **CLI** natively from GitHub releases; the CLI runs the
  **linter in Docker**. Default output dirs: `build/qodana/results`, `build/qodana/cache`.
- **Extension** `qodana { }` props: `projectPath`, `resultsPath`, `cachePath`, `qodanaPath`.
  Task props: `arguments` (`List<String>`), `useNightly` (`Boolean`).
- **Linter tiers:** Community (free) vs Ultimate vs Ultimate Plus. Analysis ALWAYS runs the
  linter in Docker — **Qodana Cloud has no compute** and never runs analysis; it only stores
  reports/baselines/trends. There is **no free Ultimate license for open source** — public
  repos get Community only. Local vs CI is only *where Docker runs*; findings are identical.
  Community lacks Spring + SQL inspections and the Ultimate-Plus-only taint/SCA/license-audit.
  We use **Community**; the user's `QODANA_TOKEN` adds free Cloud reporting only.
- **Repo:** `salesforce-misc/ReVoman` is **PUBLIC** → unlimited Actions minutes + free SARIF
  upload to GitHub code scanning; no subscription needed.
- **Existing `qodana.yaml` is stale/wrong:** `linter: jetbrains/qodana-jvm:latest` is the
  **paid** linter (must be `-community`), `projectJDK: '11'` is wrong (repo needs 21),
  schema `version: "1.0"` is old.
- **Local Docker not currently running** (`docker`/`colima` installed, daemon down) — a local
  scan needs `colima start` first.

## Non-goals

- No paid Ultimate/Ultimate-Plus license. (An optional one-off Ultimate-Plus *trial* scan to
  eyeball deeper findings is explicitly out of scope for this pass.)
- No fixing of Medium/Low/weak-warning findings this pass.
- No suppressing findings to silence them. Fix the real issue or leave it and report why.
- No wiring `qodanaScan` into `check`/`build` (would force Docker on every build).

## Design

### 1. Plugin wiring (version-catalog convention)

`gradle/libs.versions.toml`:
- `[versions]`: `qodana = "2026.1.3"`
- `[plugins]`: `qodana = { id = "org.jetbrains.qodana", version.ref = "qodana" }`

`build.gradle.kts`:
- Add `alias(libs.plugins.qodana)` to the `plugins { }` block.
- Add a `qodana { }` config block (results stay at the default `build/qodana/results`;
  set `cachePath` to a stable dir so the linter image/cache persists across runs).
- **Not** added to `check`/`build`. `qodanaScan` stays opt-in, mirroring the opt-in
  `-PincludeCoreIT` pattern.

### 2. `qodana.yaml` rewrite (latest schema + free path)

- `version: "1.0.0"` (current schema).
- `linter: jetbrains/qodana-jvm-community:latest` (free Community linter).
- `projectJDK: "21"`.
- `bootstrap: ./gradlew kaptKotlin classes` — generate kapt/Immutables sources before
  analysis so Qodana doesn't report false "unresolved reference" errors.
- Keep existing `exclude` (`DataClassPrivateConstructor`, `UnusedSymbol`); add path excludes
  for `build/`, `js/`, `**/generated/**`.
- Modern `failureConditions.severityThresholds` (replaces the old `failThreshold`), set high
  initially so the scan reports without failing the build.

### 3. GitHub Actions workflow (CI backstop) — `.github/workflows/qodana.yml`

- Triggers: `pull_request` + `push` to `master`.
- `JetBrains/qodana-action@v2026.1` (matches plugin/linter version) on `ubuntu-latest`
  (Docker preinstalled — no colima).
- `env: QODANA_TOKEN: ${{ secrets.QODANA_TOKEN }}` → reports to free Qodana Cloud.
- Upload SARIF via `github/codeql-action/upload-sarif` → GitHub code scanning (free, public
  repo). Findings appear in the Security tab + inline on PRs.
- Purpose is **backstop**: nothing merges unscanned even if a dev skips the local run. Local
  `qodanaScan` remains the primary pre-push gate.

**Secret handling:** `QODANA_TOKEN` goes ONLY into a GitHub Actions secret
(`gh secret set QODANA_TOKEN`, read from env — never committed). The token the user pasted in
chat is considered compromised and **must be rotated** in Qodana Cloud after wiring.

### 4. Docs — `DEVELOPMENT.md` + `AGENTS.md`

`CLAUDE.md` → `@AGENTS.md` → `@DEVELOPMENT.md`, so dev-loop instructions live in
**`DEVELOPMENT.md`**. Add a "Static Analysis / Qodana" section:
- `colima start` prerequisite + `./gradlew qodanaScan`.
- Results path, Community-linter/free-tier note, "run before pushing" guidance.
- CI backstop note.
Add one pointer line in `AGENTS.md` near the Test Automation section.

### 5. Run the scan (local, primary)

1. `colima start` (Docker up).
2. `./gradlew qodanaScan` → downloads CLI + Community linter image (needs Docker ≥4GB mem),
   writes SARIF to `build/qodana/results/qodana.sarif.json`.
3. Parse SARIF, filter to **Critical + High** severities, group by rule + file into fixable
   category buckets (e.g. null-safety, dead-code, probable-bug, resource-leak, API-misuse).
4. **Fallback** if local Docker is uncooperative: pull the SARIF from the first CI run of the
   branch instead. Either way, a concrete Critical+High list precedes any fixing.

### 6. Parallel worktree fix teams

Via `superpowers:dispatching-parallel-agents` + git worktrees:
- One subagent per finding category, each in its own worktree (`isolation: worktree`) so
  parallel edits never collide.
- Each agent: fix only its bucket; follow `STYLE.md` (functional Kotlin) and the
  `my-java-coding-style` skill for any `.java`; add/adjust tests; run `./gradlew spotlessApply`
  + relevant `test`/`integrationTest`; verify its findings are gone.
- Hard rule: **no behavior changes to game a finding, no suppressions.** Fix the real issue or
  leave it and report why.

### 7. Integration & delivery

- Collect each worktree's diff onto **one** feature branch `chore/qodana-setup-and-fixes`,
  **one commit per finding-category** (plus a setup commit for plugin+yaml+workflow+docs).
- Re-run `./gradlew build` (unit + integration + spotless + detekt) on the merged branch —
  all must pass.
- Re-run `qodanaScan` — confirm Critical+High count dropped.
- Open **one PR**. CI's Qodana workflow runs on it → SARIF back into code scanning, loop closed.

## Testing

- `./gradlew build` green on the final branch (unit + integration + spotlessCheck + detekt + kover).
- `./gradlew qodanaScan` re-run shows reduced Critical+High.
- Any code changed by fix agents carries tests covering the corrected behavior.
- Workflow validated (YAML lints; action/linter versions pinned and consistent).

## Risks / open items

- **Docker memory:** Community linter needs ≥4GB. If colima's default VM is smaller, bump it.
- **kapt bootstrap:** if generated sources still cause false positives, refine the `bootstrap`
  command or add targeted `exclude` paths.
- **Finding volume:** if Critical+High is very large, we bucket and may stage across multiple
  fix rounds rather than one — the branch/PR stays single.
- **Token rotation:** user must rotate `QODANA_TOKEN` post-setup (exposed in chat).

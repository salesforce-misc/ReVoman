# Performance Platform Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the complete `origin/overfullstack/perf` history into the current master lineage without importing unrelated production changes, and leave one supported formal-performance platform ready for native-Linux extension.

**Architecture:** Create a fresh branch from the latest fetched `origin/master`, make a true two-parent merge with `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0`, and reconcile the four overlapping paths explicitly. Preserve the platform's frozen distribution, runner, schemas, comparator, finalizers, V3 workload, and failure matrices; retire the old V1 timing path as a supported claim path while preserving historical evidence.

**Tech Stack:** Git, Gradle Kotlin DSL, Kotlin/JVM 21, JMH, Kotest/JUnit Platform, GitHub Actions, Qodana.

**Spec:** `docs/superpowers/specs/2026-08-21-merge-first-performance-workflow-design.md`

## Global Constraints

- Never edit, switch, merge, reset, rebase, or commit in `/home/gopala.akshintala/code-clones/work/revoman-root`.
- The protected root must remain at `47d03c0fc3b0b01ac06d7a3a80bf925ae5ce201e` with only `.idea/kotlinc.xml` modified and SHA-256 `c995703f125cf3ad057ffdd509b211bd3a5533c22307a17323fef86cb3c9b694`.
- Never rewrite a published branch or delete an existing worktree or performance artifact.
- The integration branch starts from the latest fetched `origin/master`; record that full SHA before merging.
- The merge must retain `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0` as a parent and ancestor.
- Do not add either completed optimization to this branch.
- Do not run formal A/B comparisons in this plan.
- Java support is exactly major version 21; no Java vendor is required.
- Use pnpm for every explicit npm package installation.
- Do not push, open, update, or merge a PR until the remote-state gate is explicitly authorized.

---

## File and Module Map

- `buildSrc/performance-runner/`: retain the pure Kotlin runner, capture/comparison/campaign engines, strict schemas, finalizers, publication, and tests from the platform branch.
- `buildSrc/src/main/kotlin/performance/`: retain the Gradle distribution-freeze and validation seam.
- `scripts/performance/run`: retain the thin host adapter; native Linux changes belong to the next plan.
- `config/performance/`: retain expected cells, profiles, runtime declarations, and policies.
- `src/jmh/` and `src/jmhTest/`: retain the V3 cold/warm workload and structural canary; preserve current-master diagnostic benchmarks that are not name collisions.
- `.github/workflows/`: retain hardened build/Qodana behavior and the hosted diagnostic canary; remove the old supported timing workflow.
- `DEVELOPMENT.md`, `docs/modules/ROOT/pages/performance.adoc`, and `README.adoc`: describe one formal platform and clearly separate canonical claims from diagnostics.
- `api/revoman-root.api`: regenerate from the merged master production source; do not accept the platform branch's stale snapshot.

### Task 1: Create and Audit the True Merge

**Files:**
- Modify through merge: repository tree in a new isolated worktree
- Inspect: `AGENTS.md`
- Inspect: `DEVELOPMENT.md`
- Inspect: `STYLE.md`

**Interfaces:**
- Consumes: fetched refs `origin/master` and `origin/overfullstack/perf`
- Produces: an uncommitted two-parent merge with exactly the expected conflict set and one audited
  auto-merged overlap

- [ ] **Step 1: Reverify protected state and fetch without touching the root worktree**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root rev-parse HEAD
git -C /home/gopala.akshintala/code-clones/work/revoman-root status --short
sha256sum /home/gopala.akshintala/code-clones/work/revoman-root/.idea/kotlinc.xml
git -C /home/gopala.akshintala/code-clones/work/revoman-root fetch origin master overfullstack/perf
```

Expected: the protected values match the global constraints, and fetching changes refs only.

- [ ] **Step 2: Record immutable inputs and create a fresh implementation worktree**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root rev-parse origin/master
git -C /home/gopala.akshintala/code-clones/work/revoman-root rev-parse origin/overfullstack/perf
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base origin/master origin/overfullstack/perf
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add -b codex/perf-platform-integration-2026-08-21 /home/gopala.akshintala/code-clones/work/revoman-perf-platform-integration-20260821 origin/master
```

Expected: the first value becomes `PLATFORM_MASTER_SHA`; the worktree is clean and its `HEAD` equals that SHA.

- [ ] **Step 3: Verify the overlap before merging**

```bash
comm -12 \
  <(git diff --name-only 009bc8f4c1fe9fb7d393036616a3c3b6cd787aca..origin/master | LC_ALL=C sort) \
  <(git diff --name-only 009bc8f4c1fe9fb7d393036616a3c3b6cd787aca..origin/overfullstack/perf | LC_ALL=C sort)
```

Expected, exactly:

```text
DEVELOPMENT.md
build.gradle.kts
gradle/libs.versions.toml
src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt
```

- [ ] **Step 4: Start the merge without committing**

```bash
git merge --no-ff --no-commit e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0
git diff --name-only --diff-filter=U
```

Expected, exactly:

```text
DEVELOPMENT.md
build.gradle.kts
src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt
```

`gradle/libs.versions.toml` is the fourth overlapping path but Git auto-merges it. Inspect it as a
deliberate resolution in Task 2. If the conflict set differs, abort this isolated merge with
`git merge --abort`, refetch, and re-audit before continuing.

### Task 2: Resolve the Four Overlaps Without Production Drift

**Files:**
- Modify: `DEVELOPMENT.md`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Delete through resolution: `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt`
- Retain: `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxCanaryBenchmark.kt`
- Test: `src/jmhTest/kotlin/com/salesforce/revoman/benchmark/SandboxCanaryContractTest.kt`
- Test: `buildSrc/src/test/kotlin/performance/PerformanceMeasurementPluginTest.kt`

**Interfaces:**
- Consumes: the uncommitted merge from Task 1
- Produces: a resolved tree with current-master build semantics plus the formal performance convention and canary

- [ ] **Step 1: Resolve the benchmark name collision**

Delete the obsolete `SandboxBenchmark.kt`; retain the platform's formal class and its exact benchmark identity:

```kotlin
@State(Scope.Benchmark)
open class SandboxCanaryBenchmark {
  @Benchmark
  fun bootstrap(): Any = PmSandbox.create()
}
```

Keep `RuntimeLifecycleBenchmark.kt`, `EnvAccumBenchmark.kt`, `MarshallingBenchmark.kt`, and `RegexVarBenchmark.kt` from current master as diagnostic-only benchmarks.

- [ ] **Step 2: Resolve the version catalog**

Use the current-master value for every coordinate already present there. Add only platform-required aliases absent from master, including the JMH Gradle plugin and runner libraries referenced by `buildSrc/performance-runner/build.gradle.kts`.

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem dependencies
```

Expected: Gradle resolves without an unknown alias or implicit downgrade.

- [ ] **Step 3: Resolve the root build script**

Preserve all current-master production, test, lifecycle-benchmark, Kotlin, and dependency behavior. Integrate these platform contracts:

```kotlin
plugins {
  id("revoman.performance-conventions")
}

performanceMeasurement {
  treatmentJar.convention(tasks.named<Jar>("jar").flatMap { it.archiveFile })
  profileDirectory.set(layout.projectDirectory.dir("config/performance/profiles"))
  expectedCells.set(layout.projectDirectory.file("config/performance/expected-cells.json"))
  adapter.set(layout.projectDirectory.file("scripts/performance/run"))
}
```

Also retain the V3 fixture manifest generation, `jmhTest` suite, runtime/qualification input preparation, protocol closure inputs, and strict rejection of unsupported flattened JMH tasks from the platform branch.

- [ ] **Step 4: Resolve development documentation**

Preserve all current-master setup and test guidance, then add one performance section with these exact classifications:

```text
Formal claims: only finalized, checksum-valid, claimEligible=true controlled-host campaigns.
Diagnostics: direct JMH, hosted canaries, standalone captures/comparisons, GC, and JFR.
The optimization commit need not contain the runner; freeze its jar with a runner already merged to master.
```

- [ ] **Step 5: Assert that merge resolution did not change production behavior**

```bash
git diff --exit-code "$(git rev-parse HEAD)" -- src/main src/test src/integrationTest
git diff --name-only --diff-filter=U
```

Expected: both commands produce no output. The first parent is still the pre-merge master commit while `HEAD` names that commit during the uncommitted merge.

- [ ] **Step 6: Run focused RED/GREEN contracts**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem \
  buildSrc:test \
  :buildSrc:performance-runner:test \
  jmhTest
```

Expected: all platform, adapter, workflow-security, distribution, runner, and JMH contracts pass.

- [ ] **Step 7: Commit the history-preserving merge**

```bash
git add DEVELOPMENT.md build.gradle.kts gradle/libs.versions.toml src/jmh buildSrc config scripts .github docs README.adoc qodana.yaml api/revoman-root.api
git commit -m "merge: integrate formal performance platform"
git show --no-patch --format='%H%n%P%n%s' HEAD
```

Expected: the commit has two parents; the second is `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0`.

### Task 3: Reconcile Master-Only Performance Paths and ABI Evidence

**Files:**
- Delete: `.github/workflows/benchmark.yml`
- Delete: `Dockerfile.perf`
- Delete: `scripts/compare-jmh.py`
- Delete: `scripts/tests/test_compare_jmh.py`
- Delete: `scripts/perf-docker`
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/performance-campaign.yml`
- Modify: `.github/workflows/qodana.yml`
- Modify: `docs/modules/ROOT/pages/performance.adoc`
- Modify: `api/revoman-root.api`
- Add: `docs/superpowers/specs/2026-08-21-merge-first-performance-workflow-design.md`
- Add: the three `docs/superpowers/plans/2026-08-21-*.md` plans

**Interfaces:**
- Consumes: the two-parent merge commit
- Produces: one supported performance entry point and an ABI snapshot generated from current master production code

- [ ] **Step 1: Write the legacy-path failure contract**

Add assertions to `buildSrc/src/test/kotlin/performance/DocumentationContractTest.kt`:

```kotlin
import io.kotest.matchers.file.shouldNotExist

test("legacy timing entry points are retired") {
  listOf(
      ".github/workflows/benchmark.yml",
      "Dockerfile.perf",
      "scripts/compare-jmh.py",
      "scripts/tests/test_compare_jmh.py",
      "scripts/perf-docker",
    )
    .forEach { path -> repositoryPath(path).toFile().shouldNotExist() }
}
```

- [ ] **Step 2: Run the contract and observe the expected failure**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem buildSrc:test --tests performance.DocumentationContractTest
```

Expected: FAIL because at least one legacy path still exists.

- [ ] **Step 3: Remove only the obsolete supported paths**

Delete the five paths named in Step 1. Do not delete `docs/superpowers/benchmarks/`, `build/perf-hotspots/`, or any prior report/artifact.

- [ ] **Step 4: Keep CI diagnostic and privacy-safe**

The build workflow may freeze and validate a distribution and run a structural canary. The hosted workflow must keep the following semantics:

```yaml
name: 'Hosted performance diagnostic'

permissions:
  contents: read

# Uploaded hosted results are diagnostic and ClaimEligible=false.
```

Retain action SHA pinning, credential scrubbing, sanitized artifact roots, and Qodana image digest pinning from the platform branch.

- [ ] **Step 5: Regenerate and review ABI evidence**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem updateKotlinAbi
git diff -- api/revoman-root.api
git diff --exit-code HEAD^1 -- src/main src/test src/integrationTest
```

Expected: the API dump reflects current-master production code and the production-source diff remains empty.

- [ ] **Step 6: Rerun the focused contract and commit reconciliation**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem buildSrc:test --tests performance.DocumentationContractTest
git add .github Dockerfile.perf README.adoc DEVELOPMENT.md api build.gradle.kts buildSrc config docs gradle qodana.yaml scripts src/jmh src/jmhTest
git commit -m "build(perf): reconcile platform with current master"
```

Expected: PASS, and the commit contains no `src/main`, `src/test`, or `src/integrationTest` diff against the recorded master base.

### Task 4: Verify PR A and Hold the Remote Gate

**Files:**
- Inspect: complete branch diff and commit graph
- Test: all Gradle verification suites

**Interfaces:**
- Consumes: the locally complete PR A branch
- Produces: a review packet; no remote state changes

- [ ] **Step 1: Prove topology and scope**

```bash
git merge-base --is-ancestor e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0 HEAD
git log --merges --format='%H %P %s' "${PLATFORM_MASTER_SHA}..HEAD"
git diff --exit-code "${PLATFORM_MASTER_SHA}" -- src/main src/test src/integrationTest
git diff --stat "${PLATFORM_MASTER_SHA}..HEAD"
```

Expected: ancestry succeeds, exactly one integration merge is present, and production/test source has no diff.

- [ ] **Step 2: Run the full verification matrix**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem clean test integrationTest jmhTest buildSrc:test :buildSrc:performance-runner:test checkKotlinAbi verifyPerformanceDistribution
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem qodanaScan
```

Expected: every task passes. Save command, exit status, duration, and report paths in the review packet.

- [ ] **Step 3: Reverify the protected root**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root rev-parse HEAD
git -C /home/gopala.akshintala/code-clones/work/revoman-root status --short
sha256sum /home/gopala.akshintala/code-clones/work/revoman-root/.idea/kotlinc.xml
```

Expected: the global protected-state values are unchanged.

- [ ] **Step 4: Request independent standards/spec review**

The reviewer must separately report:

```text
Standards: repository style, build security, tests, Qodana, documentation.
Spec: two-parent ancestry, four conflict resolutions, no production drift, V1 retirement, preserved artifacts, no formal comparison.
```

- [ ] **Step 5: Stop at the remote-state gate**

Do not push or open the PR until explicitly authorized. When authorized, push this new branch, open PR A against `master`, require GitHub merge-commit mode, then fetch and prove both the PR merge SHA and `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0` are ancestors of `origin/master` before starting the Linux plan.

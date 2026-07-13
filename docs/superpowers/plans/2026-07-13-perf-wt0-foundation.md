# Perf WT-0: Foundation (deps + JMH) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the shared build foundation — the `truffle-runtime` optimizing GraalJS dependency, the `kotlinx-collections-immutable` dependency, and a working JMH benchmark module with a baseline-capture procedure — so the four domain worktrees (WT-1..WT-4) can branch off it and each add their own benchmarks and fixes conflict-free.

**Architecture:** This worktree touches ONLY build configuration — `gradle/libs.versions.toml` and `build.gradle.kts` — plus one trivial Kotlin benchmark and one markdown procedure doc. No application/source-logic changes. The "tests" for this build/config plan are Gradle invocations that must succeed: `./gradlew dependencies` (deps resolve), `./gradlew tasks` (the JMH plugin applies on the pinned Gradle), `./gradlew jmh -Pjmh.includes=SmokeBenchmark` (the harness runs end-to-end), and `./gradlew build test integrationTest` (nothing regresses). Every task ends with a real verification command, its expected output, and a conventional commit.

**Tech Stack:** Kotlin 2.4.20-Beta1, Gradle (wrapper 9.7.0-milestone-3), JMH via me.champeau.jmh, GraalVM 25.1.3, kotlinx.collections.immutable

## Global Constraints
- JDK 21+ required
- All existing tests must stay green: `./gradlew test integrationTest`
- Preserve the functional-programming style (STYLE.md): Either/map/flatMap/fold, immutable flow — no imperative rewrites
- `./gradlew spotlessApply` before every commit
- Prefer `./gradlew` (wrapper); fall back to installed `gradle` only if the distribution can't be fetched

## Versions to verify before/while implementing (no guessing at merge time)
- **`champeau-jmh = "0.7.3"`** (the `me.champeau.jmh` plugin) — **must be confirmed compatible with the pinned Gradle `9.7.0-milestone-3`.** Task 3's verification IS this gate. If the plugin fails to apply on the milestone Gradle, use the manual-JMH fallback documented under Task 3 (no plugin: raw `jmh` source set + `org.openjdk.jmh` deps + a `JavaExec` task).
- **`kotlinx-collections-immutable = "0.4.0"`** — confirm it resolves via `./gradlew dependencies`; bump to the newest stable if `0.4.0` is unresolved on the configured repos. On the JVM this multiplatform artifact resolves to the `-jvm` variant.
- **`jmh = "1.37"`** (JMH core, pinned via the `jmh { jmhVersion = … }` extension) — confirm it is the JMH core version the chosen plugin release expects; adjust if the plugin bundles a newer default.

---

### Task 1: Add the `truffle-runtime` optimizing GraalJS dependency
**Files:**
- Modify: `gradle/libs.versions.toml` (add a `[libraries]` entry after line 75)
- Modify: `build.gradle.kts:33` (add an `api` dependency next to `implementation(libs.graal.js)`)
**Interfaces:**
- Produces: `libs.truffle.runtime` catalog accessor, exposed as an `api` dependency so every ReVoman consumer (and WT-1's shared-`Engine` work) inherits the optimizing runtime (jargraal) on stock JDK 21.

- [ ] **Step 1: Add the catalog library entry.** In `gradle/libs.versions.toml`, in the `[libraries]` block, add this line immediately after the `mockk = …` line (currently line 75). Reuse the existing `graal` version (25.1.3):
  ```toml
  truffle-runtime = { module = "org.graalvm.truffle:truffle-runtime", version.ref = "graal" }
  ```
- [ ] **Step 2: Add the `api` dependency.** In `build.gradle.kts`, inside the `dependencies { … }` block, add this line immediately after `implementation(libs.graal.js)` (currently line 33) so both GraalJS artifacts sit together:
  ```kotlin
  api(libs.truffle.runtime)
  ```
- [ ] **Step 3: Run `./gradlew dependencies --configuration runtimeClasspath | grep -i truffle`** — Expected: a line resolving `org.graalvm.truffle:truffle-runtime:25.1.3` on the runtime classpath, and `BUILD SUCCESSFUL` for the underlying task. (If `grep` filters out the `BUILD SUCCESSFUL` line, re-run without the pipe to confirm success.)
- [ ] **Step 4: Format + commit.**
  ```bash
  ./gradlew spotlessApply
  git add gradle/libs.versions.toml build.gradle.kts
  git commit -m "build: add org.graalvm.truffle:truffle-runtime as api dep (optimizing GraalJS runtime)"
  ```

---

### Task 2: Add the `kotlinx-collections-immutable` dependency
**Files:**
- Modify: `gradle/libs.versions.toml` (add a `[versions]` entry after line 30 and a `[libraries]` entry after the Task 1 entry)
- Modify: `build.gradle.kts` (add an `implementation` dependency in the `dependencies` block)
**Interfaces:**
- Produces: `libs.kotlinx.collections.immutable` catalog accessor (`implementation` scope) — WT-4 uses it to back `PostmanEnvironment` with a persistent map (fix E2).

- [ ] **Step 1: Add the version.** In `gradle/libs.versions.toml`, in the `[versions]` block, add this line immediately after `gradle-taskinfo = "3.0.2"` (currently line 30):
  ```toml
  kotlinx-collections-immutable = "0.4.0"
  ```
- [ ] **Step 2: Add the catalog library entry.** In `gradle/libs.versions.toml`, in the `[libraries]` block, add this line immediately after the `truffle-runtime = …` line added in Task 1:
  ```toml
  kotlinx-collections-immutable = { module = "org.jetbrains.kotlinx:kotlinx-collections-immutable", version.ref = "kotlinx-collections-immutable" }
  ```
- [ ] **Step 3: Add the `implementation` dependency.** In `build.gradle.kts`, inside the `dependencies { … }` block, add this line immediately after `implementation(libs.graal.js)` / the Task 1 `api(libs.truffle.runtime)` line (grouping is cosmetic; any spot in the `implementation` group is fine):
  ```kotlin
  implementation(libs.kotlinx.collections.immutable)
  ```
- [ ] **Step 4: Run `./gradlew dependencies --configuration runtimeClasspath | grep -i collections-immutable`** — Expected: a line resolving `org.jetbrains.kotlinx:kotlinx-collections-immutable(-jvm):0.4.0`. If the line is ABSENT or shows `FAILED`, the version is wrong: run `./gradlew dependencies --configuration compileClasspath` to see the resolution error, bump `kotlinx-collections-immutable` in `[versions]` to the newest stable release, and re-run until it resolves. Record the version you settled on in the commit message.
- [ ] **Step 5: Format + commit.**
  ```bash
  ./gradlew spotlessApply
  git add gradle/libs.versions.toml build.gradle.kts
  git commit -m "build: add kotlinx-collections-immutable (persistent-map backing for env, WT-4)"
  ```

---

### Task 3: Register the `me.champeau.jmh` plugin (Gradle-9 compatibility gate)
**Files:**
- Modify: `gradle/libs.versions.toml` (`[versions]` after Task 2 entry; `[plugins]` after line 102)
- Modify: `build.gradle.kts:11-19` (add an `alias(...)` to the `plugins { }` block)
**Interfaces:**
- Produces: the applied `me.champeau.jmh` plugin and its task graph (`jmh`, `jmhJar`, `jmhClasses`, `compileJmhKotlin`) — the shared benchmark harness all domain worktrees build on. Also produces the `libs.plugins.jmh` catalog accessor.

- [ ] **Step 1: Add the plugin + JMH-core versions.** In `gradle/libs.versions.toml`, in the `[versions]` block, add these two lines immediately after the `kotlinx-collections-immutable = "0.4.0"` line from Task 2. (Distinct roots `jmh` and `champeau-jmh` avoid the version-catalog "cannot have both a value and sub-values" clash.)
  ```toml
  jmh = "1.37"
  champeau-jmh = "0.7.3"
  ```
- [ ] **Step 2: Add the plugin catalog entry.** In `gradle/libs.versions.toml`, in the `[plugins]` block, add this line immediately after `gradle-taskinfo = {id = "org.barfuin.gradle.taskinfo", version.ref = "gradle-taskinfo"}` (currently line 102):
  ```toml
  jmh = { id = "me.champeau.jmh", version.ref = "champeau-jmh" }
  ```
- [ ] **Step 3: Apply the plugin.** In `build.gradle.kts`, in the `plugins { }` block, add this line immediately after `alias(libs.plugins.nexus.publish)` (currently line 18), i.e. as the last alias before the closing `}`:
  ```kotlin
  alias(libs.plugins.jmh)
  ```
- [ ] **Step 4: Run `./gradlew tasks --all | grep -i jmh`** — Expected: the plugin applied cleanly on Gradle 9.7.0-milestone-3 and registered its tasks, e.g. lines containing `jmh`, `jmhJar`, `jmhClasses`, and `compileJmhKotlin`. Also confirm the overall invocation prints `BUILD SUCCESSFUL`.
  - **If this FAILS** (plugin incompatible with the milestone Gradle — a genuine risk with a pre-release Gradle): do NOT block. Fall back to a manual JMH setup and note it in the commit. Revert Steps 2-3 (keep the `[versions]` entries) and instead: (a) declare a `jmh` source set via `sourceSets { create("jmh") { … } }` extending the main output; (b) add `jmhImplementation("org.openjdk.jmh:jmh-core:1.37")` and `kapt("org.openjdk.jmh:jmh-generator-annprocess:1.37")` (JMH's `@Benchmark` annotation processor — kapt is already applied via `revoman.kt-conventions`); (c) register a `JavaExec` task named `jmh` whose `mainClass` is `org.openjdk.jmh.Main`, classpath is the `jmh` source set runtime classpath, and which forwards `-Pjmh.includes` as its first program argument. The Task 4/5 source-set path and `-Pjmh.includes` invocation stay identical, so WT-1..4 are unaffected.
- [ ] **Step 5: Format + commit.**
  ```bash
  ./gradlew spotlessApply
  git add gradle/libs.versions.toml build.gradle.kts
  git commit -m "build: apply me.champeau.jmh plugin for the benchmark module"
  ```

---

### Task 4: Wire `-Pjmh.includes`, add the JMH source set + smoke benchmark, run it
**Files:**
- Modify: `build.gradle.kts` (add a top-level `jmh { }` extension block after the `moshi { … }` block, ~line 155)
- Create: `src/jmh/kotlin/com/salesforce/revoman/benchmark/SmokeBenchmark.kt`
**Interfaces:**
- Produces: (1) the source-set path **`src/jmh/kotlin/com/salesforce/revoman/benchmark/`** — WT-1..WT-4 each drop their own `*Benchmark.kt` here; (2) the invocation contract **`./gradlew jmh -Pjmh.includes=<ClassSimpleName>`** — wired by the `jmh { }` block reading the `jmh.includes` project property; (3) a pinned JMH core version via `jmhVersion`.

- [ ] **Step 1: Add the `jmh { }` extension block.** In `build.gradle.kts`, after the `moshi { enableSealed = true }` line (currently line 155), add:
  ```kotlin
  jmh {
    // Pin JMH core so every worktree benchmarks against a known JMH release.
    jmhVersion = libs.versions.jmh.get()
    // Select benchmarks from the CLI, e.g. ./gradlew jmh -Pjmh.includes=SmokeBenchmark
    if (project.hasProperty("jmh.includes")) {
      includes.add(project.property("jmh.includes").toString())
    }
  }
  ```
  (This targets the champeau-jmh 0.7.x lazy-property API: `jmhVersion` is a `Property<String>` set via `=`, `includes` is a `ListProperty<String>` appended via `.add(...)`. If you took the manual-JMH fallback in Task 3, skip this block — the `JavaExec` task already reads `-Pjmh.includes`.)
- [ ] **Step 2: Create the smoke benchmark.** Create `src/jmh/kotlin/com/salesforce/revoman/benchmark/SmokeBenchmark.kt` with exactly this content (open class + open method so JMH can subclass/invoke it; named constant avoids the detekt `MagicNumber` rule; fast fork/iteration settings keep the smoke run to a few seconds):
  ```kotlin
  /**
   * ************************************************************************************************
   * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
   * Version 2.0 For full license text, see the LICENSE file in the repo root or
   * http://www.apache.org/licenses/LICENSE-2.0
   * ************************************************************************************************
   */
  package com.salesforce.revoman.benchmark

  import java.util.concurrent.TimeUnit
  import org.openjdk.jmh.annotations.Benchmark
  import org.openjdk.jmh.annotations.BenchmarkMode
  import org.openjdk.jmh.annotations.Fork
  import org.openjdk.jmh.annotations.Measurement
  import org.openjdk.jmh.annotations.Mode
  import org.openjdk.jmh.annotations.OutputTimeUnit
  import org.openjdk.jmh.annotations.Scope
  import org.openjdk.jmh.annotations.State
  import org.openjdk.jmh.annotations.Warmup

  private const val UPPER_BOUND = 100

  /**
   * Smoke benchmark proving the `jmh` source set compiles and the `me.champeau.jmh` toolchain runs
   * end-to-end on this project. The domain worktrees (WT-1..WT-4) drop their own `*Benchmark.kt`
   * into this same package; this file only validates the harness.
   */
  @State(Scope.Benchmark)
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @Fork(1)
  @Warmup(iterations = 1, time = 1)
  @Measurement(iterations = 1, time = 1)
  open class SmokeBenchmark {
    @Benchmark open fun sumOfRange(): Int = (1..UPPER_BOUND).sum()
  }
  ```
- [ ] **Step 3: Run `./gradlew jmh -Pjmh.includes=SmokeBenchmark`** — Expected: the run compiles `compileJmhKotlin`, executes JMH, prints a results table whose last data row contains `SmokeBenchmark.sumOfRange … avgt …`, writes results to `build/results/jmh/results.txt`, and ends with `BUILD SUCCESSFUL`. (First run downloads JMH artifacts — that's expected.)
- [ ] **Step 4: Sanity-check compile-only path.** Run `./gradlew compileJmhKotlin` — Expected: `BUILD SUCCESSFUL` (or `UP-TO-DATE`), proving the Kotlin `jmh` compilation is wired so WT-1..4 files will compile.
- [ ] **Step 5: Format + commit.**
  ```bash
  ./gradlew spotlessApply
  git add build.gradle.kts src/jmh/kotlin/com/salesforce/revoman/benchmark/SmokeBenchmark.kt
  git commit -m "feat: add JMH source set + SmokeBenchmark, wire -Pjmh.includes selection"
  ```

---

### Task 5: Document the baseline-capture procedure and record the smoke baseline
**Files:**
- Create: `docs/superpowers/benchmarks/baseline.md`
- Create: `docs/superpowers/benchmarks/results/` (holds captured result snapshots; add a `.gitkeep`)
**Interfaces:**
- Produces: the baseline protocol every domain worktree follows — run its benchmark on the WT-0 base commit BEFORE its fix lands, snapshot `build/results/jmh/results.txt` into `docs/superpowers/benchmarks/results/<sha>-<domain>.txt`, then re-run after the fix and record the delta.

- [ ] **Step 1: Create the results snapshot dir.** Create an empty file `docs/superpowers/benchmarks/results/.gitkeep` so the directory is tracked.
- [ ] **Step 2: Create the baseline doc.** Create `docs/superpowers/benchmarks/baseline.md` with exactly this content:
  ````markdown
  # ReVoman Perf Benchmarks — Baseline Capture

  Component-level JMH benchmarks live in `src/jmh/kotlin/com/salesforce/revoman/benchmark/`.
  Full end-to-end collection runs need live network/orgs and are NOT JMH-repeatable, so we
  benchmark the isolated hot paths the perf audit flagged (regex/vars, marshalling, GraalJS
  eval, env accumulation).

  ## Run all benchmarks

  ```bash
  ./gradlew jmh
  ```

  Results are written to `build/results/jmh/results.txt`.

  ## Run one benchmark class

  ```bash
  ./gradlew jmh -Pjmh.includes=SmokeBenchmark      # regex substring against the class name
  ```

  ## Baseline protocol (per domain worktree WT-1..WT-4)

  1. **Before** applying the domain fix, from the worktree's WT-0 base commit, run the domain's
     benchmark and snapshot the numbers:
     ```bash
     ./gradlew jmh -Pjmh.includes=<DomainBenchmark>
     cp build/results/jmh/results.txt docs/superpowers/benchmarks/results/$(git rev-parse --short HEAD)-<domain>-before.txt
     ```
  2. **After** the fix lands (tests green), re-run the same benchmark and snapshot again with a
     `-after` suffix.
  3. Record the before/after delta in the worktree's PR description / ledger. Keep the raw
     snapshot files committed under `docs/superpowers/benchmarks/results/` so deltas are auditable.

  ## Notes

  - The optimizing GraalJS runtime (`org.graalvm.truffle:truffle-runtime`, added in WT-0) is on the
    classpath, so GraalJS benchmarks measure jargraal-JIT behavior, not interpreter-only.
  - JMH warmup/fork settings live on each `@Benchmark` class; the smoke benchmark uses minimal
    iterations for speed — domain benchmarks should use JMH defaults or higher for stable numbers.
  ````
- [ ] **Step 3: Capture the smoke baseline.** Run `./gradlew jmh -Pjmh.includes=SmokeBenchmark` then copy the result: `cp build/results/jmh/results.txt "docs/superpowers/benchmarks/results/$(git rev-parse --short HEAD)-smoke.txt"` — Expected: `BUILD SUCCESSFUL` and a non-empty snapshot file containing a `SmokeBenchmark.sumOfRange` row. This proves the capture mechanism end-to-end.
- [ ] **Step 4: Commit.**
  ```bash
  git add docs/superpowers/benchmarks/
  git commit -m "docs: add JMH baseline-capture procedure + smoke snapshot"
  ```

---

### Task 6: Full green gate (nothing regressed)
**Files:** none (verification only)
**Interfaces:**
- Produces: the guarantee that WT-1..WT-4 branch off a green WT-0 — deps resolve, formatting is clean, and unit + integration tests still pass with the new `truffle-runtime` runtime on the classpath.

- [ ] **Step 1: Verify formatting is clean.** Run `./gradlew spotlessCheck` — Expected: `BUILD SUCCESSFUL` (no formatting violations from the new benchmark / build-script edits).
- [ ] **Step 2: Run the unit build + tests.** Run `./gradlew build` — Expected: `BUILD SUCCESSFUL` (compiles all source sets, runs detekt + unit `test`; the new `truffle-runtime` classpath addition must not break the build or unit tests).
- [ ] **Step 3: Run the full test gate.** Run `./gradlew test integrationTest` — Expected: `BUILD SUCCESSFUL`. (Integration tests may require network access to Pokemon/restfulapi.dev/apigee; run where that access is available. WT-0 changes only build config, so behavior is unchanged — this confirms the optimizing GraalJS runtime is a transparent, faster drop-in.)
- [ ] **Step 4: Commit (only if Step 1 auto-fixed anything).** If `spotlessCheck` failed and you ran `./gradlew spotlessApply` to fix it:
  ```bash
  git add -A
  git commit -m "chore: spotless formatting for WT-0 foundation"
  ```
  Otherwise no commit is needed — WT-0 is complete and green.

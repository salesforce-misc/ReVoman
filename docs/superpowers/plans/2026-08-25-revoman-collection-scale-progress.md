# ReVoman collection-scale progress bookkeeping implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace quadratic mid-run `StepReport` prefix rebuilding with structurally shared
progress lists, retain a reusable 1/10/100/500-step exploratory benchmark, and publish reproducible
accepted evidence for the confirmed 100 and 500-step workloads.

**Architecture:** Keep the final report accumulator unchanged and maintain a parallel internal
`PersistentList<StepReport>` for progress snapshots. A small internal list operation owns
replace-or-append semantics, while `executeStepsSerially` supplies persistent prefixes. The
benchmark and final selector land before baseline measurement; production changes land in the
next commit; validated evidence lands last.

**Tech stack:** JDK 25, Gradle 9.7.1, Kotlin, kotlinx-benchmark/JMH, JFR, kotlinx immutable
collections, Kotest, http4k, Kotlin DataFrame, Spotless, Detekt, Qodana, IntelliJ IDEA.

**Spec:** `docs/superpowers/specs/2026-08-25-revoman-collection-scale-progress-design.md`

## Global constraints

- Work in the existing externally managed worktree at detached HEAD. Task 3 may use one disposable
  temporary worktree solely to reconstruct the matched revisions; remove it immediately afterward.
  Do not rewrite unrelated user changes.
- Do not push, publish, release, merge, start an HTTP server, add a notebook, or automate broad
  process killing.
- Add no public API solely for benchmarking. Preserve Maven coordinates, public APIs, artifacts,
  runtime defaults, error contracts, script isolation, TLS and proxy behavior, Apache fallback,
  and custom-adapter semantics.
- Address only collection-progress list rebuilding in production. Do not optimize environment
  replacement, Moshi construction, V3 loading, or sandbox bootcode in this plan.
- Introduce no cache. The approved `PersistentList` is per-`revUp` state and becomes unreachable
  with that run.
- Generate benchmark fixtures and validate invariants outside measured methods. Construct normal
  per-run state inside measured methods. Use deterministic `Kick.httpClient` HTTP 200 responses.
- Use final benchmark settings of five forks, ten warmups per fork, twenty measurements per fork,
  one-second iterations, average-time mode, milliseconds, and CSV.
- Run final measurements sequentially with identical JDK, Gradle, JVM flags, affinity, governor,
  configuration, and dependency graph. Use `--no-daemon --max-workers=1`.
- Close IntelliJ and stop Gradle and Kotlin daemons before each final run. Inspect user processes
  and terminate only exact interfering PIDs. Record a zero when none are terminated.
- If either accepted baseline and candidate confidence interval overlaps, discard both complete
  outputs and rerun the entire pair unchanged. Never combine forks or rows across attempts.
- Keep raw measurements in this plan's ignored SDD workspace until the reporter validates the
  pair. Publish the complete run directory atomically under
  `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/`.
- A row passes only when `candidate score + candidate error < baseline score - baseline error`.
- Every production change follows red, green, refactor. Record the failing and passing commands in
  the implementer report.
- Each implementation task ends in one reviewed commit. Amend review fixes into that task's
  commit. Rerun measurements if a reviewed revision changes after it was measured.

---

### Task 1: Freeze the reusable workload and baseline revision

**Files:**

- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/RevUpWorkload.kt`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/CollectionScaleRevUpBenchmark.kt`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/EnvironmentScaleRevUpBenchmark.kt`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/PlaceholderDensityRevUpBenchmark.kt`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/ScriptModeRevUpBenchmark.kt`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/RepeatedPathRevUpBenchmark.kt`
- Modify: `build-logic/src/main/kotlin/revoman.benchmarks.gradle.kts`
- Create: `docs/superpowers/specs/2026-08-25-revoman-collection-scale-progress-design.md`
- Create: `docs/superpowers/plans/2026-08-25-revoman-collection-scale-progress.md`

**Interfaces:**

- Consumes: `ReVoman.revUp(Kick)`, `Kick.httpClient(HttpHandler)`, and the existing benchmark
  `commonProfile` configuration helper.
- Produces: `CollectionScaleRevUpBenchmark.revUpByStepCount`, the
  `mainCollectionScaleFinalBenchmark` task, and an exact reviewed baseline commit.

- [ ] **Step 1: Review and finish the shared benchmark workload**

Keep `PreparedCollection` immutable and generated in setup. Its helpers must have these effective
signatures:

```kotlin
internal fun prepareCollection(
  stepCount: Int,
  placeholdersPerRequest: Int = 0,
  includeScript: Boolean = false,
): PreparedCollection

internal fun prepareEnvironment(size: Int): Map<String, Any?>

internal fun revUp(
  collection: PreparedCollection,
  environment: Map<String, Any?> = emptyMap(),
): Rundown

internal fun revUpPath(
  templatePath: String,
  environment: Map<String, Any?>,
): Rundown
```

Every helper invocation must configure a fresh `Kick`, a fresh stream for in-memory collections,
and this deterministic handler:

```kotlin
.httpClient { Response(Status.OK).body("{\"ok\":true}") }
```

`@Setup` must validate the fixture and perform one unmeasured `revUp` that checks the expected
report count and `areAllStepsSuccessful`. The benchmark method may only invoke the workload and
consume its returned `Rundown`.

- [ ] **Step 2: Keep the complete exploratory matrix**

Use these exact parameters:

```kotlin
@Param("1", "10", "100", "500") var stepCount: String = "1"
@Param("0", "10", "100", "1000") var environmentSize: String = "0"
@Param("1", "10") var placeholdersPerRequest: String = "1"
@Param("script-free", "script-bearing") var scriptMode: String = "script-free"
@Param("1", "10") var repetitions: String = "1"
```

Collection scale uses zero environment entries, zero placeholders, and no scripts. Environment
scale uses ten steps and zero placeholders. Placeholder density uses ten steps and 100 environment
entries. Script mode uses one and ten steps. Repeated path execution uses the benchmark-owned V3
`pm-templates/v3/single-ok` fixture.

- [ ] **Step 3: Add a final profile that selects only collection scale**

Extend `revoman.benchmarks.gradle.kts` without changing `smoke` or `final`:

```kotlin
register("collectionScaleFinal") {
  commonProfile(iterationCount = 20, warmupCount = 10, iterationMillis = 1000, forkCount = 5)
  include(
    "com.salesforce.revoman.benchmark.CollectionScaleRevUpBenchmark.revUpByStepCount"
  )
}
```

At the reviewed Task 1 revision, the generated task was
`:benchmarks:mainCollectionScaleFinalBenchmark`, and its CSV contained exactly four primary rows
with step counts 1, 10, 100, and 500. This is historical exploratory output. Task 3 later narrows
only this final profile to 100 and 500 while retaining all four values on the reusable `@Param`.

- [ ] **Step 4: Compile, format, and smoke-test the matrix**

Run:

```bash
./gradlew :benchmarks:compileKotlin :benchmarks:tasks --all \
  :benchmarks:mainSmokeBenchmark spotlessCheck --no-daemon --max-workers=1
```

Expected: exit 0; the task listing contains `mainCollectionScaleFinalBenchmark`; setup validation
passes for all 16 new rows; the existing `ScriptFreeRevUpBenchmark` also runs; no external server
process starts.

- [ ] **Step 5: Commit the reviewed baseline source**

Inspect `git diff --check` and `git status --short`. Stage only the files named in this task, then
commit:

```bash
git commit -m "perf: add collection-scale benchmark study"
```

Record the full SHA as `BASELINE_REVISION` in the task report. Do not measure the final baseline
until this task's review loop is clean, because any amendment changes the required revision.

---

### Task 2: Measure the baseline and replace progress prefixes with persistent lists

**Files:**

- Modify: `revoman/src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKSyncProgressTest.kt`
- Modify: `revoman/src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt`
- Modify: `revoman/src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Create in ignored SDD workspace: `measurements/accepted-attempt/baseline/`

**Interfaces:**

- Consumes: the reviewed Task 1 commit and
  `CollectionScaleRevUpBenchmark.revUpByStepCount`.
- Produces: persistent `PostmanSDK.syncProgress` snapshots, a reviewed candidate commit, and its
  matching raw baseline CSV plus metadata in the ignored workspace.

- [ ] **Step 1: Prove the baseline revision and dependency graph**

Require a clean tracked worktree and record:

```bash
git rev-parse HEAD
git status --short
./gradlew :benchmarks:dependencies --configuration runtimeClasspath \
  --no-daemon --max-workers=1
```

Save the dependency report and SHA-256 in the ignored SDD workspace. The revision must equal the
reviewed Task 1 `BASELINE_REVISION`.

- [ ] **Step 2: Prepare the machine without broad process killing**

Run `idea-session status`; IntelliJ must be closed before measurement. Run `./gradlew --stop` and
inspect the current user's processes with:

```bash
ps -u "$(id -u)" -o pid=,comm=,args=
```

Identify IntelliJ, Gradle, Kotlin, profiling, compilation, backup, indexing, or unrelated benchmark
processes. Terminate only exact interfering PIDs owned by this task. Record the inspected process
classes, exact terminated PIDs, affinity, CPU topology, governors, load average, memory, kernel,
JDK, Gradle version, and WSL status. Do not record hostname, username, environment variables, or
credentials.

- [ ] **Step 3: Measure the final baseline**

Capture `measurementStartedAtUtc` immediately before and `measurementCompletedAtUtc` immediately
after this exact command:

```bash
taskset --cpu-list 0,1,2,3,4,5,6,7 \
  ./gradlew :benchmarks:mainCollectionScaleFinalBenchmark \
  --no-daemon --max-workers=1
```

Copy the generated CSV to
`measurements/accepted-attempt/baseline/raw.csv`. Verify it contains one row for each exact step
count and no other benchmark. Record all sanitized environment metadata beside it. This is the
pre-change baseline and must never be regenerated from the candidate source tree.

- [ ] **Step 4: Write the failing progress-update test**

Before changing production code, add this behavior to `PostmanSDKSyncProgressTest` using the
file's existing `step` and `report` helpers:

```kotlin
@Test
fun `syncProgress retains persistent progress and preserves the prior snapshot`() {
  val stepA = step("a")
  val stepB = step("b")
  val reportA = report(stepA)
  val preReportB = report(stepB)
  val original = persistentListOf(reportA, preReportB)
  val evolved = preReportB.copy(nextRequest = "afterHttp")
  val pm = pmWithSeededRundown(original)
  val priorRundown = pm.rundown

  pm.syncProgress(evolved)

  priorRundown.stepReports shouldBe persistentListOf(reportA, preReportB)
  pm.rundown.stepReports shouldBe persistentListOf(reportA, evolved)
  pm.rundown.stepReports.shouldBeInstanceOf<PersistentList<*>>()
}
```

The production changes this catches are replacing a persistent progress list with an `ArrayList`,
mutating a retained snapshot, or duplicating the current entry.

- [ ] **Step 5: Run the test and observe red**

Run:

```bash
./gradlew :revoman:test --tests \
  '*PostmanSDKSyncProgressTest.syncProgress retains persistent progress and preserves the prior snapshot' \
  --no-daemon --max-workers=1
```

Expected: the assertion fails because the current `dropLast(1) + stepReport` implementation returns
an ordinary list instead of retaining `PersistentList`. A passing test or unrelated error is not
red; correct the test and rerun until this representation regression causes the failure. Record the
failing output in the task report.

- [ ] **Step 6: Implement the persistent replace-or-append operation**

In `PostmanSDK.kt`, import `PersistentList`, `plus`, and `toPersistentList`, then add this private
operation:

```kotlin
private fun List<StepReport>.updatedProgress(stepReport: StepReport): PersistentList<StepReport> {
  val progress = toPersistentList()
  return if (progress.lastOrNull()?.step == stepReport.step) {
    progress.replacingAt(progress.lastIndex, stepReport)
  } else {
    progress + stepReport
  }
}
```

Change `syncProgress` to keep `currentStepReport = stepReport` and then use:

```kotlin
rundown = rundown.copy(stepReports = rundown.stepReports.updatedProgress(stepReport))
```

Do not change the three call sites or their order.

- [ ] **Step 7: Supply persistent prefixes from the sequencer**

In `ReVoman.kt`, import `PersistentList`, `persistentListOf`, and `plus`. In
`executeStepsSerially`, retain the final accumulator and add a parallel progress value:

```kotlin
val reports = mutableListOf<StepReport>()
var progressReports = persistentListOf<StepReport>()
```

Pass `progressReports` to `runStep`. After `runStep` returns, append to both in this order:

```kotlin
reports += report
progressReports = progressReports + report
```

Change only the internal `runStep` parameter from `List<StepReport>` to
`PersistentList<StepReport>`, then seed its `Rundown` with:

```kotlin
stepReports = stepReportsSoFar + preStepReport
```

Leave ledger early returns, report finalization, halt checks, loops, polling, and the final
`Rundown` constructor unchanged.

- [ ] **Step 8: Verify green and protect the behavior**

Run:

```bash
./gradlew :revoman:test --tests '*PostmanSDKSyncProgressTest' \
  --no-daemon --max-workers=1
./gradlew :revoman:test --tests '*ControlFlowE2ETest' \
  --no-daemon --max-workers=1
./gradlew :revoman:test --no-daemon --max-workers=1
./gradlew spotlessCheck --no-daemon --max-workers=1
```

Expected: exit 0 for every command. The focused suite proves current-report replacement, loop
history, and snapshot stability. The full unit suite catches changes to execution and output
contracts.

- [ ] **Step 9: Commit the candidate**

Inspect `git diff --check` and `git status --short`. Stage only the three files named by this task,
then commit:

```bash
git commit -m "perf: share collection progress reports persistently"
```

Record the full SHA as `CANDIDATE_REVISION`. Do not measure the final candidate until this task's
review loop is clean. If review amends the commit, the amended SHA is the only valid candidate.

---

### Task 3: Lock the approved acceptance scope into matched revisions

**Files:**

- Modify: `build-logic/src/main/kotlin/revoman.benchmarks.gradle.kts`
- Modify: `docs/superpowers/specs/2026-08-25-revoman-collection-scale-progress-design.md`
- Modify: `docs/superpowers/plans/2026-08-25-revoman-collection-scale-progress.md`

**Interfaces:**

- Consumes: reviewed baseline `8f32ef6314d4f388dca7179ff701640523905d4a`, reviewed candidate
  `b81506134c02a3fa000919cbe58d97fb2f6c7fff`, and the user's approval to narrow accepted evidence.
- Produces: a clean linear `BASELINE_REVISION`/`CANDIDATE_REVISION` pair with identical two-row
  benchmark profiles and no production difference except Task 2's reviewed optimization.

- [ ] **Step 1: Record the rejected full-matrix evidence**

Preserve both complete four-row pairs under the ignored SDD measurement workspace. Record that 1
and 10 steps had overlapping intervals in both attempts, while 100 and 500 steps passed the strict
rule. Neither attempt may enter `benchmark-results/` or be reused as final timing evidence.

- [ ] **Step 2: Restrict only the final acceptance profile**

Keep `@Param("1", "10", "100", "500")` on the reusable exploratory benchmark. Add this selector to
`collectionScaleFinal` after `include(...)`:

```kotlin
param("stepCount", "100", "500")
```

Do not change the smoke profile, the general final profile, measured operations, fixtures, or the
generic reporter. Verify the generated final task emits exactly two raw rows with step counts 100
and 500.

- [ ] **Step 3: Reconstruct the exact matched revisions**

Use an isolated temporary worktree or another non-destructive Git workflow. Build a new baseline
commit from `760ad02664fd33de866fe814f12730d1734c6b49` that contains Task 1's benchmark matrix, the
approved two-row final profile, and the amended design/plan, but none of Task 2's production or
regression-test changes. Replay Task 2's reviewed change as the direct candidate child. Preserve
the original commit subjects. Do not use `git reset --hard` or `git checkout --`.

Verify:

```bash
git diff --check BASELINE_REVISION CANDIDATE_REVISION
git diff --name-status BASELINE_REVISION CANDIDATE_REVISION
git diff BASELINE_REVISION CANDIDATE_REVISION -- \
  revoman/src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
  revoman/src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt \
  revoman/src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKSyncProgressTest.kt
```

Expected: the candidate is the baseline's direct child; both contain the exact same benchmark
configuration and docs; the candidate delta is only the reviewed two production files and focused
regression test.

- [ ] **Step 4: Smoke and independently review both revisions**

On the baseline, run the two-row final profile with smoke-scale overrides if the Gradle plugin
supports them; otherwise run the normal smoke matrix and inspect the final task configuration. On
the candidate, run the focused regression suite and `spotlessCheck`. Review the reconstructed pair
before recording the new full SHAs. Supersede the old reviewed SHAs for final evidence only; keep
their rejected measurement artifacts intact.

---

### Task 4: Produce accepted evidence and complete repository verification

**Files:**

- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/manifest.json`
- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/environment/baseline.json`
- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/environment/candidate.json`
- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/raw/baseline.csv`
- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/raw/candidate.csv`
- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/comparison.csv`
- Create: `benchmark-results/collection-scale-progress-bookkeeping/<run-id>/report.md`

**Interfaces:**

- Consumes: Task 3's reviewed matched `BASELINE_REVISION` and `CANDIDATE_REVISION`,
  `mainCollectionScaleFinalBenchmark`, and `BenchmarkReportCli`.
- Produces: one atomically published accepted evidence directory, a concise measured-gain statement,
  and a verified evidence commit.

- [ ] **Step 1: Start from the matched baseline and capture its dependency graph**

Create a new ignored accepted-attempt directory owned by Task 4. Require a clean tracked worktree,
switch with `git switch --detach BASELINE_REVISION` if necessary, and record `git rev-parse HEAD`.
It must equal Task 3's reviewed matched `BASELINE_REVISION`. Capture the complete baseline runtime
classpath with:

```bash
./gradlew :benchmarks:dependencies --configuration runtimeClasspath \
  --no-daemon --max-workers=1
```

Save the complete report, its normalized form, and the normalized SHA-256 under this new attempt's
baseline directory. Do not copy, filter, or otherwise reuse Task 2's earlier baseline capture or
either rejected four-row attempt. Accepted evidence must come from fresh unfiltered two-row output
produced by the matched Task 3 revisions.

- [ ] **Step 2: Prepare and measure the matched baseline**

Run the exact machine-hygiene sequence before measurement. Require `idea-session status` to show
IntelliJ closed, run `./gradlew --stop`, and inspect the current user's complete process list with
`ps -u "$(id -u)" -o pid=,comm=,args=`. Check IntelliJ, Gradle, Kotlin, JMH/benchmark, profiling/JFR,
compilation, backup, and indexing processes. Terminate only exact interfering PIDs owned by this
task and record each PID, or record zero. Capture affinity, CPU model and topology, sibling groups,
governors, load average, memory, kernel, both launcher and toolchain JDKs, Gradle, and WSL status.

Capture `measurementStartedAtUtc` immediately before and `measurementCompletedAtUtc` immediately
after this full unprofiled command, with no smoke overrides:

```bash
taskset --cpu-list 0,1,2,3,4,5,6,7 \
  ./gradlew :benchmarks:mainCollectionScaleFinalBenchmark \
  --no-daemon --max-workers=1
```

While still on `BASELINE_REVISION`, copy the generated CSV and sanitized metadata into the new
attempt's baseline directory. Verify the raw CSV has exactly the unfiltered 100 and 500 rows and no
other benchmark. Do not run JFR, async-profiler, or a GC profiler during this measurement.

- [ ] **Step 3: Switch cleanly to the candidate and compare dependency graphs**

After the baseline raw CSV and metadata are complete, require a clean tracked worktree and switch
once with `git switch --detach CANDIDATE_REVISION`. Record `git rev-parse HEAD` and require the exact
Task 3 candidate SHA. Capture the candidate runtime classpath with the same Gradle command, normalize
it identically, and save its report and SHA-256 under the candidate directory. Compare the normalized
reports and SHA-256 values with the fresh matched baseline. Stop before measurement if they differ.

- [ ] **Step 4: Prepare and measure the matched candidate**

Repeat Step 2's exact IntelliJ, daemon, process, affinity, topology, governor, load, memory, kernel,
JDK, Gradle, WSL, and exact-PID hygiene. Use the same CPU list and capture lifecycle timestamps
immediately around the same full unprofiled command:

```bash
taskset --cpu-list 0,1,2,3,4,5,6,7 \
  ./gradlew :benchmarks:mainCollectionScaleFinalBenchmark \
  --no-daemon --max-workers=1
```

Copy its unfiltered two-row CSV and sanitized metadata into the candidate directory. Verify exact
100 and 500 rows and no other benchmark. The baseline and candidate measurements are one sequential
pair with identical revisions, dependency graph, command, profile, JDKs, flags, affinity, governor,
and hygiene. If the sequence is interrupted or any condition changes, reject both outputs and
restart from the baseline. Never combine rows or forks across attempts.

- [ ] **Step 5: Build the complete staged evidence package**

Choose `RUN_ID` as the UTC completion timestamp in compact form `yyyyMMdd'T'HHmmss'Z'`. In a new
sibling staging directory, create the exact tree from this task's Files section. The manifest must
record:

- schema version 1, study ID, run ID, both full revisions, and lifecycle timestamps;
- selector
  `^com\\.salesforce\\.revoman\\.benchmark\\.CollectionScaleRevUpBenchmark\\.revUpByStepCount$`;
- two expected keys with normalized parameters `9:stepCount=3:100` and `9:stepCount=3:500`;
- the exact identical commands, CPU list, JDK, Gradle, Kotlin, kotlinx-benchmark, JMH, DataFrame,
  kernel, profile, confidence level, raw paths, and environment paths.

Environment JSON must contain the captured lifecycle timestamps, revision, kernel, WSL status,
CPU model and topology, sibling groups, affinity, governors, memory, JDKs, Gradle settings, load,
benchmark selector and profile, command, and hygiene counts. It must not contain hostname,
username, environment variables, credentials, or absolute home-directory paths.

- [ ] **Step 6: Validate with the existing DataFrame reporter**

Run:

```bash
./gradlew :benchmark-reporting:run \
  --args="compare --manifest /absolute/staging/path/manifest.json" \
  --no-daemon --max-workers=1
```

Exit 0 means every candidate upper bound is below its baseline lower bound and the reporter created
both `comparison.csv` and `report.md`. Verify two comparison rows, both with `passed=true`.

If any intervals overlap, delete neither tracked file nor prior accepted evidence. Mark the current
ignored attempt rejected, switch the clean worktree to `BASELINE_REVISION`, repeat the complete
baseline hygiene and measurement, switch back to `CANDIDATE_REVISION`, repeat the complete
candidate hygiene and measurement, and rerun the reporter. Use `git switch --detach <full-sha>` and
never `git reset` or `git checkout --`. Do not mix data across attempts.

- [ ] **Step 7: Publish the accepted directory atomically**

After reporter validation, verify all seven required files exist and contain no sensitive fields.
Move the complete staged run directory into the new study directory with one same-filesystem rename.
Do not expose a partial directory under `benchmark-results/`. Inspect `git status --short` and stage
only the accepted run directory.

- [ ] **Step 8: Run complete verification from the candidate revision**

Run fresh commands and retain their exit codes and summaries:

```bash
./gradlew :revoman:test :revoman:integrationTest :benchmark-reporting:test \
  --no-daemon --max-workers=1
./gradlew spotlessCheck detekt --no-daemon --max-workers=1
./gradlew qodanaScan --no-daemon --max-workers=1
./gradlew build --configuration-cache --no-daemon --max-workers=1
./gradlew build --configuration-cache --no-daemon --max-workers=1
```

Expected: all tests and checks pass; Qodana reports zero problems; the second build reuses the
configuration cache; neither build reports an Isolated Projects or Configuration Cache problem.
If Qodana refers to a missing host Gradle path, inspect ignored
`.qodana/cache/.idea/gradle.xml`, temporarily point that cache copy at the wrapper, rerun Qodana,
then restore the ignored cache file. Do not change tracked IDE configuration to hide the problem.

- [ ] **Step 9: Resync IntelliJ after measurement**

Use `idea-session` to open this exact worktree and trigger a Gradle reload. Wait for sync to finish,
then inspect the session status and build output. Record whether the IDE completed the sync without
Gradle model errors. IDE Index MCP is unavailable in this Codex session, so do not claim an indexed
navigation check that cannot be run.

- [ ] **Step 10: Commit accepted evidence**

Inspect `git diff --check`, confirm the working tree contains only the accepted evidence directory,
and commit:

```bash
git commit -m "perf: record collection-scale progress evidence"
```

The task report must quote the DataFrame report's two baseline scores, candidate scores, confidence
bounds, delta percentages, and the workload-specific gain. It must also list every verification
command and exit code.

---

### Task 5: Correct verification findings and rebuild the matched pair

**Files:**

- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/PreparedCollection.kt`
- Modify: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/RevUpWorkload.kt`
- Create: `benchmarks/src/test/kotlin/com/salesforce/revoman/benchmark/RevUpWorkloadTest.kt`
- Modify: `revoman/src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Modify: `docs/superpowers/specs/2026-08-25-revoman-collection-scale-progress-design.md`
- Modify: `docs/superpowers/plans/2026-08-25-revoman-collection-scale-progress.md`

**Interfaces:**

- Consumes: Task 4's reproducible Detekt/Qodana findings and the reviewed Task 3 matched pair.
- Produces: a new direct-parent `BASELINE_REVISION`/`CANDIDATE_REVISION` pair that passes all static
  gates before measurement while preserving workload bytes and progress behavior.

- [ ] **Step 1: Preserve the failing checks as the red evidence**

Record Task 4's exact Detekt and Qodana commands and findings. Do not add suppressions, change the
Detekt baseline, change Qodana configuration, or weaken any gate. The rejected strict-PASS timing
package remains intact under the ignored SDD workspace.

- [ ] **Step 2: Correct the benchmark helper at its source**

Move `PreparedCollection` to its matching file and change `internal data class` to `internal class`.
No call site uses value equality, destructuring, or `copy`. In `RevUpWorkload.kt`, define one named
padding-width constant and use it for both generated step names and environment keys. Replace the
placeholder range with a count-sized list construction whose `joinToString(separator = "/",
prefix = "/")` produces the exact same strings for counts 0, 1, and 10.

Before refactoring, add a characterization test in the benchmark module that records and asserts
representative generated fixture hashes for step counts 1/10/100/500 and placeholder counts 0/1/10,
including zero-placeholder URL shape and script-free/script-bearing output. Run it successfully on
the old helper, then rerun it unchanged after the correction. Also run the full benchmark smoke
matrix and require every setup validation to pass. Record the before/after hashes in the task report.
No measured method or HTTP behavior may change.

- [ ] **Step 3: Correct the candidate-only operator assignment**

In the candidate production change, replace:

```kotlin
progressReports = progressReports + report
```

with:

```kotlin
progressReports += report
```

Do not change `PersistentList`, the imported immutable `plus`, the accumulator lifecycle, or any
other production line. Run the focused progress test first, then the full unit and control-flow
suites. The existing persistent-type and retained-snapshot regression test is the behavioral guard.

- [ ] **Step 4: Reconstruct and verify new matched revisions**

Build a corrected baseline directly on `760ad02664fd33de866fe814f12730d1734c6b49` with all benchmark,
profile, design, and plan files but no Task 2 production/test change. Build its direct-child candidate
with the two production files and focused regression test, including the equivalent `+=` spelling.
Preserve the original commit subjects. Both revisions must have byte-identical benchmark sources,
build logic, design, and plan. Do not use `git reset --hard` or `git checkout --`.

Verify both relevant deltas, `git diff --check`, exact parentage, clean worktree, and then run fresh:

```bash
./gradlew :benchmarks:mainSmokeBenchmark --no-daemon --max-workers=1
./gradlew :revoman:test --tests '*PostmanSDKSyncProgressTest' --rerun-tasks \
  --no-daemon --max-workers=1
./gradlew :revoman:test --tests '*ControlFlowE2ETest' --rerun-tasks \
  --no-daemon --max-workers=1
./gradlew :revoman:test --rerun-tasks --no-daemon --max-workers=1
./gradlew spotlessCheck detekt --rerun-tasks --no-daemon --max-workers=1
./gradlew qodanaScan --no-daemon --max-workers=1
```

Expected: all benchmark setup validations and tests pass, Detekt is clean, and Qodana reports zero
problems. If Qodana or Detekt reports any new problem, stop before timing and return to systematic
diagnosis. Independently review the complete corrected baseline and candidate before recording the
new full SHAs.

---

### Task 6: Rerun accepted evidence from the corrected reviewed pair

Repeat every Task 4 step from a new ignored attempt using only Task 5's reviewed full SHAs. Reuse no
raw timing, metadata, dependency capture, row, fork, staged package, or environment file from Task 4
or any earlier attempt. Measure the complete baseline then candidate pair sequentially, apply the
same strict DataFrame rule, publish seven files atomically only after PASS, rerun every verification
command successfully, resync this worktree in IntelliJ, and commit only the accepted evidence as:

```bash
git commit -m "perf: record collection-scale progress evidence"
```

The Task 6 report must state the corrected full revisions, exact scores/errors/bounds/deltas, all
verification commands and exit codes, configuration-cache reuse, Isolated Projects status, Qodana
problem count, IntelliJ sync result, and `mcp-unavailable` IDE Index status.

---

## Plan self-review

- Spec coverage: Tasks 1 through 6 cover the benchmark matrix, test-first implementation, rejected
  verification evidence, root-cause corrections, fresh matched measurements, strict comparison,
  atomic evidence, full checks, IntelliJ resync, and independent SDD reviews.
- Type consistency: Task 1 produces `mainCollectionScaleFinalBenchmark`; later tasks consume the
  same task. Task 2 produces `updatedProgress`; Tasks 3 and 5 reconstruct reviewed matched
  revisions; Task 6 consumes only Task 5's exact full SHAs.
- Scope: Production edits are limited to `ReVoman.kt` and `PostmanSDK.kt`. The test changes only
  progress behavior. Reporting stays generic.
- Evidence separation: Diagnostic JFR/GC data, rejected four-row data, and Task 4's strict-PASS but
  verification-rejected pair remain ignored and cannot be reused. Only fresh, clean, unprofiled
  two-row CSVs from Task 5's reviewed revisions can enter the accepted run directory.

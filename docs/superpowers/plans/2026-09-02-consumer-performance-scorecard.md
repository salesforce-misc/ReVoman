# Consumer Performance Scorecard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible, JDK 25-only baseline scorecard for seven public ReVoman consumer
journeys and publish the accepted metrics in the Antora documentation.

**Architecture:** Keep all measured fixtures and JMH methods in `:benchmarks`. Extend
`:benchmark-reporting` with an absolute-scorecard mode and a compiled Kotlin orchestration entry
point that runs the prepared JMH fat JAR directly, validates its output, and atomically installs a
complete evidence directory. Gradle provides the JDK 25 launcher and executable benchmark artifact;
the production `:revoman` module and the existing strict comparison rule remain unchanged.

**Tech Stack:** Kotlin 2.4.20-RC2, Gradle 9.7.1, JDK 25 toolchains and daemon JVM criteria,
`kotlinx-benchmark`/JMH, Kotest, Kotlin DataFrame, `kotlinx.serialization.json`, Gradle TestKit,
Antora, JetBrains IDE Index and JVM Debugger MCP, and the JDK-bundled async-profiler.

**Approved design:**
[`docs/superpowers/specs/2026-09-02-consumer-performance-scorecard-design.md`](../specs/2026-09-02-consumer-performance-scorecard-design.md)

**Delivery rule:** Do not edit production files under `revoman/src/main`. Do not optimize any
runtime path in this study. Preserve user-owned `.idea` changes, previous benchmark evidence, and
the existing comparison CLI behavior. Do not add shell orchestration or a standalone `.kts` file;
the scorecard workflow belongs in the compiled Kotlin runner.

---

## Execution prerequisite

Resolve an installed JDK 25 once per shell without committing its machine-specific path:

```bash
export REVOMAN_JAVA25_HOME="$(
  ./gradlew -q javaToolchains \
    | awk '/Language Version: 25/{seen=1} seen && /Location:/{sub(/^.*Location: /, ""); print; exit}'
)"
test -x "$REVOMAN_JAVA25_HOME/bin/java"
"$REVOMAN_JAVA25_HOME/bin/java" -version
```

Expected: Java feature version 25. Use `JAVA_HOME="$REVOMAN_JAVA25_HOME"` and prepend its `bin`
directory for every command that executes compiled benchmark code. Runtime investigation additionally
requires `idea-cli`, `ide-index-mcp`, `jetbrains-debugger`, and
`superpowers:systematic-debugging`; Task 5 must not start without their required bindings.

---

## Task 1: Build and pin the seven consumer journeys

**Files:**

- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/ConsumerJourneyFixtures.kt`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/ConsumerJourneyBenchmark.kt`
- Create: `benchmarks/src/test/kotlin/com/salesforce/revoman/benchmark/ConsumerJourneyFixturesTest.kt`

- [ ] **Step 1: Add failing V2/V3 equivalence and deterministic-handler tests**

  In `ConsumerJourneyFixturesTest`, specify these contracts before adding the fixtures:

  - `postmanV2TenStep` and `v3TenStep` each expose the same ten ordered `GET` requests to
    `/step-0001` through `/step-0010` and return ten successful reports;
  - `v3HundredStep` returns 100 successful reports;
  - all three rows invoke their in-process handler exactly once per HTTP step;
  - no fixture URL uses localhost or a routable host.

  Give the fixture a handler ledger, not a global counter, so each execution can assert its own
  method, path, body, and call count.

  Run:

  ```bash
  ./gradlew :benchmarks:test --tests '*ConsumerJourneyFixturesTest*'
  ```

  Expected: compilation fails because `prepareConsumerJourneys` and its result type do not exist.

- [ ] **Step 2: Implement the fixture owner and equivalent V2/V3 documents**

  Add this benchmark-internal shape:

  ```kotlin
  internal class PreparedConsumerJourneys(
    val postmanV2TenStep: Kick,
    val v3TenStep: Kick,
    val v3HundredStep: Kick,
    val v3TenStepScripted: Kick,
    val threeKicks: List<Kick>,
    val runbook: Runbook,
    val handlerLedger: HandlerLedger,
    private val fixtureRoot: Path,
  ) : AutoCloseable

  internal fun prepareConsumerJourneys(): PreparedConsumerJourneys

  internal class PreparedVerboseRendering(
    val rundown: Rundown,
    private val fixtureRoot: Path,
  ) : AutoCloseable

  internal fun prepareVerboseRendering(): PreparedVerboseRendering
  ```

  Generate V2 JSON in memory. Generate V3 fixture directories beneath one temporary trial root,
  with `.resources/definition.yaml` and stable `*.request.yaml` files ordered in increments of
  1,000. Reuse a single deterministic `HttpHandler` that selects JSON bodies by request path and
  records calls in `HandlerLedger`. Use `http://benchmark.invalid`; never open a socket. Reuse the
  existing `Rundown.validate` helper without changing `RevUpWorkload.kt`, its fixture bytes, or its
  hash test.

  Run the test again. Expected: the equivalence and step-count cases pass.

- [ ] **Step 3: Add failing script, handoff, and runbook contract tests**

  Add tests proving:

  - the script-bearing V3 fixture executes ten HTTP steps and a representative `beforeRequest` or
    `afterResponse` script writes the expected environment value;
  - `ReVoman.revUp(threeKicks)` executes 30 requests and carries an `Int`, a `Boolean`, and a
    `List<String>` through real `pm.environment.set`/lookup handoffs;
  - the equivalent three-step `Runbook` executes one ten-step kick per step, applies
    `consumes`/`produces`, marks the middle step `underTest`, runs `assertAfter`, and returns three
    successful child rundowns;
  - no expected environment value is injected directly into a downstream kick.

  Run the focused test. Expected: failures identify the missing scripts and workflow definitions.

- [ ] **Step 4: Implement the script-bearing, multi-kick, and runbook fixtures**

  Build three V3 ten-request directories. The first response/script produces the mixed-type values,
  the second consumes them and produces the next lookup key, and the third consumes that key. Use
  the same three kicks in both the list and `Runbook` journeys so the scorecard compares orchestration
  forms rather than different HTTP work. Keep all setup immutable after `prepareConsumerJourneys`
  returns.

  Run:

  ```bash
  ./gradlew :benchmarks:test --tests '*ConsumerJourneyFixturesTest*'
  ```

  Expected: all handler, script, environment, and contract assertions pass.

- [ ] **Step 5: Add the failing verbose-rendering contract**

  Assert that `prepareConsumerJourneys()` does not execute any request. Separately assert that
  `prepareVerboseRendering()` executes a 100-step V3 kick exactly once, stores that successful
  `Rundown`, and that `toJson(Verbosity.VERBOSE)` parses as JSON with exactly 100 `stepReports`, a
  successful summary, request/response detail, environment data, and per-step snapshots.

  Run the focused test. Expected: it fails until the isolated rendering preparation exists.

- [ ] **Step 6: Complete trial preparation and resource cleanup**

  Keep execution out of `prepareConsumerJourneys()`. Prepare and validate the rendering rundown only
  in `prepareVerboseRendering()`, then reset its handler ledger after setup validation. Recursively
  remove only each object's owned temporary fixture root from `close()`. Verify a failed preparation
  also cleans up its temporary directory.

  Run the focused test. Expected: all seven consumer contracts and cleanup pass.

- [ ] **Step 7: Add one JMH method per documentation row**

  Implement `ConsumerJourneyBenchmark` plus separate `@State(Scope.Benchmark)` classes
  `ConsumerRevUpState` and `VerboseRenderingState`, each with trial-scoped setup/teardown. The first
  prepares only immutable kicks; the second calls `prepareVerboseRendering()` and owns the one
  setup-time rundown. Add these exact method names and boundaries:

  ```text
  postmanV2TenStepRevUp             ReVoman.revUp(postmanV2TenStep)
  v3TenStepRevUp                    ReVoman.revUp(v3TenStep)
  v3HundredStepRevUp                ReVoman.revUp(v3HundredStep)
  v3TenStepScriptedRevUp            ReVoman.revUp(v3TenStepScripted)
  threeKickEnvironmentHandoff       ReVoman.revUp(threeKicks)
  threeStepRunbookWithContracts     ReVoman.revUp(runbook)
  verboseHundredStepRundownJson     renderingState.rundown.toJson(Verbosity.VERBOSE)
  ```

  Each method consumes its return value through `Blackhole`. Both state setups must require
  `Runtime.version().feature() == 25` and
  `System.getProperty("revoman.scorecard.expectedJavaFeature") == "25"`, then perform only invariant
  checks. `ConsumerRevUpState` must not load a collection, create a `PostmanSDK`, execute a request,
  or retain a prior result. Only `VerboseRenderingState` executes and retains the serialization
  fixture.

  Run:

  ```bash
  ./gradlew :benchmarks:mainBenchmarkJar :benchmarks:test
  "$REVOMAN_JAVA25_HOME/bin/java" -jar \
    benchmarks/build/benchmarks/main/jars/benchmarks-main-jmh-JMH.jar -l \
    | rg 'ConsumerJourneyBenchmark'
  ```

  Expected: the fat JAR builds and lists exactly the seven method names.

- [ ] **Step 8: Format and commit the benchmark slice**

  ```bash
  ./gradlew :benchmarks:spotlessApply :benchmarks:test :benchmarks:mainBenchmarkJar
  git diff --check
  git add benchmarks/src/main benchmarks/src/test
  git commit -m "benchmarks: add consumer journey scorecard"
  ```

  Do not stage `.idea/*`.

---

## Task 2: Add absolute scorecard validation without changing comparison semantics

**Files:**

- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/JmhCsv.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ComparisonReport.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ScorecardReport.kt`
- Create: `benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/ScorecardReportCliTest.kt`
- Create: `benchmark-reporting/src/test/resources/jmh/consumer-scorecard.csv`
- Modify: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/BenchmarkReportCli.kt`
- Modify: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/BenchmarkSchemas.kt`
- Modify: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/CsvSupport.kt`
- Modify: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ReportPublisher.kt`
- Modify: `benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/BenchmarkReportCliTest.kt`

- [ ] **Step 1: Characterize the existing comparison command before refactoring**

  Add parameterized assertions for exit codes `0`, `1`, and `2`, the exact strict predicate
  `candidateScore + candidateError < baselineScore - baselineError`, the existing CLI spelling,
  duplicate handling, and two-file rollback. Include equality at the interval boundary as a failure.

  Run:

  ```bash
  ./gradlew :benchmark-reporting:test --tests '*BenchmarkReportCliTest*'
  ```

  Expected: all characterization tests pass against the current implementation.

- [ ] **Step 2: Extract shared JMH CSV parsing with no behavior change**

  Move `JmhRow`, its normalized key, numeric validation, parameter normalization, and `readJmh`
  into `JmhCsv.kt`. Move `ComparisonRow`, `compareFrames`, and comparison-frame construction into
  `ComparisonReport.kt`. Keep the same DataFrame schemas and output rendering.

  Run the characterization test. Expected: it remains green with byte-equivalent comparison files.

- [ ] **Step 3: Write failing tests for a valid seven-row absolute scorecard**

  Add a manifest and CSV fixture containing these exact fully qualified benchmarks in this order:

  ```text
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.postmanV2TenStepRevUp
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepRevUp
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3HundredStepRevUp
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepScriptedRevUp
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeKickEnvironmentHandoff
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeStepRunbookWithContracts
  com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.verboseHundredStepRundownJson
  ```

  Call `BenchmarkReportCli.run(arrayOf("scorecard", "--manifest", manifest))`. Expect exit `0` and
  exactly `scorecard.csv`, `report.md`, and `performance-scorecard.adoc`, with the fixed
  consumer-facing order and the columns journey, workload, score, 99.9-percent error, and unit.

  Run the new test. Expected: it fails with the current compare-only usage error.

- [ ] **Step 4: Implement scorecard schema, metadata, and renderers**

  Add:

  ```kotlin
  internal data class ExpectedScorecardRow(
    val benchmark: String,
    val journey: String,
    val workload: String,
  )

  internal data class ScorecardProfile(
    val mode: String,
    val unit: String,
    val threads: Int,
    val forks: Int,
    val warmups: Int,
    val measurements: Int,
    val iterationSeconds: Int,
    val confidence: Double,
  )
  ```

  Add `ScorecardRowSchema` to `BenchmarkSchemas.kt`. In `ScorecardReport.kt`, parse the manifest,
  validate it against the fixed profile (`avgt`, `ms/op`, one thread, five forks, ten warmups,
  twenty measurements, one-second iterations, 99.9-percent confidence, 100 samples), join the CSV
  to the seven expected descriptors, and build the typed DataFrame. Extend `CsvSupport.kt` with
  scorecard CSV, Markdown, and AsciiDoc renderers. The AsciiDoc partial must include `scorecard-study-id`
  and `scorecard-run-id` attributes so documentation tests can resolve its evidence source. Format
  numeric display values with `Locale.ROOT` and retain enough precision to reconstruct the CSV
  score and error values.

  Run the valid-case test. Expected: it passes.

- [ ] **Step 5: Add every structural rejection as a focused red/green test**

  For each case, first add one failing test, then the smallest validation that makes it pass:

  - missing expected row;
  - duplicate row;
  - unexpected row;
  - selector mismatch;
  - wrong mode, threads, samples, or unit;
  - malformed, non-finite, or negative numeric data;
  - absent or renamed `Score Error (99.9%)` data;
  - wrong forks, warmups, measurements, iteration duration, or confidence;
  - missing revision, affinity, library version, dependency fingerprint, or any JDK identity;
  - any Java feature other than 25.

  Invalid scorecards return `2`, publish nothing, and never use exit `1`.

  Run:

  ```bash
  ./gradlew :benchmark-reporting:test --tests '*ScorecardReportCliTest*'
  ```

  Expected: all rejection tests pass.

- [ ] **Step 6: Generalize atomic file publication and protect comparison mode**

  Replace the comparison-specific publisher with:

  ```kotlin
  internal fun publishFiles(
    runDir: Path,
    contentsByName: Map<String, String>,
    move: (Path, Path) -> Unit = ::atomicMove,
  )
  ```

  Reject absolute or nested output names. Stage, install, and roll back all files as one set. Keep a
  thin `publishComparison` call for the existing two outputs and add `publishScorecard` for the
  three absolute outputs. Test failure after the first and second scorecard installations, existing
  output restoration, and temporary-directory cleanup.

  Run both reporting test classes. Expected: all old and new tests pass.

- [ ] **Step 7: Format and commit the reporting slice**

  ```bash
  ./gradlew :benchmark-reporting:spotlessApply :benchmark-reporting:test
  git diff --check
  git add benchmark-reporting/src
  git commit -m "benchmark-reporting: add absolute scorecards"
  ```

---

## Task 3: Implement the compiled Kotlin scorecard runner

**Files:**

- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ConsumerScorecardMain.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ConsumerScorecardRunner.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ScorecardEnvironment.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/ScorecardProcess.kt`
- Create: `benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/ConsumerScorecardRunnerTest.kt`
- Create: `benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/ScorecardEnvironmentTest.kt`
- Modify: `.gitignore`

- [ ] **Step 1: Specify the process seam and Java 25 preflight failures**

  Add these internal boundary types:

  ```kotlin
  internal data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
  )

  internal fun interface ProcessExecutor {
    fun execute(command: List<String>, workingDirectory: Path): ProcessResult
  }

  internal data class ScorecardRunRequest(
    val projectRoot: Path,
    val benchmarkJar: Path,
    val javaExecutable: Path,
    val javaFeature: Int,
    val gradleDaemonJavaFeature: Int,
    val gradleMaxWorkers: Int,
    val libraryVersion: String,
    val runtimeValidation: Path,
    val allowedDirtyPaths: Set<Path>,
  )
  ```

  Test independent failures for runner JVM, selected launcher, inherited `JAVA_HOME`/`PATH` Java,
  daemon JVM, and JMH feature values other than 25; max workers other than one; unreadable or
  non-executable Java; unreadable/non-JMH JAR; and runtime-validation metadata for a different Git
  revision. The process fake must capture argv as `List<String>` so no command passes through a
  shell.

  Run:

  ```bash
  ./gradlew :benchmark-reporting:test --tests '*ConsumerScorecardRunnerTest*'
  ```

  Expected: compilation fails because the runner types do not exist.

- [ ] **Step 2: Implement preflight as pure validation plus injected probes**

  Add `ScorecardHost` in `ScorecardEnvironment.kt` for explicit environment-variable reads,
  filesystem reads, clock access, and read-only system commands. Implement the real host using
  `ProcessBuilder`; capture stdout/stderr concurrently and preserve the child exit code. Validate:

  - `Runtime.version().feature()`, launcher metadata, daemon metadata, inherited Java, and the JMH
    child expectation all equal 25;
  - `gradleMaxWorkers == 1`;
  - `git rev-parse HEAD` matches the runtime-validation record;
  - `git status --porcelain=v1 -z` contains only normalized paths explicitly allowed by the request;
  - the benchmark selector is the exact anchored `ConsumerJourneyBenchmark` selector;
  - the runtime-validation record names all seven methods and all approved debugger assertions.

  Print concise phase-level progress to stdout and actionable failures to stderr; do not add a new
  logging framework. Run the focused tests. Expected: all preflight cases pass without starting a
  real benchmark.

- [ ] **Step 3: Specify safe environment capture and CPU-affinity selection**

  Test parsing `/sys/devices/system/cpu/cpu*/topology/thread_siblings_list` against the process's
  allowed CPU set and selecting the lowest logical CPU from each physical sibling group. Test
  disjoint ranges and offline CPUs. Specify `environment/run.json` fields for kernel, CPU model and
  topology, selected affinity, total memory, load, governor, exact JDK identities, Gradle version,
  UTC timestamps, and process-hygiene observations.

  Add negative assertions that the JSON never contains hostname, username, home-directory value,
  arbitrary environment variables, or credentials.

  Run:

  ```bash
  ./gradlew :benchmark-reporting:test --tests '*ScorecardEnvironmentTest*'
  ```

  Expected: failures identify the missing topology parser and allowlist-only serializer.

- [ ] **Step 4: Implement allowlist-only environment capture**

  Read only `/proc/loadavg`, `/proc/meminfo`, CPU topology/model files, scaling-governor files, and
  the explicit Java/Gradle command outputs. Record process hygiene as command names, numeric PIDs,
  and the operator decision; omit full command lines. Detect IntelliJ, Gradle/Kotlin daemons, Codex,
  debuggers, remote desktop, and desktop-shell processes. Reject an active IntelliJ process, Gradle
  or Kotlin daemon, another JMH run, or profiler targeting the selected CPUs. Record but do not
  reject Codex, the desktop shell, or NoMachine; exclude their observed CPU activity from any claim
  of an idle machine. Never signal or terminate a process.

  Run the environment test. Expected: affinity and redaction cases pass.

- [ ] **Step 5: Specify staging, profiling, JMH, and all-or-nothing publication**

  With a fake `ProcessExecutor` and fixed `Clock`, assert this sequence:

  1. create `.benchmark-staging/consumer-performance-scorecard/<run-id>`, where the UTC identifier
     uses `yyyyMMdd'T'HHmmss'Z'`;
  2. run each of seven method selectors separately for `cpu`, `alloc`, and `lock` profiles with one
     fork and short smoke iterations, then use the selected JDK's `jfr view` command to render a
     text summary beside each recording;
  3. run one unprofiled final JMH command for all seven rows;
  4. write `manifest.json`, `environment/run.json`, and `raw/results.csv`;
  5. invoke scorecard validation/rendering;
  6. atomically move the complete run to
     `benchmark-results/consumer-performance-scorecard/<run-id>`.

  Require 21 nonempty `raw/profiles/<method>/<event>.jfr` files and 21 matching `.txt` summaries.
  Use `hot-methods` for CPU, `allocation-by-class` for allocation, and `contention-by-site` for
  locks. Test child failure, missing CSV, malformed CSV, scorecard rejection, profile or summary
  failure, target collision, and final move failure. Every failure must leave the accepted target
  absent and retain the staging directory with a failure summary for diagnosis.

- [ ] **Step 6: Implement deterministic command construction**

  Derive the profiler library from the selected launcher home as `lib/libasyncProfiler.so`; fail
  before running if it is absent. Every JMH fork receives:

  ```text
  -Drevoman.scorecard.expectedJavaFeature=25
  -Drevoman.banner=off
  ```

  Construct the final command as argument values, not a shell string:

  ```text
  taskset --cpu-list <selected-cpus> <java25> -jar <benchmark-jar>
  ^com\.salesforce\.revoman\.benchmark\.ConsumerJourneyBenchmark\..*$
  -bm avgt -tu ms -t 1 -f 5 -wi 10 -i 20 -w 1s -r 1s
  -rf csv -rff <staging>/raw/results.csv
  -jvmArgsAppend "-Drevoman.scorecard.expectedJavaFeature=25 -Drevoman.banner=off"
  ```

  Profile commands use the exact single-method selector, one fork, one warmup, one measurement, and
  a single `-jvmArgsAppend` value containing the two required system properties plus
  `-agentpath:<lib>=start,event=<cpu|alloc|lock>,file=<profile>,loglevel=warn`. After each profile,
  invoke `<selected-java-home>/bin/jfr view` with the fixed view for that event and save stdout to
  the matching `.txt` file. Record profile and summary paths under a `profilerFacts` manifest
  section and initialize a separate `optimizationHypotheses` array as empty. Do not reuse profiled
  measurements in `results.csv`.

  Use `Files.move(stagingRun, acceptedRun, ATOMIC_MOVE)` with a same-filesystem non-atomic fallback;
  never replace an existing accepted run. Run the runner tests. Expected: every success and rollback
  case passes and the fake sees no recursive Gradle command.

- [ ] **Step 7: Build the exact manifest and dependency fingerprint**

  Manifest schema version `1` records study/run IDs, library version, Git revision, anchored
  selector, ordered expected rows, exact final command, fixed profile, dependency fingerprint,
  start/end timestamps, affinity, Java identities, runtime-validation summary, and these relative
  paths:

  ```text
  environment/run.json
  raw/results.csv
  scorecard.csv
  report.md
  performance-scorecard.adoc
  raw/profiles/<method>/<event>.jfr
  raw/profiles/<method>/<event>.txt
  ```

  Compute the dependency fingerprint as SHA-256 over a canonical stream containing the relative
  path and bytes of `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, and the
  JMH fat JAR. Test that changing any input changes the fingerprint and that map/filesystem order
  does not.

- [ ] **Step 8: Add the compiled CLI entry and ignore only disposable staging**

  `ConsumerScorecardMain.kt` accepts explicit `--project-root`, `--benchmark-jar`,
  `--java-executable`, `--java-feature`, `--gradle-daemon-java-feature`, `--gradle-max-workers`,
  `--library-version`, `--runtime-validation`, and repeatable `--allowed-dirty-path` arguments. It
  exits `0` only after accepted publication and `2` for validation/execution failures. Add
  `/.benchmark-staging/` to `.gitignore`; do not ignore `benchmark-results/`.

  Run:

  ```bash
  ./gradlew :benchmark-reporting:spotlessApply :benchmark-reporting:test
  git diff --check
  ```

  Expected: all runner, environment, and reporting tests pass.

- [ ] **Step 9: Commit the runner slice**

  ```bash
  git add .gitignore benchmark-reporting/src
  git commit -m "benchmark-reporting: orchestrate consumer scorecards"
  ```

---

## Task 4: Wire JDK 25, the executable artifact, and the Gradle entry point

**Files:**

- Create: `gradle/gradle-daemon-jvm.properties`
- Create: `.run/Consumer Journey JVM Debug.run.xml`
- Create: `build-logic/src/test/kotlin/ConsumerScorecardPluginFunctionalTest.kt`
- Modify: `build-logic/build.gradle.kts`
- Modify: `build-logic/src/main/kotlin/revoman.benchmarks.gradle.kts`
- Modify: `benchmarks/build.gradle.kts`
- Modify: `benchmark-reporting/build.gradle.kts`

- [ ] **Step 1: Generate vendor-neutral Gradle daemon JVM criteria**

  Run the Gradle-supported generator, then inspect rather than hand-author the file:

  ```bash
  ./gradlew updateDaemonJvm --jvm-version=25
  sed -n '1,120p' gradle/gradle-daemon-jvm.properties
  ```

  Expected: the criteria require Java 25 and do not pin a vendor or installation path.

- [ ] **Step 2: Add the fixed kotlinx-benchmark profile**

  In `revoman.benchmarks.gradle.kts`, register `consumerScorecard` using `commonProfile` with 20
  measurements, 10 warmups, one-second iterations, and five forks. Include only
  `com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.*`. Keep `smoke`, `final`, and
  `collectionScaleFinal` unchanged.

  Run:

  ```bash
  ./gradlew :benchmarks:tasks --all | rg 'consumerScorecard|mainConsumerScorecard'
  ```

  Expected: the aggregate and main-target tasks are present.

- [ ] **Step 3: Publish and resolve the prepared JMH fat JAR lazily**

  In `benchmarks/build.gradle.kts`, add a non-resolvable, consumable
  `consumerScorecardExecutable` configuration whose artifact is the `mainBenchmarkJar` task output.
  In `benchmark-reporting/build.gradle.kts`, add the matching resolvable, non-consumable
  configuration and a project dependency on
  `project(path = ":benchmarks", configuration = "consumerScorecardExecutable")`.

  Run:

  ```bash
  ./gradlew :benchmarks:outgoingVariants \
    | sed -n '/consumerScorecardExecutable/,+12p'
  ./gradlew :benchmark-reporting:dependencies --configuration consumerScorecardExecutable
  ```

  Expected: one executable JAR artifact resolves and brings no benchmark runtime onto the runner's
  compile classpath.

- [ ] **Step 4: Write a failing Gradle TestKit fixture for task wiring**

  Add `testImplementation(gradleTestKit())` and Kotest/JUnit Platform configuration to
  `build-logic/build.gradle.kts`. The functional fixture applies the reporting plugin to a temporary
  multi-project build, supplies a fake executable JAR, and asserts:

  - `runConsumerScorecard` is `JavaExec` and depends on the executable artifact;
  - its launcher language version is 25;
  - all runner arguments are declared task inputs and resolved lazily;
  - `--gradle-max-workers` reflects the actual start parameter;
  - a Java 21 launcher/daemon mismatch fails before the fake child marker appears;
  - with Java 25 metadata, a lightweight fake child writes CSV and reaches the runner validation
    seam without running a real JMH profile.

  Run:

  ```bash
  ./gradlew -p build-logic test --tests '*ConsumerScorecardPluginFunctionalTest*'
  ```

  Expected: the test fails because `runConsumerScorecard` is not registered.

- [ ] **Step 5: Register `runConsumerScorecard` with a Java 25 launcher**

  Use `JavaToolchainService.launcherFor { languageVersion = JavaLanguageVersion.of(25) }`. Set the
  main class to `com.salesforce.revoman.benchmark.reporting.ConsumerScorecardMainKt`, classpath to
  the reporting main runtime, and argument providers for the project root, resolved fat JAR,
  selected Java executable/feature, current daemon feature, max workers, the
  `revoman.version` Gradle property,
  `-PscorecardRuntimeValidation=<path>`, and optional comma-separated
  `-PscorecardAllowedDirty=<relative-paths>`.

  Make the runtime-validation property mandatory only when the task executes, not during normal
  project configuration. Do not call Gradle from the Kotlin runner.

  Run the functional test. Expected: all wiring and fake-child cases pass.

- [ ] **Step 6: Verify all four Java layers on 25**

  Re-resolve the execution-prerequisite variable if this is a new shell, then verify the wrapper
  and single-use daemon:

  ```bash
  export REVOMAN_JAVA25_HOME="$(
    ./gradlew -q javaToolchains \
      | awk '/Language Version: 25/{seen=1} seen && /Location:/{sub(/^.*Location: /, ""); print; exit}'
  )"
  test -x "$REVOMAN_JAVA25_HOME/bin/java"
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew --version
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew :benchmarks:mainBenchmarkJar :benchmark-reporting:test \
      -Dorg.gradle.workers.max=1 --no-daemon
  ```

  Expected: wrapper launcher and single-use daemon both report feature 25; the benchmark compiles
  for 25; reporting tests pass. If any layer reports 21, stop and diagnose instead of weakening the
  preflight.

- [ ] **Step 7: Add the checked-in remote JVM debug configuration**

  Create an IntelliJ Remote JVM Debug configuration named `Consumer Journey JVM Debug` for
  `localhost:5005`, client/attach mode, with no credentials or absolute paths. It will attach to a
  single JMH fork launched with:

  ```text
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005
  ```

  Use `idea-cli` to open the worktree, refresh the project, and confirm through the IntelliJ MCP that
  the configuration is discoverable. Do not start a debug server or expose a non-loopback listener.

- [ ] **Step 8: Format and commit the Gradle slice**

  ```bash
  ./gradlew spotlessApply
  ./gradlew -p build-logic test
  git diff --check
  git add gradle/gradle-daemon-jvm.properties '.run/Consumer Journey JVM Debug.run.xml' \
    build-logic benchmarks/build.gradle.kts benchmark-reporting/build.gradle.kts
  git commit -m "build: wire JDK 25 consumer scorecard"
  ```

---

## Task 5: Validate runtime paths, capture profiles, and publish the baseline

**Files:**

- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/manifest.json`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/environment/run.json`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/raw/results.csv`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/raw/profiles/**/*.jfr`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/raw/profiles/**/*.txt`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/scorecard.csv`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/report.md`
- Create: `benchmark-results/consumer-performance-scorecard/<run-id>/performance-scorecard.adoc`
- Create: `docs/modules/ROOT/partials/performance-scorecard.adoc`
- Create: `benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/DocumentationScorecardTest.kt`
- Modify: `docs/modules/ROOT/pages/performance.adoc`

- [ ] **Step 1: Confirm the required live IDE capabilities before runtime investigation**

  Use the `idea-cli` skill to open this exact worktree and confirm the active IntelliJ project path.
  Use `ide_index_status` and require a ready index. Require actual JVM debugger MCP operations such
  as run-configuration discovery, breakpoint creation, JVM debug-session start, stack/variable
  inspection, and resume. Confirm `Consumer Journey JVM Debug` is listed. The currently observed
  generic IntelliJ endpoint exposes only `xdebug_*`, which is not sufficient for this Kotlin/JVM
  task.

  Expected: continue only when the `idea`, `intellij-index`, and JVM debugger bindings are callable
  in this task. If they are still absent, stop here and ask the user to expose the JVM debugger MCP;
  do not replace the debugger checks with logs or inference.

- [ ] **Step 2: Navigate every target path with IDE Index**

  Resolve definitions and callers for `ReVoman.revUp(Kick)`, `ReVoman.revUp(List<Kick>)`,
  `ReVoman.revUp(Runbook)`, `isV3Collection`, `V3Loader.load`, the V2 loader/buffer path,
  `PmSandbox`, runbook contract evaluation, and `Rundown.toJson`. Record file/line anchors and the
  expected breakpoint sequence in a temporary runtime-validation note tied to `git rev-parse HEAD`.

- [ ] **Step 3: Debug each isolated smoke journey**

  Run one method at a time with one fork, a single short warmup, and a single measurement. Through
  debugger breakpoints and inspected values, verify and record:

  - V2 enters only the V2 buffer/loader path and both V3 sizes enter `V3Loader.load`;
  - `PmSandbox` is absent from script-free V2/V3 and present only for scripted handoffs;
  - handler counts are 10, 10, 100, 10, 30, and 30 for the six execution rows;
  - mixed-type environment values reach the second and third kick;
  - runbook consumes/produces and `assertAfter` execute successfully;
  - serialization begins with the prepared successful 100-step rundown and does not call
    `ReVoman.revUp` inside the measurement.

  For each exact method name from Task 1 Step 7, set `REVOMAN_DEBUG_METHOD`, start this command, and
  attach `Consumer Journey JVM Debug` while the fork is suspended:

  ```bash
  export REVOMAN_DEBUG_METHOD='postmanV2TenStepRevUp'
  "$REVOMAN_JAVA25_HOME/bin/java" -jar \
    benchmarks/build/benchmarks/main/jars/benchmarks-main-jmh-JMH.jar \
    "^com\.salesforce\.revoman\.benchmark\.ConsumerJourneyBenchmark\.${REVOMAN_DEBUG_METHOD}$" \
    -bm avgt -tu ms -t 1 -f 1 -wi 1 -i 1 -w 250ms -r 250ms \
    -jvmArgsAppend \
    "-Drevoman.scorecard.expectedJavaFeature=25 -Drevoman.banner=off -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005"
  ```

  Repeat with the other six names; do not run two suspended forks concurrently.

  Save a credential-free JSON validation record containing revision, timestamp, seven method names,
  debugger tool/session identity, and boolean results. Do not include usernames or hostnames.
  Store it at
  `.benchmark-staging/consumer-performance-scorecard/runtime-validation.json`, then set:

  ```bash
  export REVOMAN_RUNTIME_VALIDATION="$(pwd)/.benchmark-staging/consumer-performance-scorecard/runtime-validation.json"
  test -f "$REVOMAN_RUNTIME_VALIDATION"
  ```

- [ ] **Step 4: Run fast pre-measurement verification**

  ```bash
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew :benchmarks:test :benchmarks:mainBenchmarkJar :benchmark-reporting:test \
      -Dorg.gradle.workers.max=1 --no-daemon
  git status --short
  ```

  Expected: tests pass, the JMH JAR exists, and every dirty path is either an intended scorecard
  change or a separately identified user-owned IDE file.

- [ ] **Step 5: Prepare the machine without broad process termination**

  Close IntelliJ normally, stop only this repository's Gradle daemons, inspect CPU governor/load and
  user processes, and resolve exact interfering PIDs. Never kill by broad process name and never
  signal Codex, the desktop shell, NoMachine, or unrelated user jobs. If interference cannot be
  removed safely, postpone the run.

  Re-run `git status --porcelain=v1`. In this worktree, the already observed user-owned paths are
  `.idea/kotlinc.xml` and `.idea/misc.xml`; stop if any other tracked path is dirty after Tasks 1-4
  are committed. Set the exact allowlist and record it in the manifest:

  ```bash
  export REVOMAN_ALLOWED_DIRTY='.idea/kotlinc.xml,.idea/misc.xml'
  ```

- [ ] **Step 6: Execute the one accepted scorecard workflow**

  Run with the validation record from Step 3 and Java 25 in the wrapper environment:

  ```bash
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew :benchmark-reporting:runConsumerScorecard \
      --no-daemon --max-workers=1 \
      -PscorecardRuntimeValidation="$REVOMAN_RUNTIME_VALIDATION" \
      -PscorecardAllowedDirty="$REVOMAN_ALLOWED_DIRTY"
  ```

  Expected: the runner first writes 21 separate async-profiler JFR captures, then one unprofiled
  seven-row CSV, validates the absolute scorecard, and atomically installs exactly one UTC run
  directory. Any failure means reject the entire attempt, diagnose under systematic-debugging, and
  rerun the unchanged protocol; never hand-edit measurements or publish a subset.

- [ ] **Step 7: Audit the accepted evidence before documentation**

  ```bash
  find benchmark-results/consumer-performance-scorecard -maxdepth 5 -type f -print | sort
  git status --short
  ```

  Check that the manifest revision is the measured commit, all seven rows are `avgt`/`ms/op`, each
  has one thread and 100 samples, errors are finite and nonnegative, every JDK feature is 25, the
  affinity matches `environment/run.json`, and all 21 profiles and 21 fixed-view summaries are
  nonempty. Confirm `profilerFacts` references the raw captures/summaries and
  `optimizationHypotheses` remains separate and empty. This is a read-only audit of the atomically
  published run; do not hand-edit evidence or make a production change from the observations.

- [ ] **Step 8: Add a failing evidence-to-doc identity test**

  In `DocumentationScorecardTest`, read `docs/modules/ROOT/partials/performance-scorecard.adoc`, parse
  its `scorecard-study-id` and `scorecard-run-id`, resolve the corresponding accepted evidence file,
  and assert byte-for-byte identity. Also assert `performance.adoc` includes
  `partial$performance-scorecard.adoc[]` exactly once.

  Configure `benchmark-reporting` tests with a `revoman.projectRoot` system property from the Gradle
  project directory; do not infer the root from the test process working directory.

  Run:

  ```bash
  ./gradlew :benchmark-reporting:test --tests '*DocumentationScorecardTest*'
  ```

  Expected: it fails because the documentation partial is not present.

- [ ] **Step 9: Publish the generated AsciiDoc partial and rewrite the performance page**

  Copy the accepted `performance-scorecard.adoc` bytes without reformatting to
  `docs/modules/ROOT/partials/performance-scorecard.adoc`. Replace the unsupported 75-step anecdote
  in `performance.adoc` with:

  - the included generated scorecard;
  - the named CPU/JDK/OS snapshot from the accepted environment record;
  - the exact warmed JMH protocol;
  - an explanation that figures include only in-process ReVoman engine work;
  - explicit exclusions for DNS, sockets, TLS, remote-service latency, cold JVM startup, and caller
    work outside the measured public call;
  - a link to the accepted evidence directory and a statement that it is an absolute baseline, not
    an optimization comparison.

  Run the documentation test. Expected: it passes.

- [ ] **Step 10: Build and inspect the Antora site**

  ```bash
  npx --yes --package antora --package @antora/lunr-extension \
    antora antora-playbook.yml
  export REVOMAN_PERFORMANCE_HTML="$(find build/site -type f -path '*/performance.html' -print -quit)"
  test -n "$REVOMAN_PERFORMANCE_HTML"
  rg -n 'Postman V2 collection|Verbose result rendering|99.9' \
    "$REVOMAN_PERFORMANCE_HTML"
  ```

  Expected: Antora succeeds and the rendered page contains all seven rows and the confidence label.

- [ ] **Step 11: Run repository verification on Java 25**

  ```bash
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew build :revoman:test :revoman:integrationTest \
      :benchmarks:mainBenchmarkJar :benchmark-reporting:test spotlessCheck detekt \
      --no-daemon --max-workers=1
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew :revoman:kaptKotlin :revoman:classes --no-daemon --max-workers=1
  JAVA_HOME="$REVOMAN_JAVA25_HOME" PATH="$REVOMAN_JAVA25_HOME/bin:$PATH" \
    ./gradlew qodanaScan --no-daemon --max-workers=1
  git diff --check
  ```

  Expected: all unit/integration tests, benchmark compilation, reporting tests, Spotless, Detekt,
  Antora, and Qodana pass. Investigate any failure with `superpowers:systematic-debugging` and the JVM
  debugger; do not waive a gate.

- [ ] **Step 12: Commit the accepted evidence and documentation**

  Review `git diff --stat` and `git status --short`. Stage only the new accepted run, generated
  partial, performance page, documentation test, and its build-script property wiring:

  ```bash
  git add benchmark-results/consumer-performance-scorecard \
    docs/modules/ROOT/partials/performance-scorecard.adoc \
    docs/modules/ROOT/pages/performance.adoc \
    benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/DocumentationScorecardTest.kt \
    benchmark-reporting/build.gradle.kts
  git commit -m "docs: publish consumer performance baseline"
  ```

  Do not push, release, merge, or publish artifacts. Leave all user-owned `.idea` changes unstaged.

---

## Final acceptance checklist

- [ ] Exactly seven stable consumer-facing benchmark rows are present.
- [ ] The first six rows time one complete public `revUp` call; serialization times only `toJson`.
- [ ] All HTTP traffic uses deterministic in-process `Kick.httpClient(HttpHandler)` responses.
- [ ] Gradle client, daemon, toolchain, runner, and every JMH fork report Java feature 25.
- [ ] Final results use `avgt`, `ms/op`, one thread, five forks, ten warmups, twenty measurements,
  one-second iterations, and 99.9-percent errors.
- [ ] The existing comparison command still uses the strict disjoint-interval rule unchanged.
- [ ] Runtime paths were verified with IDE Index and a real JetBrains JVM debugger binding.
- [ ] CPU, allocation, and lock profiles exist for every journey and are not timing inputs.
- [ ] The accepted evidence directory was installed atomically and contains no credentials,
  usernames, or hostname.
- [ ] The Antora partial is byte-identical to accepted evidence and the rendered page names all
  exclusions.
- [ ] No file under `revoman/src/main` changed and no prior evidence or user-owned IDE file was
  staged.
- [ ] Full Gradle, Antora, Detekt, Spotless, integration, and Qodana verification passed.

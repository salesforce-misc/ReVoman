# ReVoman Benchmark Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to execute this plan task-by-task with a fresh implementer and reviewer for each task.

**Goal:** Establish a reusable, isolated benchmark platform for ReVoman and produce reproducible evidence that deferring the legacy `JSEvaluator` improves script-free `revUp` performance.

**Architecture:** Convert the source-bearing root into a source-free Gradle 9.7.1 aggregator with `:revoman`, `:benchmarks`, and `:benchmark-reporting` subprojects. Put reusable build policy in a `build-logic` included build, runtime scenarios in `:benchmarks`, and generic typed-DataFrame comparison/report generation in `:benchmark-reporting`. Keep the eager and lazy production variants in separate commits so measurements can be traced to exact revisions.

**Tech Stack:** Gradle 9.7.1, JDK 25, Kotlin 2.4.20-RC, kotlinx-benchmark 0.4.18 with its supported JMH default, Kotlin DataFrame 1.0.0-rc01, Kotest, http4k, Detekt, Spotless, Qodana, Maven Publish, IntelliJ IDEA debugger and IDE Index MCP.

**Spec:** `docs/superpowers/specs/2026-08-24-revoman-benchmark-platform-design.md`

## Global constraints

- Work only on `codex/benchmark-platform-lazy-js-evaluator` in the existing worktree.
- Do not push, publish to a remote repository, invoke the release script, add a server, add a notebook, or add an automated process-killing task.
- Preserve `com.salesforce.revoman:revoman:0.90.0`, public APIs, runtime defaults, generated immutable classes, JAR contents, POM semantics, Gradle module metadata, sources JAR, and release behavior.
- Keep the root source-free. Do not use `allprojects` or `subprojects`; do not mutate another project's model.
- The current Nexus publishing plugin is the sole compatibility exception: never apply it during normal configuration. The existing release script may explicitly enable it at the root with `-Prevoman.releaseMode=true` and disable Isolated Projects for that legacy invocation because the plugin itself requires root-wide traversal. Do not use Gradle's ignore-problems option.
- Use type-safe project accessors for project dependencies. Use string task paths only for source-free root lifecycle aggregation where cross-project model access would violate isolation.
- Keep the DataFrame compiler plugin and DataFrame dependencies confined to `:benchmark-reporting`. `:benchmark-reporting` must not depend on `:revoman` or `:benchmarks`.
- `./gradlew build` must compile and test all three subprojects without running JMH.
- Use `Kick.httpClient(HttpHandler)` for deterministic in-process HTTP. Do not introduce a test-only factory, overload, or public API.
- Run measurements with `--no-daemon --max-workers=1` and an identical CPU affinity for both revisions. Never combine runs produced with different settings.
- Preserve every raw result needed by Task 3 in the ignored SDD workspace. Do not place accepted evidence under `benchmark-results/` until the reporter has validated the complete pair.
- The untouched base revision has a known Detekt failure in `KickHttpClientTest`: `RuntimeException` violates `TooGenericExceptionThrown`. Task 1 may replace only that test exception with `IllegalStateException`; it must not change production behavior or the assertion contract.
- Every task ends as exactly one implementation commit in final history. During that task's review loop, amend its commit instead of retaining separate fix commits, then rerun every check and measurement whose revision changed. Task 1, Task 2, and Task 3 together are the three implementation commits required by the design.

## Stable reporting contract

Implement the reporting application in package `com.salesforce.revoman.benchmark.reporting` with these entry points and domain types. Internal helpers may be refined without widening the public surface.

```kotlin
package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path

data class ReportRequest(val manifest: Path)

object BenchmarkReportCli {
  fun run(args: Array<String>): Int
}

fun main(args: Array<String>) {
  kotlin.system.exitProcess(BenchmarkReportCli.run(args))
}
```

The accepted command is:

```bash
./gradlew :benchmark-reporting:run --args="compare --manifest /absolute/path/to/manifest.json"
```

Use DataFrame-generated row types for normalized input and comparison output. The schemas must contain at least these fields:

```kotlin
@DataSchema
interface NormalizedJmhRowSchema {
  val benchmark: String
  val parameters: String
  val mode: String
  val threads: Int
  val samples: Int
  val score: Double
  val scoreError99_9: Double
  val unit: String
}

@DataSchema
interface ComparisonRowSchema {
  val benchmark: String
  val parameters: String
  val mode: String
  val threads: Int
  val unit: String
  val baselineScore: Double
  val baselineError99_9: Double
  val baselineLower: Double
  val candidateScore: Double
  val candidateError99_9: Double
  val candidateUpper: Double
  val deltaPercent: Double
  val passed: Boolean
}
```

Normalize every `Param: <name>` JMH column into one deterministic `parameters` value by sorting parameter names and serializing unambiguously. The comparison key is `(benchmark, parameters, mode, threads, unit)`. A valid row passes only when:

```text
candidateScore + candidateError99_9 < baselineScore - baselineError99_9
```

Return `0` only when every selected row passes, `1` for a structurally valid comparison with at least one failing row, and `2` for usage or invalid input. Invalid input includes unreadable files, malformed CSV, missing required columns, missing 99.9-percent confidence errors, malformed or non-finite numeric values, negative errors, duplicate keys, empty selected input, differing key sets, and incompatible units. Validate into a sibling temporary directory, then atomically move the completed `comparison.csv` and `report.md` into the manifest's run directory. On exit `1` or `2`, leave the run directory without new or partially replaced generated outputs.

## Measurement profiles

The `:benchmarks` convention exposes two named kotlinx-benchmark configurations for the `main` target:

| Configuration | Forks | Warmups | Measurements | Iteration time | Mode | Unit | Format |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| `smoke` | 1 | 2 | 2 | 250 ms | Average time | ms/op | CSV |
| `final` | 5 | 10 | 20 | 1 s | Average time | ms/op | CSV |

Use `advanced("jvmForks", value)` for JMH forks and do not override the JMH version supported by kotlinx-benchmark 0.4.18. Verify task/configuration names with `./gradlew :benchmarks:tasks --all`, then run the checked-in configurations through the generated `:benchmarks:mainSmokeBenchmark` and `:benchmarks:mainFinalBenchmark` tasks.

---

## Task 1: Migrate the build and establish the reusable benchmark platform

**Outcome:** A behavior-neutral multi-project build, reusable benchmark scenario, generic DataFrame reporter with tests, all repository path updates, and a retained eager-baseline measurement from the Task 1 commit.

**Files:**

- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/revoman.kotlin-jvm.gradle.kts`
- Create: `build-logic/src/main/kotlin/revoman.library.gradle.kts`
- Create: `build-logic/src/main/kotlin/revoman.benchmarks.gradle.kts`
- Create: `build-logic/src/main/kotlin/revoman.benchmark-reporting.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`
- Modify: `gradle/libs.versions.toml`
- Delete: `buildSrc/`
- Move: `src/main/` to `revoman/src/main/`
- Move: `src/test/` to `revoman/src/test/`
- Move: `src/integrationTest/` to `revoman/src/integrationTest/`
- Move: `js/` to `revoman/js/`
- Create: `revoman/build.gradle.kts`
- Create: `benchmarks/build.gradle.kts`
- Create: `benchmarks/src/main/kotlin/com/salesforce/revoman/benchmark/ScriptFreeRevUpBenchmark.kt`
- Create: `benchmarks/src/main/resources/pm-templates/v3/single-ok/.resources/definition.yaml`
- Create: `benchmarks/src/main/resources/pm-templates/v3/single-ok/o.request.yaml`
- Create: `benchmark-reporting/build.gradle.kts`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/BenchmarkSchemas.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/BenchmarkReportCli.kt`
- Create: `benchmark-reporting/src/main/kotlin/com/salesforce/revoman/benchmark/reporting/Main.kt`
- Create: `benchmark-reporting/src/test/kotlin/com/salesforce/revoman/benchmark/reporting/BenchmarkReportCliTest.kt`
- Create: `benchmark-reporting/src/test/resources/jmh/` fixtures used by the reporter tests
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/qodana.yml`
- Modify: `.github/workflows/watch-maven-central.yml`
- Modify: `qodana.yaml`
- Modify: `DEVELOPMENT.md`
- Modify: repository documentation containing `build/libs`, root publication task, or old `src/` paths
- Modify: `scripts/release.sh`
- Modify: `scripts/watch-maven-central.sh`
- Modify: `.gitignore` only if needed to ignore SDD and benchmark staging without ignoring accepted `benchmark-results/`

### Step 1: Capture the pre-migration artifact contract

Before moving files, record `git rev-parse HEAD` and generate the existing binary JAR, sources JAR, POM, and Gradle module metadata without invoking a remote publication:

```bash
./gradlew jar sourcesJar generatePomFileForMavenJavaPublication generateMetadataFileForMavenJavaPublication --no-daemon --max-workers=1
```

Copy those four outputs plus sorted ZIP entry lists and extracted manifests into the ignored SDD workspace under `artifacts/pre-migration/`. Record SHA-256 values, but compare structure and metadata semantically because ZIP timestamps need not match.

Expected: the files are captured from the eager base, and no tracked file changes are introduced.

### Step 2: Bootstrap the reporting subproject without implementation

Create `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, the minimal `revoman.kotlin-jvm` and `revoman.benchmark-reporting` conventions, and an empty `benchmark-reporting` application project. Import the root version catalog into the included build. Add `includeBuild("build-logic")` and `include(":benchmark-reporting")` to `settings.gradle.kts` while leaving the existing root library and `buildSrc` behavior intact for this intermediate red-test state.

Apply Kotlin/JDK 25, application support, DataFrame 1.0.0-rc01, and `org.jetbrains.kotlin.plugin.dataframe` 2.4.20-RC only to `:benchmark-reporting`. Configure its test dependencies and application main class. Do not create any reporting production classes yet.

Run:

```bash
./gradlew :benchmark-reporting:compileTestKotlin --no-daemon --max-workers=1
```

Expected: the empty reporting module is addressable and its build configuration succeeds.

### Step 3: Add reporter acceptance tests first

Create compact JMH CSV fixtures and write Kotest tests against `BenchmarkReportCli.run`. Each test uses a fresh temporary run directory and manifest.

Required red tests:

1. Disjoint confidence intervals create sorted `comparison.csv` and `report.md`, return `0`, and calculate both bounds.
2. Overlapping intervals are valid, return `1`, and leave pre-existing generated outputs unchanged.
3. Baseline and candidate with different keys return `2` and publish nothing.
4. Parameter columns in different source-column order normalize to the same sorted comparison key.
5. Malformed numeric text returns `2`.
6. `NaN`, positive infinity, and negative infinity each return `2`.
7. A negative score error returns `2`.
8. A missing `Score Error (99.9%)` column or value returns `2`.
9. Duplicate normalized keys return `2`.
10. A failure during staged publication leaves both prior outputs intact and no staging directory behind.

Run:

```bash
./gradlew :benchmark-reporting:test --tests '*BenchmarkReportCliTest' --no-daemon --max-workers=1
```

Expected: compilation fails because the reporting classes do not yet exist. Preserve the exact red result in the implementer report.

### Step 4: Replace `buildSrc` with isolated convention plugins

Create the included build and move reusable policy into four focused precompiled script plugins:

- `revoman.kotlin-jvm`: Kotlin JVM, JDK 25 toolchain, common compiler flags, test conventions, Spotless, and Detekt.
- `revoman.library`: Java library, integration-test suite, existing source/resource layout semantics, documentation/JAR tasks, Immutables/KSP or KAPT wiring, Maven publication, signing, and existing publication metadata.
- `revoman.benchmarks`: Kotlin JVM, kotlinx-benchmark 0.4.18, Kotlin all-open for benchmark state, `main` target, and the exact smoke/final configurations above.
- `revoman.benchmark-reporting`: Kotlin JVM, application support, DataFrame 1.0.0-rc01, and `org.jetbrains.kotlin.plugin.dataframe` at 2.4.20-RC.

Import the root version catalog into `build-logic` instead of duplicating dependency versions. Put publication values in `gradle.properties`:

```properties
revoman.group=com.salesforce.revoman
revoman.version=0.90.0
revoman.artifactId=revoman
revoman.stagingProfileId=1ea0a23e61ba7d
```

Use the existing staging profile value from `buildSrc/src/main/kotlin/Config.kt`; do not invent a replacement. Convention code must read these providers from the consuming project and must not hard-code the release version.

In `settings.gradle.kts`:

- `includeBuild("build-logic")`
- enable `TYPESAFE_PROJECT_ACCESSORS`
- include `:revoman`, `:benchmarks`, and `:benchmark-reporting`
- set centralized repositories and `RepositoriesMode.FAIL_ON_PROJECT_REPOS`
- retain plugin management and Develocity configuration

In `gradle.properties` retain build cache and configuration cache, set `org.gradle.configuration-cache.problems=fail`, and enable `org.gradle.isolated-projects=true` without an ignore-problems option.

Make the root apply only `base` during normal builds. Keep Maven-publish behavior in `:revoman`, the sole published project. Declare the current `io.github.gradle-nexus.publish-plugin` at the root with `apply false`; apply it at the root only when the provider-backed Gradle property `revoman.releaseMode` is exactly `true`. In that conditional branch, set the root `group` and `version` from `revoman.group` and `revoman.version` providers before applying and configuring Nexus, because the plugin reads the root coordinates for its staging description and package group. The release script must pass `-Prevoman.releaseMode=true -Dorg.gradle.isolated-projects=false` to its existing Nexus task route. This preserves the legacy release process while keeping the incompatible plugin absent from every normal build, CI run, benchmark, and IDE sync. Never set an ignore-problems property. Aggregate root `assemble` and `check` with isolated-project-safe task-path dependencies. Do not put sources, dependencies, publications, or compilation configuration in the root.

### Step 5: Move the library without changing its contract

Move the existing sources and `js` directory to `:revoman`. Transfer the root build's dependencies, generated-source wiring, integration suite, publication, signing, test JVM settings, resource exclusions, and task behavior to `revoman/build.gradle.kts` plus the conventions.

Use `projects.revoman` from `benchmarks/build.gradle.kts`. In the integration-test suite, use the subproject's own production component without a root-project dependency.

Fix only the known pre-existing Detekt failure in `revoman/src/test/kotlin/com/salesforce/revoman/KickHttpClientTest.kt` by throwing `IllegalStateException("whisper boom")` instead of `RuntimeException("whisper boom")`; keep the asserted ReVoman exception and message unchanged.

### Step 6: Implement the generic reporter until the tests pass

Implement typed DataFrame schemas, CSV normalization, manifest parsing, validation, comparison, CSV emission, and Markdown rendering. The reporter must operate solely on paths and metadata from the manifest and must contain no branch for the lazy-evaluator study.

Use `java.nio.file.Files.createTempDirectory(runDir.parent, ".benchmark-report-")` for sibling staging. Fully write and close staged outputs, validate that both exist, then publish without exposing a half-written pair. Prefer an atomic directory exchange or backup-and-rollback sequence that preserves both prior files on any failure. Always clean the staging and backup paths in `finally`.

Run:

```bash
./gradlew :benchmark-reporting:test --tests '*BenchmarkReportCliTest' --no-daemon --max-workers=1
```

Expected: all ten acceptance categories pass.

### Step 7: Add the script-free benchmark

Implement `ScriptFreeRevUpBenchmark` as JMH average-time state. In trial setup, resolve and validate the benchmark-owned collection resource without creating `PostmanSDK`. In the measured method:

```kotlin
val result =
  ReVoman.revUp(
    Kick.configure()
      .templatePath(templatePath)
      .dynamicEnvironment("baseUrl", "http://benchmark.invalid")
      .httpClient { Response(Status.OK).body("""{"ok":true}""") }
      .off(),
  )
blackhole.consume(result)
```

Construct normal per-run state inside the measured method. Do not cache a `Kick`, `PostmanSDK`, returned ledger, or HTTP server. Suppress only non-semantic banner/log noise using existing supported settings. Keep fixture discovery and invariant checks outside the measurement.

Run:

```bash
./gradlew :benchmarks:compileKotlin :benchmarks:tasks --all --no-daemon --max-workers=1
./gradlew :benchmarks:mainSmokeBenchmark --no-daemon --max-workers=1
```

Expected: benchmark compilation succeeds, the task listing proves both configurations are available, and smoke CSV contains the script-free benchmark in average-time milliseconds.

### Step 8: Update paths and release routing

Update all path-sensitive consumers:

- CI test reports from root `build/reports/tests` to `revoman/build/reports/tests` plus reporting-test results where applicable.
- Qodana generated-source preparation to `:revoman` tasks and generated-source paths.
- local development JAR references to `revoman/build/libs/revoman-0.90.0.jar`.
- local Maven publication to `:revoman:publishToMavenLocal` while preserving the remote release script's existing user-facing command flow.
- version lookup in release/watch scripts from `buildSrc/Config.kt` to `revoman.version` in `gradle.properties`.
- documentation and scripts that assume the old source or artifact paths.

Update the existing release Gradle invocation to include `-Prevoman.releaseMode=true -Dorg.gradle.isolated-projects=false`. Do not disable isolation in `gradle.properties`, CI, development commands, local publication, benchmarks, or IDE sync.

Do not invoke `scripts/release.sh` or any remote publish task.

### Step 9: Verify migration and artifact compatibility

Run these commands from a clean daemon state:

```bash
./gradlew --stop
./gradlew build --no-daemon --max-workers=1
./gradlew :revoman:test :revoman:integrationTest :benchmark-reporting:test --no-daemon --max-workers=1
./gradlew spotlessCheck detekt qodanaScan --no-daemon --max-workers=1
./gradlew :revoman:publishToMavenLocal --no-daemon --max-workers=1
./gradlew build --configuration-cache --no-daemon --max-workers=1
./gradlew build --configuration-cache --no-daemon --max-workers=1
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository -Prevoman.releaseMode=true -Dorg.gradle.isolated-projects=false -Dorg.gradle.parallel=false --no-configuration-cache --no-daemon --max-workers=1 --dry-run
```

Expected:

- `build` compiles benchmark code and reporter tests but the output contains no JMH execution.
- all test and quality tasks pass.
- the second configuration-cache run reports reuse.
- output contains no Isolated Projects problem and no Configuration Cache problem.
- local Maven publication stays `com.salesforce.revoman:revoman:0.90.0`.
- the safe release-mode dry run exposes the existing root publish/close route, includes `:revoman`'s Maven publication-to-Sonatype task in the task graph, reports root group/version matching `:revoman`, and performs no task action or network publication; the preceding normal isolated builds never apply the Nexus plugin.

Generate migrated JAR, sources JAR, POM, and module metadata. Compare them to `artifacts/pre-migration/` by sorted entry list, manifest attributes, bundled immutable classes, source paths/content, dependency coordinates/scopes, variants, capabilities, and artifact coordinates. Record every intentional non-semantic difference; unexpected differences block the task.

Create a temporary Kotlin/Java consumer outside the repository, depend on `mavenLocal()` and `com.salesforce.revoman:revoman:0.90.0`, compile it, and run one script-free collection through `Kick.httpClient`. Delete or retain it only inside the ignored SDD workspace.

Use `idea-session sync`, wait for indexing to finish through IDE Index MCP, and record a successful Gradle resync.

### Step 10: Commit the platform, then capture the eager baseline

Review `git diff --check`, confirm that `PostmanSDK.kt` still has eager initialization, and commit all Task 1 tracked changes:

```bash
git add -A
git commit -m "build: establish reusable benchmark platform"
```

Record the Task 1 commit SHA as the baseline revision. Use the IntelliJ debugger with a constructor/initializer breakpoint in `JSEvaluator` and run the existing script-free `KickHttpClientTest` case. Record that eager `PostmanSDK` construction reaches the breakpoint. Also run the existing direct `evaluateJS` and `jsonStrToObj` tests and record their successful behavior.

Before the final baseline measurement:

1. Close IntelliJ and verify its exact processes exited.
2. Run `./gradlew --stop`; inspect current-user Gradle/Kotlin daemons and stop only exact remaining PIDs.
3. Inspect current-user processes and terminate only exact PIDs demonstrably interfering with measurement. Do not use name-wide kills.
4. Determine the lowest logical CPU from each physical-core sibling group from `/sys/devices/system/cpu/cpu*/topology/thread_siblings_list`, and use the resulting comma-separated affinity list for both variants.
5. Capture sanitized kernel, WSL state, CPU model/topology, memory, JVM, Gradle, governor, load, affinity, timestamp, benchmark configuration, command, and revision into `artifacts/measurements/baseline-environment.json`. Do not capture hostname, username, environment variables, command-line secrets, or credentials.
6. Store the recorded affinity in `CPU_AFFINITY`, run `taskset --cpu-list "$CPU_AFFINITY" ./gradlew :benchmarks:mainFinalBenchmark --no-daemon --max-workers=1`, and copy the raw CSV unchanged to `artifacts/measurements/baseline.csv`.

Expected: the raw baseline CSV is the unchanged JMH output for the exact final profile; the Task 1 SHA is recorded alongside it in baseline environment metadata and later in the manifest. Leave the tracked tree clean after the commit.

---

## Task 2: Defer the legacy evaluator and capture the candidate

**Outcome:** A single production change makes `JSEvaluator` lazy without altering direct-evaluation behavior, and a retained candidate measurement is produced from the Task 2 commit under the identical environment and profile.

**Files:**

- Modify: `revoman/src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt`
- Modify: `docs/modules/ROOT/examples/main/com/salesforce/revoman/internal/postman/PostmanSDK.kt`
- Modify: focused existing tests only if a direct-evaluation regression assertion is missing

### Step 1: Establish direct-evaluation regression coverage

Run the focused tests before changing production code:

```bash
./gradlew :revoman:test --tests '*EvalJsTest' --tests '*PostmanSDKEvalIsolationTest' --tests '*PostmanSDKJsonStrToObjTest' --no-daemon --max-workers=1
```

Expected: existing eager behavior passes. If the suites do not call `evaluateJS` twice and `jsonStrToObj` twice on the same `PostmanSDK`, add the smallest package-internal regression test that does so and asserts equal correct results. Do not inspect the lazy delegate through reflection and do not introduce a production seam.

### Step 2: Make the exact lazy change

Replace the eager declaration in the production source exactly with:

```kotlin
// The production script path uses PmSandbox. Defer this legacy evaluator until a caller invokes
// evaluateJS/jsonStrToObj directly, so script-free revUp runs do not create an unused Context.
private val jsEvaluator: JSEvaluator by lazy { JSEvaluator(nodeModulesPath) }
```

Mirror the same maintained example in the documentation source. Change no other production behavior in this commit.

### Step 3: Prove behavior and laziness

Run:

```bash
./gradlew :revoman:test --tests '*EvalJsTest' --tests '*PostmanSDKEvalIsolationTest' --tests '*PostmanSDKJsonStrToObjTest' --tests '*KickHttpClientTest' --no-daemon --max-workers=1
./gradlew :revoman:integrationTest --no-daemon --max-workers=1
./gradlew spotlessCheck detekt --no-daemon --max-workers=1
```

Expected: all focused, integration, and quality checks pass.

Open IntelliJ and synchronize the project. Put the same breakpoint in the `JSEvaluator` initializer used for the baseline proof:

- Run the same script-free `KickHttpClientTest`; the test completes and never reaches the breakpoint.
- Run the focused same-instance `evaluateJS` regression; the first call reaches the initializer and the second does not.
- Run the focused same-instance `jsonStrToObj` regression in a fresh test instance; the first call reaches the initializer and the second does not.

Record debugger session identifiers, run configuration names, breakpoint location, hit counts, and test results in the SDD report. A debugger/tooling failure must be diagnosed with `superpowers:systematic-debugging` plus `idea-cli`, IDE Index MCP, and JetBrains Debugger MCP; do not infer a miss from logs alone.

### Step 4: Commit only the lazy change

Inspect the staged diff. It must contain only the exact production/documentation declaration and any focused package-internal regression coverage justified in Step 1:

```bash
git add revoman/src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt \
  docs/modules/ROOT/examples/main/com/salesforce/revoman/internal/postman/PostmanSDK.kt \
  revoman/src/test/kotlin/com/salesforce/revoman/internal/postman/
git commit -m "perf: defer legacy JavaScript evaluator"
```

If no test file changed, stage only the two `PostmanSDK.kt` files. Record the Task 2 commit SHA as the candidate revision.

### Step 5: Capture an identical candidate measurement

Close IntelliJ and repeat the Task 1 measurement preparation exactly. Reuse the recorded CPU affinity, JDK, Gradle, benchmark selector, final profile, `--no-daemon`, and `--max-workers=1`. Capture sanitized candidate metadata to `artifacts/measurements/candidate-environment.json` and raw CSV to `artifacts/measurements/candidate.csv`.

Do not change governor, affinity, dependency versions, JVM flags, or benchmark configuration. Run `taskset --cpu-list "$CPU_AFFINITY" ./gradlew :benchmarks:mainFinalBenchmark --no-daemon --max-workers=1`. If the candidate confidence interval overlaps the baseline interval, discard both CSVs and rerun the complete baseline/candidate pair from their exact commits with unchanged settings. Use non-destructive detached Git worktrees only inside an ignored temporary measurement area; never reset or detach the primary worktree and never mix individual forks from different pairs.

Expected: baseline and candidate raw CSVs form one complete, identically configured pair, and the primary worktree is back at the Task 2 commit with no tracked changes.

---

## Task 3: Validate and commit benchmark evidence

**Outcome:** One self-contained, sanitized, reporter-validated evidence directory is atomically published and committed; no failed or partial run is retained.

**Files:**

- Create: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/manifest.json`
- Create: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/environment/baseline.json`
- Create: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/environment/candidate.json`
- Create: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/raw/baseline.csv`
- Create: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/raw/candidate.csv`
- Generate: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/comparison.csv`
- Generate: `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/report.md`

Use a UTC run identifier formatted `YYYYMMDDTHHMMSSZ` from the accepted complete pair. The directory is study data only; reporting code must remain unchanged.

### Step 1: Stage the complete run outside tracked evidence

Create the manifest and copy the preserved raw CSVs and sanitized environment JSON files into a sibling temporary directory under `benchmark-results/postman-sdk-js-evaluator/`. The manifest must include:

- schema version
- study ID and run ID
- exact baseline and candidate commit SHAs
- benchmark selector and normalized expected key set
- exact baseline and candidate commands
- Kotlin, kotlinx-benchmark, JMH-default, DataFrame, Gradle, JDK, and OS/kernel versions
- forks, warmups, measurements, iteration time, mode, unit, and 99.9-percent confidence level
- relative paths to both raw CSVs and environment files
- start/end timestamps for the accepted pair
- the CPU affinity and a statement that both variants used it

Validate manually that neither manifest nor environment JSON includes hostname, username, environment variables, home paths, tokens, passwords, cookies, or credentials.

### Step 2: Exercise reporter exit contracts against retained scratch copies

Before processing the accepted manifest, rerun the reporter tests and explicit CLI smoke cases:

```bash
./gradlew :benchmark-reporting:test --no-daemon --max-workers=1
./gradlew :benchmark-reporting:run --args="compare --manifest /absolute/path/to/passing-manifest.json" --no-daemon --max-workers=1
./gradlew :benchmark-reporting:run --args="compare --manifest /absolute/path/to/overlap-manifest.json" --no-daemon --max-workers=1
./gradlew :benchmark-reporting:run --args="compare --manifest /absolute/path/to/invalid-manifest.json" --no-daemon --max-workers=1
```

Expected exit codes are `0`, `1`, and `2`. The last two leave prior outputs unchanged. These paths point to SDD scratch fixtures, not accepted evidence.

### Step 3: Atomically publish the accepted comparison

Run the reporter once against the staged accepted manifest:

```bash
./gradlew :benchmark-reporting:run --args="compare --manifest /absolute/path/to/staged/manifest.json" --no-daemon --max-workers=1
```

Expected: exit `0`; every comparison row passes the strict non-overlap rule; `comparison.csv` is sorted by the complete normalized key; `report.md` is generated from the same typed DataFrame values; and no staging/backup directory remains.

Move the complete validated run directory into its final `benchmark-results/postman-sdk-js-evaluator/$RUN_ID/` name with one atomic rename. If validation returns `1` or `2`, do not create a final run directory and do not commit evidence.

### Step 4: Verify evidence and the whole branch

Run:

```bash
./gradlew build --no-daemon --max-workers=1
./gradlew :revoman:test :revoman:integrationTest :benchmark-reporting:test --no-daemon --max-workers=1
./gradlew spotlessCheck detekt qodanaScan --no-daemon --max-workers=1
./gradlew :revoman:publishToMavenLocal --no-daemon --max-workers=1
./gradlew build --configuration-cache --no-daemon --max-workers=1
./gradlew build --configuration-cache --no-daemon --max-workers=1
git diff --check
```

Expected: every command passes, the second cache run reuses configuration, no isolation/configuration-cache diagnostics occur, and `build` does not execute JMH. Resynchronize IntelliJ one final time and verify IDE Index is out of dumb mode.

Inspect the evidence with:

```bash
find "benchmark-results/postman-sdk-js-evaluator/$RUN_ID" -maxdepth 3 -type f -print | sort
git grep -nEi 'hostname|user(name)?|password|token|secret|cookie|/home/' -- "benchmark-results/postman-sdk-js-evaluator/$RUN_ID"
```

Expected: exactly the seven specified files are present and the sensitive-data scan has no match except explicit schema field names that contain no values. Manually reconcile raw scores/errors, calculated bounds, pass flags, revisions, commands, and environment affinity across all seven files.

### Step 5: Commit only validated evidence

```bash
git add "benchmark-results/postman-sdk-js-evaluator/$RUN_ID"
git commit -m "perf: record lazy evaluator benchmark evidence"
```

Expected: Task 3 contains only the validated evidence directory. The branch has one documentation commit followed by the three required implementation/evidence commits, and the worktree is clean.

## Final review gate

After all three tasks pass their per-task spec reviews, run one fresh whole-branch review against the design and this plan. The reviewer must inspect the complete diff from the documentation commit's parent through Task 3, all retained verification evidence, artifact compatibility report, IntelliJ debugger proof, measurement pairing, and sanitization. Permit one consolidated fix wave if the final reviewer finds issues. Fixes to the current Task 3 evidence commit are amended into Task 3. A fix to Task 1 or Task 2 must be made as a fixup commit, autosquashed into its target, followed by a complete baseline/candidate remeasurement from the rewritten Task 1/Task 2 SHAs, evidence regeneration, and amendment of Task 3. The final history must still contain exactly the documentation commit plus three implementation/evidence commits. Rerun affected verification, and do not claim completion until `superpowers:verification-before-completion` confirms fresh command output.

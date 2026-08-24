# ReVoman benchmark platform and lazy evaluator proof

## Goal

Convert ReVoman into a Gradle 9.7.1 multi-project build and prove that lazily creating the
legacy `JSEvaluator` improves script-free `revUp` performance. The benchmark infrastructure must
support future studies across the library rather than encode assumptions about this first study.

## Project structure

- `:revoman` contains the published library, unit tests, integration tests, and JavaScript
  resources.
- `:benchmarks` contains reusable kotlinx-benchmark scenarios and depends on `:revoman`.
- `:benchmark-reporting` contains the DataFrame command-line reporter and has no ReVoman project
  dependency.
- The source-free root configures and aggregates the build without cross-project mutation.

Preserve ReVoman's Maven coordinates, public APIs, JAR contents, POM, release process, and runtime
defaults.

## Gradle architecture

- Replace `buildSrc` with a `build-logic` included build containing focused convention plugins for
  Kotlin and JDK 25, library publishing, benchmark execution, and benchmark reporting.
- Enable type-safe project accessors and use `projects.revoman`.
- Centralize repositories with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`. Do not use `allprojects`
  or `subprojects` blocks.
- Enable Gradle's incubating Isolated Projects through `org.gradle.isolated-projects=true`. Fix all
  diagnostics and never ignore isolation problems.
- Retain Configuration Cache and change its problem policy from `warn` to `fail` once the existing
  tasks are compatible.
- Move version and publication coordinates from compiled build logic into Gradle properties.
- Update CI report paths, Qodana generated-source tasks, documentation, release scripts, local JAR
  paths, and Maven publication routing.
- `./gradlew build` compiles benchmark code and tests reporting code but never runs JMH.

The existing Nexus publishing plugin requires the root project and internally traverses every
project, so it is incompatible with Isolated Projects. Keep it out of normal build and IDE-sync
configuration. Apply it at the root only when the release script supplies an explicit release-mode
property, and have that legacy release invocation explicitly disable Isolated Projects. This is a
narrow compatibility boundary for the preserved release process, not an ignored isolation problem;
all development, CI, local-publication, benchmark, and verification invocations remain isolated.
The release-only branch sets the root group and version from the same Gradle properties used by
`:revoman` before applying Nexus, and a dry run must prove that the legacy root task graph includes
the `:revoman` publication.

Use kotlinx-benchmark `0.4.18` with its supported JMH default and JDK 25. Use DataFrame
`1.0.0-rc01` with compiler plugin `2.4.20-RC` only in `:benchmark-reporting`.

## Benchmark runner

The first benchmark executes a complete script-free `revUp` through the public API. It owns its
collection fixture and uses `Kick.httpClient` to return a deterministic in-process HTTP 200
response. Invariant fixture loading happens outside the measured method; normal per-run state is
constructed inside it and the result is consumed.

The runner provides two profiles:

| Profile | Forks | Warmups | Measurements | Iteration time |
| --- | ---: | ---: | ---: | ---: |
| Smoke | 1 | 2 | 2 | 250 ms |
| Final | 5 | 10 | 20 | 1 s |

Both use average-time mode, millisecond output, and CSV reports.

## Evidence and reporting

Store accepted evidence under `benchmark-results/<study-id>/<run-id>/`:

```text
manifest.json
environment/baseline.json
environment/candidate.json
raw/baseline.csv
raw/candidate.csv
comparison.csv
report.md
```

The manifest records schema version, study and run identifiers, revisions, benchmark selector,
commands, JMH settings, confidence level, environment paths, and timestamps. Environment metadata
records kernel, WSL status, CPU model and topology, memory, JVM, Gradle, CPU affinity, governor,
load, and revision. It must not record hostnames, usernames, environment variables, or credentials.

The reporting application uses typed DataFrame schemas for normalized JMH rows and comparisons.
It joins baseline and candidate rows by benchmark name, sorted parameter values, mode, threads,
and unit. It rejects missing or duplicate rows, different key sets or units, malformed or non-finite
numbers, negative errors, and missing 99.9 percent confidence data.

For every selected row, success requires:

```text
candidate score + candidate error < baseline score - baseline error
```

The reporter exits `0` only when every row passes, `1` for a valid comparison that fails the rule,
and `2` for invalid input. It writes to a temporary staging directory and publishes results
atomically only after validation. Failed or partial results are not committed.

Do not add notebooks, servers, study-specific reporting branches, or automated process-killing
tasks.

## Production change

Change `PostmanSDK` to:

```kotlin
// The production script path uses PmSandbox. Defer this legacy evaluator until a caller invokes
// evaluateJS/jsonStrToObj directly, so script-free revUp runs do not create an unused Context.
private val jsEvaluator: JSEvaluator by lazy { JSEvaluator(nodeModulesPath) }
```

Do not add a factory, overload, or public API to test laziness. Use the IntelliJ debugger to show
that the eager baseline constructs `JSEvaluator` during script-free `revUp`, while the candidate
does not. Confirm that `evaluateJS` and `jsonStrToObj` initialize it once and continue working.

## Verification

- Run all existing unit and integration tests, reporting tests, Spotless, Detekt, Qodana, local
  Maven publication, Configuration Cache reuse, Isolated Projects diagnostics, and an IntelliJ
  Gradle resync.
- Verify without publishing that the explicit legacy release mode exposes the existing Nexus task
  route while normal isolated builds do not apply the incompatible plugin.
- Compare pre-migration and migrated library artifacts by entry list, manifest, bundled immutable
  classes, sources, Gradle metadata, and generated POM. Compile and run a temporary consumer against
  the locally published artifact.
- Test reporting success, confidence overlap, mismatched and parameterized rows, malformed CSV,
  non-finite values, duplicate keys, and atomic output.
- Before final measurement, close IntelliJ, stop Gradle and Kotlin daemons, inspect user processes,
  and terminate only exact interfering process IDs. Pin both variants to the lowest logical CPU
  from each physical-core sibling group.
- Run final variants with `--no-daemon --max-workers=1`. If intervals overlap, rerun the complete
  pair with unchanged settings and never combine results from different configurations.

## Delivery

After this design record, use three implementation commits:

1. A behavior-neutral multi-project migration with the benchmark platform, reporter, tests, and
   path updates. Run the eager baseline from this revision.
2. The lazy evaluator change and focused regression coverage. Run the candidate from this revision.
3. The validated raw evidence, metadata, comparison CSV, and generated Markdown report.

Work on `codex/benchmark-platform-lazy-js-evaluator` in the existing worktree. Do not publish,
push, or run the release script.

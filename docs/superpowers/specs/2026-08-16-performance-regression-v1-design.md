# ReVoman Performance Regression V1: Design

**Date:** 2026-08-16
**Status:** Implemented and locally verified; canonical GitHub A/A pending
**Runtime baseline:** `master` after the reviewed runtime-performance commits were merged locally

## Goal

Build a small, trustworthy performance-regression loop around ReVoman's existing root
`src/jmh` source set. It must measure the runtime-lifecycle changes on the target branch,
catch material future regressions on free GitHub-hosted runners, and support unlimited local
experimentation in one reproducible Docker image.

The infrastructure is a measuring instrument, not the end goal. Once V1 is trusted, a bounded
whole-codebase audit will add benchmarks only for demonstrated hotspots or important user-visible
performance contracts.

## Review facts that V1 must address

- The reviewed target branch broke its existing performance harness:
  `./gradlew compileJmhKotlin` fails in `RegexVarBenchmark.kt` because `RegexReplacer` and
  `PostmanSDK` became interfaces with factory functions while the benchmark still invokes their
  old constructors and signatures. That compatibility repair is part of the merged runtime
  baseline, so V1 starts from a compiling suite.
- The existing `SandboxBenchmark` creates one sandbox per trial and measures repeated steady-state
  evaluation. It cannot prove the branch's lazy per-kick sandbox behavior or the script-free path's
  removal of eager Graal context creation.
- JMH 1.37 and the Gradle JMH plugin 0.7.3 are already pinned. A new module or benchmark driver is
  unnecessary.
- A JMH fat jar that contains multi-release dependencies must declare `Multi-Release: true`.
  Benchmark errors must fail the task instead of producing apparently successful partial output.
- Application INFO logging previously produced roughly 216 MB of output during a benchmark run;
  those measurements are contaminated and are not a baseline.

The runtime review also found non-performance merge-readiness issues. They were resolved and
verified before forming V1's baseline; V1 neither hides nor waives unrelated failures.

## Decisions

### Existing root JMH remains the only benchmark harness

Keep all benchmarks under `src/jmh`. The merged baseline already restores the existing suite and
keeps its benchmark identities. V1 expands `RuntimeLifecycleBenchmark` into the two canonical
workloads below and makes the smallest `SandboxBenchmark` change needed to suppress contaminating
application logs and validate every measured result.

There is no `benchmark-driver` module, custom result protocol, result schema, adapter layer,
process supervisor, JFR subsystem, or checked-in result archive.

### Canonical benchmark workloads

#### `RuntimeLifecycleBenchmark`: regression-gated

The class uses only stable public ReVoman APIs so the exact same source compiles against both the
baseline and candidate libraries. Trial setup starts one deterministic loopback HTTP server and
prepares two one-step collection fixtures. Each measured invocation calls exactly one
`ReVoman.revUp(kick)`.

- `scriptFreeOneStep`: one successful GET and no collection scripts. This captures eager runtime
  and sandbox costs that should disappear from the target branch.
- `scriptedOneStep`: the same request with one minimal pre-request script and one minimal test
  script. This includes sandbox creation, boot, two script phases, reuse within the kick, and close.

A trial preflight verifies a successful report and expected environment mutations before warmup.
Each measured invocation repeats a small fail-closed result check so an execution failure cannot be
reported as a performance improvement. That symmetric validation overhead is part of both baseline
and candidate measurements. Existing structural tests continue to prove zero sandbox creations for
script-free execution, one sandbox reused across script phases, one close, and isolation between
kicks.

The workflow copies both selected benchmark sources and the lifecycle fixtures from the candidate
into the disposable baseline checkout, then verifies the copies are byte-identical. This is the
minimal cross-version seam: both targets run the same workloads without introducing a separate
driver module.

#### `SandboxBenchmark`: regression-gated guard

Keep the existing steady-state sandbox benchmark. It protects repeated guest execution and shared
Engine/Source behavior, but its result must never be presented as proof of lazy lifecycle behavior.
The workflow compares it only while its selected source is byte-identical in baseline and
candidate; otherwise the comparison is `INCOMPARABLE` and fails closed.

### Measurements

All V1 measurements use JMH `AverageTime` and the built-in `gc` profiler:

- primary score: latency in `us/op`;
- secondary score: `gc.alloc.rate.norm` in `B/op`.

The canonical GitHub run uses two forks, five one-second warmup iterations, and eight one-second
measurement iterations. Smoke mode uses one fork, one 200-millisecond warmup iteration, and one
200-millisecond measurement iteration. JMH runs with fail-on-error enabled and writes raw JSON
plus a human log. The existing `-Pjmh.includes` interface remains; V1 also accepts
`-Pjmh.resultsFile`, `-Pjmh.humanOutputFile`, and `-Pjmh.profilers` so diagnostic Gradle runs can
write outside `build/` and enable `gc` without editing the build.

Benchmark setup owns server, fixture, and resource creation. Trial teardown closes everything it
owns. Network access is loopback-only; no Salesforce org or public API is part of a benchmark.

Benchmark trial setup switches Log4j's root level to `OFF` before creating workload resources, and
the fork disables the banner. The raw human log remains an artifact so unexpected output is visible.

### Minimal comparator

A small Python-standard-library comparator consumes baseline and candidate JMH JSON. It joins
records by benchmark name, parameters, and mode, rejects duplicate records, and validates matching
units plus finite, positive scores. It compares the primary time score and `gc.alloc.rate.norm`;
higher is worse for both V1 metrics. Decimal comparison makes the exact 20-percent boundary pass
without a floating-point tolerance rule.

The initial material-regression limit is **20 percent** for both selected metrics:

```text
candidate > baseline * 1.20  => REGRESSION
```

Any single selected result over the limit fails the comparison. Any selected
benchmark/parameter mismatch, mode/unit mismatch, missing allocation metric, empty
JSON, non-finite value, or benchmark error is `INCOMPARABLE` and exits non-zero. Improvements and
changes within the limit pass. The comparator emits a concise Markdown table, a small JSON summary,
and a process exit status. It does not define a versioned schema or retain a historical denominator.

The 20-percent limit is accepted only after an A/A run of the same ref on one GitHub-hosted runner
passes. If A/A breaches it, V1 is not made more permissive automatically; first investigate the
workload, run order, and contamination. Threshold changes require reviewed evidence.

## GitHub Actions: canonical verdict

Add one compact `.github/workflows/benchmark.yml` with:

- `pull_request` against `master` plus `workflow_dispatch` for explicit refs and A/A experiments;
- read-only repository permissions, one `ubuntu-latest` job, and a 30-minute timeout;
- sibling `baseline` and `candidate` checkouts on the same runner;
- Eclipse Temurin 21.0.11+10 for both targets, with the resolved runtime version recorded;
- one Gradle setup/cache action;
- benchmark compilation, shared-workload verification, baseline run, candidate run, comparison;
- unconditional upload of raw JMH JSON, human logs, comparison JSON/Markdown, commit SHAs, and
  `java -version`; and
- one `performance-results` artifact retained for seven days, with no committed benchmark-result
  history.

For pull requests the base and head SHAs are supplied by the event. Manual runs accept baseline and
candidate refs; supplying the same ref to both performs the canonical A/A experiment.

The workflow invokes each checkout's existing Gradle JMH task with identical settings from one
disposable init file. The same file applies `Multi-Release: true` to both JMH jars, so the historical
baseline packaging defect does not prevent measurement. The candidate build permanently retains
that manifest fix for normal local use.

After V1 lands, comparisons use the comparator from the protected baseline checkout. Candidate
fallback exists only to bootstrap the workflow in the V1 change itself or to diagnose an older
manual baseline that predates the comparator. Repository review protection remains required for
changes to the workflow and gated benchmark/workload paths; like any candidate-supplied test, the
workflow cannot prove that its own workload was not deliberately weakened.

GitHub-hosted runner timing remains noisy. Running both targets in one job controls the software
stack and reduces host variation, but does not make the runner dedicated. The paired run plus the
material threshold is the canonical regression verdict; raw results remain available for human
interpretation.

## Docker: diagnostic reproducibility only

Add exactly one `Dockerfile.perf` and one user-facing wrapper command,
`./scripts/perf-docker`. The wrapper builds or reuses the image and executes any supplied command;
it does not become a benchmark campaign framework.

The local image tag is `revoman-perf:jdk21`.

The image contains:

- Ubuntu 24.04, matching the current `ubuntu-latest` runner image;
- Eclipse Temurin JDK `21.0.11+10`, the same vendor and runtime build as performance CI; and
- only the required command-line tools: CA certificates, curl, Git, and Python 3, in addition to
  the base image's shell/core tools.

The wrapper:

- bind-mounts the current checkout at `/workspace`;
- bind-mounts the caller's result directory, defaulting to
  `$PWD/build/perf-results`, at `/results`;
- mounts the persistent named volume `revoman-perf-gradle` at `/gradle-cache` and sets
  `GRADLE_USER_HOME` to it;
- uses an image-created writable `/gradle-cache` mount point so a fresh named volume works with the
  caller's non-root UID/GID;
- runs with the caller's UID/GID so generated files remain writable;
- builds from the Dockerfile alone because the image copies no repository content, avoiding a large
  generated-artifact build context;
- forwards the arbitrary command and its exit status; and
- never requests privileged mode or mounts a Docker socket.

The preferred host is `gopalaaksh-wsl3`. Docker gives repeatable userspace, JDK, and CLI versions;
it does not reproduce GitHub's CPU, scheduler, kernel/PMU exposure, or contention. Docker results
are therefore diagnostic. GitHub Actions remains authoritative. Native runs on
`gopalaaksh-wsl3` may be used for optional `perf` or async-profiler investigation, outside the
regression gate and without requiring privileged Docker.

When GitHub moves `ubuntu-latest` to a newer Ubuntu release, update `Dockerfile.perf` deliberately
after the workflow image change is available. Do not pin CI to an older Ubuntu merely because the
temporary host cannot upgrade.

## JDK decision

Stay on JDK 21 for V1. JDK 25 is technically viable, but changing the JVM while measuring a runtime
refactor would mix library and JVM effects without adding a required capability. A future JDK 25
migration should be evaluated separately against the same workloads and adopted only for a measured
benefit or project-wide maintenance reason.

## Whole-codebase measurement phase after V1

After compilation, smoke, JSON, comparator tests, Docker A/A, and GitHub A/A establish that the
instrument is trustworthy, perform a bounded hotspot audit:

1. Profile representative public workflows on native `gopalaaksh-wsl3` to find actual CPU and
   allocation concentration.
2. Rank findings by user-visible cost, scaling risk, and regression likelihood.
3. Add a benchmark only for a top hotspot, a claimed optimization, or an important architectural
   performance contract.

Prioritize public, user-visible workloads in this order:

1. Scripted `revUp(Kick)` scaling across environment size, script count, and mutation density.
2. Multi-step `Kick`, `List<Kick>`, and `Runbook` execution across run length and orchestration size.
3. Ledger cold-start versus warm-reuse behavior.
4. Startup, input parsing, and typed-payload costs through short public runs; isolate an internal
   component only when profiling shows that it dominates.
5. Configured logging overhead using no-op and in-memory sinks; filesystem sinks remain diagnostic.

Public-interface benchmarks are candidates for the canonical CI gate. Internal microbenchmarks are
diagnostic and require profiling evidence, except for the existing sandbox guard. Use deterministic
tests for polling boundedness and resource-count invariants; add polling JMH coverage only if a
profile elevates it. Do not create one benchmark per class or method.

## Expected file scope

The implementation should remain close to this file set:

- modify `build.gradle.kts` for JMH compilation participation, fail-closed behavior, selectable
  JSON/result paths, GC profiling, and `Multi-Release: true`;
- expand `RuntimeLifecycleBenchmark.kt` and add small lifecycle fixtures under `src/jmh`;
- update `SandboxBenchmark.kt` to suppress application logging and validate measured results;
- add `scripts/compare-jmh.py` plus focused comparator fixtures/tests;
- add `Dockerfile.perf` and `scripts/perf-docker`;
- add `.github/workflows/benchmark.yml`; and
- update `DEVELOPMENT.md` with exact native, Docker, comparison, and A/A commands.

No `settings.gradle.kts` change, new Gradle module, Compose file, `act`, nested Docker, self-hosted
runner, custom GitHub runner image, privileged mode, host-governor automation, benchmark-result
archive, or code copied from `benchmark-driver` is in scope.

## Acceptance criteria

V1 is ready when all of the following are true:

1. `./gradlew compileJmhKotlin` succeeds in the dedicated workflow preflight.
2. The JMH smoke run fails on benchmark exceptions and produces non-empty JSON and a human log.
3. Comparator fixture tests cover pass, improvement, exact 20-percent boundary, regression,
   missing benchmark, mismatched unit, missing allocation, malformed/empty JSON, and non-finite
   values.
4. Runtime lifecycle preflight and existing structural tests protect behavior outside timed code.
5. The Docker wrapper performs compilation, smoke, JSON generation, comparator validation, and a
   diagnostic A/A run on a Docker host with bind-mounted results and a reused Gradle cache. Prefer
   `gopalaaksh-wsl3`; an equivalent local fallback may validate the software path when remote Docker
   access is externally blocked, provided the gap is reported.
6. A manual GitHub A/A run passes before the regression check is treated as authoritative.
7. A deliberately worsened comparator fixture and a deliberately failing smoke benchmark both
   produce non-zero exits.
8. Unit tests, integration tests, formatting, and the project-required static-analysis gate are
   reported honestly; V1 does not claim that unrelated target-branch findings are fixed.

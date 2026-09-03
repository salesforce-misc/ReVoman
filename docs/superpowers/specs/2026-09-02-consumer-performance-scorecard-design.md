# ReVoman consumer performance scorecard

## Goal

Publish reproducible baseline performance metrics for the library operations that matter most to a
ReVoman consumer. The scorecard measures warmed, steady-state engine work on this Linux machine.
Every HTTP response comes from a deterministic in-process `Kick.httpClient(HttpHandler)`.

This study records the current release snapshot. It does not compare an optimization candidate,
change production behavior, or claim that a previously observed hotspot is still dominant.

## Consumer journeys

Add a dedicated `ConsumerJourneyBenchmark` suite with eight stable benchmark methods. Each method
maps to one documentation row rather than using parameters that readers must decode.

| Row | Measured consumer operation | Fixture and result contract |
| --- | --- | --- |
| Postman V2 collection | `ReVoman.revUp(kick)` for a script-free ten-step Postman Collection V2 document | Ten successful reports and exactly ten handler calls |
| Script-bearing Postman V2 collection | `ReVoman.revUp(kick)` for a ten-step Postman Collection V2 document with a representative Postman script | Ten successful reports, ten handler calls, and the expected script effect |
| ReVoman V3 collection | `ReVoman.revUp(kick)` for the equivalent script-free ten-step V3 graph | Ten successful reports and exactly ten handler calls |
| Large V3 collection | `ReVoman.revUp(kick)` for a script-free 100-step V3 graph | 100 successful reports and exactly 100 handler calls |
| Script-bearing V3 collection | `ReVoman.revUp(kick)` for a ten-step V3 graph with representative Postman scripts | Ten successful reports, ten handler calls, and the expected script effect |
| Three-kick workflow | `ReVoman.revUp(kicks)` for three ten-step V3 collections | Thirty successful reports, exact handler counts, and real environment values handed from each kick to the next |
| Contracted runbook | `ReVoman.revUp(runbook)` for the equivalent three-step `Runbook` | Three successful child rundowns, satisfied `consumes` and `produces` contracts, and the same environment handoffs |
| Verbose result rendering | `rundown.toJson(Verbosity.VERBOSE)` | A prepared successful 100-step `Rundown` renders valid verbose JSON with all step reports |

The script-free V2 and V3 rows stay separate even though their logical requests match. They exercise
different consumer input formats and loader paths. Script-bearing V2 and V3 also stay separate so
the scorecard covers each supported input format's script parsing and mapping before the shared
sandbox execution path.

## Measurement boundary

The first seven rows time one complete public `revUp` call. Trial setup constructs and validates
immutable fixture data and consumer configuration. It does not load a collection, create a
`PostmanSDK`, execute a request, or retain a prior `Rundown`. Each measured invocation starts with
the prepared configuration and consumes the returned result through the JMH blackhole.

The rendering row intentionally has a different boundary. Trial setup executes the 100-step V3
journey once and validates its `Rundown`. The measured method times only
`toJson(Verbosity.VERBOSE)` and consumes the returned string.

Fixtures live in `:benchmarks`, use benchmark-only host names, and never open a socket. The handler
returns deterministic response bodies selected by request path. Correctness tests exercise handler
counts, step counts, script effects, environment handoffs, contracts, and serialized output outside
JMH. JMH setup repeats the inexpensive invariant checks needed to reject a broken measurement.

## Benchmark protocol

Add a `consumerScorecard` kotlinx-benchmark configuration with these fixed settings:

| Setting | Value |
| --- | ---: |
| Mode | Average time |
| Unit | ms/op |
| Threads | 1 |
| Forks | 5 |
| Warmups per fork | 10 |
| Measurements per fork | 20 |
| Iteration time | 1 second |
| Confidence interval | 99.9 percent |
| Report format | CSV |

The run uses one logical CPU from each physical-core sibling group and `--max-workers=1`. The
launcher records the selected affinity in the manifest. It uses the same isolation policy as the
accepted benchmark studies: close IntelliJ, stop Gradle and Kotlin daemons, inspect user processes,
and terminate only exact interfering process IDs. The repository must be clean apart from known,
recorded user-owned files before measurement.

This is a release snapshot run, initiated manually when documentation metrics should change. CI
compiles the suite and tests its supporting code but does not execute the final JMH profile.

## JDK 25 execution

The Gradle client, single-use daemon, Java toolchain, and JMH forks must all use Java feature version
25. The scorecard accepts neither Java 21 nor a mixture of Java versions.

Use Gradle daemon JVM criteria and the existing Java toolchain configuration to select Java 25
without hardcoding a machine-specific JDK path. The caller must launch the wrapper from a Java 25
environment through `JAVA_HOME` or `PATH`. The scorecard fails before measurement unless its
environment, Gradle runtime, selected launcher metadata, and an assertion inside each JMH fork all
report feature version 25.

## Kotlin orchestration

Do not add `scripts/run-consumer-scorecard.sh` or require a machine-installed `kotlin` command.
Java cannot execute a standalone `.kts` file, and this machine has no independent Kotlin launcher.

Put the orchestration in a compiled Kotlin entry point in `:benchmark-reporting`. Expose it through
a Gradle `JavaExec` task named `:benchmark-reporting:runConsumerScorecard`. Gradle supplies its Java
25 launcher and the built JMH artifact through lazy task inputs. The Kotlin runner owns:

- preflight checks and environment capture;
- the UTC run identifier and staging directory;
- launching the JMH process with the requested CPU affinity;
- collecting the raw CSV and verifying the child exit status;
- invoking absolute-scorecard validation and rendering;
- atomically publishing a complete evidence directory.

The runner launches the prepared JMH artifact directly with the Gradle-provided Java executable. It
must not recursively invoke another Gradle build. A single wrapper command therefore bootstraps the
compiled runner while Kotlin owns the workflow.

## Reporting modes

Keep the existing comparison command and its strict acceptance rule unchanged:

```text
candidate score + candidate error < baseline score - baseline error
```

Add a separate `scorecard` command for one absolute JMH result. It expects exactly the eight named
rows and rejects missing rows, duplicates, unexpected rows, wrong mode, wrong thread count, wrong
unit, malformed or non-finite values, negative errors, and missing 99.9-percent confidence data.
It also verifies the fixed fork, warmup, measurement, iteration-time, revision, affinity, and JDK
metadata from the manifest.

An absolute scorecard has no improvement verdict. Validation means that all eight measurements are
complete and comparable with future snapshots made under the same protocol. The generated table
shows journey, workload, score, 99.9-percent error, and unit in a fixed consumer-facing order.

## Evidence and documentation

Publish an accepted run only under:

```text
benchmark-results/consumer-performance-scorecard/<run-id>/
  manifest.json
  environment/run.json
  raw/results.csv
  scorecard.csv
  report.md
  performance-scorecard.adoc
```

The manifest records schema version, study and run identifiers, library version, exact revision,
benchmark selector and expected rows, command, profile settings, dependency fingerprint,
measurement timestamps, CPU affinity, and relative artifact paths. `environment/run.json` records
the kernel, CPU topology and model, memory, load, governor, JDK identities, Gradle version, and
process-hygiene observations. It must not record usernames, hostnames, arbitrary environment
variables, or credentials.

The runner writes everything to a sibling staging directory. It publishes the run directory only
after JMH and every validation check succeed. Failure leaves no accepted partial run. If runtime
checks, profiling, or final validation reveal bad data, reject the whole run, diagnose the cause,
and rerun the unchanged protocol.

Create `docs/modules/ROOT/partials/performance-scorecard.adoc` as a byte-for-byte copy of the
accepted run's `performance-scorecard.adoc`. `performance.adoc` includes that partial and explains
that these figures measure the in-process ReVoman engine on one named hardware and software
snapshot. They exclude DNS, sockets, TLS, remote service latency, cold JVM startup, and application
work outside the measured public call. A test fails if the documentation partial differs from the
manifest-selected evidence file.

## Runtime validation and profiling

Before the final measurement, run each journey as an isolated smoke case through IntelliJ. Use IDE
Index navigation to identify the relevant definitions and call sites. Use the JetBrains debugger to
verify:

- script-free and script-bearing V2 and V3 inputs enter their intended loader paths;
- `PmSandbox` performs script work only for the script-bearing and handoff fixtures that require it;
- handler invocation counts match executed HTTP steps;
- the multi-kick and runbook cases carry the intended environment values;
- runbook contracts execute and pass;
- verbose serialization begins with a fully prepared 100-step `Rundown`.

Capture separate JFR or async-profiler CPU, allocation, and lock profiles for every smoke journey.
Profiles explain what each score includes; they are not timing inputs and do not justify a
production change in this study. Record profiler-backed facts separately from optimization
hypotheses. Any optimization becomes a later, separately approved baseline-and-candidate study.

Implementation and runtime investigation must occur in a task where the `idea`, `intellij-index`,
and JetBrains debugger MCP tools are available. Starting IntelliJ during an already-running task
does not add missing MCP bindings.

## Tests and verification

Use test-driven development for each component.

- Benchmark workload tests pin all eight fixture contracts, including V2/V3 equivalence, exact
  handler counts, script effects, all-type environment handoffs, runbook contracts, and verbose
  JSON content.
- Reporting tests cover a valid eight-row snapshot and every structural rejection above. Existing
  comparison-mode tests prove its CLI, exit codes, strict inequality, and atomic publication remain
  unchanged.
- Gradle functional tests cover Java 25 preflight failures, task inputs, JMH artifact wiring, and a
  lightweight fake child process so orchestration is testable without running the final profile.
- Documentation tests verify the evidence-to-partial byte identity and the Antora include.
- Normal verification runs benchmark compilation, `:benchmarks:test`,
  `:benchmark-reporting:test`, all existing unit and integration tests, Spotless, Detekt, and
  Qodana. It does not run the final JMH profile automatically.

## Delivery boundaries

This study may add benchmark fixtures, reporting/orchestration code, Gradle configuration, accepted
evidence, and documentation. It must not modify `:revoman` production behavior or public APIs. It
must not publish artifacts, push commits, run a remote release, use an external HTTP server, add a
notebook, weaken the existing comparison rule, or present an incomplete subset of rows.

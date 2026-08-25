# ReVoman collection-scale progress bookkeeping design

## Goal

Remove the confirmed quadratic collection-progress bookkeeping cost from `revUp` without changing
observable execution behavior. Measure the change with a reusable collection-scale benchmark and
the repository's existing strict DataFrame reporter.

## Investigation evidence

The study uses benchmark-owned, in-memory Postman V2 collections and a deterministic
`Kick.httpClient` handler that returns HTTP 200. Setup generates and validates fixtures. Each
measured invocation constructs normal `Kick` and `revUp` state.

The exploratory matrix covers:

| Scenario | Parameters | Fixed workload |
| --- | --- | --- |
| Collection scale | 1, 10, 100, 500 steps | Empty environment, no placeholders, no scripts |
| Environment scale | 0, 10, 100, 1,000 entries | 10 steps, no placeholders, no scripts |
| Placeholder density | 1 and 10 placeholders per request | 10 steps, 100 environment entries |
| Script mode | 1 and 10 steps, script-free and script-bearing | Empty environment |
| Repeated V3 path | 1 and 10 complete `revUp` calls | Existing benchmark-owned `single-ok` fixture |

The collection-scale smoke run produced 0.134, 0.314, 2.066, and 13.131 ms/op for 1, 10, 100,
and 500 steps. Smoke timings select workloads only. They are not accepted timing evidence.

GC profiling measured 41,376, 148,298, 1,446,027, and 11,508,825 allocated bytes/op for the same
step counts. Increasing the workload from 100 to 500 steps increased allocation by 7.96 times for
five times as many steps.

At 500 steps, JFR recorded 941 worker CPU samples. Stacks containing `PostmanSDK.syncProgress`
accounted for 342 samples, or 36.3 percent. Its sampled allocations were 7.94 GB of 19.14 GB, or
41.5 percent. The separate `stepReportsSoFar + preStepReport` seed allocated another 1.52 GB, or
7.9 percent. Together these sites accounted for 49.4 percent of sampled allocation. JFR recorded
no Java monitor-enter contention.

The strongest alternative was `replaceVariablesInEnv` for a 1,000-entry environment. Directly
attributable frames accounted for 12.8 percent of CPU samples and 31.6 percent of sampled
allocation. That path is deferred because its benefit depends on environment size and its
correctness constraints include dynamic generators, recursive references, scripts, and typed
values. Repeated V3 loading scaled approximately linearly and any cache would require explicit
ownership, concurrency, invalidation, and retention rules. Moshi contention was not confirmed.

## Approved change

Keep the final `executeStepsSerially` report accumulator as the existing mutable list. Add a
parallel `PersistentList<StepReport>` used only for mid-run progress:

1. Start the progress list empty beside the final report accumulator.
2. Pass it to `runStep` as the completed progress prefix.
3. Seed the current `Rundown` with `progressReports + preStepReport`, using the
   `kotlinx.collections.immutable.plus` extension so the result remains persistent.
4. Make `PostmanSDK.syncProgress` convert an ordinary input list once with `toPersistentList`, then
   replace the current last entry with `replacingAt(lastIndex, stepReport)` or append with `+` using
   the persistent `plus` extension.
5. After `runStep` returns, append its final report to both accumulators.
6. Build the final returned `Rundown` from the existing mutable accumulator.

`kotlinx-collections-immutable` is already an implementation dependency bundled in the ReVoman
artifact. The change adds no dependency, cache, public API, benchmark-only production seam, or
new mutable shared state.

## Behavioral invariants

- Final `Rundown.stepReports` has the same count, order, reports, loop iterations, ledger skips,
  request skips, failure precedence, and stop reason.
- Mid-run `pm.rundown.stepReports` contains completed reports followed by exactly one current
  report. The current report is replaced at the same request, response-script, and post-hook
  boundaries.
- A previously retained `Rundown` or progress-list snapshot never changes after later progress
  publications.
- Hooks, dynamic generators, halt predicates, polling, ledger capture, and control-flow directives
  observe the same state at the same lifecycle boundaries.
- Environment, collection-variable, global, script-isolation, custom-adapter, HTTP-handler,
  Apache fallback, TLS, proxy, and error behavior remain unchanged.
- Maven coordinates, published artifacts, public signatures, runtime defaults, and release
  behavior remain unchanged.

## Rejected alternatives

- Mutating a shared `ArrayList` entry in place could change retained `Rundown` snapshots.
- Skipping progress updates for script-free or hook-free runs could change lifecycle visibility.
- Optimizing environment replacement first has higher compatibility risk and a narrower workload.
- Caching parsed V3 collections requires invalidation and retention policies and does not fix the
  confirmed collection-scale cost.
- Sharing or caching sandbox contexts risks script isolation.
- Suppressing Detekt or Qodana findings, adding them to a baseline, or weakening the repository
  quality gates would hide defects discovered by the required verification rather than fix them.

## Verification-gate correction

The first fresh two-row pair passed the strict reporter but is rejected because repository
verification found two Detekt and three Qodana problems. Correct them without changing the workload
or production behavior:

1. Move the internal `PreparedCollection` declaration into `PreparedCollection.kt` and make it a
   regular class. Its `ByteArray` is transport state, not value-equality state, and no benchmark
   consumes generated `equals`, `hashCode`, `copy`, or component functions.
2. Replace the benchmark identifier padding literal with one named constant and use it for both
   step and environment names.
3. Replace the placeholder `0 until count` construction with a count-sized list construction that
   emits the exact same joined string, including the empty-count prefix.
4. Spell the immutable persistent-list reassignment as `progressReports += report`. With no
   `plusAssign` operation, Kotlin resolves this to the same imported persistent `plus` result and
   reassigns the local variable.

The corrected baseline and candidate must remain direct parent/child revisions with identical
benchmark sources, build logic, and documentation. The candidate delta remains the reviewed
production/test optimization, plus only the equivalent operator-assignment spelling. Before new
timing, prove all exploratory fixture setup validations, the focused progress tests, Detekt,
Spotless, and Qodana pass. Any timing from the verification-rejected revisions remains diagnostic
and cannot enter accepted evidence.

## Measurement contract

The accepted study ID is `collection-scale-progress-bookkeeping`. The exploratory benchmark keeps
the 1, 10, 100, and 500 step workloads. Two complete, unchanged baseline/candidate pairs showed
overlapping confidence intervals at 1 and 10 steps but strict wins at 100 and 500 steps. The 1 and
10 step rows are therefore retained as documented neutral exploratory evidence, not promoted into
the accepted comparison. Final evidence contains only the 100 and 500 step
`CollectionScaleRevUpBenchmark.revUpByStepCount` rows.

Both final revisions use average time in milliseconds, five forks, ten warmups per fork, twenty
measurements per fork, and one-second iterations. They must share a benchmark profile that fixes
`stepCount` to 100 and 500, so the baseline and candidate raw CSVs are unfiltered outputs from the
same two-row benchmark configuration.

Fixture generation must retain the same collection JSON, step/environment naming width,
placeholder ordering, zero-placeholder URL shape, script variants, and validation behavior after
the verification-gate correction. The correction adds no public API, dependency, cache, or measured
operation.

Record the baseline from the reviewed benchmark commit and the candidate from the reviewed
optimization commit. Use the same JDK, Gradle, JVM flags, CPU affinity, governor, dependency graph,
and benchmark configuration. Run both with `--no-daemon --max-workers=1`. Close IntelliJ, stop
Gradle and Kotlin daemons, inspect user processes, and terminate only exact interfering PIDs.

Measure a fresh sequential pair from the matched revisions. Start from a clean baseline, capture
and normalize its dependency graph, perform the complete machine-hygiene sequence, then run the
full unprofiled two-row baseline. Only after its raw CSV and metadata are complete may the worktree
switch cleanly to the candidate. Capture and compare the candidate dependency graph, repeat the
same hygiene, then run the matching candidate command. Rejected four-row measurements and Task 2's
earlier baseline capture may not be filtered, copied, or reused as accepted evidence.

If either accepted confidence interval overlaps, discard both runs and repeat the complete pair
unchanged. Never combine forks or rows from different configurations. Accept the pair only when
the existing reporter proves, for both rows:

```text
candidate score + candidate error < baseline score - baseline error
```

Publish raw CSVs, sanitized environment metadata, the manifest, comparison CSV, and Markdown
report atomically under `benchmark-results/<study-id>/<run-id>/`. Profiler-instrumented results
remain diagnostic artifacts and are never final timing evidence.

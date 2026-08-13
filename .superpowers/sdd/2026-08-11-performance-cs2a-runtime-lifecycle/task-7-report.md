# Task 7 — Real execution lifetime evidence

Status: READY FOR FINAL REVIEW. Runtime, adapter, collector, provider/evaluator, raw-JAR,
manifest/fixture, real-worker integration, detached-baseline integration, the standalone harness
self-test, the complete mutation set, and the full post-format Step 8 gate are green. Staging and
commit remain intentionally pending review.

## RED evidence

- Root RED probe (run separately): `:test` failed during `compileTestKotlin` because
  `drainLifecycleDiagnostics` did not exist. This was the intended missing weak-only runtime
  diagnostics seam.
- Benchmark-driver RED probe (run separately): `:benchmark-driver:test` failed during
  `compileTestKotlin` because `TrackedWeakReference`, `LifecycleWeakReferenceProvider`, and
  `RetainedCheckpointCollector` did not exist. This was the intended missing target/collector
  lifecycle seam.
- Manifest RED probe: the exact lifecycle fixture test failed at
  `DeterministicHttpFixtureTest.kt:79` after its retained assertion was changed to require exactly
  `RETAINED_SLOPE` while the packaged manifest still declared an empty retained gate list.

## GREEN evidence so far

- Diagnostics/driver binding focused gate: root diagnostics 5 tests and driver focused 54 tests,
  all green (30 Gradle tasks).
- Raw compatibility gate:
  `:test --tests '*ApiBaselineInventoryTest' --tests '*JvmSurfaceVisibilityTest'` with rerun,
  no-build-cache, and no-configuration-cache: 23/23 green (24 Gradle tasks).
- Provider/evaluator/manifest focused gate:
  `:benchmark-driver:test --tests '*RetainedMemoryRunnerTest' --tests
  '*ReleaseGateEvaluatorTest' --tests '*VerifiedLifecycleWorkloadSnapshotTest' --tests
  '*DeterministicHttpFixtureTest'` with rerun, no-build-cache, and no-configuration-cache: 51/51
  green, zero skipped/failures/errors (15 Gradle tasks). XML counts: runner 1, evaluator 34,
  lifecycle snapshot 6, deterministic fixture 10.
- Post-review pre-integration gate:
  `:benchmark-driver:test --tests '*ComparisonReportIntegrityTest' --tests
  '*ReleaseGateEvaluatorTest' --tests '*BenchmarkCampaignTest' --tests
  '*RetainedMemoryRunnerTest' --tests '*TargetAdapterContractTest' --tests
  '*RetainedCheckpointCollectorTest'` with rerun, no-build-cache, and
  no-configuration-cache: 75/75 green, zero skipped/failures/errors. XML counts: comparison report
  12, evaluator 36, campaign assembler 6, runner 1, adapter 16, collector 4. IntelliJ error and
  build diagnostics were clean for all nine Kotlin files.
- Current target export completed at `build/benchmark-target-current.json`; its raw manifest-file
  SHA-256 is `bd959ea2000c78d345c9525f798139eb26d9dd1bba4e7eff6d454ea439d9ebb9`.
- The final fresh detached checkout at exact commit
  `83f3cd70f78ad733412d10cbc8287aaabafe7aac` exported
  `build/benchmark-target-task7-baseline.json`; its raw manifest-file SHA-256 is
  `2454fded351773a29f5c2c71042043bba29716fc1291b8bf061267e3ac74e748`.
  The earlier pre-final export SHA `599c6e57...b4b5` differed only because the manifest records
  the absolute project-JAR execution path: replacing final directory
  `task7-final-baseline.4llQe7nd` with earlier directory `task7-baseline.BIXWGcLk` reproduces that
  earlier SHA exactly. Both exports have identical commit/tree identity and project-JAR SHA-256
  `ee173d0eb0e88791f6f40e18a00b1fdd578c9c22c71be9c864320978af2a84fe`.
- Detached-baseline broad `:benchmark-driver:integrationTest` with adapter
  `baseline-83f3cd70`: 43 discovered, 41 executed, exactly 2 skipped, zero failures/errors. The
  skipped methods were exactly `RunnerIntegrationTest.real retained worker reports major lifecycle
  weak references` and `BenchmarkDriverIntegrationTest.major lifecycle retained campaign preserves
  v2 series identity`.
- Detached-baseline standalone `:benchmark-driver:benchmarkHarnessSelfTest` with adapter
  `baseline-83f3cd70`: green (20 Gradle tasks); this task does not emit JUnit XML.
- Filtered current-manifest `:benchmark-driver:integrationTest` with adapter `major-v1`: exactly 2
  discovered/executed, zero skipped/failures/errors. Final post-format XML SHA-256 values were
  `1fb7d1a3fca579ccd5f0ea91949e93c9df2fdf980efb4e8e422a640e23a12476` for
  `BenchmarkDriverIntegrationTest` and
  `86be5b3cd6a65a65bf11e2782eb74abcfb6eb2ae3e5f6523935425a60d3db80f` for
  `RunnerIntegrationTest`.
- The real major worker serialized two exact rows (`ExecutionSession`, `KickExecution`) with
  `created == executionCount == cleared` and at least four acknowledged cycles. The serialized
  campaign preserved baseline `Cs1FakeExecutionToken` 1/1 evidence and exact major rows at all
  `1000`, `2000`, and `4000` points beneath one v2 provider/configuration series. The CLI verifier
  accepted the serialized campaign.
- The real campaign's 120-second run-envelope SHA-256 is
  `d5fcf4808f4a06c419a45049300d1897aa28452d6684b63701d475cb7f7aeeb0`, derived from the pinned v2
  base hash and installed logging SHA-256
  `1d2bd7bedacfcb118f3cc0188976bc789612db279ef7c8041d0e8d96bee9eea6`.

## Raw-JAR and source-visibility evidence

- Task-7 cumulative raw additions: **549** (Task 6 cumulative 534 plus 15 Task-7 rows).
- Task-7 cumulative raw removals: **447** (Task 6 cumulative 447 plus zero Task-7 removals).
- The Task-7 sets are exact cumulative deltas relative to the frozen raw baseline, not the frozen
  baseline itself.
- New owner: `com/salesforce/revoman/internal/runtime/ExecutionLifecycleDiagnostics`.
  Its 15 rows are one synthetic class (`0x1031`), eight private fields, and six methods. The exact
  drain descriptor is `()[Ljava/lang/Object;`. `drain`, `registerExecutionSession`, and
  `registerKickExecution` are synthetic `0x1019`; helper methods and all state are private.
- An initial raw-gate run showed that file-level `@JvmSynthetic` made the facade class synthetic but
  left the three callable members non-synthetic. Member-level `@JvmSynthetic` was added, the emitted
  `0x1019` flags and scanner `memberSynthetic=true` values were derived from the rebuilt artifact,
  and both external and same-package javac rejection probes then passed.
- Frozen Kotlin ABI and frozen raw baseline inputs were not changed.

## Provider and configuration identities

- Provider: `revoman-retained-two-phase-weak-proof-final-heap/v2`.
- Procedure: `reachability(two acknowledged full-GC cycles) then final-heap(two acknowledged full-GC cycles)`.
- Underlying default phase configuration SHA-256:
  `c9b31a1e8f4d304c8f1f7102114359e938b328bf21eb7b78e5312ccc5b65e4e9`.
- V2 base configuration SHA-256 (provider, procedure, ordered phase hashes, then
  `1000`, `2000`, `4000`, NUL-delimited):
  `60bebf52318b0dd7750b2d3365b52e6e7df62f0a8ef3ba80875e11f208ebf37d`.
- Known test logging SHA-256:
  `7f818fa42dc2603482186d3507dfc3bb2e7d6c229e998605eb46025c176b79db`.
- Known 5-second run-envelope SHA-256:
  `b8d9dfd1d8497bb1e45b543ffa46882cbc6094a91d783a060ced2213c437f6e8`.
- The hashes were reproduced independently from the implementation's exact ordered NUL envelopes;
  tests pin the literal base and run values and prove otherwise-identical baseline/major plans have
  one provider/configuration series identity.

## Manifest and fixture identities

- Only the packaged manifest's `RETAINED` gate list changed, from empty to exactly
  `["RETAINED_SLOPE"]`; cold and warm gate bytes were preserved.
- Manifest SHA-256:
  `288f95f6d9e2904cd019656b83ce915a2e23fb6f6f24391d1c596161ce71c31e`.
- Collection SHA-256 unchanged:
  `baacf0d7e9067c41848edf172aad8508b612528133d6576707f47da534c0ea86`.
- Handler SHA-256 unchanged:
  `12c15383ba5a0aa6aef1e32f409a86dc5168223ad18ccb17270506e625b105ef`.
- Fixture-tree SHA-256 unchanged:
  `31af0229163ef1ed544189f9b1f1dbd9a80607ffd024a2e5bd09cddfae919c92`.

## Evaluator evidence

- Compatibility/model validation occurs before retained-policy inspection and returns
  `INCOMPATIBLE` for malformed models.
- Exact-v2 expected uncleared rows return `FAIL` before cycle/shape defects; exact role shapes,
  counts, all three checkpoints, and every block are required before slope evaluation.
- Two- and three-cycle exact-v2 evidence, missing/extra/duplicate/unknown rows, and created-count
  mismatches are `INCONCLUSIVE`; valid baseline `1/1` and candidate execution-count/execution-count
  evidence across five blocks can pass. Legacy/future providers keep the generic two-cycle rule.
- A focused review exposed that validating weak-reference role policy before slope makes an
  uncleared expected row a slope-free `FAIL`, while the pre-Task-7 comparison model/schema required
  all `PASS`/`FAIL` decisions to carry numeric evidence. Task-7 scope was explicitly expanded to
  `ComparisonReport.kt`, the comparison schema, `ComparisonReportIntegrityTest.kt`, and
  `BenchmarkCampaignTest.kt`. The exception is restricted to the exact conjunction
  `FAIL` + `RETAINED_SLOPE` + `STRUCTURAL` + `RETAINED` + `RETAINED_BYTES` + null statistic;
  cold-median, bytes-per-step, and retained-`PASS` evidence-free forgeries remain rejected.

## Mutation evidence

Each mutation below produced the intended RED and was restored before the post-mutation focused
gate:

- diagnostics: replace exact `WeakReference` registration with a strong referent; allocate the
  growable buffer while disabled; reread the enabling property dynamically; omit
  `KickExecution` registration; remove checked record-count arithmetic; clear rather than swap the
  queue; accept a `WeakReference` subclass;
- adapter: eagerly bind the diagnostics method during ordinary prepare; retain target-created
  non-interned type strings rather than canonical driver constants; accept an empty drain; accept
  a weak-reference subclass; remove checked normalization counting; persist the test-only
  normalization observer on `MajorPreparedWorkload`;
- two-phase collector/provider: retain phase-one records on the collector; use the first heap;
  report only phase-two cycles; clamp the total cycles to four; prebuild records outside the
  `proveReachability` helper frame; remove checked cycle addition; include the adapter identity in
  the provider/configuration envelope;
- evaluator: accept exact-v2 two/three-cycle evidence; bypass candidate exact shape; bypass
  baseline exact shape; downgrade an uncleared expected row to `INCONCLUSIVE`; upgrade an
  uncleared unknown extra row to `FAIL`;
- worker: enable the property only after opening the runtime; omit property restoration; swallow a
  restoration failure; reverse prepared/runtime cleanup ordering;
- artifact/surface/filter: alter one pinned fixture hash; omit the exact diagnostics drain raw row;
  remove file-level `@JvmSynthetic`; and remove the Runner major-only method annotation.

The annotation mutation made the detached-baseline broad integration execute and fail the
major-only method instead of skipping exactly two methods. Removing file-level `@JvmSynthetic`
survived the source-only javac probe but changed the exact raw class row, so the raw gate killed it.
The restored post-mutation focused gate executed 24/24 tests (worker effects 4, adapter 16,
collector 4), with zero skips/failures/errors. Fresh IntelliJ diagnostics reported zero errors in
all six corresponding production/test files.

The worker-cleanup coverage review exposed one genuine unobservable boundary in the original
file list. Task-7 scope was explicitly expanded to add `TargetForkMainTest.kt` and a defaulted
internal `TargetForkEffects`/`runTargetFork` seam. Production `main` still uses `System` and
`BenchmarkJson`; the seam exists only to prove enable-before-body, restore-before-publish,
restore-failure publication suppression, body-primary/direct-suppressed restoration failure, and
prepared/runtime/recording/restoration cleanup order. The brief and implementation plan Files and
staging lists were updated accordingly.

## Final Step 8 gate

- Final root diagnostics/API/JVM gate: 28/28 executed, zero skipped/failures/errors; external
  Kotlin and javac compatibility compilers and `checkKotlinAbi` passed (30 Gradle tasks).
- Final driver gate: 349/349 executed, zero skipped/failures/errors; `jmhClasses` and `installDist`
  passed (22 Gradle tasks). The four adapter-normalization sentinels cleared in this run.
- Final detached-baseline standalone harness self-test passed (20 Gradle tasks; no JUnit XML).
- Final detached-baseline broad integration: 43 discovered, 41 executed, exactly the two
  major-only methods skipped, zero failures/errors (21 Gradle tasks).
- Final current/`major-v1` filtered integration: exactly 2 discovered/executed, zero
  skipped/failures/errors (21 Gradle tasks).
- The first `spotlessCheck` identified formatting-only indentation/wrapping in exactly three
  Task-7 files. Repository `spotlessApply` touched only those three files; fresh IntelliJ
  diagnostics were clean, and the complete Step 8 sequence above was rerun on the final formatted
  bytes. Final `spotlessCheck` passed (17 Gradle tasks).
- `git diff --check` and `git diff --cached --check` passed; the index is empty, as intentionally
  required before final review. Task-8 workflow and `BenchmarkWorkflowTest.kt` remain unchanged.

## Remaining actions

- After final review, stage exactly the Task-7 list, run the cached diff/status checks, commit with
  the exact required subject, and verify the worktree is clean. No exact-SHA CI-green claim is made;
  that workflow reconciliation remains Task 8.

# Task 11 report — executable benchmark campaign application

## Status

Implemented Task 11 test-first in the isolated `performance-cs1-benchmark-foundation` worktree.
The change is committed locally with the requested commit message; it is not merged or pushed.

## Scope delivered

- Replaced the placeholder entry point with a stable shell-free CLI for `list-workloads`,
  `run-paired`, `compare`, `verify`, and `capture-baseline`. The parser accepts only exact
  `--option value` argv pairs, rejects missing/unknown/duplicate options and invalid mode/metric
  shapes, requires explicit controlled-host policy, and maps success/execution/input/gate outcomes
  to exit codes 0/1/2/3.
- Added atomic absent-child artifact reservation beneath a canonical existing writable parent.
  Existing directories, regular files, hard links, symlinks (including broken links), and
  noncanonical parents fail before launches. Every pass/block/role/fork gets a distinct
  create-new directory; JSON and Markdown outputs are schema-checked and atomically published.
- Added the strict `revoman-target-manifest/v1` schema and a loader that snapshots bytes,
  schema-validates, decodes/canonicalizes, and performs full `VerifiedTargetManifest.preflight`.
  Dirty manifests remain structurally usable for smoke/JMH, while direct target verification and
  controlled/capture operations require clean identities.
- Completed the standalone distribution-owned Gradle init script. It requires a nonblank target
  ID, puts the normal target JAR first, uses resolved Maven/project component logical IDs in
  original runtime order, rejects unsupported/duplicate IDs, directories, and `-jmh.jar`, records
  full Git/Gradle/wrapper/JDK identities, and publishes atomically without target imports.
- Added one `BenchmarkCampaign` composition root. It owns one pair of verified target snapshots,
  one logging snapshot, one captured lifecycle workload snapshot, one deterministic fixture
  server, shared harness/environment/adapter identities, and top-level postflight/cleanup across
  all alternating blocks and separate metric passes.
- Refactored cold, warm, retained, and warm-allocation runners so the campaign can reuse one
  verified session while their legacy standalone APIs retain their original owned lifecycle.
  Target forks receive the same captured verification tokens; target, logging, source/snapshot,
  fixture, process, thread, and temporary-directory finalization preserves primary/suppressed
  failures.
- Reused `PairedBlockOrchestrator` for scheduling and whole-pair health rejection. Task 11 assigns
  the exact block/role/fork positions and merges only identical `(metric, provider,
  providerConfigurationSha256, unit)` evidence. Cold latency, cold JFR allocation, cold peak RSS,
  warm latency, warm normalized-JMH allocation, and retained checkpoints remain separate passes.
- Hardened warm allocation so strict single-target JMH stays single-target. The campaign
  schema-validates and decodes normalized evidence, verifies exact scheduled harness/target/
  workload/configuration identity, cross-checks normalized raw observations against the raw JMH
  importer, then reattaches them once to the actual block/role/fork. Raw JSON, normalized JSON, and
  human output are content-addressed artifacts. No role/pairing field was added to the JMH schema.
- Extracted `RuntimeIdentityFactory` from private JMH code and reused it for JMH and paired
  campaigns. Target classpath snapshots are path-free; distribution/source identities exclude
  timestamps and installation roots, include the thin JMH JAR and all declared installed assets,
  and never self-hash.
- `compare` accepts paired evidence only, loads exact packaged workload manifests, runs the Task 10
  evaluator, writes machine/Markdown reports, and returns nonzero for every non-PASS decision.
  `verify` dispatches strict paired/JMH validation and additionally supports clean target-manifest
  verification. `capture-baseline` requires controlled intent, both full fixed commits, both clean
  roles, and adapter `baseline-83f3cd70` before artifact reservation.
- Extended the installed harness self-test to require every fixed distribution path, the lifecycle
  benchmark plus `BenchmarkList`/`CompilerHints`, no flattened target/Graal classes, and stable
  target-manifest logical IDs.

## TDD and debugging evidence

The first focused RED failed at `:benchmark-driver:compileTestKotlin` on the absent `BenchmarkCli`,
`BenchmarkCommand`, `ArtifactDirectory`, and `TargetManifestLoader` APIs. Separate compile REDs
covered `RuntimeIdentityFactory`, normalized-JMH identity verification, paired evidence assembly,
and campaign-owned warm-allocation postflight.

The first real installed CLI integration RED failed because `BenchmarkDriverApplication` and exit
semantics did not exist. Subsequent real-process RED/GREEN cycles found and fixed:

- canonical JUnit temporary-parent handling;
- paired schema fields that required nullable properties Moshi intentionally omits;
- fractional JMH `B/op` observations being incorrectly subjected to integral-byte validation;
- a generic fixture property leaking stale lifecycle source state across tests;
- repeated role-directory creation failing the second fork; and
- missing warm JMH raw/normalized/human artifact identities.

Every failure was reproduced, traced to its boundary, and fixed with the narrow corresponding test.

## Verification evidence

- Final complete unit and real-process integration command:

  ```text
  ./gradlew :benchmark-driver:test :benchmark-driver:integrationTest \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70 \
    --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
  ```

  Result: `BUILD SUCCESSFUL` in 1m02s; all 24 tasks executed; 293 unit tests and 38 integration
  tests passed with zero failures/errors. A final focused CLI integration run after the last
  validation additions passed all 5 CLI tests, including real multi-fork cold and real two-role
  warm JMH campaigns.

- Required distribution/JMH gate:

  ```text
  ./gradlew :benchmark-driver:benchmarkHarnessSelfTest :benchmark-driver:benchmarkJmh \
    :benchmark-driver:jmhClasses :benchmark-driver:installDist spotlessCheck \
    -Pbenchmark.includes=HarnessSanityBenchmark \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70 -Pbenchmark.quick=true \
    --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
  ```

  Result: `BUILD SUCCESSFUL`; all 29 tasks executed. The nested installed init-script export and
  strict JMH run both passed.

- Installed CLI `list-workloads` prints `jmh.component-operations.v1` and
  `lifecycle.no-script-one-step.v1`.
- Installed thin JAR contains `WarmLifecycleAllocationBenchmark`, `META-INF/BenchmarkList`, and
  `META-INF/CompilerHints`; it contains no `org/graalvm/**` or target `internal/**` classes.
- Installed standalone clean export produced `revoman-target-manifest/v1`, target `current`, first
  logical ID `project:revoman-root:jar`, 61 unique stable Maven/project logical IDs, no directories,
  and no `-jmh.jar`. Omitting `benchmark.targetId` failed with `benchmark.targetId is required`.
- Source manifest contains 78 relative artifacts, both fixed adapter IDs, no absolute artifact
  paths, no runtime timestamp, and no `distributionSha256` self-reference.
- Commons Math remains pinned at 3.6.1. All schemas/manifests/results parse with `jq`; `git diff
  --check`, package/shell/scope scans, and final focused parser/loader plus `spotlessCheck` pass.

## Self-review

- Smoke evidence records `policySha256 = null` and can never pass release gates. Controlled runs
  require explicit verified policy, clean roles, a clean harness source identity, and the Task 10
  minimum block shapes before launch.
- Timed target/JMH forks perform only cheap token/stamp validation; full manifest/classpath hashing
  is controller-side. JMH allocation observations are never copied to both roles.
- Rejected block artifacts remain auditable while observations remain empty and excluded from
  statistics. Provider drift is rejected rather than silently merged.
- Target, output, and workload paths are canonicalized and workload IDs cannot escape the exact
  installed catalog. All subprocesses use argument lists; no shell parser is involved.
- Qodana was not run because the task explicitly commits without pushing; repository guidance
  requires Qodana before a push. The only recurring build warning is the pre-existing Kotlin 2.4
  redundant annotation-target warning (plus the existing test JVM CDS warning).

## Commit

```text
feat: complete benchmark driver application
```

## Review fix round 1 — benchmark application integrity

### Status

All nine confirmed review groups were fixed test-first. The fix is staged and committed locally as
`fix: harden benchmark application integrity`; it is not pushed or merged. Task 12 remains out of
scope.

### Integrity fixes

- Made `generateBenchmarkHarnessSource` freshness-aware for the exact Git HEAD/tree/porcelain state
  and explicitly non-cacheable. A clean-clone functional probe reproduced the original stale
  `dirty:false` output, then proved clean HEAD -> dirty checkout -> new clean HEAD transitions all
  regenerate deterministic identities.
- Split JDK compatibility keys correctly: target build roles compare distribution, vendor, full
  version, and Gradle-daemon JVM flags; runtime-versus-build compares distribution/vendor/version
  only. `javaHome` remains path-local and excluded everywhere.
- Added the exact valueless `compare --enforce-release-gates` flag. Ordinary comparison always
  publishes its report and exits zero; explicit enforcement maps non-PASS decisions to exit 3.
- Recompute `HarnessIdentity.distributionSha256` from its ordered artifact snapshot in both paired
  and JMH model validation. `compare` additionally requires the input harness to equal the current
  installed distribution. Installed `verify` tests reject forged paired and JMH hashes.
- Replaced prechecks with an alias-safe `AtomicOutputSet`: outputs are hard-link reserved under
  canonical writable parents, inputs/outputs and output pairs reject existing, symlink, hard-link,
  case-insensitive, and canonical-parent aliases, both JSON/Markdown files are fully prepared before
  publication, and every reservation/publication is rolled back with suppressed cleanup failures.
- `TargetManifestLoader` now schema-validates, decodes, hashes, and preflights one captured byte
  snapshot through `VerifiedTargetManifest.preflightFromSnapshot`; postflight still detects any
  later source replacement.
- Extracted `VerifiedCampaignSession` and `JmhControllerProcessLauncher` from the former 851-line
  composition root. One campaign now owns target snapshots, installed harness identity, workload
  snapshot, deterministic server/base URL, logging snapshot, and JFR snapshot. Top-level ordered
  postflight covers both targets, harness, workload source/snapshot, logging, JFR, cleanup, and
  primary/suppressed failure preservation.
- Campaign warm allocation writes a parent identity context plus read-only target token. The JMH
  controller consumes the parent target/harness/environment/workload/logging identities, existing
  outer fixture root/base URL, and cheap role-bound target stamps; it no longer performs a full
  target preflight or opens a per-controller workload snapshot/server. Cold allocation reuses the
  single outer verified JFR snapshot.
- Replacement exhaustion is valid evidence: accepted blocks may be zero through the requested
  count, rejected blocks remain serialized and auditable, campaign assembly does not require
  `COMPLETE`, and comparison reports insufficient zero/short evidence as `INCONCLUSIVE`.
- Added full KDoc to `discoverInstallationRoot` including the returned installed-layout contract and
  failure semantics.

### RED/GREEN evidence

- Initial focused RED stopped at `compileTestKotlin` with 14 expected missing-contract errors for
  the valueless gate model/parser, output-set API, and snapshot preflight API. After the first fix
  group, the identical focused command passed 46 tests with zero failures/errors.
- Campaign ownership RED stopped on the absent installed-harness postflight, campaign-owned JFR
  overload, and parent fixture-base-URL contract. Its GREEN command passed 30 tests with zero
  failures/errors.
- The isolated Gradle functional RED generated `dirty:false` after an unrelated tracked-file
  mutation. GREEN recorded commit `834ba502...`, then `dirty:true`, then new commit `ea86c9b4...`
  with `dirty:false`; the temporary clone was deleted.
- One real integration diagnosis cycle initially reported `INCOMPATIBLE` because the synthetic
  baseline retained the current commit. The test fixture was corrected to the protocol's mandatory
  full baseline commit, after which the same cold/compare/verify gate passed.

### Final verification evidence

- `./gradlew :benchmark-driver:test --rerun-tasks --no-build-cache
  --no-configuration-cache --console=plain` -> `BUILD SUCCESSFUL` in 16s; 15/15 tasks executed; 302
  unit tests passed with zero failures/errors/skips.
- `./gradlew :benchmark-driver:integrationTest
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json"
  -Pbenchmark.adapter=baseline-83f3cd70 --rerun-tasks --no-build-cache
  --no-configuration-cache --console=plain` -> `BUILD SUCCESSFUL` in 54s; 21/21 tasks executed; 39
  integration tests passed with zero failures/errors/skips.
- The focused installed two-role warm JMH campaign passed in 11s with 21/21 tasks. The installed
  cold/compare/verify campaign, including ordinary and enforced gate exits, passed in 13s with 21/21
  tasks.
- `benchmarkHarnessSelfTest benchmarkJmh jmhClasses installDist spotlessCheck` passed in 16s with
  29/29 tasks; its nested installed init-script export also passed.
- A separate installed init-script export passed in 21s with 16/16 tasks: 61 ordered and unique
  classpath IDs, project JAR first, no directory and no `-jmh.jar` entry.
- Installed layout contains every fixed bin/conf/libexec/schema/workload/JFR/thin-JAR path. The thin
  JAR contains the lifecycle benchmark, `BenchmarkList`, and `CompilerHints`, with no Graal or target
  internal classes. The installed source identity now contains 81 relative artifacts, both fixed
  adapters, and no distribution self-hash.
- Installed strict JMH evidence verifies successfully. All schemas, workload manifests, exported
  target manifest, JMH raw result, and normalized result parse with `jq`. Runtime dependency output
  confirms Commons Math 3.6.1, JMH 1.37, NetworkNT 3.0.4, and resolved Moshi 1.15.2.
- `git diff --check` passes. The final process scan found no live benchmark controller, JMH, or
  target-worker processes. Both Task 11 temporary verification artifacts were deleted.

### Commit

```text
fix: harden benchmark application integrity
```

## Review fix round 2 — campaign isolation

### Status

The two confirmed isolation gaps were fixed test-first. The fix is committed locally as
`fix: preserve inconclusive campaign isolation`; it is not pushed or merged.

### Completeness and process-lifecycle fixes

- Added one release-gate completeness rule shared by normative ratios, targeted ratios, retained
  slope, and per-step spread. Controlled evidence is statistically usable only when every requested
  accepted block is present and the configured baseline/candidate first-position counts differ by
  at most one. Incomplete evidence produces an `INCONCLUSIVE` decision without a fabricated ratio,
  slope, or observed value; rejected block evidence remains serialized and auditable.
- Replaced the JMH controller launcher's ad hoc tracker/virtual-thread cleanup with the proven
  process-package ownership primitives: `ProcessTreeTracker`, bounded
  `DefaultLauncherCleanup.finalizeOwnedProcessTree`, conditional late-handle continuation, and
  `FailureAccumulator`.
- The JMH launcher now owns one named three-task executor for tracker/stdout/stderr, retains only
  64 KiB per output tail, detects tracker failure while the root is running, and finalizes the root
  plus retained descendants exactly once on success, timeout, interruption, tracker failure, drain
  failure, or post-exit observation failure. Tracker stop/join, task shutdown, interruption restore,
  and primary/ordered-suppressed failure semantics match `JdkProcessLauncher`.
- Added deterministic process-start, tracker, drain, cleanup, and deadline seams. A real inherited-
  pipe descendant integration test proves drain failure kills the descendant and leaves no named
  launcher task alive.

### RED/GREEN evidence

- The release-gate RED produced three expected failures: short accepted evidence still exposed
  intervals, biased accepted order still passed normative/targeted ratios, and biased structural
  evidence still exposed retained/per-step values. GREEN passed all 28 evaluator tests, including
  accepted-short, biased equal-count, balanced odd-count, targeted, retained, per-step, and rejected-
  evidence cases.
- The launcher RED failed test compilation on the absent lifecycle-safe constructor and process-
  start/thread seams. GREEN passed five deterministic cases: post-start interruption, tracker
  failure, drain failure, post-exit observation failure with ordered suppressed cleanup failures,
  and successful exactly-once finalization with no leaked launcher threads.
- The real descendant containment test first failed at the integration precondition because the
  required target-manifest property was omitted. With the prescribed manifest/adapter properties,
  the inherited-pipe drain failure completed in eight seconds, terminated the descendant, and left
  no launcher tasks.

### Final verification evidence

- Complete launcher/containment rerun passed in 50 seconds: 27 `JdkProcessLauncherTest`, five
  `JmhControllerProcessLauncherTest`, and 31 `RunnerIntegrationTest` cases, with zero failures or
  errors.
- The installed two-role warm allocation campaign passed separately in nine seconds and attached
  each normalized JMH target only to its scheduled role.
- Full `:benchmark-driver:test :benchmark-driver:integrationTest` passed in 1m05s: 311 unit tests and
  40 integration tests, with zero failures or errors.
- `benchmarkHarnessSelfTest benchmarkJmh jmhClasses installDist spotlessCheck` passed all 29 tasks
  in 13 seconds, including the nested installed target-manifest export and strict JMH execution.
- The final process scan found no live benchmark controller, JMH, target-worker, or fixture
  descendant. The benchmark temporary-directory scan was empty. The installed distribution contains
  all fixed bin/conf/libexec/schema/workload/JFR/thin-JAR paths; the thin JAR contains the lifecycle
  benchmark, `BenchmarkList`, and `CompilerHints`, and no Graal or target-internal packages.
- `git diff --check`, the scope/package scan, and final `spotlessCheck` pass.

### Commit

```text
fix: preserve inconclusive campaign isolation
```

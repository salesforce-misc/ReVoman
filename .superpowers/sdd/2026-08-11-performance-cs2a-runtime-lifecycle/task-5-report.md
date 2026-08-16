# Task 5 implementation report: single-kick `ExecutionSession`

## Scope and starting point

- Worktree: `.worktrees/performance-cs2a-lifecycle`
- Exact clean starting commit: `83b0998b9b70c0859de43d9cca887c35e3f1a795`
- Commit message: `refactor: introduce execution session lifecycle`
- Scope is Task 5 only. The list and runbook paths intentionally retain their public-recursive,
  one-session-per-kick behavior for Task 6.
- No frozen ABI artifact was edited. Both frozen files remain byte-identical to the starting commit:
  - `api/cs2-baseline-revoman-root.jvm.tsv`:
    `6ecd4fd73461ed3353148cbb34a2cea1f60ba3bed256af7ed4dfeacfdaac1d2f`
  - `api/revoman-root.api`:
    `d2a4e5dd67cf05b3c94321a44f0828799b87cbe467c752b6f4886eb80d6b59ec`

## TDD RED

The prescribed RED was captured before adding production boundaries:

```bash
./gradlew :test \
  --tests '*ExecutionSessionTest' \
  --tests '*ReVomanRuntimeTest' \
  --tests '*KickExecutionTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Result: `compileTestKotlin FAILED`, exit 1. Representative compiler failures were unresolved
references to `ExecutionSessionFactory`, `executionSession`, `reVomanRuntime`, and
`KickExecutionFactory`, plus the missing expanded `KickExecution` ownership API. This proved the
tests could not compile against the Task 4 production surface.

## GREEN implementation

- Added the synthetic internal `ExecutionSession`/factory boundary. The anonymous session owns the
  carried environment, finalized-rundown builder, current child, and closed state.
- Preserved the exact kick envelope: banner start; borrowed sink install; transactional child
  creation, activation, execution, and closure; active-child clearing; step recording while the
  sink remains installed; sink restoration; then append, callback, and fresh carry capture.
- Registered every returned child in a short-lived `ResourceScope`. `activeChild` is cleared in an
  inner `finally`, including when `closeAfter` rethrows the body failure or promotes a close failure,
  so later session closure cannot retry that child.
- Kept factory ownership transactional: no child ownership transfers before a successful return.
  The construction-failure test creates a partial closeable inside its recording factory and proves
  the factory rolls it back while the session owns and closes zero children.
- Carry uses `rundown.mutableEnv.toMap()` after the callback. Tests force the memoized immutable
  snapshot first, mutate the live environment in the callback, and prove both the new value and
  snapshot detachment.
- Added one-shot `KickExecution` body execution while keeping sandbox creation lazy. Reading or
  passing `scripts` does not construct a runtime; first nonblank script execution constructs one
  sandbox and child closure closes it once.
- Moved the existing kick body into a function-local anonymous `KickBody` in `KickRunner.kt` and
  made `ReVoman.revUp(Kick)` a slim delegate to `ReVomanRuntime`. The sequencing helper returns an
  unnameable lexical `Pair` instead of emitting a source-callable local data-class surface; execution
  order, control flow, ledger, hooks, polling, progress, and reporting remain the moved behavior.
- The real no-script and script controls call `reVomanRuntime(countingSandboxFactory)` and cross the
  production runtime, session, child, anonymous runner, and `PmJsEval` path. The script positive
  control unambiguously invokes a collection-level `postResponse` script; changing it to the
  no-script fixture makes the sandbox assertion fail.
- The default no-argument graph lexically creates an anonymous `SandboxFactory` whose `create()` is
  the only new `PmSandbox` construction point.

## Focused tests and counts

After implementation and restoration of all mutations:

```bash
./gradlew spotlessApply :test \
  --tests '*ExecutionSessionTest' \
  --tests '*ReVomanRuntimeTest' \
  --tests '*KickExecutionTest' \
  --tests '*LegacyEvaluatorRemovalTest' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Result: `BUILD SUCCESSFUL in 16s`; `SUCCESS: Executed 42 tests in 4.6s`; 32 actionable tasks
executed. Breakdown: 9 session, 4 runtime, 9 child, 1 legacy-owner, 5 baseline-inventory, and 14 JVM
visibility tests.

The session tests explicitly cover success ordering; effective-environment precedence; detached
post-callback carry; construction rollback; body/close suppression; close-failure promotion;
callback and fresh-snapshot failure after child closure; active-child non-retry; idempotent session
close; post-close rejection; and preservation of transferred environment, collection-variable, and
global peer scopes.

## Required mutation proofs

Each mutation was made against the working implementation, its owning focused test was run to RED,
and the production/test source was restored before the final gate.

1. **Early sink restoration**: moved `Banner.recordSteps` after `RunLogContext.restore`.
   `ExecutionSessionTest.built session records completed steps before restoring borrowed sink`
   failed (exit 1); classfile positions showed restore at 9471 and record at 10517, violating the
   required record-before-restore order. Restored and green.
2. **Memoized carry**: replaced `rundown.mutableEnv.toMap()` with `rundown.immutableEnv`.
   `ExecutionSessionTest.carried environment wins and fresh post-callback snapshot is detached`
   failed (exit 1): expected `shared=callback`, but the carried map retained the earlier memoized
   value. Restored and green.
3. **Fake-only/no-script sandbox path**: replaced the real-script positive-control fixture with the
   no-script fixture. `ReVomanRuntimeTest.real script path creates and closes exactly one sandbox
   runtime` failed (exit 1): expected create count 1, actual 0. Restored and green.
4. **Widened synthetic operation**: removed `@JvmSynthetic` from `ReVomanRuntime.execute`.
   `JvmSurfaceVisibilityTest.external Java can name focused types but cannot operate or construct
   them` failed (exit 1) because the adversarial Java consumer compiled. Restored and green.
5. **Missing raw row**: removed the `ExecutionSession` class row from the literal Task 5 additions.
   `ApiBaselineInventoryTest.JVM baseline is canonical complete and active raw additions are exactly
   approved` failed (exit 1), reporting the class as one unexpected active row. Restored and green.
6. **Stale ReVoman PostmanSDK reference**: added a private `PostmanSDK` descriptor to `ReVoman`.
   `LegacyEvaluatorRemovalTest.built root jar removes the legacy evaluator surface but retains
   nodeModulesPath source shape` failed (exit 1), reporting the unexpected `ReVoman` owner.
   Restored and green.

## Final prescribed gate

Exact command:

```bash
./gradlew :test \
  --tests '*ExecutionSessionTest' \
  --tests '*ReVomanRuntimeTest' \
  --tests '*KickExecutionTest' \
  --tests '*LegacyEvaluatorRemovalTest' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*Ledger*Test' \
  --tests '*Hook*Test' \
  --tests '*Polling*Test' \
  --tests '*ControlFlow*Test' \
  --tests '*JvmSurfaceVisibilityTest' \
  compileApiCompatibilityTestKotlin \
  compileApiCompatibilityTestJava \
  checkKotlinAbi spotlessCheck \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Result:

```text
SUCCESS: Executed 117 tests in 16.5s
BUILD SUCCESSFUL in 26s
38 actionable tasks: 38 executed
git diff --check: exit 0, no output
```

Both external compatibility compilers, `checkKotlinAbi`, and `spotlessCheck` passed in the same
fresh, no-build-cache, no-configuration-cache run. Compiler warnings were pre-existing unchecked
casts/deprecations and Kotlin language-version notices; there were no Task 5 errors or test failures.

## ABI and classfile evidence

- Compiler-extracted cumulative Task 5 raw delta is exact: 510 literal additions and 429 literal
  removals. Both `ApiBaselineInventoryTest` and `JvmSurfaceVisibilityTest` compare the complete
  active-minus-frozen and frozen-minus-active rendered-row sets for equality.
- The root JAR contains only the declared internal interfaces/top-level factory containers and the
  actual anonymous implementations, including:
  `ExecutionSessionKt$executionSession$1`,
  `ExecutionSessionKt$executionSessionFactory$1`,
  `KickExecutionKt$kickExecution$1`,
  `KickRunnerKt$kickExecutionFactory$body$1`,
  `KickRunnerKt$kickExecutionFactory$1`,
  `ReVomanRuntimeKt$reVomanRuntime$1`, and
  `ReVomanRuntimeKt$reVomanRuntime$sandboxFactory$1`.
- Built-JAR assertions prove operations are synthetic; implementations are unnameable and
  unconstructible from external and same-package Java; direct type-reference attempts for every
  emitted owner fail with name/access diagnostics rather than wrong-arity-only diagnostics; no
  focused type implements `AutoCloseable`; and there are no companions, `INSTANCE` fields, or
  ambient current-session fields.
- Parsed constant-pool owner/member/descriptor evidence proves `ReVoman` has zero operational
  `PostmanSDK` references and the exact emitted runner owner contains the moved wiring. The test uses
  the configured root JAR, not source scans, class loading, prefixes, or substring-only evidence.
- The built-session bytecode test proves `Banner.recordSteps` occurs before
  `RunLogContext.restore` in the emitted anonymous implementation.
- Independent read-only audits returned PASS for both lifecycle semantics and ABI/JVM structure,
  with no Critical or Important findings.

## Self-review

- Reviewed the complete scoped production and test diff against the Task 5 brief.
- Confirmed list and runbook source behavior remains intentionally recursive for Task 6.
- Confirmed no public API exposure, named default implementation, named runner class, global hook,
  singleton, companion, `ThreadLocal`, or current-session storage was introduced.
- Confirmed callback and carry finalization occur only after successful child closure and sink
  restoration; factory/body/close/callback/snapshot failures retain their specified ownership and
  suppression behavior.
- Confirmed a closed session clears only its own retained environment/results and does not clear the
  peer scopes transferred into the returned legacy `Rundown`.
- Confirmed `git diff --check` is clean and both frozen ABI baselines are byte-identical.
- Context7 was not needed because no library/API syntax lookup was required. IDE index tools were
  unavailable, so definitions/references and ABI claims were verified with `rg`, Kotlin/Java
  compilers, parsed classfiles, and `javap`. No runtime debugger was needed.

## Files and commit

Production:

- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSession.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickExecution.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickRunner.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/runtime/ReVomanRuntime.kt`

Tests and exact gates:

- `src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSessionTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/runtime/KickExecutionTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/runtime/ReVomanRuntimeTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/runtime/LegacyEvaluatorRemovalTest.kt`
- `src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt`
- `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt`
- `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`

Documentation:

- `docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- `.superpowers/sdd/2026-08-11-performance-cs2a-runtime-lifecycle/task-5-report.md`

The exact scoped files above are committed together with message
`refactor: introduce execution session lifecycle`; the immutable commit SHA is reported in the
handoff because a commit cannot contain its own SHA.

## Concerns

None. Task 6 still must route list/runbook execution through one shared session; that behavior was
deliberately not implemented here.

## Formal review fix round 1

Review was resumed from clean `b5ade4ab12973b5501ab46592710dc553d03e93e`; the two intervening
commits modify only the Task 6 plan. This round changed only Task 5 tests/report and the Task 5
section of the shared plan. No Task 6 wording or production implementation was changed.

### Old-test escapes and new RED evidence

1. The original session event test observed only `create, execute, close`; the bytecode test proved
   only `record < restore`. Neither could fail for banner-after-install or callback-before-restore.
   After adding one exact observable sequence, deliberately removing the `Banner` interception
   produced RED: expected `banner-start, install, create, execute, close, record, restore,
   callback`, but actual lacked `banner-start` and `record` (exit 1). Separately removing the install
   event produced RED with actual `banner-start, create, execute, close, record, restore, callback`
   (exit 1). The restored test also installs an ambient previous sink and asserts the callback sees
   that exact sink after restore.
2. The original real no-script/script controls counted only sandbox create/execute/close; exact
   session/child counts came from a fake child path. Test-only decorators were added around the real
   `kickExecutionFactory` and `executionSessionFactory`, without replacing the production
   `KickBody`. Deliberately removing the child-execute/child-close/session-close increments made all
   three real controls RED (exit 1); representative failure: expected `childExecutes=1`, actual 0.
3. The original single-kick test returned a normal map, so an accidental unconditional carry copy
   escaped. A decorating real child now returns a traversal-failing live environment. Mutating
   `ExecutionSession` from `if (carryForward) mutableEnv.toMap()` to unconditional copying made the
   focused test RED (exit 1) with exact `IllegalStateException: carry snapshot traversed` thrown by
   `ReVomanRuntime.execute`. The production branch was restored.
4. The original session test asserted one close throwable but did not prove that a child's existing
   suppression tree stays intact, and the ResourceScope test did not retain resources to prove
   exact-once/idempotent closure. The session now asserts exact body identity, the exact propagated
   child throwable as its sole direct suppression, unchanged nested identities/order, and no retry.
   The compile-correct multi-resource proof retains two real `ResourceScope` resources and asserts
   direct `[secondFailure, firstFailure]` suppression, empty inner suppression lists, reverse close
   order, one close each, and unchanged counts after a second scope close. Removing the new count,
   inner-suppression, and idempotent-reclose assertions recreated the old-test escape; restoration
   followed the focused GREEN below.

### Adjudicated suppression contract

`ExecutionSession` owns exactly one `KickExecution`, so it can honestly receive only one propagated
child close throwable. Pre-populating that throwable to pretend the session produced multiple
siblings would manufacture a false nested shape. The Task 5 plan now says exactly:

- the one-child session directly suppresses the child's one propagated throwable onto a body
  failure, preserving any existing nested suppression without flattening/reordering and without
  retry; and
- `ResourceScope`, which can own multiple resources, guarantees direct reverse-registration-order
  suppression for multiple distinct close failures, exact identity, exact-once close, and
  idempotent re-close.

This closes the formal finding without widening production interfaces or changing the raw JVM ABI.

### GREEN and lifecycle evidence

The covering focused command before formatting/ABI gates was:

```bash
./gradlew :test \
  --tests '*ExecutionSessionTest' \
  --tests '*ReVomanRuntimeTest' \
  --tests '*ResourceScopeTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Result: `SUCCESS: Executed 27 tests in 3.8s`; `BUILD SUCCESSFUL in 13s`; 24 actionable tasks
executed. The exact envelope is now observed as:

```text
banner-start -> install -> create -> execute -> close -> record -> restore -> callback
```

The successful envelope uses one non-empty step report, and the close-failure case returns the same
non-empty rundown from the body yet proves banner step count remains zero, callback count remains
zero, and child close is not retried. The callback observes the exact ambient previous sink.

Both real controls now assert exactly one session open/close and one child create/execute/close. The
no-script control still asserts zero sandbox creates/closes. The one-script control still crosses
the production anonymous runner and real `PmJsEval` port, asserting one sandbox create, script
execute, and close. The no-carry-copy control crosses that same real script path, returns despite a
map whose entry traversal throws, proves the returned map remains live/readable by key, then
explicitly traverses it to demonstrate the sentinel failure was armed.

### Proportional final verification

Immediately before the fix commit:

```bash
./gradlew :test \
  --tests '*ExecutionSessionTest' \
  --tests '*ReVomanRuntimeTest' \
  --tests '*KickExecutionTest' \
  --tests '*ResourceScopeTest' \
  --tests '*LegacyEvaluatorRemovalTest' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  compileApiCompatibilityTestKotlin \
  compileApiCompatibilityTestJava \
  checkKotlinAbi spotlessCheck \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git diff --exit-code -- src/main/kotlin \
  api/cs2-baseline-revoman-root.jvm.tsv api/revoman-root.api
```

Result:

```text
SUCCESS: Executed 56 tests in 6.8s
BUILD SUCCESSFUL in 19s
38 actionable tasks: 38 executed
git diff --check: exit 0, no output
production and frozen ABI diff: exit 0, no output
```

The same run passed both compatibility compilers, `checkKotlinAbi`, exact raw-JVM inventory and
visibility tests, legacy evaluator ownership, and `spotlessCheck`. Review-fix files are exactly:

- `src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSessionTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/runtime/ReVomanRuntimeTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/runtime/ResourceScopeTest.kt`
- the Task 5 section of
  `docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- this report

No production or frozen ABI file differs. Task 6 plan changes from commits `4b982557` and
`b5ade4ab` were preserved byte-for-byte. The fix is committed with message
`test: close Task 5 lifecycle proof gaps`; its SHA is reported in the handoff.

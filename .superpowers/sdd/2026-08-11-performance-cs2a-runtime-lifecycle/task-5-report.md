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

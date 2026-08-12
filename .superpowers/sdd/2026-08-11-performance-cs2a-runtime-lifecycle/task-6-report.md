# CS2a Task 6 Report: Multi-kick Execution Sessions

## Scope and result

Task 6 is complete on the exact Task 5 base
`60b49d6a7a57506e59970df76e0f6dbbabcd5ac5`. List, vararg, and runbook execution
now route through one freshly opened `ExecutionSession` per public call while every configured kick
occurrence still creates and closes a distinct child execution. The legacy Java-callable
`RunbookExeKt.executeRunbook(Runbook, Map)` adapter is preserved; the session-taking helper and the
new runtime overloads remain Kotlin-only synthetic routes.

`ExecutionSession` remains unchanged and retains the deep `executeKick` plus `close` interface.
Tasks 7 and 8 were not touched.

## TDD evidence

### Structural RED

With only `RunbookExeStructureTest` added:

```text
./gradlew :test --tests '*RunbookExeStructureTest' --rerun-tasks \
  --no-build-cache --no-configuration-cache --console=plain
```

The test failed because method-scoped bytecode for `RunbookExeKt.runStepBody` still contained:

```text
invokestatic com/salesforce/revoman/ReVoman.revUp(Kick): Rundown
```

This established the forbidden public single-kick recursion before production edits.

### Compile RED

After adding the lifecycle tests and before adding runtime overloads:

```text
./gradlew :test \
  --tests '*ReVomanRuntimeTest' \
  --tests '*ExecutionSessionE2ETest' \
  --tests '*MultiKickEnvTypesE2ETest' \
  --tests '*RunbookExeE2ETest' \
  --tests '*RunbookLegibilityE2ETest' \
  --tests '*RunbookExeStructureTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

`compileTestKotlin` failed on the missing `ReVomanRuntime.execute(List, PostExeHook, Map)` and
`ReVomanRuntime.execute(Runbook, Map)` overloads (list/runbook arguments could only resolve against
the existing `Kick` overload).

### GREEN

The focused restored implementation executed 57 tests successfully. The tests prove:

- one outer session for non-empty and empty list/runbook calls;
- one distinct sequential child per configured occurrence, including duplicate kick objects;
- fresh post-hook carry snapshots preserving non-String values and remaining detached from live
  mutable environments;
- runbook carry frozen immediately after `executeKick`, before produces and `assertAfter` logic;
- real child scope, collection/global variable, capture, and control-flow isolation;
- exact borrowed/overridden/NoOp sink routing and ambient-context restoration;
- child and outer-session closure on success and every failure boundary, including primary
  throwable identity and direct suppression;
- direct vararg/list/runbook/single public facade routing without public recursion.

## Implementation evidence

- `ReVomanRuntime` has exactly three no-default synthetic `execute` overloads. List execution opens
  one session using the public dynamic environment and calls `executeKick(..., carryForward = true,
  beforeCarry = ...)`. Runbook execution opens one session and invokes only the synthetic
  session-taking runbook helper.
- All four hand-written `ReVoman.revUp` primaries call their matching runtime descriptor directly.
  The five generated `@JvmOverloads` methods call only their matching `$default` bridge; the three
  `$default` bridges call only the matching primary.
- The two-argument runbook adapter calls the runtime factory/interface route. `runStepBody` calls
  only `ExecutionSession.executeKick`, takes `mutableEnv.toMap()` immediately, and has no
  `ReVoman` class/member/descriptor reference.
- Runbook-scope sinks are installed only when real; child kick sinks are overridden only by a real
  runbook sink. NoOp masks an ambient sink at each kick boundary, and every borrowed sink remains
  unclosed.

## Raw JVM, javac, and classpath evidence

The configured root JAR inventory was compared as raw rows against the frozen JVM baseline.

```text
CS2_TASK6_RAW_JVM_ADDITIONS = 518 rows
CS2_TASK6_RAW_JVM_REMOVALS  = 431 rows
frozen JVM entries         = 6101
```

The literal cumulative sets match the active-minus-frozen and frozen-minus-active differences
exactly. Route-owner checks reject ambient runtime/session fields, `ThreadLocal`, companions,
`INSTANCE`, and `AutoCloseable` ownership.

The in-test JDK compiler used `-classpath <configured-root-jar>:<external-compat-classpath>` and a
fresh output directory for every consumer. It compiled the positive Java control
`RunbookExeKt.executeRunbook(Runbook, Map)` and rejected both external-package and same-package Java
attempts to call list/runbook runtime overloads or the session helper. Same-package attempts cover
the complete literal Task 5 anonymous-owner graph plus `DefaultResourceScope` and the resource-scope
implementation; failures are ownership/visibility diagnostics, with the synthetic helper proven
absent behind the preserved two-argument adapter signature.

## ABI evidence

The active and frozen ABI files are byte-identical to the Task 5 base:

```text
active Kotlin ABI SHA-256: d2a4e5dd67cf05b3c94321a44f0828799b87cbe467c752b6f4886eb80d6b59ec
frozen Kotlin ABI SHA-256: 3cbe0a2168e4db655d60b49e8f00d66d8951aca9ab08d64176dca5bc72cbf4fa
frozen raw JVM SHA-256:    6ecd4fd73461ed3353148cbb34a2cea1f60ba3bed256af7ed4dfeacfdaac1d2f
```

`checkKotlinAbi`, both external compatibility compilers, and both independent ABI inventory tests
passed.

## Mutation proofs

Each mutation produced a targeted RED and was restored before the final gate:

1. List carry from the memoized immutable snapshot lost post-hook/typed values; the frozen-carry
   E2E test failed.
2. Runbook carry read after `assertAfter`; the next step observed the mutation (`99` instead of
   frozen `42`).
3. Vararg delegated to the public list facade; method-scoped bytecode routing failed.
4. The runbook adapter delegated to `ReVoman.revUp`; its exact runtime edges and no-ReVoman rule
   failed.
5. Runbook NoOp was installed; ambient context was incorrectly masked at coarse/assert boundaries.
6. Kick-boundary NoOp masking was removed; the ambient sink leaked into child execution.
7. One literal Task 6 raw row was removed; the raw-surface test reported that exact unexpected row.
8. `@JvmSynthetic` was removed from the list runtime overload; external and same-package javac
   controls compiled and failed their invisibility assertions.
9. The runtime anonymous implementation owner was omitted from the same-package matrix; the matrix
   completeness assertion reported exactly
   `ReVomanRuntimeKt$reVomanRuntime$1` missing.

## Final verification

```text
./gradlew :test \
  --tests '*ReVomanRuntimeTest' \
  --tests '*ExecutionSessionE2ETest' \
  --tests '*MultiKickEnvTypesE2ETest' \
  --tests '*Runbook*Test' \
  --tests '*RunbookExeStructureTest' \
  --tests '*ControlFlow*Test' \
  --tests '*Hook*Test' \
  --tests '*Ledger*Test' \
  --tests '*RunLog*Test' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  compileApiCompatibilityTestKotlin \
  compileApiCompatibilityTestJava \
  checkKotlinAbi spotlessCheck \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

BUILD SUCCESSFUL in 34s
SUCCESS: Executed 201 tests in 22.6s
38 actionable tasks: 38 executed

git diff --check
# exit 0, no output
```

Only existing compiler/deprecation warnings outside this Task 6 change were emitted.

## Self-review

- Reviewed the complete production diff and method-scoped classfile call graph. There is no public
  facade recursion, ambient session sharing, or new closeable ownership surface.
- Reviewed all success/failure boundaries: the child is closed before callbacks and assertions,
  the outer session always closes, and sink restoration occurs in `finally`.
- Confirmed `ExecutionSession.kt`, frozen ABI files, migration ledger, and Tasks 7/8 have no diff.
- Confirmed only the Task 6 plan, three production files, prescribed tests/gates, two fixtures, and
  this report are in scope.
- Concern: none. The helper's javac rejection necessarily reports wrong arity against the retained
  two-argument adapter because `@JvmSynthetic` removes the three-argument overload from Java
  resolution; the diagnostic assertions additionally prove the visible required/found signatures,
  so this is not accepted as an ownership barrier by itself.

## Files and commit

The scoped file list is the Task 6 plan, `ReVoman.kt`, `ReVomanRuntime.kt`, `RunbookExe.kt`, the
prescribed lifecycle/compatibility tests and gates, and
`pm-templates/v3/session-isolation/{a,b}.request.yaml`, plus this report.

Task commit: this report is committed with message
`refactor: route multi-kick execution through sessions` on base
`60b49d6a7a57506e59970df76e0f6dbbabcd5ac5`.

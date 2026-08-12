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
CS2_TASK6_RAW_JVM_ADDITIONS = 534 rows
CS2_TASK6_RAW_JVM_REMOVALS  = 447 rows
frozen JVM entries         = 6101
```

The literal cumulative sets match the active-minus-frozen and frozen-minus-active differences
exactly. Route-owner checks reject ambient runtime/session fields, `ThreadLocal`, companions,
`INSTANCE`, and `AutoCloseable` ownership.

The in-test JDK compiler used `-classpath <configured-root-jar>:<external-compat-classpath>` and a
fresh output directory for every consumer. It compiled the positive Java control
`RunbookExeKt.executeRunbook(Runbook, Map)` and rejected both external-package and same-package Java
attempts to call list/runbook runtime overloads or the uniquely named
`executeRunbookInSession(ExecutionSession, Runbook, Map)` helper. The helper attempts report exact
member-resolution diagnostics and explicitly reject `compiler.err.cant.apply.symbol`; javac can no
longer misidentify the preserved two-argument adapter as a wrong-arity candidate. Same-package
attempts also cover the complete literal Task 5 anonymous-owner graph plus `DefaultResourceScope`
and the resource-scope implementation.

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
- Concern: none. The session helper now has the unique name `executeRunbookInSession`; external and
  same-package javac attempts fail with exact member-resolution diagnostics and never with the
  wrong-arity `compiler.err.cant.apply.symbol` diagnostic.

## Files and commit

The scoped file list is the Task 6 plan, `ReVoman.kt`, `ReVomanRuntime.kt`, `RunbookExe.kt`, the
prescribed lifecycle/compatibility tests and gates, and
`pm-templates/v3/session-isolation/{a,b}.request.yaml`, plus this report.

Task commit: this report is committed with message
`refactor: route multi-kick execution through sessions` on base
`60b49d6a7a57506e59970df76e0f6dbbabcd5ac5`.

## Formal-review fix round 1

### Findings addressed

The round closed three of the four Important proof gaps from the formal review. It strengthened the
routing checks but did not cover the runtime single and list bodies, which remained open after the
scoped re-review:

1. Two real-sandbox public controls now overlap concurrent list calls with latches and nest a
   public list call inside a public runbook assertion. Behaviorally distinct environment,
   collection-variable, global, control-flow capture, sink, and thread values prove that each
   public call owns independent state without a mutable observer.
2. The Kotlin-only session helper is uniquely named `executeRunbookInSession`. External-package and
   same-package Java attempts fail with exact member-resolution diagnostics; both reject
   `compiler.err.cant.apply.symbol`, while the two-argument compatibility adapter still compiles.
3. The routing assertions compare complete method-scoped routing sequences for the runbook body,
   adapter, helper, runbook runtime body, generated overloads, default dispatchers, and all four
   public primaries. The runtime single and list bodies were not asserted and remained open.
4. The three-step runbook lifecycle test gives its third configured kick a non-carried template
   identity. The expected child-create, execute, and close sequence must end in `third`, so carry
   cannot overwrite the marker and make a duplicate second occurrence masquerade as the third.

### RED and mutation evidence

The reviewed implementation already had independent public sessions, so the isolation tests are
positive controls rather than fabricated behavioral REDs. Each strengthened proof was checked by
a targeted mutation and restored before the final gate:

1. Replacing the third runbook kick identity with the duplicate identity failed `runbook owns one
   session and distinct sequential children for duplicate kicks` at the exact third create,
   execute, and close events.
2. Replacing the session helper's sole `executeStep` route with a helper self-call failed
   `runbook compatibility adapter and synthetic session helper keep exact routing` on the complete
   method-scoped invocation sequence.
3. Routing the runtime runbook overload through `ReVoman.revUp` failed `runtime runbook
   implementation invokes only the session helper` on the unexpected public-facade edge.
4. Routing the vararg primary through the public list overload failed `public overloads dispatch
   only through matching defaults and runtime descriptors` on the extra `ReVoman.revUp` edge.
5. Removing `@JvmSynthetic` from `executeRunbookInSession` made the adversarial Java consumer
   compile and failed the external and same-package invisibility controls. Restoring the annotation
   restored exact `compiler.err.cant.resolve.location.args` diagnostics.

The helper rename changed four compiler-emitted inline-log owners. The raw gate exposed 16 member
rows for the new owners and 16 rows for the old owners. Those literal rows were added to the
cumulative exact sets, which now contain 534 additions and 447 removals. Both independent complete
raw-delta comparisons pass; no count, prefix, or superset shortcut is used.

### Restored GREEN

```text
./gradlew :test \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  --tests '*RunbookExeStructureTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

BUILD SUCCESSFUL in 32s
SUCCESS: Executed 26 tests in 4.7s

./gradlew :test \
  --tests '*ExecutionSessionE2ETest' \
  --tests '*MultiKickEnvTypesE2ETest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

BUILD SUCCESSFUL in 25s
SUCCESS: Executed 16 tests in 11.9s
```

The proportional Task 6 gate then ran the runtime, lifecycle, multi-kick, runbook, control-flow,
hook, ledger, logging, raw-surface, and routing tests together with both external compatibility
compilers, `checkKotlinAbi`, and `spotlessCheck`:

```text
BUILD SUCCESSFUL in 51s
SUCCESS: Executed 204 tests in 29.1s
38 actionable tasks: 38 executed

git diff --check
# exit 0, no output
```

Only the existing Kotlin compiler and deprecation warnings were emitted.

### Fix-round self-review

- IntelliJ semantic navigation resolves the runtime call directly to
  `executeRunbookInSession`, with only its runtime import and invocation as code references;
  closed-batch diagnostics report no errors in `ReVomanRuntime.kt`.
- Exact compiled bytecode proves the helper, adapter, runbook runtime body, and public facade
  routing. The two independent raw inventories prove the 16 renamed-owner rows on each side are
  compiler-derived literal rows.
- The latch controls prove concurrent and reentrant public isolation with real sandboxes and
  independent sinks. The corrected lifecycle sequence proves all three configured runbook
  identities survive carry.
- `ExecutionSession.kt`, frozen ABI files, the migration map, and the Task 7/8 plan sections have no
  diff. Scoped re-review left one open concern: the runtime single and list body sequences were not
  pinned.

Fix-round commit message: `test: close Task 6 isolation proof gaps`.

## Formal-review fix round 2

### Remaining finding closed

The scoped re-review of `26ac4dad9da6b2d7922cf9deab756fb6a36bbe82` found that
`RunbookExeStructureTest` selected only the runtime `execute(Runbook, Map)` body. Extra edges in
`execute(Kick)` and `execute(List, PostExeHook, Map)` could pass undetected.

The test now selects all three overloads by exact method name and descriptor and compares every
method-scoped invocation in order. The exact sequences include parameter checks, session creation,
child execution, and the close/suppression mechanics emitted by inline `useInternal`. The list
sequence also includes collection traversal, its `invokedynamic` callback site, result insertion,
and a separately pinned callback body that invokes only `PostExeHook.accept`. The runbook assertion
was expanded to the same complete standard. Any public `ReVoman` edge, runtime self-edge, missing
edge, or extra invocation fails exact equality.

The classfile parser now resolves each `invokedynamic` call site's exact name and descriptor and
rejects malformed nonzero reserved bytes. Production code, raw JVM sets, and Kotlin ABI files are
unchanged.

### Targeted RED and mutation evidence

Both mutations changed compiled method edges only; the focused test inspected the generated root
JAR and did not execute the recursive runtime path.

1. The runtime single body temporarily called `ReVoman.revUp(kick)` before
   `session.executeKick`. `runtime single implementation pins one session child and close route`
   failed on the exact unexpected static edge:

   ```text
   com/salesforce/revoman/ReVoman.revUp
   (Lcom/salesforce/revoman/input/config/Kick;)Lcom/salesforce/revoman/output/Rundown;

   FAILURE: Executed 6 tests in 1.5s (1 failed)
   BUILD FAILED in 12s
   ```

2. After restoring the single body, the runtime list body temporarily called `execute(kick)`
   inside its map. `runtime list implementation pins one session child callback and close route`
   failed on the exact unexpected virtual self-edge:

   ```text
   com/salesforce/revoman/internal/runtime/ReVomanRuntimeKt$reVomanRuntime$1.execute
   (Lcom/salesforce/revoman/input/config/Kick;)Lcom/salesforce/revoman/output/Rundown;

   FAILURE: Executed 6 tests in 1.6s (1 failed)
   BUILD FAILED in 11s
   ```

Both production mutations were restored before GREEN verification; `git diff` contains no
production file.

### Restored GREEN

```text
./gradlew spotlessApply :test \
  --tests '*RunbookExeStructureTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

BUILD SUCCESSFUL in 13s
SUCCESS: Executed 6 tests in 1.4s
```

The affected Task 6 gate then ran both raw inventories, JVM visibility, all six structure tests,
both external compatibility compilers, Kotlin ABI, and formatting:

```text
./gradlew :test \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  --tests '*RunbookExeStructureTest' \
  checkKotlinAbi \
  compileApiCompatibilityTestKotlin \
  compileApiCompatibilityTestJava \
  spotlessCheck \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

BUILD SUCCESSFUL in 16s
SUCCESS: Executed 28 tests in 4.1s
38 actionable tasks: 38 executed
```

Only the existing Kotlin compiler and deprecation warnings were emitted. The real-sandbox suites
were not rerun because the fix changes only the structure test, Task 6 plan, report, and local
ledger.

### Fix-round self-review

- The exact single sequence permits one session open, one `executeKick$default`, and only the
  emitted close/suppression calls after standard parameter and empty-map setup.
- The exact list sequence permits one session open, the emitted collection mechanics, one callback
  call site, one `executeKick`, result insertion, and close/suppression calls. Its callback body
  permits only parameter checks and `PostExeHook.accept`.
- The exact runbook sequence permits one session open, `executeRunbookInSession`, and the emitted
  close/suppression calls. No runtime body can add a public facade or runtime self-edge.
- Production, raw JVM sets, Kotlin ABI files, and Task 7/8 sections are unchanged. Concern: none.

Fix-round commit message: `test: pin every Task 6 runtime route`.

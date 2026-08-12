# Performance Change Set 2a — Runtime Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce one explicit `ExecutionSession` per outer public execution and one deterministic `KickExecution` per kick, remove the unused legacy JavaScript evaluator/second Graal context, retain the immutable Postman boot `Source` strongly, and preserve all current execution semantics while establishing focused internal seams for later performance change sets.

**Architecture:** Keep `ReVoman` as the small public facade and move orchestration behind an internal `ReVomanRuntime`. Each facade call opens exactly one lexical `ExecutionSession`; list and runbook paths call `ExecutionSession.executeKick` directly and never recurse through a public `revUp(Kick)`. A `KickExecution` owns kick-local scopes, capture, exchange/progress adapters, and a lazy real `PmSandbox`, closing them in reverse order with primary/suppressed failure semantics. Process-wide reuse remains limited to the immutable Graal `Engine` and one strongly retained boot `Source`. `PostmanSDK` becomes an internal, explicitly transitional composition adapter over focused state components; it no longer owns lifecycle or any evaluator/context.

**Tech Stack:** Kotlin 2.4.20-Beta2, Java 21, Gradle 9.7 JVM Test Suites, Kotlin Gradle Plugin built-in ABI validation, GraalVM Polyglot 25.2.4, Kotest/JUnit 5/Truth, the existing standalone benchmark driver, Antora, Detekt, Spotless, and Qodana.

## Global Constraints

- Preserve the approved public behavior until its owning later change set: CS2b owns step identity/HTTP DTOs, CS2c owns `JsonCodec`/`FrozenValue`, CS2d owns sink capabilities, CS3 owns journals/sandbox budgets, CS4 owns `RunProgress`/immutable final reports, CS5 owns polling, and CS6 owns `ExecutionInputs`.
- Do not introduce placeholder production types named `RunProgress`, `JsonCodec`, `ExecutionInputs`, `StepEnvironmentEffects`, `SandboxBudget`, or the future logging/polling contracts in CS2a.
- The session boundary is lexical and explicit. Do not add a session `ThreadLocal`, coroutine context, ambient singleton, or implicit lookup.
- Every public `revUp` call creates a fresh session. A public reentrant call made by user code inside a hook therefore creates an independent session.
- A list or runbook creates one session and one `KickExecution` per kick. Only the finalized environment carries between kicks; collection variables, globals, capture state, sandbox guest globals, and every other kick resource start fresh.
- Preserve merge precedence exactly: ledger values are the floor, configured environment sources are next, the kick's dynamic environment overlays them, and the previously carried environment wins over the next kick's own dynamic values.
- For list execution, invoke `PostExeHook` after adding the current rundown to the accumulated snapshot and before capturing the carried environment, so hook environment mutations carry. Pass a frozen list snapshot to the hook, not a live builder.
- Preserve runbook semantics: carry the kick's final environment before runbook contract/assertion checks, as the current implementation does; an assertion failure still aborts the outer session.
- Keep caller-supplied `RunLogSink` instances borrowed. CS2a may preserve the existing `RunLogContext` implementation, but session state itself must not use it.
- Keep `Kick.nodeModulesPath` source-compatible in CS2a because the approved Postman-sandbox design explicitly retained it. Deprecate and document it as ignored, remove its evaluator consumption, and do not restore filesystem CommonJS loading.
- Remove `PostmanSDK.evaluateJS`, `jsonStrToObj`, nested `JSEvaluator`, their host `Request`/`Response` JSON bridge, and the second Graal context. Do not replace them with a second evaluator or an early partial codec.
- The only JavaScript execution path is the real `PmSandbox`. No-script kicks must not instantiate a sandbox bridge or context.
- Retain exactly one immutable boot `Source` strongly for the JVM lifetime alongside the shared immutable `Engine`; never retain a `Context` process-wide and never pool contexts.
- Cleanup is idempotent, reverse-order, and failure-safe: the body failure remains primary and cleanup failures are suppressed in deterministic order. `KickExecutionFactory.create` is transactional: it either returns one fully handed-off child whose expensive resources remain lazy, or rolls back every resource it opened before throwing. The session closes only successfully returned children.
- Keep ordinary hot-path logging unchanged in CS2a. Add lifecycle diagnostics through tests/structural evidence, not new per-step INFO logs.
- Use Context7 for version-sensitive Kotlin/Gradle syntax. Use the `idea` and `intellij-index` MCP servers for definitions/references/refactors when available; if enterprise policy disables them, record that fact and use `rg` plus compiler evidence. For runtime failures, use `superpowers:systematic-debugging` and the JetBrains debugger skill first; JDWP is fallback only.
- Work only in `.worktrees/performance-cs2a-lifecycle`. Preserve the unrelated untracked `.superpowers/` and `docs/revoman-graphalow-licensing-brief.md` in the primary checkout.
- Each task below is a separate reviewed commit. Before any claim of completion, run the task's focused gate, the final full gates, `git diff --check`, and an independent Standards + Spec review.
- Before Task 1, independently review and commit this exact plan as
  `docs: plan CS2a execution lifecycle`; all later clean-tree and detached-SHA assertions therefore
  include the plan itself. Do not leave this plan untracked until Task 8 or fold it into a measured
  implementation/evidence commit.

---

### Task 1: Freeze the pre-CS2 public surface and add consumer compile gates

**Files:**
- Modify: `build.gradle.kts`
- Add: `api/revoman-root.api`
- Add: `api/cs2-baseline-revoman-root.api`
- Add: `api/cs2-baseline-revoman-root.jvm.tsv`
- Add: `api/cs2-migration-map.tsv`
- Add: `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceInventory.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`
- Add: `src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinHooksPicksAndDynamicApiFixture.kt`
- Add: `src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaHooksPicksAndDynamicApiFixture.java`
- Add: `src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinPollingApiFixture.kt`
- Add: `src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaPollingApiFixture.java`
- Add: `src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinEnvironmentAndTxnApiFixture.kt`
- Add: `src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaEnvironmentAndTxnApiFixture.java`
- Add: `src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinRunLogSinkApiFixture.kt`
- Add: `src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaRunLogSinkApiFixture.java`
- Add: `src/apiCompatibilityTest/kotlin/org/example/revoman/consumer/KotlinLedgerAndRunbookApiFixture.kt`
- Add: `src/apiCompatibilityTest/java/org/example/revoman/consumer/JavaLedgerAndRunbookApiFixture.java`
- Add: `docs/modules/ROOT/pages/migration-guide.adoc`
- Modify: `docs/modules/ROOT/nav.adoc`
- Modify: `docs/superpowers/specs/2026-08-09-performance-redesign-design.md`

**Interfaces:**
- Consumes: current public Kotlin/JVM ABI at `ea3a4da2`, the documented Java builder/static interop surface, and the approved major-version migration table.
- Produces: generated current ABI reference `api/revoman-root.api`, immutable pre-CS2 Kotlin baseline `api/cs2-baseline-revoman-root.api`, immutable pre-CS2 JVM member/flag inventory `api/cs2-baseline-revoman-root.jvm.tsv`, a machine-checkable whole-redesign migration map, source-level Kotlin/Java consumer compilation gates, and the living migration guide.

- [ ] **Step 1: Add the missing-inventory RED tests and consumer fixtures**

Add `ApiBaselineInventoryTest` before enabling ABI validation. It must fail because both checked-in dumps are absent, then later assert:

```kotlin
private val requiredBaselineSymbols =
  listOf(
    "public final class com/salesforce/revoman/ReVoman",
    "public final class com/salesforce/revoman/internal/postman/PostmanSDK",
    "com/salesforce/revoman/internal/postman/PostmanSDK\$JSEvaluator",
    "com/salesforce/revoman/internal/postman/PostmanSDK\$Variables",
    "com/salesforce/revoman/internal/postman/PostmanSDK\$Request",
    "com/salesforce/revoman/internal/postman/PostmanSDK\$Response",
    "com/salesforce/revoman/internal/postman/PostmanSDK\$Xml2Json",
    "com/salesforce/revoman/internal/postman/Info",
    "com/salesforce/revoman/internal/postman/RegexReplacer",
    "public abstract interface class com/salesforce/revoman/input/PostExeHook",
    "public abstract interface class com/salesforce/revoman/output/log/RunLogSink",
  )
```

Read the generated text rather than duplicating the whole ABI in test strings. Apply `requiredBaselineSymbols` only to the frozen baseline. Assert that baseline's SHA-256 equals a committed constant and that it contains the complete `PostmanSDK` container/nested types; the active reference must merely exist and be nonempty because later intentional major-version changes remove those symbols from it. Perform a one-time byte comparison between the initial active reference and frozen copy while creating this commit; do not permanently require equality.

Create paired Java/Kotlin fixture classes in the external package `org.example.revoman.consumer`. They are compile fixtures, not friend-path tests. Configure their compile classpaths from the resolved root `jar` plus its external runtime dependencies, not `implementation(project(":"))`, project class directories, or a Kotlin friend path. Assert the Kotlin compiler arguments contain no `-Xfriend-paths`, and make both compile tasks depend on `jar`. Between the pairs, compile real examples for:

- pre/post hooks and picks;
- custom dynamic-variable generators;
- polling request/completion callbacks;
- `PostmanEnvironment` reads/writes/copy helpers and `TxnInfo` typed access;
- a custom `RunLogSink` implementation;
- `LedgerSnapshot`/`LedgerEntry`; and
- `Runbook.configure()` plus `RunbookRundown` list delegation.

Pin Java-specific behavior: use `TxnInfo.getTypedTxnObj(Type)`, receiver-first companion statics such as `TxnInfo.getURIPath(info)`, `Runbook.configure()`, and `RunbookRundown.size()/get()/stream()`. Do not add implementation-only dependencies merely to make the fixtures compile.

- [ ] **Step 2: Run the focused RED**

Run the inventory test first:

```bash
./gradlew :test --tests '*ApiBaselineInventoryTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: the test fails because `api/revoman-root.api` and its frozen baseline do not exist. Separately run:

```bash
./gradlew compileApiCompatibilityTestKotlin compileApiCompatibilityTestJava \
  --no-build-cache --no-configuration-cache --console=plain
```

Expected: Gradle reports those tasks do not exist until the JVM suite is registered. Keeping these probes separate ensures task-graph selection cannot prevent the inventory test from running. Fix fixture syntax only; do not add production lifecycle code.

- [ ] **Step 3: Enable KGP built-in ABI validation with the current Kotlin syntax**

Use the Context7-verified Kotlin 2.4 form in the root build only:

```kotlin
kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation()
}
```

Do not use removed `enabled.set(true)` syntax, and do not put this in `revoman.kt-conventions` because that would gate `benchmark-driver` too. The verified task/path contract is:

- `internalDumpKotlinAbi` writes `build/kotlin/abi/revoman-root.api`;
- `updateKotlinAbi` updates `api/revoman-root.api`;
- `checkKotlinAbi` compares them and is automatically attached to `check`.

Generate the active dump with `./gradlew updateKotlinAbi`, then create the immutable baseline copy from those exact bytes. Prove the initial copy with `cmp api/revoman-root.api api/cs2-baseline-revoman-root.api`. Later tasks may regenerate only `api/revoman-root.api`; never rewrite the frozen baseline.

In the same commit, add one deterministic classfile reader shared by `JvmSurfaceVisibilityTest` and
a root `freezeCs2JvmSurface` `JavaExec` task. It records every class entry and every declared field,
constructor, and method from the built root JAR, regardless of visibility, as a canonical TSV row containing owner, kind,
name, JVM descriptor, owner/member access flags, and `ACC_SYNTHETIC`/`ACC_BRIDGE` state. It also
derives whether an external Java source consumer can name the owner and invoke/read the member;
synthetic members are not source-callable. Sort by the full row under `Locale.ROOT`, write UTF-8
with LF endings, reject duplicates, and make the freeze task refuse to overwrite an existing file.
Run it once at `ea3a4da2` to create `api/cs2-baseline-revoman-root.jvm.tsv`, commit that file's
SHA-256 as a test constant, and have every later comparison extract the active inventory directly
from the current built JAR. Never infer Java visibility from the Kotlin ABI dump and never rewrite
the frozen JVM inventory after Task 1.

- [ ] **Step 4: Register the external consumer suite**

Register the suite without a project dependency, then replace its source set's classpaths with the
built root archive plus the root project's external runtime artifacts:

```kotlin
register<JvmTestSuite>("apiCompatibilityTest") {
  // Compile fixtures only; no implementation(project(":")) and no association with main.
}

val rootApiJar = tasks.named<Jar>("jar")
sourceSets.named("apiCompatibilityTest") {
  val externalRuntime = configurations.named("runtimeClasspath")
  compileClasspath = files(rootApiJar) + externalRuntime.get()
  runtimeClasspath = output + compileClasspath
}
tasks.named("apiCompatibilityTestClasses") { dependsOn(rootApiJar) }
```

Wire `check` to `apiCompatibilityTestClasses`. Assert the resolved compile classpaths contain exactly
the root JAR plus external artifacts, contain neither root project class directories nor another
project output, and that the Kotlin compile task has no `-Xfriend-paths`. Do not associate this
compilation with `main`; it must consume the built archive like an external project. Keep the
fixtures split by domain so CS2b/CS2c/CS2d can update independent files after they fan out.

In Task 1—not later—also make the root `test` task depend on `rootApiJar`, declare the archive as an
input, and pass its provider-resolved absolute path as the dedicated
`revoman.compat.rootJar` system property. `JvmSurfaceVisibilityTest` and the inventory reader must
fail if that property is absent, points at classes/a directory, or differs from the exact `jar`
task output. Reuse this wiring for every later structural test; never infer the archive with a glob.

Add `JvmSurfaceVisibilityTest` as a built-JAR gate. It receives the exact root archive path from a Gradle provider, invokes the JDK compiler against negative Java snippets, and inspects class/member access flags from that archive. In Task 1 it records the intentional legacy reachability of `PostmanSDK`/`RegexReplacer`; Tasks 2 through 8 tighten the expected surface. Kotlin `internal` alone is not evidence because it commonly emits JVM-public bytecode. The final rule is that the transitional aggregate and new lifecycle/state types expose no Java-source-callable operational constructor, factory, field/property, or method. Implement them as internal interfaces whose callable members are `ACC_SYNTHETIC`, synthetic top-level Kotlin factories, and implementation classes that are either file-private or function-local anonymous when same-package Java could otherwise reach a package-private class; do not use companion-object factories or public singleton fields. The classfile test—not the annotation choice—is authoritative and rejects every unlisted type, bridge, field, constructor, or nonsynthetic public callable. Task 7 adds the separately reflection-bound synthetic diagnostics descriptor to that explicit allowlist.

- [ ] **Step 5: Establish the migration ledger and approve the written design**

Create `api/cs2-migration-map.tsv` with unique rows and the columns `kind`, `legacyId`, `owner`, `disposition`, and `replacementId`. `kind` is `kotlin`, `java`, `json`, or `behavior`; `owner` is one of `CS2a`, `CS2b`, `CS2c`, `CS2d`, `CS3`, `CS4`, `CS5`, or `CS6`. Create `migration-guide.adoc` as the human rendering of that ledger using valid AsciiDoc `|===` tables—not Markdown pipe-table syntax.

Before any CS2 runtime change, populate the ledger for every approved umbrella migration row, including hooks/picks/dynamic generators/polling callbacks, direct `PostmanEnvironment` construction and helpers, `Step.rawPMStep`, `StepEnvVars`, step/ledger identity, `TxnInfo`/live HTTP/Moshi retention, `PollingReport.responses`, raw `InputStream` configuration, per-call Moshi mutation, execution history, file flushing/footer behavior, `RunLogSink` capabilities, and every version-2 Rundown/RunbookRundown/polling/ledger serialized field. At minimum include explicit JSON rows for `StepReport.pmEnvSnapshot`, `PollingReport.responses`, legacy environment snapshot fields, and the future root `schemaVersion`.

Expand the CS2a rows to every public baseline declaration that becomes internal or disappears: `PostmanSDK`, all public constructors/properties/helpers, `Info`, `RegexReplacer`, nested `JSEvaluator`/`Variables`/`Request`/`Response`/`Xml2Json`, `evaluateJS`, and `jsonStrToObj`. Normalize each removed Kotlin ABI declaration and Java descriptor to an exact `legacyId`. Compiler-generated `$default`, `DefaultImpls`, synthetic accessor, and bridge members may be grouped under their exact declared owner only when the extractor proves `ACC_SYNTHETIC`/`ACC_BRIDGE`; every generated member must resolve to one ledger row and no orphan grouping is allowed. Seed their user-facing summary with this AsciiDoc shape:

```asciidoc
[cols="1,1,2",options="header"]
|===
|Legacy surface |CS2a disposition |Replacement
|`PostmanSDK.evaluateJS` |removed |Real `PmSandbox`; `JsonPojoUtils` for host decoding until CS2c
|`PostmanSDK.jsonStrToObj` |removed |`JsonPojoUtils.jsonToPojo`, then immutable `JsonCodec` in CS2c
|`PostmanSDK.JSEvaluator` and host bridge types |removed |No host evaluator; collection scripts retain real Postman APIs
|`Kick.nodeModulesPath` |deprecated and ignored |Vendored Postman sandbox modules
|===
```

State that serialized output is unchanged in CS2a and link the page in `nav.adoc`. Extend
`ApiBaselineInventoryTest` to parse the TSV, reject duplicate/blank/unknown-owner rows, and require
every known CS2a baseline symbol plus every approved serialized-field migration. Define two exact
removal projections: `owner=CS2a, kind=kotlin, disposition in {removed,internalized}` for normalized
Kotlin ABI declarations, and the corresponding `kind=java` rows for the frozen baseline's
Java-source-callable member keys (owner, kind, name, descriptor, and relevant access flags). Task 8
requires Kotlin `baseline - active` and Java-source-callable `baseline - active` equality
independently against those projections. A descriptor retained as `ACC_SYNTHETIC` is absent from the
active Java-source-callable set even though it remains in raw bytecode. Also require the supported
Kotlin `active - baseline` set and Java-source-callable member `active - baseline` set to be empty;
CS2a adds no supported public API. Compare the complete raw active JAR inventory separately and
require every new internal type, file-private or function-local implementation, synthetic bridge/factory, and Task 7
diagnostics descriptor to equal one explicit addition allowlist, with no `Companion` field/class,
public singleton field, or orphan generated member. Apply the explicit generated-member grouping
rule only after proving `ACC_SYNTHETIC`/`ACC_BRIDGE`. Retained/deprecated behavior rows such as `Kick.nodeModulesPath`
and every `json`/other `behavior` row are validated against their separate structural or schema
invariant and are never compared to an ABI-removal set. This makes undocumented removals, stale
removal rows, and stale non-ABI contracts fail without comparing incompatible domains. Change the
umbrella spec status to `Approved; implementation in progress` without changing settled decisions.

- [ ] **Step 6: Verify and commit the compatibility foundation**

Run:

```bash
./gradlew checkKotlinAbi \
  :test \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  compileApiCompatibilityTestKotlin compileApiCompatibilityTestJava \
  spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: both languages compile without friend paths, the baseline test passes, and `checkKotlinAbi` is green. Commit:

```bash
git add build.gradle.kts api src/apiCompatibilityTest \
  src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceInventory.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  docs/modules/ROOT docs/superpowers/specs/2026-08-09-performance-redesign-design.md
git commit -m "build: freeze major-version public API surface"
```

### Task 2: Add deterministic resource ownership primitives

**Files:**
- Modify: `docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/InternalCloseable.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ResourceScope.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/SandboxRuntime.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/ResourceScopeTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/SandboxRuntimeTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`

**Interfaces:**
- Consumes: Kotlin's primary/suppressed exception semantics and current `PmExecutionContext`/`PmExecutionResult` sandbox DTOs.
- Produces: a small LIFO resource owner and the kick-owned sandbox port/factory used by `KickExecution`.

- [ ] **Step 1: Write failure-order and idempotence tests first**

Cover all of these cases before production exists:

1. resources close once in reverse registration order;
2. an empty scope closes cleanly;
3. repeated `close()` is a no-op;
4. on success, the first close failure becomes primary and later failures are suppressed in reverse-close order;
5. when a body already failed, every close failure is appended to that body failure without replacement; and
6. registration after close fails immediately and closes the newly offered resource so it cannot leak.

Use named fake closeables and assert exact primary/suppressed identities, not only messages.

- [ ] **Step 2: Capture the missing-type RED**

Run:

```bash
./gradlew :test \
  --tests '*ResourceScopeTest' \
  --tests '*SandboxRuntimeTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: `compileTestKotlin` fails on absent `ResourceScope`, `SandboxRuntime`, and `SandboxFactory`.

- [ ] **Step 3: Implement the deep resource owner**

Use a narrow interface:

```kotlin
internal interface InternalCloseable {
  @JvmSynthetic fun close()
}

@JvmSynthetic
internal inline fun <T : InternalCloseable, R> T.useInternal(block: (T) -> R): R

internal interface ResourceScope : InternalCloseable {
  @JvmSynthetic fun <T : InternalCloseable> own(resource: T): T
  @JvmSynthetic fun closeAfter(primary: Throwable?): Throwable?
  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun resourceScope(): ResourceScope
```

Store only owned internal closeables in an `ArrayDeque`, close with `removeLast()`, and centralize
exception aggregation in `closeAfter`. `close()` throws the returned failure only when there was no
prior primary. Do not implement `java.lang.AutoCloseable`: its inherited public `close()` would
defeat the raw-JAR Java boundary. Return a function-local anonymous `ResourceScope` implementation
from the synthetic top-level factory. A top-level Kotlin `private` implementation becomes a
package-private JVM class that same-package Java can construct and operate; the anonymous class
instead has no Java source name and a non-source-callable constructor. The built-JAR gate must try
both the former named implementation and the compiler-emitted anonymous binary name from a Java
source in `com.salesforce.revoman.internal.runtime`. Every interface callable remains synthetic, so
Kotlin in this module can use the owner while javac cannot construct or operate it. Do not use a companion object:
its public nonsynthetic `Companion` field would violate the Java-source surface gate. `useInternal`
mirrors Kotlin `use` exactly for body-primary and suppressed-close
semantics; test both paths before replacing the current sandbox call site. Do not log/swallow
cleanup failures or generalize this into a service locator.

- [ ] **Step 4: Define the sandbox port at the kick boundary**

Add:

```kotlin
internal interface ScriptExecutor {
  @JvmSynthetic
  fun execute(
    script: String,
    target: ScriptTarget,
    context: PmExecutionContext,
    timeoutMs: Long = SANDBOX_DEFAULT_TIMEOUT_MS,
  ): PmExecutionResult
}

internal interface SandboxRuntime : ScriptExecutor, InternalCloseable

internal fun interface SandboxFactory {
  @JvmSynthetic
  fun create(): SandboxRuntime
}
```

Define file-private `SANDBOX_DEFAULT_TIMEOUT_MS = 60_000L` beside the port. `ScriptExecutor` is a
regular interface because the repository's Kotlin 2.4.20-Beta2 compiler rejects default arguments
on a `fun interface` abstract method; this retains the inherited three-argument call while
`SandboxFactory` remains a functional interface. `ScriptExecutor` is the non-owning invocation port passed to script orchestration;
`SandboxRuntime` adds the owned close boundary. This is a real ownership/test seam, not a generic
repository abstraction. It must mirror `PmSandbox` exactly and introduce no future `SandboxBudget`
policy.

Extend `JvmSurfaceVisibilityTest` now: from the built root JAR, javac may name the five boundary
interface types but must be unable to call their synthetic operations, construct an implementation
through the provided synthetic factory/function-local anonymous implementation, use the synthetic default
bridge, or treat `SandboxFactory` as a Java SAM.
Classfile inspection must prove every Kotlin-only interface/top-level callable is synthetic and
that each implementation constructor is non-source-callable through the combined source-name and
owner/member access facts and exactly present in the raw addition allowlist. Mutation-test one
removed `@JvmSynthetic` and one named or widened implementation boundary before restoring.

Keep the pre-CS2 JVM baseline immutable. Record every Task 2 class, member, file facade, default
bridge, and implementation row as its exact ten-column `JvmSurfaceEntry.render()` value in one
shared cumulative raw-JAR addition allowlist consumed by both compatibility tests. Frozen rows may
not disappear. Supported Kotlin additions and Java-source-callable operational member additions
remain empty; source-nameable class rows alone are not operational member additions. Removing one
raw allowlist row must fail the gate.

- [ ] **Step 5: Verify and commit**

Run:

```bash
./gradlew checkKotlinAbi :test \
  --tests '*ResourceScopeTest' \
  --tests '*SandboxRuntimeTest' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: every focused test and formatting check passes. Commit:

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/runtime \
  src/test/kotlin/com/salesforce/revoman/internal/runtime \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt \
  src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md
git commit -m "refactor: add execution resource ownership"
```

### Task 3: Prepare the real sandbox for kick ownership and retain the boot Source strongly

**Files:**
- Modify: `docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResources.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickExecution.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/KickExecutionTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxBootTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEngineSharingTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResourcesTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`

**Interfaces:**
- Consumes: `ResourceScope`, `SandboxRuntime`, the current shared `Engine`, and the checked-in Postman sandbox resources.
- Produces: the initial closeable `KickExecution` resource owner, a lazy per-kick sandbox/context, and one strongly held immutable boot `Source` per process. Task 5 adds its one-shot body execution state.

- [ ] **Step 1: Add lazy-lifecycle and source-identity tests**

Write `KickExecutionTest` against an injected counting `SandboxFactory`. Assert:

- construction and reading/passing the `scripts` executor call the factory zero times;
- the first `scripts.execute` invocation creates one runtime and repeated phases reuse it;
- two kick executions receive distinct runtimes;
- close before first access does not create a runtime;
- close after access closes once; and
- `scripts.execute` after close fails without reopening resources.

Extend `SandboxResourcesTest` to assert `SandboxResources.bootSource === SandboxResources.bootSource`, the source name includes the packaged sandbox version, and the resource bytes are read/built once. Extend engine/isolation tests so two real bridges share the `Engine` and `Source` but cannot observe each other's guest globals.

Add a deterministic `PmSandboxBootTest` case that injects a failure immediately after a real
`Context` has been built but before bridge initialization completes. Assert that the context closes
exactly once. Add a closer-failure variant and require the injected boot failure to remain primary
with the close failure suppressed; a later owner `close()` is idempotent and adds nothing.

- [ ] **Step 2: Run the focused RED**

```bash
./gradlew :test \
  --tests '*KickExecutionTest' \
  --tests '*PmSandboxBootTest' \
  --tests '*SandboxEngineSharingTest' \
  --tests '*SandboxResourcesTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: missing `KickExecution`/`bootSource` contracts and identity assertions fail. Existing sandbox behavior remains the positive control.

- [ ] **Step 3: Retain the immutable boot Source**

In `SandboxResources` add:

```kotlin
@get:JvmSynthetic
internal val bootSource: Source by lazy {
  Source.newBuilder("js", bootcode, "postman-sandbox-$version.js").build()
}
```

Keep `bootcode`, `bridgeClient`, and `version` immutable JVM-lifetime resources. In `SandboxBridge.boot()`, evaluate `SandboxResources.bootSource` rather than constructing a local source. Update its KDoc to state that only the immutable `Engine` and `Source` are process-wide; each `Context`, event loop, bridge value, and emitted result remains kick-local.

- [ ] **Step 4: Adapt the real sandbox to the ownership port**

Make `PmSandbox : SandboxRuntime` and replace the existing `PmSandbox().use { ... }` call in
`ReVoman` with the Task 2 `useInternal` equivalent so main compilation does not depend on
`AutoCloseable`. Preserve lazy bridge boot and idempotent close. Put the `60_000 ms` default only on
`ScriptExecutor.execute`; Kotlin inherits that base default at an override call site, so the
existing three-argument `PmJsEval` call remains a compile-time positive control until Task 4 changes
its static type to `ScriptExecutor`. The `PmSandbox` override itself repeats the parameter without a
default. Introduce only the narrow bridge-construction/after-context/closer seams needed for
deterministic failure injection. `PmSandbox.close()` must always delegate to idempotent
`SandboxBridge.close()` rather than checking its own post-boot flag. If `SandboxBridge.boot()` fails
after context creation, close immediately in the same catch, keep the boot failure primary,
suppress a close failure, and leave later owner cleanup idempotent. Never rely on
`PmSandbox.booted`, because that flag is set only after a successful return.

Add the first `KickExecution` as a small one-shot resource owner:

```kotlin
internal interface KickExecution : InternalCloseable {
  @get:JvmSynthetic val scripts: ScriptExecutor
  @get:JvmSynthetic val sandboxInitialized: Boolean
  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun kickExecution(
  sandboxFactory: SandboxFactory = DEFAULT_SANDBOX_FACTORY,
): KickExecution
```

`scripts` is a lightweight delegating function object. Create and register the real sandbox only when `scripts.execute(...)` is invoked, never when `scripts` is read or passed into `PmJsEval`. Register a newly created sandbox in `ResourceScope` immediately. Task 4 adds the phase-level blank-script invocation test; Task 5 adds the first full public no-script orchestration test when it routes the body through this owner. Task 3 pins the lower-level lazy boundary. Do not add input, codec, progress, journal, polling, or logging ownership yet; later tasks extend this interface/function-local implementation pair only with real CS2a state. Extend the built-JAR gate so javac cannot construct `KickExecution`, read its properties, call `close`, invoke `ScriptExecutor`, or create either sandbox port; classfile inspection proves the exact function-local/synthetic shape and the absence of companion/singleton fields.

Return a function-local anonymous `KickExecution` implementation from the synthetic factory, and
use a function-local anonymous `ScriptExecutor` delegate. Do not introduce a top-level Kotlin
`private` implementation: Task 2 proved that it becomes package-private JVM bytecode that
same-package Java can construct and operate. Extend the same-package javac adversary to target the
compiler-emitted implementation binary names. Append every Task 3 class/member row to the shared
cumulative exact raw-JAR addition allowlist consumed by both compatibility tests. Moving the sole
default argument to `ScriptExecutor` necessarily removes the old synthetic, non-source-callable
`PmSandbox.execute$default` row. Retain `PmSandbox`'s existing private companion and
`DEFAULT_TIMEOUT_MS` field byte-for-byte, declare the no-default override `final`, and record that
one exact old bridge row in a shared cumulative raw-JAR removal allowlist. Both compatibility tests
must require `frozen - active` to equal exactly that set and `active - frozen` to equal exactly the
Task 2-3 addition set; supported Kotlin/Java operational additions and removals remain empty. Do not
add a migration-ledger row: this compiler bridge is synthetic/non-source-callable and outside the
supported-API removal projection.

- [ ] **Step 5: Verify and commit**

Run:

```bash
./gradlew checkKotlinAbi :test \
  --tests '*KickExecutionTest' \
  --tests '*PmSandbox*Test' \
  --tests '*SandboxEngineSharingTest' \
  --tests '*SandboxResourcesTest' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: the lazy factory/source identity and every existing sandbox test pass. Commit:

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox \
  src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
  src/main/kotlin/com/salesforce/revoman/internal/runtime/KickExecution.kt \
  src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox \
  src/test/kotlin/com/salesforce/revoman/internal/runtime/KickExecutionTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt \
  src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md
git commit -m "perf: retain sandbox boot source per process"
```

### Task 4: Split Postman execution state and remove the legacy evaluator

**Files:**
- Modify: `docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanVariableScopes.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/postman/StepScriptCapture.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/LegacyRundownProgress.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/RegexReplacer.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/DynamicVariableGenerator.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/Polling.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickExecution.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt` (remove stale two-context KDoc)
- Modify: `src/main/kotlin/com/salesforce/revoman/input/config/KickDef.kt` (deprecation/documentation only)
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanVariableScopesTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/postman/StepScriptCaptureTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/LegacyRundownProgressTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/LegacyEvaluatorRemovalTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/RegexReplacerTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/RegexReplacerConsumedTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/RegexReplacerScopesTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/DynamicVariableGeneratorTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/exe/PollingTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/exe/PmJsEvalScopesDiffTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/ScriptHookPhaseBarrierE2ETest.kt`
- Add: `src/test/resources/postman/phase-barrier/collection.postman_collection.json`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxApiCoverageTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceInventory.kt` (expose exact constant-pool class/member references)
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`
- Delete: `src/test/kotlin/com/salesforce/revoman/input/EvalJsTest.kt`
- Delete: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKEvalIsolationTest.kt`
- Delete: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKJsonStrToObjTest.kt`
- Replace: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKVariableScopesTest.kt`
- Replace: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKCollectionVariablesTest.kt`
- Replace: `src/test/kotlin/com/salesforce/revoman/internal/postman/PostmanSDKSyncProgressTest.kt`
- Modify: `docs/modules/ROOT/pages/scripts-and-pm-apis.adoc`
- Modify: `docs/modules/ROOT/pages/migration-guide.adoc`
- Modify: `api/revoman-root.api`

**Interfaces:**
- Consumes: current `PostmanEnvironment`, `Step`, mutable interim `Rundown`, real sandbox context/result DTOs, borrowed non-owning `ScriptExecutor`, and existing script phase barriers.
- Produces: focused scope/capture/legacy-progress modules; a bound `RegexReplacer` with no `PostmanSDK` operation/callback parameters; an internal transitional `PostmanSDK` wiring facade; complete removal of the legacy evaluator; and removal of the ignored node-module path's runtime consumption while retaining its deprecated source surface.

- [x] **Step 1: Move existing behavior assertions onto focused contracts before deleting anything**

Create tests with these exact responsibilities:

```kotlin
internal interface PostmanVariableScopes {
  @get:JvmSynthetic val environment: PostmanEnvironment<Any?>
  @get:JvmSynthetic val collectionVariables: PostmanEnvironment<Any?>
  @get:JvmSynthetic val globals: PostmanEnvironment<Any?>
  @get:JvmSynthetic @set:JvmSynthetic var environmentName: String?
  @JvmSynthetic fun contains(key: String): Boolean
  @JvmSynthetic fun resolve(key: String): Any?
  @JvmSynthetic fun ownerOf(key: String): PostmanEnvironment<Any?>?
}

@JvmSynthetic
internal fun postmanVariableScopes(
  environment: PostmanEnvironment<Any?>,
  collectionVariables: PostmanEnvironment<Any?>,
  globals: PostmanEnvironment<Any?>,
  environmentName: String?,
): PostmanVariableScopes

internal interface StepScriptCapture {
  @JvmSynthetic fun reset(step: Step)
  @JvmSynthetic fun recordAssertions(step: Step, assertions: List<PmTestAssertion>)
  @JvmSynthetic fun recordNextRequest(step: Step, value: String?, wasSet: Boolean)
  @JvmSynthetic fun recordSkipRequest(step: Step)
  @JvmSynthetic fun assertionsFor(step: Step): List<PmTestAssertion>
  @JvmSynthetic fun nextRequestFor(step: Step): String?
  @JvmSynthetic fun nextRequestWasSetFor(step: Step): Boolean
  @JvmSynthetic fun skipRequestFor(step: Step): Boolean
}

@JvmSynthetic internal fun stepScriptCapture(): StepScriptCapture

internal interface LegacyRundownProgress {
  @get:JvmSynthetic @set:JvmSynthetic var currentReport: StepReport
  @get:JvmSynthetic @set:JvmSynthetic var rundown: Rundown
  @get:JvmSynthetic @set:JvmSynthetic var currentRequestName: String
  @JvmSynthetic fun sync(report: StepReport)
}

@JvmSynthetic internal fun legacyRundownProgress(): LegacyRundownProgress

internal interface RegexReplacer {
  @JvmSynthetic fun replaceVariablesRecursively(stringWithRegex: String?): String?
  @JvmSynthetic fun replaceVariablesInPmItem(item: Item): Item
  @JvmSynthetic fun replaceVariablesInRequestRecursively(request: Request): Request
  @JvmSynthetic fun replaceVariablesInEnv(): Map<String, Any?>
}

@JvmSynthetic
internal fun regexReplacer(
  scopes: PostmanVariableScopes,
  progress: LegacyRundownProgress,
  customDynamicVariableGenerators: Map<String, CustomDynamicVariableGenerator>,
): RegexReplacer

internal interface PostmanSDK {
  @get:JvmSynthetic val scopes: PostmanVariableScopes
  @get:JvmSynthetic val capture: StepScriptCapture
  @get:JvmSynthetic val progress: LegacyRundownProgress
  @get:JvmSynthetic val regexReplacer: RegexReplacer
}

@JvmSynthetic
internal fun postmanSDK(
  scopes: PostmanVariableScopes,
  capture: StepScriptCapture,
  progress: LegacyRundownProgress,
  regexReplacer: RegexReplacer,
): PostmanSDK
```

These are authoritative Kotlin-only bytecode shapes, not merely Kotlin-`internal` declarations.
Each exact `@JvmSynthetic` top-level factory must return a function-local anonymous `object :
Interface { ... }`; do not declare a named top-level `Default*` implementation. Tasks 2 and 3
proved that a top-level Kotlin `private class` emits a package-private JVM owner with a callable
constructor, so it does not satisfy the same-package Java adversary. Annotate every callable and
property accessor `@JvmSynthetic` and keep them off Java interfaces with inherited operational
methods. Apply the anonymous-factory shape to `PostmanVariableScopes`, `StepScriptCapture`,
`LegacyRundownProgress`, transitional `PostmanSDK`, and `RegexReplacer`. Do not use companion or
named-object factories, and emit no `Companion` class/field or `INSTANCE` field.
`regexReplacer` has no injected/default function seam: its anonymous implementation calls the
focused top-level `dynamicVariableGenerator(key, progress)` directly. This pure dispatch does not
vary in production, and retaining `::dynamicVariableGenerator` would emit a callable-reference
singleton helper with an `INSTANCE` field. Exercise dynamic-variable results through
`RegexReplacerTest`/`DynamicVariableGeneratorTest`; do not inject the top-level function in tests.
`JvmSurfaceVisibilityTest` enumerates
`PostmanVariableScopes`, `StepScriptCapture`, `LegacyRundownProgress`, `PostmanSDK`, and
`RegexReplacer`; external and same-package negative javac snippets cannot construct an
implementation or read, write, or invoke an operation. Target the extractor-proven anonymous
binary names as Tasks 2 and 3 do; do not assert that a named `Default*` class is absent as a
substitute for testing the emitted implementations.

Preserve scope precedence, present-null behavior, loop/reset behavior, assertion accumulation, explicit `setNextRequest(null)` distinction, skip state, and the current one-entry progress replacement behavior. Keep `Step` keys until CS2b and the copied interim `Rundown` until CS4; label both classes transitional in KDoc.

Before removing evaluator tests, extend real-sandbox coverage for supported bundled `require(...)` modules (lodash, moment, and XML parsing) plus `pm.request.json()`/`pm.response.json()`. Add a counting/throwing `ScriptExecutor` control to `PmJsEvalScopesDiffTest` proving both pre-request and test phases return for absent/blank scripts without calling `execute`. These tests are the replacement behavior, not a host evaluator.

Add `ScriptHookPhaseBarrierE2ETest` around a deterministic JDK loopback HTTP fixture with at least
two collection steps. Step 1 must prove the complete ordered barrier: pre-request JS set/unset ->
pre-step hook observation and set/unset -> HTTP/post-response JS observation and set/unset ->
post-step hook observation and set/unset -> Step 2 pre-request JS observation of the final Step 1
state. Exercise environment, collection-variable, and global keys independently so no mutation can
pass through the wrong scope: carry environment set/unset mutations through every JS/hook barrier,
and mutate distinct collection/global sentinels in the scripts while asserting hooks and the next
script never observe them through the environment. Assert existing numeric normalization at a
JS/hook boundary. Use
separate ledger-control keys—one produced through the supported environment mutation path and one
consumed through regex resolution—and assert their exact produced/consumed sets independently from
the phase-visibility keys. This is the explicit phase-boundary regression suite; it must fail if the
extraction batches synchronization, skips an apply-back barrier, cross-contaminates scopes, changes
numeric normalization, or couples the produced and consumed controls.

- [x] **Step 2: Capture separate REDs for removal and the missing focused modules**

First add only the JAR/reflection-based `LegacyEvaluatorRemovalTest`; it must not statically
reference any absent focused type. Run it alone and capture an executed assertion failure on the
present evaluator surface:

```bash
./gradlew :test --tests '*LegacyEvaluatorRemovalTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Then add the direct focused-module tests from Step 1 and run their separate missing-type probe:

```bash
./gradlew :test \
  --tests '*PostmanVariableScopesTest' \
  --tests '*StepScriptCaptureTest' \
  --tests '*LegacyRundownProgressTest' \
  --tests '*PmJsEvalScopesDiffTest' \
  --tests '*ScriptHookPhaseBarrierE2ETest' \
  --tests '*PmSandboxApiCoverageTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: the first command executes and fails because it finds `PostmanSDK$JSEvaluator`,
`evaluateJS`, and `jsonStrToObj`; the second fails during test compilation because the focused
types are absent. Keep these as two distinct RED observations: do not let the compile failure mask
the executable JAR/reflection assertion failure. `nodeModulesPath` remains as a positive
source-compatibility control.

- [x] **Step 3: Extract state without changing executor behavior**

Move the three variable stores/name into `PostmanVariableScopes`, the four Step-keyed maps into `StepScriptCapture`, and request-name/current-report/interim-rundown synchronization into `LegacyRundownProgress`. Update `RegexReplacer`, dynamic-variable generation, and polling to request only the focused module they need. Make `RegexReplacer` Kotlin-`internal` in the same intentional ABI change; its public constructor currently exposes `PostmanSDK`, so leaving it public would either fail visibility checks or preserve the aggregate through an accidental unsupported API.

Construct and bind the graph in this order, with no cycle and no late `PostmanSDK` injection:

```kotlin
val environment: PostmanEnvironment<Any?> =
  PostmanEnvironment(
    mutableEnv = PersistentBackedMutableMap(ledgerValues + mergedEnv.values),
    moshiReVoman = moshiReVoman,
  )
val collectionVariables: PostmanEnvironment<Any?> =
  PostmanEnvironment(mutableEnv = mutableMapOf(), moshiReVoman = moshiReVoman)
val globals: PostmanEnvironment<Any?> =
  PostmanEnvironment(mutableEnv = mutableMapOf(), moshiReVoman = moshiReVoman)
val scopes =
  postmanVariableScopes(
    environment = environment,
    collectionVariables = collectionVariables,
    globals = globals,
    environmentName = mergedEnv.name,
  )
val progress = legacyRundownProgress()
val replacer =
  regexReplacer(
    scopes = scopes,
    progress = progress,
    customDynamicVariableGenerators = kick.customDynamicVariableGenerators(),
  )
val capture = stepScriptCapture()
val pm = postmanSDK(scopes, capture, progress, replacer)
```

Create these stores only after `moshiReVoman`, `ledgerValues`, and `mergedEnv` are available. The
environment store retains the current initial value semantics exactly—ledger values are the floor,
`mergedEnv.values` wins on collision, and the live map uses `PersistentBackedMutableMap`—while the
collection-variable and global stores are fresh empty mutable stores using the same
`PostmanEnvironment` constructor and `MoshiReVoman`. Pass `mergedEnv.name` into `scopes`; do not
assign the name through a later aggregate mutation.

The bound `RegexReplacer` operations take no `PostmanSDK`; recursive calls use its captured
`scopes`, custom generators receive `progress.currentReport` and `progress.rundown`, and the
`$currentRequestName` dynamic value reads `progress.currentRequestName`. Its anonymous
implementation calls the top-level focused `dynamicVariableGenerator` directly—there is no
function-valued factory parameter, default bridge, callable reference, or injectable pure-function
test seam. Change `dynamicVariableGenerator` accordingly, extend `DynamicVariableGeneratorTest`
with an exact current-request-name control, and cover built-in/custom dispatch through focused
`RegexReplacer` behavior. Polling receives `PostmanVariableScopes` (plus its existing rundown and
polling inputs) and reads `scopes.environment`; it does not receive `PostmanSDK` or unrelated
capture/progress state.

Keep `PostmanSDK` at the exact four-property interface above; migrate temporary Task-4 call sites
through `pm.scopes`, `pm.capture`, `pm.progress`, and `pm.regexReplacer` rather than restoring flat
aggregate delegates. Its KDoc must name it as a transitional wiring facade deleted by CS4; it must
not create or close resources.

Designate `ReVoman` as the only temporary Task-4 operational consumer allowed to mention
`PostmanSDK`; `KickRunner` does not exist until Task 5. The exact declaration infrastructure—
`PostmanSDK`, its function-local anonymous implementation, its synthetic top-level factory facade,
and any extractor-proven synthetic compiler helper owned by those declarations—is separately
allowlisted because those
classfiles necessarily name the type but do not consume it. Task 5 moves the final operational
reference to `KickRunner`, removes it from `ReVoman`, and tightens the operational allowlist to that
one class while retaining only the exact declaration-infrastructure set. Extend the built-JAR structural test to reject constant-pool/type-descriptor references
from `RegexReplacer`, dynamic-variable generation, polling, `PmJsEval`, every other focused module, or any
new operational class. Mutation-test the allowlist with one temporary forbidden operational reference. This containment
gate prevents later CS2 branches from adding new dependencies to the aggregate before CS4 deletes
it.

- [x] **Step 4: Route all script behavior through the real sandbox**

Change `PmJsEval` to receive scopes, capture, and a borrowed, non-owning `ScriptExecutor`. It must
never close that executor. It must inspect the selected script and return before invoking
`ScriptExecutor.execute` when the script is absent/blank; merely entering either phase or passing
the executor cannot initialize a sandbox. Build request/response context maps directly from the
template request and `http4k.Response`; remove the host `PostmanSDK.Request`/`Response` objects.
Preserve the immediate apply-back barrier after every script and all
set/unset/assertion/control-flow semantics.

Do not move lifecycle ownership early. In Task 4, `ReVoman` remains the temporary `PostmanSDK`
wiring owner and may continue to lexically scope a direct `PmSandbox`; `PmSandbox` satisfies
`ScriptExecutor` and is passed to `PmJsEval` only as a borrowed executor. Task 5 routes the full
single-kick body through `KickExecution` and owns the public no-script lifecycle proof. Task 4's
blank/absent controls prove only that neither script phase invokes its supplied executor.

Before deleting any evaluator test or production declaration, run the real-sandbox replacement
suite after the focused implementation compiles:

```bash
./gradlew :test \
  --tests '*PmSandboxApiCoverageTest' \
  --tests '*PmJsEvalScopesDiffTest' \
  --tests '*ScriptHookPhaseBarrierE2ETest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: supported bundled lodash, moment, XML parsing, request/response JSON, both blank/absent
executor controls, and the two-step phase barrier all pass against the real sandbox. This GREEN is
required before Step 5 deletions.

- [x] **Step 5: Delete the legacy evaluator and its orphaned API/dependency**

Delete from `PostmanSDK`:

- `JSEvaluator`, `evaluateJS`, and `jsonStrToObj`;
- nested `Variables`, `Request`, `Response`, and `Xml2Json`;
- top-level `Info`; and
- every Graal `Context`/`Source`/host-access/CommonJS import.

Remove `com.github.underscore` from the root dependencies/version catalog after proving it has no other consumer.

Remove every runtime read/constructor handoff of `nodeModulesPath` from `ReVoman`, `KickExecution`, and `PostmanSDK`, but retain the generated getter/builder/override surface. Deprecate it in `KickDef` and state in the migration guide that collection scripts execute in the bundled Postman sandbox; the path is ignored because arbitrary filesystem CommonJS loading existed only in the removed evaluator. Do not remove Node/npm sandbox-generation build tasks; vendored resource generation remains separately owned.

Update `SandboxBridge` KDoc in the same change: it owns the sole per-run Graal `Context`, backed by
the one process-wide engine and retained boot `Source`; remove every claim that a second
`PostmanSDK` evaluator context exists or shares warm-up.

- [x] **Step 6: Prove structural removal and update the live ABI**

Reuse Task 1's root-`test`/exact-`jar` provider wiring. Extend `JvmSurfaceInventory` once to expose
canonical per-class constant-pool references for `CONSTANT_Class`, `CONSTANT_Fieldref`,
`CONSTANT_Methodref`, and `CONSTANT_InterfaceMethodref`, resolving owner, member name, and JVM
descriptor, plus exact `CONSTANT_String` values needed for the CommonJS-option absence check,
without loading production classes. Both structural tests must inspect only
`configuredRootJar()` and these parsed references; do not use `Class.forName`, project class
directories, a JAR glob, or raw byte/string substring searches. `LegacyEvaluatorRemovalTest`
opens that archive and asserts:

- no `PostmanSDK$JSEvaluator.class` entry;
- `PostmanSDK` declares neither `evaluateJS` nor `jsonStrToObj`;
- the exact `Context.newBuilder([Ljava/lang/String;)Lorg/graalvm/polyglot/Context$Builder;`
  method reference occurs only in `SandboxBridge`;
- generated `Kick`/builder declarations still contain `nodeModulesPath` for source compatibility,
  while `ReVoman` and every production execution owner under `internal/runtime`, `internal/exe`,
  and `internal/postman` has no constant-pool method reference that invokes it and no CommonJS
  filesystem option `CONSTANT_String`; and
- `PostmanSDK` and `RegexReplacer` expose no Java-source-callable constructor, factory, property, or method from the built JAR, while the exact designated internal wiring bridge remains synthetic or package-private.

Make the temporary `PostmanSDK` reference gate exact. Its reviewed declaration-infrastructure set
contains only the `PostmanSDK` interface, its extractor-named anonymous implementation, its
synthetic top-level factory facade, and extractor-proven synthetic helpers; its operational set is
exactly `ReVoman`. Assert that every constant-pool class/member/descriptor reference to
`PostmanSDK` belongs to one of those literal owners and that no additional owner is present. Do not
use package prefixes as an allowlist. Task 5 deliberately replaces the operational owner.

Task 4 changes the raw inventory in both directions. In `Cs2JvmSurfaceAdditions.kt`, add literal
cumulative `CS2_TASK4_RAW_JVM_ADDITIONS` and `CS2_TASK4_RAW_JVM_REMOVALS` sets shared by
`ApiBaselineInventoryTest` and `JvmSurfaceVisibilityTest`. Review and paste every rendered diff row:
all focused interfaces, synthetic factories, anonymous implementations, accessors, lambdas, and
compiler helpers are additions; every frozen legacy `PostmanSDK`, nested evaluator/bridge type,
`Info`, `RegexReplacer`, companion, constructor, field, and method row is a removal, alongside the
prior Task 3 bridge removal and any other exact row changed by the extraction. No owner-prefix,
row-count, `removals.single()`, or "all removals are synthetic" shortcut is allowed. Both tests
require `active - frozen == CS2_TASK4_RAW_JVM_ADDITIONS` and `frozen - active ==
CS2_TASK4_RAW_JVM_REMOVALS`, and reject `Companion`/`INSTANCE` additions.
Do not add a callable-reference helper exception for `regexReplacer`: the authoritative factory
has no function-valued default or `::dynamicVariableGenerator`, so any new callable-reference
helper class or `INSTANCE` field is an unexpected raw addition and fails the exact allowlist.

Keep supported-surface accounting separate. `ApiBaselineInventoryTest` continues to require the
supported Kotlin and Java removals to equal the CS2a migration-map projections exactly, requires
supported additions to be empty, and then applies the shared literal raw allowlists independently.
The migration map—not the raw allowlist—governs supported public removals; the raw allowlists govern
all classfile rows, including non-source-callable compiler output.

Regenerate only the live dump:

```bash
./gradlew updateKotlinAbi
```

Assert the frozen `api/cs2-baseline-revoman-root.api` still matches its committed SHA-256 constant and documents every removed symbol.

Attach this structural test to ordinary `check`, and mutation-test it by temporarily restoring one forbidden evaluator class/method reference. A source-only `rg` assertion is supplemental, not the ownership proof.

- [x] **Step 7: Verify and commit**

Run:

```bash
./gradlew :test \
  --tests '*PostmanVariableScopesTest' \
  --tests '*StepScriptCaptureTest' \
  --tests '*LegacyRundownProgressTest' \
  --tests '*LegacyEvaluatorRemovalTest' \
  --tests '*PmJsEvalScopesDiffTest' \
  --tests '*ScriptHookPhaseBarrierE2ETest' \
  --tests '*PostmanSDK*Test' \
  --tests '*RegexReplacer*Test' \
  --tests '*DynamicVariableGeneratorTest' \
  --tests '*PmSandbox*Test' \
  --tests '*Polling*Test' \
  --tests '*ApiBaselineInventoryTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  compileApiCompatibilityTestKotlin compileApiCompatibilityTestJava checkKotlinAbi \
  spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: real sandbox replacement coverage, focused state tests, structural removal, consumer compilation, ABI check, and formatting all pass. Commit:

```bash
git add docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt \
  src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceInventory.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  src/test/kotlin/com/salesforce/revoman/internal/postman/DynamicVariableGeneratorTest.kt \
  src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt \
  build.gradle.kts gradle/libs.versions.toml api/revoman-root.api \
  src/main src/test src/integrationTest docs/modules/ROOT/pages
git commit -m "perf: remove legacy JavaScript evaluator"
```

### Task 5: Introduce ExecutionSession and move single-kick execution behind it

**Files:**
- Modify: `docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSession.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ReVomanRuntime.kt`
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickRunner.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickExecution.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSessionTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/ReVomanRuntimeTest.kt`
- Extend: `src/test/kotlin/com/salesforce/revoman/internal/runtime/KickExecutionTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/runtime/LegacyEvaluatorRemovalTest.kt`

**Interfaces:**
- Consumes: public `Kick`/`Rundown`, focused Postman state, current step pipeline, borrowed `RunLogContext`, and per-kick banner semantics.
- Produces: a testable internal facade runtime, one outer session, one kick child, and a slim
  public `ReVoman` API object.

- [ ] **Step 1: Write lifecycle-count, carry, and failure tests before moving code**

Define small internal factory boundaries that compose from the leaf dependency without mutating
global state:

```kotlin
internal fun interface ExecutionSessionFactory {
  @JvmSynthetic
  fun open(initialEnvironment: Map<String, Any?>): ExecutionSession
}

internal fun interface KickExecutionFactory {
  @JvmSynthetic
  fun create(
    configuredKick: Kick,
    effectiveDynamicEnvironment: Map<String, Any?>,
  ): KickExecution
}

internal fun interface KickBody {
  @JvmSynthetic
  fun execute(owner: KickExecution): Rundown
}

internal interface ReVomanRuntime {
  @JvmSynthetic
  fun execute(kick: Kick): Rundown
}

@JvmSynthetic
internal fun kickExecutionFactory(sandboxFactory: SandboxFactory): KickExecutionFactory

@JvmSynthetic
internal fun executionSessionFactory(
  kickExecutions: KickExecutionFactory,
): ExecutionSessionFactory

@JvmSynthetic
internal fun reVomanRuntime(sessions: ExecutionSessionFactory): ReVomanRuntime

@JvmSynthetic
internal fun reVomanRuntime(sandboxFactory: SandboxFactory): ReVomanRuntime

@JvmSynthetic
internal fun reVomanRuntime(): ReVomanRuntime
```

Every factory returns a function-local anonymous implementation. Do not add top-level `Default*`
classes, a named `KickRunner` class, object declarations, companions, singleton `INSTANCE` fields,
`ThreadLocal` overrides, service locators, or another ambient test hook. Kotlin top-level `private`
classes are still same-package Java-nameable bytecode and therefore do not satisfy this boundary.
The no-argument runtime lexically builds an anonymous `SandboxFactory` whose `create()` constructs
`PmSandbox`; merely constructing the graph, runtime, session, child, or reading `scripts` must not
call it.

Add tests proving one `open` for the single-kick runtime entrypoint, exactly one child, exact
ordering, and closure after success/body failure. Split construction failure precisely: a factory
throw before return yields zero session-owned children/closes and the factory proves it rolled back
any partial internals; a successfully returned child whose body throws is closed exactly once by
the session with exact suppression ordering. A recording factory may return a real
`ExecutionSession` wired with a fake kick executor for session-only tests; do not make
`ExecutionSession` open or introduce a broad interface solely so tests can fake it. List/runbook
entrypoints and hook-failure coverage are added in Task 6.

Add a full no-script lifecycle test through `ReVomanRuntime.execute(kick)` with a counting
`SandboxFactory`: one session and one kick execute, but the factory count remains zero and no
`SandboxBridge`/Graal `Context` is created. A one-script positive control must create exactly one
runtime and close it once. These controls must call `reVomanRuntime(countingSandboxFactory)` and
cross the real runtime, real session, real child, function-local production runner, and real
`PmJsEval` path. Recording decorators may count session/child creation, but these two tests must not
replace `KickBody` with a fake.

- [ ] **Step 2: Capture the missing-boundary RED**

```bash
./gradlew :test \
  --tests '*ExecutionSessionTest' \
  --tests '*ReVomanRuntimeTest' \
  --tests '*KickExecutionTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: absent session/runtime APIs and lifecycle ownership fail the new assertions. Current
list/runbook public recursion is an intentional Task-5 positive control, not this RED; Task 6
removes it.

- [ ] **Step 3: Implement the session's narrow state machine**

Use these concrete child seams rather than an open session class:

```kotlin
internal interface KickExecution : InternalCloseable {
  @get:JvmSynthetic val configuredKick: Kick
  @get:JvmSynthetic val effectiveDynamicEnvironment: Map<String, Any?>
  @get:JvmSynthetic val scripts: ScriptExecutor
  @get:JvmSynthetic val sandboxInitialized: Boolean
  @JvmSynthetic fun execute(): Rundown
  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun kickExecution(
  configuredKick: Kick,
  effectiveDynamicEnvironment: Map<String, Any?>,
  body: KickBody,
  sandboxFactory: SandboxFactory,
): KickExecution

internal interface ExecutionSession : InternalCloseable {
  @JvmSynthetic
  fun executeKick(
    configuredKick: Kick,
    carryForward: Boolean,
    beforeCarry: ((Rundown, List<Rundown>) -> Unit)? = null,
  ): Rundown
  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun executionSession(
  initialEnvironment: Map<String, Any?>,
  kickExecutions: KickExecutionFactory,
): ExecutionSession
```

`kickExecutionFactory(sandboxFactory)` creates one function-local, stateless anonymous `KickBody`
as the production runner and returns an anonymous `KickExecutionFactory` whose children come from
the function-local `kickExecution(...)` implementation. The runner may retain only immutable
collaborators; all kick, scopes, progress, report, and sequencing state belongs to the child or
method locals. Tests may inject a fake `KickBody` into real children for child-only tests, while the
runtime sandbox-count controls above use the production body. The default graph constructs a fully
initialized child whose expensive resources remain lazy. A factory that throws before returning
transfers no child to the session; once returned, the session sets `activeChild` before calling
`execute`.

Make the factory contract transactional in KDoc and implementation. Construction code may not open
a closeable before handoff; if a future constructor step must do so, the factory owns a local
`ResourceScope` and rolls it back before propagating failure. The session never guesses about an
object it did not receive.

`ExecutionSession` owns only what exists in CS2a:

- current carried environment;
- append-only finalized `Rundown` builder;
- creation/closure of one current `KickExecution`; and
- top-level cleanup state.

Its central method is:

```kotlin
@JvmSynthetic
internal fun executeKick(
  configuredKick: Kick,
  carryForward: Boolean,
  beforeCarry: ((Rundown, List<Rundown>) -> Unit)? = null,
): Rundown
```

The production runner exists only as the function-local anonymous `KickBody` emitted from
`KickRunner.kt`; there is no source-nameable `KickRunner` declaration or exported runner factory.
The built-JAR gate enumerates the declared internal interfaces/factories and every actual emitted
anonymous owner. Java may name an internal interface whose operations are synthetic, but it cannot
invoke those operations or name/construct the emitted runtime, session, runner, or child
implementations. No type implements `AutoCloseable` or another Java interface that reintroduces an
inherited callable lifecycle method.

The single-kick runtime opens its session with `emptyMap()`. Compute
`configuredKick.dynamicEnvironment() + carriedEnvironment`, so the outer/carried state retains the
current right-hand precedence. Pass both configured kick and this exact effective map into the
child; the moved body begins by deriving an effective kick with
`configuredKick.overrideDynamicEnvironment(effectiveDynamicEnvironment)` and uses that kick for
every body read. Add a test whose carried key overrides the same configured dynamic key and is
observed by the real/fake body.

Preserve the complete kick envelope in this exact order:

1. `Banner.onRunStart()`;
2. install the effective kick's borrowed `RunLogSink`;
3. create the child, mark it active, execute it, and close it lexically;
4. clear `activeChild`;
5. only after successful child closure, call `Banner.recordSteps`;
6. restore the borrowed sink; then
7. append the rundown, invoke `beforeCarry` with `rundowns.toList()`, and carry if requested.

The sandbox therefore closes while the kick sink is still installed, a close failure records no
completed banner steps, and a later list hook runs after sink restoration. Never close a borrowed
sink. When `carryForward` is true, take a fresh detached snapshot from the live environment after
the callback with `rundown.mutableEnv.toMap()`. Never use `Rundown.immutableEnv` or
`PostmanEnvironment.immutableEnv`: both are memoized lazy snapshots and may have been forced before
the callback mutates the live map. Add a regression that forces `immutableEnv`, mutates through the
callback, and proves the new value is the carried value; later mutation of an older rundown must
not mutate already captured carry. Keep the accumulated rundown list as a frozen snapshot; CS4
owns its allocation optimization.

The session is sequential/closeable, rejects execution after close, keeps at most one active child,
and closes an incomplete child before its own resources. `KickExecution.execute` is one-shot.
For each returned child, create a short-lived `ResourceScope`, register the child, assign
`activeChild`, invoke the body, and call that scope's `closeAfter(bodyFailure)` in `finally` before
clearing `activeChild`. Put `activeChild = null` in an inner `finally` around `closeAfter` so it runs
even when that method rethrows the body throwable or promotes a close failure. Catch `Throwable`,
not only `Exception`. Rethrow the body throwable by exact identity with distinct close failures
directly suppressed in reverse order; on a successful body, a close failure becomes primary and
the rundown is not appended. If the factory throws before returning a child, the session owns
nothing and propagates that failure. Append/callback/carry occur only after successful child
closure. A callback or fresh-snapshot failure therefore observes an already closed child; session
close remains idempotent and must not retry it.

Before session close, `ReVomanRuntime` materializes the returned single result. Close then clears the
session's carried-environment reference and mutable rundown builder so retaining a closed session
cannot retain results. It must not clear `Rundown.mutableEnv`, `Rundown.collectionVariables`, or
`Rundown.globals`: all three peer scopes have transferred into the returned legacy `Rundown` and
remain valid after both child and session close. Only `mutableEnv` participates in cross-kick
carry. Add after-close assertions for all three peer scopes. Empty list/runbook ownership is Task 6,
not Task 5.

- [ ] **Step 4: Move the existing kick body intact**

Move `revUpInternal`, lexical `SequenceResult`, the sequencer, and `runStep` helpers from
`ReVoman.kt` into the function-local production runner in `KickRunner.kt`. Move the sole operational
transitional `PostmanSDK` wiring reference from `ReVoman` into the extractor-proven emitted runner
owners, then tighten `LegacyEvaluatorRemovalTest` from the exact ReVoman owner set to those exact
runner owners plus the unchanged declaration-infrastructure set. `ReVoman` must retain zero
operational constant-pool references to `PostmanSDK`. Use the configured root JAR and parsed
constant-pool class/member/descriptor references; do not use source scanning, class loading, raw
substring searches, or owner prefixes. Keep execution ordering, Either mappings, ledger behavior,
interim progress copies, polling, hooks, halt behavior, and environment snapshots byte-for-byte
equivalent. This is a move-and-delegate step: do not combine CS2b/CS3/CS4 optimizations.

Keep `Banner.onRunStart()`, `Banner.recordSteps()`, per-kick borrowed sink installation/restoration, and no-op sink nuance at the kick boundary. The session never closes a borrowed sink.

- [ ] **Step 5: Make ReVoman a facade over ReVomanRuntime**

Move only `revUp(Kick)` in this task and use `carryForward = false`, proving that the single path
does not touch or materialize an environment carry snapshot. Leave
`revUp(List<Kick>, PostExeHook, Map)` and `revUp(Runbook, Map)` source- and behavior-identical in
Task 5: the list fold intentionally continues calling public `revUp(kick)` once per kick, and
`RunbookExe` intentionally continues calling public `ReVoman.revUp(kick)` once per step. They thus
open one session per kick until Task 6 moves both atomically to one shared session and removes those
public calls. Do not alter list callback/carry ordering or runbook contract/assertion/sink ordering
in this task.

Do not expose `ExecutionSession`, `KickExecution`, or factories publicly and do not store the current session in `ReVoman`.

Do not equate Kotlin `internal` with JVM encapsulation. Interfaces may be Java-nameable marker-like
owners because javac hides their synthetic abstract operations; the contract is that Java cannot
invoke their operations or construct any emitted implementation. Compile same-package adversarial
snippets against the configured root JAR and require name/access diagnostics rather than an
unrelated arity failure. Reject `AutoCloseable`, any inherited Java-callable lifecycle method,
companions, `INSTANCE` fields, ambient current-session fields, widened members, and unexpected
emitted owners.

After the implementation first compiles, extract the actual root-JAR delta and define literal
cumulative `CS2_TASK5_RAW_JVM_ADDITIONS` and `CS2_TASK5_RAW_JVM_REMOVALS` sets in
`Cs2JvmSurfaceAdditions.kt`. Both `ApiBaselineInventoryTest` and `JvmSurfaceVisibilityTest` must
require exact full rendered-row equality for `active - frozen` and `frozen - active`; neither test
may use package/owner prefixes, counts, globs, or a superset assertion. Preserve both frozen JVM and
Kotlin baselines byte-for-byte, reject every source-callable operational addition/removal outside
the approved CS2a migration projection, and assert `checkKotlinAbi` remains unchanged. Record only
compiler-extracted Task-5 rows—no speculative Task-6 or Task-7 descriptors.

- [ ] **Step 6: Verify the focused refactor and commit**

Run:

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

Expected: lifecycle/failure semantics, real lazy-sandbox controls, exact PostmanSDK ownership,
external Kotlin/Java consumers, and literal raw/Kotlin ABI gates pass. Deliberately mutation-test at
least: early sink restoration, memoized carry, a fake-only sandbox path, one widened synthetic
operation, one missing raw row, and one stale ReVoman PostmanSDK reference; every mutation must fail
its owning focused test and be restored before this command. Commit:

```bash
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
  src/main/kotlin/com/salesforce/revoman/internal/runtime \
  src/test/kotlin/com/salesforce/revoman/internal/runtime \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2JvmSurfaceAdditions.kt \
  src/test/kotlin/com/salesforce/revoman/compat/ApiBaselineInventoryTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  src/test/kotlin/com/salesforce/revoman/internal/runtime/LegacyEvaluatorRemovalTest.kt \
  docs/superpowers/plans/2026-08-11-performance-cs2a-runtime-lifecycle.md
git commit -m "refactor: introduce execution session lifecycle"
```

### Task 6: Route list and runbook execution through one session

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ReVomanRuntime.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSession.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookExe.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSessionE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/MultiKickEnvTypesE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/RunbookExeE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/RunbookLegibilityE2ETest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`

**Interfaces:**
- Consumes: `ExecutionSession.executeKick`, current list `PostExeHook` ordering, runbook halt/continue/contract behavior, and borrowed logging context.
- Produces: one explicit outer session for every single/list/runbook entrypoint, independent sessions for public reentrant calls, and no internal recursion through `ReVoman.revUp`.

- [ ] **Step 1: Add end-to-end lifecycle and carry RED tests**

Use `reVomanRuntime(...)` with recording `ExecutionSessionFactory`/kick-body seams for
lifecycle counts; do not widen its synthetic factory/file-private implementation or install a global observer in the public
singleton. Test the public reentrant call behaviorally in an isolated process, and let Task 7's opt-in weak diagnostics prove exact public-call object counts. Cover:

1. single execution creates one session and one kick;
2. an empty list and empty runbook each create one session and zero kicks;
3. a three-kick list creates one session and three sequentially closed children;
4. a runbook creates one session and one child per configured kick;
5. a public `ReVoman.revUp` invoked by a hook creates an independent nested session rather than joining the outer session;
6. only the finalized environment carries; collection variables, globals, script capture, and sandbox globals do not;
7. the carried environment wins over the next kick's dynamic value;
8. `PostExeHook` receives a snapshot including the current rundown, and its mutation carries to the next kick;
9. retaining an earlier callback list does not make it grow as later kicks finish; and
10. runbook carry is captured before contract/`assertAfter`, so assertion mutations do not carry;
11. duplicate configured kick/path occurrences still create distinct children in configured order;
12. a throwing `PostExeHook` observes that the current child was already closed exactly once, then closes the outer session while preserving the hook failure as primary;
13. runbook produces/`assertAfter` failures observe the already-closed child and then close the session with exact primary/suppressed ordering, while a consumes failure occurs before child creation and closes only the outer session; and
14. the legacy mutable environment, collection variables, and globals are transferred into the returned `Rundown`, so closing `KickExecution` before the list hook must not clear or invalidate any peer scope; only the environment carries forward.

Keep the existing halt/continue, `assertAfter`, logging-bracket, and failure-legibility expectations as positive controls.

- [ ] **Step 2: Prove the current recursive runbook path is RED**

Run:

```bash
./gradlew :test \
  --tests '*ExecutionSessionE2ETest' \
  --tests '*MultiKickEnvTypesE2ETest' \
  --tests '*RunbookExeE2ETest' \
  --tests '*RunbookLegibilityE2ETest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: lifecycle counts and reentrant isolation fail because list/runbook orchestration still enters public `revUp` per kick.

- [ ] **Step 3: Route list execution without changing hook semantics**

Implement the list entrypoint as one `ExecutionSession.useInternal` block using Task 2's
Kotlin-only close helper. For each kick call:

```kotlin
session.executeKick(
  configuredKick = kick,
  carryForward = true,
  beforeCarry = { current, accumulated -> postExeHook.accept(current, accumulated) },
)
```

Use the existing `PostExeHook.accept(Rundown, List<Rundown>)` signature exactly. Ordering is normative: append current, freeze accumulated list, invoke hook, then snapshot the environment. Do not retain callback snapshots in the session after the callback returns.

- [ ] **Step 4: Route runbooks through the existing session**

Change `executeRunbook`/its step helpers to accept the concrete internal `ExecutionSession` and never import/call public `ReVoman`. Let the session overlay the carried environment; the runbook may still override only the effective borrowed sink. Capture carry immediately after each kick, then run the existing contract and `assertAfter` checks. Preserve the current handling of halt, continue, legibility, and temporary `RunLogSink.NoOp` installation; never close a borrowed sink.

Mark every new list/runbook entrypoint on `ReVomanRuntime` and `ExecutionSession` `@JvmSynthetic`;
extend the same built-JAR negative-javac inventory rather than allowing orchestration methods to
become Java-callable as this task adds overloads.

- [ ] **Step 5: Prove no ambient sharing or internal public recursion remains**

Add a structural assertion that `RunbookExe` bytecode has no invocation/reference to `ReVoman.revUp`. Add latch-coordinated behavioral controls showing concurrent and reentrant public calls have independent carried/scope state; do not attempt to observe their internal identities through mutable global test instrumentation.

- [ ] **Step 6: Verify and commit**

Run:

```bash
./gradlew :test \
  --tests '*ExecutionSessionE2ETest' \
  --tests '*MultiKickEnvTypesE2ETest' \
  --tests '*Runbook*Test' \
  --tests '*ControlFlow*Test' \
  --tests '*Hook*Test' \
  --tests '*Ledger*Test' \
  --tests '*RunLog*Test' \
  --tests '*JvmSurfaceVisibilityTest' \
  checkKotlinAbi spotlessCheck \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
```

Expected: list/runbook/reentrant semantics, cleanup, ABI, and formatting pass. Commit:

```bash
git add src/main/kotlin/com/salesforce/revoman \
  src/test/kotlin/com/salesforce/revoman
git commit -m "refactor: route multi-kick execution through sessions"
```

### Task 7: Publish real lifecycle weak-reference evidence to the benchmark driver

**Files:**
- Add: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ExecutionLifecycleDiagnostics.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/runtime/ExecutionSession.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/runtime/KickExecution.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/internal/runtime/ExecutionLifecycleDiagnosticsTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapter.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major/MajorV1BindingContract.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major/MajorV1Adapter.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/TargetForkMain.kt`
- Add: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/RetainedCheckpointCollector.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/RetainedMemoryRunner.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ReleaseGateEvaluator.kt`
- Modify: `benchmark-driver/src/main/resources/workloads/v1/lifecycle.no-script-one-step.v1/manifest.json`
- Add: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/process/RetainedCheckpointCollectorTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/target/FakeTargetJarBuilder.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapterContractTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/RetainedMemoryRunnerTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/compare/ReleaseGateEvaluatorTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/compare/ComparisonFixtures.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/fixture/DeterministicHttpFixtureTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/jmh/VerifiedLifecycleWorkloadSnapshotTest.kt`
- Modify: `benchmark-driver/src/integrationTest/kotlin/com/salesforce/revoman/benchmark/driver/process/RunnerIntegrationTest.kt`
- Modify: `benchmark-driver/src/integrationTest/kotlin/com/salesforce/revoman/benchmark/driver/cli/BenchmarkDriverIntegrationTest.kt`

**Interfaces:**
- Consumes: CS1's retained-worker protocol, two-GC checkpoint, target classloader isolation, `major-v1` exact descriptor bindings, and the release evaluator's required `ExecutionSession`/`KickExecution` names.
- Produces: opt-in weak-only target diagnostics, target-neutral tracked references in the worker, real major-v1 retained outcomes, and unchanged fake-token evidence for the pinned CS1 baseline adapter.

- [ ] **Step 1: Add disabled/enabled/drain RED tests**

Define one versioned enable token, for example `revoman.lifecycleDiagnostics=weak-references-v1`. Tests must prove:

- diagnostics disabled by default allocate/register no weak-reference records;
- enabling before runtime class initialization records one `ExecutionSession` and one `KickExecution` per one-kick outer call;
- registration stores no strong reference to either object;
- `drain()` is atomic, returns each record once, and clears its internal queue;
- malformed enable values fail closed instead of silently enabling; and
- ordinary calls expose no public diagnostics API in Kotlin source/ABI.

Use isolated classloaders or subprocesses for property/class-initialization tests; do not make test order depend on resetting an already initialized singleton.

- [ ] **Step 2: Capture driver and target binding RED**

Add target-neutral driver values without widening the public `PreparedWorkload` interface:

```kotlin
internal data class TrackedWeakReference(
  val type: String,
  val reference: WeakReference<*>,
)

internal interface LifecycleWeakReferenceProvider {
  fun drainLifecycleWeakReferences(): List<TrackedWeakReference>
}
```

Only the major prepared workload implements the internal capability. Absence means a legacy target with no real lifecycle surface; an empty list from an implementing provider is invalid after retained executions. Write tests expecting the `major-v1` adapter to return exactly the two named groups and the baseline adapter to preserve the CS1 fake-token path. Run the focused tests and capture failure on the absent diagnostics method/binding.

Run the RED probes separately so root and driver filters cannot mask one another:

```bash
./gradlew :test --tests '*ExecutionLifecycleDiagnosticsTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:test \
  --tests '*TargetAdapterContractTest' \
  --tests '*RetainedCheckpointCollectorTest' \
  --tests '*RetainedMemoryRunnerTest' \
  --tests '*ReleaseGateEvaluatorTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: the first fails on the absent runtime facade; the second fails on absent target capability/collector and exact retained gate checks.

- [ ] **Step 3: Implement weak-only runtime diagnostics**

Use a top-level synthetic JVM facade rather than a Kotlin object, so ordinary execution does not allocate an object singleton and Java/Kotlin consumers do not see a supported API:

```kotlin
@file:JvmName("ExecutionLifecycleDiagnostics")
@file:JvmSynthetic

package com.salesforce.revoman.internal.runtime

@JvmName("drain")
internal fun drainLifecycleDiagnostics(): Array<Any>
```

The compiled class/method contract is exactly `ExecutionLifecycleDiagnostics.drain()[Ljava/lang/Object;`; add a reflection/classfile test for the static descriptor because the Kotlin ABI dump intentionally excludes this internal synthetic seam. The returned bootstrap-safe exact `Object[]` alternates `String` type names and exact `java.lang.ref.WeakReference` instances; it must contain no target-defined DTO or `WeakReference` subclass. Register `WeakReference(this)` from each `ExecutionSession` and `KickExecution` constructor only when the versioned property was enabled before the diagnostics facade initialized. Use synchronization only on this opt-in diagnostics path. Disabled initialization may hold only scalar/nullable state, not an empty growable record container. Never hold a strong execution reference, rundown, environment, context, classloader-owned DTO, or throwable.

`drain()` validates the internal pair structure and swaps its mutable record buffer for a fresh empty buffer before returning the old records as an array. It must not merely call `clear()` on a growable buffer whose O(executionCount) backing storage remains process-reachable. Return an empty array when nothing was registered. Keep the top-level function/facade Kotlin-`internal`; the stable JVM method exists solely for the versioned adapter and is documented in the migration/benchmark notes, not as supported user API.

- [ ] **Step 4: Bind and normalize the major-v1 evidence**

Add the exact descriptor to `MajorV1BindingContract`:

```text
com/salesforce/revoman/internal/runtime/ExecutionLifecycleDiagnostics.drain()[Ljava/lang/Object;
```

Do not bind this member during ordinary `MajorV1Adapter.prepare()`. `MajorPreparedWorkload` implements `LifecycleWeakReferenceProvider` and lazily resolves/caches the exact static handle only when retained mode calls `drainLifecycleWeakReferences()`. That method runs inside `TargetRuntime.withTargetContext`, requires `raw.javaClass == Array<Any>::class.java`, validates an even length, requires nonblank expected type strings and `value.javaClass === WeakReference::class.java`, copies them into driver-owned records using driver constants, and drops the raw array before GC. Repeated expected type names are required because there is one record per execution; unknown names, a subclass payload, malformed pairs, or repeated weak-reference identity are worker-integrity failures. Extend `FakeTargetJarBuilder.majorJar()` with configurable valid/malformed exact static diagnostics fixtures rather than making every unrelated major-adapter test fail at preparation.

- [ ] **Step 5: Replace fake candidate tokens without weakening the baseline**

For retained mode with exact adapter ID `major-v1` only, set the diagnostics property before opening the target runtime/classloader. Never enable it for `baseline-83f3cd70` or ordinary cold/warm/allocation/RSS workers. Preserve the previous property value and restore it in worker cleanup with the existing body-primary/ordered-suppressed failure semantics. After all executions, inspect the prepared workload before retained sampling:

- exact `baseline-83f3cd70`: require that the capability is absent and create the existing single `Cs1FakeExecutionToken` weak reference;
- exact `major-v1`: require the capability and drain it; absence/empty evidence is an integrity failure, never a fake fallback;
- any future adapter: require an explicitly versioned capability contract rather than implicitly granting fake evidence.

Malformed arrays/elements/subclasses/unknown names/repeated reference identities and a completely
empty major-v1 drain abort the worker and publish no result. A nonempty, structurally valid drain
with one expected group missing, a count mismatch, or uncleared referents is unfavorable benchmark
evidence: serialize the actual per-type outcomes so the gate deterministically cannot PASS.

Do not hold those O(executionCount) `WeakReference` objects or driver wrappers during the heap measurement. Use two explicit phases:

1. `RetainedCheckpointCollector.proveReachability(referenceSource)` invokes the source/drain inside its own helper frame, owns the resulting weak references, runs the existing two-acknowledgement `FullGcProtocol`, computes only immutable per-type `WeakReferenceOutcome` counts, and returns; then
2. after that helper frame and every raw array/list/reference wrapper are unreachable, run a second two-acknowledgement `FullGcProtocol` and use only this second sample's `usedHeapBytes` as retained-memory evidence.

The reference source is a callback that captures only the prepared provider, never a prebuilt list; therefore the outer collector frame cannot retain the list into phase 2. Compute the checkpoint's `completedGcCycles` with `Math.addExact(reachability.completedGcCycles, finalHeap.completedGcCycles)`. Each `FullGcProtocol` call acknowledges at least two cycles but may observe more concurrent collector increments, so require a truthful total of at least four rather than hard-coding equality to four; retain the existing model's `>= 2` forward-compatible validation. Version the retained provider identity/configuration hash from the CS1 fake-token procedure to a CS2a two-phase weak-proof/final-heap procedure, and update every golden/test that asserts the provider hash. With an injected GC sampler, test call order, checked cycle summation, use of only the second heap value, buffer swap, and that the final sampler cannot reach the first phase's raw array/list/wrapper objects. Mutations that reuse the first heap, clamp the cycle count to four, or retain the list into phase 2 must fail without timing thresholds.

Restore/clear the diagnostics property in worker cleanup for tests even though production target workers are one-shot JVMs. Do not add diagnostics to latency/allocation/RSS modes.

- [ ] **Step 6: Prove classloader safety and real retained execution**

Add contract tests showing the driver-side tracked records contain only exact JDK `WeakReference`/String types. For classloader collection, deliberately keep normalized tracked records alive while dropping the prepared workload, runtime, all method handles, and restored thread context classloader; `TargetRuntime.close()` alone is not sufficient evidence. Add a direct real `TargetForkMain` integration command with a small `retainedExecutionCount` (do not change the production runner's fixed 1,000/2,000/4,000 points) that verifies:

- the baseline role reports only `Cs1FakeExecutionToken`;
- the major-v1 role reports `ExecutionSession` and `KickExecution` with exact created counts;
- at least four acknowledged GC cycles occur under the two-phase provider, with the exact checked sum preserved; and
- every real candidate weak reference clears.

Mark each newly added major-only JUnit Jupiter integration method with
`@EnabledIfSystemProperty(named = "revoman.benchmark.adapter", matches = "major-v1")`. The complete
integration suite intentionally skips only those named methods when run under
`baseline-83f3cd70`; the later filtered major-v1 command must execute all selected methods with zero
skips. Record both counts. Do not let a major-only test throw merely because the full compatibility
suite is exercising the fixed baseline adapter.

Harden `ReleaseGateEvaluator` as an archive trust boundary: every candidate retained observation must contain exactly one row each for `ExecutionSession` and `KickExecution`, each `created` count must equal that observation's `executionCount`, and every `cleared` count must equal `created`. Every baseline observation must contain exactly one `Cs1FakeExecutionToken` row with `created == cleared == 1`. Missing, extra, duplicate, count-mismatched, or uncleared evidence is FAIL/INCONCLUSIVE according to the existing compatibility-versus-gate distinction, never PASS. Keep generic schema/model compatibility at `completedGcCycles >= 2`, but for the exact new CS2a retained provider/configuration identity require `completedGcCycles >= 4` before release evaluation. Add malicious two-cycle and three-cycle archives, as well as weak-row attacks, through `ComparisonFixtures`; do not rely only on the worker/provider to have validated the original JSON.

Keep the full controlled 1,000/2,000/4,000 × five accepted-block retained campaign for the final performance gate rather than ordinary CI.

Now that the candidate exposes real lifecycle weak references, change the packaged lifecycle
workload's `RETAINED` gate list from empty to exactly `["RETAINED_SLOPE"]`. Update the manifest
contract test that previously pinned the CS1-only empty list. Recompute and update
`VerifiedLifecycleWorkloadSnapshotTest.LIFECYCLE_MANIFEST_SHA256`; never hand-copy the old constant.
This is a workload-contract identity change and must flow into the captured `WorkloadIdentity`; do
not reuse any CS1 result or fixture hash. Cold and warm required gates remain byte-for-byte
unchanged.

- [ ] **Step 7: Verify and commit**

Export a fresh major target and run exact focused/broad gates:

```bash
./gradlew :benchmark-driver:installDist
./gradlew \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.targetId=current-cs2a

TASK7_BASELINE_ROOT=$(mktemp -d "$PWD/build/task7-baseline.XXXXXXXX")
git worktree add --detach "$TASK7_BASELINE_ROOT/checkout" \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
"$TASK7_BASELINE_ROOT/checkout/gradlew" -p "$TASK7_BASELINE_ROOT/checkout" \
  -I "$PWD/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-task7-baseline.json" \
  -Pbenchmark.targetId=task7-baseline-83f3cd70 --no-daemon --console=plain

./gradlew :test --tests '*ExecutionLifecycleDiagnosticsTest' \
  --tests '*JvmSurfaceVisibilityTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:test \
  :benchmark-driver:jmhClasses \
  :benchmark-driver:installDist \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:integrationTest \
  -Pbenchmark.targetManifest=build/benchmark-target-task7-baseline.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle campaign*' \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew spotlessCheck --rerun-tasks --no-build-cache \
  --no-configuration-cache --console=plain
git diff --check
```

Expected: runtime diagnostics, adapter/fake fixtures, two-phase collector, release evaluator, direct small-count real worker integration, all driver tests, packaging, and formatting pass. Commit:

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/runtime \
  src/test/kotlin/com/salesforce/revoman/internal/runtime \
  src/test/kotlin/com/salesforce/revoman/compat/JvmSurfaceVisibilityTest.kt \
  benchmark-driver/src/main benchmark-driver/src/test benchmark-driver/src/integrationTest
git commit -m "test: expose real execution lifetime evidence"
```

### Task 8: Reconcile the major API, documentation, and full acceptance gates

**Files:**
- Modify: `api/revoman-root.api`
- Verify unchanged: `api/cs2-baseline-revoman-root.api`
- Modify: `docs/modules/ROOT/pages/migration-guide.adoc`
- Modify: `docs/modules/ROOT/pages/scripts-and-pm-apis.adoc`
- Modify: `docs/modules/ROOT/nav.adoc`
- Modify: `README.adoc`
- Modify: `DEVELOPMENT.md`
- Modify: `.github/workflows/build.yml`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/BenchmarkWorkflowTest.kt`
- Modify: `docs/superpowers/specs/2026-08-09-performance-redesign-design.md`
- Add: `docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh`
- Add: `docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh`
- Add: `docs/superpowers/benchmarks/operators/cs2a-operator.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt`
- Add: `docs/superpowers/reports/2026-08-11-performance-cs2a-runtime-lifecycle.md`
- Add after capture: `docs/superpowers/benchmarks/results/v1/cs2a-<implementation-sha>/`

**Interfaces:**
- Consumes: all CS2a commits, the frozen pre-major API, current live API, consumer fixtures, benchmark workload/adapters, and repository verification policy.
- Produces: an explicit migration record, independently reviewed source/behavior conformance, clean full gates, and reproducible cold/warm/retained evidence without claiming a win unless confidence gates pass.

- [ ] **Step 1: Reconcile the active ABI and migration tables**

Run `./gradlew updateKotlinAbi`, then inspect the generated diff. `ApiBaselineInventoryTest` must
compute the normalized frozen-baseline-minus-active Kotlin declaration set and
`JvmSurfaceVisibilityTest` must extract the current built JAR and compute the frozen-minus-active
Java-source-callable member set; require exact equality with Task 1's two CS2a removal projections.
Also require active-minus-frozen supported Kotlin declarations and Java-source-callable members to
be empty. Finally compare the complete raw-JAR addition set to the exact Task 2-7 allowlist of
internal interface/type entries, file-private implementations, synthetic top-level
factories/bridges, and the one synthetic diagnostics descriptor; no companion/singleton field or
unlisted generated member is allowed. Use Task 1's explicit grouping rule for compiler-generated
synthetic/bridge members. Validate JSON and retained/deprecated behavior rows through their own
schema/structural assertions. Every removal must be intentional and present in the migration guide;
every unrelated addition/removal, missing row, or stale row is a defect. At minimum document:

- removed `PostmanSDK` evaluator/bridge surface;
- deprecated/ignored `Kick.nodeModulesPath` with its source surface retained and evaluator consumption removed;
- internal lifecycle types are not user API;
- no serialized report/schema field changes belong to CS2a; and
- CS2b/CS2c/CS2d migration rows remain pending and are not preempted.

Verify the frozen baseline hash and required legacy symbols without comparing it to the now-changed active dump. Compile every Java/Kotlin consumer fixture against the current major API.

- [ ] **Step 2: Add final structural and lifecycle invariant tests**

The final structural suite must fail if any of these regress:

- a production `Context.newBuilder` call exists outside `SandboxBridge`;
- `PostmanSDK$JSEvaluator`, `evaluateJS`, or `jsonStrToObj` reappears;
- `nodeModulesPath` disappears from generated/public APIs or is consumed by an executor/evaluator;
- `RunbookExe` calls public `ReVoman.revUp`;
- a no-script kick initializes the real sandbox;
- more/fewer than one session per outer call or one kick child per kick is observed;
- normal non-retained execution registers lifecycle diagnostics;
- a CS2a internal state/lifecycle type exposes a Java-source-callable constructor, factory, property, or method from the built JAR;
- any production class outside the exact `PostmanSDK` declaration-infrastructure set and the sole
  operational `KickRunner` wiring owner refers to `PostmanSDK`; or
- either normalized Kotlin/Java-source-callable removal set differs from its CS2a
  migration-ledger projection, either supported addition set is nonempty, the raw-JAR addition set
  differs from its explicit internal/synthetic allowlist, or a retained behavior/JSON row fails its
  separate invariant.

Implement these final cross-cutting assertions in `Cs2aStructuralInvariantTest`, extending
`LegacyEvaluatorRemovalTest`, `ExecutionSessionE2ETest`,
`ExecutionLifecycleDiagnosticsTest`, `ApiBaselineInventoryTest`, and
`JvmSurfaceVisibilityTest` for their owning details. Their archive inspection and negative-javac
subprocesses consume the exact built root JAR through a Gradle-provided path and receive neither
project class directories nor Kotlin friend paths; this does not make the ordinary root test source
set an external compilation. Prefer classfile/JAR inspection over brittle source-text assertions. Mutation-test the Java visibility, aggregate dependency
allowlist, no-script lazy path, and ABI-ledger equality before restoring the implementation.

Update ordinary `build.yml` before the major surface disappears. After the normal current-target
export, use a distinct `actions/checkout` path for clean detached
`83f3cd70f78ad733412d10cbc8287aaabafe7aac`, export a fixed-baseline manifest with that checkout's
wrapper, and run the complete driver integration suite plus `benchmarkHarnessSelfTest` with
`baseline-83f3cd70`. Run only the new targeted lifecycle integration tests with the current manifest
and `major-v1`. `BenchmarkWorkflowTest` must parse and prove the two manifest paths, target IDs,
adapter assignments, fixed SHA, and absence of a current-manifest/baseline-adapter mismatch. Capture
RED by applying the test to the old workflow, then GREEN after the workflow split.

- [ ] **Step 3: Run the complete correctness and packaging gates**

Export a fresh current-target manifest and a separate clean pinned baseline manifest for the JMH
harness self-test, then run serially. The self-test intentionally uses
`jmh.component-operations.v1`, which `major-v1` does not accept; never point it at the current
manifest.

```bash
./gradlew :benchmark-driver:installDist \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.targetId=current-cs2a \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew checkKotlinAbi apiCompatibilityTestClasses \
  :test :integrationTest :benchmark-driver:test \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

SELFTEST_ROOT=$(mktemp -d "$PWD/build/cs2a-selftest.XXXXXXXX")
git worktree add --detach "$SELFTEST_ROOT/baseline" \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
test -z "$(git -C "$SELFTEST_ROOT/baseline" status --porcelain)"
test "$(git -C "$SELFTEST_ROOT/baseline" rev-parse HEAD)" = \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
"$SELFTEST_ROOT/baseline/gradlew" -p "$SELFTEST_ROOT/baseline" \
  -I "$PWD/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-baseline-selftest.json" \
  -Pbenchmark.targetId=baseline-selftest-83f3cd70 --no-daemon --console=plain

./gradlew :benchmark-driver:integrationTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle campaign*' \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:benchmarkHarnessSelfTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew build :benchmark-driver:jmhClasses :benchmark-driver:installDist \
  spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin \
  :benchmark-driver:classes --no-configuration-cache --console=plain
./gradlew qodanaScan --no-configuration-cache --console=plain
```

If a gate fails or behavior is unexpected, use `superpowers:systematic-debugging` and the JetBrains debugger skill first; use JDWP only if the IDE debugger cannot attach. Do not weaken, skip, or silently reclassify a gate. Record exact test counts, task outcomes, Qodana findings, and any environmental limitation in the report.

- [ ] **Step 4: Review and freeze the implementation SHA before measurement**

Run an independent fixed-range Standards + Spec review over the Task 1 compatibility commit through
the current worktree. Resolve every Critical/Important finding with focused RED/GREEN evidence and
rerun the proportional correctness gates. Add versioned operator scripts under
`docs/superpowers/benchmarks/operators`: the runner reads the exact candidate SHA only from a
root-owned `0444` `/opt/revoman-benchmark/cs2a-implementation-sha`, and the supervisor verifies the
checked-in runner SHA before installing/executing it. Preserve the independently audited Task 13
lock, lease, process-group containment, stale-state recovery, and governor restoration logic; add
`bash -n`, ShellCheck, signal/timeout/restoration tests, and a fixed-range security review for all
three scripts. Then stage the active ABI, structural tests, source,
ordinary documentation, and spec status and create the pre-evidence implementation commit:

```bash
git add api src benchmark-driver .github/workflows/build.yml \
  build.gradle.kts gradle/libs.versions.toml \
  README.adoc DEVELOPMENT.md docs/modules/ROOT \
  docs/superpowers/specs/2026-08-09-performance-redesign-design.md \
  docs/superpowers/benchmarks/operators
git commit -m "refactor: complete CS2a execution lifecycle"
export CS2A_IMPLEMENTATION_SHA=$(git rev-parse HEAD)
readonly CS2A_IMPLEMENTATION_SHA
printf '%s\n' "$CS2A_IMPLEMENTATION_SHA" >"$PWD/build/cs2a-implementation-sha"
test -z "$(git status --porcelain)"
git push origin HEAD:refs/heads/codex/performance-cs2a-lifecycle
```

The detached harness and candidate measured below must both be this exact reviewed SHA. If any
production, test, ABI, build, workload, or ordinary-documentation change is needed afterward, make
a new implementation commit, repeat review/gates, and replace `CS2A_IMPLEMENTATION_SHA`; never
silently benchmark an earlier tree. Before every smoke/controlled/archive-only invocation, export
the value from `build/cs2a-implementation-sha`; never recompute it from `HEAD` after an attempt
evidence commit. A host-state-only rerun therefore measures the same reviewed implementation SHA,
while a code/harness correction first produces and exports a new reviewed implementation SHA.

- [ ] **Step 5: Run proportional benchmark evidence**

Preselect the only CS2a improvement hypotheses before observing candidate results:

- cold `ALLOCATED_BYTES` mean: candidate/baseline upper-95 ratio at most `0.85`; and
- warm `LATENCY` median: candidate/baseline upper-95 ratio at most `0.80`.

These are optional targeted wins, not substitutes for the normative gates. The current `compare`
CLI emits the same cold-allocation and warm-median bootstrap intervals as normative decisions but
does not accept ad-hoc `TargetedClaim` arguments. Do not add a benchmark-CLI feature in CS2a and do
not relabel the decision kind. The report may state that a preselected improvement threshold was
met only when the corresponding persisted interval's `upper95` satisfies the threshold; otherwise
state only the machine comparator's PASS/FAIL/INCONCLUSIVE outcome. Never choose another metric
after inspecting results.

First run deterministic local smoke using two independently exported target IDs and never-reused
output paths. `BASELINE_CHECKOUT` is a clean detached checkout of full SHA
`83f3cd70f78ad733412d10cbc8287aaabafe7aac`; `CANDIDATE_CHECKOUT` is a clean detached checkout of
the exact reviewed CS2a implementation commit. Provision all three checkouts rather than relying on
operator placeholders:

```bash
set -euo pipefail
mkdir -p "$PWD/build"
SMOKE_ROOT=$(mktemp -d "$PWD/build/cs2a-smoke.XXXXXXXX")
SMOKE_CHECKOUTS=$(mktemp -d "$PWD/build/cs2a-smoke-checkouts.XXXXXXXX")
: "${CS2A_IMPLEMENTATION_SHA:?export the exact reviewed implementation SHA from Step 4}"
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
BASELINE_SHA=83f3cd70f78ad733412d10cbc8287aaabafe7aac
BASELINE_CHECKOUT="$SMOKE_CHECKOUTS/baseline"
HARNESS_CHECKOUT="$SMOKE_CHECKOUTS/harness"
CANDIDATE_CHECKOUT="$SMOKE_CHECKOUTS/candidate"
git worktree add --detach "$BASELINE_CHECKOUT" "$BASELINE_SHA"
git worktree add --detach "$HARNESS_CHECKOUT" "$CS2A_IMPLEMENTATION_SHA"
git worktree add --detach "$CANDIDATE_CHECKOUT" "$CS2A_IMPLEMENTATION_SHA"

assert_detached_clean() {
  local checkout=$1 expected=$2
  test "$(git -C "$checkout" rev-parse HEAD)" = "$expected"
  test -z "$(git -C "$checkout" status --porcelain)"
  test -z "$(git -C "$checkout" symbolic-ref -q HEAD || true)"
}
assert_detached_clean "$BASELINE_CHECKOUT" "$BASELINE_SHA"
assert_detached_clean "$HARNESS_CHECKOUT" "$CS2A_IMPLEMENTATION_SHA"
assert_detached_clean "$CANDIDATE_CHECKOUT" "$CS2A_IMPLEMENTATION_SHA"
INIT="$HARNESS_CHECKOUT/benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts"
DRIVER="$HARNESS_CHECKOUT/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"

"$BASELINE_CHECKOUT/gradlew" -p "$BASELINE_CHECKOUT" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$SMOKE_ROOT/baseline.json" \
  -Pbenchmark.targetId=baseline-83f3cd70-smoke --no-daemon --console=plain
"$CANDIDATE_CHECKOUT/gradlew" -p "$CANDIDATE_CHECKOUT" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$SMOKE_ROOT/candidate.json" \
  -Pbenchmark.targetId=candidate-cs2a-smoke --no-daemon --console=plain
"$HARNESS_CHECKOUT/gradlew" -p "$HARNESS_CHECKOUT" \
  :benchmark-driver:installDist --no-daemon --console=plain

"$DRIVER" run-paired --mode cold --intent smoke \
  --baseline "$SMOKE_ROOT/baseline.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$SMOKE_ROOT/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 --metrics latency \
  --artifacts-dir "$SMOKE_ROOT/cold-artifacts" --output "$SMOKE_ROOT/cold.json"
"$DRIVER" run-paired --mode warm --intent smoke \
  --baseline "$SMOKE_ROOT/baseline.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$SMOKE_ROOT/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 1 --iterations 3 --seed 5928239383101656625 --metrics latency \
  --artifacts-dir "$SMOKE_ROOT/warm-artifacts" --output "$SMOKE_ROOT/warm.json"
"$DRIVER" verify --input "$SMOKE_ROOT/cold.json"
"$DRIVER" verify --input "$SMOKE_ROOT/warm.json"

./gradlew :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle campaign*' \
  -Pbenchmark.targetManifest="$SMOKE_ROOT/candidate.json" \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Then, on the controlled Linux host and under the existing fixed host policy, run two freshly built
baseline targets for cold/warm A/A before paired baseline `83f3cd70` versus the exact CS2a
candidate. The exact command body below is the checked-in
`docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh`; do not paste it into an ordinary
shell. Use the checked-in `cs2a-governor-supervisor.sh`, derived from the independently reviewed
Task 13 root-owned supervisor with only its versioned controlled-run/config/state/timeout inputs
changed.
The runner must retain Task 13's exact UID, inherited locked-FD/root lease/handshake checks,
root-only stale-governor recovery, process-group containment, and verified all-governor restoration.
Install the reviewed supervisor `root:root 0555`, the implementation-SHA file `root:root 0444`,
and the policy `root:root 0444`; invoke the supervisor through `dzdo`, and define success only
as its final post-restoration zero exit. Record all three script SHA-256 values. This is the exact
exclusive lease/governor wrapper; direct runner invocation is invalid controlled evidence.

The runner pins the controlled-host JDK, creates a previously absent run root, clones four detached
checkouts inside it, and preserves every rejected block:

```bash
#!/usr/bin/env bash
set -euo pipefail
test -n "${BASH_VERSION:-}"
REMOTE_HOST=gopalaaksh-wsl3
: "${CS2A_IMPLEMENTATION_SHA:?export the exact reviewed implementation SHA}"
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
readonly CS2A_IMPLEMENTATION_SHA
git cat-file -e "$CS2A_IMPLEMENTATION_SHA^{commit}"
OPERATOR_ROOT=$(mktemp -d "$PWD/build/cs2a-operator-source.XXXXXXXX")
git worktree add --detach "$OPERATOR_ROOT/source" "$CS2A_IMPLEMENTATION_SHA"
test "$(git -C "$OPERATOR_ROOT/source" rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
test -z "$(git -C "$OPERATOR_ROOT/source" status --porcelain)"
test -z "$(git -C "$OPERATOR_ROOT/source" symbolic-ref -q HEAD || true)"
OPERATOR_DIR="$OPERATOR_ROOT/source/docs/superpowers/benchmarks/operators"
printf '%s\n' "$CS2A_IMPLEMENTATION_SHA" >"$PWD/build/cs2a-implementation-sha"
RUNNER_SHA=$(sha256sum "$OPERATOR_DIR/cs2a-controlled-run.sh" | cut -d' ' -f1)
SUPERVISOR_SHA=$(sha256sum "$OPERATOR_DIR/cs2a-governor-supervisor.sh" | cut -d' ' -f1)
OPERATOR_SHA=$(sha256sum "$OPERATOR_DIR/cs2a-operator.sh" | cut -d' ' -f1)
test "$(sha256sum "$0" | cut -d' ' -f1)" = "$OPERATOR_SHA"

persist_attempt() {
  local operator_status=$1 recorded_status evidence_dir evidence_rel attempt_id evidence_sha
  local unrelated_status staged_names
  [[ "$operator_status" =~ ^[0-9]+$ ]] || return 70
  recorded_status=$(cat "$PWD/build/cs2a-operator-status.txt") || return 70
  test "$operator_status" = "$recorded_status" || return 70
  test -s "$PWD/build/cs2a-local-evidence-dir.txt" || return 70
  evidence_dir=$(cat "$PWD/build/cs2a-local-evidence-dir.txt") || return 70
  case "$evidence_dir" in
    "$PWD"/docs/superpowers/benchmarks/results/v1/cs2a-"$CS2A_IMPLEMENTATION_SHA"/cs2a.* | \
    "$PWD"/docs/superpowers/benchmarks/results/v1/cs2a-"$CS2A_IMPLEMENTATION_SHA"/operator-failure.*) ;;
    *) echo "unsafe canonical attempt directory: $evidence_dir" >&2; return 70 ;;
  esac
  evidence_rel=${evidence_dir#"$PWD/"}
  attempt_id=$(basename "$evidence_dir") || return 70
  test -n "$evidence_rel" || return 70
  (cd "$evidence_dir" && sha256sum -c evidence-sha256sums.txt) || return 70
  unrelated_status=$(git status --porcelain --untracked-files=all -- . \
    ":(exclude)$evidence_rel") || return 70
  test -z "$unrelated_status" || return 70
  staged_names=$(git ls-files -- "$evidence_rel") || return 70
  if test -n "$staged_names"; then
    unrelated_status=$(git status --porcelain -- "$evidence_rel") || return 70
    test -z "$unrelated_status" || return 70
    evidence_sha=$(git log -1 --format=%H -- "$evidence_rel") || return 70
    [[ "$evidence_sha" =~ ^[0-9a-f]{40}$ ]] || return 70
    printf '%s\n' "$evidence_sha" \
      >"$PWD/build/cs2a-attempt-evidence-sha.txt" || return 70
    unrelated_status=$(git status --porcelain) || return 70
    test -z "$unrelated_status" || return 70
    return 0
  fi
  git add -- "$evidence_rel" || return 70
  git diff --cached --check || return 70
  staged_names=$(git diff --cached --name-only -- "$evidence_rel") || return 70
  test -n "$staged_names" || return 70
  git commit -m "perf: archive CS2a attempt $attempt_id" || return 70
  evidence_sha=$(git rev-parse HEAD) || return 70
  printf '%s\n' "$evidence_sha" \
    >"$PWD/build/cs2a-attempt-evidence-sha.txt" || return 70
  unrelated_status=$(git status --porcelain) || return 70
  test -z "$unrelated_status" || return 70
  return 0
}

OPERATOR_MODE=run
RESUME_RUN_ROOT=
RESUME_GOVERNOR_STATE=
PERSIST_STATUS=
case "$#" in
  0) ;;
  2)
    test "$1" = --persist-only
    OPERATOR_MODE=persist-only
    PERSIST_STATUS=$2
    ;;
  3)
    test "$1" = --archive-only
    OPERATOR_MODE=archive-only
    RESUME_RUN_ROOT=$2
    RESUME_GOVERNOR_STATE=$3
    ;;
  *) echo 'usage: cs2a-operator.sh [--archive-only RUN_ROOT GOVERNOR_STATE | --persist-only STATUS]' >&2; exit 2 ;;
esac

if test "$OPERATOR_MODE" = persist-only; then
  persist_attempt "$PERSIST_STATUS" || exit 70
  exit 0
fi

"$OPERATOR_ROOT/source/gradlew" -p "$OPERATOR_ROOT/source" \
  :benchmark-driver:installDist --no-daemon --console=plain \
  >"$PWD/build/cs2a-local-validation-driver.log" 2>&1
LOCAL_DRIVER="$OPERATOR_ROOT/source/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
test -x "$LOCAL_DRIVER"

if test "$OPERATOR_MODE" = run; then
scp "$OPERATOR_DIR/cs2a-controlled-run.sh" \
  "$OPERATOR_DIR/cs2a-governor-supervisor.sh" \
  "$OPERATOR_DIR/cs2a-operator.sh" \
  "$PWD/build/cs2a-implementation-sha" \
  "$REMOTE_HOST:/opt/revoman-benchmark/runs/"
ssh "$REMOTE_HOST" \
  "test \"\$(sha256sum /opt/revoman-benchmark/runs/cs2a-controlled-run.sh | cut -d' ' -f1)\" = '$RUNNER_SHA' &&
   test \"\$(sha256sum /opt/revoman-benchmark/runs/cs2a-governor-supervisor.sh | cut -d' ' -f1)\" = '$SUPERVISOR_SHA' &&
   test \"\$(sha256sum /opt/revoman-benchmark/runs/cs2a-operator.sh | cut -d' ' -f1)\" = '$OPERATOR_SHA' &&
   test \"\$(tr -d '\\r\\n' </opt/revoman-benchmark/runs/cs2a-implementation-sha)\" = '$CS2A_IMPLEMENTATION_SHA'"
ssh -tt "$REMOTE_HOST" \
  "dzdo install -o root -g root -m 0555 \
     /opt/revoman-benchmark/runs/cs2a-governor-supervisor.sh \
     /opt/revoman-benchmark/cs2a-governor-supervisor.sh &&
   dzdo install -o root -g root -m 0444 \
     /opt/revoman-benchmark/runs/cs2a-implementation-sha \
     /opt/revoman-benchmark/cs2a-implementation-sha"
INSTALLED_SUPERVISOR_SHA=$(ssh -tt "$REMOTE_HOST" \
  'dzdo sha256sum /opt/revoman-benchmark/cs2a-governor-supervisor.sh' \
  | tr -d '\r' | awk '{print $1}')
test "$INSTALLED_SUPERVISOR_SHA" = "$SUPERVISOR_SHA"

set +e
ssh -tt "$REMOTE_HOST" \
  'dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh' \
  | tee "$PWD/build/cs2a-supervisor.log"
SUPERVISOR_STATUS=${PIPESTATUS[0]}
POST_SUPERVISOR_STATUS=0
RESUME_VALIDATION_STATUS=0
POST_STATUS_PERSISTED=false
record_post_supervisor_failure() {
  POST_SUPERVISOR_STATUS=70
}
printf '%s\n' "$SUPERVISOR_STATUS" >"$PWD/build/cs2a-supervisor-exit.txt" \
  || record_post_supervisor_failure

REMOTE_RUN_ROOT=$(tr -d '\r' <"$PWD/build/cs2a-supervisor.log" \
  | sed -n 's/^RUN_ROOT=//p')
GOVERNOR_STATE=$(tr -d '\r' <"$PWD/build/cs2a-supervisor.log" \
  | sed -n 's/^GOVERNOR_STATE=//p')
else
  set +e
  RESUME_VALIDATION_STATUS=0
  POST_STATUS_PERSISTED=false
  record_post_supervisor_failure() { RESUME_VALIDATION_STATUS=70; }
  REMOTE_RUN_ROOT=$RESUME_RUN_ROOT
  GOVERNOR_STATE=$RESUME_GOVERNOR_STATE
  if ! [[ "$REMOTE_RUN_ROOT" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] \
    || ! [[ "$GOVERNOR_STATE" =~ ^/run/revoman-cs2a/governor-state\.[A-Za-z0-9]+$ ]]; then
    echo 'invalid archive-only path' >&2
    exit 2
  fi
  REMOTE_REAL=$(ssh "$REMOTE_HOST" "readlink -f -- '$REMOTE_RUN_ROOT'")
  test "$REMOTE_REAL" = "$REMOTE_RUN_ROOT" || record_post_supervisor_failure
  RECORDED_RUN_ROOT=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/run-root.txt'" | tr -d '\r\n')
  test "$RECORDED_RUN_ROOT" = "$REMOTE_RUN_ROOT" || record_post_supervisor_failure
  RECORDED_IMPLEMENTATION=$(ssh -tt "$REMOTE_HOST" \
    'dzdo cat /opt/revoman-benchmark/cs2a-implementation-sha' | tr -d '\r\n')
  test "$RECORDED_IMPLEMENTATION" = "$CS2A_IMPLEMENTATION_SHA" \
    || record_post_supervisor_failure
  RESUME_EXECUTED=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/executed-script-sha256sums.tsv'" | tr -d '\r')
  test "$(printf '%s\n' "$RESUME_EXECUTED" | awk -F '\t' \
    '$1 == "runner" { print $2 }')" = "$RUNNER_SHA" || record_post_supervisor_failure
  test "$(printf '%s\n' "$RESUME_EXECUTED" | awk -F '\t' \
    '$1 == "supervisor" { print $2 }')" = "$SUPERVISOR_SHA" \
    || record_post_supervisor_failure
  ROOT_POLICY_SHA=$(ssh -tt "$REMOTE_HOST" \
    'dzdo sha256sum /opt/revoman-benchmark/controlled-host.json' \
    | tr -d '\r' | awk '{print $1}')
  test "$ROOT_POLICY_SHA" = \
    7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79 \
    || record_post_supervisor_failure
  RESUME_POLICY_SHA=$(ssh "$REMOTE_HOST" \
    "sha256sum '$REMOTE_RUN_ROOT/meta/controlled-host.json' 2>/dev/null | awk '{print \$1}'")
  case "$RESUME_POLICY_SHA" in
    '' | 7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79) ;;
    *) record_post_supervisor_failure ;;
  esac
  SUPERVISOR_STATUS=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/child-or-supervisor-status.txt'" | tr -d '\r\n')
  if ! ssh -tt "$REMOTE_HOST" \
    "dzdo test -f '$GOVERNOR_STATE/operator-post-supervisor-exit.txt'"; then
    record_post_supervisor_failure
    ssh -tt "$REMOTE_HOST" \
      "(dzdo /bin/bash -c 'set -o noclobber; umask 077; \
         printf \"70\\n\" >\"$GOVERNOR_STATE/operator-post-supervisor-exit.txt\"' \
         || dzdo test -f '$GOVERNOR_STATE/operator-post-supervisor-exit.txt') &&
       dzdo chmod 0400 '$GOVERNOR_STATE/operator-post-supervisor-exit.txt'" \
      || record_post_supervisor_failure
  fi
  POST_SUPERVISOR_STATUS=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/operator-post-supervisor-exit.txt'" | tr -d '\r\n')
  case "$POST_SUPERVISOR_STATUS" in 0 | 70) POST_STATUS_PERSISTED=true ;; \
    *) POST_SUPERVISOR_STATUS=70; record_post_supervisor_failure ;; esac
  printf '%s\n' "$SUPERVISOR_STATUS" >"$PWD/build/cs2a-supervisor-exit.txt" \
    || record_post_supervisor_failure
fi
RUN_ROOT_VALID=true
GOVERNOR_STATE_VALID=true
if test -z "$REMOTE_RUN_ROOT" \
  || test "$(printf '%s\n' "$REMOTE_RUN_ROOT" | wc -l | tr -d ' ')" -ne 1; then
  RUN_ROOT_VALID=false
  record_post_supervisor_failure
fi
if test -z "$GOVERNOR_STATE" \
  || test "$(printf '%s\n' "$GOVERNOR_STATE" | wc -l | tr -d ' ')" -ne 1; then
  GOVERNOR_STATE_VALID=false
  record_post_supervisor_failure
fi
case "$REMOTE_RUN_ROOT" in
  /opt/revoman-benchmark/runs/cs2a.*) ;;
  *) RUN_ROOT_VALID=false; record_post_supervisor_failure ;;
esac
case "$GOVERNOR_STATE" in
  /run/revoman-cs2a/governor-state.*) ;;
  *) GOVERNOR_STATE_VALID=false; record_post_supervisor_failure ;;
esac
if test "$GOVERNOR_STATE_VALID" = true; then
  REMOTE_CHILD_STATUS=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/child-or-supervisor-status.txt'" | tr -d '\r\n')
  REMOTE_RESTORATION_FAILED=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/restoration-failed.txt'" | tr -d '\r\n')
  REMOTE_CONTAINMENT_FAILED=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/containment-failed.txt'" | tr -d '\r\n')
  test "$REMOTE_CHILD_STATUS" = "$SUPERVISOR_STATUS" || record_post_supervisor_failure
  test "$REMOTE_RESTORATION_FAILED" = false || record_post_supervisor_failure
  test "$REMOTE_CONTAINMENT_FAILED" = false || record_post_supervisor_failure
  ssh -tt "$REMOTE_HOST" \
    "dzdo flock -n /opt/revoman-benchmark/task13.lock -c true &&
     dzdo awk -F '\\t' '{ command=\"cat \" \$1; command | getline current; close(command);
       if (current != \$2) exit 1 }' '$GOVERNOR_STATE/original-governors.tsv'" \
    || record_post_supervisor_failure
fi
if test "$RUN_ROOT_VALID" = true && test "$GOVERNOR_STATE_VALID" = true; then
  ssh -tt "$REMOTE_HOST" \
    "dzdo install -d -o gopala.akshintala -g gopala.akshintala -m 0700 \
       '$REMOTE_RUN_ROOT/meta/supervisor' &&
     for file in child-or-supervisor-status.txt restoration-failed.txt \
       containment-failed.txt finished-at.txt original-governors.tsv \
       executed-script-sha256sums.tsv run-root.txt; do
       dzdo install -o gopala.akshintala -g gopala.akshintala -m 0400 \
         '$GOVERNOR_STATE/'\"\$file\" '$REMOTE_RUN_ROOT/meta/supervisor/'\"\$file\" || exit 1
     done" || record_post_supervisor_failure
fi
if test "$RUN_ROOT_VALID" = true; then
  if test "$OPERATOR_MODE" = run; then
    scp "$PWD/build/cs2a-supervisor.log" \
      "$REMOTE_HOST:$REMOTE_RUN_ROOT/meta/operator-supervisor.log" \
      || record_post_supervisor_failure
    scp "$PWD/build/cs2a-supervisor-exit.txt" \
      "$REMOTE_HOST:$REMOTE_RUN_ROOT/meta/operator-supervisor-exit.txt" \
      || record_post_supervisor_failure
  else
    scp "$REMOTE_HOST:$REMOTE_RUN_ROOT/meta/operator-supervisor.log" \
      "$PWD/build/cs2a-supervisor.log" || record_post_supervisor_failure
    scp "$REMOTE_HOST:$REMOTE_RUN_ROOT/meta/operator-supervisor-exit.txt" \
      "$PWD/build/cs2a-supervisor-exit.txt" || record_post_supervisor_failure
  fi
fi
persist_original_post_status() {
  local candidate="$PWD/build/cs2a-original-post-supervisor-exit.txt"
  local remote_candidate="$REMOTE_RUN_ROOT/meta/.operator-post-supervisor-exit.upload"
  printf '%s\n' "$POST_SUPERVISOR_STATUS" >"$candidate" || return 1
  scp "$candidate" "$REMOTE_HOST:$remote_candidate" || return 1
  ssh -tt "$REMOTE_HOST" \
    "dzdo install -o root -g root -m 0400 '$remote_candidate' \
       '$GOVERNOR_STATE/operator-post-supervisor-exit.txt' &&
     dzdo install -o gopala.akshintala -g gopala.akshintala -m 0400 \
       '$GOVERNOR_STATE/operator-post-supervisor-exit.txt' \
       '$REMOTE_RUN_ROOT/meta/supervisor/operator-post-supervisor-exit.txt' &&
     rm -f '$remote_candidate'" || return 1
  test "$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$GOVERNOR_STATE/operator-post-supervisor-exit.txt'" | tr -d '\r\n')" \
    = "$POST_SUPERVISOR_STATUS"
}
if test "$OPERATOR_MODE" = run; then
  if test "$RUN_ROOT_VALID" != true || test "$GOVERNOR_STATE_VALID" != true; then
    POST_SUPERVISOR_STATUS=70
    printf '%s\n' "$POST_SUPERVISOR_STATUS" \
      >"$PWD/build/cs2a-original-post-supervisor-exit.txt"
    POST_STATUS_PERSISTED=true
  elif persist_original_post_status; then
      POST_STATUS_PERSISTED=true
  else
    POST_SUPERVISOR_STATUS=70
    if persist_original_post_status; then POST_STATUS_PERSISTED=true; fi
  fi
else
  if test "$POST_STATUS_PERSISTED" = true && test "$RUN_ROOT_VALID" = true; then
    ssh -tt "$REMOTE_HOST" \
      "dzdo install -o gopala.akshintala -g gopala.akshintala -m 0400 \
         '$GOVERNOR_STATE/operator-post-supervisor-exit.txt' \
         '$REMOTE_RUN_ROOT/meta/supervisor/operator-post-supervisor-exit.txt'" \
      || record_post_supervisor_failure
  fi
fi
```

Run this block only through the checked-in `cs2a-operator.sh` with macOS `/bin/bash` 3.2 or newer;
`PIPESTATUS` and later `shopt` are Bash contracts, not zsh-compatible snippets. Marker parsing is
deliberately scalar/count based and does not use Bash-4-only `mapfile`. The `dzdo` prompts are the
only interactive steps. The
checked-in supervisor pins the expected runner SHA and rejects a source or implementation-SHA file
with the wrong owner, mode, type, or bytes. Before launching the child, it hashes its own installed
path and the exact runner it will execute, persists those values as `role<TAB>sha256` rows in
root-only `executed-script-sha256sums.tsv`, and includes that file in the final governor-state
handoff. It also writes the exact authenticated child `RUN_ROOT` to root-only `run-root.txt` before
restoration, so archive-only recovery cannot substitute another user-owned directory. The operator compares the installed supervisor hash before launch; archive acceptance
compares both executed hashes to the exact detached implementation sources. For local archive
verification/recomparison, the operator builds and uses the installed driver from that same clean
detached implementation checkout; it never uses a distribution built from the dirty pre-commit
tree or a later evidence-commit `HEAD`.

```bash
#!/usr/bin/env bash
set -euo pipefail
test -n "${BASH_VERSION:-}"
export JAVA_HOME=/home/gopala.akshintala/core-public/tools/Linux/jdk/sfdc-jdk-zulu-21.helium_x64
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
export GRADLE_OPTS=-Dorg.gradle.daemon=false
CS2A_IMPLEMENTATION_SHA=$(tr -d '\r\n' </opt/revoman-benchmark/cs2a-implementation-sha)
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
BASELINE_SHA=83f3cd70f78ad733412d10cbc8287aaabafe7aac
SOURCE_REPO="$HOME/code-clones/work/revoman-root"
RUN_ROOT=
early_runner_exit() {
  local status=$?
  trap - EXIT
  set +e
  case "$RUN_ROOT" in
    /opt/revoman-benchmark/runs/cs2a.*)
      mkdir -p "$RUN_ROOT/meta"
      test -f "$RUN_ROOT/meta/stage.txt" \
        || printf '%s\n' setup >"$RUN_ROOT/meta/stage.txt"
      printf '%s\n' "$status" >"$RUN_ROOT/meta/runner-exit.txt"
      printf '%s\n' 1 >"$RUN_ROOT/meta/inventory-exit.txt"
      printf 'RUN_ROOT=%s\n' "$RUN_ROOT"
      ;;
  esac
  exit "$status"
}
RUN_ROOT=$(mktemp -d /opt/revoman-benchmark/runs/cs2a.XXXXXXXX)
case "$RUN_ROOT" in /opt/revoman-benchmark/runs/cs2a.*) ;; *) exit 70 ;; esac
trap early_runner_exit EXIT
mkdir "$RUN_ROOT/meta"
printf '%s\n' setup >"$RUN_ROOT/meta/stage.txt"
HARNESS="$RUN_ROOT/checkouts/harness"
BASELINE_A="$RUN_ROOT/checkouts/baseline-a"
BASELINE_B="$RUN_ROOT/checkouts/baseline-b"
CANDIDATE="$RUN_ROOT/checkouts/candidate"
POLICY=/opt/revoman-benchmark/controlled-host.json
EXPECTED_POLICY_SHA256=7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79
EXPECTED_POLICY_SEMANTIC_SHA256=48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60
EXPECTED_HOST_FINGERPRINT=12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44
INIT="$HARNESS/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts"
DRIVER="$HARNESS/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
mkdir "$RUN_ROOT/checkouts" "$RUN_ROOT/manifests" "$RUN_ROOT/results" \
  "$RUN_ROOT/artifacts" "$RUN_ROOT/logs"

write_inventory() (
  set -euo pipefail
  find "$RUN_ROOT/manifests" "$RUN_ROOT/results" -type f -print0 \
    | sort -z | xargs -0 -r sha256sum >"$RUN_ROOT/meta/evidence-sha256sums.txt"
  find "$RUN_ROOT/artifacts" -type f -printf '%P\t%s\n' \
    | LC_ALL=C sort >"$RUN_ROOT/meta/artifact-inventory.tsv"
  find "$RUN_ROOT/artifacts" -type f -print0 \
    | sort -z | xargs -0 -r sha256sum >"$RUN_ROOT/meta/artifact-sha256sums.txt"
  find "$RUN_ROOT/logs" -type f -print0 \
    | sort -z | xargs -0 -r sha256sum >"$RUN_ROOT/meta/command-output-sha256sums.txt"
)
on_runner_exit() {
  local status=$? inventory_status=0
  trap - EXIT
  set +e
  write_inventory
  inventory_status=$?
  printf '%s\n' "$status" >"$RUN_ROOT/meta/runner-exit.txt"
  printf '%s\n' "$inventory_status" >"$RUN_ROOT/meta/inventory-exit.txt"
  printf 'RUN_ROOT=%s\n' "$RUN_ROOT"
  if test "$status" -eq 0 && test "$inventory_status" -ne 0; then
    status=$inventory_status
  fi
  exit "$status"
}
trap on_runner_exit EXIT

git -C "$SOURCE_REPO" fetch origin codex/performance-cs2a-lifecycle
git -C "$SOURCE_REPO" cat-file -e "$CS2A_IMPLEMENTATION_SHA^{commit}"
git -C "$SOURCE_REPO" merge-base --is-ancestor "$CS2A_IMPLEMENTATION_SHA" \
  origin/codex/performance-cs2a-lifecycle
for checkout in "$HARNESS" "$CANDIDATE"; do
  git clone --no-hardlinks --quiet "$SOURCE_REPO" "$checkout"
  git -C "$checkout" checkout --detach "$CS2A_IMPLEMENTATION_SHA"
done
for checkout in "$BASELINE_A" "$BASELINE_B"; do
  git clone --no-hardlinks --quiet "$SOURCE_REPO" "$checkout"
  git -C "$checkout" checkout --detach "$BASELINE_SHA"
done
for checkout in "$HARNESS" "$BASELINE_A" "$BASELINE_B" "$CANDIDATE"; do
  test -z "$(git -C "$checkout" status --porcelain)"
  test -z "$(git -C "$checkout" symbolic-ref -q HEAD || true)"
done
test "$(git -C "$HARNESS" rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
test "$(git -C "$CANDIDATE" rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
test "$(git -C "$BASELINE_A" rev-parse HEAD)" = "$BASELINE_SHA"
test "$(git -C "$BASELINE_B" rev-parse HEAD)" = "$BASELINE_SHA"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh" \
  "$RUN_ROOT/meta/"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh" \
  "$RUN_ROOT/meta/"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-operator.sh" \
  "$RUN_ROOT/meta/"
cp "$POLICY" "$RUN_ROOT/meta/controlled-host.json"
printf '%s\n' "$CS2A_IMPLEMENTATION_SHA" >"$RUN_ROOT/meta/implementation-sha.txt"
(cd "$RUN_ROOT/meta" && sha256sum \
  cs2a-controlled-run.sh cs2a-governor-supervisor.sh cs2a-operator.sh \
  >operator-script-sha256sums.txt)
test "$(stat -c '%U:%G:%a' "$POLICY")" = root:root:444
test "$(sha256sum "$POLICY" | cut -d' ' -f1)" = "$EXPECTED_POLICY_SHA256"
jq -e --arg host "$EXPECTED_HOST_FINGERPRINT" '
  .schema == "revoman-controlled-host/v1" and
  .hostFingerprintSha256 == $host and
  .allowedGovernors == ["performance"] and
  .powerEvidenceRequirement == "FIXED_MAINS"
' "$POLICY" >/dev/null
printf '%s  %s\n' "$EXPECTED_POLICY_SHA256" "$POLICY" \
  >"$RUN_ROOT/meta/policy-sha256.txt"
printf '%s\n' "$EXPECTED_POLICY_SEMANTIC_SHA256" \
  >"$RUN_ROOT/meta/policy-semantic-sha256.txt"
printf '%s\n' "$RUN_ROOT" >"$RUN_ROOT/meta/run-root.txt"
: >"$RUN_ROOT/meta/commands.tsv"

run_logged() {
  local label=$1 status
  shift
  [[ "$label" =~ ^[a-z0-9][a-z0-9.-]*$ ]]
  test ! -e "$RUN_ROOT/logs/$label.stdout"
  test ! -e "$RUN_ROOT/logs/$label.stderr"
  test ! -e "$RUN_ROOT/logs/$label.exit"
  {
    printf '%s' "$label"
    printf '\t%q' "$@"
    printf '\n'
  } >>"$RUN_ROOT/meta/commands.tsv"
  if "$@" >"$RUN_ROOT/logs/$label.stdout" 2>"$RUN_ROOT/logs/$label.stderr"; then
    status=0
  else
    status=$?
  fi
  printf '%s\n' "$status" >"$RUN_ROOT/logs/$label.exit"
  return "$status"
}

run_campaign() {
  local label=$1 output=$2 status
  shift 2
  if run_logged "$label" "$@"; then status=0; else status=$?; fi
  printf '%s\n' "$status" >"$RUN_ROOT/meta/$label-exit.txt"
  case "$status" in
    0 | 1) test -s "$output" ;;
    *) return "$status" ;;
  esac
}

verify_controlled_result() {
  local label=$1 result=$2
  run_logged "verify-$label" "$DRIVER" verify --input "$result"
  jq -e --arg policy "$EXPECTED_POLICY_SEMANTIC_SHA256" \
    --arg host "$EXPECTED_HOST_FINGERPRINT" \
    '.environment.policySha256 == $policy and
     .environment.hostFingerprintSha256 == $host' "$result" >/dev/null
}

run_logged install-harness "$HARNESS/gradlew" -p "$HARNESS" \
  :benchmark-driver:installDist --no-daemon --console=plain
run_logged export-baseline-a "$BASELINE_A/gradlew" -p "$BASELINE_A" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$RUN_ROOT/manifests/baseline-a.json" \
  -Pbenchmark.targetId=baseline-a-cs2a --no-daemon --console=plain
run_logged export-baseline-b "$BASELINE_B/gradlew" -p "$BASELINE_B" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$RUN_ROOT/manifests/baseline-b.json" \
  -Pbenchmark.targetId=baseline-b-cs2a --no-daemon --console=plain
run_logged export-candidate "$CANDIDATE/gradlew" -p "$CANDIDATE" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$RUN_ROOT/manifests/candidate.json" \
  -Pbenchmark.targetId=candidate-cs2a --no-daemon --console=plain

for manifest in "$RUN_ROOT"/manifests/*.json; do
  run_logged "verify-manifest-$(basename "$manifest" .json)" \
    "$DRIVER" verify --input "$manifest"
done

run_campaign cold-aa "$RUN_ROOT/results/cold-aa.json" \
  "$DRIVER" capture-baseline --mode cold --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 \
  --metrics latency,peak-rss,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/cold-aa" --output "$RUN_ROOT/results/cold-aa.json"
run_campaign warm-aa "$RUN_ROOT/results/warm-aa.json" \
  "$DRIVER" capture-baseline --mode warm --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 20 --iterations 100 --seed 5928239383101656625 \
  --metrics latency,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/warm-aa" --output "$RUN_ROOT/results/warm-aa.json"
printf '%s\n' aa-captured >"$RUN_ROOT/meta/stage.txt"
aa_failed=false
for mode in cold warm; do
  verify_controlled_result "aa-$mode" "$RUN_ROOT/results/$mode-aa.json"
  if run_logged "comparison-aa-$mode" \
    "$DRIVER" compare --input "$RUN_ROOT/results/$mode-aa.json" \
      --output-json "$RUN_ROOT/results/comparison-aa-$mode.json" \
      --output-md "$RUN_ROOT/results/comparison-aa-$mode.md" --enforce-release-gates; then
    status=0
  else
    status=$?
  fi
  printf '%s\n' "$status" >"$RUN_ROOT/meta/comparison-aa-$mode-exit.txt"
  test -s "$RUN_ROOT/results/comparison-aa-$mode.json"
  test -s "$RUN_ROOT/results/comparison-aa-$mode.md"
  if test "$status" -ne 0; then aa_failed=true; fi
done
printf '%s\n' aa-compared >"$RUN_ROOT/meta/stage.txt"
if test "$aa_failed" = true; then exit 3; fi

run_campaign cold-candidate "$RUN_ROOT/results/cold-candidate.json" \
  "$DRIVER" run-paired --mode cold --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 \
  --metrics latency,peak-rss,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/cold-candidate" \
  --output "$RUN_ROOT/results/cold-candidate.json"
run_campaign warm-candidate "$RUN_ROOT/results/warm-candidate.json" \
  "$DRIVER" run-paired --mode warm --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 20 --iterations 100 --seed 5928239383101656625 \
  --metrics latency,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/warm-candidate" \
  --output "$RUN_ROOT/results/warm-candidate.json"
run_campaign retained-candidate "$RUN_ROOT/results/retained-candidate.json" \
  "$DRIVER" run-paired --mode retained --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 0 --iterations 0 --seed 5928239383101656625 \
  --metrics retained --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/retained-candidate" \
  --output "$RUN_ROOT/results/retained-candidate.json"
printf '%s\n' candidate-captured >"$RUN_ROOT/meta/stage.txt"

candidate_status=0
for mode in cold warm retained; do
  verify_controlled_result "candidate-$mode" "$RUN_ROOT/results/$mode-candidate.json"
  if run_logged "comparison-candidate-$mode" \
    "$DRIVER" compare --input "$RUN_ROOT/results/$mode-candidate.json" \
      --output-json "$RUN_ROOT/results/comparison-candidate-$mode.json" \
      --output-md "$RUN_ROOT/results/comparison-candidate-$mode.md" \
      --enforce-release-gates; then
    status=0
  else
    status=$?
  fi
  printf '%s\n' "$status" >"$RUN_ROOT/meta/comparison-candidate-$mode-exit.txt"
  test -s "$RUN_ROOT/results/comparison-candidate-$mode.json"
  test -s "$RUN_ROOT/results/comparison-candidate-$mode.md"
  if test "$status" -ne 0 && test "$candidate_status" -eq 0; then
    candidate_status=$status
  fi
done
printf '%s\n' candidate-compared >"$RUN_ROOT/meta/stage.txt"
exit "$candidate_status"
```

The checked-in runner must wrap every external Gradle/driver command with the Task 13-style
shell-escaped command logger and record stdout, stderr, and exit status without changing the
command's semantics. Its `EXIT` path writes the evidence/artifact inventories even for FAIL or
INCONCLUSIVE and prints exactly one `RUN_ROOT=<absolute-path>` line. Large JFR files remain under the never-reused remote root, but their relative paths,
sizes, and SHA-256 values are mandatory inventory rows.

After the supervisor has restored every governor, the following tail of the same checked-in
`cs2a-operator.sh` copies every attempt back locally whether it passed, failed, or was
inconclusive. It shares the already authenticated implementation/source/script identities from the
first operator section. Use the unique remote directory basename as the attempt key; never
overwrite or omit an earlier non-PASS attempt:

```bash
if test "$RUN_ROOT_VALID" = true; then
  ATTEMPT_ID=$(basename "$REMOTE_RUN_ROOT")
else
  ATTEMPT_ID="operator-failure.$(date -u +%Y%m%dT%H%M%SZ).$$"
fi
EVIDENCE_ROOT="$PWD/docs/superpowers/benchmarks/results/v1/cs2a-$CS2A_IMPLEMENTATION_SHA"
FINAL_EVIDENCE_DIR="$EVIDENCE_ROOT/$ATTEMPT_ID"
TRANSFER_STATUS=0
test "$POST_STATUS_PERSISTED" = true || TRANSFER_STATUS=70
mkdir -p "$EVIDENCE_ROOT" || TRANSFER_STATUS=70
test ! -e "$FINAL_EVIDENCE_DIR" || TRANSFER_STATUS=70
if EVIDENCE_DIR=$(mktemp -d "$PWD/build/cs2a-archive-stage.XXXXXXXX"); then
  case "$EVIDENCE_DIR" in
    "$PWD"/build/cs2a-archive-stage.*) ;;
    *) echo "unsafe archive staging path: $EVIDENCE_DIR" >&2; exit 70 ;;
  esac
else
  echo 'cannot create safe local archive staging directory' >&2
  exit 70
fi
for directory in manifests results logs meta; do
  mkdir "$EVIDENCE_DIR/$directory" || TRANSFER_STATUS=70
  if test "$RUN_ROOT_VALID" = true; then
    ssh "$REMOTE_HOST" "test -d '$REMOTE_RUN_ROOT/$directory'"
    REMOTE_DIRECTORY_STATUS=$?
    case "$REMOTE_DIRECTORY_STATUS" in
      0)
        rsync -a "$REMOTE_HOST:$REMOTE_RUN_ROOT/$directory/" "$EVIDENCE_DIR/$directory/" \
          || TRANSFER_STATUS=70
        ;;
      1) ;;
      *) TRANSFER_STATUS=70 ;;
    esac
  fi
done
cp "$PWD/build/cs2a-supervisor.log" "$EVIDENCE_DIR/meta/operator-supervisor.log" \
  || TRANSFER_STATUS=70
cp "$PWD/build/cs2a-supervisor-exit.txt" "$EVIDENCE_DIR/meta/operator-supervisor-exit.txt" \
  || TRANSFER_STATUS=70
cp "$PWD/build/cs2a-local-validation-driver.log" \
  "$EVIDENCE_DIR/meta/operator-local-validation-driver.log" || TRANSFER_STATUS=70
printf '%s\n' "$POST_SUPERVISOR_STATUS" \
  >"$EVIDENCE_DIR/meta/operator-post-supervisor-exit.txt"
printf '%s\n' "$RESUME_VALIDATION_STATUS" \
  >"$EVIDENCE_DIR/meta/operator-resume-validation-exit.txt"

ARCHIVE_VALID=true
test -x "$LOCAL_DRIVER" || TRANSFER_STATUS=70
if COPIED_MANIFEST=$(mktemp "$PWD/build/cs2a-copied-bytes.XXXXXXXX"); then
  case "$COPIED_MANIFEST" in
    "$PWD"/build/cs2a-copied-bytes.*) ;;
    *) echo "unsafe copied-byte manifest path: $COPIED_MANIFEST" >&2; exit 70 ;;
  esac
  (cd "$EVIDENCE_DIR" &&
    find . -type f -print0 | sort -z | xargs -0 -r sha256sum >"$COPIED_MANIFEST") \
    || TRANSFER_STATUS=70
  mv "$COPIED_MANIFEST" "$EVIDENCE_DIR/meta/remote-copied-bytes-sha256sums.txt" \
    || TRANSFER_STATUS=70
  (cd "$EVIDENCE_DIR" && sha256sum -c meta/remote-copied-bytes-sha256sums.txt) \
    || TRANSFER_STATUS=70
else
  TRANSFER_STATUS=70
fi
VALIDATION_LOG="$EVIDENCE_DIR/meta/local-validation.txt"
: >"$VALIDATION_LOG" || TRANSFER_STATUS=70

shopt -s nullglob
inputs=("$EVIDENCE_DIR"/results/*-aa.json "$EVIDENCE_DIR"/results/*-candidate.json)
for input in "${inputs[@]}"; do
  if "$LOCAL_DRIVER" verify --input "$input" >>"$VALIDATION_LOG" 2>&1; then
    status=0
  else
    status=$?
  fi
  printf 'verify\t%s\t%s\n' "$status" "${input#"$EVIDENCE_DIR/"}" >>"$VALIDATION_LOG"
  if test "$status" -ne 0; then ARCHIVE_VALID=false; fi
done
shopt -u nullglob

validate_manifest_copy() {
  local path=$1 expected_id=$2 expected_commit=$3
  if test ! -f "$path"; then return; fi
  if ! jq -e --arg id "$expected_id" --arg commit "$expected_commit" '
    .schema == "revoman-target-manifest/v1" and
    .targetId == $id and .gitCommit == $commit and .dirty == false and
    (.gitTree | type == "string" and length > 0) and
    (.classpath | type == "array" and length > 0) and
    ([.classpath[].logicalId] | length == (unique | length)) and
    all(.classpath[]; (.logicalId | type == "string" and length > 0) and
      (.sizeBytes | type == "number") and
      (.sha256 | type == "string" and length == 64))
  ' "$path" >>"$VALIDATION_LOG" 2>&1; then
    printf 'invalid-manifest-metadata\t%s\n' "${path#"$EVIDENCE_DIR/"}" >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
  fi
}

BASELINE_COMMIT=83f3cd70f78ad733412d10cbc8287aaabafe7aac
validate_manifest_copy "$EVIDENCE_DIR/manifests/baseline-a.json" baseline-a-cs2a "$BASELINE_COMMIT"
validate_manifest_copy "$EVIDENCE_DIR/manifests/baseline-b.json" baseline-b-cs2a "$BASELINE_COMMIT"
validate_manifest_copy "$EVIDENCE_DIR/manifests/candidate.json" candidate-cs2a \
  "$CS2A_IMPLEMENTATION_SHA"

validate_campaign_identity() {
  local path=$1 mode=$2 candidate_id=$3 candidate_adapter=$4 candidate_commit=$5
  local baseline_manifest candidate_manifest
  if test ! -f "$path"; then return; fi
  if test ! -f "$EVIDENCE_DIR/manifests/baseline-a.json"; then
    printf 'campaign-missing-baseline-manifest\t%s\n' "${path#"$EVIDENCE_DIR/"}" \
      >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
    return
  fi
  baseline_manifest=$(sha256sum "$EVIDENCE_DIR/manifests/baseline-a.json" | cut -d' ' -f1)
  case "$candidate_id" in
    baseline-b-cs2a)
      if test ! -f "$EVIDENCE_DIR/manifests/baseline-b.json"; then
        ARCHIVE_VALID=false
        return
      fi
      candidate_manifest=$(sha256sum "$EVIDENCE_DIR/manifests/baseline-b.json" | cut -d' ' -f1)
      ;;
    candidate-cs2a)
      if test ! -f "$EVIDENCE_DIR/manifests/candidate.json"; then
        ARCHIVE_VALID=false
        return
      fi
      candidate_manifest=$(sha256sum "$EVIDENCE_DIR/manifests/candidate.json" | cut -d' ' -f1)
      ;;
    *) ARCHIVE_VALID=false; return ;;
  esac
  if ! jq -e \
    --arg mode "$mode" \
    --arg policy 48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60 \
    --arg host 12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44 \
    --arg baselineCommit "$BASELINE_COMMIT" \
    --arg baselineManifest "$baseline_manifest" \
    --arg candidateId "$candidate_id" \
    --arg candidateAdapter "$candidate_adapter" \
    --arg candidateCommit "$candidate_commit" \
    --arg candidateManifest "$candidate_manifest" '
      .schema == "revoman-benchmark/v1" and .intent == "CONTROLLED" and
      .configuration.mode == $mode and
      .environment.policySha256 == $policy and
      .environment.hostFingerprintSha256 == $host and
      .configuration.targets == [
        {"role":"BASELINE","targetId":"baseline-a-cs2a","adapterId":"baseline-83f3cd70"},
        {"role":"CANDIDATE","targetId":$candidateId,"adapterId":$candidateAdapter}
      ] and
      ([.targets[] | select(
        .id == "baseline-a-cs2a" and .gitCommit == $baselineCommit and
        .dirty == false and .manifestSha256 == $baselineManifest and
        .adapter.id == "baseline-83f3cd70"
      )] | length == 1) and
      ([.targets[] | select(
        .id == $candidateId and .gitCommit == $candidateCommit and
        .dirty == false and .manifestSha256 == $candidateManifest and
        .adapter.id == $candidateAdapter
      )] | length == 1)
    ' "$path" >>"$VALIDATION_LOG" 2>&1; then
    printf 'campaign-identity-mismatch\t%s\n' "${path#"$EVIDENCE_DIR/"}" \
      >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
  fi
}

validate_campaign_identity "$EVIDENCE_DIR/results/cold-aa.json" COLD \
  baseline-b-cs2a baseline-83f3cd70 "$BASELINE_COMMIT"
validate_campaign_identity "$EVIDENCE_DIR/results/warm-aa.json" WARM \
  baseline-b-cs2a baseline-83f3cd70 "$BASELINE_COMMIT"
validate_campaign_identity "$EVIDENCE_DIR/results/cold-candidate.json" COLD \
  candidate-cs2a major-v1 "$CS2A_IMPLEMENTATION_SHA"
validate_campaign_identity "$EVIDENCE_DIR/results/warm-candidate.json" WARM \
  candidate-cs2a major-v1 "$CS2A_IMPLEMENTATION_SHA"
validate_campaign_identity "$EVIDENCE_DIR/results/retained-candidate.json" RETAINED \
  candidate-cs2a major-v1 "$CS2A_IMPLEMENTATION_SHA"

RECOMPARE=
if candidate=$(mktemp -d "$PWD/build/cs2a-recompare.XXXXXXXX"); then
  case "$candidate" in
    "$PWD"/build/cs2a-recompare.*) RECOMPARE=$candidate ;;
    *) ARCHIVE_VALID=false; TRANSFER_STATUS=70 ;;
  esac
else
  ARCHIVE_VALID=false
  TRANSFER_STATUS=70
fi
recompare_if_complete() {
  local label=$1 input=$2 archived_json=$3 archived_md=$4 expected_exit=$5 status
  if test ! -f "$input" && test ! -f "$archived_json" && test ! -f "$archived_md"; then
    return 0
  fi
  if test ! -f "$input" || test ! -f "$archived_json" || test ! -f "$archived_md" \
    || test ! -f "$expected_exit"; then
    printf 'incomplete-comparison\t%s\n' "$label" >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
    return 0
  fi
  if test -z "$RECOMPARE"; then
    printf 'missing-safe-recompare-directory\t%s\n' "$label" >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
    return 0
  fi
  if "$LOCAL_DRIVER" compare --input "$input" \
    --output-json "$RECOMPARE/$label.json" \
    --output-md "$RECOMPARE/$label.md" --enforce-release-gates \
    >>"$VALIDATION_LOG" 2>&1; then
    status=0
  else
    status=$?
  fi
  if test "$status" != "$(cat "$expected_exit")" \
    || ! cmp -s "$RECOMPARE/$label.json" "$archived_json" \
    || ! cmp -s "$RECOMPARE/$label.md" "$archived_md"; then
    ARCHIVE_VALID=false
  fi
  printf 'recompare\t%s\t%s\n' "$status" "$label" >>"$VALIDATION_LOG"
}

for mode in cold warm; do
  recompare_if_complete "comparison-aa-$mode" \
    "$EVIDENCE_DIR/results/$mode-aa.json" \
    "$EVIDENCE_DIR/results/comparison-aa-$mode.json" \
    "$EVIDENCE_DIR/results/comparison-aa-$mode.md" \
    "$EVIDENCE_DIR/meta/comparison-aa-$mode-exit.txt"
done
for mode in cold warm retained; do
  recompare_if_complete "comparison-candidate-$mode" \
    "$EVIDENCE_DIR/results/$mode-candidate.json" \
    "$EVIDENCE_DIR/results/comparison-candidate-$mode.json" \
    "$EVIDENCE_DIR/results/comparison-candidate-$mode.md" \
    "$EVIDENCE_DIR/meta/comparison-candidate-$mode-exit.txt"
done

require_archive_file() {
  if test ! -f "$EVIDENCE_DIR/$1"; then
    printf 'missing\t%s\n' "$1" >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
  fi
}
for required in \
  meta/cs2a-controlled-run.sh \
  meta/cs2a-governor-supervisor.sh \
  meta/cs2a-operator.sh \
  meta/operator-script-sha256sums.txt \
  meta/operator-post-supervisor-exit.txt \
  meta/operator-resume-validation-exit.txt \
  meta/operator-local-validation-driver.log \
  meta/implementation-sha.txt \
  meta/controlled-host.json \
  meta/policy-sha256.txt \
  meta/policy-semantic-sha256.txt \
  meta/run-root.txt \
  meta/commands.tsv \
  meta/runner-exit.txt \
  meta/inventory-exit.txt \
  meta/evidence-sha256sums.txt \
  meta/artifact-inventory.tsv \
  meta/artifact-sha256sums.txt \
  meta/command-output-sha256sums.txt; do
  require_archive_file "$required"
done

if test -f "$EVIDENCE_DIR/meta/operator-script-sha256sums.txt"; then
  if ! (cd "$EVIDENCE_DIR/meta" && sha256sum -c operator-script-sha256sums.txt) \
    >>"$VALIDATION_LOG" 2>&1; then
    ARCHIVE_VALID=false
  fi
fi
if test -f "$EVIDENCE_DIR/meta/implementation-sha.txt" \
  && test "$(cat "$EVIDENCE_DIR/meta/implementation-sha.txt")" \
    != "$CS2A_IMPLEMENTATION_SHA"; then
  printf 'implementation-sha-mismatch\n' >>"$VALIDATION_LOG"
  ARCHIVE_VALID=false
fi
if test -f "$EVIDENCE_DIR/meta/controlled-host.json" \
  && test -f "$EVIDENCE_DIR/meta/policy-sha256.txt"; then
  ARCHIVED_POLICY_SHA=$(sha256sum "$EVIDENCE_DIR/meta/controlled-host.json" | cut -d' ' -f1)
  RECORDED_POLICY_SHA=$(awk 'NR == 1 { print $1 }' "$EVIDENCE_DIR/meta/policy-sha256.txt")
  if test "$ARCHIVED_POLICY_SHA" != 7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79 \
    || test "$RECORDED_POLICY_SHA" != "$ARCHIVED_POLICY_SHA"; then
    printf 'policy-sha-mismatch\n' >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
  fi
fi
if test -f "$EVIDENCE_DIR/meta/policy-semantic-sha256.txt" \
  && test "$(cat "$EVIDENCE_DIR/meta/policy-semantic-sha256.txt")" \
    != 48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60; then
  printf 'policy-semantic-sha-mismatch\n' >>"$VALIDATION_LOG"
  ARCHIVE_VALID=false
fi
for required in \
  meta/operator-supervisor.log \
  meta/operator-supervisor-exit.txt \
  meta/supervisor/child-or-supervisor-status.txt \
  meta/supervisor/restoration-failed.txt \
  meta/supervisor/containment-failed.txt \
  meta/supervisor/finished-at.txt \
  meta/supervisor/original-governors.tsv \
  meta/supervisor/run-root.txt \
  meta/supervisor/operator-post-supervisor-exit.txt \
  meta/supervisor/executed-script-sha256sums.tsv; do
  require_archive_file "$required"
done

validate_executed_script() {
  local role=$1 script=$2 rows actual expected
  rows=$(awk -F '\t' -v role="$role" '$1 == role { print $2 }' \
    "$EVIDENCE_DIR/meta/supervisor/executed-script-sha256sums.tsv" 2>/dev/null || true)
  if test -z "$rows" \
    || test "$(printf '%s\n' "$rows" | wc -l | tr -d ' ')" -ne 1; then
    printf 'invalid-executed-script-row\t%s\n' "$role" >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
    return
  fi
  actual=$rows
  expected=$(sha256sum "$EVIDENCE_DIR/meta/$script" | cut -d' ' -f1)
  if test "$actual" != "$expected"; then
    printf 'executed-script-mismatch\t%s\t%s\t%s\n' \
      "$role" "$actual" "$expected" >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
  fi
}
if test -f "$EVIDENCE_DIR/meta/supervisor/executed-script-sha256sums.tsv"; then
  EXECUTED_ROWS="$EVIDENCE_DIR/meta/supervisor/executed-script-sha256sums.tsv"
  if ! awk -F '\t' '
    NF != 2 { exit 1 }
    $1 != "runner" && $1 != "supervisor" { exit 1 }
    length($2) != 64 || $2 !~ /^[0-9a-f]+$/ { exit 1 }
    { count[$1]++; total++ }
    END { exit !(total == 2 && count["runner"] == 1 && count["supervisor"] == 1) }
  ' "$EXECUTED_ROWS"; then
    printf 'invalid-executed-script-row-set\n' >>"$VALIDATION_LOG"
    ARCHIVE_VALID=false
  else
    validate_executed_script runner cs2a-controlled-run.sh
    validate_executed_script supervisor cs2a-governor-supervisor.sh
  fi
fi
if test -f "$EVIDENCE_DIR/meta/stage.txt"; then
  STAGE=$(cat "$EVIDENCE_DIR/meta/stage.txt")
else
  STAGE=missing
  ARCHIVE_VALID=false
  printf 'missing\tmeta/stage.txt\n' >>"$VALIDATION_LOG"
fi
case "$STAGE" in
  setup | aa-captured | aa-compared | candidate-captured | candidate-compared) ;;
  *) ARCHIVE_VALID=false; printf 'invalid-stage\t%s\n' "$STAGE" >>"$VALIDATION_LOG" ;;
esac
case "$STAGE" in
  aa-captured | aa-compared | candidate-captured | candidate-compared)
    require_archive_file manifests/baseline-a.json
    require_archive_file manifests/baseline-b.json
    require_archive_file manifests/candidate.json
    require_archive_file results/cold-aa.json
    require_archive_file results/warm-aa.json
    ;;
esac
case "$STAGE" in
  aa-compared | candidate-captured | candidate-compared)
    for mode in cold warm; do
      require_archive_file "results/comparison-aa-$mode.json"
      require_archive_file "results/comparison-aa-$mode.md"
    done
    ;;
esac
case "$STAGE" in
  candidate-captured | candidate-compared)
    for mode in cold warm retained; do
      require_archive_file "results/$mode-candidate.json"
    done
    ;;
esac
if test "$STAGE" = candidate-compared; then
  for mode in cold warm retained; do
    require_archive_file "results/comparison-candidate-$mode.json"
    require_archive_file "results/comparison-candidate-$mode.md"
  done
fi
printf '%s\n' "$ARCHIVE_VALID" >"$EVIDENCE_DIR/meta/local-validation-passed.txt"
FINAL_OPERATOR_STATUS=$SUPERVISOR_STATUS
if test "$POST_SUPERVISOR_STATUS" -ne 0 || test "$RESUME_VALIDATION_STATUS" -ne 0 \
  || test "$TRANSFER_STATUS" -ne 0 \
  || test "$ARCHIVE_VALID" != true; then
  if test "$FINAL_OPERATOR_STATUS" -eq 0; then FINAL_OPERATOR_STATUS=70; fi
fi
printf '%s\n' "$FINAL_OPERATOR_STATUS" >"$EVIDENCE_DIR/meta/operator-final-exit.txt"
(cd "$EVIDENCE_DIR" &&
  find . -type f ! -name evidence-sha256sums.txt -print0 \
    | sort -z | xargs -0 -r sha256sum >evidence-sha256sums.txt &&
  sha256sum -c evidence-sha256sums.txt)
CHECKSUM_STATUS=$?
if test "$CHECKSUM_STATUS" -ne 0; then
  TRANSFER_STATUS=70
  FINAL_OPERATOR_STATUS=70
  printf '%s\n' "$FINAL_OPERATOR_STATUS" >"$EVIDENCE_DIR/meta/operator-final-exit.txt"
  if ! (cd "$EVIDENCE_DIR" &&
    find . -type f ! -name evidence-sha256sums.txt -print0 \
      | sort -z | xargs -0 -r sha256sum >evidence-sha256sums.txt &&
    sha256sum -c evidence-sha256sums.txt); then
    printf 'regenerated checksum verification failed\n' >&2
  fi
fi
if test "$TRANSFER_STATUS" -eq 0 \
  && test ! -e "$FINAL_EVIDENCE_DIR" \
  && mv "$EVIDENCE_DIR" "$FINAL_EVIDENCE_DIR"; then
  EVIDENCE_DIR=$FINAL_EVIDENCE_DIR
  printf '%s\n' "$EVIDENCE_DIR" >"$PWD/build/cs2a-local-evidence-dir.txt"
  printf 'LOCAL_EVIDENCE_DIR=%s\n' "$EVIDENCE_DIR"
else
  FINAL_OPERATOR_STATUS=70
  printf '%s\n' "$FINAL_OPERATOR_STATUS" >"$EVIDENCE_DIR/meta/operator-final-exit.txt"
  (cd "$EVIDENCE_DIR" &&
    find . -type f ! -name evidence-sha256sums.txt -print0 \
      | sort -z | xargs -0 -r sha256sum >evidence-sha256sums.txt)
  printf 'LOCAL_EVIDENCE_STAGING_DIR=%s\n' "$EVIDENCE_DIR"
fi
exit "$FINAL_OPERATOR_STATUS"
```

Invoke that checked-in operator from the clean implementation worktree with fail-fast disabled only
long enough to capture its status. When it publishes a canonical local archive, request the
Standards + Spec archive review immediately and commit that one attempt before any rerun or source
correction, regardless of PASS/FAIL/INCONCLUSIVE/invalid status:

```bash
: "${CS2A_IMPLEMENTATION_SHA:?export the exact measured implementation SHA}"
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
rm -f "$PWD/build/cs2a-local-evidence-dir.txt"
set +e
/bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh
OPERATOR_STATUS=$?
set -e
printf '%s\n' "$OPERATOR_STATUS" >"$PWD/build/cs2a-operator-status.txt"

test -s "$PWD/build/cs2a-local-evidence-dir.txt"
```

Pause for the required independent archive review. In the same or a fresh Bash shell, invoke the
operator's self-contained persistence mode; it rehydrates the measured SHA, verifies the final
checksum and exact Git scope, and commits only the canonical attempt. Persistence returns `0` only
after the commit/hash/clean-tree checks and `70` for any persistence failure; the original operator
status remains a separate recorded value and is propagated only afterward:

```bash
CS2A_IMPLEMENTATION_SHA=${CS2A_IMPLEMENTATION_SHA:-$(cat "$PWD/build/cs2a-implementation-sha")}
export CS2A_IMPLEMENTATION_SHA
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
OPERATOR_STATUS=$(cat "$PWD/build/cs2a-operator-status.txt")
set +e
/bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  --persist-only "$OPERATOR_STATUS"
PERSIST_STATUS=$?
set -e
test "$PERSIST_STATUS" -eq 0

if test "$OPERATOR_STATUS" -ne 0; then
  exit "$OPERATOR_STATUS"
fi
```

If transfer/checksum infrastructure fails before the atomic rename, the operator prints only a
safe staging path and deliberately does not populate `cs2a-local-evidence-dir.txt`; repair/retry the
copy from the same immutable remote run root with the checked-in archive-only mode. Recover both
authenticated markers from the original supervisor log, require exactly one of each, and invoke:

```bash
REMOTE_RUN_ROOT=$(tr -d '\r' <"$PWD/build/cs2a-supervisor.log" \
  | sed -n 's/^RUN_ROOT=//p')
GOVERNOR_STATE=$(tr -d '\r' <"$PWD/build/cs2a-supervisor.log" \
  | sed -n 's/^GOVERNOR_STATE=//p')
test -n "$REMOTE_RUN_ROOT"
test "$(printf '%s\n' "$REMOTE_RUN_ROOT" | wc -l | tr -d ' ')" -eq 1
test -n "$GOVERNOR_STATE"
test "$(printf '%s\n' "$GOVERNOR_STATE" | wc -l | tr -d ' ')" -eq 1
CS2A_IMPLEMENTATION_SHA=${CS2A_IMPLEMENTATION_SHA:-$(cat "$PWD/build/cs2a-implementation-sha")}
export CS2A_IMPLEMENTATION_SHA
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
rm -f "$PWD/build/cs2a-local-evidence-dir.txt"
set +e
/bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  --archive-only "$REMOTE_RUN_ROOT" "$GOVERNOR_STATE"
OPERATOR_STATUS=$?
set -e
printf '%s\n' "$OPERATOR_STATUS" >"$PWD/build/cs2a-operator-status.txt"
test -s "$PWD/build/cs2a-local-evidence-dir.txt"
```

Archive-only mode executes the same detached-source/script/policy authentication, validates the
root-owned `run-root.txt` cross-link and executed hashes, rechecks lock release/governor
restoration, refreshes the supervisor handoff, and enters only the common fresh-staging local
copy/validation/checksum/atomic-publish tail. It never installs or invokes the supervisor/runner.
The first run's post-supervisor status is persisted root-owned before any local staging and is never
recomputed or reset by recovery; archive-only records its own validation status separately, and
selection requires both values to be zero. If both original persistence attempts failed, recovery
uses a root `noclobber` create to record the only safe value, `70`; it never overwrites an existing
root value, and that recovered attempt can be archived but cannot be selected as PASS.
After a canonical publish and independent review, execute the exact self-contained
`--persist-only` block above; otherwise retain the staging path and retry this same command. Never commit a staging directory as an attempt and
never rerun measurements merely because archival transport failed. If review finds a code/harness
defect, the failed attempt commit is the clean parent of the corrective implementation commit; the
new implementation SHA must use a new remote root. The attempt commit itself is the append-only
registry, and `git log -1 -- <attempt-directory>` recovers its immutable evidence SHA.

Before accepting the archive, copy the root supervisor's final status/restoration/containment files
into `meta/supervisor/` through the privileged operator and recompute the local checksum manifest.
Every attempt archives and hashes every byte that exists plus the raw+semantic policy hashes,
checked-in runner/supervisor sources and hashes, exact command/status/stage logs, and artifact/JFR
inventories. Stage-aware validation never fabricates or requires files from phases that did not
run. Only a `candidate-compared` attempt requires all three manifests, both raw A/A campaign JSON
files, all three raw candidate campaign JSON files, and all five comparison JSON/Markdown pairs.
An invalid partial file makes `local-validation-passed=false` but is still committed as failed
operator evidence. Preserve and report each A/A comparator's exact machine decision; any A/A
decision other than PASS makes only the overall CS2a candidate conclusion INCONCLUSIVE, stops
candidate capture, and is never rerun merely to seek PASS. A new attempt is permitted only after a documented code, harness, or objectively
measured host-state correction; preserve and register every prior attempt under its own key.

Before this command block, verify all four checkouts are clean and detached at their exact full
SHAs; the two baseline checkouts must both be `83f3cd70f78ad733412d10cbc8287aaabafe7aac`, and the
harness/candidate checkout must be the reviewed CS2a head. Acquire the same exclusive controlled
host lease and failure-safe governor restoration used by Task 13. A failed A/A comparison stops
candidate measurement. Never weaken the host policy or rerun into any path under `RUN_ROOT`.

The required release decisions are:

- cold: at least 50 fresh-process samples per role with latency, JFR allocation, and peak RSS;
- warm: at least five independent JMH forks with latency and `gc.alloc.rate.norm`;
- retained: five accepted paired host blocks, with fresh 1,000/2,000/4,000 execution JVMs per role/replicate group and real candidate weak outcomes.

Preserve rejected blocks and unfavorable evidence. Cold upper-95 candidate/baseline ratios must be
at most `1.05` median, `1.10` p95, `1.05` allocation mean, and `1.05` peak-RSS mean. Warm upper-95
ratios must be at most `1.03` median, `1.05` p95, and `1.03` allocation mean. Retained evidence must
contain exactly the expected baseline/candidate weak-reference groups, every candidate
`ExecutionSession`/`KickExecution` reference must clear, and the candidate retained-slope upper-95
endpoint must be at most `1,024 bytes/execution`. Report PASS, FAIL, or INCONCLUSIVE exactly as
evaluated; a normative PASS is not automatically a targeted performance win.

- [ ] **Step 6: Select and re-review the already immutable evidence archive**

Each attempt was already independently reviewed and committed immediately after copy-back. Re-run
Standards + Spec review over the complete selected attempt's operator script/status, identity/hash,
stage-aware validation, comparator decisions, and draft-report claims. Preserve Critical/Important
findings as a new corrective implementation plus never-reused attempt; never amend or rewrite an
attempt commit. A normative FAIL or INCONCLUSIVE blocks the CS2a merge; the two optional
targeted-win thresholds do not. Select one already committed complete valid attempt whose normative
decisions all pass:

```bash
: "${SELECTED_ATTEMPT:?export the exact complete cs2a attempt directory basename}"
: "${CS2A_IMPLEMENTATION_SHA:?export the exact measured implementation SHA from the attempt}"
[[ "$SELECTED_ATTEMPT" =~ ^cs2a\.[A-Za-z0-9]+$ ]]
[[ "$CS2A_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
SELECTED="docs/superpowers/benchmarks/results/v1/cs2a-$CS2A_IMPLEMENTATION_SHA/$SELECTED_ATTEMPT"
test -d "$SELECTED"
test -z "$(git status --porcelain --untracked-files=all -- "$SELECTED")"
test -n "$(git ls-files -- "$SELECTED")"
CS2A_EVIDENCE_SHA=$(git log -1 --format=%H -- "$SELECTED")
[[ "$CS2A_EVIDENCE_SHA" =~ ^[0-9a-f]{40}$ ]]
test "$(cat "$SELECTED/meta/stage.txt")" = candidate-compared
test "$(cat "$SELECTED/meta/local-validation-passed.txt")" = true
test "$(cat "$SELECTED/meta/implementation-sha.txt")" = "$CS2A_IMPLEMENTATION_SHA"
test "$(cat "$SELECTED/meta/runner-exit.txt")" = 0
test "$(cat "$SELECTED/meta/inventory-exit.txt")" = 0
test "$(cat "$SELECTED/meta/supervisor/child-or-supervisor-status.txt")" = 0
test "$(cat "$SELECTED/meta/supervisor/restoration-failed.txt")" = false
test "$(cat "$SELECTED/meta/supervisor/containment-failed.txt")" = false
test "$(cat "$SELECTED/meta/supervisor/run-root.txt")" = \
  "$(cat "$SELECTED/meta/run-root.txt")"
test "$(cat "$SELECTED/meta/operator-supervisor-exit.txt")" = 0
test "$(cat "$SELECTED/meta/operator-post-supervisor-exit.txt")" = 0
test "$(cat "$SELECTED/meta/operator-resume-validation-exit.txt")" = 0
test "$(cat "$SELECTED/meta/supervisor/operator-post-supervisor-exit.txt")" = \
  "$(cat "$SELECTED/meta/operator-post-supervisor-exit.txt")"
test "$(cat "$SELECTED/meta/operator-final-exit.txt")" = 0
test -s "$SELECTED/meta/commands.tsv"
for script in cs2a-controlled-run.sh cs2a-governor-supervisor.sh cs2a-operator.sh; do
  test -s "$SELECTED/meta/$script"
done
(cd "$SELECTED/meta" && sha256sum -c operator-script-sha256sums.txt)
for manifest in baseline-a baseline-b candidate; do
  test "$(cat "$SELECTED/logs/verify-manifest-$manifest.exit")" = 0
done
test "$(wc -l <"$SELECTED/meta/supervisor/executed-script-sha256sums.tsv" | tr -d ' ')" = 2
awk -F '\t' '
  NF != 2 { exit 1 }
  $1 != "runner" && $1 != "supervisor" { exit 1 }
  length($2) != 64 || $2 !~ /^[0-9a-f]+$/ { exit 1 }
  { count[$1]++ }
  END { exit !(count["runner"] == 1 && count["supervisor"] == 1) }
' "$SELECTED/meta/supervisor/executed-script-sha256sums.tsv"
test "$(awk -F '\t' '$1 == "runner" { print $2 }' \
  "$SELECTED/meta/supervisor/executed-script-sha256sums.tsv")" = \
  "$(sha256sum "$SELECTED/meta/cs2a-controlled-run.sh" | cut -d' ' -f1)"
test "$(awk -F '\t' '$1 == "supervisor" { print $2 }' \
  "$SELECTED/meta/supervisor/executed-script-sha256sums.tsv")" = \
  "$(sha256sum "$SELECTED/meta/cs2a-governor-supervisor.sh" | cut -d' ' -f1)"
for campaign in cold-aa warm-aa cold-candidate warm-candidate retained-candidate; do
  test "$(cat "$SELECTED/meta/$campaign-exit.txt")" = 0
done
for report in comparison-aa-cold comparison-aa-warm \
  comparison-candidate-cold comparison-candidate-warm comparison-candidate-retained; do
  test "$(cat "$SELECTED/meta/$report-exit.txt")" = 0
done
(cd "$SELECTED" && sha256sum -c evidence-sha256sums.txt)
test "$(git show "$CS2A_EVIDENCE_SHA:$SELECTED/meta/implementation-sha.txt")" = \
  "$CS2A_IMPLEMENTATION_SHA"
```

- [ ] **Step 7: Write the report and land CS2a**

Write the report with the exact `CS2A_IMPLEMENTATION_SHA` and `CS2A_EVIDENCE_SHA`, commit range, API removals, migration links, exact commands/results, diagnostic evidence, benchmark identities/hashes, confidence decisions, every preserved attempt, known limitations, and the merge ordering rule for the future ABI dump. Commit the documentation before rendering because `antora-playbook.yml` reads `HEAD`, then run `npx antora antora-playbook.yml` and require zero unresolved xrefs/includes. If a linked worktree's `.git` indirection is rejected, apply the exact documentation commit to a disposable ordinary clone and record that command/result rather than omitting the gate.

Verify clean scope:

```bash
git status --short
git diff --check
git log --oneline ea3a4da2..HEAD
```

Commit only the final report reconciliation; implementation and evidence are already immutable
parents:

```bash
git add docs/superpowers/reports/2026-08-11-performance-cs2a-runtime-lifecycle.md
git commit -m "docs: document execution lifecycle migration"
npx antora antora-playbook.yml
```

After all commits and reviews are green, merge CS2a to local `master`, push `master`, and wait for exact-SHA Build, Qodana, and Docs CI. Only then branch CS2b, CS2c, and CS2d in parallel. For generated `api/revoman-root.api` conflicts in later branches, regenerate with `./gradlew updateKotlinAbi` after each merge; never hand-merge generated ABI text.

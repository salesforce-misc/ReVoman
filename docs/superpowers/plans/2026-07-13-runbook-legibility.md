# Runbook Legibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a ReVoman test's Postman-collection chain legible from one declaration — surfaced in the test code (a `Runbook` DSL), an overhauled grouped log, and an on-demand mermaid/markdown view.

**Architecture:** A thin `Runbook` layer wraps each `Kick` as a narrated `RunbookStep` (intent/phase/produces/consumes/assertAfter). A `revUp(Runbook)` overload drives a fold that mirrors the existing multi-kick `revUp(List<Kick>)` env threading exactly, interleaving per-step contract checks + `assertAfter` and emitting coarse log events that bracket the existing per-request events. `Kick` is untouched; the existing `revUp` overloads stay.

**Tech Stack:** Kotlin (JVM 21), Java interop (`fun interface`, `Consumer`, `@JvmStatic`), Kotest/JUnit5 + Google Truth, `com.sun.net.httpserver.HttpServer` for network-free E2E.

## Global Constraints

- JDK 21+; Kotlin four-space indent; `when` over if-else chains (STYLE.md).
- Functional style: state transformation over mutation; `fold`/`map`/`firstOrNull` over loops; monadic ops over `when` on `Either`/`Result` (STYLE.md).
- Copyright header (Apache 2.0, `Copyright (c) 2023, Salesforce, Inc.`) at the top of every new `.kt` file — copy verbatim from `KickDef.kt:1-7`.
- `Kick` (`KickDef.kt`) MUST NOT be modified. Existing `revUp(vararg Kick)` / `revUp(List<Kick>)` MUST remain behavior-identical.
- New public APIs get KDoc (AGENTS.md). Add logging for new features (AGENTS.md).
- Run `./gradlew spotlessApply` before every commit; verify with `./gradlew test` (unit). The 6 restful-api.dev integrationTest failures are a known env quota flake, NOT regressions.
- Reuse the existing sink pipeline (`RunLogSink`/`StepEvent`/`RevomanLog.event`) — do NOT add a new sink type.

## Existing symbols this plan builds on (verbatim signatures)

- `com.salesforce.revoman.ReVoman` — `object`. `revUp(kick: Kick): Rundown`; `revUp(kicks: List<Kick>, postExeHook, dynamicEnvironment): List<Rundown>`; multi-kick fold at `ReVoman.kt:89-103`.
- `com.salesforce.revoman.output.Rundown` — `data class`; `@JvmField val mutableEnv: PostmanEnvironment<Any?>`; `val immutableEnv: Map<String, Any?>`; `val firstUnIgnoredUnsuccessfulStepReport: StepReport?`; `@JvmField val stepReports: List<StepReport>`.
- `com.salesforce.revoman.output.postman.PostmanEnvironment<ValueT>` — `data class ... : MutableMap<String, ValueT> by mutableEnv`; so `containsKey(k)`, `keys`, `get(k)` work directly; `fun getAsString(key: String?): String?`.
- `com.salesforce.revoman.input.config.Kick` — Immutable; `Kick.configure().templatePath(..)...off()`; `fun dynamicEnvironment(): Map<String, Any?>`; `fun overrideDynamicEnvironment(Map): Kick` (Immutables `override*` from `@Value.Style`).
- `com.salesforce.revoman.output.log.StepEvent` — `sealed interface`; `val path: String`; existing subtypes `StepStarted`, `StepFinished`, `LedgerSkipped`, `RequestSkipped`, `Jumped`, `RunStopped`, `LoopBudgetExceeded`; `enum class Outcome { SUCCESS, FAILED, SKIPPED }`.
- `com.salesforce.revoman.output.log.RunLogSink` — `interface`; `fun event(event: StepEvent)`; `object NoOp`.
- `com.salesforce.revoman.output.log.ConsoleRunLogSink` — `class ConsoleRunLogSink(out: PrintStream)`; `private fun render(event: StepEvent): String`; `companion object { @JvmField val DEFAULT }`.
- `com.salesforce.revoman.internal.log.RevomanLog` — `internal object`; `fun event(event: StepEvent)` (safe, swallows sink errors).
- Test harness pattern: loopback `HttpServer` + `pm-templates/v3/cf-ledger-jump`, see `MultiKickEnvTypesE2ETest.kt:58-76`.

---

### Task 1: Data model — `Phase`, `StepAssertion`, `StepSpec`, `RunbookStep`

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/input/config/RunbookStep.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/input/config/StepSpecTest.kt`

**Interfaces:**
- Consumes: `Kick`, `Rundown`, `PostmanEnvironment` (existing).
- Produces:
  - `enum class Phase { SETUP, SEED, ACT, ASSERT, CLEANUP }`
  - `fun interface StepAssertion { fun assertStep(rundown: Rundown, env: PostmanEnvironment<Any?>) }`
  - `class StepSpec` — mutable spec serving BOTH the Kotlin `step { }` receiver and the Java `Consumer<StepSpec>`. Fields: `var intent: String`, `var phase: Phase`, `var kick: Kick?` (default null). Fluent methods (each returns `StepSpec`): `consumes(vararg keys: String)`, `produces(vararg keys: String)`, `produces(keyToValue: Map<String, String?>)`, `produces(pair: Pair<String, String?>)`, `underTest()`, `assertAfter(assertion: StepAssertion)`. `fun build(): RunbookStep`.
  - `data class RunbookStep(val intent: String, val phase: Phase, val kick: Kick, val consumes: Set<String>, val produces: Map<String, String?>, val underTest: Boolean, val assertAfter: StepAssertion?)`

**Design note:** This model uses Kotlin data class + a mutable `StepSpec` builder rather than the Immutables convention used by `Kick`, because (a) `assertAfter` is a lambda (awkward in Immutables) and (b) one `StepSpec` must serve both the Kotlin lambda-with-receiver and the Java `Consumer` fluent style. Note this deviation in the file's KDoc.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StepSpecTest {
    private fun anyKick(): Kick = Kick.configure().templatePath("pm-templates/v3/cf-stop").off()

    @Test
    fun `build snapshots spec into an immutable RunbookStep`() {
        val step =
            StepSpec()
                .apply {
                    intent = "seed fixture"
                    phase = Phase.SEED
                    kick = anyKick()
                }
                .consumes("authToken")
                .produces("accountId", "shiftIds")
                .build()
        assertThat(step.intent).isEqualTo("seed fixture")
        assertThat(step.phase).isEqualTo(Phase.SEED)
        assertThat(step.consumes).containsExactly("authToken")
        assertThat(step.produces.keys).containsExactly("accountId", "shiftIds")
        assertThat(step.produces.values.all { it == null }).isTrue()
        assertThat(step.underTest).isFalse()
        assertThat(step.assertAfter).isNull()
    }

    @Test
    fun `produces with values and underTest are captured`() {
        val step =
            StepSpec()
                .apply {
                    intent = "act"
                    phase = Phase.ACT
                    kick = anyKick()
                }
                .underTest()
                .produces("schedulingStatus" to "Success")
                .assertAfter { _, _ -> }
                .build()
        assertThat(step.underTest).isTrue()
        assertThat(step.produces).containsEntry("schedulingStatus", "Success")
        assertThat(step.assertAfter).isNotNull()
    }

    @Test
    fun `build without a kick fails fast`() {
        val ex = assertThrows<IllegalStateException> {
            StepSpec().apply { intent = "no kick"; phase = Phase.SETUP }.build()
        }
        assertThat(ex).hasMessageThat().contains("kick")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.StepSpecTest"`
Expected: FAIL — unresolved references `StepSpec`, `Phase`, `RunbookStep`.

- [ ] **Step 3: Write minimal implementation**

Create `RunbookStep.kt` (with the copyright header). Implement:

```kotlin
package com.salesforce.revoman.input.config

import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment

/** The story phase a [RunbookStep] belongs to; drives log grouping. Mirrors the comment blocks
 *  that today separate setup/seed/act config constants. */
enum class Phase {
    SETUP,
    SEED,
    ACT,
    ASSERT,
    CLEANUP,
}

/** A per-step assertion run after a step's [Rundown] is produced; receives that step's rundown and
 *  the accumulated env. A thrown [AssertionError] halts the runbook. Complementary to the contract
 *  checks and to the whole-run `postExeHook`. */
fun interface StepAssertion {
    fun assertStep(rundown: Rundown, env: PostmanEnvironment<Any?>)
}

/** Mutable builder serving BOTH the Kotlin `step { }` receiver DSL and the Java
 *  `Consumer<StepSpec>` configurator. Snapshot into an immutable [RunbookStep] via [build].
 *  Deviates from the Immutables convention used by [Kick] because [assertAfter] is a lambda and one
 *  spec must serve both language front doors. */
class StepSpec {
    var intent: String = ""
    var phase: Phase = Phase.SETUP
    var kick: Kick? = null

    private val consumes: MutableSet<String> = linkedSetOf()
    private val produces: MutableMap<String, String?> = linkedMapOf()
    private var underTest: Boolean = false
    private var assertAfter: StepAssertion? = null

    fun consumes(vararg keys: String): StepSpec = apply { consumes += keys }

    fun produces(vararg keys: String): StepSpec = apply { keys.forEach { produces[it] = null } }

    fun produces(keyToValue: Map<String, String?>): StepSpec = apply { produces += keyToValue }

    fun produces(pair: Pair<String, String?>): StepSpec = apply { produces[pair.first] = pair.second }

    fun underTest(): StepSpec = apply { underTest = true }

    fun assertAfter(assertion: StepAssertion): StepSpec = apply { assertAfter = assertion }

    fun build(): RunbookStep =
        RunbookStep(
            intent = intent,
            phase = phase,
            kick = checkNotNull(kick) { "A runbook `step` requires a `kick` (was null for intent='$intent')" },
            consumes = consumes.toSet(),
            produces = produces.toMap(),
            underTest = underTest,
            assertAfter = assertAfter,
        )
}

/** Immutable snapshot of one runbook step: a [Kick] wrapped with narration. */
data class RunbookStep(
    val intent: String,
    val phase: Phase,
    val kick: Kick,
    val consumes: Set<String>,
    val produces: Map<String, String?>,
    val underTest: Boolean,
    val assertAfter: StepAssertion?,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.StepSpecTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/input/config/RunbookStep.kt src/test/kotlin/com/salesforce/revoman/input/config/StepSpecTest.kt
git commit -m "feat: runbook step data model (Phase, StepSpec, RunbookStep, StepAssertion)"
```

---

### Task 2: `Runbook` + dual-language builders

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/input/config/Runbook.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/input/config/RunbookDslTest.kt`
- Test: `src/test/java/com/salesforce/revoman/input/config/RunbookJavaDslTest.java`

**Interfaces:**
- Consumes: `StepSpec`, `RunbookStep`, `Phase`, `Kick` (Task 1).
- Produces:
  - `class Runbook internal constructor(val name: String?, val steps: List<RunbookStep>)`
  - Java builder: `Runbook.configure(): RunbookBuilder`; `RunbookBuilder.name(String): RunbookBuilder`; `RunbookBuilder.step(intent: String, phase: Phase, kick: Kick): RunbookBuilder`; `RunbookBuilder.step(intent: String, phase: Phase, kick: Kick, configurator: Consumer<StepSpec>): RunbookBuilder`; `RunbookBuilder.off(): Runbook`.
  - Kotlin DSL: top-level `fun Runbook(name: String? = null, block: RunbookBuilder.() -> Unit): Runbook`; `fun RunbookBuilder.step(block: StepSpec.() -> Unit)`.
  - `@DslMarker annotation class RunbookDsl` on `RunbookBuilder` and `StepSpec` receivers.

- [ ] **Step 1: Write the failing Kotlin test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RunbookDslTest {
    private fun anyKick(name: String): Kick =
        Kick.configure().templatePath("pm-templates/v3/$name").off()

    @Test
    fun `Kotlin receiver DSL builds ordered steps`() {
        val runbook =
            Runbook(name = "wfs double book") {
                step {
                    intent = "login as admin"
                    phase = Phase.SETUP
                    kick = anyKick("cf-stop")
                    produces("authToken")
                }
                step {
                    intent = "schedule"
                    phase = Phase.ACT
                    kick = anyKick("cf-loop")
                    underTest()
                    consumes("authToken")
                    produces("schedulingStatus" to "Success")
                }
            }
        assertThat(runbook.name).isEqualTo("wfs double book")
        assertThat(runbook.steps.map { it.intent })
            .containsExactly("login as admin", "schedule")
            .inOrder()
        assertThat(runbook.steps[1].underTest).isTrue()
        assertThat(runbook.steps[1].consumes).containsExactly("authToken")
        assertThat(runbook.steps[1].produces).containsEntry("schedulingStatus", "Success")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.RunbookDslTest"`
Expected: FAIL — unresolved `Runbook`, `step`.

- [ ] **Step 3: Write minimal implementation**

Create `Runbook.kt` (copyright header):

```kotlin
package com.salesforce.revoman.input.config

import java.util.function.Consumer

/** Marks the runbook builder receivers so an inner `step { }` cannot accidentally call
 *  outer-[RunbookBuilder] methods. */
@DslMarker annotation class RunbookDsl

/** An ordered, named chain of [RunbookStep]s — the legible form of a multi-collection test.
 *  Build via the Kotlin `Runbook { step { } }` DSL or the Java `Runbook.configure()...off()`
 *  builder; drive with `ReVoman.revUp(runbook)`. */
class Runbook internal constructor(val name: String?, val steps: List<RunbookStep>)

/** Fluent builder backing both language front doors. */
@RunbookDsl
class RunbookBuilder internal constructor() {
    private var name: String? = null
    private val steps: MutableList<RunbookStep> = mutableListOf()

    fun name(name: String): RunbookBuilder = apply { this.name = name }

    /** Java: pure-narration step (no contract/assertion). */
    fun step(intent: String, phase: Phase, kick: Kick): RunbookBuilder =
        step(intent, phase, kick, Consumer {})

    /** Java: configured step. */
    fun step(intent: String, phase: Phase, kick: Kick, configurator: Consumer<StepSpec>): RunbookBuilder =
        apply {
            val spec = StepSpec().also { it.intent = intent; it.phase = phase; it.kick = kick }
            configurator.accept(spec)
            steps += spec.build()
        }

    internal fun addSpec(spec: StepSpec) = apply { steps += spec.build() }

    internal fun build(): Runbook = Runbook(name, steps.toList())
}

/** Kotlin top-level receiver DSL entry point. */
fun Runbook(name: String? = null, block: RunbookBuilder.() -> Unit): Runbook =
    RunbookBuilder().apply { name?.let { name(it) } }.apply(block).build()

/** Kotlin `step { }` receiver — accumulates a [StepSpec] into the builder. */
@RunbookDsl
fun RunbookBuilder.step(block: StepSpec.() -> Unit) {
    addSpec(StepSpec().apply(block))
}
```

Also add `@RunbookDsl` to the `StepSpec` class declaration in `RunbookStep.kt`:

```kotlin
@RunbookDsl
class StepSpec {
```

- [ ] **Step 4: Run Kotlin test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.RunbookDslTest"`
Expected: PASS.

- [ ] **Step 5: Write and run the Java DSL test**

Create `src/test/java/com/salesforce/revoman/input/config/RunbookJavaDslTest.java`:

```java
/*
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.salesforce.revoman.input.config;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.config.Phase.ACT;
import static com.salesforce.revoman.input.config.Phase.SETUP;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunbookJavaDslTest {
    private static Kick anyKick(final String name) {
        return Kick.configure().templatePath("pm-templates/v3/" + name).off();
    }

    @Test
    void javaBuilderBuildsOrderedSteps() {
        final var runbook =
            Runbook.configure()
                .name("wfs double book")
                .step("login as admin", SETUP, anyKick("cf-stop"), s -> s.produces("authToken"))
                .step("schedule", ACT, anyKick("cf-loop"),
                    s -> s.underTest()
                          .consumes("authToken")
                          .produces(Map.of("schedulingStatus", "Success")))
                .off();
        assertThat(runbook.getName()).isEqualTo("wfs double book");
        assertThat(runbook.getSteps()).hasSize(2);
        assertThat(runbook.getSteps().get(1).getUnderTest()).isTrue();
        assertThat(runbook.getSteps().get(1).getProduces()).containsEntry("schedulingStatus", "Success");
    }
}
```

Add the Java-facing `configure()` factory to `Runbook.kt`'s `Runbook` class via a companion:

```kotlin
class Runbook internal constructor(val name: String?, val steps: List<RunbookStep>) {
    companion object {
        @JvmStatic fun configure(): RunbookBuilder = RunbookBuilder()
    }
}
```

Run: `./gradlew test --tests "com.salesforce.revoman.input.config.RunbookJavaDslTest"`
Expected: PASS. (Kotlin `val name`/`val steps` expose `getName()`/`getSteps()`; `RunbookStep` data-class `val underTest` exposes `getUnderTest()`.)

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/input/config/Runbook.kt src/main/kotlin/com/salesforce/revoman/input/config/RunbookStep.kt src/test/kotlin/com/salesforce/revoman/input/config/RunbookDslTest.kt src/test/java/com/salesforce/revoman/input/config/RunbookJavaDslTest.java
git commit -m "feat: Runbook DSL (Kotlin receiver + Java configure/off builders)"
```

---

### Task 3: Contract-check pure functions (subset / at-least semantics)

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookContract.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/exe/RunbookContractTest.kt`

**Interfaces:**
- Consumes: `RunbookStep` (Task 1), `PostmanEnvironment` (existing).
- Produces (in `com.salesforce.revoman.internal.exe`, `internal`):
  - `data class ContractViolation(val missingConsumed: Set<String>, val missingProduced: Set<String>, val valueMismatches: Map<String, Pair<String?, String?>>)` — `expected to actual`.
  - `fun checkConsumes(step: RunbookStep, envKeys: Set<String>): Set<String>` — returns declared consume keys absent from `envKeys` (empty = OK).
  - `fun checkProduces(step: RunbookStep, env: PostmanEnvironment<Any?>): ContractViolation` — computes missing produced keys AND, for declared key→value entries, value mismatches (compare via `env.getAsString(key)`). `missingConsumed` stays empty here.
  - `fun ContractViolation.isEmpty(): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RunbookContractTest {
    private fun step(consumes: Set<String> = emptySet(), produces: Map<String, String?> = emptyMap()) =
        RunbookStep(
            intent = "s",
            phase = Phase.ACT,
            kick = Kick.configure().templatePath("pm-templates/v3/cf-stop").off(),
            consumes = consumes,
            produces = produces,
            underTest = false,
            assertAfter = null,
        )

    private fun env(vararg entries: Pair<String, Any?>) =
        PostmanEnvironment<Any?>(mutableMapOf(*entries))

    @Test
    fun `consumes passes when all declared keys present, ignoring extras`() {
        val missing = checkConsumes(step(consumes = setOf("authToken")), setOf("authToken", "extra"))
        assertThat(missing).isEmpty()
    }

    @Test
    fun `consumes reports only the missing declared keys`() {
        val missing = checkConsumes(step(consumes = setOf("authToken", "userId")), setOf("authToken"))
        assertThat(missing).containsExactly("userId")
    }

    @Test
    fun `produces key-only passes when present`() {
        val v = checkProduces(step(produces = mapOf("accountId" to null)), env("accountId" to "001"))
        assertThat(v.isEmpty()).isTrue()
    }

    @Test
    fun `produces key-only reports missing key`() {
        val v = checkProduces(step(produces = mapOf("accountId" to null)), env())
        assertThat(v.missingProduced).containsExactly("accountId")
    }

    @Test
    fun `produces key to value reports mismatch, passes on match`() {
        val ok = checkProduces(step(produces = mapOf("status" to "Success")), env("status" to "Success"))
        assertThat(ok.isEmpty()).isTrue()
        val bad = checkProduces(step(produces = mapOf("status" to "Success")), env("status" to "Error"))
        assertThat(bad.valueMismatches).containsKey("status")
        assertThat(bad.valueMismatches["status"]).isEqualTo("Success" to "Error")
    }

    @Test
    fun `empty declarations are always satisfied`() {
        assertThat(checkConsumes(step(), setOf("x"))).isEmpty()
        assertThat(checkProduces(step(), env("x" to 1)).isEmpty()).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.RunbookContractTest"`
Expected: FAIL — unresolved `checkConsumes`, `checkProduces`, `ContractViolation`.

- [ ] **Step 3: Write minimal implementation**

Create `RunbookContract.kt` (copyright header):

```kotlin
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment

/** A step's data-flow contract breach. Empty sets/map = satisfied. `valueMismatches` maps a key to
 *  `expected to actual`. */
internal data class ContractViolation(
    val missingConsumed: Set<String> = emptySet(),
    val missingProduced: Set<String> = emptySet(),
    val valueMismatches: Map<String, Pair<String?, String?>> = emptyMap(),
)

internal fun ContractViolation.isEmpty(): Boolean =
    missingConsumed.isEmpty() && missingProduced.isEmpty() && valueMismatches.isEmpty()

/** Subset/at-least: the declared consume keys absent from [envKeys]. Extras in the env are ignored. */
internal fun checkConsumes(step: RunbookStep, envKeys: Set<String>): Set<String> =
    step.consumes - envKeys

/** Subset/at-least on produced keys, plus value equality for declared key→value entries. */
internal fun checkProduces(step: RunbookStep, env: PostmanEnvironment<Any?>): ContractViolation {
    val missing = step.produces.keys.filterNot { env.containsKey(it) }.toSet()
    val mismatches =
        step.produces
            .filterValues { it != null }
            .filterKeys { it !in missing }
            .mapNotNull { (key, expected) ->
                val actual = env.getAsString(key)
                if (actual == expected) null else key to (expected to actual)
            }
            .toMap()
    return ContractViolation(missingProduced = missing, valueMismatches = mismatches)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.RunbookContractTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookContract.kt src/test/kotlin/com/salesforce/revoman/internal/exe/RunbookContractTest.kt
git commit -m "feat: runbook data-flow contract checks (subset semantics + value match)"
```

---

### Task 4: New coarse `StepEvent`s

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt` (add subtypes after `LoopBudgetExceeded`, before the closing `}`)
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/RunbookStepEventTest.kt`

**Interfaces:**
- Consumes: `Phase` (Task 1), `Outcome` (existing).
- Produces (new `StepEvent` subtypes):
  - `data class PhaseEntered(val phase: Phase) : StepEvent { override val path = phase.name }`
  - `data class RunbookStepStarted(override val path: String, val intent: String, val phase: Phase, val consumes: Set<String>, val underTest: Boolean) : StepEvent`
  - `data class RunbookStepFinished(override val path: String, val intent: String, val outcome: Outcome, val produced: Map<String, String?>, val tookMs: Long) : StepEvent`
  - `data class RunbookContractFailed(override val path: String, val intent: String, val missingConsumed: Set<String>, val missingProduced: Set<String>, val valueMismatches: Map<String, Pair<String?, String?>>) : StepEvent`
- `path` for step events = the runbook step's `intent` (steps are identified by intent, not a URL path).

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Phase
import org.junit.jupiter.api.Test

class RunbookStepEventTest {
    @Test
    fun `PhaseEntered path derives from phase name`() {
        assertThat(StepEvent.PhaseEntered(Phase.SEED).path).isEqualTo("SEED")
    }

    @Test
    fun `runbook step events carry narration`() {
        val started = StepEvent.RunbookStepStarted("act", "schedule", Phase.ACT, setOf("id"), true)
        assertThat(started.intent).isEqualTo("schedule")
        assertThat(started.underTest).isTrue()
        val finished =
            StepEvent.RunbookStepFinished("act", "schedule", Outcome.SUCCESS, mapOf("s" to "Success"), 12L)
        assertThat(finished.produced).containsEntry("s", "Success")
        val failed = StepEvent.RunbookContractFailed("act", "schedule", emptySet(), setOf("s"), emptyMap())
        assertThat(failed.missingProduced).containsExactly("s")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.RunbookStepEventTest"`
Expected: FAIL — unresolved subtypes.

- [ ] **Step 3: Write minimal implementation**

In `StepEvent.kt`, add `import com.salesforce.revoman.input.config.Phase` and, inside the `sealed interface StepEvent { … }` body after `LoopBudgetExceeded`:

```kotlin
  /** A runbook phase boundary opened. Coarse event bracketing the per-request events below it. */
  data class PhaseEntered(val phase: Phase) : StepEvent {
    override val path: String = phase.name
  }

  /** A runbook step (one whole collection/[com.salesforce.revoman.input.config.Kick]) opened. */
  data class RunbookStepStarted(
    override val path: String,
    val intent: String,
    val phase: Phase,
    val consumes: Set<String>,
    val underTest: Boolean,
  ) : StepEvent

  /** A runbook step finished with [outcome]; [produced] maps declared produced keys to values. */
  data class RunbookStepFinished(
    override val path: String,
    val intent: String,
    val outcome: Outcome,
    val produced: Map<String, String?>,
    val tookMs: Long,
  ) : StepEvent

  /** A runbook step breached its data-flow contract. */
  data class RunbookContractFailed(
    override val path: String,
    val intent: String,
    val missingConsumed: Set<String>,
    val missingProduced: Set<String>,
    val valueMismatches: Map<String, Pair<String?, String?>>,
  ) : StepEvent
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.RunbookStepEventTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt src/test/kotlin/com/salesforce/revoman/output/log/RunbookStepEventTest.kt
git commit -m "feat: coarse runbook StepEvents (PhaseEntered, RunbookStep started/finished, ContractFailed)"
```

---

### Task 5: `RunbookRundown` output type

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/RunbookRundown.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/RunbookRundownTest.kt`

**Interfaces:**
- Consumes: `Rundown` (existing), `RunbookStep` (Task 1).
- Produces:
  - `class RunbookRundown(val name: String?, val stepRundowns: List<Pair<RunbookStep, Rundown>>) : List<Rundown> by stepRundowns.map { it.second }`
  - `val rundowns: List<Rundown>` (the `List<Rundown>` view, for explicit access).
  - `fun stepFor(intent: String): Pair<RunbookStep, Rundown>?`
  - `toMermaid()` / `toMarkdown()` are added in Task 8 (declared there); this task delivers only the container + `List<Rundown>` delegation.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RunbookRundownTest {
    private fun step(intent: String) =
        RunbookStep(intent, Phase.ACT, Kick.configure().templatePath("pm-templates/v3/cf-stop").off(),
            emptySet(), emptyMap(), false, null)

    private fun rundown() =
        Rundown(mutableEnv = PostmanEnvironment(), haltOnFailureOfTypeExcept = emptyMap(),
            providedStepsToExecuteCount = 0)

    @Test
    fun `behaves as a List of Rundown in order`() {
        val a = rundown(); val b = rundown()
        val rr = RunbookRundown("rb", listOf(step("login") to a, step("act") to b))
        assertThat(rr).hasSize(2)
        assertThat(rr[0]).isSameInstanceAs(a)
        assertThat(rr[1]).isSameInstanceAs(b)
        assertThat(rr.last()).isSameInstanceAs(b)
    }

    @Test
    fun `stepFor finds by intent`() {
        val a = rundown()
        val rr = RunbookRundown(null, listOf(step("login") to a))
        assertThat(rr.stepFor("login")?.second).isSameInstanceAs(a)
        assertThat(rr.stepFor("missing")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RunbookRundownTest"`
Expected: FAIL — unresolved `RunbookRundown`.

- [ ] **Step 3: Write minimal implementation**

Create `RunbookRundown.kt` (copyright header):

```kotlin
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.RunbookStep

/** The executed runbook: each [RunbookStep] paired with its [Rundown], in order. Implements
 *  `List<Rundown>` so it drops in wherever today's `revUp(List<Kick>)` return is consumed, while
 *  adding step pairing and (Task 8) `toMermaid()`/`toMarkdown()` views. */
class RunbookRundown(
    val name: String?,
    val stepRundowns: List<Pair<RunbookStep, Rundown>>,
) : List<Rundown> by stepRundowns.map { it.second } {

    val rundowns: List<Rundown>
        get() = stepRundowns.map { it.second }

    fun stepFor(intent: String): Pair<RunbookStep, Rundown>? =
        stepRundowns.firstOrNull { it.first.intent == intent }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RunbookRundownTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/RunbookRundown.kt src/test/kotlin/com/salesforce/revoman/output/RunbookRundownTest.kt
git commit -m "feat: RunbookRundown output (List<Rundown> + step pairing)"
```

---

### Task 6: Runbook executor + `revUp(Runbook)` overload

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookExe.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` (add overload + import)
- Test: `src/test/kotlin/com/salesforce/revoman/RunbookExeE2ETest.kt`

**Interfaces:**
- Consumes: `Runbook`/`RunbookStep` (Tasks 1-2), contract fns (Task 3), coarse events (Task 4), `RunbookRundown` (Task 5), `ReVoman.revUp(kick)` + fold pattern (`ReVoman.kt:89-103`), `RevomanLog.event` (existing).
- Produces:
  - `internal fun executeRunbook(runbook: Runbook, dynamicEnvironment: Map<String, Any?>): RunbookRundown`
  - `ReVoman.revUp(runbook: Runbook, dynamicEnvironment: Map<String, Any?> = emptyMap()): RunbookRundown` (`@JvmStatic @JvmOverloads`).
- **Semantics:** mirror the existing multi-kick fold (`ReVoman.kt:89-103`) — each step's kick inherits `kick.dynamicEnvironment() + accumulatedMutableEnv`; thread `rundown.mutableEnv.immutableEnv` forward. Per step, in order: emit `PhaseEntered` (only on phase change), `RunbookStepStarted`; check `consumes` against accumulated env keys BEFORE running; run `revUp(kick)`; check `produces` against the step's `rundown.mutableEnv`; on any violation emit `RunbookContractFailed` + `RunbookStepFinished(FAILED)` then throw `AssertionError`; else run `assertAfter` (propagates on throw); emit `RunbookStepFinished(SUCCESS)`.

- [ ] **Step 1: Write the failing E2E test** (loopback server, mirrors `MultiKickEnvTypesE2ETest`)

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.step
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RunbookExeE2ETest {
    private val collection = "pm-templates/v3/cf-ledger-jump"

    private fun kick(seed: Map<String, Any?> = emptyMap()) =
        Kick.configure()
            .templatePath(collection)
            .dynamicEnvironment("baseUrl", baseUrl)
            .let { seed.entries.fold(it) { k, (key, value) -> k.dynamicEnvironment(key, value) } }
            .insecureHttp(true)
            .off()

    @Test
    fun `runbook threads env across steps like the multi-kick fold`() {
        val rr =
            ReVoman.revUp(
                Runbook("thread") {
                    step { intent = "seed"; phase = Phase.SETUP; kick = kick(mapOf("count" to 42)) }
                    step { intent = "use"; phase = Phase.ACT; kick = kick() }
                })
        assertThat(rr).hasSize(2)
        assertThat(rr[1].mutableEnv["count"]).isEqualTo(42)
        assertThat(rr.stepFor("use")).isNotNull()
    }

    @Test
    fun `consumes breach halts at the step`() {
        val ex = assertThrows<AssertionError> {
            ReVoman.revUp(
                Runbook {
                    step { intent = "needs token"; phase = Phase.ACT; kick = kick(); consumes("authToken") }
                })
        }
        assertThat(ex).hasMessageThat().contains("authToken")
    }

    @Test
    fun `produces value mismatch halts at the step`() {
        val ex = assertThrows<AssertionError> {
            ReVoman.revUp(
                Runbook {
                    step {
                        intent = "wrong value"; phase = Phase.ACT; kick = kick(mapOf("count" to 42))
                        produces("count" to "999")
                    }
                })
        }
        assertThat(ex).hasMessageThat().contains("count")
    }

    @Test
    fun `assertAfter runs and can pass`() {
        val rr =
            ReVoman.revUp(
                Runbook {
                    step {
                        intent = "assert"; phase = Phase.ACT; kick = kick(mapOf("count" to 42))
                        assertAfter { _, env -> assertThat(env["count"]).isEqualTo(42) }
                    }
                })
        assertThat(rr).hasSize(1)
    }

    companion object {
        private lateinit var server: HttpServer
        private lateinit var baseUrl: String

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        @AfterAll @JvmStatic fun stopServer() = server.stop(0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.RunbookExeE2ETest"`
Expected: FAIL — `revUp(Runbook)` unresolved.

- [ ] **Step 3: Write the executor**

Create `RunbookExe.kt` (copyright header):

```kotlin
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.internal.log.RevomanLog
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.RunbookRundown
import com.salesforce.revoman.output.log.Outcome
import com.salesforce.revoman.output.log.StepEvent

/** Drives a [Runbook] over the existing single-kick [ReVoman.revUp], threading env exactly as the
 *  multi-kick fold (ReVoman.kt) does, and interleaving coarse log events + data-flow contract
 *  checks + per-step assertions. First breach throws [AssertionError] (halt). */
internal fun executeRunbook(runbook: Runbook, dynamicEnvironment: Map<String, Any?>): RunbookRundown {
    var accumulatedEnv: Map<String, Any?> = dynamicEnvironment
    var lastPhase: Phase? = null
    val pairs =
        runbook.steps.map { step ->
            if (step.phase != lastPhase) {
                RevomanLog.event(StepEvent.PhaseEntered(step.phase))
                lastPhase = step.phase
            }
            RevomanLog.event(
                StepEvent.RunbookStepStarted(step.intent, step.intent, step.phase, step.consumes, step.underTest))
            checkConsumesOrHalt(step, accumulatedEnv.keys)
            val startNs = System.nanoTime()
            val rundown = ReVoman.revUp(step.kick.overrideDynamicEnvironment(step.kick.dynamicEnvironment() + accumulatedEnv))
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            checkProducesOrHalt(step, rundown, tookMs)
            step.assertAfter?.assertStep(rundown, rundown.mutableEnv)
            RevomanLog.event(
                StepEvent.RunbookStepFinished(
                    step.intent, step.intent, Outcome.SUCCESS, producedValues(step, rundown), tookMs))
            accumulatedEnv = rundown.mutableEnv.immutableEnv
            step to rundown
        }
    return RunbookRundown(runbook.name, pairs)
}

private fun producedValues(step: RunbookStep, rundown: Rundown): Map<String, String?> =
    step.produces.keys.associateWith { rundown.mutableEnv.getAsString(it) }

private fun checkConsumesOrHalt(step: RunbookStep, envKeys: Set<String>) {
    val missing = checkConsumes(step, envKeys)
    if (missing.isNotEmpty()) {
        RevomanLog.event(StepEvent.RunbookContractFailed(step.intent, step.intent, missing, emptySet(), emptyMap()))
        throw AssertionError("Runbook step '${step.intent}' missing consumed env keys: $missing")
    }
}

private fun checkProducesOrHalt(step: RunbookStep, rundown: Rundown, tookMs: Long) {
    val violation = checkProduces(step, rundown.mutableEnv)
    if (!violation.isEmpty()) {
        RevomanLog.event(
            StepEvent.RunbookContractFailed(
                step.intent, step.intent, emptySet(), violation.missingProduced, violation.valueMismatches))
        RevomanLog.event(
            StepEvent.RunbookStepFinished(step.intent, step.intent, Outcome.FAILED, emptyMap(), tookMs))
        throw AssertionError(
            "Runbook step '${step.intent}' contract breach — missing produced: ${violation.missingProduced}, " +
                "value mismatches (expected→actual): ${violation.valueMismatches}")
    }
}
```

- [ ] **Step 4: Add the `revUp(Runbook)` overload to `ReVoman.kt`**

Add imports near the other imports:

```kotlin
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.internal.exe.executeRunbook
import com.salesforce.revoman.output.RunbookRundown
```

Add inside `object ReVoman` (after the existing `revUp(kicks: List<Kick>, ...)` overload):

```kotlin
  /** Execute a [Runbook] — the legible, narrated form of a multi-collection chain. Threads env
   *  exactly like [revUp] over `List<Kick>`, adding per-step data-flow contract checks, per-step
   *  assertions, and coarse grouped log events. Halts (throws [AssertionError]) at the first breach. */
  @JvmStatic
  @JvmOverloads
  fun revUp(runbook: Runbook, dynamicEnvironment: Map<String, Any?> = emptyMap()): RunbookRundown =
    executeRunbook(runbook, dynamicEnvironment)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.RunbookExeE2ETest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Regression guard — existing multi-kick behavior unchanged**

Run: `./gradlew test --tests "com.salesforce.revoman.MultiKickEnvTypesE2ETest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookExe.kt src/main/kotlin/com/salesforce/revoman/ReVoman.kt src/test/kotlin/com/salesforce/revoman/RunbookExeE2ETest.kt
git commit -m "feat: runbook executor + revUp(Runbook) overload (env-threading, contract halt, assertAfter)"
```

---

### Task 7: `ConsoleRunLogSink` overhaul — glyphs, grouping, plain-`revUp` groups

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt` (update existing snapshots + add coarse-event cases)

**Interfaces:**
- Consumes: all `StepEvent` subtypes incl. Task 4's coarse ones; `Outcome`, `Phase`.
- Produces: new `render(event)` `when`-arms. Rendering rules (monospace, glyphs only — no color):
  - `PhaseEntered` → `━━ <PHASE> ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n` (pad the rule to a fixed 52-char line).
  - `RunbookStepStarted` → `┌ <marker> <intent>` + right-aligned `⟵ <consumes joined by ", " or —>` (marker `◆` if `underTest` else `▶`); append `   ★ UNDER TEST` when `underTest`. Newline.
  - existing `StepStarted` → `│ · <name>\n` (nested child request; `name` field carries the request label).
  - existing `StepFinished` → `│   <httpStatus> <outcomeWord> <tookMs>ms\n` (+ existing req/resp block indented under `│`), where `outcomeWord` = `OK`/`FAIL`/`SKIP` from `outcome`.
  - `RunbookStepFinished` → `└ <✔ or ✘> <intent>` + right-aligned `⟶ <produced as k or k=v joined>`; `✘` when `outcome==FAILED`. Newline.
  - `RunbookContractFailed` → `│ ⚠ CONTRACT  <detail>\n` (detail lists missing consumed/produced + mismatches).
  - existing `LedgerSkipped` → `│ ↺ reused ${reused}\n`; `RequestSkipped` → `│ ⊘ skipped ${path}\n`; `Jumped` → `│ ↪ ${path} → ${toPath}\n`; `RunStopped` → `■ STOP ${path}: ${reason}\n`; `LoopBudgetExceeded` → `✖ LOOP-BUDGET ${path} budget=${budget}\n`.
- **Plain-`revUp` grouping:** no new event needed — when a run has no `RunbookStepStarted` bracketing (plain `revUp`), the child `StepStarted`/`StepFinished` still render with the `│` gutter, so a plain run reads as an ungrouped-but-consistent tree. (Phase/step brackets appear only when the runbook layer emits them.) Keep the change minimal: the gutter is unconditional.

- [ ] **Step 1: Read the current test to see existing snapshot expectations**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.ConsoleRunLogSinkTest"` and read `src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt` to capture the exact strings currently asserted (they encode the OLD glyphs and will be rewritten).

- [ ] **Step 2: Write/adjust failing tests for the new rendering**

Add cases (and update old ones) in `ConsoleRunLogSinkTest.kt`. Example new assertions:

```kotlin
@Test
fun `renders phase rule`() {
    val out = capture { it.event(StepEvent.PhaseEntered(Phase.SEED)) }
    assertThat(out).startsWith("━━ SEED ━━")
}

@Test
fun `renders runbook step open with under-test marker and consumes`() {
    val out = capture {
        it.event(StepEvent.RunbookStepStarted("schedule", "schedule", Phase.ACT, setOf("accountId"), true))
    }
    assertThat(out).contains("┌ ◆ schedule")
    assertThat(out).contains("⟵ accountId")
    assertThat(out).contains("★ UNDER TEST")
}

@Test
fun `renders runbook step close with produced values`() {
    val out = capture {
        it.event(StepEvent.RunbookStepFinished("schedule", "schedule", Outcome.SUCCESS,
            mapOf("schedulingStatus" to "Success"), 5))
    }
    assertThat(out).contains("└ ✔ schedule")
    assertThat(out).contains("⟶ schedulingStatus=Success")
}

@Test
fun `renders contract failure`() {
    val out = capture {
        it.event(StepEvent.RunbookContractFailed("schedule", "schedule", emptySet(), setOf("schedulingStatus"), emptyMap()))
    }
    assertThat(out).contains("⚠ CONTRACT")
    assertThat(out).contains("schedulingStatus")
}

@Test
fun `child request nests under a gutter`() {
    val out = capture { it.event(StepEvent.StepStarted("auth/login", "login")) }
    assertThat(out).startsWith("│ · ")
}
```

Where `capture` is a helper that installs a `ConsoleRunLogSink(PrintStream)` over a `ByteArrayOutputStream` and returns the string (follow the existing test's setup pattern).

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.ConsoleRunLogSinkTest"`
Expected: FAIL — old glyphs vs new expectations; unresolved arms for coarse events.

- [ ] **Step 4: Rewrite `render` in `ConsoleRunLogSink.kt`**

```kotlin
private const val RULE_WIDTH = 52

private fun render(event: StepEvent): String =
    when (event) {
        is StepEvent.PhaseEntered -> phaseRule(event.phase.name)
        is StepEvent.RunbookStepStarted -> renderStepOpen(event)
        is StepEvent.RunbookStepFinished -> renderStepClose(event)
        is StepEvent.RunbookContractFailed -> renderContractFailed(event)
        is StepEvent.StepStarted -> "│ · ${event.name}\n"
        is StepEvent.StepFinished -> renderFinished(event)
        is StepEvent.LedgerSkipped -> "│ ↺ reused ${event.reused}\n"
        is StepEvent.RequestSkipped -> "│ ⊘ skipped ${event.path}\n"
        is StepEvent.Jumped -> "│ ↪ ${event.path} → ${event.toPath}\n"
        is StepEvent.RunStopped -> "■ STOP ${event.path}: ${event.reason}\n"
        is StepEvent.LoopBudgetExceeded -> "✖ LOOP-BUDGET ${event.path} budget=${event.budget}\n"
    }

private fun phaseRule(name: String): String {
    val prefix = "━━ $name "
    val fill = (RULE_WIDTH - prefix.length).coerceAtLeast(3)
    return prefix + "━".repeat(fill) + "\n"
}

private fun renderStepOpen(e: StepEvent.RunbookStepStarted): String {
    val marker = if (e.underTest) "◆" else "▶"
    val consumes = if (e.consumes.isEmpty()) "—" else e.consumes.joinToString(", ")
    val underTest = if (e.underTest) "   ★ UNDER TEST" else ""
    return "┌ $marker ${e.intent}          ⟵ $consumes$underTest\n"
}

private fun renderStepClose(e: StepEvent.RunbookStepFinished): String {
    val marker = if (e.outcome == Outcome.FAILED) "✘" else "✔"
    val produced =
        if (e.produced.isEmpty()) "—"
        else e.produced.entries.joinToString(", ") { (k, v) -> if (v == null) k else "$k=$v" }
    return "└ $marker ${e.intent}          ⟶ $produced\n"
}

private fun renderContractFailed(e: StepEvent.RunbookContractFailed): String {
    val parts =
        listOfNotNull(
            e.missingConsumed.takeIf { it.isNotEmpty() }?.let { "missing consumed: $it" },
            e.missingProduced.takeIf { it.isNotEmpty() }?.let { "missing produced: $it" },
            e.valueMismatches.takeIf { it.isNotEmpty() }?.let { "value mismatch (expected→actual): $it" },
        )
    return "│ ⚠ CONTRACT  ${parts.joinToString("; ")}\n"
}
```

Update `renderFinished` to the nested gutter form:

```kotlin
private fun renderFinished(event: StepEvent.StepFinished): String {
    val word = when (event.outcome) {
        Outcome.SUCCESS -> "OK"
        Outcome.FAILED -> "FAIL"
        Outcome.SKIPPED -> "SKIP"
    }
    val header = "│   ${event.httpStatus} $word ${event.tookMs}ms\n"
    val keys =
        if (event.produced.isEmpty() && event.consumed.isEmpty()) ""
        else "│   ⟵ ${event.consumed}  ⟶ ${event.produced}\n"
    val req = event.requestMsg?.let { "│ REQ:\n$it\n" } ?: ""
    val resp = event.responseMsg?.let { "│ RESP:\n$it\n" } ?: ""
    return header + keys + req + resp
}
```

Add the imports `import com.salesforce.revoman.input.config.Phase` if needed (Phase referenced only via event; not required if not directly named — verify at compile).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.ConsoleRunLogSinkTest"`
Expected: PASS.

- [ ] **Step 6: Full unit-test sweep** (catch any other snapshot depending on old glyphs)

Run: `./gradlew test`
Expected: PASS (ignore known restful-api.dev integrationTest quota flakes — this is `test`, unit only).

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSink.kt src/test/kotlin/com/salesforce/revoman/output/log/ConsoleRunLogSinkTest.kt
git commit -m "feat: overhaul console log — phase rules, step grouping, data-flow arrows, under-test/contract markers"
```

---

### Task 8: Generated view — `toMermaid()` / `toMarkdown()` + auto md summary

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/RunbookView.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/output/RunbookRundown.kt` (delegate `toMermaid()`/`toMarkdown()` to the view fns)
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookExe.kt` (auto-emit md summary as a narration line at run end)
- Test: `src/test/kotlin/com/salesforce/revoman/output/RunbookViewTest.kt`

**Interfaces:**
- Consumes: `RunbookRundown`, `RunbookStep`, `Rundown`, `Phase`, `RevomanLog.info` (existing `internal object RevomanLog` — `inline fun info(msg: () -> String)`).
- Produces:
  - `internal fun renderRunbookMarkdown(rr: RunbookRundown): String` — a table: `| Phase | Step | Consumes | Produces | Outcome |`.
  - `internal fun renderRunbookMermaid(rr: RunbookRundown): String` — a `sequenceDiagram` with a participant per distinct phase and a message per step labeled with intent; produced keys annotated via `Note`.
  - `RunbookRundown.toMarkdown(): String` and `RunbookRundown.toMermaid(): String` delegating to the above.
  - Outcome per step derived from `rundown.firstUnIgnoredUnsuccessfulStepReport == null` → `OK` else `FAIL`.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RunbookViewTest {
    private fun step(intent: String, phase: Phase, consumes: Set<String> = emptySet(),
                     produces: Map<String, String?> = emptyMap()) =
        RunbookStep(intent, phase, Kick.configure().templatePath("pm-templates/v3/cf-stop").off(),
            consumes, produces, false, null)

    private fun rundown() =
        Rundown(mutableEnv = PostmanEnvironment(), haltOnFailureOfTypeExcept = emptyMap(),
            providedStepsToExecuteCount = 0)

    private fun rr() =
        RunbookRundown("demo", listOf(
            step("login", Phase.SETUP, produces = mapOf("authToken" to null)) to rundown(),
            step("schedule", Phase.ACT, consumes = setOf("authToken"),
                produces = mapOf("status" to "Success")) to rundown()))

    @Test
    fun `markdown lists steps with phase, consumes, produces`() {
        val md = rr().toMarkdown()
        assertThat(md).contains("| Phase | Step |")
        assertThat(md).contains("SETUP")
        assertThat(md).contains("login")
        assertThat(md).contains("authToken")
        assertThat(md).contains("status=Success")
    }

    @Test
    fun `mermaid is a sequence diagram naming each step intent`() {
        val mmd = rr().toMermaid()
        assertThat(mmd).startsWith("sequenceDiagram")
        assertThat(mmd).contains("login")
        assertThat(mmd).contains("schedule")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RunbookViewTest"`
Expected: FAIL — unresolved `toMarkdown`/`toMermaid`.

- [ ] **Step 3: Write the view functions**

Create `RunbookView.kt` (copyright header):

```kotlin
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.RunbookStep

private fun outcomeOf(rundown: Rundown): String =
    if (rundown.firstUnIgnoredUnsuccessfulStepReport == null) "OK" else "FAIL"

private fun producedText(step: RunbookStep): String =
    if (step.produces.isEmpty()) "—"
    else step.produces.entries.joinToString(", ") { (k, v) -> if (v == null) k else "$k=$v" }

private fun consumedText(step: RunbookStep): String =
    if (step.consumes.isEmpty()) "—" else step.consumes.joinToString(", ")

/** A markdown table runbook: one row per step. Pure function of the declared+executed runbook. */
internal fun renderRunbookMarkdown(rr: RunbookRundown): String {
    val title = rr.name?.let { "### Runbook: $it\n\n" } ?: ""
    val header = "| Phase | Step | Consumes | Produces | Outcome |\n|---|---|---|---|---|\n"
    val rows =
        rr.stepRundowns.joinToString("\n") { (step, rundown) ->
            "| ${step.phase} | ${step.intent} | ${consumedText(step)} | ${producedText(step)} | ${outcomeOf(rundown)} |"
        }
    return title + header + rows + "\n"
}

/** A mermaid sequence diagram: a `Runbook` participant issuing one message per step, annotated with
 *  produced keys. Theme is applied by whoever writes it into docs. */
internal fun renderRunbookMermaid(rr: RunbookRundown): String {
    val lines =
        rr.stepRundowns.flatMap { (step, _) ->
            listOf(
                "    Runbook->>${step.phase}: ${step.intent}",
                "    Note right of ${step.phase}: ⟶ ${producedText(step)}",
            )
        }
    return (listOf("sequenceDiagram", "    participant Runbook") + lines).joinToString("\n") + "\n"
}
```

Add to `RunbookRundown.kt`:

```kotlin
    fun toMarkdown(): String = renderRunbookMarkdown(this)

    fun toMermaid(): String = renderRunbookMermaid(this)
```

- [ ] **Step 4: Auto-emit the md summary at run end**

In `RunbookExe.kt`, before `return RunbookRundown(...)`, build the result then log its markdown:

```kotlin
    val result = RunbookRundown(runbook.name, pairs)
    RevomanLog.info { "\n" + com.salesforce.revoman.output.renderRunbookMarkdown(result) }
    return result
```

(Replace the bare `return RunbookRundown(runbook.name, pairs)` with the above. Add `import com.salesforce.revoman.internal.log.RevomanLog` — already imported for events.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.RunbookViewTest"`
Expected: PASS.

- [ ] **Step 6: Re-run the executor E2E** (auto-summary must not break it)

Run: `./gradlew test --tests "com.salesforce.revoman.RunbookExeE2ETest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/RunbookView.kt src/main/kotlin/com/salesforce/revoman/output/RunbookRundown.kt src/main/kotlin/com/salesforce/revoman/internal/exe/RunbookExe.kt src/test/kotlin/com/salesforce/revoman/output/RunbookViewTest.kt
git commit -m "feat: runbook generated views (toMarkdown/toMermaid) + auto md summary in log"
```

---

### Task 9: End-to-end demonstration + full verification

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/RunbookLegibilityE2ETest.kt`

**Interfaces:**
- Consumes: everything above; loopback server pattern.
- Produces: a single test that reads like a runbook and asserts the three surfaces (executes, contract-guards, and renders a view), proving the feature end-to-end from a fresh reader's perspective.

- [ ] **Step 1: Write the demonstration test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.step
import com.salesforce.revoman.output.log.ConsoleRunLogSink
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RunbookLegibilityE2ETest {
    private fun kick(seed: Map<String, Any?> = emptyMap()) =
        Kick.configure()
            .templatePath("pm-templates/v3/cf-ledger-jump")
            .dynamicEnvironment("baseUrl", baseUrl)
            .let { seed.entries.fold(it) { k, (key, value) -> k.dynamicEnvironment(key, value) } }
            .runLogSink(ConsoleRunLogSink.DEFAULT)
            .insecureHttp(true)
            .off()

    @Test
    fun `a runbook reads as a story, guards its handoffs, and renders a view`() {
        val rundown =
            ReVoman.revUp(
                Runbook("legibility demo") {
                    step {
                        intent = "seed session token"
                        phase = Phase.SETUP
                        kick = kick(mapOf("authToken" to "tok-123"))
                        produces("authToken")
                    }
                    step {
                        intent = "act under test"
                        phase = Phase.ACT
                        kick = kick(mapOf("count" to 7))
                        underTest()
                        consumes("authToken")
                        produces("count" to "7")
                        assertAfter { _, env -> assertThat(env["count"]).isEqualTo(7) }
                    }
                })

        // Executes and threads env across steps.
        assertThat(rundown).hasSize(2)
        assertThat(rundown[1].mutableEnv["authToken"]).isEqualTo("tok-123")
        // Step pairing accessible by intent.
        assertThat(rundown.stepFor("act under test")).isNotNull()
        // Generated view surfaces the story.
        val md = rundown.toMarkdown()
        assertThat(md).contains("seed session token")
        assertThat(md).contains("act under test")
        assertThat(md).contains("count=7")
        assertThat(rundown.toMermaid()).startsWith("sequenceDiagram")
    }

    companion object {
        private lateinit var server: HttpServer
        private lateinit var baseUrl: String

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        @AfterAll @JvmStatic fun stopServer() = server.stop(0)
    }
}
```

- [ ] **Step 2: Run the demonstration test**

Run: `./gradlew test --tests "com.salesforce.revoman.RunbookLegibilityE2ETest"`
Expected: PASS. Inspect the Gradle output to eyeball the grouped log (phase rules, `┌ ◆ … ★ UNDER TEST`, `└ ✔ … ⟶ count=7`, and the auto markdown summary).

- [ ] **Step 3: Full build**

Run: `./gradlew build`
Expected: unit + spotless + kover pass. The only acceptable failures are the 6 known restful-api.dev integrationTest HTTP-405 quota flakes (RestfulAPIDev*Test x4, LedgerRoundTripKtTest, PokemonSandboxApiTest) — classify any integ failure as network-vs-regression before trusting it; unit `test` must be clean.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply
git add src/test/kotlin/com/salesforce/revoman/RunbookLegibilityE2ETest.kt
git commit -m "test: end-to-end runbook legibility demonstration"
```

---

## Self-Review

**Spec coverage:**
- New types (`Runbook`, `RunbookStep`, `Phase`, `RunbookRundown`) → Tasks 1, 2, 5. ✅
- Kotlin `Runbook { step { } }` + Java `configure()/off()` → Task 2. ✅
- Data-flow subset semantics + key→value + empty=narration → Task 3; enforcement/halt → Task 6. ✅
- `assertAfter` complementary to Truth → Tasks 1 (type), 6 (invocation). ✅
- First-breach halt → Task 6. ✅
- Coarse events bracketing per-request → Task 4 (types), 6 (emit). ✅
- Glyph/grouping overhaul + plain-`revUp` consistent gutter → Task 7. ✅
- Generated view + auto md summary, files on-demand → Task 8. ✅
- `RunbookRundown` as `List<Rundown>` (spec open-consideration, resolved yes) → Task 5. ✅
- `Phase` fixed 5-value enum (spec open-consideration, resolved) → Task 1. ✅
- Kick untouched, existing `revUp` overloads intact → Task 6 regression guard. ✅
- Migration proof → Task 9 (fresh DSL E2E; real WFS port deferred — it's a core-IT excluded from CI, so a CI-runnable loopback demo is the better proof).

**Placeholder scan:** No TBD/TODO; every code step shows compilable code. ✅

**Type consistency:** `StepSpec`/`RunbookStep`/`Phase`/`StepAssertion` (Task 1) reused verbatim in Tasks 2-9. `checkConsumes`/`checkProduces`/`ContractViolation` (Task 3) match Task 6 call sites. `RunbookRundown(name, stepRundowns)` ctor (Task 5) matches Task 6/8 use. Coarse `StepEvent` field names (Task 4) match Task 6 emit + Task 7 render. `revUp(runbook, dynamicEnvironment)` (Task 6) matches Task 9 calls. ✅

**Note on `Rundown` test construction:** `Rundown(mutableEnv=…, haltOnFailureOfTypeExcept=emptyMap(), providedStepsToExecuteCount=0)` uses the real constructor (see `Rundown.kt:16-33`; `stepReports` defaults empty). If a future compile error surfaces on a required param, add it from the data-class signature — do not stub.

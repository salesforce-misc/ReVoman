# Postman Sandbox Integration — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ReVoman's hand-rolled `pm` shim with Postman's real, bundled `postman-sandbox` bootcode running under the embedded GraalJS, for all *script-only* `pm` APIs (test/expect, variables/environment/globals/collectionVariables, request/response, info, dynamic vars, xml2Json, JSON) — with `pm.sendRequest` and control-flow stubbed (Phase 2).

**Architecture:** Bundle Postman's prebuilt browser `bootcode` (the real `pm` API + postman-collection + lodash + xml2js) as a classpath resource. A thin Kotlin bridge boots it in one GraalJS `Context` per ReVoman run, supplying a single-threaded event loop and browser-global shims, and drives it via Postman's own `uvm` bridge protocol (host↔guest events, Flatted-serialized on the guest→host path). Env mutations are read back from the returned execution scopes via a snapshot diff so the existing ledger keeps working.

**Tech Stack:** Kotlin, GraalJS (`org.graalvm.js:js-language` 25.0.3, already a dep), GraalVM Polyglot API, okio (resource reads via `ClasspathResolver`), Kotest + JUnit5, http4k (Phase 2), Gradle node-gradle plugin (generator only).

**Spec:** `docs/superpowers/specs/2026-06-04-pm-api-sandbox-integration-design.md`
**Spike (proof + repro):** `docs/superpowers/specs/assets/pm-sandbox-spike/`

---

## File Structure

**Created (production):**
- `src/main/resources/postman-sandbox/bootcode.js` — vendored resolved browser bundle (~2 MB; generated, committed)
- `src/main/resources/postman-sandbox/bridge-client.js` — vendored uvm bridge-client string (generated, committed)
- `src/main/resources/postman-sandbox/pm-sandbox-version.txt` — bundled postman-sandbox version (generated, committed)
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEventLoop.kt` — single-threaded timer queue
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResources.kt` — loads the 3 resources
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/Flatted.kt` — Flatted decode (guest→host)
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmExecutionContext.kt` — input context model + result model
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt` — owns GraalJS Context + event routing
- `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt` — public-internal entry: `execute(...)`

**Modified (production):**
- `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt` — call `PmSandbox` instead of `pm.evaluateJS`
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt:143-146` — construct `PmSandbox`, thread it through
- `build.gradle.kts` — add `generatePmSandbox` task

**Created (tests):**
- `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEventLoopTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/FlattedTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResourcesTest.kt`
- `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxBootTest.kt` (the spike, permanent canary)
- `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt` (per-pm-area coverage)
- `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxEnvDiffTest.kt` (env-sync diff)

> **Note on test naming:** STYLE.md says `testXxx` no-backticks, but every existing test in this repo (`EvalJsTest`, `RegexReplacerTest`) uses backtick names with `@Test`. Follow the **existing live convention** (backtick + `@Test`) for consistency within the suite.

---

## Task 1: Vendor the Postman sandbox resources + generator task

**Files:**
- Create: `src/main/resources/postman-sandbox/bootcode.js`
- Create: `src/main/resources/postman-sandbox/bridge-client.js`
- Create: `src/main/resources/postman-sandbox/pm-sandbox-version.txt`
- Modify: `build.gradle.kts` (after the `node { ... }` block at line 63)

- [ ] **Step 1: Pin the version and generate the resources**

The spike already proved `postman-sandbox@6.7.0`. Run this once to produce the three resource files:

```bash
cd /tmp && rm -rf pm-gen && mkdir pm-gen && cd pm-gen
npm init -y >/dev/null 2>&1
npm install postman-sandbox@6.7.0 postman-collection >/dev/null 2>&1
DEST="$OLDPWD/src/main/resources/postman-sandbox"   # run the script from the repo root so $OLDPWD is repo root
# (if unsure, replace $DEST below with the absolute repo path)
mkdir -p "$DEST"
node -e "require('./node_modules/postman-sandbox/.cache/bootcode.browser.js')((e,c)=>{if(e)throw e;require('fs').writeFileSync(process.argv[1],c)})" "$DEST/bootcode.js"
node -e "require('fs').writeFileSync(process.argv[1], require('./node_modules/uvm/lib/bridge-client')())" "$DEST/bridge-client.js"
node -e "require('fs').writeFileSync(process.argv[1], require('./node_modules/postman-sandbox/package.json').version)" "$DEST/pm-sandbox-version.txt"
```

Expected: three files created. Verify:

```bash
cd <repo-root>
wc -c src/main/resources/postman-sandbox/bootcode.js          # ~2.0 MB
wc -c src/main/resources/postman-sandbox/bridge-client.js     # ~3.2 KB
cat   src/main/resources/postman-sandbox/pm-sandbox-version.txt   # 6.7.0
grep -c "require('vm')\|require('fs')\|require('child_process')" src/main/resources/postman-sandbox/bootcode.js   # 0
```

- [ ] **Step 2: Add the `generatePmSandbox` Gradle task to document & reproduce generation**

In `build.gradle.kts`, immediately after the `node { ... }` block (currently ends ~line 66), add:

```kotlin
// Regenerates the vendored Postman sandbox resources from a pinned postman-sandbox version.
// These resources ARE committed (so consumers need no Node at runtime — JVM-first). To upgrade:
// bump PM_SANDBOX_VERSION, run `./gradlew generatePmSandbox`, commit the changed resources.
val pmSandboxVersion = "6.7.0"
tasks.register<Exec>("generatePmSandbox") {
    group = "postman"
    description = "Regenerate vendored postman-sandbox bootcode resources (pinned $pmSandboxVersion)"
    val outDir = layout.projectDirectory.dir("src/main/resources/postman-sandbox")
    workingDir = layout.buildDirectory.dir("pm-sandbox-gen").get().asFile
    doFirst { workingDir.mkdirs() }
    // Uses the system node (node-gradle downloads one for the `js/` project; this task just needs npm+node on PATH).
    commandLine(
        "bash", "-c",
        """
        set -e
        npm init -y >/dev/null 2>&1 || true
        npm install postman-sandbox@$pmSandboxVersion postman-collection >/dev/null 2>&1
        mkdir -p "${'$'}{OUT}"
        node -e "require('./node_modules/postman-sandbox/.cache/bootcode.browser.js')((e,c)=>{if(e)throw e;require('fs').writeFileSync(process.env.OUT+'/bootcode.js',c)})"
        node -e "require('fs').writeFileSync(process.env.OUT+'/bridge-client.js', require('./node_modules/uvm/lib/bridge-client')())"
        node -e "require('fs').writeFileSync(process.env.OUT+'/pm-sandbox-version.txt', require('./node_modules/postman-sandbox/package.json').version)"
        """.trimIndent()
    )
    environment("OUT", outDir.asFile.absolutePath)
}
```

- [ ] **Step 3: Verify the task is registered**

Run: `./gradlew help --task generatePmSandbox`
Expected: prints the task description, no error.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/postman-sandbox/ build.gradle.kts
git commit -m "feat(sandbox): vendor postman-sandbox bootcode resources + generator task"
```

---

## Task 2: SandboxEventLoop

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEventLoop.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEventLoopTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SandboxEventLoopTest {
  @Test
  fun `immediate tasks run FIFO`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<Int>()
    loop.schedule({ order.add(1) }, 0)
    loop.schedule({ order.add(2) }, 0)
    loop.run()
    order shouldContainExactly listOf(1, 2)
  }

  @Test
  fun `timed tasks run in virtual-time order regardless of insertion order`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<String>()
    loop.schedule({ order.add("late") }, 50)
    loop.schedule({ order.add("early") }, 10)
    loop.run()
    order shouldContainExactly listOf("early", "late")
  }

  @Test
  fun `clear cancels a pending timed task`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<String>()
    val id = loop.schedule({ order.add("cancelled") }, 10)
    loop.schedule({ order.add("kept") }, 20)
    loop.clear(id)
    loop.run()
    order shouldContainExactly listOf("kept")
  }

  @Test
  fun `nested scheduling drains fully`() {
    val loop = SandboxEventLoop()
    val order = mutableListOf<Int>()
    loop.schedule({
      order.add(1)
      loop.schedule({ order.add(2) }, 0)
    }, 0)
    loop.run()
    order shouldContainExactly listOf(1, 2)
  }

  @Test
  fun `runaway loop throws after backstop`() {
    val loop = SandboxEventLoop()
    var thrown = false
    try {
      lateinit var reschedule: () -> Unit
      reschedule = { loop.schedule({ reschedule() }, 0) }
      reschedule()
      loop.run()
    } catch (e: IllegalStateException) {
      thrown = true
    }
    thrown shouldBe true
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.SandboxEventLoopTest"`
Expected: FAIL — `SandboxEventLoop` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import java.util.PriorityQueue

/**
 * A minimal single-threaded event loop backing the sandbox's `setTimeout`/`setImmediate`/
 * `setInterval` globals (GraalJS provides none natively). Uses virtual time: timed tasks fire in
 * delay order, not wall-clock — deterministic and instant in tests. Confined to the calling thread.
 */
internal class SandboxEventLoop {
  private val ready: ArrayDeque<Runnable> = ArrayDeque()
  private val timers: PriorityQueue<LongArray> = PriorityQueue(compareBy { it[0] })
  private val timerFns: MutableMap<Long, Runnable> = HashMap()
  private var seq: Long = 1
  private var virtualNow: Long = 0

  fun schedule(task: Runnable, delayMs: Long): Long {
    val id = seq++
    if (delayMs <= 0) {
      ready.addLast(Runnable { timerFns.remove(id); task.run() })
    } else {
      timers.add(longArrayOf(virtualNow + delayMs, id))
      timerFns[id] = task
    }
    return id
  }

  fun clear(id: Long) {
    timerFns.remove(id)
  }

  /** Drains ready tasks first, then timed tasks in virtual-time order, until nothing remains. */
  fun run() {
    var guard = 0
    while (true) {
      check(++guard <= RUNAWAY_BACKSTOP) { "sandbox event loop runaway (> $RUNAWAY_BACKSTOP iterations)" }
      ready.removeFirstOrNull()?.let { it.run(); continue }
      val next = timers.poll() ?: break
      val fn = timerFns.remove(next[1]) ?: continue // cancelled
      virtualNow = maxOf(virtualNow, next[0])
      fn.run()
    }
  }

  private companion object {
    const val RUNAWAY_BACKSTOP = 5_000_000
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.SandboxEventLoopTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEventLoop.kt src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEventLoopTest.kt
git commit -m "feat(sandbox): single-threaded virtual-time event loop"
```

---

## Task 3: Flatted decoder

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/Flatted.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/FlattedTest.kt`

**Context:** The guest dispatches events as `__uvm_emit(Flatted.stringify(argsArray))`. Flatted is a circular-safe JSON variant: the top-level value is a JSON array of "slots"; every string in a slot is actually an **index** (as a string) into the slots array; the real root is slot 0. We only need **decode** (guest→host). Host→guest passes live host objects directly (no Flatted).

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FlattedTest {
  @Test
  fun `decodes a flat array of event args`() {
    // Flatted.stringify(["execute", "spike1"]) => [["1","2"],"execute","spike1"]
    val decoded = Flatted.parse("""[["1","2"],"execute","spike1"]""")
    decoded shouldBe listOf("execute", "spike1")
  }

  @Test
  fun `decodes nested objects with deduped strings`() {
    // Flatted.stringify(["assertion", {name:"t", passed:true}])
    val json = """[["1","2"],"assertion",{"name":"3","passed":true},"t"]"""
    val decoded = Flatted.parse(json) as List<*>
    decoded[0] shouldBe "assertion"
    val obj = decoded[1] as Map<*, *>
    obj["name"] shouldBe "t"
    obj["passed"] shouldBe true
  }

  @Test
  fun `decodes numbers and null`() {
    // Flatted.stringify(["x", 42, null]) => [["1",2,3],"x",42,null]
    val decoded = Flatted.parse("""[["1",2,3],"x",42,null]""") as List<*>
    decoded[0] shouldBe "x"
    (decoded[1] as Number).toInt() shouldBe 42
    decoded[2] shouldBe null
  }

  @Test
  fun `decodes circular references without infinite loop`() {
    // An object referencing itself: slot0 = ["1"], slot1 = {self:"1"} -> obj.self === obj
    val decoded = Flatted.parse("""[["1"],{"self":"1"}]""") as List<*>
    val obj = decoded[0] as Map<*, *>
    (obj["self"] === obj) shouldBe true
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.FlattedTest"`
Expected: FAIL — `Flatted` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Decodes Flatted (https://github.com/WebReflection/flatted) — the circular-safe JSON the uvm
 * bridge uses on the guest→host path. The serialized form is a JSON array of "slots"; every JSON
 * string in a slot is actually a decimal **index** into the slots array, and slot 0 is the root.
 * We rebuild the object graph, resolving string-indices to their referenced slot (which may be a
 * primitive string, an object, an array, or — for cycles — an ancestor already being built).
 *
 * Returns the decoded root. Bridge event payloads are always a top-level array, so callers cast to
 * `List<*>` and read `(eventName, ...args)`.
 */
internal object Flatted {
  private val slotsAdapter =
    Moshi.Builder().build().adapter<List<Any?>>(
      Types.newParameterizedType(List::class.java, Any::class.java)
    )

  fun parse(json: String): Any? {
    val slots = slotsAdapter.fromJson(json) ?: return null
    if (slots.isEmpty()) return null
    val built = arrayOfNulls<Any?>(slots.size)
    val done = BooleanArray(slots.size)
    return resolve(0, slots, built, done)
  }

  private fun resolve(
    index: Int,
    slots: List<Any?>,
    built: Array<Any?>,
    done: BooleanArray,
  ): Any? {
    if (done[index]) return built[index]
    return when (val raw = slots[index]) {
      is String -> { // a string slot is a literal string value
        built[index] = raw
        done[index] = true
        raw
      }
      is Map<*, *> -> {
        val out = LinkedHashMap<String, Any?>()
        built[index] = out // register BEFORE recursing so cycles resolve to `out`
        done[index] = true
        for ((k, v) in raw) out[k as String] = deref(v, slots, built, done)
        out
      }
      is List<*> -> {
        val out = ArrayList<Any?>(raw.size)
        built[index] = out
        done[index] = true
        for (v in raw) out.add(deref(v, slots, built, done))
        out
      }
      else -> { // number, boolean, null
        built[index] = raw
        done[index] = true
        raw
      }
    }
  }

  /** A child value: a String here is an index into `slots`; anything else is a literal. */
  private fun deref(
    value: Any?,
    slots: List<Any?>,
    built: Array<Any?>,
    done: BooleanArray,
  ): Any? =
    when (value) {
      is String -> resolve(value.toInt(), slots, built, done)
      else -> value
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.FlattedTest"`
Expected: PASS (4 tests).

> If the circular test fails because Moshi rejects a bare top-level array, switch the adapter to parse via `Moshi.adapter(Any::class.java)` and cast; the slot logic is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/Flatted.kt src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/FlattedTest.kt
git commit -m "feat(sandbox): Flatted decoder for guest->host bridge payloads"
```

---

## Task 4: SandboxResources

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResources.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResourcesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SandboxResourcesTest {
  @Test
  fun `loads bootcode bridgeClient and version from classpath`() {
    SandboxResources.bootcode.length shouldNotContain "" // non-empty sentinel below
    (SandboxResources.bootcode.length > 1_000_000) shouldBe true
    SandboxResources.bridgeClient shouldContain "bridge"
    SandboxResources.version shouldBe "6.7.0"
  }

  @Test
  fun `bootcode has no node-vm dependencies`() {
    SandboxResources.bootcode shouldNotContain "require('vm')"
    SandboxResources.bootcode shouldNotContain "require('child_process')"
  }
}
```

> Remove the placeholder `shouldNotContain ""` line — replaced by the size check on the next line. (Kept here only to show intent; delete it when writing the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.SandboxResourcesTest"`
Expected: FAIL — `SandboxResources` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import com.salesforce.revoman.input.readFileToString

/**
 * Loads the vendored Postman sandbox resources from the classpath. Uses ReVoman's
 * [readFileToString] (backed by the ClasspathResolver, which honours the thread context
 * classloader) rather than okio's `FileSystem.RESOURCES` — the latter is bound to okio's own
 * classloader and cannot see resources on bazel/URLClassLoader/OSGi child loaders.
 *
 * Resources are read once and cached for the JVM lifetime; they are immutable build artifacts.
 */
internal object SandboxResources {
  private const val DIR = "postman-sandbox"

  val bootcode: String by lazy { readFileToString("$DIR/bootcode.js") }
  val bridgeClient: String by lazy { readFileToString("$DIR/bridge-client.js") }
  val version: String by lazy { readFileToString("$DIR/pm-sandbox-version.txt").trim() }
}
```

- [ ] **Step 4: Write the real test file (delete the placeholder line) and run**

Final test file body (replaces Step 1's first test):

```kotlin
  @Test
  fun `loads bootcode bridgeClient and version from classpath`() {
    (SandboxResources.bootcode.length > 1_000_000) shouldBe true
    SandboxResources.bridgeClient shouldContain "bridge"
    SandboxResources.version shouldBe "6.7.0"
  }
```

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.SandboxResourcesTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResources.kt src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResourcesTest.kt
git commit -m "feat(sandbox): classpath loader for vendored sandbox resources"
```

---

## Task 5: PmExecutionContext + result model

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmExecutionContext.kt`
- Test: covered indirectly by Task 7/8 (pure data classes; no standalone test).

- [ ] **Step 1: Write the model**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

/** Which sandbox lifecycle script is running. Maps to Postman's event `listen` value. */
internal enum class ScriptTarget(val listen: String) {
  PRE_REQUEST("prerequest"),
  TEST("test"),
}

/** A single variable scope (environment / globals / collectionVariables) as key→value. */
internal data class PmScope(val id: String, val values: Map<String, Any?>)

/**
 * Everything the sandbox needs to execute one script. Variable scopes are snapshots taken from
 * [com.salesforce.revoman.output.postman.PostmanEnvironment] (and friends) before execution; the
 * sandbox returns mutated copies in [PmExecutionResult].
 */
internal data class PmExecutionContext(
  val environment: PmScope,
  val globals: PmScope = PmScope("globals", emptyMap()),
  val collectionVariables: PmScope = PmScope("collectionVariables", emptyMap()),
  val request: Map<String, Any?>? = null,
  val response: Map<String, Any?>? = null,
)

/** One `pm.test`/legacy `test` assertion result reported by the sandbox. */
internal data class PmAssertion(
  val name: String,
  val index: Int,
  val passed: Boolean,
  val skipped: Boolean,
  val error: String?,
)

/**
 * The outcome of a single sandbox execution.
 * - [environment]/[globals]/[collectionVariables]: the FULL post-execution scope values (caller
 *   diffs against the pre-snapshot to derive produced/unset).
 * - [assertions]: pm.test results (failures are data, NOT thrown).
 * - [error]: a thrown script error (pre-req/test JS failure) — null on success.
 * - [nextRequest]/[skipRequest]: control-flow directives (Phase 2 wires them to the sequencer;
 *   Phase 1 records them but the stubs never set them).
 */
internal data class PmExecutionResult(
  val environment: Map<String, Any?>,
  val globals: Map<String, Any?>,
  val collectionVariables: Map<String, Any?>,
  val assertions: List<PmAssertion>,
  val error: Throwable?,
  val nextRequest: String? = null,
  val skipRequest: Boolean = false,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew testClasses`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmExecutionContext.kt
git commit -m "feat(sandbox): execution context + result data model"
```

---

## Task 6: SandboxBridge (boot + execute, no host-callback events yet)

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxBootTest.kt` (the permanent spike canary)

**Context:** This is the heart — it ports the proven spike (`docs/superpowers/specs/assets/pm-sandbox-spike/Spike.java`) into Kotlin. Boot once; expose `dispatchExecute(...)` that emits the `execute` event, drains the loop, collects guest emits, and decodes them into a `PmExecutionResult`.

- [ ] **Step 1: Write the failing test (the canary)**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PmSandboxBootTest {
  @Test
  fun `real pm API boots under GraalJS and runs pm test + environment set`() {
    val bridge = SandboxBridge()
    bridge.boot()
    val result =
      bridge.dispatchExecute(
        id = "boot1",
        script =
          """
          pm.environment.set('spikeKey', 'spikeVal-' + (1 + 1));
          pm.test('one plus one is two', function () { pm.expect(1 + 1).to.eql(2); });
          pm.test('env round-trips', function () {
            pm.expect(pm.environment.get('spikeKey')).to.eql('spikeVal-2');
          });
          pm.test('intentional failure', function () { pm.expect(true).to.eql(false); });
          """
            .trimIndent(),
        target = ScriptTarget.TEST,
        context = PmExecutionContext(environment = PmScope("env1", emptyMap())),
        timeoutMs = 5000,
      )
    bridge.close()

    result.error shouldBe null
    result.assertions shouldHaveSize 3
    result.assertions[0].passed shouldBe true
    result.assertions[1].passed shouldBe true
    result.assertions[2].passed shouldBe false
    result.environment["spikeKey"] shouldBe "spikeVal-2"
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxBootTest"`
Expected: FAIL — `SandboxBridge` unresolved reference.

- [ ] **Step 3: Write the implementation**

> This is a direct Kotlin port of the validated spike. The host-callback events (sendRequest, control flow) are NOT wired here — Task 9 stubs sendRequest at the pm layer; Phase 2 adds the handlers. This task only needs: boot, `initialize`, `execute`, collect emits, decode assertions + result scopes.

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.github.oshai.kotlinlogging.KotlinLogging
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * Owns a single GraalJS [Context] running Postman's real sandbox bootcode, driven via the uvm
 * bridge protocol. Single-threaded: all eval + loop draining happen on the calling thread.
 *
 * Boot installs browser-global shims (timers via [SandboxEventLoop], atob/btoa, Blob/File/etc.),
 * captures the guest `bridge` Value before the bootcode deletes it, evals the bootcode, and sends
 * `initialize`. Each [dispatchExecute] emits an `execute` event, drains the loop until the
 * terminal `execution.result.<id>` arrives (or [timeoutMs] elapses), and decodes the collected
 * guest emits into a [PmExecutionResult].
 */
internal class SandboxBridge {
  private lateinit var ctx: Context
  private lateinit var guestBridge: Value
  private val loop = SandboxEventLoop()
  private val emits = mutableListOf<String>() // raw Flatted strings, guest -> host

  fun boot() {
    ctx =
      Context.newBuilder("js")
        .allowExperimentalOptions(true)
        .option("js.esm-eval-returns-exports", "true")
        .option("js.ecmascript-version", "2024")
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { true }
        .build()
    val bindings = ctx.getBindings("js")

    bindings.putMember(
      "__java_setTimer",
      ProxyExecutable { args ->
        val fn = args[0]
        val delay = if (args.size > 1 && args[1].fitsInLong()) args[1].asLong() else 0L
        val extra = if (args.size > 2) args.copyOfRange(2, args.size) else emptyArray()
        loop.schedule({ fn.executeVoid(*extra) }, delay)
      },
    )
    bindings.putMember(
      "__java_clearTimer",
      ProxyExecutable { args ->
        if (args.isNotEmpty() && args[0].fitsInLong()) loop.clear(args[0].asLong())
        null
      },
    )
    bindings.putMember("__java_emit", ProxyExecutable { args -> emits.add(args[0].asString()); null })
    bindings.putMember(
      "__java_btoa",
      ProxyExecutable { args ->
        java.util.Base64.getEncoder()
          .encodeToString(args[0].asString().toByteArray(Charsets.ISO_8859_1))
      },
    )
    bindings.putMember(
      "__java_atob",
      ProxyExecutable { args ->
        String(java.util.Base64.getDecoder().decode(args[0].asString()), Charsets.ISO_8859_1)
      },
    )

    // Closure-capture host fns so they survive the bootcode's recreatingTheUniverse() global wipe
    // (it allowlists setTimeout/__uvm_emit etc. but NOT our __java_* helpers).
    ctx.eval(
      "js",
      """
      (function (jSet, jClear, jEmit, jAtob, jBtoa) {
        globalThis.setTimeout = function (fn, d) { return jSet(fn, d | 0, ...Array.prototype.slice.call(arguments, 2)); };
        globalThis.clearTimeout = function (id) { return jClear(id); };
        globalThis.setInterval = function (fn, d) { return jSet(fn, d | 0, ...Array.prototype.slice.call(arguments, 2)); };
        globalThis.clearInterval = function (id) { return jClear(id); };
        globalThis.setImmediate = function (fn) { return jSet(fn, 0, ...Array.prototype.slice.call(arguments, 1)); };
        globalThis.clearImmediate = function (id) { return jClear(id); };
        globalThis.queueMicrotask = function (fn) { return jSet(fn, 0); };
        globalThis.__uvm_emit = function (s) { jEmit(s); };
        globalThis.__uvm_setTimeout = globalThis.setTimeout;
        globalThis.Blob = globalThis.Blob || function Blob() {};
        globalThis.File = globalThis.File || function File() {};
        globalThis.FileReader = globalThis.FileReader || function FileReader() {};
        globalThis.FormData = globalThis.FormData || function FormData() {};
        globalThis.atob = function (s) { return jAtob(s); };
        globalThis.btoa = function (s) { return jBtoa(s); };
      })(__java_setTimer, __java_clearTimer, __java_emit, __java_atob, __java_btoa);
      ${SandboxResources.bridgeClient}
      """
        .trimIndent(),
    )

    guestBridge = bindings.getMember("bridge")
    check(guestBridge != null && !guestBridge.isNull) { "sandbox: no global bridge after bridge-client" }

    ctx.eval(Source.newBuilder("js", SandboxResources.bootcode, "bootcode.js").build())
    loop.run()

    guestBridge.invokeMember("emit", "initialize", ProxyObject.fromMap(HashMap()))
    loop.run()
    logger.info { "Postman sandbox booted (postman-sandbox ${SandboxResources.version})" }
  }

  fun dispatchExecute(
    id: String,
    script: String,
    target: ScriptTarget,
    context: PmExecutionContext,
    timeoutMs: Long,
  ): PmExecutionResult {
    emits.clear()

    val event =
      ProxyObject.fromMap(
        linkedMapOf<String, Any?>(
          "listen" to target.listen,
          "script" to
            ProxyObject.fromMap(
              linkedMapOf<String, Any?>(
                "type" to "text/javascript",
                "exec" to ProxyArray.fromArray(script),
              )
            ),
        )
      )
    val ctxObj =
      ProxyObject.fromMap(
        linkedMapOf<String, Any?>(
          "environment" to scopeToProxy(context.environment),
          "globals" to scopeToProxy(context.globals),
          "collectionVariables" to scopeToProxy(context.collectionVariables),
        ).also { m ->
          context.request?.let { m["request"] = ProxyObject.fromMap(it) }
          context.response?.let { m["response"] = ProxyObject.fromMap(it) }
        }
      )
    val options =
      ProxyObject.fromMap(
        linkedMapOf<String, Any?>(
          "timeout" to timeoutMs,
          "cursor" to ProxyObject.fromMap(HashMap()),
          "allowSkipRequest" to (target == ScriptTarget.PRE_REQUEST),
        )
      )

    guestBridge.invokeMember("emit", "execute", id, event, ctxObj, options)
    loop.run()

    return decodeResult(id)
  }

  fun close() {
    if (::ctx.isInitialized) ctx.close(true)
  }

  private fun scopeToProxy(scope: PmScope): ProxyObject =
    ProxyObject.fromMap(
      linkedMapOf<String, Any?>(
        "id" to scope.id,
        "values" to
          ProxyArray.fromList(
            scope.values.map { (k, v) ->
              ProxyObject.fromMap(linkedMapOf<String, Any?>("key" to k, "value" to v))
            }
          ),
      )
    )

  @Suppress("UNCHECKED_CAST")
  private fun decodeResult(id: String): PmExecutionResult {
    val assertions = mutableListOf<PmAssertion>()
    var error: Throwable? = null
    var environment: Map<String, Any?> = emptyMap()
    var globals: Map<String, Any?> = emptyMap()
    var collectionVariables: Map<String, Any?> = emptyMap()

    for (raw in emits) {
      val parsed = Flatted.parse(raw) as? List<*> ?: continue
      val name = parsed.firstOrNull() as? String ?: continue
      when {
        name == "execution.assertion.$id" -> {
          // (cursor, assertions[])
          (parsed.last() as? List<*>)?.forEach { a ->
            val m = a as? Map<*, *> ?: return@forEach
            assertions.add(
              PmAssertion(
                name = m["name"] as? String ?: "",
                index = (m["index"] as? Number)?.toInt() ?: 0,
                passed = m["passed"] as? Boolean ?: false,
                skipped = m["skipped"] as? Boolean ?: false,
                error = (m["error"] as? Map<*, *>)?.get("message") as? String ?: m["error"] as? String,
              )
            )
          }
        }
        name == "execution.error.$id" -> {
          val errObj = parsed.getOrNull(2)
          val msg = (errObj as? Map<*, *>)?.get("message") as? String ?: errObj?.toString() ?: "sandbox error"
          error = RuntimeException(msg)
        }
        name == "execution.result.$id" -> {
          // (err, execution)
          val execution = parsed.getOrNull(2) as? Map<*, *> ?: continue
          environment = scopeValues(execution["environment"])
          globals = scopeValues(execution["globals"])
          collectionVariables = scopeValues(execution["collectionVariables"])
        }
      }
    }
    return PmExecutionResult(environment, globals, collectionVariables, assertions, error)
  }

  /** Reads a returned VariableScope's `values: [{key,value}]` (or `{key:value}`) into a flat map. */
  private fun scopeValues(scope: Any?): Map<String, Any?> {
    val m = scope as? Map<*, *> ?: return emptyMap()
    val values = m["values"]
    return when (values) {
      is List<*> ->
        values.mapNotNull {
          val e = it as? Map<*, *> ?: return@mapNotNull null
          val k = e["key"] as? String ?: return@mapNotNull null
          k to e["value"]
        }.toMap()
      is Map<*, *> -> values.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap()
      else -> emptyMap()
    }
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxBootTest"`
Expected: PASS — assertions decoded (2 pass, 1 fail), `spikeKey == spikeVal-2`.

> If the returned `environment` map is empty, the sandbox may serialize scope `values` as an array of `[key,value]` pairs differently than assumed — inspect a raw emit by temporarily logging `emits` and adjust `scopeValues`. The spike's raw `execution.result` dump (in the spike README) is the reference.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxBootTest.kt
git commit -m "feat(sandbox): GraalJS bridge boots real pm API + decodes execution result"
```

---

## Task 7: PmSandbox entry facade + per-pm-area coverage tests

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt`

- [ ] **Step 1: Write the facade**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

/**
 * The single entry point the rest of ReVoman uses to run pm scripts. Wraps a [SandboxBridge]
 * (one booted GraalJS context per ReVoman run). Construct once per run; [close] at the end.
 *
 * All GraalJS/bridge/Flatted detail lives behind [execute].
 */
internal class PmSandbox : AutoCloseable {
  private val bridge = SandboxBridge()
  private var booted = false
  private var idSeq = 0L

  private fun ensureBooted() {
    if (!booted) {
      bridge.boot()
      booted = true
    }
  }

  fun execute(
    script: String,
    target: ScriptTarget,
    context: PmExecutionContext,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): PmExecutionResult {
    ensureBooted()
    return bridge.dispatchExecute("step${idSeq++}", script, target, context, timeoutMs)
  }

  override fun close() {
    if (booted) bridge.close()
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 60_000L
  }
}
```

- [ ] **Step 2: Write the coverage test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PmSandboxScriptApiTest {
  private val sandbox = PmSandbox()

  @AfterAll fun tearDown() = sandbox.close()

  private fun runTest(script: String, env: Map<String, Any?> = emptyMap()) =
    sandbox.execute(script, ScriptTarget.TEST, PmExecutionContext(environment = PmScope("e", env)))

  @Test
  fun `pm test and expect chai assertions`() {
    val r = runTest("pm.test('t', () => pm.expect(2).to.be.a('number'));")
    r.assertions shouldHaveSize 1
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm environment get set unset`() {
    val r =
      runTest(
        """
        pm.environment.set('a', '1');
        pm.environment.unset('seed');
        pm.test('has a', () => pm.expect(pm.environment.get('a')).to.eql('1'));
        """
          .trimIndent(),
        env = mapOf("seed" to "x"),
      )
    r.assertions[0].passed shouldBe true
    r.environment["a"] shouldBe "1"
    (r.environment.containsKey("seed")) shouldBe false
  }

  @Test
  fun `pm response json and status assertions`() {
    val r =
      sandbox.execute(
        "pm.test('ok', () => { pm.response.to.have.status(200); pm.expect(pm.response.json().x).to.eql(7); });",
        ScriptTarget.TEST,
        PmExecutionContext(
          environment = PmScope("e", emptyMap()),
          response = mapOf("code" to 200, "status" to "OK", "body" to """{"x":7}"""),
        ),
      )
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `dynamic variable guid via replaceIn`() {
    val r =
      runTest(
        """
        const id = pm.variables.replaceIn('{{${'$'}guid}}');
        pm.test('guid shape', () => pm.expect(id).to.match(/^[0-9a-f-]{36}${'$'}/));
        """
          .trimIndent()
      )
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `failing assertion is data not exception`() {
    val r = runTest("pm.test('fails', () => pm.expect(1).to.eql(2));")
    r.error shouldBe null
    r.assertions[0].passed shouldBe false
  }

  @Test
  fun `thrown error surfaces as result error`() {
    val r = runTest("throw new Error('boom');")
    (r.error?.message?.contains("boom")) shouldBe true
  }
}
```

- [ ] **Step 3: Run the tests**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxScriptApiTest"`
Expected: PASS (6 tests). The `pm.response`/`pm.request` context maps may need field-name tweaks (`code`/`status`/`body`) — if `pm.response.json()` fails, log the raw emit and align the context map keys to what `postman-collection`'s `Response` expects (`code`, `status`, `body`, `header`).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt
git commit -m "feat(sandbox): PmSandbox facade + per-pm-area coverage tests"
```

---

## Task 8: Env-sync diff helper

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt` (add a top-level diff fn in the same file)
- Test: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxEnvDiffTest.kt`

**Context:** The ledger needs produced/unset keys. Today they come from live `set()`/`unset()` callbacks; now they come from diffing the returned scope against the pre-snapshot.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PmSandboxEnvDiffTest {
  @Test
  fun `diff detects produced changed and unset keys`() {
    val before = mapOf("keep" to "1", "change" to "old", "remove" to "x")
    val after = mapOf("keep" to "1", "change" to "new", "add" to "y")
    val diff = diffScopes(before, after)
    diff.produced shouldContainExactlyInAnyOrder listOf("change", "add")
    diff.unset shouldContainExactlyInAnyOrder listOf("remove")
  }

  @Test
  fun `no changes yields empty diff`() {
    val same = mapOf("a" to "1")
    val diff = diffScopes(same, same)
    diff.produced shouldBe emptySet()
    diff.unset shouldBe emptySet()
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxEnvDiffTest"`
Expected: FAIL — `diffScopes` / `ScopeDiff` unresolved.

- [ ] **Step 3: Add the helper to PmSandbox.kt**

Append to `src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt` (top-level, below the class):

```kotlin
/** Keys produced (added or value-changed) and unset (removed) between two scope snapshots. */
internal data class ScopeDiff(val produced: Set<String>, val unset: Set<String>)

/**
 * Diffs a pre-execution scope snapshot against the post-execution scope returned by the sandbox.
 * `produced` = keys whose value is new or changed; `unset` = keys present before but gone after.
 * Equality uses structural `==` on the boxed values (Strings/numbers/maps from the bridge decode).
 */
internal fun diffScopes(before: Map<String, Any?>, after: Map<String, Any?>): ScopeDiff {
  val produced = after.filter { (k, v) -> !before.containsKey(k) || before[k] != v }.keys
  val unset = before.keys - after.keys
  return ScopeDiff(produced, unset)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.sandbox.PmSandboxEnvDiffTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandbox.kt src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxEnvDiffTest.kt
git commit -m "feat(sandbox): scope-diff helper for produced/unset ledger capture"
```

---

## Task 9: Wire PmSandbox into PmJsEval (replace evaluateJS), stub sendRequest

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt:143-146` and the `executeStepsSerially` signature
- Test: rely on existing `integrationTest` suite (real collections) + the unit tests above.

**Context:** `PmJsEval` currently calls `pm.evaluateJS(...)`. We route through `PmSandbox` instead, build the context from the current `pm`/step, and apply the result back to `pm.environment` via `diffScopes` so the ledger keeps working. `pm.sendRequest` is provided by the real bootcode, but its HTTP callback is unhandled in Phase 1 — so we detect a sendRequest attempt and fail the step with a clear "not yet supported (Phase 2)" message. (The real bootcode dispatches `execution.request.<id>`; with no host responder the loop would drain without the response, and the script's await would never settle → caught by the timeout. To give a crisp error instead, Task 6's `decodeResult` already ignores it; here we detect the unанswered request event and raise the explicit error.)

> Implementation note for the stub: in `SandboxBridge.decodeResult`, also scan for `name == "execution.request.$id"`; if present and no matching response was produced, set `error = UnsupportedOperationException("pm.sendRequest is not supported yet (Phase 2)")`. Add that branch now.

- [ ] **Step 1: Add the sendRequest-stub detection to SandboxBridge.decodeResult**

In `SandboxBridge.kt`, inside the `for (raw in emits)` `when`, add a branch:

```kotlin
        name == "execution.request.$id" -> {
          // Phase 1: no host HTTP responder wired. Surface a crisp, intentional error.
          error = UnsupportedOperationException("pm.sendRequest is not supported yet (Phase 2)")
        }
```

Also: because an unanswered `pm.sendRequest` leaves the script's `await` pending, guard the loop drain with the host timeout. In `dispatchExecute`, replace `loop.run()` (after the execute emit) with a bounded drain that stops once `execution.result.$id` OR `execution.request.$id` is seen:

```kotlin
    guestBridge.invokeMember("emit", "execute", id, event, ctxObj, options)
    drainUntilTerminal(id)
    return decodeResult(id)
```

And add:

```kotlin
  /** Drains the loop; the sandbox dispatches execution.result.<id> when done (or request.<id> for
   *  an unsupported sendRequest in Phase 1). The loop itself terminates when no tasks remain. */
  private fun drainUntilTerminal(id: String) {
    loop.run()
    // loop.run() returns when the queue is empty. For a well-formed script that is exactly after
    // execution.result.<id> has been dispatched (via setImmediate). For an unanswered sendRequest,
    // the queue also drains (the pending await simply never resumes), and decodeResult sees the
    // request event and raises the Phase-1 error.
  }
```

> No timeout thread is needed in Phase 1: with virtual-time timers, `loop.run()` always returns. The sandbox's own `options.timeout` covers genuinely infinite async only in Phase 2 when real I/O is involved.

- [ ] **Step 2: Rewrite PmJsEval.kt to route through PmSandbox**

Full new content of `src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Right
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.sandbox.PmScope
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.internal.postman.sandbox.diffScopes
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.RequestFailure.PreReqJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure

@JvmSynthetic
internal fun executePreReqJS(
  currentStep: Step,
  itemWithRegex: Item,
  currentStepReport: StepReport,
  pm: PostmanSDK,
  sandbox: PmSandbox,
): Either<PreReqJSFailure, Unit> {
  val preReqJS =
    itemWithRegex.event?.find { it.listen == "prerequest" }?.script?.exec?.joinToString("\n")
  return if (!preReqJS.isNullOrBlank()) {
    runCatching(currentStep, PRE_REQ_JS) {
        pm.request = pm.from(itemWithRegex.request)
        runSandboxScript(preReqJS, ScriptTarget.PRE_REQUEST, pm)
      }
      .mapLeft { PreReqJSFailure(it, currentStepReport.requestInfo!!.get()) }
  } else {
    Right(Unit)
  }
}

@JvmSynthetic
internal fun executePostResJS(
  currentStep: Step,
  item: Item,
  currentStepReport: StepReport,
  pm: PostmanSDK,
  sandbox: PmSandbox,
): Either<PostResJSFailure, Unit> {
  val postResJs = item.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  return if (!postResJs.isNullOrBlank()) {
    runCatching(currentStep, POST_RES_JS) {
        val httpResponse = currentStepReport.responseInfo!!.get().httpMsg
        pm.setRequestAndResponse(pm.from(item.request), httpResponse)
        runSandboxScript(postResJs, ScriptTarget.TEST, pm)
      }
      .mapLeft {
        PostResJSFailure(
          it,
          currentStepReport.requestInfo!!.get(),
          currentStepReport.responseInfo!!.get(),
        )
      }
  } else {
    Right(Unit)
  }
}

/**
 * Runs a pm script in the real sandbox, then applies the returned environment scope back onto
 * [PostmanSDK.environment] via a diff so the ledger records produced/unset keys exactly as before.
 * Throws on a script error so the surrounding [runCatching] maps it to the right failure type.
 */
private fun runSandboxScript(script: String, target: ScriptTarget, pm: PostmanSDK) {
  val before: Map<String, Any?> = pm.environment.mutableEnv.toMap()
  val context =
    PmExecutionContext(
      environment = PmScope("environment", before),
      request = pm.requestAsContextMap(),
      response = if (target == ScriptTarget.TEST) pm.responseAsContextMap() else null,
    )
  val result = pm.sandbox.execute(script, target, context)
  result.error?.let { throw it }
  // Apply env mutations back, recording produced/unset via the same code paths the ledger reads.
  val diff = diffScopes(before, result.environment)
  diff.produced.forEach { key -> pm.environment.set(key, result.environment[key]) }
  diff.unset.forEach { key -> pm.environment.unset(key) }
  // Assertions (pm.test results) are attached to the StepReport in a later phase; for Phase 1 they
  // are available on `result.assertions` and logged by the sandbox console handler.
}
```

> **Dependencies this introduces** (define them in Step 3): `pm.sandbox` accessor, `pm.requestAsContextMap()`, `pm.responseAsContextMap()`. These bridge the existing `PostmanSDK.Request`/`Response` inner classes to the context maps the sandbox expects.

- [ ] **Step 3: Add the context-map helpers + sandbox holder to PostmanSDK**

In `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt`, add a `sandbox` field and two helpers. Add to the constructor params:

```kotlin
  internal lateinit var sandbox: com.salesforce.revoman.internal.postman.sandbox.PmSandbox
```

And methods inside the class:

```kotlin
  internal fun requestAsContextMap(): Map<String, Any?>? =
    if (::request.isInitialized) {
      linkedMapOf(
        "method" to request.method,
        "url" to request.url.raw,
        "header" to request.header.map { linkedMapOf("key" to it.key, "value" to it.value) },
        "body" to (request.body?.raw?.let { linkedMapOf("mode" to "raw", "raw" to it) }),
      )
    } else null

  internal fun responseAsContextMap(): Map<String, Any?>? =
    if (::response.isInitialized) {
      linkedMapOf("code" to response.code, "status" to response.status, "body" to response.body)
    } else null
```

> Check `Url`'s raw-string accessor name (`request.url.raw`) against `template/Url.kt`; adjust if the field is named differently. Same for `Header.key`/`Header.value` against `template/Header.kt`.

- [ ] **Step 4: Thread PmSandbox through ReVoman.kt**

In `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`:

a) After constructing `pm` (line ~143-144), create and attach the sandbox, and ensure it's closed at run end. Replace:

```kotlin
    val pm =
      PostmanSDK(moshiReVoman, kick.nodeModulesPath(), regexReplacer, environment.toMutableMap())
    val stepNameToReport =
      executeStepsSerially(pmStepsDeepFlattened, kick, moshiReVoman, regexReplacer, pm)
```

with:

```kotlin
    val pm =
      PostmanSDK(moshiReVoman, kick.nodeModulesPath(), regexReplacer, environment.toMutableMap())
    val sandbox = com.salesforce.revoman.internal.postman.sandbox.PmSandbox()
    pm.sandbox = sandbox
    val stepNameToReport =
      sandbox.use { executeStepsSerially(pmStepsDeepFlattened, kick, moshiReVoman, regexReplacer, pm) }
```

b) Update the two `executePreReqJS(...)` / `executePostResJS(...)` call sites inside `executeStepsSerially` to pass `sandbox`:

```kotlin
              executePreReqJS(step, itemWithRegex, preStepReport, pm, pm.sandbox)
```
```kotlin
              timed(step, exeTimings, POST_RES_JS) { executePostResJS(step, itemWithRegex, sr, pm, pm.sandbox) }
```

(Threading via `pm.sandbox` avoids changing `executeStepsSerially`'s signature.)

- [ ] **Step 5: Build + run the full unit suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. The existing `EvalJsTest` exercises the OLD `pm.evaluateJS` directly — it still passes (that method stays on PostmanSDK; we did not remove it in Phase 1). New sandbox tests pass.

- [ ] **Step 6: Run integration tests (real collections, end-to-end)**

Run: `./gradlew integrationTest`
Expected: PASS. If a collection uses `pm.sendRequest`, that step now fails with the explicit Phase-2 message — note which collections, but do not fix here (Phase 2). If a collection fails for any OTHER reason, that is a Phase-1 regression to fix before proceeding.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/exe/PmJsEval.kt src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt src/main/kotlin/com/salesforce/revoman/ReVoman.kt src/main/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxBridge.kt
git commit -m "feat(sandbox): route pre-req/test scripts through real pm sandbox; stub sendRequest"
```

---

## Task 10: Ledger parity verification

**Files:**
- Test: extend `src/integrationTest` — run an existing ledger E2E (`src/test/kotlin/com/salesforce/revoman/LedgerSkipE2ETest.kt` and any integration ledger tests) and confirm produced/consumed sets are unchanged.

- [ ] **Step 1: Run the existing ledger tests**

Run: `./gradlew test --tests "com.salesforce.revoman.LedgerSkipE2ETest" && ./gradlew test --tests "com.salesforce.revoman.input.FileUtilsLedgerTest"`
Expected: PASS. These assert produced/consumed ledger entries. Because env mutations now flow through `pm.environment.set(...)` in `runSandboxScript` (same path the ledger reads), produced keys must match the pre-change output.

- [ ] **Step 2: If parity fails, diagnose (do not paper over)**

A mismatch means the diff produced a different set than the live-`set` capture did. Likely causes and fixes:
- A key set then unset within one script appears as `unset` (correct) — verify the old behavior matched.
- A key set to an equal value is NOT counted as produced by the diff (old `set()` always counted it). If the ledger expects it, change `diffScopes` to also include keys the script touched. To know "touched", the sandbox would need to report writes; defer that nuance only if a real test demands it, and document it.

- [ ] **Step 3: Commit any parity fix**

```bash
git add -A
git commit -m "test(sandbox): verify ledger produced/consumed parity with real pm sandbox"
```

---

## Task 11: Full verification + docs

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/PostmanSDK.kt` (KDoc note), spec status.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (compiles, spotless, all `test` + `integrationTest` green, except known sendRequest-using collections which are documented as Phase 2).

- [ ] **Step 2: Run spotless**

Run: `./gradlew spotlessApply`
Expected: formats new files; re-run `./gradlew build` → green.

- [ ] **Step 3: Update the spec status line**

In `docs/superpowers/specs/2026-06-04-pm-api-sandbox-integration-design.md`, under Rollout Phase 1, note completion date and link this plan.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "docs(sandbox): mark Phase 1 (script-only pm APIs via real sandbox) complete"
```

---

## Out of Scope (Phase 2 / Phase 3 — separate plans)

- **Phase 2:** wire `execution.request.<id>` → http4k `ApacheClient` (reuse `prepareHttpClient`, bypass hooks) with the `execution.response.<id>` callback; `pm.execution.setNextRequest`/`skipRequest` → step sequencer (modify `executeStepsSerially`'s linear fold into an index-driven driver); attach `result.assertions` to `StepReport`.
- **Phase 3:** delete old `PostmanSDK` shim internals (`evaluateJS`, `Variables`, `Request.json`, `Response.json/text`, dynamic-var generators); throw clear "unsupported" from `pm.cookies`/`pm.vault`/`pm.datasets`; benchmark; finalize known-limits doc.

---

## Self-Review Notes

- **Spec coverage (Phase 1 slice):** resource bundling+generator (T1) ✓; event loop (T2) ✓; Flatted (T3) ✓; resource load via ClasspathResolver/okio-gotcha (T4) ✓; context/result model (T5) ✓; boot+bridge+decode = the spike (T6) ✓; facade + per-pm-area coverage (T7) ✓; env-sync diff for ledger (T8) ✓; PmJsEval wiring + sendRequest stub + one-context-per-run lifecycle (T9) ✓; ledger parity (T10) ✓; full verification (T11) ✓. sendRequest/control-flow/assertion-attach correctly deferred to Phase 2.
- **Known soft spots flagged inline for the implementer:** (a) exact returned-scope serialization shape in `scopeValues` — reference the spike's raw dump; (b) `pm.response`/`pm.request` context map field names vs postman-collection — adjust if `.json()` fails; (c) `Url.raw`/`Header.key` accessor names — verify against template models; (d) ledger parity nuance for set-to-equal-value. Each has a concrete diagnosis step, not a hand-wave.

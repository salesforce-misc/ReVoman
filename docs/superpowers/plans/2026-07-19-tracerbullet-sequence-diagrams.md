# TracerBullet-style Sequence Diagrams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit a self-documenting Mermaid `sequenceDiagram` per run — actors = request hosts, edges = HTTP calls with status/timing, plus env data-flow notes and duplicate-call flags — from ReVoman's existing `StepEvent` stream, with no new dependency.

**Architecture:** A new `DiagramRunLogSink` (sibling to `ConsoleRunLogSink`/`FileRunLogSink`) consumes the same `StepEvent` stream, accumulates structured `RunInteraction`s during a run, and on `close()` renders a Mermaid diagram via a pure `DiagramRenderer` (the single grammar source, mirroring `RunLogRenderer`). A `CompositeRunLogSink` lets a consumer run file + diagram sinks together. The consumer composes (Core holds a concrete `FileRunLogSink` and calls file-only methods on it, so a library compose-factory is impossible).

**Tech Stack:** Kotlin (JDK 21), http4k `Request`/`Uri`, Kotest (unit), JUnit + Google Truth (integrationTest), Gradle. Core consumer side is Java (loki-core wrapper).

## Global Constraints

- JDK 21+ required; JVM target (do NOT build with JDK 25 — detekt breaks). Copied from DEVELOPMENT.md.
- No new runtime dependency — the diagram is text ReVoman generates (Core fat-jar `java_import` supplies no transitive deps).
- Sinks MUST NOT throw in a way that fails a run — every `line`/`event`/`close` swallows its own errors with a `logger.debug` breadcrumb (the `RunLogSink` contract).
- `RunLogRenderer` / `DiagramRenderer` are pure, stateless, `object`s; methods `@JvmStatic` for Java consumers.
- Kotlin style: 4-space indent; `when` over if-else chains; functional combinators over loops; explicit types on public API; KDoc on public APIs (see STYLE.md).
- Run formatting before any build that hits `spotlessCheck`: `./gradlew spotlessApply`.
- Copyright header block on every new `.kt`/`.java` file (copy from any sibling, e.g. `FileRunLogSink.kt` lines 1-7 for Kotlin; `RunLogConfig.java` lines 1-5 for Java).
- Core testing policy: run ONLY the new/modified FTest you touch (`ftest-console`); leave the full suite to CI.

---

## File Structure

New (ReVoman library):
- `src/main/kotlin/com/salesforce/revoman/output/log/RunInteraction.kt` — accumulation value type.
- `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRenderer.kt` — pure Mermaid grammar.
- `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSink.kt` — the sink + factory.
- `src/main/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSink.kt` — fan-out sink.
- `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRendererTest.kt`
- `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSinkTest.kt`
- `src/test/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSinkTest.kt`

Modified (ReVoman library):
- `src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt` — +`method`/`host`/`path` on `StepFinished`.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt` — +`requestCoordinates` helper.
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — populate the 3 fields in `emitStepFinished`.
- `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt` — +`diagram` field (`@JvmOverloads`).
- `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt` — 6-arg-compat case.
- `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonDiagramKtTest.kt` — end-to-end `.mmd`.

Modified (Core consumer — `~/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/`):
- `runtime/RunLogConfig.java` — read `logs.content.diagram`.
- `runtime/ReVomanFTest.java` — build + compose the diagram sink.
- one loki-core ReVoman FTest — assert the `.mmd`.

---

### Task 1: Add `method`/`host`/`path` to `StepEvent.StepFinished`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt:31-48`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/StepEventTest.kt` (create)

**Interfaces:**
- Produces: `StepEvent.StepFinished` with new trailing params `method: String? = null`, `host: String? = null`, `path: String? = null` (nullable, defaulted → every existing constructor call keeps compiling).

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/output/log/StepEventTest.kt`:

```kotlin
/*
 * (copy the 7-line copyright header from StepEvent.kt lines 1-7)
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepEventTest {

  @Test
  fun `StepFinished defaults new coordinate fields to null`() {
    val e =
      StepEvent.StepFinished(
        path = "s",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1L,
        outcome = Outcome.SUCCESS,
      )
    e.method.shouldBeNull()
    e.host.shouldBeNull()
    e.path shouldBe "s"
  }

  @Test
  fun `StepFinished carries method host path when supplied`() {
    val e =
      StepEvent.StepFinished(
        path = "api/v2/pokemon/ditto",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1L,
        outcome = Outcome.SUCCESS,
        method = "GET",
        host = "pokeapi.co",
      )
    e.method shouldBe "GET"
    e.host shouldBe "pokeapi.co"
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.StepEventTest"`
Expected: FAIL — compilation error, `StepFinished` has no parameter `method`.

- [ ] **Step 3: Add the fields**

In `StepEvent.kt`, extend the `StepFinished` data class (add after the existing `consumedValues` param, before the closing `)`):

```kotlin
    /** Consumed env keys mapped to their post-step values (`toString()`); empty if none. */
    val consumedValues: Map<String, String?> = emptyMap(),
    /** HTTP method of this step's request (e.g. "GET"); null when the step made no request. */
    val method: String? = null,
    /** Request host[:port] — the diagram actor for this step; null when the step made no request. */
    val host: String? = null,
    /** Request URI path; null when the step made no request. */
    val requestPath: String? = null,
  ) : StepEvent
```

**Naming note:** the new field is `requestPath` (NOT `path`) — `StepEvent.path` already exists as the interface member (the step id). The test in Step 1 is already consistent with this: it asserts `e.path shouldBe "s"` (the interface step id) and never references `requestPath`, so no test edit is needed here. `RunInteraction` (Task 3) and `DiagramRunLogSink.accumulate` (Task 4) both read this field as `requestPath` — keep the name identical everywhere.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.StepEventTest"`
Expected: PASS.

- [ ] **Step 5: Verify existing sink tests still compile/pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.*"`
Expected: PASS (all existing `FileRunLogSinkTest`/`RunLogRendererTest`/`ConsoleRunLogSinkTest` unaffected — new params defaulted).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/StepEvent.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/StepEventTest.kt
git commit -m "feat(log): add method/host/requestPath to StepFinished event"
```

---

### Task 2: Extract request coordinates + populate them at the emit site

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt` (add helper after `renderHttpMsg`)
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt:533-564` (`emitStepFinished`)
- Test: `src/test/kotlin/com/salesforce/revoman/internal/exe/RequestCoordinatesTest.kt` (create)

**Interfaces:**
- Consumes: `StepEvent.StepFinished(..., method, host, requestPath)` from Task 1.
- Produces: `internal fun requestCoordinates(request: Request): Triple<String, String, String>` returning `(method.name, uri.authority, uri.path)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/exe/RequestCoordinatesTest.kt`:

```kotlin
/*
 * (copy the 7-line copyright header from HttpRequest.kt lines 1-7)
 */
package com.salesforce.revoman.internal.exe

import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

class RequestCoordinatesTest {

  @Test
  fun `extracts method authority and path from a request`() {
    val req = Request(Method.GET, "https://pokeapi.co/api/v2/pokemon/ditto?x=1")
    val (method, host, path) = requestCoordinates(req)
    method shouldBe "GET"
    host shouldBe "pokeapi.co"
    path shouldBe "/api/v2/pokemon/ditto"
  }

  @Test
  fun `authority keeps an explicit port`() {
    val req = Request(Method.POST, "https://localhost:6101/services/data")
    val (_, host, _) = requestCoordinates(req)
    host shouldBe "localhost:6101"
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.RequestCoordinatesTest"`
Expected: FAIL — `requestCoordinates` unresolved.

- [ ] **Step 3: Add the helper**

In `HttpRequest.kt`, add after `renderHttpMsg` (keep the `@JvmSynthetic` + `internal` idiom used in that file):

```kotlin
/**
 * The diagram/topology coordinates of an http4k [Request]: its HTTP method name, the URI
 * authority (host[:port] — the sequence-diagram actor), and the URI path. Single source of the
 * request-shape a [com.salesforce.revoman.output.log.DiagramRunLogSink] plots, so extraction is
 * defined once here rather than re-parsed from rendered wire text.
 */
@JvmSynthetic
internal fun requestCoordinates(request: Request): Triple<String, String, String> =
  Triple(request.method.name, request.uri.authority, request.uri.path)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.RequestCoordinatesTest"`
Expected: PASS.

- [ ] **Step 5: Populate the fields at the emit site**

In `ReVoman.kt`, inside `emitStepFinished`, before the `RevomanLog.event(` call, capture the request once, then pass the three fields. Replace the body of `emitStepFinished` (lines 533-564) so it reads:

```kotlin
  /** Emits the [StepEvent.StepFinished] boundary event for a finished step's [report]. */
  private fun emitStepFinished(step: Step, report: StepReport) {
    val captureForSink = RunLogContext.hasActiveSink()
    val request: Request? =
      if (report.requestInfo != null && report.requestInfo.isRight)
        report.requestInfo.get().httpMsg
      else null
    val coordinates = request?.let { requestCoordinates(it) }
    RevomanLog.event(
      StepEvent.StepFinished(
        path = step.path,
        httpStatus =
          if (report.responseInfo != null && report.responseInfo.isRight)
            report.responseInfo.get().httpMsg.status.code
          else null,
        produced = report.envVars.produced,
        consumed = report.envVars.consumed,
        tookMs = report.exeTimings.values.sumOf { it.toMillis() },
        outcome = if (report.isSuccessful) Outcome.SUCCESS else Outcome.FAILED,
        requestMsg = if (captureForSink && request != null) renderHttpMsg(request) else null,
        responseMsg =
          if (captureForSink && report.responseInfo != null && report.responseInfo.isRight)
            renderHttpMsg(report.responseInfo.get().httpMsg)
          else null,
        producedValues =
          if (captureForSink)
            report.envVars.produced.associateWith { report.pmEnvSnapshot[it]?.toString() }
          else emptyMap(),
        consumedValues =
          if (captureForSink)
            report.envVars.consumed.associateWith { report.pmEnvSnapshot[it]?.toString() }
          else emptyMap(),
        method = coordinates?.first,
        host = coordinates?.second,
        requestPath = coordinates?.third,
      )
    )
  }
```

Add `import com.salesforce.revoman.internal.exe.requestCoordinates` and `import org.http4k.core.Request` to `ReVoman.kt` if not already present (check the existing import block; `Request` is likely already imported).

- [ ] **Step 6: Run to verify compilation + existing tests**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.exe.*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/internal/exe/HttpRequest.kt \
        src/main/kotlin/com/salesforce/revoman/ReVoman.kt \
        src/test/kotlin/com/salesforce/revoman/internal/exe/RequestCoordinatesTest.kt
git commit -m "feat(log): populate request coordinates on StepFinished"
```

---

### Task 3: `RunInteraction` + `DiagramRenderer` (pure Mermaid grammar)

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/RunInteraction.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRenderer.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRendererTest.kt`

**Interfaces:**
- Produces:
  - `data class RunInteraction(val seq: Int, val from: String, val to: String, val method: String, val requestPath: String, val status: Int?, val tookMs: Long, val outcome: Outcome, val produced: Set<String>, val consumed: Set<String>, val phase: String?, val intent: String?, val underTest: Boolean)`
  - `object DiagramRenderer { @JvmStatic fun render(interactions: List<RunInteraction>): String }`
- Consumes: `Outcome` (existing enum in `StepEvent.kt`).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRendererTest.kt`:

```kotlin
/*
 * (copy the 7-line copyright header from RunLogRenderer.kt lines 1-7)
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class DiagramRendererTest {

  private fun interaction(
    seq: Int,
    host: String,
    method: String = "GET",
    path: String = "/x",
    status: Int? = 200,
    tookMs: Long = 10L,
    outcome: Outcome = Outcome.SUCCESS,
    produced: Set<String> = emptySet(),
    consumed: Set<String> = emptySet(),
    phase: String? = null,
    intent: String? = null,
    underTest: Boolean = false,
  ): RunInteraction =
    RunInteraction(
      seq, "User", host, method, path, status, tookMs, outcome, produced, consumed, phase, intent,
      underTest,
    )

  @Test
  fun `empty run renders a minimal valid diagram`() {
    DiagramRenderer.render(emptyList()) shouldBe "sequenceDiagram\n    actor User\n"
  }

  @Test
  fun `single host renders participant request and response`() {
    val out = DiagramRenderer.render(listOf(interaction(0, "pokeapi.co", "GET", "/api/v2/pokemon/ditto")))
    out shouldStartWith "sequenceDiagram\n    actor User\n"
    out shouldContain "participant h0 as pokeapi.co\n"
    out shouldContain "User->>h0: GET /api/v2/pokemon/ditto\n"
    out shouldContain "h0-->>User: 200 (10ms)\n"
  }

  @Test
  fun `distinct hosts get distinct participants in first-seen order`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "pokeapi.co"),
          interaction(1, "restful-api.dev"),
          interaction(2, "pokeapi.co"),
        )
      )
    out shouldContain "participant h0 as pokeapi.co\n"
    out shouldContain "participant h1 as restful-api.dev\n"
    // first-seen order: pokeapi.co (h0) declared before restful-api.dev (h1)
    (out.indexOf("participant h0") < out.indexOf("participant h1")) shouldBe true
    // pokeapi.co reused on the third call -> still h0 (stable id)
    out shouldContain "User->>h0: GET /x\n"
    out shouldContain "User->>h1: GET /x\n"
  }

  @Test
  fun `failed step renders ERR status`() {
    val out = DiagramRenderer.render(listOf(interaction(0, "h", status = null, outcome = Outcome.FAILED)))
    out shouldContain "h0-->>User: ERR (10ms)\n"
  }

  @Test
  fun `phase boundary emits a note when the phase changes`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "a", phase = "SEED"),
          interaction(1, "a", phase = "SEED"),
          interaction(2, "a", phase = "TEST"),
        )
      )
    out shouldContain "Note over User: ━━ SEED"
    out shouldContain "Note over User: ━━ TEST"
    // only two phase notes (SEED once, TEST once), not per-interaction
    out.split("Note over User: ━━ SEED").size shouldBe 2
  }

  @Test
  fun `env data-flow renders a note linking consumer to producer host`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "auth.host", produced = setOf("accessToken")),
          interaction(1, "api.host", consumed = setOf("accessToken")),
        )
      )
    out shouldContain "Note right of h1: ⟵ accessToken from h0\n"
  }

  @Test
  fun `duplicate calls are flagged in the inefficiency summary`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "pokeapi.co", "GET", "/api/v2/pokemon/ditto"),
          interaction(1, "pokeapi.co", "GET", "/api/v2/pokemon/ditto"),
          interaction(2, "pokeapi.co", "GET", "/api/v2/pokemon/pikachu"),
        )
      )
    out shouldContain "Note over User: ⚠ 2× GET pokeapi.co/api/v2/pokemon/ditto"
    // the non-duplicate is NOT flagged
    (out.contains("⚠ 1×") ) shouldBe false
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.DiagramRendererTest"`
Expected: FAIL — `RunInteraction` / `DiagramRenderer` unresolved.

- [ ] **Step 3: Create `RunInteraction`**

Create `src/main/kotlin/com/salesforce/revoman/output/log/RunInteraction.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.output.log

/**
 * One HTTP interaction in a run, the unit a [DiagramRunLogSink] accumulates and [DiagramRenderer]
 * plots. [from] is always `"User"` — ReVoman is the black-box client, so the honest topology is
 * `User -> each distinct host`. [phase]/[intent]/[underTest] carry the enclosing runbook context
 * the sink tracks as it streams events; they are null for a plain (non-runbook) run.
 *
 * @param seq zero-based order of this interaction within the run
 * @param to request host[:port] — the diagram actor for this call
 * @param requestPath request URI path
 * @param status HTTP status code; null on a request that never got a response
 * @param produced env keys this step produced (for data-flow edges)
 * @param consumed env keys this step consumed (for data-flow edges)
 */
data class RunInteraction(
  val seq: Int,
  val from: String,
  val to: String,
  val method: String,
  val requestPath: String,
  val status: Int?,
  val tookMs: Long,
  val outcome: Outcome,
  val produced: Set<String>,
  val consumed: Set<String>,
  val phase: String?,
  val intent: String?,
  val underTest: Boolean,
)
```

- [ ] **Step 4: Create `DiagramRenderer`**

Create `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRenderer.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.output.log

/**
 * The single source of truth for rendering a run's [RunInteraction] list to a Mermaid
 * `sequenceDiagram`. Pure and stateless (a function of its argument only), mirroring
 * [RunLogRenderer]: the diagram grammar lives ONCE here so no consumer can drift it. Chosen over
 * PlantUML because Mermaid renders natively in GitHub/IDEs and needs no external tool.
 *
 * The diagram carries three things beyond "who called whom":
 * - one `participant` per distinct host (first-seen order, stable id `h<n>`),
 * - a phase note whenever the enclosing runbook phase changes,
 * - a data-flow note where a step consumes an env key an earlier step produced,
 * - and a trailing inefficiency summary flagging duplicate `(method, host, path)` calls.
 */
object DiagramRenderer {

  /** Render [interactions] to a Mermaid `sequenceDiagram` block (newline-terminated). */
  @JvmStatic
  fun render(interactions: List<RunInteraction>): String {
    val header = "sequenceDiagram\n    actor User\n"
    if (interactions.isEmpty()) return header
    val hostIds: Map<String, String> = assignHostIds(interactions)
    val participants =
      hostIds.entries.joinToString("") { (host, id) -> "    participant $id as $host\n" }
    val body = renderBody(interactions, hostIds)
    val inefficiency = renderInefficiency(interactions)
    return header + participants + body + inefficiency
  }

  /** Distinct hosts mapped to stable ids `h0, h1, …` in first-seen order. */
  private fun assignHostIds(interactions: List<RunInteraction>): Map<String, String> =
    interactions
      .map { it.to }
      .distinct()
      .withIndex()
      .associate { (i, host) -> host to "h$i" }

  private fun renderBody(
    interactions: List<RunInteraction>,
    hostIds: Map<String, String>,
  ): String {
    // Where each produced key first appeared, so a later consumer can point back to its host id.
    val producedBy: Map<String, String> =
      interactions
        .flatMap { i -> i.produced.map { key -> key to hostIds.getValue(i.to) } }
        .toMap()
    val sb = StringBuilder()
    var currentPhase: String? = null
    for (i in interactions) {
      if (i.phase != null && i.phase != currentPhase) {
        sb.append("    Note over User: ━━ ${i.phase}\n")
        currentPhase = i.phase
      }
      val id = hostIds.getValue(i.to)
      sb.append("    User->>$id: ${i.method} ${i.requestPath}\n")
      val statusText = i.status?.toString() ?: "ERR"
      sb.append("    $id-->>User: $statusText (${i.tookMs}ms)\n")
      i.consumed
        .mapNotNull { key -> producedBy[key]?.let { producerId -> key to producerId } }
        .filter { (_, producerId) -> producerId != id }
        .forEach { (key, producerId) ->
          sb.append("    Note right of $id: ⟵ $key from $producerId\n")
        }
    }
    return sb.toString()
  }

  /** Trailing `⚠ n× METHOD host/path` note per duplicated call; empty when nothing repeats. */
  private fun renderInefficiency(interactions: List<RunInteraction>): String =
    interactions
      .groupingBy { "${it.method} ${it.to}${it.requestPath}" }
      .eachCount()
      .filter { it.value > 1 }
      .entries
      .joinToString("") { (call, count) -> "    Note over User: ⚠ ${count}× $call\n" }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.DiagramRendererTest"`
Expected: PASS (all 7).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/RunInteraction.kt \
        src/main/kotlin/com/salesforce/revoman/output/log/DiagramRenderer.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/DiagramRendererTest.kt
git commit -m "feat(log): add DiagramRenderer + RunInteraction (Mermaid sequence diagram)"
```

---

### Task 4: `DiagramRunLogSink`

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSinkTest.kt`

**Interfaces:**
- Consumes: `RunInteraction`, `DiagramRenderer` (Task 3); `StepEvent`, `RunLogSink`, `Outcome`.
- Produces:
  - `class DiagramRunLogSink : RunLogSink` with a companion `@JvmStatic fun open(logsDir: Path, runLabel: String, startedAt: Instant): DiagramRunLogSink` and `@JvmStatic fun openOrNoOp(logsDir: Path, runLabel: String, startedAt: Instant): DiagramRunLogSink?`.
  - Writes `<logsDir>/<runLabel>/<timestamp>.mmd` on `close()`, repoints `latest.mmd`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSinkTest.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagramRunLogSinkTest {

  private val ts: Instant = Instant.parse("2026-06-05T13:57:02Z")
  private val mmd = "2026-06-05T13-57-02.mmd"

  private fun mmdFile(logsDir: Path, label: String): Path = logsDir.resolve(label).resolve(mmd)

  private fun finished(host: String, path: String, produced: Set<String> = emptySet(), consumed: Set<String> = emptySet()) =
    StepEvent.StepFinished(
      path = path,
      httpStatus = 200,
      produced = produced,
      consumed = consumed,
      tookMs = 7L,
      outcome = Outcome.SUCCESS,
      method = "GET",
      host = host,
      requestPath = path,
    )

  @Test
  fun `writes a mmd sequence diagram on close`(@TempDir logsDir: Path) {
    val sink = DiagramRunLogSink.open(logsDir, "T.m", ts)
    sink.event(finished("pokeapi.co", "/api/v2/pokemon/ditto"))
    sink.close()
    val body = Files.readString(mmdFile(logsDir, "T.m"))
    body shouldStartWith "sequenceDiagram"
    body shouldContain "participant h0 as pokeapi.co"
    body shouldContain "User->>h0: GET /api/v2/pokemon/ditto"
  }

  @Test
  fun `tracks phase from PhaseEntered and plots it`(@TempDir logsDir: Path) {
    val sink = DiagramRunLogSink.open(logsDir, "T.m", ts)
    sink.event(StepEvent.PhaseEntered(Phase.SEED))
    sink.event(finished("a.host", "/x"))
    sink.close()
    Files.readString(mmdFile(logsDir, "T.m")) shouldContain "Note over User: ━━ SEED"
  }

  @Test
  fun `ignores StepFinished with no host`(@TempDir logsDir: Path) {
    val sink = DiagramRunLogSink.open(logsDir, "T.m", ts)
    sink.event(
      StepEvent.StepFinished(
        path = "hook-only",
        httpStatus = null,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1L,
        outcome = Outcome.SUCCESS,
      )
    )
    sink.close()
    // no participants, just the minimal header
    Files.readString(mmdFile(logsDir, "T.m")) shouldBe "sequenceDiagram\n    actor User\n"
  }

  @Test
  fun `latest_mmd points to the newest run`(@TempDir logsDir: Path) {
    DiagramRunLogSink.open(logsDir, "T.m", ts).close()
    val latest = logsDir.resolve("T.m").resolve("latest.mmd")
    Files.exists(latest) shouldBe true
    val pointed =
      if (Files.isSymbolicLink(latest)) Files.readSymbolicLink(latest).fileName.toString()
      else Files.readString(latest).trim()
    pointed shouldBe mmd
  }

  @Test
  fun `openOrNoOp on an unwritable dir returns null and does not throw`() {
    DiagramRunLogSink.openOrNoOp(Path.of("/dev/null/cannot/create"), "T.m", ts).shouldBeNull()
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.DiagramRunLogSinkTest"`
Expected: FAIL — `DiagramRunLogSink` unresolved.

- [ ] **Step 3: Create `DiagramRunLogSink`**

Create `src/main/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSink.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * A [RunLogSink] that renders ONE run as a Mermaid `sequenceDiagram`. Unlike the per-event live
 * text sinks ([ConsoleRunLogSink]/[FileRunLogSink]), it ACCUMULATES the run's HTTP interactions
 * during the run and renders the whole diagram on [close] — a sequence diagram is a whole-run
 * artifact. All grammar delegates to [DiagramRenderer] (the single source). Writes
 * `<logsDir>/<runLabel>/<timestamp>.mmd` and repoints `latest.mmd`. Best-effort and never-throw:
 * any render/IO error is swallowed (logged once), honoring the [RunLogSink] contract.
 * Single-threaded for its lifetime per that contract.
 */
@Suppress("TooGenericExceptionCaught")
class DiagramRunLogSink
private constructor(
  private val mmdFile: Path,
  private val testDir: Path,
) : RunLogSink {

  private val interactions = mutableListOf<RunInteraction>()
  private var seq = 0
  private var currentPhase: String? = null
  private var currentIntent: String? = null
  private var currentUnderTest = false

  /** No-op: the diagram is built from structured events, not narration lines. */
  override fun line(level: LogLevel, message: String) {}

  override fun event(event: StepEvent) {
    runCatching { accumulate(event) }
      .onFailure { logger.debug { "DiagramRunLogSink event failed (ignored): $it" } }
  }

  private fun accumulate(event: StepEvent) {
    when (event) {
      is StepEvent.PhaseEntered -> currentPhase = event.phase.name
      is StepEvent.RunbookStepStarted -> {
        currentIntent = event.intent
        currentUnderTest = event.underTest
      }
      is StepEvent.RunbookStepFinished -> {
        currentIntent = null
        currentUnderTest = false
      }
      is StepEvent.StepFinished ->
        event.host?.let { host ->
          interactions.add(
            RunInteraction(
              seq = seq++,
              from = "User",
              to = host,
              method = event.method ?: "?",
              requestPath = event.requestPath ?: "",
              status = event.httpStatus,
              tookMs = event.tookMs,
              outcome = event.outcome,
              produced = event.produced,
              consumed = event.consumed,
              phase = currentPhase,
              intent = currentIntent,
              underTest = currentUnderTest,
            )
          )
        }
      else -> {} // other events do not contribute an interaction
    }
  }

  override fun close() {
    runCatching {
        val diagram = DiagramRenderer.render(interactions.toList())
        Files.writeString(mmdFile, diagram, StandardCharsets.UTF_8)
        repointLatest()
      }
      .onFailure { logger.debug { "DiagramRunLogSink close failed (ignored): $it" } }
  }

  /** Repoint `latest.mmd` -> this run file. Symlink first; fall back to a pointer file. */
  private fun repointLatest() {
    val latest = testDir.resolve(LATEST)
    val target = mmdFile.fileName
    try {
      Files.deleteIfExists(latest)
      Files.createSymbolicLink(latest, target)
    } catch (e: Exception) {
      try {
        Files.writeString(latest, target.toString(), StandardCharsets.UTF_8)
      } catch (ignored: IOException) {
        logger.warn { "DiagramRunLogSink latest pointer failed (ignored): $e" }
      }
    }
  }

  companion object {
    private val STAMP: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)
    private const val LATEST = "latest.mmd"

    /**
     * Open a diagram sink for one run, creating `<logsDir>/<runLabel>/`. Throws [IOException] on a
     * directory-creation failure — callers wanting the never-fail guarantee use [openOrNoOp].
     */
    @JvmStatic
    @Throws(IOException::class)
    fun open(logsDir: Path, runLabel: String, startedAt: Instant): DiagramRunLogSink {
      val testDir = logsDir.resolve(runLabel)
      Files.createDirectories(testDir)
      val mmdFile = testDir.resolve(STAMP.format(startedAt) + ".mmd")
      return DiagramRunLogSink(mmdFile, testDir)
    }

    /** Never-throw factory: a real sink, or `null` when opening failed. */
    @JvmStatic
    fun openOrNoOp(logsDir: Path, runLabel: String, startedAt: Instant): DiagramRunLogSink? =
      try {
        open(logsDir, runLabel, startedAt)
      } catch (e: Exception) {
        logger.warn { "DiagramRunLogSink open failed; diagram disabled for this run: $e" }
        null
      }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.DiagramRunLogSinkTest"`
Expected: PASS (all 5).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/DiagramRunLogSinkTest.kt
git commit -m "feat(log): add DiagramRunLogSink (accumulate events, render .mmd on close)"
```

---

### Task 5: `CompositeRunLogSink`

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSink.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSinkTest.kt`

**Interfaces:**
- Consumes: `RunLogSink`, `StepEvent`, `LogLevel`.
- Produces: `class CompositeRunLogSink(delegates: List<RunLogSink>) : RunLogSink` with `companion object { @JvmStatic fun of(vararg sinks: RunLogSink): RunLogSink }`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSinkTest.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CompositeRunLogSinkTest {

  private class Recording : RunLogSink {
    val events = mutableListOf<StepEvent>()
    var closed = false

    override fun line(level: LogLevel, message: String) {}

    override fun event(event: StepEvent) {
      events.add(event)
    }

    override fun close() {
      closed = true
    }
  }

  private class Exploding : RunLogSink {
    override fun line(level: LogLevel, message: String) = error("boom-line")

    override fun event(event: StepEvent) = error("boom-event")

    override fun close() = error("boom-close")
  }

  private val evt = StepEvent.RequestSkipped("s")

  @Test
  fun `fans event and close out to every delegate`() {
    val a = Recording()
    val b = Recording()
    val composite = CompositeRunLogSink.of(a, b)
    composite.event(evt)
    composite.close()
    a.events shouldBe listOf(evt)
    b.events shouldBe listOf(evt)
    a.closed shouldBe true
    b.closed shouldBe true
  }

  @Test
  fun `one throwing delegate does not stop the others or fail the call`() {
    val good = Recording()
    val composite = CompositeRunLogSink.of(Exploding(), good)
    // must not throw
    composite.event(evt)
    composite.line(LogLevel.INFO, "x")
    composite.close()
    good.events shouldBe listOf(evt)
    good.closed shouldBe true
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.CompositeRunLogSinkTest"`
Expected: FAIL — `CompositeRunLogSink` unresolved.

- [ ] **Step 3: Create `CompositeRunLogSink`**

Create `src/main/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSink.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A [RunLogSink] that fans every call out to each of its [delegates], so one run can drive several
 * sinks at once (e.g. a [FileRunLogSink] for the text log AND a [DiagramRunLogSink] for the
 * diagram). Each delegate call is guarded independently: a throwing delegate is swallowed (logged
 * once) and NEVER stops the remaining delegates or fails the run, honoring the [RunLogSink]
 * never-throw contract. The CONSUMER composes this (it owns the concrete sink handles); the
 * library never returns a composite from its own factories.
 */
@Suppress("TooGenericExceptionCaught")
class CompositeRunLogSink(private val delegates: List<RunLogSink>) : RunLogSink {

  override fun line(level: LogLevel, message: String) =
    delegates.forEach { guard { it.line(level, message) } }

  override fun event(event: StepEvent) = delegates.forEach { guard { it.event(event) } }

  override fun close() = delegates.forEach { guard { it.close() } }

  private inline fun guard(block: () -> Unit) =
    runCatching(block).onFailure { logger.debug { "CompositeRunLogSink delegate failed (ignored): $it" } }
      .let {}

  companion object {
    /**
     * Compose [sinks] into one fan-out sink. Filters out [RunLogSink.NoOp] delegates (they add
     * nothing); returns the sole real sink directly when only one remains, and [RunLogSink.NoOp]
     * when none do — so the composite is only allocated when it actually fans out.
     */
    @JvmStatic
    fun of(vararg sinks: RunLogSink): RunLogSink {
      val real = sinks.filter { it !== RunLogSink.NoOp }
      return when (real.size) {
        0 -> RunLogSink.NoOp
        1 -> real.first()
        else -> CompositeRunLogSink(real.toList())
      }
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.CompositeRunLogSinkTest"`
Expected: PASS (both).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSink.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/CompositeRunLogSinkTest.kt
git commit -m "feat(log): add CompositeRunLogSink fan-out sink"
```

---

### Task 6: `FileRunLogConfig` +`diagram` (source-compatible for Core)

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt`

**Interfaces:**
- Produces: `FileRunLogConfig` gains a trailing `val diagram: Boolean` with a `= false` default and a `@JvmOverloads` constructor, so Java's positional 6-arg `new FileRunLogConfig(libLogs, steps, perf, outcome, runbook, heaviestSteps)` still compiles (regenerated overload) and yields `diagram == false`.

- [ ] **Step 1: Inspect the existing test file**

Read `src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt` to match its idiom (it exists — see file list). Add the new case alongside the existing ones.

- [ ] **Step 2: Write the failing test**

Add to `FileRunLogConfigTest.kt`:

```kotlin
  @Test
  fun `six-arg constructor stays source-compatible and defaults diagram off`() {
    // Mirrors Core's positional Java call: new FileRunLogConfig(libLogs, steps, perf, outcome,
    // runbook, heaviestSteps). @JvmOverloads must regenerate this arity.
    val config = FileRunLogConfig(true, true, true, true, true, 10)
    config.diagram shouldBe false
  }

  @Test
  fun `diagram can be turned on via the seven-arg form`() {
    val config = FileRunLogConfig(true, true, true, true, true, 10, true)
    config.diagram shouldBe true
  }
```

(Ensure `import io.kotest.matchers.shouldBe` is present.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogConfigTest"`
Expected: FAIL — `diagram` unresolved.

- [ ] **Step 4: Add the field with `@JvmOverloads`**

Edit `FileRunLogConfig.kt`. Add the `@JvmOverloads` constructor annotation and the trailing field with a default:

```kotlin
data class FileRunLogConfig
@JvmOverloads
constructor(
  val libLogs: Boolean,
  val steps: Boolean,
  val perf: Boolean,
  val outcome: Boolean,
  val runbook: Boolean,
  val heaviestSteps: Int,
  /** Also render a Mermaid `sequenceDiagram` `.mmd` for the run (via a DiagramRunLogSink). */
  val diagram: Boolean = false,
) {
```

Add `@param diagram ...` to the KDoc `@param` block. Leave `DEFAULT_ALL` untouched — `diagram` defaults to `false` there, which is correct (diagrams are opt-in even in the all-content default; a consumer flips it explicitly).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.FileRunLogConfigTest"`
Expected: PASS.

- [ ] **Step 6: Verify the whole log package still passes**

Run: `./gradlew test --tests "com.salesforce.revoman.output.log.*"`
Expected: PASS (existing `FileRunLogSinkTest` positional 6-arg `FileRunLogConfig(...)` calls still compile via the regenerated overload).

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/kotlin/com/salesforce/revoman/output/log/FileRunLogConfig.kt \
        src/test/kotlin/com/salesforce/revoman/output/log/FileRunLogConfigTest.kt
git commit -m "feat(log): add diagram toggle to FileRunLogConfig (@JvmOverloads compat)"
```

---

### Task 7: ReVoman end-to-end integration test (`.mmd` from a real run)

**Files:**
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonDiagramKtTest.kt`

**Interfaces:**
- Consumes: `DiagramRunLogSink.open` (Task 4), `Kick.configure().runLogSink(...)`, existing pokemon collection paths (`pm-templates/v3/pokemon`).

- [ ] **Step 1: Write the test (this is the failing test AND the deliverable — it exercises the full pipeline: revUp → emitStepFinished coordinates → sink → .mmd)**

Create `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonDiagramKtTest.kt`:

```kotlin
/*
 * (copy the 7-line copyright header)
 */
package com.salesforce.revoman.integration.pokemon.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.log.DiagramRunLogSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PokemonDiagramKtTest {

  @Test
  fun `run produces a mermaid sequence diagram`(@TempDir logsDir: Path) {
    val startedAt = Instant.parse("2026-06-05T13:57:02Z")
    val sink = DiagramRunLogSink.open(logsDir, "PokemonDiagramKtTest.run", startedAt)
    ReVoman.revUp(
      Kick.configure()
        .templatePath(PM_COLLECTION_PATH)
        .environmentPath(PM_ENVIRONMENT_PATH)
        .nodeModulesPath("js")
        .dynamicEnvironment(mapOf("offset" to "0", "limit" to "1"))
        .runLogSink(sink)
        .off()
    )
    sink.close()

    val mmd = logsDir.resolve("PokemonDiagramKtTest.run").resolve("2026-06-05T13-57-02.mmd")
    val body = Files.readString(mmd)
    assertThat(body).startsWith("sequenceDiagram")
    assertThat(body).contains("participant h0 as pokeapi.co")
    assertThat(body).contains("User->>h0:")
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/pokemon"
    private const val PM_ENVIRONMENT_PATH = "pm-templates/v3/pokemon/Pokemon.environment.yaml"
  }
}
```

- [ ] **Step 2: Run it (it is a live external-API test — pokeapi.co)**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.PokemonDiagramKtTest"`
Expected: PASS. (If it fails ONLY on a pokeapi.co network flake, rerun — flaky external-API tests are retried on CI but not locally; the assertion itself is deterministic once the run completes.)

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonDiagramKtTest.kt
git commit -m "test(log): end-to-end pokemon run produces a Mermaid .mmd diagram"
```

---

### Task 8: Full ReVoman build gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full build**

Run: `export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn && ./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL — unit + integration tests + `spotlessCheck` + `detekt` + `kover` all green. (JDK 21; detekt breaks on JDK 25.)

- [ ] **Step 2: If detekt flags the new files**

Address any detekt finding in the new files directly (e.g. add a scoped `@Suppress` with a one-line justification comment ONLY where the class doc already explains it, matching `FileRunLogSink`'s `@Suppress("TooManyFunctions", "TooGenericExceptionCaught")` precedent). Re-run Step 1. Do NOT suppress without a stated reason (STYLE.md).

- [ ] **Step 3: Commit any fixups**

```bash
git add -A && git commit -m "chore(log): satisfy detekt/spotless for diagram sink"
```

---

### Task 9: Core consumer wiring + Core FTest (test the feature through the real consumer)

This task is in the **Core repo** (`~/core-public/core`), Java, following `/my-java-coding-style`. Core picks up ReVoman sources directly via `.bazelrc-local` — no jar publish needed for compilation. Invoke `/salesforce-core-dev:core-engineer` for server/FTest operations; run ONLY the touched FTest.

**Files:**
- Modify: `loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/RunLogConfig.java:52-59`
- Modify: `loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/runtime/ReVomanFTest.java` (`buildRunLogSink` ~:277-291, sink type ~:44, install ~:121-123, `closeRunLog` ~:145-152)
- Modify or create: one loki-core ReVoman FTest that asserts the `.mmd`.

**Interfaces:**
- Consumes: `FileRunLogConfig` 7-arg (Task 6), `DiagramRunLogSink.openOrNoOp` (Task 4), `CompositeRunLogSink.of` (Task 5), `RunLogSink`.

- [ ] **Step 1: Compile-compat gate FIRST (before any edit)**

Confirm Core's UNCHANGED `RunLogConfig.java` still compiles against the new `FileRunLogConfig` (the `@JvmOverloads` guarantee). Build just the wrapper module:

Run (via core-engineer, from the Core root):
`bazel build //loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/...`
Expected: SUCCESS with no edits — proves the field addition is source-compatible. If this FAILS, the `@JvmOverloads` in Task 6 is wrong; fix Task 6 before proceeding.

- [ ] **Step 2: Read `logs.content.diagram` in `RunLogConfig.java`**

In `RunLogConfig.read(Path)`, change the `FileRunLogConfig` construction to the 7-arg form (add `diagram`, default `false`):

```java
            final FileRunLogConfig toggles =
                    new FileRunLogConfig(
                            boolAt(content, "libLogs", true),
                            boolAt(content, "steps", true),
                            boolAt(content, "perf", true),
                            boolAt(content, "outcome", true),
                            boolAt(content, "runbook", true),
                            heaviestStepsAt(logs),
                            boolAt(content, "diagram", false));
```

`FileRunLogConfig.DEFAULT_ALL` (used by `DEFAULT_ALL` on `RunLogConfig`) already carries `diagram == false`, so the default-on-error path stays diagram-off — no change there.

- [ ] **Step 3: Compose the diagram sink in `ReVomanFTest.java`**

`buildRunLogSink` returns a concrete `FileRunLogSink` today, and the field is typed `FileRunLogSink` because Core calls file-only methods on it. Keep that handle; compose the diagram sink separately into the RUNNER's sink slot.

3a. Keep the field `private FileRunLogSink runLogSink;` as-is (Core still needs `recordRunFact`/`renderHeaviestSteps`/`recordPerfSummary`/`footer`).

3b. Add a second field for the optional diagram sink:

```java
    // Optional per-dispatch Mermaid diagram sink; null when logs.content.diagram is off / open
    // failed. Held separately from the FileRunLogSink because the runner drives a COMPOSITE of the
    // two, but the file-specific calls (footer/perf/recordRunFact) go to the FileRunLogSink handle.
    private DiagramRunLogSink diagramSink;
```

3c. In `ftestSetUp`, where the runner sink is installed (~:121-123), build the diagram sink and install the composite:

```java
        runLogSink = buildRunLogSink(resolved, runStartedAt);
        diagramSink = buildDiagramSink(runStartedAt);
        final RunLogSink activeSink =
                CompositeRunLogSink.of(
                        runLogSink != null ? runLogSink : RunLogSink.NoOp.INSTANCE,
                        diagramSink != null ? diagramSink : RunLogSink.NoOp.INSTANCE);
        runner().setRunLogSink(activeSink);
        ReVomanPerf.setSink(runLogSink);
```

3d. Add `buildDiagramSink` next to `buildRunLogSink`:

```java
    /**
     * Build the per-dispatch Mermaid diagram sink, or {@code null} when disabled (autobuild, logs
     * off, or {@code logs.content.diagram} off) or open failed. Same logs dir / run label / start
     * instant as the {@link FileRunLogSink}, so the {@code .mmd} lands beside the {@code .log}.
     */
    private DiagramRunLogSink buildDiagramSink(final Instant startedAt) {
        if (TestContext.isRunningOnAutoBuild()) {
            return null;
        }
        if (runLogConfig == null || !runLogConfig.enabled() || !runLogConfig.content().getDiagram()) {
            return null;
        }
        return DiagramRunLogSink.openOrNoOp(
                RevomanHome.logsDir(),
                getClass().getSimpleName() + "." + getTestMethodName(),
                startedAt);
    }
```

Note `runLogConfig` is assigned inside `buildRunLogSink`, which runs on the line ABOVE `buildDiagramSink` in 3c — so `runLogConfig` is populated. (If `buildRunLogSink` returned early via autobuild, `buildDiagramSink`'s own autobuild guard also returns null.)

3e. In `closeRunLog()` (~:145-152), close the diagram sink too:

```java
    private void closeRunLog() {
        if (runLogSink != null) {
            runLogSink.close();
        }
        if (diagramSink != null) {
            diagramSink.close();
        }
        ReVomanPerf.setSink(null);
        runLogSink = null;
        diagramSink = null;
    }
```

3f. Add imports at the top of `ReVomanFTest.java`:

```java
import com.salesforce.revoman.output.log.CompositeRunLogSink;
import com.salesforce.revoman.output.log.DiagramRunLogSink;
import com.salesforce.revoman.output.log.RunLogSink;
```

(`RunLogSink` / `FileRunLogSink` may already be imported — check before adding.)

- [ ] **Step 4: Compile the wrapper module**

Run: `bazel build //loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/...`
Expected: SUCCESS.

- [ ] **Step 5: Add the Core FTest assertion**

Pick the smallest existing loki-core ReVoman FTest that runs a real collection (ask core-engineer / check `test/func/ftest-inventory.xml`), OR add a focused one. It must: enable diagrams (write `logs.content.diagram: true` into the config the test reads, or override the `RunLogConfig` in the test harness), run a dispatch, then assert a `.mmd` exists under `RevomanHome.logsDir()/<TestClass>.<method>/` whose content starts with `sequenceDiagram`. Concretely, after the dispatch:

```java
        final Path testDir =
                RevomanHome.logsDir().resolve(getClass().getSimpleName() + "." + getTestMethodName());
        try (var stream = Files.list(testDir)) {
            final Path mmd =
                    stream.filter(p -> p.getFileName().toString().endsWith(".mmd"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no .mmd written under " + testDir));
            assertThat(Files.readString(mmd)).startsWith("sequenceDiagram");
        }
```

(Use the FTest's existing assertion library — Truth or JUnit — matching the chosen test file. Follow `/my-java-coding-style`: `final var`, no bare nulls, functional style.)

- [ ] **Step 6: Register + run ONLY that FTest**

If a NEW test class, register it in the module's `test/func/ftest-inventory.xml` (per `/revoman-for-core:core-app-ops`). Then run just it:

Run (via core-engineer, `ftest-console`): the single test method.
Expected: PASS, and a `.mmd` beside the `.log` for that test under `~/.revoman/logs/`.

- [ ] **Step 7: (Only if the E2E server path is exercised) rebuild the jar + restart**

Per DEVELOPMENT.md — the running Core server holds old bytecode; a `java_import` jar is not hot-reloaded:

```bash
cd ~/code-clones/work/revoman-root
rm -f build/libs/revoman-*.jar
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn
gradle clean build   # or ./gradlew jar sourcesJar -x detekt -x test --rerun-tasks for jar-only
```
Then restart the Core server (spawn a sub-agent for the restart). Not needed if the FTest exercises only compiled wrapper code against a running server that already loaded a jar containing these classes — but since these are NEW classes, a jar rebuild + restart IS required for any server-side run.

- [ ] **Step 8: Commit (Core repo)**

Use `/core-git` for Core's git. Commit the three edits with a message referencing the ReVoman feature.

---

## Notes for the executor

- **Order matters:** Tasks 1→2 feed the data; 3→4→5 build the sinks; 6 is the config seam; 7 proves the ReVoman side end-to-end; 8 gates the whole library build; 9 proves the Core consumer. Task 9 depends on Tasks 4/5/6 being merged (Core reads them from ReVoman sources via `.bazelrc-local`).
- **Naming pin:** the `StepFinished` field is `requestPath` (NOT `path`) — `StepEvent.path` already exists as the step id. `RunInteraction.requestPath` matches. `DiagramRenderer` reads `RunInteraction.requestPath`. Do not rename in one place only.
- **Never-throw:** every sink swallows its own errors. Do not let a `runCatching` become a rethrow.
- **Mermaid glyphs** used (keep exact for tests): `━` (━ heavy line, phase), `⟵` (⟵ data-flow), `⚠` (⚠ inefficiency), `×` (× count).
```

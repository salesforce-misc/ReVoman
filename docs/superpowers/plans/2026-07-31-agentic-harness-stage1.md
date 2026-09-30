# Agentic Harness — Stage 1 (Deterministic Spine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a new `agentic-harness` Gradle module with a local mock CPQ server and three ReVoman V3 collections (`configure` → `price` → `quote`) that chain via `{{var}}` threading, executed by `ReVoman.revUp`, with a Layer-1 contract test asserting on the `Rundown`.

**Architecture:** A new Gradle subproject depends on `:` (the ReVoman library) and reuses its transitive test deps. A JDK `com.sun.net.httpserver.HttpServer` (the same in-process mock idiom the library's own `ControlFlowE2ETest` uses — zero new dependencies) serves `/configure`, `/price`, `/quote` backed by an in-memory `MutableMap` "DB". Three V3 Postman collections (mirroring the format of `src/integrationTest/resources/pm-templates/v3/core`) point at the mock and thread data through `pm.environment.set(...)` + `{{var}}`. A `GraphRunner` calls `ReVoman.revUp(List<Kick>)` to run the chain and prints `Rundown.toJson(SUMMARY)`.

**Tech Stack:** Kotlin (JVM 21), Gradle (`include(...)` submodule + `buildSrc` conventions), ReVoman library (project dep), JDK HttpServer, JUnit5 + Google Truth (assertions), Moshi (JSON, already transitive via ReVoman's http4k/moshix). No koog, no LLM, no API key in Stage 1.

## Global Constraints

- **JDK 21** runtime and build (`libs.versions.toml`: `jdk = "21"`).
- **Never modify the ReVoman library** (`build.gradle.kts`, `src/main`, `src/test`, `src/integrationTest`). Only add the new module and one `include(...)` line in `settings.gradle.kts`.
- **No new external dependencies in Stage 1.** Use the JDK HttpServer and deps already transitively provided by `project(":")` (Moshi, Truth via testImplementation, JUnit5).
- **Formatting:** ktfmt Google style (spotless runs repo-wide). Run `./gradlew spotlessApply` before every commit or `spotlessCheck` fails the build.
- **Copyright header:** every Kotlin source file in this repo starts with the standard SFDC Apache-2.0 header block (copy from any existing `src/main/kotlin/**/*.kt`). Apply it to every new `.kt`.
- **Test convention:** JUnit5 (`@Test`, `org.junit.jupiter.api`) with Google Truth (`com.google.common.truth.Truth.assertThat`) — matches the library's `RestfulAPIDevKtTest`. Kotest bundle is also on the test classpath via `revoman.kt-conventions` if a spec style is preferred, but default to JUnit5+Truth for consistency with ReVoman's own revUp tests.
- **ReVoman API (verified from source, do not re-derive):**
  - Entry: `ReVoman.revUp(kick: Kick): Rundown`; overload `revUp(kicks: List<Kick>, postExeHook, dynamicEnvironment): List<Rundown>` threads the mutable env forward from each run into the next.
  - `Kick.configure().templatePath(String).environmentPath(String)...off()` builds config; V3 templates are passed as a **directory** path resolved off the classpath.
  - `Rundown` fields/props: `stopReason: StopReason` (`@JvmField`), `firstUnIgnoredUnsuccessfulStepReport: StepReport?`, `areAllStepsSuccessful: Boolean`, `mutableEnv: PostmanEnvironment<Any?>` (read via `getAsString(key)` / `get(key)`).
  - `StopReason` enum values: `COMPLETED`, `HALTED_ON_FAILURE`, `LOOP_BUDGET_EXCEEDED` (+ a directive-stop). Happy path = `COMPLETED`.
  - `Rundown.toJson(verbosity: Verbosity = STANDARD): String` is a Kotlin **extension function** in `com.salesforce.revoman.output` (`import com.salesforce.revoman.output.toJson`). `Verbosity.SUMMARY | STANDARD | VERBOSE`.
- **V3 collection format (verified):** a directory with `.resources/definition.yaml` (`$kind: collection` + bearer auth) and one `*.request.yaml` per API (`$kind: http-request`, `url`/`method`/`headers`/`body`, `scripts` with `type: afterResponse`, and an `order:` int for sequencing). Edges are `pm.environment.set("k", v)` in one step + `{{k}}` referenced later. Env file is `*.environment.yaml` (`name:` + `values: [{key, value}]`). Only `bearer` auth is supported in V3.

---

### Task 1: Create the `agentic-harness` Gradle module skeleton

**Files:**
- Modify: `settings.gradle.kts` (add one `include("agentic-harness")` line, after `rootProject.name`)
- Create: `agentic-harness/build.gradle.kts`
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/.gitkeep` (placeholder dir; removed when first source lands)
- Create: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/ModuleWiringTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a buildable module `:agentic-harness` on the classpath of which `com.salesforce.revoman.ReVoman` (from `project(":")`) resolves.

- [ ] **Step 1: Add the module to settings**

In `settings.gradle.kts`, immediately after the line `rootProject.name = "revoman-root"`, add:

```kotlin
include("agentic-harness")
```

- [ ] **Step 2: Write the module build file**

Create `agentic-harness/build.gradle.kts`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins { id("revoman.kt-conventions") }

dependencies {
  // The deterministic execution engine. `project(":")` is the ReVoman library module.
  implementation(project(":"))

  val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
  // Truth for assertions, matching ReVoman's own revUp tests (RestfulAPIDevKtTest).
  testImplementation(libs.findLibrary("truth").get())
}
```

- [ ] **Step 3: Write a wiring test that proves ReVoman resolves from the module**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/ModuleWiringTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import org.junit.jupiter.api.Test

class ModuleWiringTest {
  @Test
  fun `ReVoman engine is on the harness module classpath`() {
    // ReVoman is a Kotlin `object` (singleton); referencing it proves the project dep resolves.
    assertThat(ReVoman.toString()).isNotEmpty()
  }
}
```

- [ ] **Step 4: Run the wiring test — expect PASS**

Run: `./gradlew :agentic-harness:test --tests "com.salesforce.revoman.harness.ModuleWiringTest"`
Expected: BUILD SUCCESSFUL, 1 test passed. (If `include` or the project dep is wrong, compilation fails with "unresolved reference: ReVoman".)

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add settings.gradle.kts agentic-harness/build.gradle.kts \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/ModuleWiringTest.kt
git commit -m "feat(harness): scaffold agentic-harness Gradle module"
```

(The `.gitkeep` under `src/main` is optional; skip it if the main source dir is created in Task 2.)

---

### Task 2: Mock CPQ server (`MockCpqServer`)

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/mock/MockCpqServer.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/mock/MockCpqServerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (uses JDK `com.sun.net.httpserver.HttpServer`).
- Produces:
  - `class MockCpqServer` with:
    - `fun start(): Int` — binds `127.0.0.1:0`, starts, returns the actual bound port.
    - `fun stop()` — stops the server (delay 0).
    - `val db: MutableMap<String, Any?>` — the in-memory "DB" (later tasks/Stage 3 assert on it).
  - Endpoints (all `POST`, JSON in/out):
    - `POST /configure` body `{ "productCode": String, "quantity": Int }` → 200 `{ "configId": "cfg-<n>" }`; records `db["config:<id>"] = productCode+quantity`.
    - `POST /price` body `{ "configId": String }` → 200 `{ "priceId": "prc-<n>", "total": <Double> }` when the configId exists in `db`, else 400 `{ "error": "unknown configId" }`.
    - `POST /quote` body `{ "priceId": String }` → 200 `{ "quoteId": "qot-<n>", "status": "DRAFT" }` when priceId exists, else 400. Records `db["quote:<id>"] = "DRAFT"`.

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/mock/MockCpqServerTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.mock

import com.google.common.truth.Truth.assertThat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MockCpqServerTest {
  private lateinit var server: MockCpqServer
  private var port: Int = 0
  private val http: HttpClient = HttpClient.newHttpClient()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    port = server.start()
  }

  @AfterEach fun tearDown() = server.stop()

  private fun post(path: String, json: String): Pair<Int, String> {
    val resp =
      http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build(),
        BodyHandlers.ofString(),
      )
    return resp.statusCode() to resp.body()
  }

  @Test
  fun `configure then price then quote chain succeeds`() {
    val (cfgStatus, cfgBody) = post("/configure", """{"productCode":"SKU-1","quantity":2}""")
    assertThat(cfgStatus).isEqualTo(200)
    assertThat(cfgBody).contains("configId")

    val configId = Regex(""""configId"\s*:\s*"([^"]+)"""").find(cfgBody)!!.groupValues[1]
    val (prcStatus, prcBody) = post("/price", """{"configId":"$configId"}""")
    assertThat(prcStatus).isEqualTo(200)
    assertThat(prcBody).contains("priceId")
    assertThat(prcBody).contains("total")

    val priceId = Regex(""""priceId"\s*:\s*"([^"]+)"""").find(prcBody)!!.groupValues[1]
    val (qotStatus, qotBody) = post("/quote", """{"priceId":"$priceId"}""")
    assertThat(qotStatus).isEqualTo(200)
    assertThat(qotBody).contains("quoteId")
    assertThat(qotBody).contains("DRAFT")
  }

  @Test
  fun `price with unknown configId is rejected`() {
    val (status, body) = post("/price", """{"configId":"nope"}""")
    assertThat(status).isEqualTo(400)
    assertThat(body).contains("error")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*MockCpqServerTest"`
Expected: FAIL — compilation error "unresolved reference: MockCpqServer".

- [ ] **Step 3: Write minimal implementation**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/mock/MockCpqServer.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local, in-process mock of the three Revenue-Cloud CPQ endpoints the harness graphs execute
 * against — [/configure], [/price], [/quote]. Backed by an in-memory [db] map that stands in for
 * the org's records (Stage 3's tau-bench-style checks assert on this final state). Uses the JDK
 * [HttpServer], the same zero-dependency mock idiom ReVoman's own `ControlFlowE2ETest` uses.
 */
class MockCpqServer {
  val db: MutableMap<String, Any?> = LinkedHashMap()
  private val seq = AtomicInteger(0)
  private lateinit var server: HttpServer

  fun start(): Int {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/configure") { ex -> handleConfigure(ex) }
    server.createContext("/price") { ex -> handlePrice(ex) }
    server.createContext("/quote") { ex -> handleQuote(ex) }
    server.start()
    return server.address.port
  }

  fun stop() = server.stop(0)

  // --- handlers -----------------------------------------------------------------------------

  private fun handleConfigure(ex: HttpExchange) {
    val body = ex.readBody()
    val productCode = body.field("productCode") ?: return ex.respond(400, """{"error":"missing productCode"}""")
    val quantity = body.field("quantity") ?: "1"
    val configId = "cfg-${seq.incrementAndGet()}"
    db["config:$configId"] = "$productCode x$quantity"
    ex.respond(200, """{"configId":"$configId"}""")
  }

  private fun handlePrice(ex: HttpExchange) {
    val body = ex.readBody()
    val configId = body.field("configId")
    if (configId == null || !db.containsKey("config:$configId")) {
      return ex.respond(400, """{"error":"unknown configId"}""")
    }
    val priceId = "prc-${seq.incrementAndGet()}"
    val total = 100.0 // deterministic stub price
    db["price:$priceId"] = total
    ex.respond(200, """{"priceId":"$priceId","total":$total}""")
  }

  private fun handleQuote(ex: HttpExchange) {
    val body = ex.readBody()
    val priceId = body.field("priceId")
    if (priceId == null || !db.containsKey("price:$priceId")) {
      return ex.respond(400, """{"error":"unknown priceId"}""")
    }
    val quoteId = "qot-${seq.incrementAndGet()}"
    db["quote:$quoteId"] = "DRAFT"
    ex.respond(200, """{"quoteId":"$quoteId","status":"DRAFT"}""")
  }

  // --- tiny JSON helpers (no dep; naive on purpose — bodies are flat + trusted) --------------

  private fun HttpExchange.readBody(): String = requestBody.readBytes().decodeToString()

  /** Extracts a flat JSON string/number field value by key, or null. */
  private fun String.field(key: String): String? =
    Regex(""""$key"\s*:\s*"?([^",}\s]+)"?""").find(this)?.groupValues?.get(1)

  private fun HttpExchange.respond(status: Int, json: String) {
    val bytes = json.encodeToByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agentic-harness:test --tests "*MockCpqServerTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/mock/MockCpqServer.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/mock/MockCpqServerTest.kt
git commit -m "feat(harness): in-memory mock CPQ server (configure/price/quote)"
```

---

### Task 3: Author the three V3 graph collections

**Files (all under `agentic-harness/src/main/resources/graphs/`):**
- Create: `configure/.resources/definition.yaml`
- Create: `configure/configure-product.request.yaml`
- Create: `configure/configure.environment.yaml`
- Create: `price/.resources/definition.yaml`
- Create: `price/price-config.request.yaml`
- Create: `price/price.environment.yaml`
- Create: `quote/.resources/definition.yaml`
- Create: `quote/quote-price.request.yaml`
- Create: `quote/quote.environment.yaml`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/graph/GraphResourcesTest.kt`

**Interfaces:**
- Consumes: the mock server's endpoint contracts from Task 2.
- Produces: three classpath-resolvable V3 collection directories. Env edge keys (later tasks depend on these exact names): `configId` (set by configure), `priceId` (set by price), `quoteId` (set by quote). Each graph reads `{{baseUrl}}` from its environment.

- [ ] **Step 1: Write the failing test (resources exist + parse)**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/graph/GraphResourcesTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.graph

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphResourcesTest {
  @Test
  fun `each graph directory has a V3 definition on the classpath`() {
    listOf("configure", "price", "quote").forEach { graph ->
      val def = javaClass.classLoader.getResource("graphs/$graph/.resources/definition.yaml")
      assertThat(def).isNotNull()
    }
  }

  @Test
  fun `price request references the configId placeholder threaded from configure`() {
    val priceReq =
      javaClass.classLoader.getResource("graphs/price/price-config.request.yaml")!!.readText()
    assertThat(priceReq).contains("{{configId}}")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*GraphResourcesTest"`
Expected: FAIL — `def` is null / resource not found.

- [ ] **Step 3: Create the `configure` graph**

`agentic-harness/src/main/resources/graphs/configure/.resources/definition.yaml`:

```yaml
$kind: collection
description: "Configure a product: turn a product code + quantity into a saved configuration."
auth:
  - id: cfg-auth
    type: bearer
    name: bearer auth
    credentials:
      token: "{{accessToken}}"
```

`agentic-harness/src/main/resources/graphs/configure/configure-product.request.yaml`:

```yaml
$kind: http-request
url: "{{baseUrl}}/configure"
method: POST
headers:
  Content-Type: application/json
body:
  type: text
  content: |-
    {"productCode":"{{productCode}}","quantity":{{quantity}}}
scripts:
  - type: afterResponse
    code: |-
      var res = pm.response.json()
      pm.environment.set("configId", res.configId)
    language: text/javascript
order: 1000
```

`agentic-harness/src/main/resources/graphs/configure/configure.environment.yaml`:

```yaml
name: configure
values:
  - key: baseUrl
    value: "http://127.0.0.1:0"
  - key: accessToken
    value: "local-dev-token"
  - key: productCode
    value: "SKU-1"
  - key: quantity
    value: "1"
```

- [ ] **Step 4: Create the `price` graph**

`agentic-harness/src/main/resources/graphs/price/.resources/definition.yaml`:

```yaml
$kind: collection
description: "Price a saved configuration: compute the total for a configId."
auth:
  - id: prc-auth
    type: bearer
    name: bearer auth
    credentials:
      token: "{{accessToken}}"
```

`agentic-harness/src/main/resources/graphs/price/price-config.request.yaml`:

```yaml
$kind: http-request
url: "{{baseUrl}}/price"
method: POST
headers:
  Content-Type: application/json
body:
  type: text
  content: |-
    {"configId":"{{configId}}"}
scripts:
  - type: afterResponse
    code: |-
      var res = pm.response.json()
      pm.environment.set("priceId", res.priceId)
      pm.environment.set("total", res.total)
    language: text/javascript
order: 1000
```

`agentic-harness/src/main/resources/graphs/price/price.environment.yaml`:

```yaml
name: price
values:
  - key: baseUrl
    value: "http://127.0.0.1:0"
  - key: accessToken
    value: "local-dev-token"
  - key: configId
    value: ""
```

- [ ] **Step 5: Create the `quote` graph**

`agentic-harness/src/main/resources/graphs/quote/.resources/definition.yaml`:

```yaml
$kind: collection
description: "Quote a priced configuration: create a draft quote from a priceId."
auth:
  - id: qot-auth
    type: bearer
    name: bearer auth
    credentials:
      token: "{{accessToken}}"
```

`agentic-harness/src/main/resources/graphs/quote/quote-price.request.yaml`:

```yaml
$kind: http-request
url: "{{baseUrl}}/quote"
method: POST
headers:
  Content-Type: application/json
body:
  type: text
  content: |-
    {"priceId":"{{priceId}}"}
scripts:
  - type: afterResponse
    code: |-
      var res = pm.response.json()
      pm.environment.set("quoteId", res.quoteId)
      pm.environment.set("quoteStatus", res.status)
    language: text/javascript
order: 1000
```

`agentic-harness/src/main/resources/graphs/quote/quote.environment.yaml`:

```yaml
name: quote
values:
  - key: baseUrl
    value: "http://127.0.0.1:0"
  - key: accessToken
    value: "local-dev-token"
  - key: priceId
    value: ""
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*GraphResourcesTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 7: Commit** (no Kotlin changed, so spotless is a no-op but run it anyway)

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/resources/graphs \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/graph/GraphResourcesTest.kt
git commit -m "feat(harness): V3 graph collections configure/price/quote (var-threaded)"
```

---

### Task 4: `GraphRunner` — run the chain via ReVoman and thread env forward

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/GraphRunner.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/GraphRunnerTest.kt`

**Interfaces:**
- Consumes: `MockCpqServer` (Task 2) for the live `baseUrl`; the V3 resources under `graphs/` (Task 3).
- Produces:
  - `object GraphRunner` (or `class`) with:
    - `fun runChain(baseUrl: String, graphs: List<String> = listOf("configure", "price", "quote"), seedEnv: Map<String, Any?> = emptyMap()): List<Rundown>` — builds one `Kick` per graph via `Kick.configure().templatePath("graphs/<g>").environmentPath("graphs/<g>/<g>.environment.yaml").dynamicEnvironment(mapOf("baseUrl" to baseUrl) + seedEnv).off()` and calls `ReVoman.revUp(kicks, ...)` so env threads forward.
    - `fun runChainAndSummarize(baseUrl: String, ...): String` — runs the chain and returns the concatenated `toJson(Verbosity.SUMMARY)` of each Rundown.
  - Note: `dynamicEnvironment` overrides the `baseUrl` placeholder (env file ships `http://127.0.0.1:0`, replaced at runtime with the real bound port). The `List<Kick>` overload threads `configId`/`priceId` forward automatically (verified: `revUp(List<Kick>)` folds `mutableEnv.immutableEnv` into the next kick).

- [ ] **Step 1: Write the failing test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/GraphRunnerTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.mock.MockCpqServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphRunnerTest {
  private lateinit var server: MockCpqServer
  private var baseUrl: String = ""

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `configure-price-quote chain threads ids forward and lands a draft quote`() {
    val rundowns = GraphRunner.runChain(baseUrl)

    // Three graphs ran, each with no failing step.
    assertThat(rundowns).hasSize(3)
    rundowns.forEach { assertThat(it.firstUnIgnoredUnsuccessfulStepReport).isNull() }

    // The final env carries the threaded ids — proof {{var}} edges connected the graphs.
    val finalEnv = rundowns.last().mutableEnv
    assertThat(finalEnv.getAsString("configId")).startsWith("cfg-")
    assertThat(finalEnv.getAsString("priceId")).startsWith("prc-")
    assertThat(finalEnv.getAsString("quoteId")).startsWith("qot-")

    // The mock "DB" recorded a DRAFT quote (tau-bench-style state proof, previewed here).
    assertThat(server.db.values).contains("DRAFT")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*GraphRunnerTest"`
Expected: FAIL — "unresolved reference: GraphRunner".

- [ ] **Step 3: Write minimal implementation**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/GraphRunner.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson

/**
 * The deterministic worker: runs one or more ReVoman V3 graph collections in order, threading the
 * mutable environment forward from each graph into the next (this is the {{var}} edge mechanism —
 * no LLM, no @{ref.id} operator). Wraps `ReVoman.revUp(List<Kick>)`.
 */
object GraphRunner {
  val DEFAULT_CHAIN: List<String> = listOf("configure", "price", "quote")

  fun runChain(
    baseUrl: String,
    graphs: List<String> = DEFAULT_CHAIN,
    seedEnv: Map<String, Any?> = emptyMap(),
  ): List<Rundown> {
    val runtimeEnv: Map<String, Any?> = mapOf("baseUrl" to baseUrl) + seedEnv
    val kicks =
      graphs.map { graph ->
        Kick.configure()
          .templatePath("graphs/$graph")
          .environmentPath("graphs/$graph/$graph.environment.yaml")
          .dynamicEnvironment(runtimeEnv)
          .off()
      }
    return ReVoman.revUp(kicks)
  }

  fun runChainAndSummarize(
    baseUrl: String,
    graphs: List<String> = DEFAULT_CHAIN,
    seedEnv: Map<String, Any?> = emptyMap(),
  ): String =
    runChain(baseUrl, graphs, seedEnv).joinToString("\n") { it.toJson(Verbosity.SUMMARY) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agentic-harness:test --tests "*GraphRunnerTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

If it fails on `dynamicEnvironment` not overriding `baseUrl`: the V3 env file value (`http://127.0.0.1:0`) is a fallback; `dynamicEnvironment(...)` merges at runtime and takes precedence over the environment file. Confirm the env key name is exactly `baseUrl`. If the `{{quantity}}` numeric placeholder produces invalid JSON (quoted), change the configure body to `"quantity":{{quantity}}` (already unquoted above) and ensure the env ships `quantity` as `"1"`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/GraphRunner.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/GraphRunnerTest.kt
git commit -m "feat(harness): GraphRunner runs V3 graph chain via ReVoman.revUp"
```

---

### Task 5: Layer-1 contract test + runnable `main()` demo

**Files:**
- Create: `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage1Demo.kt`
- Test: `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/Layer1ContractTest.kt`
- Modify: `agentic-harness/build.gradle.kts` (add an `application`-style run task for the demo `main`)

**Interfaces:**
- Consumes: `MockCpqServer` (Task 2), `GraphRunner` (Task 4).
- Produces:
  - `fun main()` in `Stage1Demo.kt` — boots the mock, runs the chain, prints each Rundown's SUMMARY json, stops the mock.
  - A gradle task `:agentic-harness:runStage1Demo` (type `JavaExec`) that runs `Stage1DemoKt`.

- [ ] **Step 1: Write the failing Layer-1 contract test**

Create `agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/Layer1ContractTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.output.StopReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The design's "evals Layer 1" beat: deterministic API-graph contract tests, no LLM. Runs the
 * graph chain against the mock and asserts on the Rundown — the exact same engine that will run
 * graphs at agent runtime.
 */
class Layer1ContractTest {
  private lateinit var server: MockCpqServer
  private var baseUrl: String = ""

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `every graph in the chain completes with no unsuccessful step`() {
    val rundowns = GraphRunner.runChain(baseUrl)
    rundowns.forEach { rundown ->
      assertThat(rundown.areAllStepsSuccessful).isTrue()
      assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport).isNull()
      assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agentic-harness:test --tests "*Layer1ContractTest"`
Expected: FAIL — compiles (GraphRunner exists) but this is the first run asserting `stopReason`; if any graph mis-chains it fails on `areAllStepsSuccessful`. If it passes immediately because Task 4 already made the chain green, that is acceptable — this task's deliverable is the contract assertion + demo, not new production code. Proceed.

- [ ] **Step 3: Write the demo `main()`**

Create `agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage1Demo.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.mock.MockCpqServer

/**
 * Stage 1 runnable demo: boot the mock CPQ server, run the configure->price->quote graph chain
 * through ReVoman, and print each Rundown summary. This is the deterministic worker, end to end,
 * with no LLM involved.
 */
fun main() {
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    println("Mock CPQ server up at $baseUrl")
    println(GraphRunner.runChainAndSummarize(baseUrl))
    println("Final mock DB state: ${server.db}")
  } finally {
    server.stop()
  }
}
```

- [ ] **Step 4: Add the run task to the module build file**

Append to `agentic-harness/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runStage1Demo") {
  group = "harness"
  description = "Boot the mock CPQ server and run the configure->price->quote graph chain"
  mainClass.set("com.salesforce.revoman.harness.Stage1DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 5: Run the contract test and the demo**

Run: `./gradlew :agentic-harness:test --tests "*Layer1ContractTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

Run: `./gradlew :agentic-harness:runStage1Demo -q`
Expected output (ids will vary): a line `Mock CPQ server up at http://127.0.0.1:<port>`, three JSON summary blocks each showing success, and `Final mock DB state: {config:cfg-1=SKU-1 x1, price:prc-2=100.0, quote:qot-3=DRAFT}`.

- [ ] **Step 6: Run the whole module suite to confirm Stage 1 is green end to end**

Run: `./gradlew :agentic-harness:test`
Expected: BUILD SUCCESSFUL — `ModuleWiringTest`, `MockCpqServerTest`, `GraphResourcesTest`, `GraphRunnerTest`, `Layer1ContractTest` all pass.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add agentic-harness/src/main/kotlin/com/salesforce/revoman/harness/Stage1Demo.kt \
  agentic-harness/src/test/kotlin/com/salesforce/revoman/harness/Layer1ContractTest.kt \
  agentic-harness/build.gradle.kts
git commit -m "feat(harness): Layer-1 contract test + runnable Stage 1 demo"
```

---

## Self-Review

**Spec coverage (Stage 1 rows of the spec's concept-to-component map):**
- Deterministic worker (`MockCpqServer` + V3 graphs chaining via `{{var}}`) → Tasks 2, 3. ✓
- `revUp(Kick): Rundown` (`GraphRunner`) → Task 4. ✓
- Evals Layer 1 (contract test on `Rundown`) → Task 5. ✓
- Module isolation (new submodule, library untouched) → Task 1. ✓
- Runnable proof (`main()` + green suite) → Task 5 Steps 5–6. ✓
- Stages 2–4 are explicitly out of scope for this plan (each gets its own plan so it stays independently runnable, per the spec's staging).

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every code step shows complete code. ✓

**Type consistency:** `MockCpqServer.start(): Int`, `.stop()`, `.db` used identically in Tasks 2, 4, 5. `GraphRunner.runChain(baseUrl, graphs, seedEnv): List<Rundown>` and `runChainAndSummarize(...)` used identically in Tasks 4, 5. Env edge keys `configId`/`priceId`/`quoteId` set in Task 3 YAML and asserted in Tasks 4, 5. `StopReason.COMPLETED`, `Rundown.areAllStepsSuccessful`, `firstUnIgnoredUnsuccessfulStepReport`, `mutableEnv.getAsString(...)`, `toJson(Verbosity.SUMMARY)` all match verified library API. ✓

**Known risk flagged in-plan (Task 4 Step 4):** ReVoman `dynamicEnvironment` precedence over the V3 env-file `baseUrl`, and numeric-placeholder JSON validity. Both have inline remedies. If `dynamicEnvironment` does not override an env-file key, the fallback is to omit `baseUrl` from the env YAML entirely so the only source is `dynamicEnvironment` — the implementer should apply that if the Task 4 test fails on a connection error.

# Postman Collection v3 Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add support for loading Postman collection schema v3 (directory tree of YAML files) to ReVoman, by converting v3 input to the existing v2 internal model at load time so the rest of the pipeline is untouched.

**Architecture:** New internal package `internal.postman.template.v3` containing `V3Loader`, `V3Model`, `V3ToV2Converter`, `V3EnvLoader`. Input dispatching happens in `ReVoman.revUp` (collections) and `Environment.mergeEnvs` (envs). Detection: directory with `.resources/definition.yaml` → v3; `.json` → v2; `.yaml`/`.yml` env file → v3 env; `.json` env file → v2 env. SnakeYAML produces `Map<String, Any?>`; loader hand-walks the map into typed Kotlin classes; converter maps to v2 `Item`/`Request`/`Auth`/`Event`.

**Tech Stack:** Kotlin 2.3.21, JDK 21, http4k, Moshi (existing), SnakeYAML 2.x (new), JUnit 5 + AssertJ + Truth, kotlin-logging, Gradle 8+.

**Spec:** `docs/superpowers/specs/2026-05-18-postman-v3-support-design.md`

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Model.kt` | Typed data classes mirroring v3 YAML schema (`V3CollectionDef`, `V3Request`, `V3Body`, `V3Script`, `V3Auth`, `V3Settings`, `V3Env`, `V3EnvValue`) |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt` | SnakeYAML invocation + `Map<String, Any?>` → typed v3 model conversion. Single place where SnakeYAML is touched. |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt` | Pure functions: `V3Request → v2 Item`, `V3Auth → v2 Auth`, `V3Script → v2 Event`, queryParam union into URL |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt` | Walks a v3 collection directory; produces `List<Item>` matching the v2 Moshi adapter shape |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt` | Loads a v3 `*.environment.yaml` to `Map<String, Any?>` |

### Modified files

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | Add `snakeyaml` version + library entry |
| `build.gradle.kts` | Add `implementation(libs.snakeyaml)` |
| `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` | Replace direct `pmTemplateAdapter.fromJson` with version-dispatching helper |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` | Branch on extension between v2 JSON and v3 YAML env loaders |

### Test files (new)

| File | Responsibility |
|---|---|
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt` | YAML → typed v3 model parsing; YAML 1.1 boolean coercion |
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt` | Conversion mappings: headers, scripts, body, auth, queryParams, name override |
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt` | Directory walk, ordering, folder-auth inheritance, empty dirs |
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoaderTest.kt` | YAML env → Map |
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3DetectionTest.kt` | `ReVoman.revUp` path routing; error messages on bad inputs |
| `src/test/resources/pm-templates/v3/flat/.resources/definition.yaml` | Fixture |
| `src/test/resources/pm-templates/v3/flat/<a/b/c>.request.yaml` | Fixtures (3 requests, ordered) |
| `src/test/resources/pm-templates/v3/nested/...` | Nested fixture with auth inheritance + override |
| `src/test/resources/pm-templates/v3/mixed-bodies/...` | POST/PATCH with json + text bodies, multi-script |
| `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonKtTest.kt` | Kotlin v3 smoke for Pokemon |
| `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV3Test.java` | Java v3 smoke for Pokemon (API friendliness gate) |
| `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonV2VsV3EquivalenceKtTest.kt` | Same logical collection in v2 + v3 → identical Rundown (Kotlin) |
| `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV2VsV3EquivalenceTest.java` | Same logical collection in v2 + v3 → identical Rundown (Java) |
| `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevKtTest.kt` | Kotlin v3 smoke |
| `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevV3Test.java` | Java v3 smoke |
| `src/integrationTest/kotlin/com/salesforce/revoman/integration/apigee/v3/ApigeeKtTest.kt` | Kotlin v3 smoke |

---

## Conventions used in this plan

- **Kotest matcher style:** existing tests use JUnit 5 + Truth + AssertJ. Stick with **Truth** (`assertThat(...)`) for new unit tests for consistency.
- **Test method naming:** existing Kotlin unit tests use backticked descriptive names (`fun \`description\`()`). Existing project AGENTS.md style guide says `testXxx` without backticks for readability. Existing `PokemonTest.java` uses single descriptive names. **Use `testXxx` form** per project style guide.
- **Logging:** `private val logger = KotlinLogging.logger {}` at file end, matching existing convention.
- **Commits:** small, frequent. Stage only files mentioned in the step.

---

## Task 1: Add SnakeYAML dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add SnakeYAML version to version catalog**

Edit `gradle/libs.versions.toml`. Under `[versions]` (alongside `okio = "3.17.0"`), add:

```toml
snakeyaml = "2.5"
```

Under `[libraries]`, add:

```toml
snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }
```

- [ ] **Step 2: Add dependency in build.gradle.kts**

Edit `build.gradle.kts`. In the `dependencies { ... }` block, add a new line in alphabetical order among the `implementation(...)` lines (e.g., after `implementation(libs.spring.beans)`):

```kotlin
implementation(libs.snakeyaml)
```

- [ ] **Step 3: Verify resolution**

Run: `./gradlew dependencies --configuration runtimeClasspath | grep -i snakeyaml`
Expected: a line containing `org.yaml:snakeyaml:2.5` (or whatever version was set).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: add SnakeYAML dependency for Postman v3 support"
```

---

## Task 2: V3 model — typed data classes

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Model.kt`

- [ ] **Step 1: Create the model file**

Create `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Model.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

internal data class V3CollectionDef(
  val kind: String = "collection",
  val order: Int? = null,
  val auth: List<V3Auth> = emptyList(),
)

internal data class V3Request(
  val kind: String = "http-request",
  val name: String? = null,
  val description: String? = null,
  val url: String,
  val method: String,
  val headers: Map<String, String> = emptyMap(),
  val queryParams: Map<String, String> = emptyMap(),
  val body: V3Body? = null,
  val scripts: List<V3Script> = emptyList(),
  val auth: List<V3Auth> = emptyList(),
  val settings: V3Settings? = null,
  val order: Int? = null,
)

internal data class V3Body(val type: String, val content: String)

internal data class V3Script(val type: String, val code: String, val language: String? = null)

internal data class V3Auth(
  val id: String? = null,
  val type: String,
  val name: String? = null,
  val credentials: Map<String, String> = emptyMap(),
)

internal data class V3Settings(val disabledSystemHeaders: List<String> = emptyList())

internal data class V3Env(val name: String?, val values: List<V3EnvValue> = emptyList())

internal data class V3EnvValue(val key: String, val value: String?)
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Model.kt
git commit -m "feat(v3): add typed data classes for v3 collection schema"
```

---

## Task 3: V3 YAML reader — write failing test for collection def

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V3YamlReaderTest {
  @Test
  fun testReadCollectionDefWithKindOnly() {
    val yaml = """
      ${'$'}kind: collection
    """.trimIndent()
    val def = V3YamlReader.readCollectionDef(yaml)
    assertThat(def.kind).isEqualTo("collection")
    assertThat(def.order).isNull()
    assertThat(def.auth).isEmpty()
  }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest.testReadCollectionDefWithKindOnly" -i`
Expected: FAIL with "unresolved reference: V3YamlReader" (or similar — the class doesn't exist yet).

- [ ] **Step 3: Create minimal V3YamlReader to make test pass**

Create `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml

internal object V3YamlReader {
  fun readCollectionDef(yaml: String): V3CollectionDef {
    val map = parseYaml(yaml)
    return mapToCollectionDef(map)
  }

  private fun parseYaml(yaml: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return (Yaml().load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
  }

  private fun mapToCollectionDef(map: Map<String, Any?>): V3CollectionDef =
    V3CollectionDef(
      kind = strOrDefault(map["\$kind"], "collection"),
      order = (map["order"] as? Number)?.toInt(),
      auth = mapToAuthList(map["auth"]),
    )

  private fun mapToAuthList(value: Any?): List<V3Auth> {
    @Suppress("UNCHECKED_CAST")
    val list = value as? List<Map<String, Any?>> ?: return emptyList()
    return list.map { m ->
      @Suppress("UNCHECKED_CAST")
      val credentials = (m["credentials"] as? Map<String, Any?>) ?: emptyMap()
      V3Auth(
        id = m["id"]?.toString(),
        type = m["type"]?.toString() ?: error("Auth entry missing 'type'"),
        name = m["name"]?.toString(),
        credentials = credentials.mapValues { (_, v) -> v?.toString() ?: "" },
      )
    }
  }

  private fun strOrDefault(value: Any?, default: String): String = value?.toString() ?: default
}

private val logger = KotlinLogging.logger {}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest.testReadCollectionDefWithKindOnly" -i`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt
git commit -m "feat(v3): add V3YamlReader.readCollectionDef for collection definition files"
```

---

## Task 4: V3 YAML reader — collection def with auth

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt`
- Modify: (likely no source change needed — `readCollectionDef` already handles auth list)

- [ ] **Step 1: Add a failing test for auth + order**

In `V3YamlReaderTest.kt`, add a method:

```kotlin
@Test
fun testReadCollectionDefWithAuthAndOrder() {
  val yaml = """
    ${'$'}kind: collection
    order: 1000
    auth:
      - id: 88daae21-effd-4cd0-b24a-65bc7a382e35
        type: bearer
        name: bearer auth
        credentials:
          token: "{{accessToken}}"
  """.trimIndent()
  val def = V3YamlReader.readCollectionDef(yaml)
  assertThat(def.kind).isEqualTo("collection")
  assertThat(def.order).isEqualTo(1000)
  assertThat(def.auth).hasSize(1)
  val auth = def.auth.single()
  assertThat(auth.id).isEqualTo("88daae21-effd-4cd0-b24a-65bc7a382e35")
  assertThat(auth.type).isEqualTo("bearer")
  assertThat(auth.name).isEqualTo("bearer auth")
  assertThat(auth.credentials).containsEntry("token", "{{accessToken}}")
}
```

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest" -i`
Expected: both tests PASS (Task 3's reader already handles auth — this test is a regression guard / acceptance check).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt
git commit -m "test(v3): cover collection def with auth list and order"
```

---

## Task 5: V3 YAML reader — request file parsing (basic GET)

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt`

- [ ] **Step 1: Write failing test**

Append to `V3YamlReaderTest.kt`:

```kotlin
@Test
fun testReadRequestBasicGet() {
  val yaml = """
    ${'$'}kind: http-request
    url: "{{baseUrl}}/nature/{{id}}"
    method: GET
    headers:
      preLog: "true"
    order: 3000
  """.trimIndent()
  val req = V3YamlReader.readRequest(yaml)
  assertThat(req.kind).isEqualTo("http-request")
  assertThat(req.url).isEqualTo("{{baseUrl}}/nature/{{id}}")
  assertThat(req.method).isEqualTo("GET")
  assertThat(req.headers).containsEntry("preLog", "true")
  assertThat(req.order).isEqualTo(3000)
  assertThat(req.body).isNull()
  assertThat(req.scripts).isEmpty()
  assertThat(req.auth).isEmpty()
}
```

- [ ] **Step 2: Run, expect FAIL on missing `readRequest`**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest.testReadRequestBasicGet" -i`
Expected: FAIL with "unresolved reference: readRequest".

- [ ] **Step 3: Add `readRequest` to V3YamlReader**

In `V3YamlReader.kt`, add inside the `object`:

```kotlin
fun readRequest(yaml: String): V3Request {
  val map = parseYaml(yaml)
  return mapToRequest(map)
}

private fun mapToRequest(map: Map<String, Any?>): V3Request =
  V3Request(
    kind = strOrDefault(map["\$kind"], "http-request"),
    name = map["name"]?.toString(),
    description = map["description"]?.toString(),
    url = map["url"]?.toString() ?: error("v3 request missing required field: url"),
    method = map["method"]?.toString() ?: error("v3 request missing required field: method"),
    headers = strMap(map["headers"]),
    queryParams = strMap(map["queryParams"]),
    body = mapToBody(map["body"]),
    scripts = mapToScripts(map["scripts"]),
    auth = mapToAuthList(map["auth"]),
    settings = mapToSettings(map["settings"]),
    order = (map["order"] as? Number)?.toInt(),
  )

private fun strMap(value: Any?): Map<String, String> {
  @Suppress("UNCHECKED_CAST")
  val m = value as? Map<String, Any?> ?: return emptyMap()
  return m.entries.associate { (k, v) -> k to (v?.toString() ?: "") }
}

private fun mapToBody(value: Any?): V3Body? {
  @Suppress("UNCHECKED_CAST")
  val m = value as? Map<String, Any?> ?: return null
  val type = m["type"]?.toString() ?: return null
  val content = m["content"]?.toString() ?: ""
  return V3Body(type = type, content = content)
}

private fun mapToScripts(value: Any?): List<V3Script> {
  @Suppress("UNCHECKED_CAST")
  val list = value as? List<Map<String, Any?>> ?: return emptyList()
  return list.map { m ->
    V3Script(
      type = m["type"]?.toString() ?: error("v3 script missing 'type'"),
      code = m["code"]?.toString() ?: "",
      language = m["language"]?.toString(),
    )
  }
}

private fun mapToSettings(value: Any?): V3Settings? {
  @Suppress("UNCHECKED_CAST")
  val m = value as? Map<String, Any?> ?: return null
  @Suppress("UNCHECKED_CAST")
  val disabled = (m["disabledSystemHeaders"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
  return V3Settings(disabledSystemHeaders = disabled)
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest.testReadRequestBasicGet" -i`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt
git commit -m "feat(v3): parse v3 *.request.yaml into typed V3Request"
```

---

## Task 6: V3 YAML reader — POST with body and multi-script

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt`

- [ ] **Step 1: Add failing test**

Append:

```kotlin
@Test
fun testReadRequestPostWithBodyAndMultiScript() {
  val yaml = """
    ${'$'}kind: http-request
    url: https://{{uri}}/objects
    method: POST
    body:
      type: json
      content: |-
        {
          "name": "x"
        }
    scripts:
      - type: afterResponse
        code: |-
          var responseJson = pm.response.json();
        language: text/javascript
      - type: beforeRequest
        code: |-
          var moment = require('moment')
        language: text/javascript
    order: 2000
  """.trimIndent()
  val req = V3YamlReader.readRequest(yaml)
  assertThat(req.method).isEqualTo("POST")
  assertThat(req.body).isNotNull()
  assertThat(req.body!!.type).isEqualTo("json")
  assertThat(req.body!!.content).contains("\"name\": \"x\"")
  assertThat(req.scripts).hasSize(2)
  assertThat(req.scripts[0].type).isEqualTo("afterResponse")
  assertThat(req.scripts[1].type).isEqualTo("beforeRequest")
}
```

- [ ] **Step 2: Run, expect PASS** (already covered by Task 5 implementation)

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest" -i`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt
git commit -m "test(v3): cover POST with body and multi-script request"
```

---

## Task 7: V3 YAML reader — YAML 1.1 boolean coercion

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt`

- [ ] **Step 1: Add regression test**

Append:

```kotlin
@Test
fun testYaml11BooleansCoercedToString() {
  val yaml = """
    ${'$'}kind: http-request
    url: "{{baseUrl}}/x"
    method: GET
    headers:
      preLog: yes
      onFlag: on
      offFlag: off
  """.trimIndent()
  val req = V3YamlReader.readRequest(yaml)
  assertThat(req.headers["preLog"]).isEqualTo("true")
  assertThat(req.headers["onFlag"]).isEqualTo("true")
  assertThat(req.headers["offFlag"]).isEqualTo("false")
}
```

Note: SnakeYAML 2.x uses YAML 1.1 by default; `yes`/`on` parse to `true`, `no`/`off` to `false`. Our `strMap` calls `toString()` which gives `"true"`/`"false"`. The contract is: header values are always strings, even if YAML coerced them.

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest.testYaml11BooleansCoercedToString" -i`
Expected: PASS.

- [ ] **Step 3: Add env reader test (boolean coercion in env values)**

Append to `V3YamlReaderTest.kt`:

```kotlin
@Test
fun testReadEnv() {
  val yaml = """
    name: Pokemon
    values:
      - key: baseUrl
        value: 'https://pokeapi.co/api/v2'
      - key: enabled
        value: yes
  """.trimIndent()
  val env = V3YamlReader.readEnv(yaml)
  assertThat(env.name).isEqualTo("Pokemon")
  assertThat(env.values).hasSize(2)
  assertThat(env.values[0].key).isEqualTo("baseUrl")
  assertThat(env.values[0].value).isEqualTo("https://pokeapi.co/api/v2")
  assertThat(env.values[1].value).isEqualTo("true")
}
```

- [ ] **Step 4: Run, expect FAIL on missing `readEnv`**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest.testReadEnv" -i`
Expected: FAIL with "unresolved reference: readEnv".

- [ ] **Step 5: Add `readEnv` to V3YamlReader**

In `V3YamlReader.kt`, add inside the `object`:

```kotlin
fun readEnv(yaml: String): V3Env {
  val map = parseYaml(yaml)
  @Suppress("UNCHECKED_CAST")
  val values = (map["values"] as? List<Map<String, Any?>>) ?: emptyList()
  return V3Env(
    name = map["name"]?.toString(),
    values = values.map { m ->
      V3EnvValue(
        key = m["key"]?.toString() ?: error("v3 env value missing 'key'"),
        value = m["value"]?.toString(),
      )
    },
  )
}
```

- [ ] **Step 6: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest" -i`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt
git commit -m "feat(v3): add env reader and YAML 1.1 boolean coercion regression tests"
```

---

## Task 8: V3-to-V2 converter — request basic GET

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V3ToV2ConverterTest {
  @Test
  fun testConvertBasicGetRequestUsesFilenameWhenNameAbsent() {
    val v3 = V3Request(
      url = "{{baseUrl}}/x",
      method = "GET",
    )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.name).isEqualTo("x")
    assertThat(item.request.method).isEqualTo("GET")
    assertThat(item.request.url.raw).isEqualTo("{{baseUrl}}/x")
    assertThat(item.request.header).isEmpty()
    assertThat(item.request.body).isNull()
    assertThat(item.request.auth).isNull()
    assertThat(item.event).isNull()
  }

  @Test
  fun testConvertRequestNameOverridesFilename() {
    val v3 = V3Request(
      name = "explicit-name",
      url = "{{baseUrl}}/x",
      method = "GET",
    )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "filename-fallback", inheritedAuth = null)
    assertThat(item.name).isEqualTo("explicit-name")
  }
}
```

- [ ] **Step 2: Run, expect FAIL on missing `V3ToV2Converter`**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3ToV2ConverterTest" -i`
Expected: FAIL with "unresolved reference: V3ToV2Converter".

- [ ] **Step 3: Create the converter**

Create `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import io.github.oshai.kotlinlogging.KotlinLogging

internal object V3ToV2Converter {
  fun toItem(v3: V3Request, fallbackName: String, inheritedAuth: Auth?): Item {
    val effectiveAuth = if (v3.auth.isNotEmpty()) toAuth(v3.auth) else inheritedAuth
    return Item(
      name = v3.name ?: fallbackName,
      item = null,
      request = toRequest(v3, effectiveAuth),
      event = toEvents(v3.scripts).takeIf { it.isNotEmpty() },
    )
  }

  fun toAuth(authList: List<V3Auth>): Auth? {
    val first = authList.firstOrNull() ?: return null
    if (first.type != "bearer") {
      logger.warn { "v3 auth type '${first.type}' not supported; dropping. Only 'bearer' is supported." }
      return null
    }
    val token = first.credentials["token"] ?: ""
    return Auth(
      type = "bearer",
      bearer = listOf(
        Auth.Bearer(
          key = first.name ?: "token",
          type = "bearer",
          value = token,
        )
      ),
    )
  }

  private fun toRequest(v3: V3Request, auth: Auth?): Request =
    Request(
      auth = auth,
      method = v3.method,
      header = v3.headers.entries.map { (k, v) -> Header(key = k, value = v) },
      url = Url(raw = mergeQueryParams(v3.url, v3.queryParams)),
      body = toBody(v3.body),
      event = null,
    )

  internal fun mergeQueryParams(url: String, queryParams: Map<String, String>): String {
    if (queryParams.isEmpty()) return url
    val separator = if (url.contains("?")) "&" else "?"
    val extra = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
    return "$url$separator$extra"
  }

  private fun toBody(v3body: V3Body?): Body? {
    if (v3body == null) return null
    return Body(mode = "raw", raw = v3body.content)
  }

  private fun toEvents(scripts: List<V3Script>): List<Event> {
    val byListen = scripts.groupBy { script ->
      when (script.type) {
        "afterResponse" -> "test"
        "beforeRequest", "prerequest" -> "prerequest"
        else -> {
          logger.warn { "v3 script type '${script.type}' not recognized; skipping." }
          null
        }
      }
    }
    return byListen.entries
      .filter { it.key != null }
      .map { (listen, scripts) ->
        val combinedExec = scripts.flatMap { it.code.lines() }
        Event(listen = listen!!, script = Event.Script(exec = combinedExec))
      }
  }
}

private val logger = KotlinLogging.logger {}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3ToV2ConverterTest" -i`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt
git commit -m "feat(v3): add V3ToV2Converter with name override and basic request mapping"
```

---

## Task 9: Converter — headers and queryParams union

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt`

- [ ] **Step 1: Add tests**

Append:

```kotlin
@Test
fun testConvertHeadersPreserveInsertionOrder() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "GET",
    headers = linkedMapOf("a" to "1", "b" to "2", "c" to "3"),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  val keys = item.request.header.map { it.key }
  assertThat(keys).containsExactly("a", "b", "c").inOrder()
}

@Test
fun testQueryParamsAppendedToUrlWhenUrlHasNoQueryString() {
  val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", linkedMapOf("foo" to "1", "bar" to "2"))
  assertThat(merged).isEqualTo("{{baseUrl}}/x?foo=1&bar=2")
}

@Test
fun testQueryParamsAppendedAfterExistingQueryString() {
  val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x?already=here", linkedMapOf("foo" to "1"))
  assertThat(merged).isEqualTo("{{baseUrl}}/x?already=here&foo=1")
}

@Test
fun testQueryParamsDuplicateKeyPreservedPerHttpSpec() {
  val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x?foo=1", linkedMapOf("foo" to "2"))
  assertThat(merged).isEqualTo("{{baseUrl}}/x?foo=1&foo=2")
}

@Test
fun testEmptyQueryParamsLeavesUrlUntouched() {
  val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", emptyMap())
  assertThat(merged).isEqualTo("{{baseUrl}}/x")
}
```

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3ToV2ConverterTest" -i`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt
git commit -m "test(v3): cover header order preservation and queryParams union"
```

---

## Task 10: Converter — body, scripts, auth

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt`

- [ ] **Step 1: Add body tests**

Append:

```kotlin
@Test
fun testConvertJsonBodyToRawMode() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "POST",
    body = V3Body(type = "json", content = """{"a":1}"""),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  assertThat(item.request.body).isNotNull()
  assertThat(item.request.body!!.mode).isEqualTo("raw")
  assertThat(item.request.body!!.raw).isEqualTo("""{"a":1}""")
}

@Test
fun testConvertTextBodyToRawMode() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "POST",
    body = V3Body(type = "text", content = "<xml/>"),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  assertThat(item.request.body!!.mode).isEqualTo("raw")
  assertThat(item.request.body!!.raw).isEqualTo("<xml/>")
}
```

- [ ] **Step 2: Add script tests**

Append:

```kotlin
@Test
fun testAfterResponseScriptMapsToTestEvent() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "GET",
    scripts = listOf(V3Script(type = "afterResponse", code = "console.log(1)\nconsole.log(2)")),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  assertThat(item.event).isNotNull()
  assertThat(item.event).hasSize(1)
  val event = item.event!!.single()
  assertThat(event.listen).isEqualTo("test")
  assertThat(event.script.exec).containsExactly("console.log(1)", "console.log(2)").inOrder()
}

@Test
fun testBeforeRequestScriptMapsToPrerequestEvent() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "GET",
    scripts = listOf(V3Script(type = "beforeRequest", code = "var x = 1")),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  val event = item.event!!.single()
  assertThat(event.listen).isEqualTo("prerequest")
}

@Test
fun testMultipleScriptsOfSameTypeAreMergedIntoOneEvent() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "GET",
    scripts = listOf(
      V3Script(type = "afterResponse", code = "a()"),
      V3Script(type = "afterResponse", code = "b()"),
    ),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  assertThat(item.event).hasSize(1)
  assertThat(item.event!!.single().script.exec).containsExactly("a()", "b()").inOrder()
}

@Test
fun testUnknownScriptTypeIsSkipped() {
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "GET",
    scripts = listOf(V3Script(type = "unknown", code = "x")),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
  assertThat(item.event).isNull()
}
```

- [ ] **Step 3: Add auth tests**

Append:

```kotlin
@Test
fun testConvertBearerAuthFromV3List() {
  val authList = listOf(V3Auth(type = "bearer", name = "bearer auth", credentials = mapOf("token" to "abc")))
  val auth = V3ToV2Converter.toAuth(authList)!!
  assertThat(auth.type).isEqualTo("bearer")
  assertThat(auth.bearer).hasSize(1)
  val b = auth.bearer.single()
  assertThat(b.key).isEqualTo("bearer auth")
  assertThat(b.type).isEqualTo("bearer")
  assertThat(b.value).isEqualTo("abc")
}

@Test
fun testNonBearerAuthIsDropped() {
  val authList = listOf(V3Auth(type = "basic", credentials = mapOf("username" to "u", "password" to "p")))
  val auth = V3ToV2Converter.toAuth(authList)
  assertThat(auth).isNull()
}

@Test
fun testRequestAuthOverridesInheritedAuth() {
  val inherited = V3ToV2Converter.toAuth(listOf(V3Auth(type = "bearer", credentials = mapOf("token" to "INHERITED"))))
  val v3 = V3Request(
    url = "{{baseUrl}}/x",
    method = "GET",
    auth = listOf(V3Auth(type = "bearer", credentials = mapOf("token" to "OVERRIDDEN"))),
  )
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = inherited)
  assertThat(item.request.auth!!.bearer.single().value).isEqualTo("OVERRIDDEN")
}

@Test
fun testInheritedAuthAppliesWhenRequestHasNoAuth() {
  val inherited = V3ToV2Converter.toAuth(listOf(V3Auth(type = "bearer", credentials = mapOf("token" to "INHERITED"))))
  val v3 = V3Request(url = "{{baseUrl}}/x", method = "GET")
  val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = inherited)
  assertThat(item.request.auth!!.bearer.single().value).isEqualTo("INHERITED")
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3ToV2ConverterTest" -i`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2ConverterTest.kt
git commit -m "test(v3): cover body, scripts, and auth conversions"
```

---

## Task 11: V3 directory walker — `flat` fixture + minimal loader

**Files:**
- Create: `src/test/resources/pm-templates/v3/flat/.resources/definition.yaml`
- Create: `src/test/resources/pm-templates/v3/flat/a.request.yaml`
- Create: `src/test/resources/pm-templates/v3/flat/b.request.yaml`
- Create: `src/test/resources/pm-templates/v3/flat/c.request.yaml`
- Create: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`

- [ ] **Step 1: Create fixtures**

Create `src/test/resources/pm-templates/v3/flat/.resources/definition.yaml`:

```yaml
$kind: collection
```

Create `src/test/resources/pm-templates/v3/flat/a.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/a
method: GET
order: 3000
```

Create `src/test/resources/pm-templates/v3/flat/b.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/b
method: GET
order: 1000
```

Create `src/test/resources/pm-templates/v3/flat/c.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/c
method: GET
order: 2000
```

- [ ] **Step 2: Write failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class V3LoaderTest {
  @Test
  fun testLoadFlatCollectionOrdersByOrderField() {
    val dir = File("src/test/resources/pm-templates/v3/flat")
    val items = V3Loader.load(dir)
    assertThat(items).hasSize(3)
    val names = items.map { it.name }
    assertThat(names).containsExactly("b", "c", "a").inOrder()
  }
}
```

- [ ] **Step 3: Run, expect FAIL on missing `V3Loader`**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest.testLoadFlatCollectionOrdersByOrderField" -i`
Expected: FAIL with "unresolved reference: V3Loader".

- [ ] **Step 4: Create V3Loader**

Create `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

internal object V3Loader {
  private const val DEF_REL_PATH = ".resources/definition.yaml"
  private const val REQUEST_SUFFIX = ".request.yaml"

  fun load(rootDir: File): List<Item> {
    require(rootDir.isDirectory) { "v3 collection root must be a directory: ${rootDir.absolutePath}" }
    val rootDef = readDefOrThrow(rootDir)
    return walk(rootDir, parentAuth = V3ToV2Converter.toAuth(rootDef.auth))
  }

  private fun walk(dir: File, parentAuth: Auth?): List<Item> {
    val def = readDefOrNull(dir)
    val effectiveAuth = if (def != null && def.auth.isNotEmpty()) {
      V3ToV2Converter.toAuth(def.auth)
    } else {
      parentAuth
    }

    val requestEntries: List<Pair<Item, Int>> = (dir.listFiles { f -> f.isFile && f.name.endsWith(REQUEST_SUFFIX) } ?: emptyArray())
      .map { file ->
        val v3req = V3YamlReader.readRequest(file.readText())
        val fallbackName = file.name.removeSuffix(REQUEST_SUFFIX)
        val item = V3ToV2Converter.toItem(v3req, fallbackName = fallbackName, inheritedAuth = effectiveAuth)
        item to (v3req.order ?: Int.MAX_VALUE)
      }

    val folderEntries: List<Pair<Item, Int>> = (dir.listFiles { f -> f.isDirectory && hasDef(f) } ?: emptyArray())
      .map { sub ->
        val subDef = readDefOrThrow(sub)
        val children = walk(sub, parentAuth = effectiveAuth)
        val folderItem = Item(name = sub.name, item = children, request = Request())
        folderItem to (subDef.order ?: Int.MAX_VALUE)
      }

    return (folderEntries + requestEntries)
      .sortedBy { it.second }
      .map { it.first }
  }

  private fun hasDef(dir: File): Boolean = File(dir, DEF_REL_PATH).isFile

  private fun readDefOrNull(dir: File): V3CollectionDef? {
    val defFile = File(dir, DEF_REL_PATH)
    if (!defFile.isFile) return null
    return V3YamlReader.readCollectionDef(defFile.readText())
  }

  private fun readDefOrThrow(dir: File): V3CollectionDef =
    readDefOrNull(dir) ?: error("Not a v3 collection root: ${dir.absolutePath}. Missing $DEF_REL_PATH")
}

private val logger = KotlinLogging.logger {}
```

- [ ] **Step 5: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest" -i`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/pm-templates/v3/flat src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt
git commit -m "feat(v3): add V3Loader with flat-directory walk and order-field sort"
```

---

## Task 12: V3 loader — nested fixture with auth inheritance + override

**Files:**
- Create: `src/test/resources/pm-templates/v3/nested/.resources/definition.yaml`
- Create: `src/test/resources/pm-templates/v3/nested/inherits-auth.request.yaml`
- Create: `src/test/resources/pm-templates/v3/nested/sub/.resources/definition.yaml`
- Create: `src/test/resources/pm-templates/v3/nested/sub/overrides-auth.request.yaml`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`

- [ ] **Step 1: Create fixtures**

`src/test/resources/pm-templates/v3/nested/.resources/definition.yaml`:

```yaml
$kind: collection
auth:
  - id: outer-auth-id
    type: bearer
    name: outer
    credentials:
      token: OUTER
```

`src/test/resources/pm-templates/v3/nested/inherits-auth.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/outer
method: GET
order: 1000
```

`src/test/resources/pm-templates/v3/nested/sub/.resources/definition.yaml`:

```yaml
$kind: collection
order: 2000
auth:
  - id: inner-auth-id
    type: bearer
    name: inner
    credentials:
      token: INNER
```

`src/test/resources/pm-templates/v3/nested/sub/overrides-auth.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/inner
method: GET
order: 1000
```

- [ ] **Step 2: Add failing test**

Append to `V3LoaderTest.kt`:

```kotlin
@Test
fun testLoadNestedCollectionWithAuthInheritanceAndOverride() {
  val dir = File("src/test/resources/pm-templates/v3/nested")
  val items = V3Loader.load(dir)
  // 1 request + 1 folder, ordered by `order` (request 1000 before folder 2000)
  assertThat(items).hasSize(2)
  assertThat(items[0].name).isEqualTo("inherits-auth")
  assertThat(items[0].request.auth!!.bearer.single().value).isEqualTo("OUTER")

  val sub = items[1]
  assertThat(sub.name).isEqualTo("sub")
  assertThat(sub.item).isNotNull()
  assertThat(sub.item).hasSize(1)
  val nested = sub.item!!.single()
  assertThat(nested.name).isEqualTo("overrides-auth")
  assertThat(nested.request.auth!!.bearer.single().value).isEqualTo("INNER")
}
```

- [ ] **Step 3: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest" -i`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/pm-templates/v3/nested src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt
git commit -m "test(v3): cover nested collection with folder auth inheritance and override"
```

---

## Task 13: V3 loader — error on missing definition.yaml + brackets-in-path

**Files:**
- Create: `src/test/resources/pm-templates/v3/no-def/somefile.txt`
- Create: `src/test/resources/pm-templates/v3/with [brackets]/.resources/definition.yaml`
- Create: `src/test/resources/pm-templates/v3/with [brackets]/req.request.yaml`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`

- [ ] **Step 1: Create fixtures**

`src/test/resources/pm-templates/v3/no-def/somefile.txt` (any content; placeholder so dir is non-empty):

```text
not a v3 collection
```

`src/test/resources/pm-templates/v3/with [brackets]/.resources/definition.yaml`:

```yaml
$kind: collection
```

`src/test/resources/pm-templates/v3/with [brackets]/req.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/x
method: GET
order: 1000
```

- [ ] **Step 2: Add failing tests**

Append to `V3LoaderTest.kt`:

```kotlin
@Test
fun testLoadThrowsWhenDefinitionMissing() {
  val dir = File("src/test/resources/pm-templates/v3/no-def")
  val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
    V3Loader.load(dir)
  }
  assertThat(ex.message).contains("Not a v3 collection root")
  assertThat(ex.message).contains(".resources/definition.yaml")
}

@Test
fun testLoadHandlesBracketsAndSpacesInPath() {
  val dir = File("src/test/resources/pm-templates/v3/with [brackets]")
  val items = V3Loader.load(dir)
  assertThat(items).hasSize(1)
  assertThat(items[0].name).isEqualTo("req")
}
```

- [ ] **Step 3: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest" -i`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add "src/test/resources/pm-templates/v3/no-def" "src/test/resources/pm-templates/v3/with [brackets]" src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt
git commit -m "test(v3): cover missing-def error and bracketed-path file walking"
```

---

## Task 14: V3 env loader

**Files:**
- Create: `src/test/resources/pm-templates/v3/test.environment.yaml`
- Create: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoaderTest.kt`
- Create: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt`

- [ ] **Step 1: Create fixture**

`src/test/resources/pm-templates/v3/test.environment.yaml`:

```yaml
name: Test
values:
  - key: baseUrl
    value: 'https://example.com'
  - key: count
    value: 5
  - key: emptyValue
    value:
```

- [ ] **Step 2: Write failing test**

Create `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoaderTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V3EnvLoaderTest {
  @Test
  fun testLoadEnvFromYamlFile() {
    val map = V3EnvLoader.loadFromPath("pm-templates/v3/test.environment.yaml")
    assertThat(map).containsEntry("baseUrl", "https://example.com")
    assertThat(map).containsEntry("count", "5")
    assertThat(map["emptyValue"]).isNull()
  }
}
```

- [ ] **Step 3: Run, expect FAIL**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3EnvLoaderTest" -i`
Expected: FAIL with "unresolved reference: V3EnvLoader".

- [ ] **Step 4: Create V3EnvLoader**

Create `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.input.bufferFile
import io.github.oshai.kotlinlogging.KotlinLogging

internal object V3EnvLoader {
  fun loadFromPath(path: String): Map<String, Any?> {
    val yaml = bufferFile(path).readUtf8()
    val env = V3YamlReader.readEnv(yaml)
    return env.values.associate { it.key to it.value }
  }
}

private val logger = KotlinLogging.logger {}
```

- [ ] **Step 5: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3EnvLoaderTest" -i`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/pm-templates/v3/test.environment.yaml src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoaderTest.kt
git commit -m "feat(v3): add V3EnvLoader for *.environment.yaml files"
```

---

## Task 15: Wire v3 collection loader into ReVoman.revUp

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3DetectionTest.kt`

- [ ] **Step 1: Write failing detection test**

Create `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3DetectionTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class V3DetectionTest {
  @Test
  fun testRevUpAcceptsV3DirectoryPathWithoutNetwork() {
    // The flat fixture's hosts (example.com) won't actually be hit; we only verify the loader is invoked
    // and the rundown is built (network step will fail, but parsing should not).
    val rundown = ReVoman.revUp(
      Kick.configure()
        .templatePath("pm-templates/v3/flat")
        .insecureHttp(true)
        .off()
    )
    // 3 steps loaded from flat fixture
    assertThat(rundown.totalNumberOfSteps).isEqualTo(3)
  }

  @Test
  fun testRevUpThrowsOnUnknownCollectionFormat() {
    val ex = assertThrows<IllegalStateException> {
      ReVoman.revUp(
        Kick.configure()
          .templatePath("src/test/resources/pm-templates/v3/no-def")
          .off()
      )
    }
    assertThat(ex.message).contains("Unrecognized collection")
      .or { assertThat(ex.message).contains("Not a v3 collection root") }
  }
}
```

NOTE: the second `assertThat(...).or { ... }` is pseudocode-style fallback — Truth doesn't support `.or`. Replace with:

```kotlin
@Test
fun testRevUpThrowsOnUnknownCollectionFormat() {
  val ex = assertThrows<IllegalStateException> {
    ReVoman.revUp(
      Kick.configure()
        .templatePath("src/test/resources/pm-templates/v3/no-def")
        .off()
    )
  }
  val msg = ex.message ?: ""
  assertThat(msg.contains("Unrecognized collection") || msg.contains("Not a v3 collection root")).isTrue()
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3DetectionTest" -i`
Expected: FAIL — `ReVoman.revUp` does not yet detect v3 directories.

- [ ] **Step 3: Modify ReVoman.kt to dispatch by path**

Open `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`. Find the block (in `revUp(kick: Kick)`):

```kotlin
val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
val templateBuffers =
  kick.templatePaths().map { bufferFile(it) } +
    kick.templateInputStreams().map { bufferInputStream(it) }
val pmStepsDeepFlattened =
  templateBuffers
    .asSequence()
    .mapNotNull { pmTemplateAdapter.fromJson(it) }
    .flatMap { (pmSteps, authFromRoot) ->
      deepFlattenItems(
        pmSteps.map { item ->
          item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
        }
      )
    }
    .toList()
```

Replace with:

```kotlin
val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
val itemsFromPaths: List<Item> = kick.templatePaths().flatMap { path ->
  val asFile = java.io.File(path)
  val asResource = path.toPath().let { p ->
    if (p.isAbsolute) null else okio.FileSystem.RESOURCES.metadataOrNull(p)
  }
  when {
    // Filesystem directory containing v3 marker
    asFile.isDirectory && java.io.File(asFile, ".resources/definition.yaml").isFile ->
      com.salesforce.revoman.internal.postman.template.v3.V3Loader.load(asFile)
    // Filesystem .json file or resource path -> v2 Moshi
    path.endsWith(".json") || asFile.isFile || asResource != null ->
      pmTemplateAdapter.fromJson(bufferFile(path))?.let { (pmSteps, authFromRoot) ->
        pmSteps.map { item ->
          item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
        }
      } ?: emptyList()
    else -> error("Unrecognized collection: $path. Expected v2 .json file or v3 directory with .resources/definition.yaml")
  }
}
val itemsFromStreams: List<Item> = kick.templateInputStreams().flatMap { stream ->
  pmTemplateAdapter.fromJson(bufferInputStream(stream))?.let { (pmSteps, authFromRoot) ->
    pmSteps.map { item ->
      item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
    }
  } ?: emptyList()
}
val pmStepsDeepFlattened = deepFlattenItems(itemsFromPaths + itemsFromStreams)
```

Add the imports at the top of the file (alongside existing imports):

```kotlin
import com.salesforce.revoman.internal.postman.template.Item
import okio.Path.Companion.toPath
```

(`java.io.File` and `okio.FileSystem` are referenced via fully-qualified names in the snippet to avoid import churn — feel free to add `import java.io.File` and `import okio.FileSystem` if it makes the code cleaner.)

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: ALL PASS — including existing v2 tests (regression guard) and new v3 detection tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3DetectionTest.kt
git commit -m "feat(v3): dispatch templatePaths between v2 JSON adapter and V3Loader"
```

---

## Task 16: Wire v3 env loader into Environment.mergeEnvs

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoaderTest.kt`

- [ ] **Step 1: Add failing test for end-to-end env merging via Environment.mergeEnvs**

Append to `V3EnvLoaderTest.kt`:

```kotlin
@Test
fun testMergeEnvsAcceptsV3YamlAndV2Json() {
  val merged = com.salesforce.revoman.internal.postman.template.Environment.mergeEnvs(
    pmEnvironmentPaths = setOf("pm-templates/v3/test.environment.yaml"),
    pmEnvironmentInputStreams = emptyList(),
    dynamicEnvironment = mapOf("override" to "yes"),
  )
  assertThat(merged).containsEntry("baseUrl", "https://example.com")
  assertThat(merged).containsEntry("count", "5")
  assertThat(merged).containsEntry("override", "yes")
}
```

NOTE: `Environment.mergeEnvs` is currently `internal` and inside a `companion object`. Confirm the visibility / access path before running. If it's `internal` and not visible to test, switch to calling via reflection or expose a test-only public façade. The simpler option: keep it `internal` (test source set sees `internal`), call as `Environment.Companion.mergeEnvs(...)` or `Environment.mergeEnvs(...)` depending on Kotlin version (Kotlin lets you call `internal companion` members from same module test).

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3EnvLoaderTest.testMergeEnvsAcceptsV3YamlAndV2Json" -i`
Expected: FAIL — Environment.mergeEnvs currently only accepts JSON.

- [ ] **Step 3: Modify Environment.mergeEnvs to dispatch by extension**

Open `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`.

Replace the existing `mergeEnvs` body:

```kotlin
@OptIn(ExperimentalStdlibApi::class)
internal fun mergeEnvs(
  pmEnvironmentPaths: Set<String>,
  pmEnvironmentInputStreams: List<InputStream>,
  dynamicEnvironment: Map<String, Any?>,
): Map<String, Any?> {
  val envAdapter = Moshi.Builder().build().adapter<Environment>()
  val envFromEnvFiles =
    (pmEnvironmentPaths.map { bufferFile(it) } +
        pmEnvironmentInputStreams.map { bufferInputStream(it) })
      .flatMap { envWithRegex ->
        envAdapter.fromJson(envWithRegex)?.values?.filter { it.enabled } ?: emptyList()
      }
      .associate { it.key to it.value }
  return envFromEnvFiles + dynamicEnvironment
}
```

with:

```kotlin
@OptIn(ExperimentalStdlibApi::class)
internal fun mergeEnvs(
  pmEnvironmentPaths: Set<String>,
  pmEnvironmentInputStreams: List<InputStream>,
  dynamicEnvironment: Map<String, Any?>,
): Map<String, Any?> {
  val envAdapter = Moshi.Builder().build().adapter<Environment>()
  val envFromYamlPaths: Map<String, Any?> = pmEnvironmentPaths
    .filter { it.endsWith(".yaml") || it.endsWith(".yml") }
    .fold(emptyMap()) { acc, path ->
      acc + com.salesforce.revoman.internal.postman.template.v3.V3EnvLoader.loadFromPath(path)
    }
  val envFromJsonPaths: Map<String, Any?> = pmEnvironmentPaths
    .filterNot { it.endsWith(".yaml") || it.endsWith(".yml") }
    .map { bufferFile(it) }
    .flatMap { source -> envAdapter.fromJson(source)?.values?.filter { it.enabled } ?: emptyList() }
    .associate { it.key to it.value }
  val envFromStreams: Map<String, Any?> = pmEnvironmentInputStreams
    .map { bufferInputStream(it) }
    .flatMap { source -> envAdapter.fromJson(source)?.values?.filter { it.enabled } ?: emptyList() }
    .associate { it.key to it.value }
  return envFromYamlPaths + envFromJsonPaths + envFromStreams + dynamicEnvironment
}
```

NOTE: Order of merging preserves existing v2 semantics — JSON files merge in their iteration order, then streams, then dynamicEnvironment overrides last. v3 YAML files merge first.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: ALL PASS — existing v2 env tests + new v3 env merge test.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoaderTest.kt
git commit -m "feat(v3): dispatch environmentPaths between JSON and YAML loaders"
```

---

## Task 17: Mixed-bodies fixture + integration-style unit test

**Files:**
- Create: `src/test/resources/pm-templates/v3/mixed-bodies/.resources/definition.yaml`
- Create: `src/test/resources/pm-templates/v3/mixed-bodies/post-json.request.yaml`
- Create: `src/test/resources/pm-templates/v3/mixed-bodies/patch-text.request.yaml`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`

- [ ] **Step 1: Create fixtures**

`src/test/resources/pm-templates/v3/mixed-bodies/.resources/definition.yaml`:

```yaml
$kind: collection
```

`src/test/resources/pm-templates/v3/mixed-bodies/post-json.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/x
method: POST
headers:
  Content-Type: application/json
body:
  type: json
  content: |-
    {"a":1}
scripts:
  - type: afterResponse
    code: console.log("after")
    language: text/javascript
  - type: beforeRequest
    code: console.log("before")
    language: text/javascript
order: 1000
```

`src/test/resources/pm-templates/v3/mixed-bodies/patch-text.request.yaml`:

```yaml
$kind: http-request
url: https://example.com/x
method: PATCH
headers:
  Content-Type: text/plain
body:
  type: text
  content: hello
order: 2000
```

- [ ] **Step 2: Add test**

Append to `V3LoaderTest.kt`:

```kotlin
@Test
fun testLoadMixedBodies() {
  val dir = File("src/test/resources/pm-templates/v3/mixed-bodies")
  val items = V3Loader.load(dir)
  assertThat(items).hasSize(2)
  val post = items[0]
  assertThat(post.name).isEqualTo("post-json")
  assertThat(post.request.method).isEqualTo("POST")
  assertThat(post.request.body!!.raw).contains("\"a\":1")
  assertThat(post.event).isNotNull()
  // Two scripts of different types -> two events
  assertThat(post.event!!.map { it.listen }.toSet()).containsExactly("test", "prerequest")
  val patch = items[1]
  assertThat(patch.request.method).isEqualTo("PATCH")
  assertThat(patch.request.body!!.raw).isEqualTo("hello")
}
```

- [ ] **Step 3: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest" -i`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/pm-templates/v3/mixed-bodies src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt
git commit -m "test(v3): cover mixed JSON and text bodies with multi-script events"
```

---

## Task 18: Pokemon v3 Kotlin integration test

**Files:**
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonKtTest.kt`

- [ ] **Step 1: Write the test**

Create `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonKtTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.pokemon.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class PokemonKtTest {
  @Test
  fun testExecutePokemonV3Collection() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(PM_COLLECTION_PATH)
          .environmentPath(PM_ENVIRONMENT_PATH)
          .nodeModulesPath("js")
          .dynamicEnvironment(mapOf("offset" to "0", "limit" to "1"))
          .off()
      )
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    // 4 leaf requests: all-pokemon, abilities, gender, color, nature -> 5 in v2 fixture; v3 has same set
    assertThat(rundown.stepReports.size).isAtLeast(4)
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/pokemon"
    private const val PM_ENVIRONMENT_PATH = "pm-templates/v3/pokemon/Pokemon.environment.yaml"
  }
}
```

NOTE on `templatePath` and resource resolution: `bufferFile` checks `path.toPath().isAbsolute`; if not absolute it loads via `RESOURCES`. For v3, we hand `templatePath` a relative directory. The dispatcher in Task 15 checks `asFile.isDirectory` first using `java.io.File(path)` — which is filesystem-relative-to-cwd. Integration tests run with cwd at the project root, so `pm-templates/v3/pokemon` resolves at `src/integrationTest/resources/pm-templates/v3/pokemon` ONLY if the test sources put their working directory there. Verify: existing v2 integration tests pass `pm-templates/v2/pokemon/pokemon.postman_collection.json` and Gradle integrationTest runs them with cwd that exposes `src/integrationTest/resources` as a classpath resource root, accessed via `okio.FileSystem.RESOURCES` (not as filesystem dir). For v3 we need filesystem dir access. **Decision**: in Task 15's dispatcher, when the v3 directory isn't found via plain `java.io.File(path)`, also try resolving against the resources classpath (fall back to `src/integrationTest/resources/<path>` for tests, or use `ClassLoader.getResource(path)?.toURI()`).

Update `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` (back-port refinement before running this task) to also resolve relative paths from the classpath:

```kotlin
private fun resolveCollectionDir(path: String): java.io.File? {
  val direct = java.io.File(path)
  if (direct.isDirectory && java.io.File(direct, ".resources/definition.yaml").isFile) return direct
  // Try classpath resource (test/integrationTest resources)
  val url = Thread.currentThread().contextClassLoader.getResource(path) ?: return null
  val viaResource = java.io.File(url.toURI())
  return if (viaResource.isDirectory && java.io.File(viaResource, ".resources/definition.yaml").isFile) viaResource else null
}
```

Then in the dispatcher branch:

```kotlin
val v3Dir = resolveCollectionDir(path)
when {
  v3Dir != null ->
    com.salesforce.revoman.internal.postman.template.v3.V3Loader.load(v3Dir)
  // ... (rest unchanged)
}
```

For env paths in `Environment.mergeEnvs`, the existing `bufferFile` already handles RESOURCES classpath, so no change needed for `*.environment.yaml`.

- [ ] **Step 2: Apply the ReVoman.kt refinement (classpath fallback)**

Edit `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`. Add the `resolveCollectionDir` helper near the bottom of the file (above `private val logger`), and update the dispatcher branch as shown above.

- [ ] **Step 3: Run the integration test**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.PokemonKtTest"`
Expected: PASS (network call to pokeapi.co; if it fails for network reasons, retry — not a code defect).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonKtTest.kt
git commit -m "feat(v3): resolve v3 collection dirs via classpath; add Pokemon Kotlin integration test"
```

---

## Task 19: Pokemon v3 Java integration test

**Files:**
- Create: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV3Test.java`

- [ ] **Step 1: Write the test**

Create `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV3Test.java`:

```java
/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon.v3;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PokemonV3Test {
  private static final String PM_COLLECTION_PATH = "pm-templates/v3/pokemon";
  private static final String PM_ENVIRONMENT_PATH = "pm-templates/v3/pokemon/Pokemon.environment.yaml";

  @Test
  void executePokemonV3CollectionFromJava() {
    final Kick config = Kick.configure()
        .templatePath(PM_COLLECTION_PATH)
        .environmentPath(PM_ENVIRONMENT_PATH)
        .nodeModulesPath("js")
        .dynamicEnvironment(Map.of("offset", "0", "limit", "1"))
        .off();
    final Rundown rundown = ReVoman.revUp(config);
    assertThat(rundown.firstUnsuccessfulStepReport).isNull();
    assertThat(rundown.stepReports.size()).isAtLeast(4);
  }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.PokemonV3Test"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV3Test.java
git commit -m "test(v3): add Pokemon v3 Java integration test for API friendliness"
```

---

## Task 20: Pokemon v2-vs-v3 equivalence (Kotlin + Java)

**Files:**
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonV2VsV3EquivalenceKtTest.kt`
- Create: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV2VsV3EquivalenceTest.java`

- [ ] **Step 1: Kotlin equivalence test**

Create `src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/PokemonV2VsV3EquivalenceKtTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.pokemon.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class PokemonV2VsV3EquivalenceKtTest {
  @Test
  fun testV2AndV3PokemonProduceIdenticalEnvAndStepCount() {
    val dynEnv = mapOf("offset" to "0", "limit" to "1")
    val v2 = ReVoman.revUp(
      Kick.configure()
        .templatePath("pm-templates/v2/pokemon/pokemon.postman_collection.json")
        .environmentPath("pm-templates/v2/pokemon/pokemon.postman_environment.json")
        .nodeModulesPath("js")
        .dynamicEnvironment(dynEnv)
        .off()
    )
    val v3 = ReVoman.revUp(
      Kick.configure()
        .templatePath("pm-templates/v3/pokemon")
        .environmentPath("pm-templates/v3/pokemon/Pokemon.environment.yaml")
        .nodeModulesPath("js")
        .dynamicEnvironment(dynEnv)
        .off()
    )
    assertThat(v3.stepReports.size).isEqualTo(v2.stepReports.size)
    val keysToCompare = listOf("baseUrl", "id", "pokemonName", "color", "gender", "ability", "nature")
    for (key in keysToCompare) {
      assertThat(v3.mutableEnv[key]).isEqualTo(v2.mutableEnv[key])
    }
  }
}
```

- [ ] **Step 2: Java equivalence test**

Create `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/PokemonV2VsV3EquivalenceTest.java`:

```java
/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.pokemon.v3;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PokemonV2VsV3EquivalenceTest {
  @Test
  void v2AndV3PokemonProduceIdenticalEnvAndStepCount() {
    final Map<String, Object> dyn = Map.of("offset", "0", "limit", "1");
    final Rundown v2 = ReVoman.revUp(
        Kick.configure()
            .templatePath("pm-templates/v2/pokemon/pokemon.postman_collection.json")
            .environmentPath("pm-templates/v2/pokemon/pokemon.postman_environment.json")
            .nodeModulesPath("js")
            .dynamicEnvironment(dyn)
            .off());
    final Rundown v3 = ReVoman.revUp(
        Kick.configure()
            .templatePath("pm-templates/v3/pokemon")
            .environmentPath("pm-templates/v3/pokemon/Pokemon.environment.yaml")
            .nodeModulesPath("js")
            .dynamicEnvironment(dyn)
            .off());
    assertThat(v3.stepReports.size()).isEqualTo(v2.stepReports.size());
    final List<String> keys = List.of("baseUrl", "id", "pokemonName", "color", "gender", "ability", "nature");
    for (final String k : keys) {
      assertThat(v3.mutableEnv.get(k)).isEqualTo(v2.mutableEnv.get(k));
    }
  }
}
```

- [ ] **Step 3: Run both**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3 src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3
git commit -m "test(v3): add Pokemon v2-vs-v3 equivalence tests in Kotlin and Java"
```

---

## Task 21: restful-api.dev v3 integration tests (Kotlin + Java)

**Files:**
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevKtTest.kt`
- Create: `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevV3Test.java`

- [ ] **Step 1: Kotlin test**

Create `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevKtTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.restfulapidev.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class RestfulAPIDevKtTest {
  @Test
  fun testExecuteRestfulApiDevV3Collection() {
    val rundown = ReVoman.revUp(
      Kick.configure()
        .templatePath(PM_COLLECTION_PATH)
        .environmentPath(PM_ENVIRONMENT_PATH)
        .nodeModulesPath("js")
        .off()
    )
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    assertThat(rundown.stepReports).hasSize(4)
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/restful-api.dev"
    private const val PM_ENVIRONMENT_PATH = "pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml"
  }
}
```

- [ ] **Step 2: Java test**

Create `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevV3Test.java`:

```java
/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.restfulapidev.v3;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import org.junit.jupiter.api.Test;

class RestfulAPIDevV3Test {
  private static final String PM_COLLECTION_PATH = "pm-templates/v3/restful-api.dev";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml";

  @Test
  void executeRestfulApiDevV3CollectionFromJava() {
    final Rundown rundown = ReVoman.revUp(
        Kick.configure()
            .templatePath(PM_COLLECTION_PATH)
            .environmentPath(PM_ENVIRONMENT_PATH)
            .nodeModulesPath("js")
            .off());
    assertThat(rundown.firstUnsuccessfulStepReport).isNull();
    assertThat(rundown.stepReports.size()).isEqualTo(4);
  }
}
```

- [ ] **Step 3: Run both**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.restfulapidev.v3.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3 src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3
git commit -m "test(v3): add restful-api.dev v3 integration tests in Kotlin and Java"
```

---

## Task 22: Apigee v3 Kotlin integration test

**Files:**
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/apigee/v3/ApigeeKtTest.kt`

- [ ] **Step 1: Write the test**

Create `src/integrationTest/kotlin/com/salesforce/revoman/integration/apigee/v3/ApigeeKtTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.apigee.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class ApigeeKtTest {
  @Test
  fun testExecuteApigeeV3Collection() {
    val rundown = ReVoman.revUp(
      Kick.configure()
        .templatePath("pm-templates/v3/Apigee")
        .nodeModulesPath("js")
        .off()
    )
    assertThat(rundown.firstUnsuccessfulStepReport).isNull()
    assertThat(rundown.stepReports).hasSize(1)
    assertThat(rundown.mutableEnv["city"]).isNotNull()
  }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.apigee.v3.ApigeeKtTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/kotlin/com/salesforce/revoman/integration/apigee/v3/ApigeeKtTest.kt
git commit -m "test(v3): add Apigee v3 Kotlin integration test"
```

---

## Task 23: Final regression run + spotless

**Files:** none modified directly; this is a verification gate.

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew test`
Expected: ALL PASS — including the existing v2 unit tests, all new v3 unit tests, and detection tests.

- [ ] **Step 2: Run all integration tests**

Run: `./gradlew integrationTest`
Expected: ALL PASS — existing v2 integration tests + new v3 integration tests.

- [ ] **Step 3: Apply formatting**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL with possible reformat to new files.

- [ ] **Step 4: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: If spotless changed anything, commit it**

```bash
git status
# if any changes:
git add -A
git commit -m "style: apply spotless formatting to v3 sources"
```

---

## Task 24: README docs for v3

**Files:**
- Modify: `README.adoc`

- [ ] **Step 1: Identify the section**

Read `README.adoc` and locate the section that explains how a user supplies `templatePath` / `environmentPath`. This is typically near the top of the "Usage" / "Configuration" section.

Run: `grep -n "templatePath\|environmentPath" README.adoc | head -20`
Use the output to navigate to the right section.

- [ ] **Step 2: Add a v3 sub-section**

Insert (just below the existing collection-format documentation):

```adoc
=== Postman Collection v3 (directory tree of YAML files)

Starting in this release, ReVoman accepts Postman v3 collections in addition to v2.

.Detection rules
[cols="1,1"]
|===
| Input | Loader

| Path to a `.json` file | v2 (existing)
| Path to a directory containing `.resources/definition.yaml` | v3 (new)
| Environment file ending in `.json` | v2 (existing)
| Environment file ending in `.yaml` or `.yml` | v3 (new)
|===

.Example
[source,kotlin]
----
ReVoman.revUp(
  Kick.configure()
    .templatePath("pm-templates/v3/pokemon")  // v3 directory
    .environmentPath("pm-templates/v3/pokemon/Pokemon.environment.yaml")  // v3 env
    .off()
)
----

.v3 collection layout

[source]
----
my-collection/
├── .resources/
│   └── definition.yaml          // {kind: collection, optional auth, optional order}
├── request-a.request.yaml       // {kind: http-request, url, method, ...}
├── request-b.request.yaml
├── sub-folder/
│   ├── .resources/
│   │   └── definition.yaml
│   └── nested-request.request.yaml
└── my-collection.environment.yaml   // optional, passed via environmentPath
----

.Known limitations of v3 support
- v3 collections must be supplied as a directory path; `templateInputStreams()` is v2-only.
- `*.environment.yaml` files must be supplied as a path; `environmentInputStreams()` is v2-only.
- Only `bearer` auth is supported (matches existing v2 limitation).
- `description` and `settings.disabledSystemHeaders` fields are parsed but ignored at runtime.
```

- [ ] **Step 3: Verify rendering (syntactic, not visual)**

Run: `./gradlew build` (asciidoctor task, if present, will validate syntax)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add README.adoc
git commit -m "docs: document Postman v3 collection support"
```

---

## Self-review

Reviewed against the spec section-by-section:

1. **Goal & scope** — covered: every in-scope item maps to a task; out-of-scope items are not implemented.
2. **Architecture (Approach A)** — Tasks 2, 3–7 (model + reader), 8–10 (converter), 11–13 (loader walk) implement v3→v2 normalization at load time.
3. **New files** — all listed in the file structure section above and created across Tasks 2, 3 (V3YamlReader), 8 (V3ToV2Converter), 11 (V3Loader), 14 (V3EnvLoader).
4. **Modified files** — `gradle/libs.versions.toml` + `build.gradle.kts` (Task 1), `ReVoman.kt` (Task 15 + 18 refinement), `Environment.kt` (Task 16).
5. **Detection rules** — Task 15 + Task 16.
6. **Dependency** — Task 1.
7. **V3 model** — Task 2.
8. **Walk algorithm** — Tasks 11, 12, 13.
9. **V3→V2 conversion table** — Tasks 8, 9, 10 (every row has an explicit test).
10. **YAML 1.1 boolean coercion** — Task 7.
11. **Env loader** — Task 14, wired in Task 16.
12. **Public API surface zero additions** — confirmed: no new `Kick` fields. Tests in Tasks 18, 19, 21 demonstrate existing API works for v3 from both Kotlin and Java.
13. **Error handling** — Task 13 (missing-def + brackets), Task 15 (unrecognized-collection error message).
14. **Logging** — converter and loader log warnings; matches existing pattern.
15. **Unit tests** — Tasks 3–14 cover loader/converter/env/detection.
16. **Unit-test fixtures** (`flat`, `nested`, `mixed-bodies`) — Tasks 11, 12, 17. (`with [brackets]` and `no-def` are extra.)
17. **Integration tests** (Java + Kotlin parity) — Tasks 18, 19, 20, 21, 22.
18. **Equivalence test** — Task 20.
19. **Rollout plan** (PRs 1-4) — the task ordering naturally produces these PR boundaries: PR1 = Tasks 1–14; PR2 = Tasks 15–17; PR3 = Tasks 18–22; PR4 = Task 24. Task 23 is a final gate before any PR merge.
20. **Risk register** — YAML 1.1 (Task 7), brackets-in-path (Task 13), classpath fallback (Task 18). Anchors/aliases risk mitigated by the eager hand-walk in `V3YamlReader` (returned data classes are immutable; SnakeYAML alias resolution happens during `Yaml.load`, after which any references in the resulting `Map` are read-only by virtue of our copy-on-build).
21. **Verification commands** — Task 23.

**Type/signature consistency check:**
- `V3Loader.load(rootDir: File): List<Item>` — used identically in Tasks 11–13 and 15, 18.
- `V3ToV2Converter.toItem(v3: V3Request, fallbackName: String, inheritedAuth: Auth?)` — used identically in Tasks 8, 9, 10, 11.
- `V3ToV2Converter.toAuth(authList: List<V3Auth>): Auth?` — used in Tasks 10, 11.
- `V3ToV2Converter.mergeQueryParams(url: String, queryParams: Map<String, String>): String` — used in Tasks 9, 10.
- `V3YamlReader.readCollectionDef`, `readRequest`, `readEnv` — used identically across Tasks 3–11, 14.
- `V3EnvLoader.loadFromPath(path: String): Map<String, Any?>` — used in Tasks 14 and 16.

**Placeholder scan:** none of "TBD", "TODO", "implement later", "fill in details", "appropriate error handling", "Similar to Task N". The few NOTEs in Tasks 15, 16, 18 explain *non-obvious* dispatch / classpath / visibility concerns and resolve them inline with concrete code.

**Spec gap check:** none found.

---

Plan complete and saved to `docs/superpowers/plans/2026-05-18-postman-v3-support.md`.

# Postman Collection v3 Support — Design

Status: Approved (design phase)
Date: 2026-05-18
Branch: `postman-v3-support-2`

## Goal

Add support for Postman collection schema v3 to ReVoman. v3 represents a collection as a directory tree of YAML files, fundamentally different from v2's single-JSON shape. ReVoman must accept either format transparently and produce identical `Rundown` results.

## Scope

**In scope**

- Load v3 collections from a directory tree containing `*.request.yaml` files plus folder-level `.resources/definition.yaml` files.
- Load v3 environment files (`*.environment.yaml`).
- Convert v3 input to the existing v2-shaped internal `Item`/`Request`/`Auth`/`Event` model so the rest of ReVoman's pipeline is untouched.
- Auto-detect v2 vs v3 based on input path (file vs directory, `.json` vs `.yaml`).
- Reuse existing public API — no new `Kick` fields.
- Unit, integration, and Java/Kotlin parity tests.

**Out of scope**

- Refactor into separate `core` / `plugin-postman-v2` / `plugin-postman-v3` modules (existing empty modules left untouched).
- v3 → v2 export.
- v3 collections supplied as `InputStream` (`templateInputStreams()` stays v2-only).
- In-process caching of parsed collections.
- Runtime effect of `settings.disabledSystemHeaders` (parsed but ignored — no-op against http4k transport).
- Propagating `description` into reports.
- Multiple auths per scope.
- Auth types beyond `bearer` (matches v2's existing limitation).
- Folder-level `description`.
- Adding v3-only fields to the internal model.

## Architecture

Approach A: V3-to-V2 normalization at load time.

All v3 code is internal package. v3 input is parsed and converted to existing v2 internal types (`Item`, `Request`, `Body`, `Auth`, `Event`) at the boundary. The v3 loader produces a `List<Item>` with the **same nested folder shape** as the v2 Moshi adapter (folder items carry their children in `Item.item`). The existing `deepFlattenItems` then traverses this tree exactly as before. Downstream pipeline (`RegexReplacer`, `PostmanSDK`, JS execution, hooks, exec/poll) sees only v2 types.

### New files

```
src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/
├── V3Loader.kt              // entry point; walks dir, returns List<Item>
├── V3Model.kt               // SnakeYAML-deserialized data classes
├── V3ToV2Converter.kt       // V3* -> v2 Item/Request/Auth/Event
└── V3EnvLoader.kt           // YAML env file -> Map<String, Any?>
```

### Modified files

- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — branch on path/extension between v2 Moshi adapter and `V3Loader`.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` — branch on extension between v2 Moshi env adapter and `V3EnvLoader`.
- `gradle/libs.versions.toml` + `build.gradle.kts` — add SnakeYAML dependency.

### Detection rules

| Input | Loader |
|---|---|
| `*.json` file | existing v2 Moshi adapter |
| Directory containing `.resources/definition.yaml` | new v3 loader |
| `*.yaml` / `*.yml` env file | new v3 env loader |
| `*.json` env file | existing v2 env loader |
| `templateInputStreams()` | existing v2 Moshi adapter (v3 not supported via stream) |
| `environmentInputStreams()` | existing v2 Moshi adapter (v3 not supported via stream) |

Anything else: `IllegalStateException` with a clear message.

## Dependency

Add `org.yaml:snakeyaml` (latest stable 2.x) at `implementation` scope (not exposed in public API). Justification: smaller dep than Jackson YAML; v3 envelope is plain scalars/maps/lists requiring no advanced YAML features; user payload serialization (Moshi adapters, `globalSkipTypes`, `requestConfig`/`responseConfig`) remains untouched because Moshi only sees the JSON-shaped intermediate produced from YAML→Map→JSON-encode.

## V3 model

SnakeYAML produces `Map<String, Any?>`; the loader hand-walks this map into typed Kotlin data classes. No SnakeYAML reflection setup needed; v3 schema is small.

```kotlin
internal data class V3CollectionDef(
  val kind: String = "collection",
  val order: Int? = null,
  val auth: List<V3Auth> = emptyList(),
)

internal data class V3Request(
  val kind: String = "http-request",
  val name: String? = null,           // overrides filename
  val description: String? = null,    // parsed, ignored
  val url: String,
  val method: String,
  val headers: Map<String, String> = emptyMap(),
  val queryParams: Map<String, String> = emptyMap(),
  val body: V3Body? = null,
  val scripts: List<V3Script> = emptyList(),
  val auth: List<V3Auth> = emptyList(),  // request-level overrides folder
  val settings: V3Settings? = null,      // parsed, ignored
  val order: Int? = null,
)

internal data class V3Body(val type: String, val content: String)
internal data class V3Script(val type: String, val code: String, val language: String?)
internal data class V3Auth(
  val id: String?,
  val type: String,                       // e.g. "bearer"
  val name: String?,
  val credentials: Map<String, String>,   // e.g. {"token": "{{accessToken}}"}
)
internal data class V3Settings(val disabledSystemHeaders: List<String> = emptyList())
internal data class V3Env(val name: String, val values: List<V3EnvValue>)
internal data class V3EnvValue(val key: String, val value: String?)
```

Unknown YAML keys are ignored (lenient walk; no strict schema validation).

## Walk algorithm

```
loadCollection(rootDir):
  rootDef = readDef(rootDir/.resources/definition.yaml)  // required
  return walk(rootDir, parentAuth = rootDef.auth, rootDef = rootDef)

walk(dir, parentAuth, parentDef):
  def = readDef(dir/.resources/definition.yaml) or null
  effectiveAuth = def?.auth.takeIf { isNotEmpty } ?: parentAuth   // folder-level inherits

  childRequests = dir.list("*.request.yaml").map { yaml ->
    v3req = parseRequest(yaml)
    requestAuth = v3req.auth.takeIf { isNotEmpty } ?: effectiveAuth
    fallbackName = yaml.fileName.removeSuffix(".request.yaml")
    convertToV2Item(v3req, requestAuth, fallbackName) to (v3req.order ?: Int.MAX_VALUE)
  }

  childFolders = dir.subDirs().filter { hasDef(it) }.map { sub ->
    subDef = readDef(sub/.resources/definition.yaml)
    subItems = walk(sub, effectiveAuth, subDef)
    folderItem = Item(name = sub.name, item = subItems.map { it.first }, ...)
    folderItem to (subDef.order ?: Int.MAX_VALUE)
  }

  return (childFolders + childRequests).sortedBy { it.second }.map { it.first }
```

Folders and requests at the same depth share one sort key (the `order` int from the def or request file). Items without `order` go to the end (stable sort).

## V3 → V2 conversion

| v3 | v2 |
|---|---|
| `V3Request.name ?: filename-without-suffix` | `Item.name` |
| `V3Request.method` | `Request.method` |
| `V3Request.url` after queryParam union | `Url.raw` |
| `V3Request.headers: Map<String,String>` (insertion order) | `List<Header>` |
| `V3Request.queryParams` map entries | appended to URL query string after existing params (URL params first; duplicates allowed; per HTTP spec) |
| `V3Body { type: "json", content }` | `Body { mode = "raw", raw = content }`. Postman v2.1 also stores JSON bodies under `mode = "raw"`; the JSON-formatting hook in `Template.toHttpRequest` keys off the request's `Content-Type` header, not `mode`, so this preserves identical behavior. |
| `V3Body { type: "text", content }` | `Body { mode = "raw", raw = content }` |
| `V3Auth.firstOrNull() { type: "bearer", credentials.token }` | `Auth { type: "bearer", bearer: [Bearer(key = name ?: "token", type = "bearer", value = credentials.token)] }` |
| `V3Auth` with `type != "bearer"` | dropped, log warning (matches existing v2 bearer-only support) |
| `V3Script { type: "afterResponse" }` | `Item.event` entry: `Event { listen: "test", script: { exec: code.lines() } }` |
| `V3Script { type: "beforeRequest"` or `"prerequest" }` | `Item.event` entry: `Event { listen: "prerequest", script: { exec: code.lines() } }` |
| Multiple scripts of same type | concatenated into one `Event`; exec lines combined. Scripts attach to the v2 `Item.event` list (not `Request.event`). |
| `V3Request.description`, `V3Settings` | dropped |

### YAML 1.1 boolean coercion

SnakeYAML defaults to YAML 1.1 where `yes`/`no`/`on`/`off`/`true`/`false` parse as `Boolean`. To prevent string-typed header/url/queryParam/body content values from silently becoming booleans, the loader coerces every leaf value via `toString()` after read. Covered by a unit test.

## Env loader

```kotlin
internal fun loadV3Env(path: String): Map<String, Any?> {
  val yaml = Yaml().load<Map<String, Any?>>(bufferFile(path).inputStream())
  val values = yaml["values"] as? List<Map<String, Any?>> ?: emptyList()
  return values.associate { (it["key"] as String) to it["value"]?.toString() }
}
```

v3 env has no `enabled` flag; every entry is enabled. Result type matches existing v2 env loader output (`Map<String, Any?>`). Although the value type is `Any?` for compatibility, the v3 loader always populates string values (coerced via `toString()`) — same effective shape as the v2 loader.

`Environment.mergeEnvs` is modified to dispatch by extension. `environmentInputStreams()` continues v2-only — documented in KDoc.

## Detection in `ReVoman.revUp`

```kotlin
private fun loadCollection(path: String): List<Item> {
  val file = File(path)
  return when {
    file.isDirectory && File(file, ".resources/definition.yaml").exists() ->
      V3Loader.load(file)
    path.endsWith(".json") ->
      v2MoshiAdapter.fromJson(bufferFile(path))?.let { /* existing flatten */ }
    else -> error(
      "Unrecognized collection: $path. Expected v2 .json file or v3 directory with .resources/definition.yaml"
    )
  }
}
```

`templateInputStreams()` continues to use the v2 adapter unchanged.

## Public API surface

Zero additions. Existing `Kick.templatePaths()` and `Kick.environmentPaths()` accept v3 paths transparently. Detection is internal.

`templateInputStreams()` and `environmentInputStreams()` documented as v2-only in KDoc.

## Error handling

| Error | Behavior |
|---|---|
| Path doesn't exist | `FileNotFoundException` with path |
| Directory without `.resources/definition.yaml` | `IllegalStateException("Not a v3 collection root: <dir>. Missing .resources/definition.yaml")` |
| Malformed YAML | SnakeYAML's `YAMLException` propagates with file path prepended |
| `$kind` missing or unexpected | `IllegalStateException("Unknown $kind in <file>: <value>")` |
| `*.request.yaml` missing required field (`url` or `method`) | `IllegalStateException("v3 request <file> missing required field: <field>")` |
| Unknown auth type | log warning, drop auth |
| Unknown script type | log warning, skip script |
| Empty collection (no `*.request.yaml`) | not an error; produce empty `List<Item>`; ReVoman already handles 0 steps |

## Logging

- info: each file parsed
- debug: each conversion
- warn: dropped/unknown fields

Fits existing `KotlinLogging` pattern.

## Testing

### Unit tests (Kotlin only)

`src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/`

| File | Coverage |
|---|---|
| `V3LoaderTest.kt` | Walks fixture dirs; asserts step count, names, hierarchy, ordering, auth inheritance |
| `V3ToV2ConverterTest.kt` | Per-mapping tests: headers map → list, scripts grouped by type, body type→mode, auth conversion, queryParams union, name override |
| `V3EnvLoaderTest.kt` | YAML env → Map; shape parity with existing v2 env loader output |
| `V3DetectionTest.kt` | Path → loader routing; v2 .json untouched; bad inputs throw with clear messages |

Plus YAML 1.1 boolean-coercion regression test inside `V3LoaderTest.kt`.

### Unit-test fixtures (new)

`src/test/resources/pm-templates/v3/`

| Fixture | Purpose |
|---|---|
| `flat/` | Single folder, 3 requests, no auth, exercises ordering |
| `nested/` | 2-level hierarchy, folder-inherited auth, request-level override |
| `mixed-bodies/` | POST/PATCH with json + text bodies, multi-script (afterResponse + beforeRequest) |

### Integration tests (Java + Kotlin parity)

Sub-package by version: `.../integration/<feature>/v3/`. Existing v2 tests stay where they are.

```
src/integrationTest/kotlin/com/salesforce/revoman/integration/pokemon/v3/
├── PokemonKtTest.kt
└── PokemonV2VsV3EquivalenceKtTest.kt

src/integrationTest/java/com/salesforce/revoman/integration/pokemon/v3/
├── PokemonTest.java
└── PokemonV2VsV3EquivalenceTest.java

src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/
└── RestfulAPIDevKtTest.kt

src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/
└── RestfulAPIDevTest.java

src/integrationTest/kotlin/com/salesforce/revoman/integration/apigee/v3/
└── ApigeeKtTest.kt
```

Java tests prove:
- `Kick.templatePaths(...)` accepts a directory string from Java
- `Kick.environmentPaths(...)` accepts a `.yaml` string from Java
- `ReVoman.revUp(kick)` returns `Rundown` usable from Java with no Kotlin-only types leaking

Kotlin tests cover idiomatic-Kotlin usage. Apigee Java mirror is omitted (existing v2 Apigee test is Kotlin-only; v3 mirrors that).

### Equivalence test

For one collection (Pokemon) in both Java and Kotlin: load the same logical collection as v2 JSON and as v3 directory tree, run both with the same dynamic env, assert identical step ordering, identical mutated env, identical response shapes.

## Rollout plan

Each PR independently mergeable; each leaves `master` working. Branch `postman-v3-support-2` accumulates them.

1. **PR 1 — model + loader (no integration):** add SnakeYAML dep, `V3Model.kt`, `V3Loader.kt`, `V3ToV2Converter.kt`, `V3EnvLoader.kt`, unit tests + unit-test fixtures. No wiring in `ReVoman.revUp`. CI green proves loader works in isolation.
2. **PR 2 — wire into `ReVoman.revUp` + `Environment.mergeEnvs`:** add detection branch. Existing v2 tests stay green (regression guard). Add v3 detection unit tests.
3. **PR 3 — integration tests:** add the three v3 integration test pairs (Java + Kotlin) plus the equivalence test pair. Real network calls. CI integrationTest job validates.
4. **PR 4 — README docs:** update `README.adoc` with v3 path-detection rules, directory layout, env file format, conversion table, known limitations. Non-code; can ride alongside PR 3 if small.

## Risk register

| Risk | Mitigation |
|---|---|
| YAML 1.1 boolean quirk (`yes`/`no`/`on`/`off` parse as bool) corrupts string values | Coerce all leaf values via `toString()` after parse; cover with a regression test |
| Path with brackets or spaces (e.g. `[sm] pq`) breaks file walk | Use `java.nio.file.Path` not string concat; test with bracketed fixture |
| YAML anchors/aliases (`&x`/`*x`) producing shared mutable refs | Treat parsed YAML as immutable; build Kotlin types eagerly |
| Future v3 schema additions break SnakeYAML loader | Lenient map-walk approach (unknown keys ignored), not strict schema validation |

## Verification commands

```bash
./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.*"
./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.*"
./gradlew build
./gradlew spotlessApply
```

## Open questions

None at design time. All clarifications captured in conversation log.

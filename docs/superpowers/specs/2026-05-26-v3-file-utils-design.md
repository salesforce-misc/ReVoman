# V3 FileUtils — Design

Status: Approved (design phase)
Date: 2026-05-26
Branch: `feat-mock-server`

## Goal

Eliminate the ~150-line consumer-side wrapper (`partitionByV3Dir`, `resolveFileV3DirAbsolutePath`, `materializeJarV3DirToTempDir`, `resolveYamlFilePath`) consumers currently write to feed jar-backed Postman v3 collections to ReVoman. After this change, a consumer with a v3 collection on the classpath (whether dev-mode filesystem or production jar) calls `kick.templatePath("my-collection")` and ReVoman handles the rest transparently.

Two complementary changes:
1. Make `ReVoman.revUp` transparently load v3 collections from filesystem dirs AND jar entries (no consumer wrapper required).
2. Expose three small public helpers in `FileUtils.kt` (`isV3Collection`, `isV3EnvFile`, `bufferV3Definition`) for consumers who still need explicit detection (e.g., choosing between `templatePaths` and `templateInputStreams`).

## Why okio

Consumer's current pain stems from two filesystem APIs (`java.io.File` for filesystem dirs, `JarFile`/`URLDecoder`/`Files.copy` for jar entries) that don't compose. The consumer materializes jar entries to a temp dir purely to bridge them. ReVoman already uses `okio.FileSystem.RESOURCES` for v2 single-file reads (`bufferFile` in `FileUtils.kt`) — that abstraction unifies filesystem and jar entries behind one API (`Path`, `source()`, `list()`, `metadata()`). Refactoring `V3Loader` to walk via `FileSystem` eliminates the dual-path code without temp-dir materialization.

Trade-off: `okio.FileSystem.RESOURCES` is a singleton bound to the default classloader at first use. Edge cases (OSGi, hot-swap classloaders, custom classloader-isolated test harnesses) won't see resources from non-default classloaders. Acceptable for ReVoman's typical Spring/JAR deployment context. Not a regression — current code has the same limitation via `bufferFile`.

## Scope

**In scope**
- Refactor `V3Loader` from `java.io.File` to `okio.Path` + `okio.FileSystem`.
- Add transparent jar-backed v3 dir support in `ReVoman.revUp`.
- Add public helpers in `FileUtils.kt`: `isV3Collection`, `isV3EnvFile`, `bufferV3Definition`.
- Use new helpers internally in `ReVoman.revUp` and `Environment.mergeEnvs` (single source of truth for detection).
- Unit test for jar-backed v3 collection loading (regression guard).

**Out of scope**
- `materializeToTempDir` helper. Whole point of the okio refactor is no temp-dir step.
- Refactor `V3EnvLoader.loadFromPath` body — already okio-based via `bufferFile`.
- New `Kick` API surface — existing `templatePath()`/`environmentPath()` already accept v3 inputs; this just makes more inputs work.
- v2 path — `bufferFile` already handles jar-backed single files via okio. No change needed.

## Architecture

### V3Loader refactor

Current (`V3Loader.kt`):
```kotlin
internal object V3Loader {
  fun load(rootDir: File): List<Item>

  private fun walk(dir: File, parentAuth: Auth?): List<Item> {
    (dir.listFiles { f -> f.isFile && f.name.endsWith(REQUEST_SUFFIX) } ?: emptyArray())...
    (dir.listFiles { f -> f.isDirectory && hasDef(f) } ?: emptyArray())...
  }

  private fun hasDef(dir: File): Boolean = File(dir, DEF_REL_PATH).isFile
}
```

After:
```kotlin
internal object V3Loader {
  fun load(rootPath: String): List<Item> {
    val (path, fs) = resolvePath(rootPath)
    return load(path, fs)
  }

  fun load(rootPath: Path, fs: FileSystem): List<Item> {
    require(fs.metadataOrNull(rootPath)?.isDirectory == true) {
      "v3 collection root must be a directory: $rootPath"
    }
    val rootDef = readDefOrThrow(rootPath, fs)
    return walk(rootPath, fs, parentAuth = V3ToV2Converter.toAuth(rootDef.auth))
  }

  private fun walk(dir: Path, fs: FileSystem, parentAuth: Auth?): List<Item> {
    val def = readDefOrNull(dir, fs)
    val effectiveAuth =
      if (def != null && def.auth.isNotEmpty()) V3ToV2Converter.toAuth(def.auth) else parentAuth

    val children = fs.list(dir)
    val requestEntries = children
      .filter { fs.metadata(it).isRegularFile && it.name.endsWith(REQUEST_SUFFIX) }
      .map { file ->
        val v3req = V3YamlReader.readRequest(fs.source(file).buffer().readUtf8())
        val fallbackName = file.name.removeSuffix(REQUEST_SUFFIX)
        val item = V3ToV2Converter.toItem(v3req, fallbackName, inheritedAuth = effectiveAuth)
        item to (v3req.order ?: Int.MAX_VALUE)
      }
    val folderEntries = children
      .filter { fs.metadata(it).isDirectory && hasDef(it, fs) }
      .map { sub ->
        val subDef = readDefOrThrow(sub, fs)
        val nestedItems = walk(sub, fs, parentAuth = effectiveAuth)
        val folderItem = Item(name = sub.name, item = nestedItems, request = Request())
        folderItem to (subDef.order ?: Int.MAX_VALUE)
      }

    return (folderEntries + requestEntries).sortedBy { it.second }.map { it.first }
  }

  private fun resolvePath(path: String): Pair<Path, FileSystem> {
    val p = path.toPath()
    val fs = if (p.isAbsolute) FileSystem.SYSTEM else FileSystem.RESOURCES
    return p to fs
  }

  private fun hasDef(dir: Path, fs: FileSystem): Boolean =
    fs.exists(dir / DEF_REL_PATH)

  private fun readDefOrNull(dir: Path, fs: FileSystem): V3CollectionDef? {
    val defFile = dir / DEF_REL_PATH
    if (!fs.exists(defFile)) return null
    return V3YamlReader.readCollectionDef(fs.source(defFile).buffer().readUtf8())
  }

  private fun readDefOrThrow(dir: Path, fs: FileSystem): V3CollectionDef =
    readDefOrNull(dir, fs) ?: error("Not a v3 collection root: $dir. Missing $DEF_REL_PATH")
}
```

Key points:
- `DEF_REL_PATH = ".resources/definition.yaml"` parsed with `okio.Path` operator `/` so brackets/spaces in paths work without escaping.
- `fs.metadata` instead of `File.isFile`/`isDirectory` — works for jar entries.
- Two-arity overload `load(Path, FileSystem)` enables tests to inject a `FakeFileSystem` if useful (not required initially; jar test will use real classpath).

### FileUtils additions

Append to `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`:

```kotlin
private const val V3_DEFINITION_REL_PATH = ".resources/definition.yaml"

private fun resolvePath(path: String): Pair<okio.Path, okio.FileSystem> {
  val p = path.toPath()
  val fs = if (p.isAbsolute) SYSTEM else RESOURCES
  return p to fs
}

fun isV3Collection(path: String): Boolean = runCatching {
  val (p, fs) = resolvePath(path)
  val md = fs.metadataOrNull(p) ?: return false
  if (!md.isDirectory) return false
  fs.exists(p / V3_DEFINITION_REL_PATH)
}.getOrDefault(false)

fun isV3EnvFile(path: String): Boolean =
  path.endsWith(".yaml") || path.endsWith(".yml")

fun bufferV3Definition(collectionDir: String): BufferedSource {
  val (p, fs) = resolvePath(collectionDir)
  return fs.source(p / V3_DEFINITION_REL_PATH).buffer()
}
```

`@JvmName("FileUtils")` already on file → Java callers use `FileUtils.isV3Collection(path)`.

`isV3Collection` is total (returns `Boolean`, never throws). Consumers can call it without try/catch.

`bufferV3Definition` propagates okio's `FileNotFoundException` if marker missing — caller's responsibility to gate with `isV3Collection`.

### ReVoman.revUp dispatch

Replace lines 90–102 (current `flatMap` over `templatePaths`) and delete `resolveV3CollectionDir` (lines 258–271):

```kotlin
val itemsFromPaths: List<Item> = kick.templatePaths().flatMap { path ->
  if (isV3Collection(path)) {
    V3Loader.load(path)
  } else {
    pmTemplateAdapter.fromJson(bufferFile(path))?.let { (pmSteps, authFromRoot) ->
      pmSteps.map { item ->
        item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
      }
    } ?: emptyList()
  }
}
```

Existing imports get one addition: `import com.salesforce.revoman.input.isV3Collection`.

`V3Loader.load` is now string-keyed; the import line `import com.salesforce.revoman.internal.postman.template.v3.V3Loader.load` stays.

### Environment.mergeEnvs dispatch

Current (line 41):
```kotlin
.filter { it.endsWith(".yaml") || it.endsWith(".yml") }
.filterNot { it.endsWith(".yaml") || it.endsWith(".yml") }
```

After:
```kotlin
.filter { isV3EnvFile(it) }
.filterNot { isV3EnvFile(it) }
```

Add `import com.salesforce.revoman.input.isV3EnvFile`.

### Consumer impact

Consumer's `partitionByV3Dir` (~150 lines) collapses to:
```java
paths.forEach(kick::templatePath);
```

If consumer needs explicit detection (still wants stream branch for non-v3 inputs), they can use:
```java
for (String path : paths) {
  if (FileUtils.isV3Collection(path) || path.endsWith(".json") || FileUtils.isV3EnvFile(path)) {
    kick.templatePath(path);
  } else {
    kick.templateInputStream(resourceLoader.getResource(path).newInputStream());
  }
}
```

Either form works. Both eliminate `materializeJarV3DirToTempDir`, `resolveYamlFilePath`, `URLDecoder`, `Files.createTempDirectory`, `Files.copy`, `deleteOnExit` — all gone.

## Detection rules (unchanged from v3 spec)

| Input | Detection | Loader |
|---|---|---|
| `*.json` file | not `isV3Collection` | v2 Moshi |
| Directory with `.resources/definition.yaml` (filesystem OR jar) | `isV3Collection` true | `V3Loader` |
| `*.yaml` / `*.yml` env file | `isV3EnvFile` true | `V3EnvLoader` |
| `*.json` env file | `isV3EnvFile` false | v2 Moshi env |
| `templateInputStreams()` | n/a | v2 Moshi (stream) — unchanged |
| `environmentInputStreams()` | n/a | v2 Moshi env (stream) — unchanged |

## Error handling

| Case | Behavior |
|---|---|
| `isV3Collection("missing-path")` | returns `false` (no throw) |
| `V3Loader.load("missing-path")` | `IllegalArgumentException` from `require(...isDirectory)` |
| Dir without marker passed to `V3Loader.load` | `IllegalStateException("Not a v3 collection root: ...")` |
| `bufferV3Definition` on dir without marker | `FileNotFoundException` from okio |
| Jar entry missing | okio throws `FileNotFoundException` |
| Path with brackets / spaces | `okio.Path` operator `/` handles these without escaping |

No new error surfaces. Detection helper is total. Loader errors match prior contract.

## Testing

### New unit tests

`src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt` (new file):
- `testIsV3CollectionTrueForFilesystemDirWithMarker` — uses `pm-templates/v3/flat`
- `testIsV3CollectionFalseForDirWithoutMarker` — uses `pm-templates/v3/no-def`
- `testIsV3CollectionFalseForV2JsonFile` — passes a `.postman_collection.json` path
- `testIsV3CollectionFalseForMissingPath`
- `testIsV3EnvFileTruthTable` — `.yaml` true, `.yml` true, `.json` false, no-extension false
- `testBufferV3DefinitionReadsMarkerContent`

`src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt` (new file):
- Package the existing `flat/` fixture into a temp `.jar` via `JarOutputStream`.
- Add the jar to a fresh `URLClassLoader`.
- Use `Thread.currentThread().setContextClassLoader(...)` to force `okio.FileSystem.RESOURCES` to see jar entries (or pass an explicit `FileSystem` to the two-arity `V3Loader.load(Path, FileSystem)`).
- Assert `V3Loader.load(...)` returns the same items as the filesystem fixture (3 items, ordered b/c/a).

This test is the regression guard: if someone reverts to `java.io.File` walking, this test fails because jar entries don't have a `java.io.File` representation.

### Modified unit tests

`V3LoaderTest.kt` — switch test calls from:
```kotlin
V3Loader.load(File("src/test/resources/pm-templates/v3/flat"))
```
to:
```kotlin
V3Loader.load("pm-templates/v3/flat")
```
Same fixtures, same assertions. Removes filesystem-path coupling.

### Integration tests

No changes. Pokemon, restful-api.dev, Apigee tests already pass relative classpath strings → keep working under the new dispatch.

## Logging

No new log messages. Existing v3 loader/converter warnings preserved.

## Rollout

Single PR on `feat-mock-server` branch. Files touched:
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt` (refactor)
- `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt` (3 new public functions)
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` (drop `resolveV3CollectionDir`, use `isV3Collection`)
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` (use `isV3EnvFile`)
- `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt` (new)
- `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt` (new)
- `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt` (adapt signatures)

Backwards compatibility: existing `kick.templatePath("...json")` and `kick.templatePath("filesystem-v3-dir")` calls keep working. New capability: jar-backed v3 dirs and classpath v3 dir strings both work.

## Verification

```bash
./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.*"
./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest"
./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.*"
./gradlew build
./gradlew spotlessApply
```

## Risk register

| Risk | Mitigation |
|---|---|
| `okio.FileSystem.RESOURCES` singleton bound to default classloader → won't see custom-classloader jars | Acceptable; matches existing `bufferFile` behavior. Two-arity `V3Loader.load(Path, FileSystem)` lets advanced consumers override if needed. |
| Jar entries with backslash separators on Windows | `okio.Path` normalizes separators internally; tested via existing brackets-in-path fixture pattern |
| Test packaging a jar at runtime is brittle | Use stable `JarOutputStream` + `JarEntry` API; fixture files copied verbatim |
| `isV3Collection` returns false silently on permission errors | Acceptable — caller can probe with `bufferV3Definition` for explicit error |

## Open questions

None at design time.

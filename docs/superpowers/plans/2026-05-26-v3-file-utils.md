# V3 FileUtils Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add public okio-based v3 collection helpers (`isV3Collection`, `isV3EnvFile`, `bufferV3Definition`) to `FileUtils.kt`, refactor `V3Loader` to walk filesystem and jar entries uniformly via `okio.FileSystem`, and make `ReVoman.revUp` transparently load jar-backed v3 collection directories so consumers can drop ~150 lines of jar-walking wrapper code.

**Architecture:** `V3Loader.load` switches from `java.io.File` to `okio.Path` + `okio.FileSystem`. New string-keyed `V3Loader.load(rootPath: String)` resolves via `SYSTEM` (absolute) or `RESOURCES` (relative classpath, transparent across filesystem dirs and jar entries). `FileUtils` exposes three pure helpers used internally by `ReVoman.revUp` and `Environment.mergeEnvs` and externally by consumers. The current `ReVoman.resolveV3CollectionDir` helper (which bails on `jar:` URLs) is deleted.

**Tech Stack:** Kotlin 2.x, JDK 21, okio 3.17 (existing dependency), JUnit 5, Truth, SnakeYAML 2.x (existing).

**Spec:** `docs/superpowers/specs/2026-05-26-v3-file-utils-design.md`

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt` | Unit tests for new public helpers (`isV3Collection`, `isV3EnvFile`, `bufferV3Definition`) |
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt` | Regression test: load v3 collection from a jar packaged at runtime |

### Modified files

| File | Change |
|---|---|
| `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt` | Add three public functions backed by okio: `isV3Collection`, `isV3EnvFile`, `bufferV3Definition` |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt` | Refactor from `java.io.File` to `okio.Path` + `okio.FileSystem`. Add string-keyed `load(rootPath: String)` overload |
| `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` | Delete `resolveV3CollectionDir`; use `isV3Collection(path)` + `V3Loader.load(path)` |
| `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` | Replace inline `endsWith(".yaml")` checks with `isV3EnvFile` helper |
| `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt` | Switch test calls from `V3Loader.load(File(...))` to `V3Loader.load("...")` (relative classpath) |

---

## Conventions used in this plan

- **Test naming:** `testXxx` with no backticks (per `STYLE.md`).
- **Test framework:** JUnit 5 + Truth (`assertThat(...)`), matching existing v3 tests.
- **Logging:** existing `private val logger = KotlinLogging.logger {}` pattern at file end. No new log statements introduced in this plan.
- **Commits:** small, frequent. Stage only files mentioned in each step.
- **Spotless:** run `./gradlew spotlessApply` after each substantive change before committing if formatting drifts.

---

## Task 1: Add `isV3EnvFile` to FileUtils (TDD)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FileUtilsTest {
  @Test
  fun testIsV3EnvFileTruthTable() {
    assertThat(isV3EnvFile("env.yaml")).isTrue()
    assertThat(isV3EnvFile("env.yml")).isTrue()
    assertThat(isV3EnvFile("env.YAML")).isFalse()
    assertThat(isV3EnvFile("env.json")).isFalse()
    assertThat(isV3EnvFile("env")).isFalse()
    assertThat(isV3EnvFile("path/to/foo.environment.yaml")).isTrue()
  }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest.testIsV3EnvFileTruthTable" -i`
Expected: FAIL with "unresolved reference: isV3EnvFile".

- [ ] **Step 3: Add `isV3EnvFile` to `FileUtils.kt`**

Edit `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`. Append before `writeToFile`:

```kotlin
fun isV3EnvFile(path: String): Boolean = path.endsWith(".yaml") || path.endsWith(".yml")
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest.testIsV3EnvFileTruthTable" -i`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt
git commit -m "$(cat <<'EOF'
feat(input): add isV3EnvFile public helper

Centralizes the .yaml / .yml extension check used today by
Environment.mergeEnvs. Will replace inline duplicates and serve as the
public detection surface for consumers.
EOF
)"
```

---

## Task 2: Add `isV3Collection` to FileUtils (TDD)

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`

- [ ] **Step 1: Add failing tests**

Append to `FileUtilsTest.kt`:

```kotlin
@Test
fun testIsV3CollectionTrueForFilesystemDirWithMarker() {
  assertThat(isV3Collection("src/test/resources/pm-templates/v3/flat")).isTrue()
}

@Test
fun testIsV3CollectionTrueForClasspathDirWithMarker() {
  assertThat(isV3Collection("pm-templates/v3/flat")).isTrue()
}

@Test
fun testIsV3CollectionFalseForDirWithoutMarker() {
  assertThat(isV3Collection("src/test/resources/pm-templates/v3/no-def")).isFalse()
}

@Test
fun testIsV3CollectionFalseForV2JsonFile() {
  assertThat(
      isV3Collection("src/test/resources/pm-templates/v2/pokemon/pokemon.postman_collection.json")
    )
    .isFalse()
}

@Test
fun testIsV3CollectionFalseForMissingPath() {
  assertThat(isV3Collection("src/test/resources/pm-templates/v3/does-not-exist")).isFalse()
  assertThat(isV3Collection("missing-classpath-resource")).isFalse()
}
```

NOTE: `pm-templates/v2/pokemon/pokemon.postman_collection.json` exists per the v3 spec's references and v2 fixtures. If the exact path differs, run `find src/test/resources -name '*.postman_collection.json' | head -3` and substitute the path that exists. The point is to assert a single-file v2 path returns `false`.

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest" -i`
Expected: FAIL with "unresolved reference: isV3Collection".

- [ ] **Step 3: Add `isV3Collection` to `FileUtils.kt`**

Edit `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`. The current file structure:

```kotlin
@file:JvmName("FileUtils")

package com.salesforce.revoman.input

import java.io.File
import java.io.InputStream
import okio.BufferedSource
import okio.FileSystem.Companion.RESOURCES
import okio.FileSystem.Companion.SYSTEM
import okio.Path.Companion.toPath
import okio.buffer
import okio.source

fun bufferFile(filePath: String): BufferedSource =
  filePath.toPath().let { (if (it.isAbsolute) SYSTEM else RESOURCES).source(it).buffer() }

// ...existing functions...

fun isV3EnvFile(path: String): Boolean = path.endsWith(".yaml") || path.endsWith(".yml")
```

Add after `writeToFile`:

```kotlin
private const val V3_DEFINITION_REL_PATH = ".resources/definition.yaml"

fun isV3Collection(path: String): Boolean =
  runCatching {
      val p = path.toPath()
      val fs = if (p.isAbsolute) SYSTEM else RESOURCES
      val md = fs.metadataOrNull(p) ?: return@runCatching false
      if (!md.isDirectory) return@runCatching false
      fs.exists(p / V3_DEFINITION_REL_PATH)
    }
    .getOrDefault(false)
```

NOTE: `okio.Path` operator `/` is provided by the existing `okio.Path.Companion.toPath` import context — no additional imports required because `Path.div` is a member function. Verify the build still passes after step 4.

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest" -i`
Expected: ALL PASS (including Task 1's `testIsV3EnvFileTruthTable`).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt
git commit -m "$(cat <<'EOF'
feat(input): add isV3Collection public helper

Detects v3 collection directories on both filesystem and classpath
(including jar entries) via okio FileSystem. Total function: returns
false on any failure rather than throwing, so consumers can call without
try/catch. Backs upcoming transparent dispatch in ReVoman.revUp.
EOF
)"
```

---

## Task 3: Add `bufferV3Definition` to FileUtils (TDD)

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`

- [ ] **Step 1: Add failing test**

Append to `FileUtilsTest.kt`:

```kotlin
@Test
fun testBufferV3DefinitionReadsMarkerContent() {
  val content = bufferV3Definition("pm-templates/v3/flat").readUtf8()
  assertThat(content).contains("\$kind: collection")
}
```

NOTE: SnakeYAML's `$kind` shows up verbatim in the YAML source; the dollar sign is escaped in the Kotlin string above so it's a literal `$kind`.

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest.testBufferV3DefinitionReadsMarkerContent" -i`
Expected: FAIL with "unresolved reference: bufferV3Definition".

- [ ] **Step 3: Add `bufferV3Definition` to `FileUtils.kt`**

Append to `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`:

```kotlin
fun bufferV3Definition(collectionDir: String): BufferedSource {
  val p = collectionDir.toPath()
  val fs = if (p.isAbsolute) SYSTEM else RESOURCES
  return fs.source(p / V3_DEFINITION_REL_PATH).buffer()
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.input.FileUtilsTest" -i`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt
git commit -m "$(cat <<'EOF'
feat(input): add bufferV3Definition public helper

Buffers the root .resources/definition.yaml for a v3 collection,
mirroring the v2 bufferFile contract. Lets consumers inspect or validate
the root definition before passing the collection to ReVoman.
EOF
)"
```

---

## Task 4: Refactor `V3Loader` to okio (preserve existing test signatures via temporary File overload)

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`

This task changes the implementation to okio internally while keeping the existing `load(File)` signature so existing tests pass. Task 5 then migrates tests, and Task 6 removes the `File` overload.

- [ ] **Step 1: Replace `V3Loader.kt` with okio implementation**

Replace contents of `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`:

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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer

internal object V3Loader {
  private const val DEF_REL_PATH = ".resources/definition.yaml"
  private const val REQUEST_SUFFIX = ".request.yaml"

  fun load(rootPath: String): List<Item> {
    val (path, fs) = resolvePath(rootPath)
    return load(path, fs)
  }

  fun load(rootDir: File): List<Item> = load(rootDir.toOkioPath(), FileSystem.SYSTEM)

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
    val requestEntries: List<Pair<Item, Int>> =
      children
        .filter { fs.metadataOrNull(it)?.isRegularFile == true && it.name.endsWith(REQUEST_SUFFIX) }
        .map { file ->
          val v3req = V3YamlReader.readRequest(fs.source(file).buffer().readUtf8())
          val fallbackName = file.name.removeSuffix(REQUEST_SUFFIX)
          val item =
            V3ToV2Converter.toItem(
              v3req,
              fallbackName = fallbackName,
              inheritedAuth = effectiveAuth,
            )
          item to (v3req.order ?: Int.MAX_VALUE)
        }

    val folderEntries: List<Pair<Item, Int>> =
      children
        .filter { fs.metadataOrNull(it)?.isDirectory == true && hasDef(it, fs) }
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

  private fun hasDef(dir: Path, fs: FileSystem): Boolean = fs.exists(dir / DEF_REL_PATH)

  private fun readDefOrNull(dir: Path, fs: FileSystem): V3CollectionDef? {
    val defFile = dir / DEF_REL_PATH
    if (!fs.exists(defFile)) return null
    return V3YamlReader.readCollectionDef(fs.source(defFile).buffer().readUtf8())
  }

  private fun readDefOrThrow(dir: Path, fs: FileSystem): V3CollectionDef =
    readDefOrNull(dir, fs) ?: error("Not a v3 collection root: $dir. Missing $DEF_REL_PATH")
}

private val logger = KotlinLogging.logger {}
```

- [ ] **Step 2: Run all v3 unit tests**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.*" -i`
Expected: ALL PASS — existing `V3LoaderTest.kt` calls `load(File(...))` which now delegates through the okio path.

NOTE: One existing test asserts the error message contains `Not a v3 collection root` (line 48 of `V3LoaderTest.kt`). The new error string is `Not a v3 collection root: <Path>. Missing .resources/definition.yaml` — assertion still holds because it's a `contains` match.

- [ ] **Step 3: Run all unit tests (regression guard)**

Run: `./gradlew test`
Expected: ALL PASS — no regressions in v2 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt
git commit -m "$(cat <<'EOF'
refactor(v3): switch V3Loader to okio FileSystem walking

Replaces java.io.File with okio.Path + okio.FileSystem so the loader
walks filesystem dirs and jar entries through one API. Adds
load(rootPath: String) and load(rootPath: Path, fs: FileSystem)
overloads. Existing load(File) is kept as a thin delegate to keep
existing tests green; removed in a follow-up commit.
EOF
)"
```

---

## Task 5: Migrate `V3LoaderTest` to string-keyed loader

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`

- [ ] **Step 1: Replace `V3LoaderTest.kt`**

Replace contents:

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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class V3LoaderTest {
  @Test
  fun testLoadFlatCollectionOrdersByOrderField() {
    val items = V3Loader.load("pm-templates/v3/flat")
    assertThat(items).hasSize(3)
    val names = items.map { it.name }
    assertThat(names).containsExactly("b", "c", "a").inOrder()
  }

  @Test
  fun testLoadNestedCollectionWithAuthInheritanceAndOverride() {
    val items = V3Loader.load("pm-templates/v3/nested")
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

  @Test
  fun testLoadThrowsWhenDefinitionMissing() {
    val ex =
      assertThrows(IllegalStateException::class.java) {
        V3Loader.load("pm-templates/v3/no-def")
      }
    assertThat(ex.message).contains("Not a v3 collection root")
    assertThat(ex.message).contains(".resources/definition.yaml")
  }

  @Test
  fun testLoadHandlesBracketsAndSpacesInPath() {
    val items = V3Loader.load("pm-templates/v3/with [brackets]")
    assertThat(items).hasSize(1)
    assertThat(items[0].name).isEqualTo("req")
  }

  @Test
  fun testLoadMixedBodies() {
    val items = V3Loader.load("pm-templates/v3/mixed-bodies")
    assertThat(items).hasSize(2)
    val post = items[0]
    assertThat(post.name).isEqualTo("post-json")
    assertThat(post.request.method).isEqualTo("POST")
    assertThat(post.request.body!!.raw).contains("\"a\":1")
    assertThat(post.event).isNotNull()
    assertThat(post.event!!.map { it.listen }.toSet()).containsExactly("test", "prerequest")
    val patch = items[1]
    assertThat(patch.request.method).isEqualTo("PATCH")
    assertThat(patch.request.body!!.raw).isEqualTo("hello")
  }
}
```

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest" -i`
Expected: ALL PASS — strings resolve via `okio.FileSystem.RESOURCES`, which exposes `src/test/resources/...` to the Gradle test runtime classpath.

NOTE: If a test fails to find the `with [brackets]` fixture via classpath, verify Gradle copies it to the test resources output by running `./gradlew processTestResources && ls "build/resources/test/pm-templates/v3/with [brackets]/.resources"`. Bracketed dirs work; this is just a sanity check.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt
git commit -m "$(cat <<'EOF'
test(v3): migrate V3LoaderTest to string-keyed classpath loader

Switches every test from V3Loader.load(File("src/test/resources/...")) to
V3Loader.load("..."), exercising the okio RESOURCES path that production
code uses. Removes filesystem-path coupling.
EOF
)"
```

---

## Task 6: Remove the `File` overload from `V3Loader`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`

- [ ] **Step 1: Verify no other call sites use `V3Loader.load(File)`**

Run: `grep -rn "V3Loader\.load" --include="*.kt" --include="*.java" src/ docs/`
Expected output: only the references in `ReVoman.kt` (which calls a string-keyed `load`) and in tests we already migrated. No lingering `load(File(...))` calls outside of `V3Loader.kt` itself.

If any unexpected call sites surface, either migrate them in this task or skip removing the overload until they are migrated.

- [ ] **Step 2: Delete the `load(File)` overload**

Edit `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`. Remove these two lines plus the `import java.io.File` and `import okio.Path.Companion.toOkioPath` lines:

```kotlin
import java.io.File
import okio.Path.Companion.toOkioPath
// ...
  fun load(rootDir: File): List<Item> = load(rootDir.toOkioPath(), FileSystem.SYSTEM)
```

The remaining imports should be:

```kotlin
import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import io.github.oshai.kotlinlogging.KotlinLogging
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
```

- [ ] **Step 3: Run all unit tests**

Run: `./gradlew test`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt
git commit -m "$(cat <<'EOF'
refactor(v3): drop V3Loader.load(File) overload

All call sites now use the string-keyed or (Path, FileSystem) overloads.
File-based entry point was a temporary bridge during the okio refactor.
EOF
)"
```

---

## Task 7: Add `V3LoaderJarTest` (jar-loading regression guard)

**Files:**
- Create: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt`

- [ ] **Step 1: Write the test**

Create `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt`:

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
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Test

class V3LoaderJarTest {
  @Test
  fun testLoadV3CollectionFromJarEntries() {
    val jar = packageFixtureIntoJar(srcDir = "src/test/resources/pm-templates/v3/flat")
    val cl = URLClassLoader(arrayOf(jar.toURI().toURL()), null)

    val rootUrl = cl.getResource("flat") ?: error("expected fixture at jar root 'flat/'")
    assertThat(rootUrl.protocol).isEqualTo("jar")

    val zipFs = FileSystem.SYSTEM.openZip(jar.toOkioPath())
    val items = V3Loader.load("/flat".toPath(), zipFs)

    assertThat(items).hasSize(3)
    assertThat(items.map { it.name }).containsExactly("b", "c", "a").inOrder()
  }

  private fun packageFixtureIntoJar(srcDir: String): File {
    val src = File(srcDir)
    require(src.isDirectory) { "fixture source dir not found: $srcDir" }
    val jar = Files.createTempFile("v3-fixture-", ".jar").toFile().apply { deleteOnExit() }
    JarOutputStream(jar.outputStream()).use { jos ->
      writeDirInto(jos, src, prefix = "flat")
    }
    return jar
  }

  private fun writeDirInto(jos: JarOutputStream, dir: File, prefix: String) {
    dir.listFiles().orEmpty().forEach { child ->
      val entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
      if (child.isDirectory) {
        jos.putNextEntry(JarEntry("$entryName/"))
        jos.closeEntry()
        writeDirInto(jos, child, prefix = entryName)
      } else {
        jos.putNextEntry(JarEntry(entryName))
        child.inputStream().use { it.copyTo(jos) }
        jos.closeEntry()
      }
    }
  }
}
```

NOTE on approach: rather than relying on a temporary `URLClassLoader` to alter `okio.FileSystem.RESOURCES` (which is bound to the default classloader), the test exercises the same code via `FileSystem.SYSTEM.openZip(jarPath)` — this is a real `okio.FileSystem` view of jar entries and uses identical `Path`/`source`/`list`/`metadata` APIs. The two-arity `V3Loader.load(Path, FileSystem)` overload added in Task 4 is the seam we need to test jar loading deterministically. The classloader assertion (`rootUrl.protocol == "jar"`) is a smoke check that the jar packaging worked.

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderJarTest" -i`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt
git commit -m "$(cat <<'EOF'
test(v3): add jar-backed V3Loader regression test

Packages the existing flat fixture into a temp jar at runtime, opens it
via okio FileSystem.SYSTEM.openZip, and asserts V3Loader walks the
entries identically to the filesystem fixture. Guards against a regression
to java.io.File walking, which silently breaks for jar-packaged
collections.
EOF
)"
```

---

## Task 8: Wire `isV3Collection` into `ReVoman.revUp` (drop `resolveV3CollectionDir`)

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`

- [ ] **Step 1: Update imports and dispatch**

Edit `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`.

Add to the imports block (alphabetical):

```kotlin
import com.salesforce.revoman.input.isV3Collection
```

Replace the current `kick.templatePaths().flatMap { ... }` block (lines 90–102) with:

```kotlin
val itemsFromPaths: List<com.salesforce.revoman.internal.postman.template.Item> =
  kick.templatePaths().flatMap { path ->
    if (isV3Collection(path)) {
      load(path)
    } else {
      pmTemplateAdapter.fromJson(bufferFile(path))?.let { (pmSteps, authFromRoot) ->
        pmSteps.map { item ->
          item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
        }
      } ?: emptyList()
    }
  }
```

Delete the entire `resolveV3CollectionDir` private helper (currently at lines 258–271).

The existing `import com.salesforce.revoman.internal.postman.template.v3.V3Loader.load` stays — it now resolves to the string-keyed `load(rootPath: String)` overload.

- [ ] **Step 2: Run all tests**

Run: `./gradlew test`
Expected: ALL PASS — including existing v3 detection tests in `V3DetectionTest.kt`.

NOTE: `V3DetectionTest.testRevUpThrowsOnUnknownCollectionFormat` asserts the message contains either `Unrecognized collection` or `Not a v3 collection root`. Today, calling `revUp` on `src/test/resources/pm-templates/v3/no-def` (a dir without marker) goes through the v2 Moshi adapter — `bufferFile` resolves the directory path via `okio.FileSystem.RESOURCES.source(...)`, which throws because it's a directory not a file. Verify this throw is still surfaced; if the assertion needs adjustment, update the test to accept the actual exception message.

- [ ] **Step 3: Run integration tests**

Run: `./gradlew integrationTest --tests "com.salesforce.revoman.integration.pokemon.v3.*"`
Expected: PASS — Pokemon v3 (and other v3 integration suites) now go through `isV3Collection` before falling back to the v2 adapter.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/ReVoman.kt
git commit -m "$(cat <<'EOF'
refactor: route v3 collections through public isV3Collection helper

ReVoman.revUp now dispatches via FileUtils.isV3Collection, which works
uniformly for filesystem dirs and jar entries via okio. Removes the
private resolveV3CollectionDir helper that bailed on jar: URLs and forced
consumers to materialize jar-backed v3 collections to a temp dir
themselves.
EOF
)"
```

---

## Task 9: Wire `isV3EnvFile` into `Environment.mergeEnvs`

**Files:**
- Modify: `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`

- [ ] **Step 1: Update imports and replace inline checks**

Edit `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`.

Add to imports:

```kotlin
import com.salesforce.revoman.input.isV3EnvFile
```

Replace:
```kotlin
.filter { it.endsWith(".yaml") || it.endsWith(".yml") }
```
with:
```kotlin
.filter { isV3EnvFile(it) }
```

Replace:
```kotlin
.filterNot { it.endsWith(".yaml") || it.endsWith(".yml") }
```
with:
```kotlin
.filterNot { isV3EnvFile(it) }
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew test`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt
git commit -m "$(cat <<'EOF'
refactor(env): use FileUtils.isV3EnvFile in mergeEnvs

Replaces inline endsWith(".yaml") || endsWith(".yml") with the public
helper. Single source of truth for env-file detection across the
codebase.
EOF
)"
```

---

## Task 10: Final regression + spotless

**Files:** none modified directly.

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew test`
Expected: ALL PASS.

- [ ] **Step 2: Run all integration tests**

Run: `./gradlew integrationTest`
Expected: ALL PASS.

- [ ] **Step 3: Apply spotless**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL. Possible reformat of new/modified files.

- [ ] **Step 4: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit any spotless-driven formatting changes**

```bash
git status
# if any changes:
git add -A
git commit -m "style: apply spotless formatting to v3 FileUtils sources"
```

---

## Self-review

Cross-checked each spec section against tasks:

1. **Goal** — Tasks 1–3 (FileUtils helpers) + Tasks 4–6 (V3Loader okio) + Tasks 8–9 (transparent dispatch) cover both consumer entry points (utility + transparent).
2. **Why okio** — Tasks 4–7 implement the `FileSystem` abstraction; Task 7 is the jar regression guard.
3. **In-scope** items —
   - V3Loader refactor: Tasks 4, 6.
   - Transparent jar v3 in `revUp`: Task 8.
   - Public helpers: Tasks 1, 2, 3.
   - Internal use of helpers: Task 8 (`isV3Collection`), Task 9 (`isV3EnvFile`).
   - Jar regression test: Task 7.
4. **Out-of-scope** items — `materializeToTempDir`, `V3EnvLoader.loadFromPath` body changes, new `Kick` API: none of these appear in any task.
5. **V3Loader refactor architecture** — Task 4 implements the exact code from the spec section "V3Loader refactor".
6. **FileUtils additions** — Tasks 1–3 produce exactly the three helpers in the spec ("FileUtils additions") with the same signatures.
7. **ReVoman.revUp dispatch** — Task 8 implements the exact replacement block from the spec ("ReVoman.revUp dispatch").
8. **Environment.mergeEnvs dispatch** — Task 9 implements the exact change from the spec ("Environment.mergeEnvs dispatch").
9. **Detection rules table** — covered transitively: `isV3Collection` is exercised in Task 2; `isV3EnvFile` in Task 1; both wired in Tasks 8, 9.
10. **Error handling** — Task 2 covers `isV3Collection` returning false on missing path. Task 5 covers `V3Loader` throwing `IllegalStateException` on missing marker. Task 4's NOTE addresses `bufferFile` directory-path behavior in `V3DetectionTest`.
11. **Testing**: new unit tests — `FileUtilsTest` (Tasks 1–3), `V3LoaderJarTest` (Task 7). Modified — `V3LoaderTest` (Task 5). Integration tests run unchanged (Task 8 step 3, Task 10 step 2).
12. **Logging** — no new log lines introduced; existing `private val logger` lines preserved verbatim in Task 4's replacement.
13. **Rollout plan** — single PR; all tasks land on `feat-v3-reader-util` branch.
14. **Verification commands** — Task 10 runs the four commands listed in the spec.
15. **Risk register** — covered:
    - Singleton `RESOURCES` classloader binding: Task 7 deliberately uses `openZip` instead, mirroring the spec's two-arity overload mitigation.
    - Backslash separators on Windows: relies on `okio.Path` normalization; no extra task — accepted risk per spec.
    - Test packaging brittleness: Task 7 uses `JarOutputStream`/`JarEntry` directly per spec.
    - `isV3Collection` silent on permission errors: spec accepts; covered by Task 2's truth table.

**Type / signature consistency:**
- `V3Loader.load(rootPath: String)` — defined in Task 4, used in Tasks 5, 7 (string form), 8.
- `V3Loader.load(rootPath: Path, fs: FileSystem)` — defined in Task 4, used in Task 7.
- `V3Loader.load(rootDir: File)` — added in Task 4 (temporary bridge), removed in Task 6.
- `isV3Collection(path: String): Boolean` — defined in Task 2, used in Task 8.
- `isV3EnvFile(path: String): Boolean` — defined in Task 1, used in Task 9.
- `bufferV3Definition(collectionDir: String): BufferedSource` — defined in Task 3.

**Placeholder scan:** no occurrences of "TBD", "TODO", "implement later", "fill in details", "appropriate error handling", "Similar to Task N". The handful of NOTEs in Tasks 2, 4, 5, 7, 8 explain non-obvious decisions and resolve them inline with concrete code or commands.

**Spec gap check:** none.

---

Plan complete and saved to `docs/superpowers/plans/2026-05-26-v3-file-utils.md`.

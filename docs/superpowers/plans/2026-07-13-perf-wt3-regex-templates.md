# Perf WT-3: Regex / Templates / Vars Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply five FP-preserving, behavior-identical micro-optimizations (C1–C5) to the regex/template/variable hot paths — a `{{` fast-path guard, a static-entry skip in the env rescan, a reused SnakeYAML reader, a single `metadataOrNull` stat per child, and JDK 21 `HexFormat` encoding — and measure the wins with a JMH benchmark.

**Architecture:** All changes are localized to four files (`RegexReplacer.kt`, `V3YamlReader.kt`, `V3Loader.kt`, `DynamicVariableGenerator.kt`). Each fix is a guard/refactor inside an existing `?.let` / `associate` / `filter` / pure-function pipeline — no signatures change, no call sites change (only `ReVoman.kt:411` and `V3Loader`'s own walk consume these, and their contracts are preserved byte-for-byte). Correctness is locked by characterization tests written *before* each edit; JMH numbers are measured, not gated.

**Tech Stack:** Kotlin, SnakeYAML, Okio FileSystem, JDK 21 HexFormat, JMH

## Global Constraints
- JDK 21+; branched off WT-0 (has JMH source set at `src/jmh/kotlin`)
- Owns ONLY these 4 files — touch no other source file:
  `src/main/kotlin/com/salesforce/revoman/internal/postman/RegexReplacer.kt`,
  `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReader.kt`,
  `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`,
  `src/main/kotlin/com/salesforce/revoman/internal/postman/DynamicVariableGenerator.kt`
- Correctness gate: `./gradlew test integrationTest` green (non-core). Bench (measured, non-gating): `./gradlew jmh -Pjmh.includes=RegexVarBenchmark`
- C1 must be byte-for-byte behavior-identical (the guard only short-circuits strings that cannot match — the pattern `\{\{(?<variableKey>[^{}]*?)}}` requires a literal `{{`)
- `postManVariableRegex` (top-level val, `RegexReplacer.kt:17`) is ALREADY hoisted — do NOT "fix" it
- Preserve FP style (STYLE.md): the existing `?.let` / `associate` / `map` / `filter` pipelines stay; add guards, don't rewrite
- Do NOT touch `V3YamlWriter` (needs its own `DumperOptions` — different file, different worktree concern)
- `./gradlew spotlessApply` before every commit
---

## Behavior-identity proof for C1 (read before implementing)

The private `replaceVariablesRecursively` resolves `{{key}}` tokens via `postManVariableRegex.replace(it) { … }`. The regex is `"\\{\\{(?<variableKey>[^{}]*?)}}"` — it **cannot** match unless the input contains a literal `{{`. Therefore `if (!it.contains("{{")) return@let it` returns exactly what `postManVariableRegex.replace(it) { … }` would return for such a string (the replace is a no-op → returns the same instance/content).

Recursion cannot defeat the guard: every recursive call (`cdvg.generate(...)` result, `dynamicVariableGenerator(...)` result, and each `resolveFromScopes` value) is itself fed back through the *same* guarded function. A resolved value that contains `{{` still hits the regex on that recursive call; a resolved value with no `{{` genuinely has nothing to resolve. Resolution never *synthesizes* a `{{` inside a string that lacked one — substitution only ever *removes* `{{…}}` tokens or leaves them literal. So no no-`{{` string ever needs processing. Result: identical output, identical side effects (`recordConsumed` / `setItBackInEnvironment` fire only when the regex actually matches, which requires `{{`).

---

## Task 1 — C1: `{{` fast-path guard in `replaceVariablesRecursively`

### 1.1 Characterization test (lock current behavior + the no-`{{` identity path)
- [ ] Append to `src/test/kotlin/com/salesforce/revoman/internal/postman/RegexReplacerTest.kt`:

```kotlin
  @Test
  fun `string without double-brace is returned unchanged`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    val plain = """{ "a": 1, "b": "no placeholders here", "c": "{ single brace }" }"""
    regexReplacer.replaceVariablesRecursively(plain, pm) shouldBe plain
  }

  @Test
  fun `null input stays null`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    regexReplacer.replaceVariablesRecursively(null, pm) shouldBe null
  }

  @Test
  fun `single unmatched brace pair is not treated as a placeholder`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    // Contains "{{" so it enters the regex path, but "{{ }" has no closing "}}" -> left literal.
    regexReplacer.replaceVariablesRecursively("prefix {{ } suffix", pm) shouldBe "prefix {{ } suffix"
  }
```
- [ ] Existing tests already cover the matched/recursive/cyclic paths and MUST keep passing:
  `dynamic variables replacement in JSON string and dynamic env`, `self-referencing variable does not cause StackOverflowError`, `mutually cyclic variables do not cause StackOverflowError`, `two-level indirection still resolves correctly`, plus all of `RegexReplacerScopesTest`.

### 1.2 Run test against UNCHANGED code (proves the test captures real behavior)
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest"`
- [ ] Expected: **PASS** (these characterize behavior that already holds).

### 1.3 Implement the guard
- [ ] In `RegexReplacer.kt`, the private overload (currently lines 37–69). Add the guard as the first line inside the `?.let` lambda, preserving the pipeline exactly.

Before:
```kotlin
  ): String? = stringWithRegex?.let {
    postManVariableRegex.replace(it) { variable ->
```
After:
```kotlin
  ): String? = stringWithRegex?.let {
    if (!it.contains("{{")) return@let it
    postManVariableRegex.replace(it) { variable ->
```

### 1.4 Re-run + correctness gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS** (green, non-core)

### 1.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(regex): fast-path guard skips regex scan for strings without {{"`

---

## Task 2 — C2: skip static entries in `replaceVariablesInEnv`

### 2.1 Characterization test (mixed placeholder / static / typed entries)
- [ ] Append to `RegexReplacerTest.kt`:

```kotlin
  @Test
  fun `replaceVariablesInEnv resolves placeholder entries and passes static + typed entries through`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm.environment["base"] = "example.com"
    pm.environment["url"] = "https://{{base}}/api"   // value placeholder
    pm.environment["staticStr"] = "no placeholders"   // static string -> unchanged
    pm.environment["count"] = 42                       // non-string -> passed through as-is
    pm.environment["flag"] = true                      // non-string -> passed through as-is
    val result = regexReplacer.replaceVariablesInEnv(pm)
    result shouldContainAll
      mapOf(
        "base" to "example.com",
        "url" to "https://example.com/api",
        "staticStr" to "no placeholders",
        "count" to 42,
        "flag" to true,
      )
  }

  @Test
  fun `replaceVariablesInEnv resolves a placeholder in the key`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm.environment["name"] = "userName"
    pm.environment["{{name}}"] = "value"  // key placeholder -> remapped to resolved key
    val result = regexReplacer.replaceVariablesInEnv(pm)
    result shouldContain ("userName" to "value")
  }
```
- [ ] Existing `unmarshall Env File with Regex and Dynamic variable` (uses `env-with-regex.json`, key `{{un}}` → `userName`, value `user-{{$epoch}}@xyz.com`) MUST keep passing — it exercises BOTH a key placeholder and a value placeholder.

### 2.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest"` → **PASS**

### 2.3 Implement — guard per entry, keep the `associate` FP style + `.toMap()` snapshot
- [ ] In `RegexReplacer.kt`, `replaceVariablesInEnv` (currently lines 135–145). Keep `.toMap()` (write-back at `ReVoman.kt:411` `putAll` depends on the snapshot). Only remap entries whose key or (string) value contains `{{`; pass everything else through unchanged.

Before:
```kotlin
  internal fun replaceVariablesInEnv(pm: PostmanSDK): Map<String, Any?> =
    pm.environment
      .toMap()
      .entries
      .associateBy(
        { replaceVariablesRecursively(it.key, pm)!! },
        {
          if (it.value is String?) replaceVariablesRecursively(it.value as String?, pm)
          else it.value
        },
      )
```
After:
```kotlin
  internal fun replaceVariablesInEnv(pm: PostmanSDK): Map<String, Any?> =
    pm.environment.toMap().entries.associate { (key, value) ->
      val valueHasPlaceholder = value is String && value.contains("{{")
      if (!key.contains("{{") && !valueHasPlaceholder) {
        key to value
      } else {
        replaceVariablesRecursively(key, pm)!! to
          (if (value is String?) replaceVariablesRecursively(value, pm) else value)
      }
    }
```
- [ ] NOTE for reviewer: static entries were already side-effect-free in the old code (the regex only matches — and only fires `recordConsumed`/`setItBackInEnvironment` — when a `{{…}}` is present). The C1 guard already short-circuits the string scan; this C2 change additionally avoids the per-entry lambda + `is String?` dispatch for static entries. Output map is identical (same keys, same values, same types).

### 2.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**. RISK per spec: verify no test relies on every value being round-tripped through type-coercion each step. The env-related integration coverage that MUST stay green: `com.salesforce.revoman.integration.restfulapidev.v3.LedgerRoundTripKtTest`, `com.salesforce.revoman.integration.jarmode.JarModeRevUpKtTest`, plus `RegexReplacerScopesTest` (`environment numeric value resolves and is coerced back to Int on setback`) and `RegexReplacerConsumedTest`.

### 2.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(regex): pass static env entries through without regex remap in replaceVariablesInEnv"`

---

## Task 3 — C5: `HexFormat` encoding + `CharArray` alphanumeric

### 3.1 Characterization test — hex/sha output identical to old `String.format`
- [ ] Append to `src/test/kotlin/com/salesforce/revoman/internal/postman/DynamicVariableGeneratorTest.kt`
  (`getRandomHex` and `randomAlphanumeric` are top-level in package `com.salesforce.revoman.internal.postman`):

```kotlin
  @Test
  fun `getRandomHex output equals the legacy percent-02X formatting for all byte values`() {
    // Locks the exact rendering the old `"%02X".format(v)` produced across the full range.
    (0..255).forEach { v ->
      val legacy = "%02X".format(v)
      val formatted = java.util.HexFormat.of().withUpperCase().toHexDigits(v.toByte())
      assertThat(formatted).isEqualTo(legacy)
    }
  }

  @Test
  fun `randomAlphanumeric returns the requested length using only a-zA-Z0-9`() {
    listOf(0, 1, 15, 64).forEach { len ->
      val out = randomAlphanumeric(len)
      assertThat(out).hasLength(len)
      assertThat(out).matches("[a-zA-Z0-9]*")
    }
  }
```
- [ ] Add a SHA-256 known-vector test in `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderTest.kt`
  (`sha256Hex` is a top-level `internal fun` in package `…template.v3`; add the import
  `import com.salesforce.revoman.internal.postman.template.v3.sha256Hex` is unneeded — same package):

```kotlin
  @Test
  fun sha256HexMatchesKnownVectors() {
    // FIPS-180-2 vectors; also locks lowercase, zero-padded, 64-char output.
    assertThat(sha256Hex("abc"))
      .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertThat(sha256Hex(""))
      .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
  }
```
- [ ] Existing `getRandomHex …` tests (length 2, `[0-9A-F]{2}`, range 00–FF) and `StepSourceHashTest` (`sha256Hex` determinism/inequality) MUST keep passing.

### 3.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.DynamicVariableGeneratorTest" --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest"` → **PASS**
  (verified out-of-band: old `%02x` sha == `HexFormat.of().formatHex`, old `%02X` == `HexFormat.of().withUpperCase().toHexDigits(byte)` for all 0..255).

### 3.3 Implement `DynamicVariableGenerator.kt` (lines 65, 69)
- [ ] Add hoisted upper-case formatter + rewrite the two pure functions.

Before:
```kotlin
fun getRandomHex(): String = "%02X".format(nextInt(256))

private val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')

fun randomAlphanumeric(length: Int) =
  (1..length).map { charPool[nextInt(0, charPool.size)] }.joinToString("")
```
After:
```kotlin
private val upperHexFormat = java.util.HexFormat.of().withUpperCase()

fun getRandomHex(): String = upperHexFormat.toHexDigits(nextInt(256).toByte())

private val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')

fun randomAlphanumeric(length: Int): String =
  CharArray(length) { charPool[nextInt(0, charPool.size)] }.concatToString()
```
- [ ] NOTE: `CharArray(length) { … }` invokes the init lambda for indices `0 until length` in order, one `nextInt` per char — same count/order of RNG draws as the old `(1..length).map`, same charset, same length. Byte-for-byte identical distribution.

### 3.4 Implement `V3Loader.kt` (line 108 `sha256Hex`)
- [ ] Replace the `joinToString`/`%02x` tail with `HexFormat` (lowercase == default).

Before:
```kotlin
internal fun sha256Hex(s: String): String =
  java.security.MessageDigest.getInstance("SHA-256")
    .digest(s.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
```
After:
```kotlin
internal fun sha256Hex(s: String): String =
  java.util.HexFormat.of()
    .formatHex(
      java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    )
```

### 3.5 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.DynamicVariableGeneratorTest" --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 3.6 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(vars): use JDK 21 HexFormat for hex/sha encoding and CharArray for randomAlphanumeric"`

---

## Task 4 — C3: reuse one SnakeYAML reader instance in `V3YamlReader`

### 4.1 Characterization test — sequential reuse parses each doc independently (no state bleed)
- [ ] Append to `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3YamlReaderTest.kt` (guards the shared-instance risk: a reused `Yaml` must not carry anchors/state across `load()` calls):

```kotlin
  @Test
  fun sequentialReadsThroughSharedYamlAreIndependent() {
    val first = V3YamlReader.readRequest(
      """
      ${'$'}kind: http-request
      url: "{{baseUrl}}/one"
      method: GET
      """
        .trimIndent()
    )
    val second = V3YamlReader.readRequest(
      """
      ${'$'}kind: http-request
      url: "{{baseUrl}}/two"
      method: POST
      """
        .trimIndent()
    )
    // Re-read the first shape after the second to prove no cross-call carryover.
    val firstAgain = V3YamlReader.readRequest(
      """
      ${'$'}kind: http-request
      url: "{{baseUrl}}/one"
      method: GET
      """
        .trimIndent()
    )
    assertThat(first.url).isEqualTo("{{baseUrl}}/one")
    assertThat(first.method).isEqualTo("GET")
    assertThat(second.url).isEqualTo("{{baseUrl}}/two")
    assertThat(second.method).isEqualTo("POST")
    assertThat(firstAgain.url).isEqualTo("{{baseUrl}}/one")
    assertThat(firstAgain.method).isEqualTo("GET")
  }
```
- [ ] All existing `V3YamlReaderTest` cases (collectionDef, request, env, YAML 1.1 boolean coercion) MUST keep passing.

### 4.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest"` → **PASS**

### 4.3 Implement — one reader instance (ThreadLocal, future-proof; walk is sequential today)
- [ ] In `V3YamlReader.kt`, hoist a per-thread `Yaml` and use it in `parseYaml` (line ~120). `Yaml` is not thread-safe; `ThreadLocal` keeps reuse safe even if reads are ever parallelized, at zero cost for the sequential walk.

Before:
```kotlin
  private fun parseYaml(yaml: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return (Yaml().load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
  }
```
After:
```kotlin
  private val yamlReader: ThreadLocal<Yaml> = ThreadLocal.withInitial { Yaml() }

  private fun parseYaml(yaml: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return (yamlReader.get().load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
  }
```
- [ ] Do NOT touch `V3YamlWriter` (separate file/worktree; needs its own `DumperOptions`).

### 4.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3YamlReaderTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 4.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(templates): reuse a per-thread SnakeYAML reader instead of new Yaml() per parse"`

---

## Task 5 — C4: single `metadataOrNull` stat per child in `V3Loader.walk`

### 5.1 Characterization test — walk output unchanged (files + subfolders, ordering, auth)
- [ ] The existing `V3LoaderTest` suite already exercises `walk` end-to-end with mixed files/folders, ordering, and auth inheritance:
  `testLoadFlatCollectionOrdersByOrderField` (order among files),
  `testLoadNestedCollectionWithAuthInheritanceAndOverride` (folder + file mix, nesting),
  `testLoadMixedBodies`, `testLoadSubfolderInheritsAuthFromGrandparentDefinition`,
  `testEmptyAuthListDoesNotBlockGrandparentInheritance`.
  These ARE the characterization tests for C4 — no new test needed (a stat-count refactor is invisible to output; the fixtures already cover regular-file entries, directory entries, and their interleaved ordering).
- [ ] (Optional guard) confirm `testLoadFlatCollectionOrdersByOrderField` covers a folder having only request files (no subfolders) and `testLoadNestedCollectionWithAuthInheritanceAndOverride` covers both partitions in one directory.

### 5.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest"` → **PASS**

### 5.3 Implement — materialize `(child, metadata)` once, then filter both partitions
- [ ] In `V3Loader.kt`, `walk` (lines 59–93). Compute metadata once per child; keep the declarative `filter`/`map` style.

Before:
```kotlin
    val children = fs.list(dir)
    val requestEntries: List<Pair<Item, Int>> =
      children
        .filter { fs.metadataOrNull(it)?.isRegularFile == true && it.name.endsWith(REQUEST_SUFFIX) }
        .map { file ->
          ...
        }

    val folderEntries: List<Pair<Item, Int>> =
      children
        .filter { fs.metadataOrNull(it)?.isDirectory == true && hasDef(it, fs) }
        .map { sub ->
          ...
        }
```
After:
```kotlin
    val childrenWithMeta = fs.list(dir).map { it to fs.metadataOrNull(it) }
    val requestEntries: List<Pair<Item, Int>> =
      childrenWithMeta
        .filter { (path, meta) -> meta?.isRegularFile == true && path.name.endsWith(REQUEST_SUFFIX) }
        .map { (file, _) ->
          ...
        }

    val folderEntries: List<Pair<Item, Int>> =
      childrenWithMeta
        .filter { (path, meta) -> meta?.isDirectory == true && hasDef(path, fs) }
        .map { (sub, _) ->
          ...
        }
```
- [ ] Keep the `.map` bodies verbatim (only the destructured parameter name changes: `file`→`(file, _)`, `sub`→`(sub, _)`). This drops each child from up-to-2 `metadataOrNull` stat calls to exactly 1.

### 5.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderTest"` → **PASS**
- [ ] Also run the jar-mode walk coverage: `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.v3.V3LoaderJarTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 5.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(templates): stat each walk child once and partition, instead of 2x metadataOrNull"`

---

## Task 6 — JMH benchmark (measured, not gated)

### 6.1 Write the benchmark
- [ ] Create `src/jmh/kotlin/com/salesforce/revoman/benchmark/RegexVarBenchmark.kt` (WT-0 provides the `jmh` source set + plugin). Measures the two hot paths C1/C2 touch: `replaceVariablesRecursively` over a mix of placeholder / no-placeholder strings, and `replaceVariablesInEnv` over a large env.

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class RegexVarBenchmark {

  private lateinit var regexReplacer: RegexReplacer
  private lateinit var pm: PostmanSDK

  // ~90% of real strings carry no placeholder (headers, static URL segments, literal body fields)
  // -> C1's fast-path guard should dominate the win here.
  private val mixedStrings: List<String> =
    (0 until 100).map { i ->
      if (i % 10 == 0) """{ "id": "{{policyId}}", "when": "{{$'$'}isoTimestamp}}" }"""
      else """{ "field$i": "static-value-$i", "note": "no placeholders in this line" }"""
    }

  @Setup
  fun setup() {
    regexReplacer = RegexReplacer()
    pm = PostmanSDK(initMoshi(), null, regexReplacer)
    pm.environment["policyId"] = "0Pol000000000001"
    // Large env: mostly static entries + a few placeholder entries (exercises C2 static skip).
    (0 until 500).forEach { i ->
      if (i % 25 == 0) pm.environment["k$i"] = "prefix-{{policyId}}-suffix"
      else pm.environment["k$i"] = "static-value-$i"
    }
  }

  @Benchmark
  fun replaceVariablesRecursivelyOverMixedStrings(bh: Blackhole) {
    mixedStrings.forEach { bh.consume(regexReplacer.replaceVariablesRecursively(it, pm)) }
  }

  @Benchmark
  fun replaceVariablesInEnvOverLargeEnv(bh: Blackhole) {
    bh.consume(regexReplacer.replaceVariablesInEnv(pm))
  }
}
```
- [ ] NOTE: replace the `$'$'` marker above with a literal `$` — it denotes the Postman dynamic-var token `{{$isoTimestamp}}` inside a Kotlin triple-quoted string (use `${'$'}` escaping in the actual file). Verify the `jmh` source set can see `internal` symbols; if WT-0 configured `jmh` to depend on `main` output only, `internal` visibility is preserved within the same module/compilation. If the benchmark cannot access `internal`, fall back to driving through the public `PostmanSDK.regexReplacer` accessor (as `RegexReplacerTest` does via `pm.regexReplacer`).

### 6.2 Run the benchmark (measure, do not gate)
- [ ] `./gradlew jmh -Pjmh.includes=RegexVarBenchmark`
- [ ] Record the two average-time numbers in the worktree ledger as the post-fix measurement. Compare against WT-0's baseline capture if present. Expected direction: `replaceVariablesRecursivelyOverMixedStrings` improves substantially (guard skips ~90% of regex scans); `replaceVariablesInEnvOverLargeEnv` improves (static entries skip the per-entry regex path).

### 6.3 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "test(bench): add RegexVarBenchmark for replaceVariablesRecursively + replaceVariablesInEnv"`

---

## Task 7 — Final full gate

- [ ] `./gradlew spotlessApply`
- [ ] `./gradlew build` (full build incl. all unit + assembly)
- [ ] `./gradlew test integrationTest` → **PASS** (green, non-core)
- [ ] Confirm all four owned files changed and NO other source file changed: `git diff --name-only <base>..HEAD` shows only `RegexReplacer.kt`, `V3YamlReader.kt`, `V3Loader.kt`, `DynamicVariableGenerator.kt`, the added test files, and `RegexVarBenchmark.kt`.
- [ ] Record the JMH deltas + the final green gate in the worktree ledger for the merge report.

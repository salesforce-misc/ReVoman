# Perf WT-2: JSON Marshalling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply six FP-preserving marshalling hot-path optimizations (B1–B6) — cache enum constants, hoist the epoch digit-check, reuse the DiMorphic label `Options`, bridge `objToJsonStrToObj` through Moshi value-trees, memoize the default `MoshiReVoman`, and render comment-free JSON bodies with the precision-safe `JsonPretty` — each locked by a characterization test and measured by a JMH benchmark.

**Architecture:** All edits are confined to six files. B1/B2/B3 hoist per-parse allocations to construction-time fields inside existing Moshi `JsonAdapter`s (no signature change). B4 swaps a JSON-string round-trip for `dstAdapter.fromJsonValue(srcAdapter.toJsonValue(input))` — same value flow, one fewer serialize/parse pass. B5 memoizes the empty-config `MoshiReVoman` behind a `by lazy` val, keeping the non-empty path fresh. B6 rewrites `Request.toHttpRequest`'s body-cleansing branch so comment-free bodies re-indent via `JsonPretty.pretty` (byte-preserving) while JSON5 comment stripping keeps the Moshi round-trip, gated on `containsComments`.

**Tech Stack:** Kotlin, Moshi (moshix), Okio, JMH

## Global Constraints
- JDK 21+; branched off WT-0 (has JMH source set at `src/jmh/kotlin/com/salesforce/revoman/benchmark/`)
- Owns ONLY these 6 files — touch no other source file:
  `src/main/kotlin/com/salesforce/revoman/internal/json/factories/CaseInsensitiveEnumAdapter.kt`,
  `src/main/kotlin/com/salesforce/revoman/internal/json/adapters/EpochAdapter.kt`,
  `src/main/kotlin/com/salesforce/revoman/input/json/factories/DiMorphicAdapter.kt`,
  `src/main/kotlin/com/salesforce/revoman/internal/json/MoshiReVoman.kt`,
  `src/main/kotlin/com/salesforce/revoman/input/json/JsonPojoUtils.kt`,
  `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Template.kt`
- Correctness gate: `./gradlew test integrationTest` green (non-core; core IT needs `-PincludeCoreIT` + real org)
- Bench (measured, non-gating): `./gradlew jmh -Pjmh.includes=MarshallingBenchmark`
- Preserve FP style (STYLE.md): Either/map/flatMap/fold, immutable flow, single-expression fns
- `./gradlew spotlessApply` before every commit
---

## Behavior notes read before implementing (two intentional, tested changes)

Most of WT-2 is byte-for-byte behavior-preserving (B1, B2, B3, B5). Two fixes have an
**observable, intentional** delta that the design explicitly anticipates — both are locked by
characterization tests and gated on the full suite:

- **B4 (`objToJsonStrToObj`) untyped-number typing.** The old string round-trip serializes then
  re-parses; when the *target* type is untyped (`Any` / raw `Map`), Moshi parses JSON numbers as
  `Double` (so an `Int 42` becomes `42.0`). The value-tree bridge (`fromJsonValue(toJsonValue(x))`)
  carries the boxed number through the tree, so `Int 42` stays `Int 42`. For **concrete** target
  types (a POJO field typed `Int`/`Long`/`Double`) both paths coerce to the declared type and are
  identical. The consumers are `PostmanEnvironment.getObj`/`getTypedObj` (lines 160, 184). Task 4
  characterizes both (concrete = identical; untyped = the documented Double→typed change) and gates
  on `PostmanEnvironmentTest` + `PostmanEnvironmentEnvVarsTest` + full IT.
- **B6 (comment-free JSON body render) numeric precision.** The old Moshi round-trip collapses
  numbers to `Double` (`5`→`5.0`, large ids lose precision — the exact problem `JsonPretty`'s KDoc
  calls out). Rendering comment-free bodies via `JsonPretty.pretty` copies bytes verbatim, so `5`
  stays `5` and big ids keep full precision. Comment-bearing (JSON5) bodies still go through the
  Moshi round-trip (only it strips comments). Task 6 tests both a comment-bearing and a plain body,
  plus the `moshiReVoman == null` path (`ReVoman.kt:394` calls `toHttpRequest(null)`).

---

## Task 1 — B1: cache enum constants in `CaseInsensitiveEnumAdapter`

**Files:**
- `src/main/kotlin/com/salesforce/revoman/internal/json/factories/CaseInsensitiveEnumAdapter.kt` (fields ~35–37; `fromJson` uses at lines 42, 47)
- New test: `src/test/kotlin/com/salesforce/revoman/internal/json/factories/CaseInsensitiveEnumAdapterTest.kt`

**Interfaces:**
- Consumes: `enumType: Class<T>` (constructor param). Produces: no signature change — an added `private val enumConstants` field reused by `fromJson`.

### 1.1 Characterization test (case-insensitive parse, exact toJson, unknown value throws)
- [ ] Create `CaseInsensitiveEnumAdapterTest.kt` (the factory is wired into the default Moshi via `addLast(CaseInsensitiveEnumAdapter.FACTORY)`, so `initMoshi()` exercises it):

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.factories

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.squareup.moshi.JsonDataException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private enum class Color {
  RED,
  GREEN,
  BLUE,
}

class CaseInsensitiveEnumAdapterTest {
  private val moshi = initMoshi()

  @Test
  fun `parses an exact-case enum name`() {
    assertThat(moshi.fromJson<Color>("\"RED\"", Color::class.java)).isEqualTo(Color.RED)
  }

  @Test
  fun `parses a differently-cased enum name case-insensitively`() {
    assertThat(moshi.fromJson<Color>("\"green\"", Color::class.java)).isEqualTo(Color.GREEN)
    assertThat(moshi.fromJson<Color>("\"BlUe\"", Color::class.java)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `serializes an enum to its canonical name`() {
    assertThat(moshi.toJson(Color.GREEN, sourceType = Color::class.java)).isEqualTo("\"GREEN\"")
  }

  @Test
  fun `throws JsonDataException for an unknown enum value`() {
    assertThrows<JsonDataException> { moshi.fromJson<Color>("\"purple\"", Color::class.java) }
  }
}
```

### 1.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.json.factories.CaseInsensitiveEnumAdapterTest"`
- [ ] Expected: **PASS** (locks current behavior).

### 1.3 Implement — cache `enumConstants` once, reuse in `fromJson`

Before (lines 33–52):
```kotlin
internal class CaseInsensitiveEnumAdapter<T : Enum<T>>(private val enumType: Class<T>) :
  JsonAdapter<T>() {
  private val nameStrings =
    enumType.getEnumConstants().map { Util.jsonName(it.name, enumType.getField(it.name)) }
  private val options = JsonReader.Options.of(*nameStrings.toTypedArray())

  override fun fromJson(reader: JsonReader): T {
    val index = reader.selectString(options)
    return if (index != -1) {
      enumType.getEnumConstants()[index]
    } else if (reader.peek() != JsonReader.Token.STRING) {
      throw JsonDataException("Expected a string but was ${reader.peek()} at path ${reader.path}")
    } else {
      val value = reader.nextString()
      enumType.enumConstants.firstOrNull { it.name.compareTo(value, ignoreCase = true) == 0 }
        ?: throw JsonDataException(
          "Expected one of $nameStrings but was $value at path ${reader.path}"
        )
    }
  }
```
After:
```kotlin
internal class CaseInsensitiveEnumAdapter<T : Enum<T>>(private val enumType: Class<T>) :
  JsonAdapter<T>() {
  // * NOTE: getEnumConstants() clones its backing array on every call; cache it once.
  private val enumConstants: Array<T> = enumType.enumConstants
  private val nameStrings =
    enumConstants.map { Util.jsonName(it.name, enumType.getField(it.name)) }
  private val options = JsonReader.Options.of(*nameStrings.toTypedArray())

  override fun fromJson(reader: JsonReader): T {
    val index = reader.selectString(options)
    return if (index != -1) {
      enumConstants[index]
    } else if (reader.peek() != JsonReader.Token.STRING) {
      throw JsonDataException("Expected a string but was ${reader.peek()} at path ${reader.path}")
    } else {
      val value = reader.nextString()
      enumConstants.firstOrNull { it.name.compareTo(value, ignoreCase = true) == 0 }
        ?: throw JsonDataException(
          "Expected one of $nameStrings but was $value at path ${reader.path}"
        )
    }
  }
```

### 1.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.json.factories.CaseInsensitiveEnumAdapterTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 1.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(json): cache enum constants once in CaseInsensitiveEnumAdapter (avoid per-parse array clone)"`

---

## Task 2 — B2: hoist the epoch digit-check regex in `EpochAdapter`

**Files:**
- `src/main/kotlin/com/salesforce/revoman/internal/json/adapters/EpochAdapter.kt` (line 23)
- New test: `src/test/kotlin/com/salesforce/revoman/internal/json/adapters/EpochAdapterTest.kt`

**Interfaces:**
- Consumes: `reader.nextString()`. Produces: no signature change — an object-level `DIGITS_REGEX` reused per `fromJson`.

**Decision (which of the two options):** use a **hoisted `"\\d+".toRegex()` object-level val**, NOT
`epoch.isNotEmpty() && epoch.all(Char::isDigit)`. Rationale: `\d` in Java regex matches ASCII
`0-9` only, whereas `Char::isDigit` (i.e. `Character.isDigit`) also matches Unicode digits
(Arabic-Indic, etc.). The hoisted regex is therefore **byte-for-byte behavior-identical** to the
current code while removing the per-call `Pattern.compile`. Cleaner in the sense that matters here =
zero behavior risk.

### 2.1 Characterization test (numeric string → epoch Date; non-numeric ISO string → delegate)
- [ ] Create `EpochAdapterTest.kt` (EpochAdapter is registered via `.add(EpochAdapter)`; drive it through a bean with a `Date` field):

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.squareup.moshi.JsonClass
import java.text.SimpleDateFormat
import java.util.Date
import org.junit.jupiter.api.Test

@JsonClass(generateAdapter = true) internal data class BeanWithDate(val date: Date)

class EpochAdapterTest {
  private val moshi = initMoshi()

  @Test
  fun `numeric string is parsed as epoch millis`() {
    val epoch = 1604216172747L
    val bean = moshi.fromJson<BeanWithDate>("{\"date\":\"$epoch\"}", BeanWithDate::class.java)!!
    assertThat(bean.date.toInstant().toEpochMilli()).isEqualTo(epoch)
  }

  @Test
  fun `non-numeric ISO string is delegated to the RFC3339 date adapter`() {
    val bean = moshi.fromJson<BeanWithDate>("{\"date\":\"2015-09-01\"}", BeanWithDate::class.java)!!
    assertThat(bean.date).isEqualTo(SimpleDateFormat("yyyy-MM-dd").parse("2015-09-01"))
  }
}
```
- [ ] Existing coverage that MUST stay green: `JsonPojoUtilsTest.jsonWithEpochDateToPojo` and `jsonWithISODateToPojo` (already exercise both branches through the epoch adapter).

### 2.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.json.adapters.EpochAdapterTest"` → **PASS**

### 2.3 Implement — hoist the regex to an object-level val

Before (lines 18–29):
```kotlin
object EpochAdapter {
  @OptIn(ExperimentalTime::class)
  @FromJson
  fun fromJson(reader: JsonReader, delegate: JsonAdapter<Date>): Date? {
    val epoch = reader.nextString()
    return if (epoch.matches("\\d+".toRegex())) {
      Date.from(Instant.fromEpochMilliseconds(epoch.toLong()).toJavaInstant())
    } else {
      delegate.fromJsonValue(epoch)
    }
  }
}
```
After:
```kotlin
object EpochAdapter {
  // * NOTE: hoisted so the digit pattern is compiled once, not on every date parse.
  //   `\d` keeps ASCII-only semantics identical to the previous inline regex.
  private val DIGITS_REGEX = "\\d+".toRegex()

  @OptIn(ExperimentalTime::class)
  @FromJson
  fun fromJson(reader: JsonReader, delegate: JsonAdapter<Date>): Date? {
    val epoch = reader.nextString()
    return if (epoch.matches(DIGITS_REGEX)) {
      Date.from(Instant.fromEpochMilliseconds(epoch.toLong()).toJavaInstant())
    } else {
      delegate.fromJsonValue(epoch)
    }
  }
}
```

### 2.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.json.adapters.EpochAdapterTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 2.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(json): hoist epoch digit-check regex in EpochAdapter (compile once)"`

---

## Task 3 — B3: build the DiMorphic label `Options` once

**Files:**
- `src/main/kotlin/com/salesforce/revoman/input/json/factories/DiMorphicAdapter.kt` (line 35, inside `findLabelValue`)
- New test: `src/test/kotlin/com/salesforce/revoman/input/json/factories/DiMorphicAdapterTest.kt`

**Interfaces:**
- Consumes: `labelKey: String` (constructor param). Produces: no signature change — an added `private val labelOptions: Options` reused per element instead of `Options.of(labelKey)` per `findLabelValue` call.

### 3.1 Characterization test — multi-element list keeps success/error discrimination
- [ ] The existing `JsonPojoUtilsTest.compositeResponseDiMorphicMarshallUnmarshall` (partial-success fixture = 3 elements ⇒ `findLabelValue` runs repeatedly) and `compositeGraphResponseDiMorphicMarshallUnmarshall` are the primary characterization for B3, plus `CompositeResponseTest`. Add one focused unit test to make the hoist explicit:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.factories

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.ErrorResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.SuccessResponse
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import io.vavr.control.Either
import org.junit.jupiter.api.Test

class DiMorphicAdapterTest {
  private val moshi =
    initMoshi(
      customAdaptersWithType =
        mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER))
    )

  @Test
  fun `discriminates success and error across repeated elements in one list`() {
    val json =
      """
      {
        "compositeResponse": [
          {"referenceId":"ok","httpStatusCode":200,"httpHeaders":{},
           "body":{"done":true,"totalSize":0,"records":[]}},
          {"referenceId":"bad","httpStatusCode":400,"httpHeaders":{},
           "body":[{"errorCode":"INVALID","message":"Invalid reference specified"}]}
        ]
      }
      """
        .trimIndent()
    val response = moshi.fromJson<CompositeResponse>(json, CompositeResponse::class.java)!!
    assertThat(response.compositeResponse[0]).isInstanceOf(SuccessResponse::class.java)
    assertThat(response.compositeResponse[1]).isInstanceOf(ErrorResponse::class.java)
  }
}
```
- [ ] If the exact success/error body shapes above don't match `CompositeResponse.ADAPTER`'s predicate, prefer relying on the existing green `JsonPojoUtilsTest` DiMorphic tests as the characterization and skip this unit test — do NOT invent a fixture the adapter can't parse.

### 3.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.input.json.factories.DiMorphicAdapterTest"` (and/or) `./gradlew test integrationTest --tests "com.salesforce.revoman.input.json.JsonPojoUtilsTest"` → **PASS**

### 3.3 Implement — hoist `labelOptions` to a construction-time field

Before (lines 19–43):
```kotlin
class DiMorphicAdapter
private constructor(
  private val labelKey: String,
  private val successAdapter: Triple<(JsonReader) -> Boolean, Type, JsonAdapter<Any>>,
  private val errorAdapter: Pair<Type, JsonAdapter<Any>>,
) : JsonAdapter<Any>() {
  override fun fromJson(reader: JsonReader): Any? {
    val readerAtLabelKey = findLabelValue(reader.peekJson())
    val jsonAdapter =
      if (readerAtLabelKey.use(successAdapter.first)) successAdapter.third else errorAdapter.second
    return jsonAdapter.fromJson(reader)
  }

  private fun findLabelValue(reader: JsonReader): JsonReader {
    reader.beginObject()
    while (reader.hasNext()) {
      if (reader.selectName(Options.of(labelKey)) == -1) {
        reader.skipName()
        reader.skipValue()
      } else {
        return reader
      }
    }
    throw JsonDataException("Missing label for $labelKey")
  }
```
After:
```kotlin
class DiMorphicAdapter
private constructor(
  private val labelKey: String,
  private val successAdapter: Triple<(JsonReader) -> Boolean, Type, JsonAdapter<Any>>,
  private val errorAdapter: Pair<Type, JsonAdapter<Any>>,
) : JsonAdapter<Any>() {
  // * NOTE: Okio Options are immutable; build the single-name lookup once, not per element.
  private val labelOptions: Options = Options.of(labelKey)

  override fun fromJson(reader: JsonReader): Any? {
    val readerAtLabelKey = findLabelValue(reader.peekJson())
    val jsonAdapter =
      if (readerAtLabelKey.use(successAdapter.first)) successAdapter.third else errorAdapter.second
    return jsonAdapter.fromJson(reader)
  }

  private fun findLabelValue(reader: JsonReader): JsonReader {
    reader.beginObject()
    while (reader.hasNext()) {
      if (reader.selectName(labelOptions) == -1) {
        reader.skipName()
        reader.skipValue()
      } else {
        return reader
      }
    }
    throw JsonDataException("Missing label for $labelKey")
  }
```

### 3.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.input.json.factories.DiMorphicAdapterTest"` → **PASS**
- [ ] `./gradlew test integrationTest --tests "com.salesforce.revoman.input.json.JsonPojoUtilsTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 3.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(json): build DiMorphic label JsonReader.Options once per adapter, not per element"`

---

## Task 4 — B4: bridge `objToJsonStrToObj` through Moshi value-trees

**Files:**
- `src/main/kotlin/com/salesforce/revoman/internal/json/MoshiReVoman.kt` (lines 101–107)
- New test: `src/test/kotlin/com/salesforce/revoman/internal/json/ObjToJsonStrToObjTest.kt`

**Interfaces:**
- Consumes: `input: Any?`, `targetType: Type`. Produces: same `PojoT?` return; internally replaces `dstAdapter.fromJson(toJson(input))` with `dstAdapter.fromJsonValue(srcAdapter.toJsonValue(input))`. Consumers: `PostmanEnvironment.getTypedObj` (line 160) and `getObj` (line 184).

### 4.1 Characterization tests — concrete-typed equivalence + documented untyped delta
- [ ] Create `ObjToJsonStrToObjTest.kt`. The helpers replicate the OLD string path and the NEW
  value-tree path **directly on the public `lenientAdapter` API**, so the equivalence check is
  independent of the edit (it proves whether the swap is safe for each input shape):

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.squareup.moshi.JsonClass
import org.junit.jupiter.api.Test

@JsonClass(generateAdapter = true)
internal data class Vals(
  val i: Int,
  val l: Long,
  val d: Double,
  val b: Boolean,
  val nested: Map<String, Int>,
)

class ObjToJsonStrToObjTest {
  private val moshi = initMoshi()

  private inline fun <reified T : Any> stringRoundTrip(input: Any?): T? =
    moshi
      .lenientAdapter<T>()
      .fromJson(moshi.lenientAdapter<Any>(input?.javaClass ?: Any::class.java).toJson(input))

  private inline fun <reified T : Any> valueTreeBridge(input: Any?): T? =
    moshi
      .lenientAdapter<T>()
      .fromJsonValue(
        moshi.lenientAdapter<Any>(input?.javaClass ?: Any::class.java).toJsonValue(input)
      )

  @Test
  fun `concrete-typed target - value-tree bridge equals string round-trip`() {
    val src = Vals(i = 42, l = 9_000_000_000L, d = 3.5, b = true, nested = mapOf("x" to 7))
    assertThat(valueTreeBridge<Vals>(src)).isEqualTo(stringRoundTrip<Vals>(src))
    assertThat(valueTreeBridge<Vals>(src)).isEqualTo(src)
  }

  @Test
  fun `the actual method round-trips a PostmanEnvironment-style map into a concrete POJO`() {
    val src = mapOf("i" to 42, "l" to 9_000_000_000L, "d" to 3.5, "b" to true, "nested" to mapOf("x" to 7))
    val out = moshi.objToJsonStrToObj<Vals>(src, Vals::class.java)!!
    assertThat(out).isEqualTo(Vals(42, 9_000_000_000L, 3.5, true, mapOf("x" to 7)))
  }

  @Test
  fun `DOCUMENTED - untyped Any target - string path yields Double, value-tree preserves Int`() {
    // Locks the observable delta B4 introduces for UNTYPED retrieval. Run this against BOTH the
    // unchanged and changed tree; the two helpers are edit-independent so it stays green either way.
    // If the observed types differ from the prediction below, UPDATE the assertions to the observed
    // values (this test's job is to pin reality, not the prediction) and re-check the risk note.
    val src = mapOf("i" to 42)
    val fromString = stringRoundTrip<Map<String, Any?>>(src)!!
    val fromTree = valueTreeBridge<Map<String, Any?>>(src)!!
    assertThat(fromString["i"]).isInstanceOf(java.lang.Double::class.java) // 42.0
    assertThat(fromTree["i"]).isInstanceOf(java.lang.Integer::class.java) // 42
  }
}
```
- [ ] Consumer-path gate (concrete targets are the real usage): `PostmanEnvironmentTest`,
  `PostmanEnvironmentEnvVarsTest`, `PostmanEnvironmentUnsteppedTest`, `RegexReplacerScopesTest`
  (`environment numeric value resolves and is coerced back to Int on setback`) MUST stay green.

### 4.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.json.ObjToJsonStrToObjTest"` → **PASS**
  (proves the equivalence helpers characterize real Moshi behavior). If the DOCUMENTED test's
  observed instance types differ from the prediction, adjust the two `isInstanceOf` assertions to
  match what the run reports, then continue.

### 4.3 Implement — value-tree bridge

Before (lines 101–107):
```kotlin
  fun <PojoT : Any> objToJsonStrToObj(
    input: Any?,
    targetType: Type = input?.javaClass ?: Any::class.java,
  ): PojoT? = lenientAdapter<PojoT>(targetType).fromJson(toJson(input))

  inline fun <reified PojoT : Any> objToJsonStrToObj(input: Any?): PojoT? =
    lenientAdapter<PojoT>().fromJson(toJson(input))
```
After:
```kotlin
  // * NOTE: bridge source->target through a Moshi value-tree instead of a JSON-string round-trip;
  //   avoids one full serialize+parse pass. For concrete target types this is identical to the
  //   string path; for UNTYPED (Any/raw Map) targets it preserves the boxed number type (Int stays
  //   Int) instead of coercing to Double. See PostmanEnvironment.getObj/getTypedObj consumers.
  fun <PojoT : Any> objToJsonStrToObj(
    input: Any?,
    targetType: Type = input?.javaClass ?: Any::class.java,
  ): PojoT? =
    lenientAdapter<PojoT>(targetType)
      .fromJsonValue(lenientAdapter<Any>(input?.javaClass ?: Any::class.java).toJsonValue(input))

  inline fun <reified PojoT : Any> objToJsonStrToObj(input: Any?): PojoT? =
    lenientAdapter<PojoT>()
      .fromJsonValue(lenientAdapter<Any>(input?.javaClass ?: Any::class.java).toJsonValue(input))
```

### 4.4 Re-run + gate (this is the B4 risk gate)
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.json.ObjToJsonStrToObjTest"` → **PASS**
- [ ] `./gradlew test --tests "com.salesforce.revoman.output.postman.PostmanEnvironmentTest" --tests "com.salesforce.revoman.output.postman.PostmanEnvironmentEnvVarsTest" --tests "com.salesforce.revoman.output.postman.PostmanEnvironmentUnsteppedTest" --tests "com.salesforce.revoman.internal.postman.RegexReplacerScopesTest"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**. If a test asserts the old untyped-`Double` form and now fails, that is the anticipated B4 delta: decide (a) update the assertion to the value-tree-preserved type (intended), or (b) if a real consumer depends on `Double`, add a `targetType == Any::class.java` guard that keeps the string round-trip for the untyped case only. Record the decision in the ledger.

### 4.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(json): bridge objToJsonStrToObj via Moshi value-tree instead of JSON-string round-trip"`

---

## Task 5 — B5: memoize the default `MoshiReVoman` in `JsonPojoUtils`

**Files:**
- `src/main/kotlin/com/salesforce/revoman/input/json/JsonPojoUtils.kt` (private `initMoshi`, lines 144–151; consumers at 39, 79, 121)
- New test: `src/test/kotlin/com/salesforce/revoman/input/json/JsonPojoUtilsMemoTest.kt`

**Interfaces:**
- Consumes: `customAdapters`, `customAdaptersWithType`, `skipTypes`, `pojoType`. Produces: a `JsonAdapter<PojoT>` — from a memoized default `MoshiReVoman` when all three config args are empty, or a freshly built one otherwise. No public signature change.

### 5.1 Characterization test — empty-config reuse + non-empty freshness
- [ ] Existing coverage already exercises BOTH branches and MUST stay green:
  empty-config (`JsonPojoUtilsTest.jsonFileToPojo`, `simpleJsonToMap`, `pojoToJson`,
  `jsonWithEpochDateToPojo`, `jsonWithISODateToPojo`) and non-empty-config
  (`compositeResponseDiMorphicMarshallUnmarshall`, `compositeGraphResponseDiMorphicMarshallUnmarshall`,
  `sObjectGraphMarshallToPQPayload` — all pass a `customAdapter`).
- [ ] Add an explicit reuse/isolation test:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class JsonPojoUtilsMemoTest {
  @Test
  fun `repeated empty-config parses produce identical results`() {
    val json = """{"key1":"value1","key2":"value2"}"""
    val first = jsonToPojo<Map<String, String>>(Map::class.java, json)
    val second = jsonToPojo<Map<String, String>>(Map::class.java, json)
    assertThat(first).isEqualTo(second)
    assertThat(second).containsExactlyEntriesIn(mapOf("key1" to "value1", "key2" to "value2"))
  }

  @Test
  fun `empty-config and non-empty-config calls interleave without interference`() {
    val json = """{"a":"b"}"""
    val empty1 = jsonToPojo<Map<String, String>>(Map::class.java, json)
    // A non-empty config call must not mutate the memoized default used by empty1/empty2.
    val nested =
      jsonToPojo<Map<String, String>>(
        Map::class.java,
        json,
        customAdapters = emptyList(),
        customAdaptersWithType = emptyMap(),
        skipTypes = setOf(String::class.java),
      )
    val empty2 = jsonToPojo<Map<String, String>>(Map::class.java, json)
    assertThat(empty1).isEqualTo(empty2)
    assertThat(nested).isNotNull()
  }
}
```

### 5.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.input.json.JsonPojoUtilsMemoTest"` → **PASS**

### 5.3 Implement — FP-clean `by lazy` memoization for the empty-config case
- [ ] The memoized default is safe to share because the empty-config branch never calls
  `addAdapters` (the only mutator on `MoshiReVoman.moshi`); `adapter(pojoType)` is read-only. The
  imported companion `initMoshi()` (no-arg) resolves unambiguously — the private overload requires a
  `pojoType` arg, so it cannot bind here. No new import needed (type is inferred).

Before (lines 144–151):
```kotlin
@SuppressWarnings("kotlin:S3923")
private fun <PojoT : Any> initMoshi(
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet(),
  pojoType: Type,
): JsonAdapter<PojoT> =
  initMoshi(customAdapters, customAdaptersWithType, skipTypes).adapter(pojoType)
```
After:
```kotlin
// * NOTE: the empty-config MoshiReVoman never has adapters added (addAdapters is the only mutator),
//   so a single memoized instance is safely shared across all default-config marshalling calls.
private val defaultMoshiReVoman by lazy { initMoshi() }

@SuppressWarnings("kotlin:S3923")
private fun <PojoT : Any> initMoshi(
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
  skipTypes: Set<Class<out Any>> = emptySet(),
  pojoType: Type,
): JsonAdapter<PojoT> =
  (if (customAdapters.isEmpty() && customAdaptersWithType.isEmpty() && skipTypes.isEmpty())
      defaultMoshiReVoman
    else initMoshi(customAdapters, customAdaptersWithType, skipTypes))
    .adapter(pojoType)
```

### 5.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.input.json.JsonPojoUtilsMemoTest"` → **PASS**
- [ ] `./gradlew test integrationTest --tests "com.salesforce.revoman.input.json.JsonPojoUtilsTest" --tests "com.salesforce.revoman.input.json.JsonPojoUtils2Test"` → **PASS**
- [ ] `./gradlew test integrationTest` → **PASS**

### 5.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(json): memoize the empty-config MoshiReVoman in JsonPojoUtils"`

---

## Task 6 — B6: render comment-free JSON bodies via precision-safe `JsonPretty`

**Files:**
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Template.kt` (`Request.toHttpRequest`, body-cleansing branch lines 67–91; `containsComments`/`COMMENT_PATTERN` at 106–115)
- New test: `src/test/kotlin/com/salesforce/revoman/internal/postman/template/RequestToHttpRequestTest.kt`

**Interfaces:**
- Consumes: `body: Body?`, `header: List<Header>`, `moshiReVoman: MoshiReVoman?`. Produces: same `org.http4k.core.Request`; comment-free JSON bodies are re-indented byte-for-byte via `JsonPretty.pretty`, JSON5 comment-bearing bodies still stripped via the Moshi round-trip. The JSON-detection side effect (setting `Content-Type: application/json` when absent) is preserved by validating with `moshiReVoman.fromJson<Any>` before rendering. `toHttpRequest(null)` (ReVoman.kt:394 null-body path) keeps its current behavior.

### 6.1 Characterization tests — comment body, plain body precision, non-JSON, null moshi
- [ ] Create `RequestToHttpRequestTest.kt` (`Request`/`Header`/`Body` are public data classes;
  `toHttpRequest` is `internal`, visible to same-module tests):

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import org.junit.jupiter.api.Test

class RequestToHttpRequestTest {
  private val moshi = initMoshi()

  private fun request(contentType: String?, rawBody: String): Request =
    Request(
      method = "POST",
      header =
        contentType?.let { listOf(Header(key = "Content-Type", value = it)) } ?: emptyList(),
      body = Body(mode = "raw", raw = rawBody),
    )

  @Test
  fun `comment-free JSON body with no content-type is pretty-printed byte-for-byte and content-type is set`() {
    val raw = """{"n":5,"id":1234567890123}"""
    val http = request(contentType = null, rawBody = raw).toHttpRequest(moshi)
    // Precision preserved: 5 stays 5 (not 5.0), large id keeps full precision.
    assertThat(http.bodyString()).contains("\"n\": 5")
    assertThat(http.bodyString()).contains("\"id\": 1234567890123")
    assertThat(http.bodyString()).doesNotContain("5.0")
    assertThat(http.header("Content-Type")).isEqualTo("application/json")
  }

  @Test
  fun `comment-bearing JSON body is round-tripped so comments are stripped`() {
    val raw =
      """
      {
        // a line comment
        "a": 1
      }
      """
        .trimIndent()
    val http = request(contentType = "application/json", rawBody = raw).toHttpRequest(moshi)
    assertThat(http.bodyString()).doesNotContain("// a line comment")
    assertThat(http.bodyString()).contains("\"a\"")
  }

  @Test
  fun `non-JSON body with no content-type is left unchanged and no content-type is added`() {
    val raw = "plain text body, not json"
    val http = request(contentType = null, rawBody = raw).toHttpRequest(moshi)
    assertThat(http.bodyString()).isEqualTo(raw)
    assertThat(http.header("Content-Type")).isNull()
  }

  @Test
  fun `null moshiReVoman with a JSON body and no content-type still adds the content-type header`() {
    // Mirrors ReVoman.kt:394 toHttpRequest(null). Body passes through unchanged (no moshi to render).
    val raw = """{"a":1}"""
    val http = request(contentType = null, rawBody = raw).toHttpRequest(null)
    assertThat(http.bodyString()).isEqualTo(raw)
    assertThat(http.header("Content-Type")).isEqualTo("application/json")
  }
}
```
- [ ] Integration coverage that MUST stay green (request-body rendering end to end): the Pokemon /
  restfulapi.dev collections that POST JSON bodies (run via `./gradlew integrationTest`). If any
  asserts the old `Double`-coerced body form, that is the intended B6 precision fix — update it.

### 6.2 Run against UNCHANGED code
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.RequestToHttpRequestTest"`
- [ ] Expected: the comment-body, non-JSON, and null-moshi tests **PASS**; the
  `comment-free ... byte-for-byte` test **FAILS** against unchanged code (old round-trip renders
  `5.0` / precision-lost id). This RED is intended — it is the failing test that drives B6.

### 6.3 Implement — gate rendering on `containsComments`; validate-then-JsonPretty for comment-free
- [ ] Add the `JsonPretty` import and rewrite the body-cleansing `let` block. Comment-bearing bodies
  keep the Moshi round-trip (it strips comments). Comment-free bodies validate as JSON via
  `moshiReVoman.fromJson<Any>` (same strictness that drove the old detection) and render via
  `JsonPretty.pretty` (byte-preserving). `moshiReVoman == null` returns the raw body and still lets
  `onSuccess` set the content-type — identical to today.

Add import (with the other imports at top of file):
```kotlin
import com.salesforce.revoman.output.json.JsonPretty
```

Before (lines 67–91):
```kotlin
    val cleansedRawBody =
      body?.raw?.trim()?.let {
        when {
          it.isBlank() -> it
          else ->
            // ! TODO 15 Mar 2025 gopala.akshintala: Detect the right content type if absent
            when {
              contentTypeHeader?.value == null ||
                (APPLICATION_JSON.value.equals(contentTypeHeader.value, true) &&
                  containsComments(it)) -> {
                runCatching { moshiReVoman?.jsonToObjToPrettyJson(it, true) ?: it }
                  .onSuccess {
                    if (contentTypeHeader == null) {
                      logger.info {
                        "Detected JSON Content type, adding $APPLICATION_JSON as content-type Header"
                      }
                      contentTypeHeader = APPLICATION_JSON
                    }
                  }
                  .getOrDefault(it)
              }
              else -> it
            }
        }
      } ?: ""
```
After:
```kotlin
    val cleansedRawBody =
      body?.raw?.trim()?.let { rawBody ->
        when {
          rawBody.isBlank() -> rawBody
          else -> {
            // ! TODO 15 Mar 2025 gopala.akshintala: Detect the right content type if absent
            val hasComments = containsComments(rawBody)
            when {
              contentTypeHeader?.value == null ||
                (APPLICATION_JSON.value.equals(contentTypeHeader.value, true) && hasComments) ->
                runCatching {
                    when {
                      // JSON5 comment stripping needs the Moshi round-trip.
                      hasComments -> moshiReVoman?.jsonToObjToPrettyJson(rawBody, true) ?: rawBody
                      // Comment-free: validate as JSON (drives detection), render precision-safe.
                      else ->
                        moshiReVoman?.let { m ->
                          m.fromJson<Any>(rawBody)
                          JsonPretty.pretty(rawBody)
                        } ?: rawBody
                    }
                  }
                  .onSuccess {
                    if (contentTypeHeader == null) {
                      logger.info {
                        "Detected JSON Content type, adding $APPLICATION_JSON as content-type Header"
                      }
                      contentTypeHeader = APPLICATION_JSON
                    }
                  }
                  .getOrDefault(rawBody)
              else -> rawBody
            }
          }
        }
      } ?: ""
```
- [ ] NOTE: `m.fromJson<Any>(rawBody)` throws exactly where the old `jsonToObjToPrettyJson` threw
  (same lenient parse), so a non-JSON body with no content-type still lands in `getOrDefault(rawBody)`
  and does NOT get `application/json` — behavior preserved. `JsonPretty.pretty` never throws (it
  passes malformed input through), so the strict JSON gate MUST remain the Moshi parse, not
  JsonPretty's first-char heuristic.

### 6.4 Re-run + gate
- [ ] `./gradlew test --tests "com.salesforce.revoman.internal.postman.template.RequestToHttpRequestTest"` → **PASS** (the previously-RED byte-for-byte test now passes)
- [ ] `./gradlew test integrationTest` → **PASS** (green, non-core). Investigate any body-assertion diff as the intended precision fix per 6.1.

### 6.5 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "perf(template): render comment-free JSON bodies via precision-safe JsonPretty; keep round-trip only to strip comments"`

---

## Task 7 — JMH benchmark (measured, not gated)

**Files:**
- Create: `src/jmh/kotlin/com/salesforce/revoman/benchmark/MarshallingBenchmark.kt`

**Interfaces:**
- Consumes: WT-0's `jmh` source set + `-Pjmh.includes` wiring. Produces: `fromJson`/`toJson`
  average-time numbers over a representative Salesforce composite response (polymorphic via
  DiMorphic — exercises B3) plus an enum+date bean (exercises B1/B2), driven through the public
  `JsonPojoUtils` entry points (exercises B5). Public API avoids `internal`-visibility concerns.

### 7.1 Write the benchmark
- [ ] Create `src/jmh/kotlin/com/salesforce/revoman/benchmark/MarshallingBenchmark.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse
import com.salesforce.revoman.input.json.jsonToPojo
import com.salesforce.revoman.input.json.pojoToJson
import io.vavr.control.Either
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
open class MarshallingBenchmark {

  // Representative composite query response: polymorphic elements (SuccessResponse/ErrorResponse),
  // records with attributes, nested bodies -> exercises DiMorphicAdapter (B3) + the default adapter
  // stack (B1 enum factory, B2 epoch adapter) via the memoized MoshiReVoman (B5).
  private val compositeJson =
    """
    {
      "compositeResponse": [
        {"referenceId":"ok1","httpStatusCode":200,"httpHeaders":{},
         "body":{"done":true,"totalSize":1,"records":[
           {"attributes":{"type":"Account","url":"/services/data/v58.0/sobjects/Account/001"},
            "Id":"001xx000003DGbXXXX","Name":"Acme","CreatedDate":"2015-09-01T00:00:00.000+0000"}]}},
        {"referenceId":"bad1","httpStatusCode":400,"httpHeaders":{},
         "body":[{"errorCode":"INVALID","message":"Invalid reference specified"}]}
      ]
    }
    """
      .trimIndent()

  private lateinit var composite: CompositeResponse

  @Setup
  fun setup() {
    composite =
      jsonToPojo<CompositeResponse>(
        CompositeResponse::class.java,
        compositeJson,
        customAdaptersWithType =
          mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER)),
      )!!
  }

  @Benchmark
  fun compositeFromJson(bh: Blackhole) {
    bh.consume(
      jsonToPojo<CompositeResponse>(
        CompositeResponse::class.java,
        compositeJson,
        customAdaptersWithType =
          mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER)),
      )
    )
  }

  @Benchmark
  fun compositeToJson(bh: Blackhole) {
    bh.consume(
      pojoToJson<CompositeResponse>(
        CompositeResponse::class.java,
        composite,
        customAdaptersWithType =
          mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER)),
      )
    )
  }
}
```
- [ ] Verify the `jsonToPojo` / `pojoToJson` overloads accept the `customAdaptersWithType` named arg
  as written (they do — see `JsonPojoUtils.kt`). If the `jmh` source set cannot see
  `CompositeResponse.ADAPTER`/`CompositeResponse`, confirm they are public (they are used from Java
  tests as `CompositeResponse.class` / `CompositeResponse.ADAPTER`), or fall back to a plain
  `Map`-typed body that still exercises B1/B2/B5 (drop the DiMorphic arg).

### 7.2 Run the benchmark (measure, do not gate)
- [ ] `./gradlew jmh -Pjmh.includes=MarshallingBenchmark`
- [ ] Record the `compositeFromJson` / `compositeToJson` average-time numbers in the worktree ledger.
  Compare against WT-0's baseline snapshot if present. Expected direction: `fromJson` improves
  (cached enum constants B1, hoisted epoch regex B2, single DiMorphic `Options` B3, memoized default
  B5); `toJson` improves modestly (B1 `toJson` still uses `nameStrings`, B5 default reuse).

### 7.3 Format + commit
- [ ] `./gradlew spotlessApply`
- [ ] `git commit -am "test(bench): add MarshallingBenchmark for composite fromJson/toJson hot path"`

---

## Task 8 — Final full gate

- [ ] `./gradlew spotlessApply`
- [ ] `./gradlew build` (full unit build incl. detekt + assembly)
- [ ] `./gradlew test integrationTest` → **PASS** (green, non-core)
- [ ] Confirm ONLY the six owned source files + the added test/benchmark files changed:
  `git diff --name-only <base>..HEAD` shows only
  `CaseInsensitiveEnumAdapter.kt`, `EpochAdapter.kt`, `DiMorphicAdapter.kt`, `MoshiReVoman.kt`,
  `JsonPojoUtils.kt`, `Template.kt`, the new `*Test.kt` files, and `MarshallingBenchmark.kt`.
- [ ] Record the JMH deltas + the final green gate in the worktree ledger for the merge report.

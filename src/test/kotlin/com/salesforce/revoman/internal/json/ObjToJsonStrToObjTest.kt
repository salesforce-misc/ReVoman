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
    val src =
      mapOf("i" to 42, "l" to 9_000_000_000L, "d" to 3.5, "b" to true, "nested" to mapOf("x" to 7))
    val out = moshi.objToJsonStrToObj<Vals>(src, Vals::class.java)!!
    assertThat(out).isEqualTo(Vals(42, 9_000_000_000L, 3.5, true, mapOf("x" to 7)))
  }

  @Test
  fun `DOCUMENTED - untyped Any target - both string path and value-tree bridge coerce numbers to Double`() {
    // Pins the observable behaviour of B4 for UNTYPED retrieval. Run against BOTH the unchanged and
    // changed tree; the two helpers are edit-independent so it stays green either way.
    // OBSERVED REALITY (differs from the brief's original prediction of Int-preservation): Moshi's
    // untyped `Any`/`Object` adapter reads every number token via `nextDouble()` in BOTH the
    // JSON-string reader AND the value-tree reader, so the untyped `Int 42` becomes `Double 42.0`
    // on BOTH paths. There is therefore NO behaviour delta for the untyped case in this repo's
    // Moshi configuration — the value-tree bridge is fully equivalent to the string round-trip.
    val src = mapOf("i" to 42)
    val fromString = stringRoundTrip<Map<String, Any?>>(src)!!
    val fromTree = valueTreeBridge<Map<String, Any?>>(src)!!
    assertThat(fromString["i"]).isInstanceOf(java.lang.Double::class.java) // 42.0
    assertThat(fromTree["i"])
      .isInstanceOf(java.lang.Double::class.java) // 42.0 (no Int-preservation)
  }
}

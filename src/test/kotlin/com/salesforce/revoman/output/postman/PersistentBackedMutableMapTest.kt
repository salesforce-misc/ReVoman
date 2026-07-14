/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PersistentBackedMutableMapTest {
  @Test
  fun `put get remove behave like a map`() {
    val m = PersistentBackedMutableMap<Any?>()
    m["a"] = 1
    m["b"] = 2
    assertThat(m["a"]).isEqualTo(1)
    assertThat(m.size).isEqualTo(2)
    m.remove("a")
    assertThat(m.containsKey("a")).isFalse()
  }

  @Test
  fun `supports null values`() {
    val m = PersistentBackedMutableMap<Any?>()
    m["k"] = null
    assertThat(m.containsKey("k")).isTrue()
    assertThat(m["k"]).isNull()
  }

  @Test
  fun `snapshot is an O(1) point-in-time view unaffected by later writes`() {
    val m = PersistentBackedMutableMap<Any?>()
    m["a"] = 1
    val snap = m.snapshotView()
    m["b"] = 2 // mutate the live map AFTER snapshot
    assertThat(snap).containsExactlyEntriesIn(mapOf("a" to 1))
    assertThat(m).containsExactlyEntriesIn(mapOf("a" to 1, "b" to 2))
  }

  @Test
  fun `equals and hashCode follow Map contract`() {
    val m = PersistentBackedMutableMap<Any?>()
    m["a"] = 1
    assertThat(m).isEqualTo(mapOf("a" to 1))
    assertThat(m.hashCode()).isEqualTo(mapOf("a" to 1).hashCode())
  }

  @Test
  fun `keys view reflects live state without copying`() {
    val m = PersistentBackedMutableMap<Any?>()
    m["a"] = 1
    val keys = m.keys
    m["b"] = 2
    // read-through view: sees the new key
    assertThat(keys).containsExactly("a", "b")
  }

  @Test
  fun `keys and values view membership delegates to the backing (not a scan)`() {
    // The ledger warm path calls pm.environment.keys.containsAll(produces) per step; the keys view
    // must answer contains via the backing's O(1) containsKey, not AbstractCollection's linear
    // scan.
    val m = PersistentBackedMutableMap<Any?>()
    m["a"] = 1
    m["b"] = 2
    assertThat(m.keys.contains("a")).isTrue()
    assertThat(m.keys.contains("missing")).isFalse()
    assertThat(m.keys.containsAll(listOf("a", "b"))).isTrue()
    assertThat(m.keys.containsAll(listOf("a", "missing"))).isFalse()
    assertThat(m.values.contains(1)).isTrue()
    assertThat(m.values.contains(999)).isFalse()
  }
}

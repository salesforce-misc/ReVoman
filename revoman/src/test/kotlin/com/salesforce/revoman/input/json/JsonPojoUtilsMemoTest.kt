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
    // NOTE: skip a type ABSENT from the payload (Long) so the non-empty-config branch is exercised
    //   (routing through a freshly built MoshiReVoman) without nulling out the Map<String,String>
    //   entries — skipping String here would null the map keys and NPE, independent of memoization.
    val nested =
      jsonToPojo<Map<String, String>>(
        Map::class.java,
        json,
        customAdapters = emptyList(),
        customAdaptersWithType = emptyMap(),
        skipTypes = setOf(Long::class.javaObjectType),
      )
    val empty2 = jsonToPojo<Map<String, String>>(Map::class.java, json)
    assertThat(empty1).isEqualTo(empty2)
    assertThat(nested).isNotNull()
  }
}

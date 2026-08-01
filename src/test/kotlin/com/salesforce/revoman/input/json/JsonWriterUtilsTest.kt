/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okio.Buffer
import org.junit.jupiter.api.Test

class JsonWriterUtilsTest {
  private val anyAdapter = Moshi.Builder().build().adapter(Any::class.java)

  private fun write(block: JsonWriter.() -> Unit): String {
    val buffer = Buffer()
    val writer = JsonWriter.of(buffer)
    writer.serializeNulls = true
    writer.beginObject()
    writer.block()
    writer.endObject()
    return buffer.readUtf8()
  }

  @Test
  fun `string writes value and null`() {
    assertThat(write { string("k", "v") }).isEqualTo("""{"k":"v"}""")
    assertThat(write { string("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `bool writes value and null`() {
    assertThat(write { bool("k", true) }).isEqualTo("""{"k":true}""")
    assertThat(write { bool("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `integer writes value and null`() {
    assertThat(write { integer("k", 7) }).isEqualTo("""{"k":7}""")
    assertThat(write { integer("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `doubl writes value and null`() {
    assertThat(write { doubl("k", 1.5) }).isEqualTo("""{"k":1.5}""")
    assertThat(write { doubl("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `lng writes value and null`() {
    assertThat(write { lng("k", 9L) }).isEqualTo("""{"k":9}""")
    assertThat(write { lng("k", null) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `objW writes a nested object and a null`() {
    assertThat(write { objW("k", "x") { string("inner", "y") } })
      .isEqualTo("""{"k":{"inner":"y"}}""")
    assertThat(write { objW<String>("k", null, fn = {}) }).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `listW writes a list and a null`() {
    val buffer = Buffer()
    val writer = JsonWriter.of(buffer)
    writer.beginObject()
    listW("k", listOf("a", "b"), writer) { writer.value(it) }
    writer.endObject()
    assertThat(buffer.readUtf8()).isEqualTo("""{"k":["a","b"]}""")

    val buffer2 = Buffer()
    val writer2 = JsonWriter.of(buffer2)
    writer2.serializeNulls = true
    writer2.beginObject()
    writer2.name("k")
    listW<String>(null, writer2) { writer2.value(it) }
    writer2.endObject()
    assertThat(buffer2.readUtf8()).isEqualTo("""{"k":null}""")
  }

  @Test
  fun `mapW writes entries and null`() {
    assertThat(write { mapW(mapOf("a" to "1"), anyAdapter) }).isEqualTo("""{"a":"1"}""")
    assertThat(
        write {
          name("k")
          mapW(null, anyAdapter)
        }
      )
      .isEqualTo("""{"k":null}""")
  }
}

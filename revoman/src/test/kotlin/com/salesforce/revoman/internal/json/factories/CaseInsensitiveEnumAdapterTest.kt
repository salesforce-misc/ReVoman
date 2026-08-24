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

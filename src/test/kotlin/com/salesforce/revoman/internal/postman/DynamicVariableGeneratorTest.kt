/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicVariableGeneratorTest {
  @Test
  fun `getRandomHex always returns exactly 2 uppercase hex digits`() {
    repeat(100) {
      val hex = getRandomHex()
      assertThat(hex).hasLength(2)
      assertThat(hex).matches("[0-9A-F]{2}")
    }
  }

  @Test
  fun `getRandomHex can produce FF`() {
    val hexValues = (1..1000).map { getRandomHex() }.toSet()
    assertThat(hexValues).contains("FF")
  }

  @Test
  fun `getRandomHex can produce 00`() {
    val hexValues = (1..1000).map { getRandomHex() }.toSet()
    assertThat(hexValues).contains("00")
  }

  @Test
  fun `getRandomHex produces values in 00 to FF range`() {
    repeat(100) {
      val hex = getRandomHex()
      val value = hex.toInt(16)
      assertThat(value).isAtLeast(0)
      assertThat(value).isAtMost(255)
    }
  }
}

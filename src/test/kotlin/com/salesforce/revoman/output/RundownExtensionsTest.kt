/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RundownExtensionsTest {
  @Test
  fun `endsWith matches when lists are equal`() {
    val list = listOf("A", "B", "C")
    val suffix = listOf("A", "B", "C")
    list.endsWith(suffix) shouldBe true
  }

  @Test
  fun `endsWith matches shorter suffix`() {
    val list = listOf("A", "B", "C", "D")
    val suffix = listOf("C", "D")
    list.endsWith(suffix) shouldBe true
  }

  @Test
  fun `endsWith fails when suffix is longer`() {
    val list = listOf("A", "B")
    val suffix = listOf("A", "B", "C")
    list.endsWith(suffix) shouldBe false
  }

  @Test
  fun `endsWith fails when suffix does not match`() {
    val list = listOf("A", "B", "C", "D")
    val suffix = listOf("B", "C")
    list.endsWith(suffix) shouldBe false
  }

  @Test
  fun `endsWith fails with empty suffix`() {
    val list = listOf("A", "B", "C")
    val suffix = emptyList<String>()
    list.endsWith(suffix) shouldBe false
  }

  @Test
  fun `endsWith matches single element`() {
    val list = listOf("A")
    val suffix = listOf("A")
    list.endsWith(suffix) shouldBe true
  }
}

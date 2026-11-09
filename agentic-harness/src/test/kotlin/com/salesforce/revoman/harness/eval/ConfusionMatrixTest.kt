/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConfusionMatrixTest {
  @Test
  fun `counts correct and off-diagonal predictions`() {
    val pairs =
      listOf(
        "configure" to "configure",
        "price" to "price",
        "price" to "quote", // one off-diagonal miss
        "quote" to "quote",
      )
    val m = ConfusionMatrices.from(pairs)
    assertThat(m.total).isEqualTo(4)
    assertThat(m.correct).isEqualTo(3)
    assertThat(m.accuracy).isWithin(1e-9).of(0.75)
    assertThat(m.counts["price"]!!["quote"]).isEqualTo(1)
    assertThat(m.counts["price"]!!["price"]).isEqualTo(1)
  }

  @Test
  fun `renders a labeled grid and treats a null prediction as none`() {
    val m = ConfusionMatrices.from(listOf("configure" to null, "price" to "price"))
    val text = m.render()
    assertThat(text).contains("price")
    assertThat(text).contains("none")
  }
}

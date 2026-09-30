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

class EvalSetTest {
  @Test
  fun `loads the labeled router eval set`() {
    val cases = EvalSet.load()
    assertThat(cases).hasSize(7)
    assertThat(cases.first()).isEqualTo(EvalCase("configure 2 units of SKU-1", "configure"))
    assertThat(cases.map { it.expected }.toSet()).containsExactly("configure", "price", "quote")
    // The deliberate near-miss: a price intent phrased with the word "quote".
    assertThat(cases.last()).isEqualTo(EvalCase("quote me a price for this config", "price"))
  }
}

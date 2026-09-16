/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ConfidencePolicyTest {
  @Test
  fun `write graphs default to the financial-services 0-90 band`() {
    val policy = ConfidencePolicy()
    assertThat(policy.isWrite("quote")).isTrue()
    assertThat(policy.threshold("quote")).isEqualTo(0.90)
  }

  @Test
  fun `read graphs use the default threshold`() {
    val policy = ConfidencePolicy()
    assertThat(policy.isWrite("configure")).isFalse()
    assertThat(policy.threshold("configure")).isEqualTo(0.60)
  }

  @Test
  fun `per-graph override wins over the write default`() {
    val policy = ConfidencePolicy(perGraphThreshold = mapOf("quote" to 0.50))
    assertThat(policy.threshold("quote")).isEqualTo(0.50)
  }
}

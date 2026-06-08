/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class BigDecimalAdapterTest {
  private val moshi = initMoshi()

  @Test
  fun `serializes a BigDecimal as an exact json number, not a rounded double`() {
    val precise = BigDecimal("1234567890123456789.123456789")
    val json = moshi.toJson(precise, sourceType = BigDecimal::class.java)
    assertThat(json).isEqualTo("1234567890123456789.123456789")
  }

  @Test
  fun `round-trips a BigDecimal without loss`() {
    val precise = BigDecimal("1234567890123456789.123456789")
    val json = moshi.toJson(precise, sourceType = BigDecimal::class.java)
    val back = moshi.fromJson<BigDecimal>(json, BigDecimal::class.java)
    assertThat(back).isEqualTo(precise)
  }

  @Test
  fun `serializes integer-valued BigDecimal without trailing zero`() {
    // The old toDouble() rendered 5 as 5.0; toString() keeps it 5.
    assertThat(moshi.toJson(BigDecimal("5"), sourceType = BigDecimal::class.java)).isEqualTo("5")
  }

  @Test
  fun `serializes negative high-precision BigDecimal verbatim`() {
    val negative = BigDecimal("-1234567890123456789.123456789")
    assertThat(moshi.toJson(negative, sourceType = BigDecimal::class.java))
      .isEqualTo("-1234567890123456789.123456789")
  }

  @Test
  fun `preserves zero scale`() {
    assertThat(moshi.toJson(BigDecimal("0.00"), sourceType = BigDecimal::class.java)).isEqualTo("0.00")
    assertThat(moshi.toJson(BigDecimal("0"), sourceType = BigDecimal::class.java)).isEqualTo("0")
  }
}

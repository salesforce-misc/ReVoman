/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.salesforce.revoman.internal.json.initMoshi
import com.salesforce.revoman.internal.json.moshiReVoman
import com.squareup.moshi.JsonClass
import com.squareup.moshi.adapter
import io.kotest.matchers.shouldBe
import java.text.SimpleDateFormat
import java.util.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdapterTest {
  companion object {
    @BeforeAll
    @JvmStatic
    fun setup() {
      initMoshi()
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `Epoch to Date`() {
    val epochDate = 1604216172747
    val jsonWithEpoch =
      """
      {
        "date" : $epochDate
      }
    """
        .trimIndent()
    val result = moshiReVoman.adapter<BeanWithDate>().fromJson(jsonWithEpoch)
    result?.date?.toInstant()?.toEpochMilli() shouldBe epochDate
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `yyyy-mm-dd to Date`() {
    val dateString = "2024-03-07"
    val jsonWithEpoch =
      """
      {
        "date" : "$dateString"
      }
    """
        .trimIndent()
    val result = moshiReVoman.adapter<BeanWithDate>().fromJson(jsonWithEpoch)
    val formatter = SimpleDateFormat("yyyy-MM-dd")
    result?.date shouldBe formatter.parse(dateString)
  }

  @JsonClass(generateAdapter = true) data class BeanWithDate(val date: Date)
}

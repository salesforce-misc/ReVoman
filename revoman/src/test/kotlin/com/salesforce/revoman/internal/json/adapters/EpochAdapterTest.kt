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
import com.squareup.moshi.JsonClass
import java.text.SimpleDateFormat
import java.util.Date
import org.junit.jupiter.api.Test

@JsonClass(generateAdapter = true) internal data class BeanWithDate(val date: Date)

class EpochAdapterTest {
  private val moshi = initMoshi()

  @Test
  fun `numeric string is parsed as epoch millis`() {
    val epoch = 1604216172747L
    val bean = moshi.fromJson<BeanWithDate>("{\"date\":\"$epoch\"}", BeanWithDate::class.java)!!
    assertThat(bean.date.toInstant().toEpochMilli()).isEqualTo(epoch)
  }

  @Test
  fun `non-numeric ISO string is delegated to the RFC3339 date adapter`() {
    val bean = moshi.fromJson<BeanWithDate>("{\"date\":\"2015-09-01\"}", BeanWithDate::class.java)!!
    assertThat(bean.date).isEqualTo(SimpleDateFormat("yyyy-MM-dd").parse("2015-09-01"))
  }
}

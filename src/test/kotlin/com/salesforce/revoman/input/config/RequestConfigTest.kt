/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import org.junit.jupiter.api.Test

class RequestConfigTest {
  private val pick = PreTxnStepPick { _, _, _ -> true }

  @Test
  fun `unmarshallRequest without adapter leaves customTypeAdapter null`() {
    val config = RequestConfig.unmarshallRequest(pick, String::class.java)
    assertThat(config.preTxnStepPick).isSameInstanceAs(pick)
    assertThat(config.requestType).isEqualTo(String::class.java)
    assertThat(config.customTypeAdapter).isNull()
  }

  @Test
  fun `unmarshallRequest with a JsonAdapter stores it on the left`() {
    val adapter: JsonAdapter<String> = Moshi.Builder().build().adapter(String::class.java)
    val config = RequestConfig.unmarshallRequest(pick, String::class.java, adapter)
    assertThat(config.customTypeAdapter?.isLeft).isTrue()
    assertThat(config.customTypeAdapter?.left).isSameInstanceAs(adapter)
  }

  @Test
  fun `unmarshallRequest with a Factory stores it on the right`() {
    val factory = JsonAdapter.Factory { _, _, _ -> null }
    val config = RequestConfig.unmarshallRequest(pick, String::class.java, factory)
    assertThat(config.customTypeAdapter?.isRight).isTrue()
    assertThat(config.customTypeAdapter?.get()).isSameInstanceAs(factory)
  }
}

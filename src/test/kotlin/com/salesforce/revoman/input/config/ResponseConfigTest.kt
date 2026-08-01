/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.squareup.moshi.JsonAdapter
import org.junit.jupiter.api.Test

class ResponseConfigTest {
  private val pick = PostTxnStepPick { _, _ -> true }
  private val factory = JsonAdapter.Factory { _, _, _ -> null }

  @Test
  fun `unmarshallResponse sets ifSuccess null`() {
    val config = ResponseConfig.unmarshallResponse(pick, String::class.java)
    assertThat(config.ifSuccess).isNull()
    assertThat(config.responseType).isEqualTo(String::class.java)
    assertThat(config.customTypeAdapter).isNull()
  }

  @Test
  fun `unmarshallSuccessResponse sets ifSuccess true`() {
    assertThat(ResponseConfig.unmarshallSuccessResponse(pick, String::class.java).ifSuccess)
      .isTrue()
  }

  @Test
  fun `unmarshallErrorResponse sets ifSuccess false`() {
    assertThat(ResponseConfig.unmarshallErrorResponse(pick, String::class.java).ifSuccess).isFalse()
  }

  @Test
  fun `success response with a Factory stores it on the right`() {
    val config = ResponseConfig.unmarshallSuccessResponse(pick, String::class.java, factory)
    assertThat(config.ifSuccess).isTrue()
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
  }

  @Test
  fun `error response with a Factory stores it on the right`() {
    val config = ResponseConfig.unmarshallErrorResponse(pick, String::class.java, factory)
    assertThat(config.ifSuccess).isFalse()
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
  }

  @Test
  fun `plain response with a Factory keeps ifSuccess null`() {
    val config = ResponseConfig.unmarshallResponse(pick, String::class.java, factory)
    assertThat(config.ifSuccess).isNull()
    assertThat(config.customTypeAdapter!!.isRight).isTrue()
  }
}

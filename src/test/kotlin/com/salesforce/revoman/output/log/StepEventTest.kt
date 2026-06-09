/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StepEventTest {
  @Test
  fun `StepFinished defaults keep new capture fields empty for back-compat`() {
    val e =
      StepEvent.StepFinished(
        path = "auth/login",
        httpStatus = 200,
        produced = setOf("accessToken"),
        consumed = setOf("baseUrl"),
        tookMs = 12,
        outcome = Outcome.SUCCESS,
      )
    assertThat(e.requestMsg).isNull()
    assertThat(e.responseMsg).isNull()
    assertThat(e.producedValues).isEmpty()
    assertThat(e.consumedValues).isEmpty()
  }

  @Test
  fun `StepFinished carries the rendered exchange and value maps`() {
    val e =
      StepEvent.StepFinished(
        path = "auth/login",
        httpStatus = 200,
        produced = setOf("accessToken"),
        consumed = setOf("baseUrl"),
        tookMs = 12,
        outcome = Outcome.SUCCESS,
        requestMsg = "POST /login\n\n{}",
        responseMsg = "HTTP/1.1 200 OK\n\n{}",
        producedValues = mapOf("accessToken" to "tok"),
        consumedValues = mapOf("baseUrl" to "https://x"),
      )
    assertThat(e.requestMsg).isEqualTo("POST /login\n\n{}")
    assertThat(e.responseMsg).isEqualTo("HTTP/1.1 200 OK\n\n{}")
    assertThat(e.producedValues).containsEntry("accessToken", "tok")
    assertThat(e.consumedValues).containsEntry("baseUrl", "https://x")
  }
}

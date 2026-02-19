/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

class PollingConfigTest {

  private val alwaysPick = PostTxnStepPick { _, _ -> true }
  private val noOpRequest = PollingRequestBuilder { _, _ -> Request(Method.GET, "http://test") }
  private val alwaysComplete = PollingCompletionPredicate { _, _ -> true }

  @Test
  fun `builder uses default interval of 2 seconds`() {
    val config = PollingConfig.poll(alwaysPick).request(noOpRequest).until(alwaysComplete)
    config.interval shouldBe Duration.ofSeconds(2)
  }

  @Test
  fun `builder uses default timeout of 30 seconds`() {
    val config = PollingConfig.poll(alwaysPick).request(noOpRequest).until(alwaysComplete)
    config.timeout shouldBe Duration.ofSeconds(30)
  }

  @Test
  fun `every overrides default interval`() {
    val customInterval = Duration.ofMillis(500)
    val config =
      PollingConfig.poll(alwaysPick)
        .request(noOpRequest)
        .every(customInterval)
        .until(alwaysComplete)
    config.interval shouldBe customInterval
  }

  @Test
  fun `timeout overrides default timeout`() {
    val customTimeout = Duration.ofSeconds(10)
    val config =
      PollingConfig.poll(alwaysPick)
        .request(noOpRequest)
        .timeout(customTimeout)
        .until(alwaysComplete)
    config.timeout shouldBe customTimeout
  }

  @Test
  fun `until sets completionPredicate and returns PollingConfig`() {
    val result = PollingConfig.poll(alwaysPick).request(noOpRequest).until(alwaysComplete)
    result.shouldBeInstanceOf<PollingConfig>()
    result.completionPredicate shouldBe alwaysComplete
  }

  @Test
  fun `request sets requestBuilder`() {
    val customRequest = Request(Method.POST, "http://custom-endpoint")
    val builder = PollingRequestBuilder { _, _ -> customRequest }
    val config = PollingConfig.poll(alwaysPick).request(builder).until(alwaysComplete)
    config.requestBuilder shouldBe builder
  }
}

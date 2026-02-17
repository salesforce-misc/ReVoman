/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.StepReport
import java.time.Duration
import org.http4k.core.Request
import org.http4k.core.Response

@ExposedCopyVisibility
data class PollingConfig
internal constructor(
  @JvmField val pick: PostTxnStepPick,
  @JvmField val requestBuilder: PollingRequestBuilder,
  @JvmField val completionPredicate: PollingCompletionPredicate,
  @JvmField val interval: Duration = DEFAULT_INTERVAL,
  @JvmField val timeout: Duration = DEFAULT_TIMEOUT,
) {
  companion object {
    @JvmField val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(2)
    @JvmField val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

    @JvmStatic fun poll(pick: PostTxnStepPick): PollingConfigBuilder = PollingConfigBuilder(pick)
  }
}

fun interface PollingRequestBuilder {
  fun buildRequest(stepReport: StepReport, env: PostmanEnvironment<Any?>): Request
}

fun interface PollingCompletionPredicate {
  fun isComplete(response: Response, env: PostmanEnvironment<Any?>): Boolean
}

class PollingConfigBuilder internal constructor(private val pick: PostTxnStepPick) {
  private lateinit var requestBuilder: PollingRequestBuilder
  private var interval: Duration = PollingConfig.DEFAULT_INTERVAL
  private var timeout: Duration = PollingConfig.DEFAULT_TIMEOUT

  fun request(requestBuilder: PollingRequestBuilder): PollingConfigBuilder = apply {
    this.requestBuilder = requestBuilder
  }

  fun every(interval: Duration): PollingConfigBuilder = apply { this.interval = interval }

  fun timeout(timeout: Duration): PollingConfigBuilder = apply { this.timeout = timeout }

  /** Terminal operation — builds the [PollingConfig] */
  fun until(completionPredicate: PollingCompletionPredicate): PollingConfig =
    PollingConfig(pick, requestBuilder, completionPredicate, interval, timeout)
}

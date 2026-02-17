/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POLLING
import java.time.Duration
import org.http4k.core.Request
import org.http4k.core.Response

sealed class PollingFailure : ExeFailure() {
  override val exeType = POLLING

  data class PollingTimeoutFailure(
    override val failure: Throwable,
    @JvmField val pollAttempts: Int,
    @JvmField val timeout: Duration,
    @JvmField val lastPollResponse: Response?,
  ) : PollingFailure()

  data class PollingRequestFailure(
    override val failure: Throwable,
    @JvmField val pollAttempts: Int,
    @JvmField val failedRequest: Request,
  ) : PollingFailure()
}

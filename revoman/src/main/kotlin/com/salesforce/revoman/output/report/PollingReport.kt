/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import java.time.Duration
import org.http4k.core.Response

data class PollingReport(
  @JvmField val pollAttempts: Int,
  @JvmField val totalDuration: Duration,
  @JvmField val responses: List<Response>,
)

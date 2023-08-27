/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.integration.core.bs

import com.squareup.moshi.JsonClass
import kotlin.Any
import kotlin.Boolean
import kotlin.String

@JsonClass(generateAdapter = true) data class OutputValues(val requestId: String?)

@JsonClass(generateAdapter = true)
data class OrderItemToBSIAResponse(
  val actionName: String?,
  val errors: Any?,
  val isSuccess: Boolean?,
  val outputValues: OutputValues?
)

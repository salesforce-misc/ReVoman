/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.integration.core.pq

import kotlin.Any
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

data class PlaceQuoteOutputRepresentation(
  val quoteId: String,
  val requestIdentifier: String,
  val responseError: List<Any>,
  val statusURL: String,
  val success: Boolean
)

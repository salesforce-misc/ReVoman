/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package org.revcloud.revoman.integration.core.pq

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

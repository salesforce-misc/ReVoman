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

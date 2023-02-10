package org.revcloud.revoman.integration.core

import com.squareup.moshi.JsonClass
import kotlin.Any
import kotlin.Boolean
import kotlin.String

@JsonClass(generateAdapter = true)
data class OutputValues(
  val requestId: String?
)

@JsonClass(generateAdapter = true)
data class OrderItemToBSIAResponse(
  val actionName: String?,
  val errors: Any?,
  val isSuccess: Boolean?,
  val outputValues: OutputValues?
)

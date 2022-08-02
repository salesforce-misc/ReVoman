package org.revcloud.integration.core

import kotlin.Any
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

data class BillingScheduleOutputRepresentation(
  val billingScheduleGroupId: String?,
  val billingSchedulesList: List<String>?,
  val errors: List<Any>?,
  val referenceItemId: String?,
  val success: Boolean?
)

data class BillingScheduleListOutputRepresentation(
  val billingScheduleResultsList: List<BillingScheduleOutputRepresentation>?
)

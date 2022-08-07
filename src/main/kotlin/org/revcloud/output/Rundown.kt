package org.revcloud.output

import org.http4k.core.Response

data class Rundown(
  @JvmField
  val itemNameToResponseWithType: Map<String, StepResponse>,
  @JvmField
  val environment: Map<String, String?>
)

data class StepResponse(val responseObj: Any?, val responseType: Class<out Any>, val responseData: Response) {
  fun isSuccessful() = responseData.status.successful
}

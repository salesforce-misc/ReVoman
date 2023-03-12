package org.revcloud.revoman.output

import org.http4k.core.Request
import org.http4k.core.Response
import java.lang.reflect.Type

data class Rundown(
  @JvmField
  val stepNameToReport: Map<String, StepReport>,
  @JvmField
  val environment: Map<String, String?>
)

data class StepReport(
  val responseObj: Any?,
  val responseType: Type,
  val requestData: Request,
  val responseData: Response,
  val testScriptJsError: Throwable? = null,
  val validationError: Any? = null
) {
  val isSuccessful: Boolean
    get() = responseData.status.successful
}

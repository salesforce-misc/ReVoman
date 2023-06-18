package org.revcloud.revoman.output

import org.http4k.core.Request
import org.http4k.core.Response
import java.lang.reflect.Type

data class Rundown(
  @JvmField
  val stepNameToReport: Map<String, StepReport>,
  @JvmField
  val environment: Map<String, String?>
) {
  val firstUnsuccessfulStepInOrder: String?
    get() = stepNameToReport.entries.firstOrNull { (_, stepReport) -> !stepReport.isSuccessful }?.key
}

data class StepReport(
  val requestData: Request,
  val responseObj: Any? = null,
  val responseType: Type? = null,
  val responseData: Response? = null,
  val httpError: Throwable? = null,
  val testScriptJsError: Throwable? = null,
  val validationError: Any? = null
) {
  val isSuccessful: Boolean
    get() = (responseData?.status?.successful ?: false) && validationError == null && testScriptJsError == null
}

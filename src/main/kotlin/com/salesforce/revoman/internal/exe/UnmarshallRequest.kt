package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.asA
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport
import com.salesforce.revoman.output.Rundown.StepReport.RequestFailure.UnmarshallRequestFailure
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import java.lang.reflect.Type
import org.http4k.core.Request
import org.http4k.format.ConfigurableMoshi

internal fun unmarshallRequest(
  stepName: String,
  pmRequest: com.salesforce.revoman.internal.postman.state.Request,
  kick: Kick,
  moshiReVoman: ConfigurableMoshi,
  stepNameToReport: Map<String, StepReport>
): Either<UnmarshallRequestFailure, TxInfo<Request>> {
  val httpRequest = pmRequest.toHttpRequest()
  val requestType: Type =
    (getRequestConfigForStepName(stepName, kick.stepNameToRequestConfig())
        ?: pickRequestConfig(
          kick.pickToRequestConfig(),
          stepName,
          TxInfo(null, null, httpRequest),
          Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
        ))
      ?.requestType ?: Any::class.java
  return when {
    isContentTypeApplicationJson(httpRequest) ->
      runChecked<Any?>(stepName, StepReport.ExeType.UNMARSHALL_REQUEST) {
          pmRequest.body?.let { body -> moshiReVoman.asA(body.raw, requestType) }
        }
        .mapLeft { UnmarshallRequestFailure(it, TxInfo(requestType, null, httpRequest)) }
    else -> Either.Right(null) // ! TODO 15/10/23 gopala.akshintala: xml2Json
  }.map { TxInfo(requestType, it, pmRequest.toHttpRequest()) }
}
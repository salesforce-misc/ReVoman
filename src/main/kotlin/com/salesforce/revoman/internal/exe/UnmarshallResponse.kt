/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Right
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.ResponseFailure.UnmarshallResponseFailure
import com.squareup.moshi.rawType
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Type
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Response
import org.http4k.format.ConfigurableMoshi

internal fun unmarshallResponse(
  kick: Kick,
  moshiReVoman: ConfigurableMoshi,
  pm: PostmanSDK,
): Either<UnmarshallResponseFailure, TxnInfo<Response>> {
  val httpResponse = pm.currentStepReport.responseInfo!!.get().httpMsg
  return when {
    isJson(httpResponse) -> {
      val httpStatus = httpResponse.status.successful
      val responseConfig =
        (kick.pickToResponseConfig()[httpStatus].orEmpty() +
            kick.pickToResponseConfig()[null].orEmpty())
          .let {
            it.firstOrNull { pick -> pick.postTxnStepPick.pick(pm.currentStepReport, pm.rundown) }
          }
      val currentStep = pm.currentStepReport.step
      val responseType: Type =
        responseConfig
          ?.also { logger.info { "$currentStep ResponseConfig found : ${pprint(it)}" } }
          ?.objType ?: Any::class.java
      val requestInfo = pm.currentStepReport.requestInfo!!.get()
      runCatching(currentStep, UNMARSHALL_RESPONSE) {
          moshiReVoman.asA(httpResponse.bodyString(), responseType.rawType.kotlin)
        }
        .mapLeft {
          UnmarshallResponseFailure(it, requestInfo, TxnInfo(responseType, null, httpResponse))
        }
        .map { TxnInfo(responseType, it, httpResponse) }
    }
    else -> {
      logger.info {
        "${pm.currentStepReport.step} No JSON found in the Response body or content-type didn't match ${APPLICATION_JSON.value}"
      }
      Right(TxnInfo(httpMsg = httpResponse, isJson = false))
    }
  }
}

private val logger = KotlinLogging.logger {}

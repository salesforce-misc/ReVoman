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
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.output.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.UnmarshallRequestFailure
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Type
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Request
import org.http4k.lens.contentType

@JvmSynthetic
internal fun unmarshallRequest(
  currentStep: Step,
  pmRequest: com.salesforce.revoman.internal.postman.template.Request,
  kick: Kick,
  moshiReVoman: MoshiReVoman,
  pm: PostmanSDK,
): Either<UnmarshallRequestFailure, TxnInfo<Request>> {
  val httpRequest = pmRequest.toHttpRequest(moshiReVoman)
  return when {
    httpRequest.bodyString().isNotBlank() &&
      APPLICATION_JSON.value.equals(httpRequest.contentType()?.value, true) -> {
      val requestType: Type =
        kick
          .requestConfig()
          .firstOrNull {
            it.preTxnStepPick.pick(
              currentStep,
              TxnInfo(httpMsg = httpRequest, moshiReVoman = moshiReVoman),
              pm.rundown,
            )
          }
          ?.also { logger.info { "$currentStep RequestConfig found : ${pprint(it)}" } }
          ?.requestType ?: Any::class.java
      runCatching(currentStep, UNMARSHALL_REQUEST) {
          pmRequest.body?.let { body -> moshiReVoman.fromJson<Any>(body.raw, requestType) }
        }
        .mapLeft {
          UnmarshallRequestFailure(
            it,
            TxnInfo(txnObjType = requestType, httpMsg = httpRequest, moshiReVoman = moshiReVoman),
          )
        }
        .map {
          TxnInfo(
            txnObjType = requestType,
            txnObj = it,
            httpMsg = httpRequest,
            moshiReVoman = moshiReVoman,
          )
        }
    }
    else -> {
      // ! TODO 15/10/23 gopala.akshintala: xml2Json
      logger.info {
        "$currentStep Blank Request body or content-type ${httpRequest.contentType()?.value} didn't match ${APPLICATION_JSON.value}"
      }
      Right(TxnInfo(isJson = false, httpMsg = httpRequest, moshiReVoman = moshiReVoman))
    }
  }
}

private val logger = KotlinLogging.logger {}

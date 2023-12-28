/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 *  Version 2.0 For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.output.report

import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PreHookFailure
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.failure.ExeFailure
import com.salesforce.revoman.output.report.failure.RequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure
import io.vavr.control.Either
import org.http4k.core.Request
import org.http4k.core.Response

data class StepReport
private constructor(
  val requestInfo: Either<out RequestFailure, TxInfo<Request>>? = null,
  val preHookFailure: PreHookFailure? = null,
  val responseInfo: Either<out ResponseFailure, TxInfo<Response>>? = null,
  val postHookFailure: PostHookFailure? = null,
  val envSnapshot: PostmanEnvironment<Any?>
) {
  internal constructor(
    requestInfo: arrow.core.Either<RequestFailure, TxInfo<Request>>? = null,
    preHookFailure: PreHookFailure? = null,
    responseInfo: arrow.core.Either<ResponseFailure, TxInfo<Response>>? = null,
    postHookFailure: PostHookFailure? = null,
  ) : this(
    requestInfo?.toVavr(),
    preHookFailure,
    responseInfo?.toVavr(),
    postHookFailure,
    pm.environment.copy()
  )

  val failure: Either<ExeFailure, TxInfo<Response>>? =
    failure(requestInfo, preHookFailure, responseInfo, postHookFailure)

  val exeFailure: ExeFailure? = failure?.fold({ it }, { null })

  val isSuccessful: Boolean = failure == null

  val isHttpStatusSuccessful: Boolean =
    failure?.fold({ it !is RequestFailure.HttpRequestFailure || it is PostHookFailure }, { false }) != false

  companion object {
    private fun failure(
      requestInfo: Either<out ExeFailure, TxInfo<Request>>? = null,
      preHookFailure: PreHookFailure? = null,
      responseInfo: Either<out ExeFailure, TxInfo<Response>>? = null,
      postHookFailure: PostHookFailure? = null,
    ): Either<ExeFailure, TxInfo<Response>>? =
      when {
        requestInfo != null ->
          when (requestInfo) {
            is Either.Left -> Either.left(requestInfo.left)
            else ->
              when {
                preHookFailure != null -> Either.left(preHookFailure)
                responseInfo != null ->
                  when (responseInfo) {
                    is Either.Left -> Either.left(responseInfo.left)
                    is Either.Right ->
                      when {
                        !responseInfo.get().httpMsg.status.successful -> Either.right(responseInfo.get())
                        else ->
                          when {
                            postHookFailure != null -> Either.left(postHookFailure)
                            else -> null
                          }
                      }
                    else -> null
                  }
                else -> null
              }
          }
        else -> null
      }

    private fun <L, R> arrow.core.Either<L, R>.toVavr(): Either<L, R> =
      fold({ Either.left(it) }, { Either.right(it) })
  }

  override fun toString(): String =
    when {
      exeFailure != null -> "❌$exeFailure"
      !isHttpStatusSuccessful ->
        "⚠️️Unsuccessful HTTP Status: ${responseInfo?.get()?.httpMsg?.status} \n${requestInfo?.get()}, ${responseInfo?.get()}"
      else -> "✅${requestInfo?.get()}, ${responseInfo?.get()}"
    }
}

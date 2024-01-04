/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.TxInfo.Companion.uriPathEndsWith
import com.salesforce.revoman.output.report.failure.ExeFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PreHookFailure
import com.salesforce.revoman.output.report.failure.RequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure
import io.vavr.control.Either
import org.http4k.core.Request
import org.http4k.core.Response

data class StepReport
private constructor(
  @JvmField val step: Step,
  @JvmField val requestInfo: Either<out RequestFailure, TxInfo<Request>>? = null,
  @JvmField val preHookFailure: PreHookFailure? = null,
  @JvmField val responseInfo: Either<out ResponseFailure, TxInfo<Response>>? = null,
  @JvmField val postHookFailure: PostHookFailure? = null,
  @JvmField val envSnapshot: PostmanEnvironment<Any?>
) {
  internal constructor(
    step: Step,
    requestInfo: arrow.core.Either<RequestFailure, TxInfo<Request>>? = null,
    preHookFailure: PreHookFailure? = null,
    responseInfo: arrow.core.Either<ResponseFailure, TxInfo<Response>>? = null,
    postHookFailure: PostHookFailure? = null,
  ) : this(
    step,
    requestInfo?.toVavr(),
    preHookFailure,
    responseInfo?.toVavr(),
    postHookFailure,
    pm.environment.copy()
  )

  @JvmField
  val failure: Either<ExeFailure, TxInfo<Response>>? =
    failure(requestInfo, preHookFailure, responseInfo, postHookFailure)

  @JvmField val exeFailure: ExeFailure? = failure?.fold({ it }, { null })

  @JvmField val isSuccessful: Boolean = failure == null

  @JvmField
  val isHttpStatusSuccessful: Boolean = failure?.fold({ it !is PostHookFailure }, { true }) != true

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
                        !responseInfo.get().httpMsg.status.successful ->
                          Either.right(responseInfo.get())
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

    @JvmStatic
    fun Either<out RequestFailure, TxInfo<Request>>?.uriPathEndsWith(path: String): Boolean =
      this?.fold({ false }, { it.uriPathEndsWith(path) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxInfo<Request>>?.containsHeader(key: String): Boolean =
      this?.fold({ false }, { it.containsHeader(key) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxInfo<Request>>?.containsHeader(
      key: String,
      value: String
    ): Boolean = this?.fold({ false }, { it.containsHeader(key, value) }) ?: false
  }

  override fun toString(): String =
    step.toString() +
      when {
        exeFailure != null -> "❌$exeFailure"
        !isHttpStatusSuccessful ->
          "⚠️️Unsuccessful HTTP Status: ${responseInfo?.get()?.httpMsg?.status} \n${requestInfo?.get()}, ${responseInfo?.get()}"
        else -> "✅${requestInfo?.get()}, ${responseInfo?.get()}"
      }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathEndsWith
import com.salesforce.revoman.output.report.failure.ExeFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PreHookFailure
import com.salesforce.revoman.output.report.failure.HttpStatusUnsuccessful
import com.salesforce.revoman.output.report.failure.RequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure
import io.vavr.control.Either
import io.vavr.control.Either.left
import io.vavr.control.Either.right
import org.http4k.core.Request
import org.http4k.core.Response

data class StepReport
private constructor(
  @JvmField val step: Step,
  @JvmField val requestInfo: Either<out RequestFailure, TxnInfo<Request>>? = null,
  @JvmField val preHookFailure: PreHookFailure? = null,
  @JvmField val responseInfo: Either<out ResponseFailure, TxnInfo<Response>>? = null,
  @JvmField val postHookFailure: PostHookFailure? = null,
  @JvmField val envSnapshot: PostmanEnvironment<Any?> = PostmanEnvironment()
) {
  internal constructor(
    step: Step,
    requestInfo: arrow.core.Either<RequestFailure, TxnInfo<Request>>? = null,
    preHookFailure: PreHookFailure? = null,
    responseInfo: arrow.core.Either<ResponseFailure, TxnInfo<Response>>? = null,
    postHookFailure: PostHookFailure? = null
  ) : this(
    step,
    requestInfo?.toVavr(),
    preHookFailure,
    responseInfo?.toVavr(),
    postHookFailure,
  )

  @JvmField
  val failure: Either<ExeFailure, HttpStatusUnsuccessful>? =
    failure(requestInfo, preHookFailure, responseInfo, postHookFailure)

  @JvmField val exeTypeForFailure: ExeType? = failure?.fold({ it.exeType }, { it.exeType })

  @JvmField val exeFailure: ExeFailure? = failure?.fold({ it }, { null })

  @JvmField val isSuccessful: Boolean = failure == null

  @JvmField
  val isHttpStatusSuccessful: Boolean = failure?.fold({ it !is PostHookFailure }, { true }) != true

  companion object {
    private fun failure(
      requestInfo: Either<out ExeFailure, TxnInfo<Request>>? = null,
      preHookFailure: PreHookFailure? = null,
      responseInfo: Either<out ExeFailure, TxnInfo<Response>>? = null,
      postHookFailure: PostHookFailure? = null,
    ): Either<ExeFailure, HttpStatusUnsuccessful>? =
      when {
        requestInfo != null ->
          when (requestInfo) {
            is Either.Left -> left(requestInfo.left)
            else ->
              when {
                preHookFailure != null -> left(preHookFailure)
                responseInfo != null ->
                  when (responseInfo) {
                    is Either.Left -> left(responseInfo.left)
                    is Either.Right ->
                      when {
                        !responseInfo.get().httpMsg.status.successful ->
                          right(HttpStatusUnsuccessful(requestInfo.get(), responseInfo.get()))
                        else ->
                          when {
                            postHookFailure != null -> left(postHookFailure)
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

    fun <L, R> arrow.core.Either<L, R>.toVavr(): Either<L, R> = fold({ left(it) }, { right(it) })

    fun <L, R> Either<L, R>.toArrow(): arrow.core.Either<L, R> = fold({ Left(it) }, { Right(it) })

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.uriPathEndsWith(path: String): Boolean =
      this?.fold({ false }, { it.uriPathEndsWith(path) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.containsHeader(key: String): Boolean =
      this?.fold({ false }, { it.containsHeader(key) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.containsHeader(
      key: String,
      value: String
    ): Boolean = this?.fold({ false }, { it.containsHeader(key, value) }) ?: false
  }

  override fun toString(): String =
    step.toString() +
      when {
        exeFailure != null -> " =>> ❌$exeFailure\n${exeFailure.failure.stackTraceToString()}"
        !isHttpStatusSuccessful ->
          " =>> ⚠️️Unsuccessful HTTP Status: ${responseInfo?.get()?.httpMsg?.status}\n${requestInfo?.get()}\n${responseInfo?.get()}"
        else -> "✅${requestInfo?.get()}\n${responseInfo?.get()}"
      }
}

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathContains
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathEndsWith
import com.salesforce.revoman.output.report.failure.ExeFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PreStepHookFailure
import com.salesforce.revoman.output.report.failure.HttpStatusUnsuccessful
import com.salesforce.revoman.output.report.failure.RequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure
import io.vavr.control.Either
import io.vavr.control.Either.left
import io.vavr.control.Either.right
import org.http4k.core.Request
import org.http4k.core.Response

data class StepReport
internal constructor(
  @JvmField val step: Step,
  @JvmField val requestInfo: Either<out RequestFailure, TxnInfo<Request>>? = null,
  @JvmField val preStepHookFailure: PreStepHookFailure? = null,
  @JvmField val responseInfo: Either<out ResponseFailure, TxnInfo<Response>>? = null,
  @JvmField val postStepHookFailure: PostStepHookFailure? = null,
  @JvmField val pmEnvSnapshot: PostmanEnvironment<Any?>,
) {
  internal constructor(
    step: Step,
    requestInfo: arrow.core.Either<RequestFailure, TxnInfo<Request>>? = null,
    preStepHookFailure: PreStepHookFailure? = null,
    responseInfo: arrow.core.Either<ResponseFailure, TxnInfo<Response>>? = null,
    postStepHookFailure: PostStepHookFailure? = null,
    pmEnvSnapshot: PostmanEnvironment<Any?>,
  ) : this(
    step,
    requestInfo?.toVavr(),
    preStepHookFailure,
    responseInfo?.toVavr(),
    postStepHookFailure,
    pmEnvSnapshot,
  )

  @JvmField
  val failure: Either<ExeFailure, HttpStatusUnsuccessful>? =
    failure(requestInfo, preStepHookFailure, responseInfo, postStepHookFailure)

  @JvmField val exeTypeForFailure: ExeType? = failure?.fold({ it.exeType }, { it.exeType })

  @JvmField val exeFailure: ExeFailure? = failure?.fold({ it }, { null })

  @JvmField val isSuccessful: Boolean = failure == null

  @JvmField
  val isHttpStatusSuccessful: Boolean =
    failure?.fold({ it !is PostStepHookFailure }, { true }) != true

  companion object {
    private fun failure(
      requestInfo: Either<out ExeFailure, TxnInfo<Request>>? = null,
      preStepHookFailure: PreStepHookFailure? = null,
      responseInfo: Either<out ExeFailure, TxnInfo<Response>>? = null,
      postStepHookFailure: PostStepHookFailure? = null,
    ): Either<ExeFailure, HttpStatusUnsuccessful>? =
      when {
        requestInfo != null ->
          when (requestInfo) {
            is Either.Left -> left(requestInfo.left)
            else ->
              when {
                preStepHookFailure != null -> left(preStepHookFailure)
                responseInfo != null ->
                  when (responseInfo) {
                    is Either.Left -> left(responseInfo.left)
                    is Either.Right ->
                      when {
                        !responseInfo.get().httpMsg.status.successful ->
                          right(HttpStatusUnsuccessful(requestInfo.get(), responseInfo.get()))
                        else ->
                          when {
                            postStepHookFailure != null -> left(postStepHookFailure)
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

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.uriPathContains(path: String): Boolean =
      this?.fold({ false }, { it.uriPathContains(path) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.uriPathEndsWith(path: String): Boolean =
      this?.fold({ false }, { it.uriPathEndsWith(path) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.containsHeader(key: String): Boolean =
      this?.fold({ false }, { it.containsHeader(key) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.containsHeader(
      key: String,
      value: String,
    ): Boolean = this?.fold({ false }, { it.containsHeader(key, value) }) ?: false
  }

  override fun toString(): String =
    "$step" +
      when {
        exeFailure != null -> " =>> ❌$exeFailure\n${exeFailure.failure.stackTraceToString()}"
        !isHttpStatusSuccessful ->
          " =>> ⚠️️Unsuccessful HTTP Status: ${responseInfo?.get()?.httpMsg?.status}\n${requestInfo?.get()}\n${responseInfo?.get()}"
        else -> "✅${requestInfo?.get()}\n${responseInfo?.get()}"
      }
}

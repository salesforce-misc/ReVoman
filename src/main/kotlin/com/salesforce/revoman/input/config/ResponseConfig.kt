/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type

data class ResponseConfig
private constructor(
  val postTxnStepPick: PostTxnStepPick,
  val ifSuccess: Boolean?,
  val objType: Type,
  val customTypeAdapter: Either<JsonAdapter<Any>, JsonAdapter.Factory>? = null,
) {
  companion object {
    @JvmStatic
    fun unmarshallResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, null, successType)

    @JvmStatic
    fun unmarshallResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customTypeAdapter: JsonAdapter<Any>
    ): ResponseConfig = ResponseConfig(postTxnStepPick, null, successType, left(customTypeAdapter))

    @JvmStatic
    fun unmarshallResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customTypeAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, null, successType, right(customTypeAdapterFactory))

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, true, successType)

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customTypeAdapter: JsonAdapter<Any>
    ): ResponseConfig = ResponseConfig(postTxnStepPick, true, successType, left(customTypeAdapter))

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customTypeAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, true, successType, right(customTypeAdapterFactory))

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, false, successType)

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customTypeAdapter: JsonAdapter<Any>
    ): ResponseConfig = ResponseConfig(postTxnStepPick, false, successType, left(customTypeAdapter))

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customTypeAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, false, successType, right(customTypeAdapterFactory))
  }
}

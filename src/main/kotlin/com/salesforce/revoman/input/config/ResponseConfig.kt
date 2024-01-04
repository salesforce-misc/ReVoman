/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.vador.config.ValidationConfig
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type

data class ResponseConfig
private constructor(
  val postTxnStepPick: PostTxnStepPick,
  val ifSuccess: Boolean,
  val responseType: Type,
  val customAdapter: Either<JsonAdapter<Any>, JsonAdapter.Factory>? = null,
  val validationConfig: ValidationConfig<*, *>? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, true, successType)

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): ResponseConfig = ResponseConfig(postTxnStepPick, true, successType, left(customAdapter))

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, true, successType, right(customAdapterFactory))

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, false, successType)

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): ResponseConfig = ResponseConfig(postTxnStepPick, false, successType, left(customAdapter))

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, false, successType, right(customAdapterFactory))

    @JvmStatic
    fun validateIfSuccess(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, true, successType, null, validationConfig)

    @JvmStatic
    fun validateIfSuccess(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, true, successType, left(customAdapter), validationConfig)

    @JvmStatic
    fun validateIfSuccess(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(
        postTxnStepPick,
        true,
        successType,
        right(customAdapterFactory),
        validationConfig
      )

    @JvmStatic
    fun validateIfFailed(
      postTxnStepPick: PostTxnStepPick,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): ResponseConfig = ResponseConfig(postTxnStepPick, false, errorType, null, validationConfig)

    @JvmStatic
    fun validateIfFailed(
      postTxnStepPick: PostTxnStepPick,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): ResponseConfig =
      ResponseConfig(postTxnStepPick, false, errorType, left(customAdapter), validationConfig)

    @JvmStatic
    fun validateIfFailed(
      postTxnStepPick: PostTxnStepPick,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): ResponseConfig =
      ResponseConfig(
        postTxnStepPick,
        false,
        errorType,
        right(customAdapterFactory),
        validationConfig
      )
  }
}

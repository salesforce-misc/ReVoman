/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type

data class RequestConfig
internal constructor(
  val preTxnStepPick: PreTxnStepPick,
  val objType: Type,
  val customTypeAdapter: Either<JsonAdapter<out Any>, JsonAdapter.Factory>? = null,
) {
  companion object {
    @JvmStatic
    fun unmarshallRequest(preTxnStepPick: PreTxnStepPick, requestType: Type): RequestConfig =
      RequestConfig(preTxnStepPick, requestType)

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
      customTypeAdapter: JsonAdapter<out Any>,
    ): RequestConfig = RequestConfig(preTxnStepPick, requestType, left(customTypeAdapter))

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
      customTypeAdapterFactory: JsonAdapter.Factory,
    ): RequestConfig = RequestConfig(preTxnStepPick, requestType, right(customTypeAdapterFactory))
  }
}

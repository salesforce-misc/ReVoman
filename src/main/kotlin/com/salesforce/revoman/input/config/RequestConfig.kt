package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type

data class RequestConfig
private constructor(
  val preTxnStepPick: PreTxnStepPick,
  val requestType: Type,
  val customAdapter: Either<JsonAdapter<Any>, JsonAdapter.Factory>? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
    ): RequestConfig = RequestConfig(preTxnStepPick, requestType)

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
      customAdapter: JsonAdapter<Any>
    ): RequestConfig = RequestConfig(preTxnStepPick, requestType, left(customAdapter))

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): RequestConfig = RequestConfig(preTxnStepPick, requestType, right(customAdapterFactory))
  }
}

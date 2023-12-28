package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type

data class RequestConfig
private constructor(
  val pick: Either<String, PreTxnStepPick>,
  val requestType: Type,
  val customAdapter: Either<JsonAdapter<Any>, JsonAdapter.Factory>? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallRequest(
      stepName: String,
      requestType: Type,
    ): Set<RequestConfig> = setOf(RequestConfig(left(stepName), requestType))

    @JvmStatic
    fun unmarshallRequest(
      stepNames: Set<String>,
      requestType: Type,
    ): Set<RequestConfig> = stepNames.flatMap { unmarshallRequest(it, requestType) }.toSet()

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
    ): Set<RequestConfig> = setOf(RequestConfig(right(preTxnStepPick), requestType))

    @JvmStatic
    fun unmarshallRequest(
      stepName: String,
      requestType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<RequestConfig> = setOf(RequestConfig(left(stepName), requestType, left(customAdapter)))

    @JvmStatic
    fun unmarshallRequest(
      stepNames: Set<String>,
      requestType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<RequestConfig> =
      stepNames.flatMap { unmarshallRequest(it, requestType, customAdapter) }.toSet()

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<RequestConfig> =
      setOf(RequestConfig(right(preTxnStepPick), requestType, left(customAdapter)))

    @JvmStatic
    fun unmarshallRequest(
      stepName: String,
      requestType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<RequestConfig> =
      setOf(RequestConfig(left(stepName), requestType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallRequest(
      stepNames: Set<String>,
      requestType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<RequestConfig> =
      stepNames.flatMap { unmarshallRequest(it, requestType, customAdapterFactory) }.toSet()

    @JvmStatic
    fun unmarshallRequest(
      preTxnStepPick: PreTxnStepPick,
      requestType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<RequestConfig> =
      setOf(RequestConfig(right(preTxnStepPick), requestType, right(customAdapterFactory)))
  }
}

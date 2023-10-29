/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
@file:JvmName("ConfigUtils")

package com.salesforce.revoman.input

import com.salesforce.revoman.input.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.HookConfig.HookType
import com.salesforce.revoman.input.HookConfig.HookType.PRE
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type
import java.util.*
import org.http4k.core.Request
import org.immutables.value.Value
import org.immutables.value.Value.Style.ImplementationVisibility.PUBLIC

@Config
@Value.Immutable
internal interface KickDef {
  // * NOTE 29/10/23 gopala.akshintala: `List` coz it allows adding a template twice
  fun templatePaths(): List<String>

  fun environmentPaths(): Set<String>

  fun dynamicEnvironments(): List<Map<String, String>>

  @Value.Derived
  fun dynamicEnvironmentsFlattened(): Map<String, String> =
    if (dynamicEnvironments().isEmpty()) emptyMap()
    else dynamicEnvironments().reduce { acc, map -> acc + map }

  fun customDynamicVariables(): Map<String, (String) -> String>

  fun runOnlySteps(): Set<String>

  fun skipSteps(): Set<String>

  fun haltOnAnyFailureExceptForSteps(): Set<String>

  @Value.Default fun haltOnAnyFailure(): Boolean = false

  fun hooks(): Set<Set<HookConfig>>

  @Value.Derived
  fun hooksFlattened(): Map<HookType, List<HookConfig>> = hooks().flatten().groupBy { it.hookType }

  // * NOTE 29/10/23 gopala.akshintala: requestConfig/responseConfig are decoupled from pre- /
  // post-hook to allow
  // setting up unmarshalling to strong types, on the final rundown, agnostic of whether the step
  // has any hook

  fun requestConfig(): Set<Set<RequestConfig>>

  @Value.Derived
  fun stepNameToRequestConfig(): Map<String, RequestConfig> =
    requestConfig().flatten().associateBy { it.stepName }

  @Value.Derived
  fun customAdaptersFromRequestConfig(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>> =
    stepNameToRequestConfig()
      .values
      .filter { it.customAdapter != null }
      .groupBy({ it.requestType }, { it.customAdapter!! })

  // ! TODO 26/08/23 gopala.akshintala: Validate for duplicate stepNames
  fun responseConfig(): Set<Set<ResponseConfig>>

  @Value.Derived
  fun stepNameToResponseConfig(): Map<Pair<Boolean, String>, ResponseConfig> =
    responseConfig().flatten().associateBy { it.ifSuccess to it.stepName }

  @Value.Derived
  fun customAdaptersFromResponseConfig(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>> =
    responseConfig()
      .flatten()
      .filter { it.customAdapter != null }
      .groupBy({ it.responseType }, { it.customAdapter!! })

  fun customAdapters(): List<Any>

  fun typesToIgnoreForMarshalling(): Set<Class<out Any>>

  @Value.Default fun insecureHttp(): Boolean = false

  @Value.Check
  fun validateConfig() {
    require(
      !haltOnAnyFailure() || (haltOnAnyFailure() && haltOnAnyFailureExceptForSteps().isEmpty())
    ) {
      "'haltOnAnyFailureExceptForSteps' should be empty when 'haltOnAnyFailure' is set to True"
    }
    require(Collections.disjoint(runOnlySteps(), skipSteps())) {
      "'runOnlySteps' and 'skipSteps' cannot have intersection"
    }
  }
  // ! TODO 22/06/23 gopala.akshintala: Validate if validation config for a step is mentioned but
  // the stepName is not present
  // ! TODO 22/06/23 gopala.akshintala: Validate if steps with the same name are used in config
}

data class RequestConfig
private constructor(
  val stepName: String,
  val requestType: Type,
  val customAdapter: Either<JsonAdapter<Any>, Factory>? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallRequest(
      stepName: String,
      requestType: Type,
    ): Set<RequestConfig> = setOf(RequestConfig(stepName, requestType))

    @JvmStatic
    fun unmarshallRequest(
      stepNames: Set<String>,
      requestType: Type,
    ): Set<RequestConfig> = stepNames.flatMap { unmarshallRequest(it, requestType) }.toSet()

    @JvmStatic
    fun unmarshallRequest(
      stepName: String,
      requestType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<RequestConfig> = setOf(RequestConfig(stepName, requestType, left(customAdapter)))

    @JvmStatic
    fun unmarshallRequest(
      stepNames: Set<String>,
      requestType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<RequestConfig> =
      stepNames.flatMap { unmarshallRequest(it, requestType, customAdapter) }.toSet()

    @JvmStatic
    fun unmarshallRequest(
      stepName: String,
      requestType: Type,
      customAdapterFactory: Factory
    ): Set<RequestConfig> = setOf(RequestConfig(stepName, requestType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallRequest(
      stepNames: Set<String>,
      requestType: Type,
      customAdapterFactory: Factory
    ): Set<RequestConfig> =
      stepNames.flatMap { unmarshallRequest(it, requestType, customAdapterFactory) }.toSet()
  }
}

typealias ValidationConfig = BaseValidationConfigBuilder<out Any, out Any?, *, *>

data class ResponseConfig
private constructor(
  val stepName: String,
  val ifSuccess: Boolean,
  val responseType: Type,
  val customAdapter: Either<JsonAdapter<Any>, Factory>? = null,
  val validationConfig: ValidationConfig? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallSuccessResponse(
      stepName: String,
      successType: Type,
    ): Set<ResponseConfig> = setOf(ResponseConfig(stepName, true, successType))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepNames: Set<String>,
      successType: Type,
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType) }.toSet()

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepName: String,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> = setOf(ResponseConfig(stepName, true, successType, left(customAdapter)))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType, customAdapter) }.toSet()

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepName: String,
      successType: Type,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, true, successType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType, customAdapterFactory) }.toSet()

    @JvmStatic
    fun unmarshallErrorResponse(
      stepName: String,
      successType: Type,
    ): Set<ResponseConfig> = setOf(ResponseConfig(stepName, false, successType))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepNames: Set<String>,
      successType: Type,
    ): Set<ResponseConfig> = stepNames.flatMap { unmarshallErrorResponse(it, successType) }.toSet()

    @JvmStatic
    fun unmarshallErrorResponse(
      stepName: String,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, false, successType, left(customAdapter)))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallErrorResponse(it, successType, customAdapter) }.toSet()

    @JvmStatic
    fun unmarshallErrorResponse(
      stepName: String,
      successType: Type,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, false, successType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallErrorResponse(it, successType, customAdapterFactory) }.toSet()

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: ValidationConfig,
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, true, successType, null, validationConfig))

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: ValidationConfig,
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfSuccess(it, successType, validationConfig) }.toSet()

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: ValidationConfig,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, true, successType, left(customAdapter), validationConfig))

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: ValidationConfig,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames
        .flatMap { validateIfSuccess(it, successType, validationConfig, customAdapter) }
        .toSet()

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: ValidationConfig,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(stepName, true, successType, right(customAdapterFactory), validationConfig),
      )

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: ValidationConfig,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      stepNames
        .flatMap { validateIfSuccess(it, successType, validationConfig, customAdapterFactory) }
        .toSet()

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: ValidationConfig,
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, false, errorType, null, validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: ValidationConfig,
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfFailed(it, errorType, validationConfig) }.toSet()

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: ValidationConfig,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, false, errorType, left(customAdapter), validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: ValidationConfig,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfFailed(it, errorType, validationConfig, customAdapter) }.toSet()

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: ValidationConfig,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(stepName, false, errorType, right(customAdapterFactory), validationConfig),
      )

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: ValidationConfig,
      customAdapterFactory: Factory
    ): Set<ResponseConfig> =
      stepNames
        .flatMap { validateIfFailed(it, errorType, validationConfig, customAdapterFactory) }
        .toSet()
  }
}

data class HookConfig
private constructor(val stepName: String, val hookType: HookType, val hook: Hook) {
  enum class HookType {
    PRE,
    POST,
  }

  sealed interface Hook {
    fun interface PreHook : Hook {
      @Throws(Throwable::class)
      fun accept(stepName: String, requestInfo: TxInfo<Request>, rundown: Rundown)
    }

    fun interface PostHook : Hook {
      @Throws(Throwable::class) fun accept(stepName: String, rundown: Rundown)
    }
  }

  companion object {
    @JvmStatic
    fun pre(stepName: String, hook: PreHook): Set<HookConfig> =
      setOf(HookConfig(stepName, PRE, hook))

    @JvmStatic
    fun pre(stepNames: Set<String>, hook: PreHook): Set<HookConfig> =
      stepNames.flatMap { pre(it, hook) }.toSet()

    @JvmStatic
    fun post(stepName: String, hook: PostHook): Set<HookConfig> =
      setOf(HookConfig(stepName, HookType.POST, hook))

    @JvmStatic
    fun post(stepNames: Set<String>, hook: PostHook): Set<HookConfig> =
      stepNames.flatMap { post(it, hook) }.toSet()
  }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Value.Style(
  typeImmutable = "*",
  typeAbstract = ["*Def"],
  builder = "configure",
  build = "off",
  depluralize = true,
  add = "*",
  put = "*",
  visibility = PUBLIC,
)
private annotation class Config

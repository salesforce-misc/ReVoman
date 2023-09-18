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
import com.salesforce.revoman.output.StepReport.TxInfo
import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import java.lang.reflect.Type
import org.http4k.core.Request
import org.immutables.value.Value
import org.immutables.value.Value.Style.ImplementationVisibility.PUBLIC

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String

  @SkipNulls fun runOnlySteps(): Set<String>

  @SkipNulls fun skipSteps(): Set<String>

  fun environmentPath(): String?

  @SkipNulls fun dynamicEnvironment(): Map<String, String>

  @SkipNulls fun customDynamicVariables(): Map<String, (String) -> String>

  fun bearerTokenKey(): String?

  @SkipNulls fun haltOnAnyFailureExceptForSteps(): Set<String>

  @SkipNulls fun hooks(): Set<Set<HookConfig>>

  @Value.Derived
  fun hooksFlattened(): Map<HookType, List<HookConfig>> = hooks().flatten().groupBy { it.hookType }

  // ! TODO 26/08/23 gopala.akshintala: Validate for duplicate stepNames
  @SkipNulls fun responseConfig(): Set<Set<ResponseConfig>>

  @Value.Derived
  fun responseConfigFlattened(): Pair<List<ResponseConfig>, List<ResponseConfig>> =
    responseConfig().flatten().partition { it.ifSuccess }

  @SkipNulls fun customAdaptersForResponse(): List<Any>

  @SkipNulls fun typesInResponseToIgnore(): Set<Class<out Any>>

  @Value.Default fun insecureHttp(): Boolean = false
}

typealias ValidationConfig = BaseValidationConfigBuilder<out Any, out Any?, *, *>

data class ResponseConfig
private constructor(
  val stepName: String,
  val ifSuccess: Boolean,
  val responseType: Type,
  val validationConfig: ValidationConfig? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallSuccessResponse(stepName: String, successType: Type): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, true, successType))

    @JvmStatic
    fun unmarshallSuccessResponse(stepNames: Set<String>, successType: Type): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType) }.toSet()

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> = setOf(ResponseConfig(stepName, true, successType, validationConfig))

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfSuccess(it, successType, validationConfig) }.toSet()

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> = setOf(ResponseConfig(stepName, false, errorType, validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfFailed(it, errorType, validationConfig) }.toSet()
  }
}

data class HookConfig
private constructor(val stepName: String, val hookType: HookType, val hook: Hook) {
  enum class HookType {
    PRE,
    POST,
    // ! TODO 08/23 gopala.akshintala: Support other Hook types
    REQUEST_SUCCESS,
    REQUEST_FAILURE,
    TEST_SCRIPT_JS_FAILURE
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
  put = "*",
  add = "*",
  depluralize = true,
  visibility = PUBLIC
)
private annotation class Config

private annotation class SkipNulls
